// SPDX-License-Identifier: GPL-2.0
/*
 * rayneo_dp_fix_v6 — reversible runtime SBS 3840x1080 enabler for the
 * OPPO OPD2407 V.2158010-2 mediatek-drm driver.
 *
 * Injection chain (carried forward unchanged from the visually verified v5):
 *   1. dp_plat_limit[] row 2 admits 3840x1080@60 / 297 MHz.
 *   2. mtk_dp_intf_config is steered through the 1080p120 clock branch.
 *   3. The common join restores the real 3840-wide DP_INTF timing.
 *   4. video_config / SetMSA receive the real 3840x1080@60 timing row.
 *
 * v6 production-lifecycle change:
 *   - probe registration is completed before the whitelist becomes active;
 *   - module exit restores the stock 2560x1600 whitelist row;
 *   - module exit does not force HPD. The userspace SukiSU controller first
 *     returns the glasses to 2D, reprobes while hooks are still armed, and
 *     only then unloads this module.
 *
 * This remains deliberately firmware-specific. Every internal instruction
 * anchor is checked before any kernel data is changed.
 */
#include <linux/delay.h>
#include <linux/kprobes.h>
#include <linux/module.h>
#include <linux/printk.h>
#include <linux/string.h>
#include <linux/uaccess.h>

extern void mtk_dp_SWInterruptSet(int bstatus);

struct dp_limit_row {
	int hdisplay, vdisplay, vrefresh, clock, valid;
};

static const struct dp_limit_row stock_tbl[10] = {
	{3840, 2160, 60, 594000, 1}, {3840, 2160, 30, 297000, 1},
	{2560, 1600, 60, 268500, 1}, {2560, 1440, 60, 241500, 1},
	{1080, 2460, 60, 174110, 1}, {1920, 1200, 60, 152128, 1},
	{1920, 1080, 120, 297000, 1}, {1920, 1080, 60, 148500, 1},
	{1280, 720, 60, 74250, 1}, {640, 480, 60, 25200, 1},
};

static const struct dp_limit_row sbs_limit_row =
	{3840, 1080, 60, 297000, 1};

#define SCAN_WINDOW (3UL << 20)
#define KP1_OFF 0x4cL
#define KP1_INSN 0x710a02dfU
#define KFIX_OFF 0x218L
#define KFIX_INSN 0xaa1303e0U
#define KV_OFF 0x24L
#define KV_INSN 0xf90007e8U

static void *intf_addr, *vidcfg_addr;
static unsigned long steer_dp_intf;
static unsigned long steer_mtk_dp;
static struct dp_limit_row *limit_tbl;
static bool limit_patched;
static bool kp_msa_registered;

/* Read-only health indicators consumed by the userspace controller. */
module_param_named(whitelist_active, limit_patched, bool, 0444);
MODULE_PARM_DESC(whitelist_active, "SBS dp_plat_limit row is active");
module_param_named(msa_hook_active, kp_msa_registered, bool, 0444);
MODULE_PARM_DESC(msa_hook_active, "SetMSA entry hook registered successfully");

static bool row_equal(const struct dp_limit_row *a,
		      const struct dp_limit_row *b)
{
	return !memcmp(a, b, sizeof(*a));
}

static struct dp_limit_row *find_table(void)
{
	char *start = (char *)&mtk_dp_SWInterruptSet;
	char *p;
	int first;
	struct dp_limit_row buf[10];

	for (p = start; p < start + SCAN_WINDOW; p += sizeof(int)) {
		if (copy_from_kernel_nofault(&first, p, sizeof(first)))
			continue;
		if (first != stock_tbl[0].hdisplay)
			continue;
		if (copy_from_kernel_nofault(buf, p, sizeof(buf)))
			continue;
		if (!memcmp(buf, stock_tbl, sizeof(buf)))
			return (struct dp_limit_row *)p;
		if (!memcmp(buf, stock_tbl, 2 * sizeof(buf[0])) &&
		    row_equal(&buf[2], &sbs_limit_row) &&
		    !memcmp(&buf[3], &stock_tbl[3], 7 * sizeof(buf[0])))
			return (struct dp_limit_row *)p;
	}
	return NULL;
}

static void set_limit_row(struct dp_limit_row *dst,
			  const struct dp_limit_row *src)
{
	/* Keep a partially written row invalid to concurrent mode validation. */
	WRITE_ONCE(dst->valid, 0);
	smp_wmb();
	WRITE_ONCE(dst->hdisplay, src->hdisplay);
	WRITE_ONCE(dst->vdisplay, src->vdisplay);
	WRITE_ONCE(dst->vrefresh, src->vrefresh);
	WRITE_ONCE(dst->clock, src->clock);
	smp_wmb();
	WRITE_ONCE(dst->valid, src->valid);
}

static void activate_limit(void)
{
	if (!limit_tbl)
		return;
	set_limit_row(&limit_tbl[2], &sbs_limit_row);
	WRITE_ONCE(limit_patched, true);
}

static void restore_limit(void)
{
	if (!limit_tbl || !READ_ONCE(limit_patched))
		return;
	set_limit_row(&limit_tbl[2], &stock_tbl[2]);
	WRITE_ONCE(limit_patched, false);
	pr_info("rayneo_v6: stock dp_plat_limit row restored\n");
}

/* kp1 @ intf_config+0x4c */
static int __kprobes steer_pre(struct kprobe *p, struct pt_regs *regs)
{
	u32 w = regs->regs[22], h = regs->regs[21];
	u32 cfgw = 0, cfgh = 0, clock = 0;
	void *cfg = (void *)regs->regs[23];

	if (w != 3840 || h != 1080)
		return 0;
	if (copy_from_kernel_nofault(&cfgw, cfg + 8, 4) ||
	    copy_from_kernel_nofault(&cfgh, cfg + 12, 4) ||
	    copy_from_kernel_nofault(&clock, cfg + 28, 4))
		return 0;
	if (cfgw != 3840 || cfgh != 1080 || clock != 297000)
		return 0;
	WRITE_ONCE(steer_dp_intf, regs->regs[19]);
	regs->regs[22] = 1920;
	*(u32 *)(cfg + 24) = 120;
	return 0;
}

static struct kprobe kp1 = { .pre_handler = steer_pre };

/* intf_config+0x250: preserve the selected 297 MHz clock class, but restore
 * real width and four-pixels-per-clock horizontal timing before DP_SIZE,
 * TGEN, and DP_BUF_RW_TIMES are programmed. */
static int __kprobes intf_fixup_pre(struct kprobe *p, struct pt_regs *regs)
{
	unsigned long dp_intf = READ_ONCE(steer_dp_intf);

	if (!dp_intf || regs->regs[19] != dp_intf)
		return 0;
	if (regs->regs[22] != 1920 || regs->regs[21] != 1080 ||
	    regs->regs[24] != 11 || regs->regs[27] != 37 ||
	    regs->regs[28] != (22U << 16) || regs->regs[23] != 5 ||
	    regs->regs[25] != 36 || regs->regs[26] != (4U << 16)) {
		pr_warn("rayneo_v6: DP_INTF fixup signature mismatch\n");
		WRITE_ONCE(steer_dp_intf, 0);
		return 0;
	}

	regs->regs[22] = 3840;
	regs->regs[24] = 22;
	regs->regs[27] = 74;
	regs->regs[28] = 44U << 16;
	WRITE_ONCE(steer_dp_intf, 0);
	pr_info("rayneo_v6: DP_INTF SBS fixup width=3840 hsw=22 hfp=44 hbp=74\n");
	return 0;
}

static struct kprobe kp_intf_fixup = { .pre_handler = intf_fixup_pre };

static int __kprobes steer_entry(struct kretprobe_instance *ri,
				 struct pt_regs *regs)
{
	u32 *cfg = (u32 *)regs->regs[1];
	u32 w = 0, h = 0, clock = 0;
	unsigned long *slot = (unsigned long *)ri->data;

	*slot = 0;
	if (!cfg)
		return 0;
	if (copy_from_kernel_nofault(&w, &cfg[2], 4) ||
	    copy_from_kernel_nofault(&h, &cfg[3], 4) ||
	    copy_from_kernel_nofault(&clock, &cfg[7], 4))
		return 0;
	if (w == 3840 && h == 1080 && clock == 297000)
		*slot = (unsigned long)cfg;
	return 0;
}

static int __kprobes steer_ret(struct kretprobe_instance *ri,
			       struct pt_regs *regs)
{
	unsigned long cfg = *(unsigned long *)ri->data;

	if (cfg) {
		*(u32 *)(cfg + 24) = 60;
		WRITE_ONCE(steer_dp_intf, 0);
	}
	return 0;
}

static struct kretprobe steer_rkp = {
	.handler = steer_ret,
	.entry_handler = steer_entry,
	.data_size = sizeof(unsigned long),
	.maxactive = 20,
};

/* video_config+0x24: capture the active mtk_dp instance. */
static int __kprobes vcfg_pre(struct kprobe *p, struct pt_regs *regs)
{
	WRITE_ONCE(steer_mtk_dp, regs->regs[0]);
	pr_info("rayneo_v6: vcfg_pre dp=%px\n", (void *)regs->regs[0]);
	return 0;
}

static struct kprobe kp_v = { .pre_handler = vcfg_pre };

static void rayneo_write_sbs_row(u8 *tbl)
{
	*(u16 *)(tbl + 0x04) = 4400;
	*(u16 *)(tbl + 0x06) = 3840;
	*(u16 *)(tbl + 0x0a) = 176;
	*(u16 *)(tbl + 0x0c) = 88;
	*(u16 *)(tbl + 0x10) = 296;
	*(u16 *)(tbl + 0x12) = 1125;
	*(u16 *)(tbl + 0x14) = 1080;
	*(u16 *)(tbl + 0x18) = 4;
	*(u16 *)(tbl + 0x1a) = 5;
	*(u16 *)(tbl + 0x1e) = 36;
	*(u8 *)(tbl + 0x20) = 60;
}

/* mhal_DPTx_SetMSA+0x14: after video_config has written the steered 1080p120
 * row and before SetMSA reads it into registers. */
static int __kprobes msa_pre(struct kprobe *p, struct pt_regs *regs)
{
	unsigned long dp = READ_ONCE(steer_mtk_dp);
	u8 *tbl;

	if (!dp || regs->regs[0] != dp)
		return 0;
	tbl = (u8 *)(dp + 0xfc4);
	if (*(u16 *)(tbl + 0x06) != 1920 || *(u8 *)(tbl + 0x20) != 120)
		return 0;
	rayneo_write_sbs_row(tbl);
	pr_info("rayneo_v6: SBS row active for SetMSA\n");
	return 0;
}

static struct kprobe kp_msa = {
	.symbol_name = "mhal_DPTx_SetMSA",
	.offset = 0x14,
	.pre_handler = msa_pre,
};

/* Late fallback retained from the verified v5 chain. */
static int __kprobes vcfg_ret(struct kretprobe_instance *ri,
			      struct pt_regs *regs)
{
	unsigned long dp = READ_ONCE(steer_mtk_dp);
	u8 *tbl;

	if (!dp)
		return 0;
	tbl = (u8 *)(dp + 0xfc4);
	if (*(u16 *)(tbl + 0x06) != 1920 || *(u8 *)(tbl + 0x20) != 120)
		return 0;
	rayneo_write_sbs_row(tbl);
	return 0;
}

static struct kretprobe vcfg_rkp = {
	.handler = vcfg_ret,
	.entry_handler = NULL,
	.data_size = 0,
	.maxactive = 20,
};

static int __init rayneo_dp_fix_v6_init(void)
{
	u32 insn = 0;
	int ret;
	struct dp_limit_row *tbl = find_table();

	if (!tbl) {
		pr_err("rayneo_dp_fix_v6: dp_plat_limit not found\n");
		return -EINVAL;
	}

	intf_addr = (char *)&mtk_dp_SWInterruptSet - 0x7584L;
	vidcfg_addr = (char *)&mtk_dp_SWInterruptSet - 0x23a0L;

	if (copy_from_kernel_nofault(&insn, (char *)intf_addr + KP1_OFF,
				     sizeof(insn)) || insn != KP1_INSN) {
		pr_err("rayneo_dp_fix_v6: kp1 sig mismatch (%08x)\n", insn);
		return -EINVAL;
	}
	if (copy_from_kernel_nofault(&insn, (char *)intf_addr + KFIX_OFF,
				     sizeof(insn)) || insn != KFIX_INSN) {
		pr_err("rayneo_dp_fix_v6: intf fixup sig mismatch (%08x)\n", insn);
		return -EINVAL;
	}
	if (copy_from_kernel_nofault(&insn, (char *)vidcfg_addr + KV_OFF,
				     sizeof(insn)) || insn != KV_INSN) {
		pr_err("rayneo_dp_fix_v6: kv sig mismatch (%08x)\n", insn);
		return -EINVAL;
	}

	kp_intf_fixup.addr =
		(kprobe_opcode_t *)((char *)intf_addr + KFIX_OFF);
	ret = register_kprobe(&kp_intf_fixup);
	if (ret)
		return ret;

	kp1.addr = (kprobe_opcode_t *)((char *)intf_addr + KP1_OFF);
	ret = register_kprobe(&kp1);
	if (ret)
		goto err_fixup;

	steer_rkp.kp.addr = intf_addr;
	ret = register_kretprobe(&steer_rkp);
	if (ret)
		goto err_kp1;

	kp_v.addr = (kprobe_opcode_t *)((char *)vidcfg_addr + KV_OFF);
	ret = register_kprobe(&kp_v);
	if (ret)
		goto err_steer;

	ret = register_kprobe(&kp_msa);
	if (ret)
		pr_warn("rayneo_v6: kp_msa reg failed %d; continuing without MSA hook\n",
			ret);
	else
		WRITE_ONCE(kp_msa_registered, true);

	vcfg_rkp.kp.addr = vidcfg_addr;
	ret = register_kretprobe(&vcfg_rkp);
	if (ret)
		goto err_msa;

	/* Publish the whitelist only after every mandatory probe is armed. */
	limit_tbl = tbl;
	activate_limit();
	pr_info("rayneo_dp_fix_v6: armed intf=%px vidcfg=%px tbl=%px\n",
		intf_addr, vidcfg_addr, tbl);
	return 0;

err_msa:
	if (READ_ONCE(kp_msa_registered)) {
		unregister_kprobe(&kp_msa);
		WRITE_ONCE(kp_msa_registered, false);
	}
	unregister_kprobe(&kp_v);
err_steer:
	unregister_kretprobe(&steer_rkp);
err_kp1:
	unregister_kprobe(&kp1);
err_fixup:
	unregister_kprobe(&kp_intf_fixup);
	return ret;
}

static void __exit rayneo_dp_fix_v6_exit(void)
{
	unregister_kretprobe(&vcfg_rkp);
	if (READ_ONCE(kp_msa_registered)) {
		unregister_kprobe(&kp_msa);
		WRITE_ONCE(kp_msa_registered, false);
	}
	unregister_kretprobe(&steer_rkp);
	unregister_kprobe(&kp_v);
	unregister_kprobe(&kp1);
	unregister_kprobe(&kp_intf_fixup);
	WRITE_ONCE(steer_dp_intf, 0);
	WRITE_ONCE(steer_mtk_dp, 0);
	msleep(50);
	restore_limit();
	limit_tbl = NULL;
}

module_init(rayneo_dp_fix_v6_init);
module_exit(rayneo_dp_fix_v6_exit);

MODULE_LICENSE("GPL");
MODULE_DESCRIPTION("RayNeo SBS 3840x1080 reversible runtime enabler v6");

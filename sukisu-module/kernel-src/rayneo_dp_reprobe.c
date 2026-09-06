// SPDX-License-Identifier: GPL-2.0
/* One-shot helper. Loading is inert; unloading performs the vendor's tested
 * software disconnect/connect cycle so DPTX discards and rereads EDID. */
#include <linux/delay.h>
#include <linux/module.h>

extern void mtk_dp_SWInterruptSet(int bstatus);

static int __init rayneo_dp_reprobe_init(void)
{
	return 0;
}

static void __exit rayneo_dp_reprobe_exit(void)
{
	mtk_dp_SWInterruptSet(2);
	msleep(600);
	mtk_dp_SWInterruptSet(4);
}

module_init(rayneo_dp_reprobe_init);
module_exit(rayneo_dp_reprobe_exit);

MODULE_LICENSE("GPL");
MODULE_DESCRIPTION("RayNeo DisplayPort one-shot EDID reprobe helper");

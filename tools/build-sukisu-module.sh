#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
module_root="$repo_root/sukisu-module"
output_dir="$repo_root/build/sukisu"
output_zip="$output_dir/ar_glass_plus_dpfix-1.0.0-v6.zip"

for required_tool in jar install mktemp; do
    command -v "$required_tool" >/dev/null || {
        echo "missing required tool: $required_tool" >&2
        exit 1
    }
done

[[ -s "$module_root/kernel/rayneo_dp_fix_v6.ko" ]] || {
    echo "missing kernel module: rayneo_dp_fix_v6.ko" >&2
    exit 1
}
[[ -s "$module_root/kernel/rayneo_dp_reprobe.ko" ]] || {
    echo "missing kernel module: rayneo_dp_reprobe.ko" >&2
    exit 1
}

stage_dir="$(mktemp -d /tmp/ar-glass-plus-sukisu.XXXXXX)"
trap 'rm -rf -- "$stage_dir"' EXIT

install -m 0644 "$module_root/module.prop" "$stage_dir/module.prop"
install -m 0644 "$module_root/skip_mount" "$stage_dir/skip_mount"
install -m 0755 "$module_root/customize.sh" "$stage_dir/customize.sh"
install -m 0755 "$module_root/service.sh" "$stage_dir/service.sh"
install -m 0755 "$module_root/uninstall.sh" "$stage_dir/uninstall.sh"
install -m 0755 "$module_root/action.sh" "$stage_dir/action.sh"
install -d -m 0755 "$stage_dir/bin" "$stage_dir/kernel" "$stage_dir/docs"
install -m 0755 "$module_root/bin/ar-glass-dpctl" "$stage_dir/bin/ar-glass-dpctl"
install -m 0644 "$module_root/kernel/rayneo_dp_fix_v6.ko" "$stage_dir/kernel/rayneo_dp_fix_v6.ko"
install -m 0644 "$module_root/kernel/rayneo_dp_reprobe.ko" "$stage_dir/kernel/rayneo_dp_reprobe.ko"
install -m 0644 "$module_root/README.md" "$stage_dir/README.md"
install -m 0644 "$module_root/docs/HID_PROTOCOL.md" "$stage_dir/docs/HID_PROTOCOL.md"

mkdir -p "$output_dir"
rm -f -- "$output_zip"
jar --create --file "$output_zip" --no-manifest -C "$stage_dir" .

printf 'built %s\n' "$output_zip"

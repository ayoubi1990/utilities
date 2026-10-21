#!/usr/bin/env bash
# Builds the Linux portable package: a tar.gz users unpack anywhere and run velo/bin/velo from.
#   installer/portable/make-portable.sh --version 1.2.0 [--vendor "Your Company"] [--no-video-tools]
#   -> dist/velo-<version>-linux-x64-portable.tar.gz
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
version=0.1.0
args=()
while [ $# -gt 0 ]; do
    case "$1" in
        --version) version="$2"; shift ;;
        *) args+=("$1") ;;
    esac
    shift
done

bash "$root/installer/linux/package.sh" --type app-image --version "$version" "${args[@]}"

out="$root/dist/velo-$version-linux-x64-portable.tar.gz"
tar -czf "$out" -C "$root/dist" velo
echo "Built $out ($(du -m "$out" | cut -f1) MB)"

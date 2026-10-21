#!/usr/bin/env bash
# Packages velo for Linux x64 so end users do not need Java installed.
#
#   installer/linux/package.sh                    app image (folder)  -> dist/velo/bin/velo
#   installer/linux/package.sh --type deb         Debian/Ubuntu       -> dist/velo_<version>_amd64.deb   (needs fakeroot)
#   installer/linux/package.sh --type rpm         Fedora/RHEL/openSUSE -> dist/velo-<version>-1.x86_64.rpm (needs rpm-build)
#
# Options: --version 1.2.0  --vendor "Your Company"  --maintainer "you@example.com"  --no-video-tools
# Needs a JDK 21 (JAVA_HOME or PATH). The portable tar.gz is made by installer/portable/make-portable.sh.
set -euo pipefail

type=app-image version=0.1.0 vendor=velo maintainer="velo@example.com" tools=1
while [ $# -gt 0 ]; do
    case "$1" in
        --type) type="$2"; shift ;;
        --version) version="$2"; shift ;;
        --vendor) vendor="$2"; shift ;;
        --maintainer) maintainer="$2"; shift ;;
        --no-video-tools) tools=0 ;;
        *) echo "unknown option $1" >&2; exit 2 ;;
    esac
    shift
done
case "$type" in app-image|deb|rpm) ;; *) echo "--type must be app-image, deb or rpm" >&2; exit 2 ;; esac

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
source="$root/source"
dist="$root/dist"
work="$root/.cache/linux"
bin="${JAVA_HOME:+$JAVA_HOME/bin/}"

# 1. The single self-contained jar.
bash "$source/build.sh" --version "$version" --vendor "$vendor"

# 2. jpackage copies everything in --input into the app, so stage the jar alone.
rm -rf "$work/input"
mkdir -p "$work/input" "$dist"
cp "$source/velo.jar" "$work/input/"

# 3. The Java modules velo needs (plus run-time-only ones jdeps cannot see).
modules="$("${bin}jdeps" --print-module-deps --ignore-missing-deps --multi-release 21 "$work/input/velo.jar")"
modules="$modules,jdk.crypto.ec,jdk.charsets,jdk.accessibility"
echo "Runtime modules: $modules"

# 4. Video tools, downloaded and checksum-verified.
if [ "$tools" = 1 ]; then
    bash "$here/fetch-tools.sh" "$work/tools"
fi

# 5. jpackage.
common=(
    --name velo
    --app-version "$version"
    --vendor "$vendor"
    --description "Fast, resumable download manager"
    --copyright "Copyright (c) $(date +%Y) $vendor"
    --icon "$here/velo.png"
    --input "$work/input"
    --main-jar velo.jar
    --main-class dev.velo.Main
    --add-modules "$modules"
    --jlink-options "--strip-debug --no-man-pages --no-header-files"
    --java-options -Xmx512m
    --app-content "$source/extension"
    --add-launcher "velo-cli=$here/velo-cli.properties"
    --dest "$dist"
)
if [ "$tools" = 1 ]; then common+=(--app-content "$work/tools"); fi

if [ "$type" = app-image ]; then
    rm -rf "$dist/velo"
    "${bin}jpackage" --type app-image "${common[@]}"
    echo "Built app image: $dist/velo/bin/velo"
    exit 0
fi

linux=(
    --linux-package-name velo
    --linux-app-category net             # Debian section / RPM group
    --linux-menu-group "Network;FileTransfer;"
    --linux-shortcut                     # adds the desktop menu entry
    --install-dir /opt
)
if [ "$type" = deb ]; then
    linux+=(--linux-deb-maintainer "$maintainer")
else
    linux+=(--linux-rpm-license-type "Proprietary")
fi
"${bin}jpackage" --type "$type" "${common[@]}" "${linux[@]}"
echo "Built $type package in $dist"

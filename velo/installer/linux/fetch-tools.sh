#!/usr/bin/env bash
# Downloads the video tools velo bundles for Linux x64, verifies their SHA-256 checksums
# against the publishers' own checksum files, and stages them for packaging.
#
#   yt-dlp           https://github.com/yt-dlp/yt-dlp       (Unlicense; standalone build, Python built in)
#   ffmpeg, ffprobe  https://github.com/BtbN/FFmpeg-Builds  (LGPL 2.1, static build)
#   deno             https://github.com/denoland/deno       (MIT; JavaScript runtime YouTube needs)
#
# Usage: installer/linux/fetch-tools.sh [STAGE_DIR]   (default: .cache/linux/tools)
# Needs curl, python3, sha256sum, tar, xz. Downloads are cached in .cache/downloads.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../.." && pwd)"
cache="$root/.cache/downloads"
stage="${1:-$root/.cache/linux/tools}"
mkdir -p "$cache"

curl_gh() {
    # A token (set in CI) avoids GitHub's anonymous API rate limit.
    if [ -n "${GITHUB_TOKEN:-}" ]; then
        curl -fsSL -H 'User-Agent: velo-build' -H "Authorization: Bearer $GITHUB_TOKEN" "$@"
    else
        curl -fsSL -H 'User-Agent: velo-build' "$@"
    fi
}

# py 'expression over the JSON object d' < file
py() { python3 -c "import json,sys; d=json.load(sys.stdin); print($1)"; }

fetch() { # URL NAME
    if [ ! -f "$cache/$2" ]; then
        echo "Downloading $2"
        curl -fsSL -H 'User-Agent: velo-build' -o "$cache/$2.download" "$1"
        mv "$cache/$2.download" "$cache/$2"
    fi
}

verify() { # NAME EXPECTED_SHA256
    local actual
    actual="$(sha256sum "$cache/$1" | cut -d' ' -f1)"
    if [ "$actual" != "$(echo "$2" | tr 'A-F' 'a-f')" ]; then
        rm -f "$cache/$1"
        echo "Checksum mismatch for $1: expected $2, got $actual (file deleted)" >&2
        exit 1
    fi
    echo "  verified $1"
}

hash_for() { # SUMS_FILE FILE_NAME  -> the hash on the line naming that file
    grep -E "[[:space:]*]$2\$" "$cache/$1" | head -1 | grep -oE '[0-9a-fA-F]{64}'
}

# ---- yt-dlp ----
json="$(curl_gh https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest)"
ytdlp_tag="$(echo "$json" | py 'd["tag_name"]')"
base="https://github.com/yt-dlp/yt-dlp/releases/download/$ytdlp_tag"
fetch "$base/SHA2-256SUMS" "yt-dlp-$ytdlp_tag.SHA2-256SUMS"
fetch "$base/yt-dlp_linux" "yt-dlp-$ytdlp_tag-linux"
verify "yt-dlp-$ytdlp_tag-linux" "$(hash_for "yt-dlp-$ytdlp_tag.SHA2-256SUMS" yt-dlp_linux)"
fetch "https://raw.githubusercontent.com/yt-dlp/yt-dlp/$ytdlp_tag/LICENSE" "yt-dlp-LICENSE.txt"
fetch "https://raw.githubusercontent.com/yt-dlp/yt-dlp/$ytdlp_tag/THIRD_PARTY_LICENSES.txt" "yt-dlp-THIRD_PARTY_LICENSES.txt"

# ---- ffmpeg (LGPL, static) ----
json="$(curl_gh https://api.github.com/repos/BtbN/FFmpeg-Builds/releases/latest)"
# Newest numbered release branch of the static LGPL build, e.g.
# ffmpeg-n9.0.2-31-gc89d6ee1ca-linux64-lgpl-9.0.tar.xz ("-lgpl-shared-" builds are excluded).
read -r ff_id ff_name ff_url sums_url < <(echo "$json" | python3 -c '
import json, re, sys
d = json.load(sys.stdin)
pat = re.compile(r"^ffmpeg-n(\d+)\.(\d+)\S*-linux64-lgpl-\d+\.\d+\.tar\.xz$")
builds = [a for a in d["assets"] if pat.match(a["name"])]
if not builds:
    sys.exit(0)
best = max(builds, key=lambda a: tuple(map(int, pat.match(a["name"]).groups())))
sums = next(a for a in d["assets"] if a["name"] == "checksums.sha256")
print(d["id"], best["name"], best["browser_download_url"], sums["browser_download_url"])
') || true
if [ -z "${ff_name:-}" ]; then
    echo "No static LGPL linux64 ffmpeg build found in the latest BtbN release" >&2
    exit 1
fi
# BtbN republishes "latest" builds daily under the same name, so cache them per release.
fetch "$sums_url" "ffmpeg-checksums-$ff_id.sha256"
fetch "$ff_url" "ffmpeg-$ff_id-$ff_name"
verify "ffmpeg-$ff_id-$ff_name" "$(hash_for "ffmpeg-checksums-$ff_id.sha256" "$ff_name")"

# ---- deno ----
json="$(curl_gh https://api.github.com/repos/denoland/deno/releases/latest)"
deno_tag="$(echo "$json" | py 'd["tag_name"]')"
base="https://github.com/denoland/deno/releases/download/$deno_tag"
fetch "$base/deno-x86_64-unknown-linux-gnu.zip" "deno-$deno_tag-linux.zip"
fetch "$base/deno-x86_64-unknown-linux-gnu.zip.sha256sum" "deno-$deno_tag-linux.zip.sha256sum"
verify "deno-$deno_tag-linux.zip" "$(grep -oE '[0-9a-fA-F]{64}' "$cache/deno-$deno_tag-linux.zip.sha256sum" | head -1)"
fetch "https://raw.githubusercontent.com/denoland/deno/$deno_tag/LICENSE.md" "deno-LICENSE.md"

# ---- stage ----
rm -rf "$stage"
mkdir -p "$stage/licenses"
install -m 755 "$cache/yt-dlp-$ytdlp_tag-linux" "$stage/yt-dlp"
cp "$cache/yt-dlp-LICENSE.txt" "$cache/yt-dlp-THIRD_PARTY_LICENSES.txt" "$stage/licenses/"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
tar -xJf "$cache/ffmpeg-$ff_id-$ff_name" -C "$tmp"
ffdir="$(find "$tmp" -mindepth 1 -maxdepth 1 -type d | head -1)"
install -m 755 "$ffdir/bin/ffmpeg" "$ffdir/bin/ffprobe" "$stage/"
cp "$ffdir/LICENSE.txt" "$stage/licenses/ffmpeg-LICENSE.txt"

python3 -m zipfile -e "$cache/deno-$deno_tag-linux.zip" "$tmp/deno"
install -m 755 "$tmp/deno/deno" "$stage/deno"
cp "$cache/deno-LICENSE.md" "$stage/licenses/"

cat > "$stage/VERSIONS.txt" <<EOF
Bundled third-party tools (run as separate programs by velo):

  yt-dlp $ytdlp_tag
  ${ff_name%.tar.xz}
  deno $deno_tag

FFmpeg is licensed under the LGPL 2.1. This is an unmodified build from
https://github.com/BtbN/FFmpeg-Builds; its source code is available from
https://ffmpeg.org/download.html and the build scripts from the BtbN repository.
EOF
echo "Staged video tools in $stage ($(du -sm "$stage" | cut -f1) MB): yt-dlp $ytdlp_tag, ${ff_name%.tar.xz}, deno $deno_tag"

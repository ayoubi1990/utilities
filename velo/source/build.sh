#!/usr/bin/env bash
# Builds a single, self-contained velo.jar with the JDK alone (no Maven or Gradle needed).
#   source/build.sh                       compile and package velo.jar
#   source/build.sh --test                also compile and run the test suite
#   source/build.sh --version 1.2.0 --vendor "Your Company"
# Uses $JAVA_HOME/bin if JAVA_HOME is set, otherwise javac/jar/java from PATH (JDK 21+).
set -euo pipefail

root="$(cd "$(dirname "$0")" && pwd)"
out="$root/out"
run_tests=0 version=0.1.0 vendor=velo
while [ $# -gt 0 ]; do
    case "$1" in
        --test) run_tests=1 ;;
        --version) version="$2"; shift ;;
        --vendor) vendor="$2"; shift ;;
        *) echo "unknown option $1" >&2; exit 2 ;;
    esac
    shift
done
bin="${JAVA_HOME:+$JAVA_HOME/bin/}"

rm -rf "$out"
mkdir -p "$out/main" "$out/fat"
libcp="$(ls "$root"/lib/*.jar | paste -sd: -)"

find "$root/src/main/java" -name '*.java' > "$out/sources-main.txt"
"${bin}javac" -encoding UTF-8 -Xlint:all -Xlint:-serial -d "$out/main" -cp "$libcp" @"$out/sources-main.txt"
# Non-code resources (the fallback icon font and its licence) go next to the classes.
cp -R "$root/src/main/resources/." "$out/main/"

# One self-contained velo.jar: our classes plus the unpacked third-party jars (FlatLaf).
(cd "$out/fat" && for j in "$root"/lib/*.jar; do "${bin}jar" --extract --file "$j"; done)
# Drop what belongs to the individual library jars: manifests, signatures, module descriptors.
rm -f "$out/fat/META-INF/MANIFEST.MF" "$out/fat/module-info.class"
find "$out/fat/META-INF" \( -name '*.SF' -o -name '*.RSA' -o -name '*.DSA' -o -name '*.EC' \
    -o -name module-info.class \) -delete
cp -R "$out/main/." "$out/fat/"
mkdir -p "$out/fat/META-INF"
cp "$root/THIRD-PARTY-NOTICES.txt" "$out/fat/META-INF/"

printf 'Multi-Release: true\nImplementation-Title: velo\nImplementation-Version: %s\nImplementation-Vendor: %s\n' \
    "$version" "$vendor" > "$out/MANIFEST.MF"
"${bin}jar" --create --file "$root/velo.jar" --manifest "$out/MANIFEST.MF" --main-class dev.velo.Main -C "$out/fat" .
echo "Built velo.jar $version (self-contained)"

if [ "$run_tests" = 1 ]; then
    find "$root/src/test/java" -name '*.java' > "$out/sources-test.txt"
    "${bin}javac" -encoding UTF-8 -Xlint:all -Xlint:-serial -d "$out/test" -cp "$out/main:$libcp" @"$out/sources-test.txt"
    "${bin}java" -ea -Djava.awt.headless=true -cp "$out/main:$out/test:$libcp" dev.velo.core.TestMain
fi

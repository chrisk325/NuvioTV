#!/usr/bin/env bash
# Rebuilds the patched nuvio-libass classes and puts them into app/libs/nuvio-libass-release.aar.
# Needs a JDK 11+ and unzip. The Android classes the patches use are compiled from signature-only stubs.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
aar="$here/../nuvio-libass-release.aar"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/stubs" "$work/classes"
unzip -q -p "$aar" classes.jar > "$work/libass.jar"
unzip -q -p "$here/../lib-common-release.aar" classes.jar > "$work/media3-common.jar"
javac -nowarn -d "$work/stubs" $(find "$here/stubs" -name '*.java')
javac --release 11 -nowarn -d "$work/classes" \
    -cp "$work/stubs:$work/libass.jar:$work/media3-common.jar" $(find "$here/src" -name '*.java')
python3 -I "$here/patch_aar.py" "$work/classes" "$aar"

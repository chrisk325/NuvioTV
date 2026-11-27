#!/usr/bin/env bash
# Rebuilds the patched nuvio-libass classes and puts them into app/libs/nuvio-libass-release.aar.
# Needs a JDK 17+, unzip and a Kotlin compiler: Gradle's (its lib/ holds kotlin-compiler-embeddable;
# KOTLIN_LIB names that directory, else GRADLE_HOME's and the Gradle wrapper's are searched).
# The Android classes the patches use are compiled from signature-only stubs.
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

# AssTrack.kt is nuvio-libass v0.0.15's (07a38cd). Only AssTrack.class goes into the AAR; the module
# name gives its internal members the AAR's own names (drainPendingChunks$nuvio_libass_release).
kotlin_lib="${KOTLIN_LIB:-}"
if [ -z "$kotlin_lib" ]; then
    for dir in ${GRADLE_HOME:+"$GRADLE_HOME/lib"} /opt/gradle-*/lib "$HOME"/.gradle/wrapper/dists/gradle-*/*/gradle-*/lib; do
        if compgen -G "$dir/kotlin-compiler-embeddable-*.jar" > /dev/null; then
            kotlin_lib="$dir"
            break
        fi
    done
fi
if [ -z "$kotlin_lib" ]; then
    echo "No Kotlin compiler found: set KOTLIN_LIB to a Gradle distribution's lib directory." >&2
    exit 1
fi
kotlin_stdlib="$(compgen -G "$kotlin_lib/kotlin-stdlib-[0-9]*.jar" | head -n 1)"
mkdir -p "$work/kotlin" "$work/classes/com/nuvio/tv/libass"
java -cp "$(compgen -G "$kotlin_lib/*.jar" | tr '\n' ':')" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -no-reflect -cp "$kotlin_stdlib" -jvm-target 17 -module-name nuvio-libass_release \
    -d "$work/kotlin" $(find "$here/src-kt" -name '*.kt')
cp "$work/kotlin/com/nuvio/tv/libass/AssTrack.class" "$work/classes/com/nuvio/tv/libass/"

python3 -I "$here/patch_aar.py" "$work/classes" "$aar"

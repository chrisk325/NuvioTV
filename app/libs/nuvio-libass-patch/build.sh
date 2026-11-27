#!/usr/bin/env bash
# Rebuilds the patched nuvio-libass classes and puts them into app/libs/nuvio-libass-release.aar.
# Needs a JDK 17+, unzip, a Kotlin compiler and an Android SDK platform:
# - Gradle's Kotlin compiler (its lib/ holds kotlin-compiler-embeddable): KOTLIN_LIB names that
#   directory, else GRADLE_HOME's and the Gradle wrapper's are searched.
# - android.jar: ANDROID_JAR names it, else the newest platforms/android-*/android.jar under
#   ANDROID_HOME, ANDROID_SDK_ROOT or local.properties' sdk.dir.
# The Java patches compile against signature-only stubs.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
aar="$here/../nuvio-libass-release.aar"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/stubs" "$work/classes"
unzip -q -p "$aar" classes.jar > "$work/libass.jar"
unzip -q -p "$here/../lib-common-release.aar" classes.jar > "$work/media3-common.jar"
unzip -q -p "$here/../lib-exoplayer-release.aar" classes.jar > "$work/media3-exoplayer.jar"
javac -nowarn -d "$work/stubs" $(find "$here/stubs" -name '*.java')
javac --release 11 -nowarn -d "$work/classes" \
    -cp "$work/stubs:$work/libass.jar:$work/media3-common.jar" $(find "$here/src" -name '*.java')

# AssTrack.kt is nuvio-libass v0.0.15's (07a38cd). AssSubtitleAtlasPipeline.kt is 3bada55's, the
# AAR's own, with the Overlay atlas in native memory (AtlasMemory). Only the classes listed below go
# into the AAR. The module name gives internal members the AAR's own names
# (drainPendingChunks$nuvio_libass_release), and the AAR as a friend path lets the pipeline use the
# AAR's internal classes.
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

android_jar="${ANDROID_JAR:-}"
if [ -z "$android_jar" ]; then
    sdk_dirs=("${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}")
    local_properties="$here/../../../local.properties"
    if [ -f "$local_properties" ]; then
        sdk_dirs+=("$(sed -n 's/^sdk\.dir=//p' "$local_properties" | sed 's/\\:/:/g; s/\\\\/\\/g' | head -n 1)")
    fi
    for sdk in "${sdk_dirs[@]}"; do
        [ -n "$sdk" ] && [ -d "$sdk/platforms" ] || continue
        android_jar="$(compgen -G "$sdk/platforms/android-*/android.jar" | sort -V | tail -n 1)"
        [ -n "$android_jar" ] && break
    done
fi
if [ -z "$android_jar" ] || [ ! -f "$android_jar" ]; then
    echo "No android.jar found: set ANDROID_JAR, or ANDROID_HOME to an Android SDK with a platform." >&2
    exit 1
fi

mkdir -p "$work/kotlin"
java -cp "$(compgen -G "$kotlin_lib/*.jar" | tr '\n' ':')" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -no-reflect -jvm-target 17 -module-name nuvio-libass_release \
    -cp "$kotlin_stdlib:$android_jar:$work/media3-common.jar:$work/media3-exoplayer.jar:$work/libass.jar:$work/classes" \
    -Xfriend-paths="$work/libass.jar" \
    -d "$work/kotlin" $(find "$here/src-kt" -name '*.kt')
for class in \
    com/nuvio/tv/libass/AssTrack.class \
    com/nuvio/tv/libass/media/widget/AssAtlasPipeline.class \
    'com/nuvio/tv/libass/media/widget/AssAtlasPipeline$Companion.class' \
    com/nuvio/tv/libass/media/widget/AtlasLibassThread.class \
    com/nuvio/tv/libass/media/widget/AtlasPayload.class; do
    mkdir -p "$work/classes/$(dirname "$class")"
    cp "$work/kotlin/$class" "$work/classes/$class"
done

python3 -I "$here/patch_aar.py" "$work/classes" "$aar"

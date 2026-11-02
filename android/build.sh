#!/usr/bin/env bash
# Builds the Android app without Gradle, using only the Android SDK and a JDK.
#
# This is the fallback path for runners with no Gradle cache; it performs the
# same steps AGP does: aapt2 resource compile/link, javac, d8, native lib
# packaging, zipalign and apksigner.
#
# Requirements (paths can be overridden with env vars):
#   ANDROID_SDK_ROOT  Android SDK with build-tools;34.0.0 and platforms;android-34
#   JAVA_HOME         JDK 17
#   PATH              Go toolchain with gomobile installed
#
# Usage: ./build.sh [arm64-v8a,armeabi-v7a,x86_64]
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/tc/android-sdk}}"
BUILD_TOOLS="$SDK/build-tools/34.0.0"
PLATFORM_JAR="$SDK/platforms/android-34/android.jar"
ABIS="${1:-arm64-v8a,armeabi-v7a,x86_64}"
GOMOBILE="${GOMOBILE:-$(command -v gomobile || echo "$HOME/tc/bin/gomobile")}"

OUT="$HERE/build"
LIBS="$HERE/app/libs"
KEYSTORE="$HERE/debug.keystore"
AAR="$LIBS/wiiu.aar"
APK="$OUT/wiiudownloader-debug.apk"

mkdir -p "$OUT" "$LIBS"

echo "==> binding Go core ($ABIS)"
targets=""
IFS=',' read -ra abi_list <<< "$ABIS"
for abi in "${abi_list[@]}"; do
    case "$abi" in
        arm64-v8a) targets="$targets,android/arm64" ;;
        armeabi-v7a) targets="$targets,android/arm" ;;
        x86_64) targets="$targets,android/amd64" ;;
        x86) targets="$targets,android/386" ;;
        *) echo "unknown ABI: $abi" >&2; exit 1 ;;
    esac
done
targets="${targets#,}"

GOMOBILE_BIND="$GOMOBILE"
if [ ! -x "$GOMOBILE_BIND" ]; then
    GOMOBILE_BIND="$(command -v gomobile)"
fi

"$GOMOBILE_BIND" bind \
    -target "$targets" \
    -androidapi 24 \
    -o "$AAR" \
    "$ROOT/mobile"

echo "==> compiling resources"
rm -rf "$OUT/res" "$OUT/gen"
mkdir -p "$OUT/res" "$OUT/gen"
"$BUILD_TOOLS/aapt2" compile --dir "$HERE/app/src/main/res" -o "$OUT/res"

"$BUILD_TOOLS/aapt2" link \
    -o "$OUT/base.apk" \
    -I "$PLATFORM_JAR" \
    --manifest "$HERE/app/src/main/AndroidManifest.xml" \
    --java "$OUT/gen" \
    --min-sdk-version 24 \
    --target-sdk-version 34 \
    --version-code 1 \
    --version-name 1.0.0 \
    "$OUT"/res/*.flat

echo "==> compiling java"
rm -rf "$OUT/classes" "$OUT/dex"
mkdir -p "$OUT/classes" "$OUT/dex"
rm -rf "$OUT/aar"
mkdir -p "$OUT/aar"
(cd "$OUT/aar" && unzip -qo "$AAR")

# The gomobile AAR ships its Java API as classes.jar.
CP="$PLATFORM_JAR:$OUT/aar/classes.jar"
find "$HERE/app/src/main/java" -name '*.java' > "$OUT/sources.txt"
find "$OUT/gen" -name '*.java' >> "$OUT/sources.txt"

"$JAVA_HOME/bin/javac" \
    -source 8 -target 8 \
    -bootclasspath "$PLATFORM_JAR" \
    -classpath "$CP" \
    -d "$OUT/classes" \
    -encoding UTF-8 \
    -nowarn \
    @"$OUT/sources.txt"

echo "==> dexing"
"$BUILD_TOOLS/d8" \
    --min-api 24 \
    --lib "$PLATFORM_JAR" \
    --output "$OUT/dex" \
    "$OUT/aar/classes.jar" \
    $(find "$OUT/classes" -name '*.class')

echo "==> packaging"
rm -f "$OUT/unsigned.apk"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
cp "$OUT/dex/classes.dex" "$OUT/classes.dex"
(cd "$OUT" && zip -q -X "unsigned.apk" "classes.dex")
for abi in "${abi_list[@]}"; do
    so="$OUT/aar/jni/$abi/libgojni.so"
    if [ -f "$so" ]; then
        mkdir -p "$OUT/package/lib/$abi"
        cp "$so" "$OUT/package/lib/$abi/"
    fi
done
(cd "$OUT/package" && zip -q -X -r "$OUT/unsigned.apk" lib)

echo "==> aligning and signing"
"$BUILD_TOOLS/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

if [ ! -f "$KEYSTORE" ]; then
    "$JAVA_HOME/bin/keytool" -genkeypair -v \
        -keystore "$KEYSTORE" \
        -storepass android -keypass android \
        -alias androiddebugkey \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi

"$BUILD_TOOLS/apksigner" sign \
    --ks "$KEYSTORE" \
    --ks-pass pass:android \
    --key-pass pass:android \
    --ks-key-alias androiddebugkey \
    --out "$APK" \
    "$OUT/aligned.apk"

"$BUILD_TOOLS/apksigner" verify --print-certs "$APK" | head -5
ls -lh "$APK"
echo "Built $APK"

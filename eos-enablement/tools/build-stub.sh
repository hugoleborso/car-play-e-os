#!/bin/sh
# Copyright 2026 The openaap authors.
# Licensed under the Apache License, Version 2.0.
#
# Build one presence stub into a sideloadable, self-signed APK.
#
#   ./tools/build-stub.sh stubs/maps [outdir]
#
# Uses only aapt2, zipalign and apksigner from the Android SDK build-tools, and
# android.jar from a platform. No Gradle, no AGP, no network, no dependency on
# this repository's Gradle build. That is deliberate: the stubs contain no code,
# so a code build system buys nothing, and someone following
# docs/09-android-auto-on-eos.md with a phone in front of them should not have
# to resolve an AGP version to get three 4 KiB APKs.
#
# The signing key is generated on first run into build/stub-keystore.jks and is
# a throwaway. It is NOT a release key. Anything sideloaded with it can be
# uninstalled and replaced freely, which is exactly what you want while
# testing. See packaging/README.md for what signing means in a ROM build, where
# the answer is different and matters much more.

set -eu

usage() {
    echo "usage: $0 <stub-directory> [output-directory]" >&2
    echo "  e.g. $0 stubs/maps" >&2
    exit 2
}

[ $# -ge 1 ] || usage

STUB_DIR=${1%/}
OUT_DIR=${2:-build}
MANIFEST="$STUB_DIR/src/main/AndroidManifest.xml"

[ -f "$MANIFEST" ] || { echo "no manifest at $MANIFEST" >&2; exit 1; }

SDK=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
if [ -z "$SDK" ] && [ -f ../local.properties ]; then
    SDK=$(sed -n 's/^sdk\.dir=//p' ../local.properties)
fi
if [ -z "$SDK" ] && [ -f local.properties ]; then
    SDK=$(sed -n 's/^sdk\.dir=//p' local.properties)
fi
[ -n "$SDK" ] || { echo "set ANDROID_SDK_ROOT" >&2; exit 1; }

# Highest installed build-tools and platform. Nothing here is version
# sensitive; the stubs have no code and no resources to compile.
BT=$(ls -1 "$SDK/build-tools" | sort -V | tail -1)
PLATFORM=$(ls -1 "$SDK/platforms" | sort -V | tail -1)
AAPT2="$SDK/build-tools/$BT/aapt2"
ZIPALIGN="$SDK/build-tools/$BT/zipalign"
APKSIGNER="$SDK/build-tools/$BT/apksigner"
ANDROID_JAR="$SDK/platforms/$PLATFORM/android.jar"

NAME=$(basename "$STUB_DIR")
PKG=$(sed -n 's/.*package="\([^"]*\)".*/\1/p' "$MANIFEST" | head -1)
MIN_SDK=$(sed -n 's/.*android:minSdkVersion="\([0-9]*\)".*/\1/p' "$MANIFEST" | head -1)
MIN_SDK=${MIN_SDK:-33}

mkdir -p "$OUT_DIR"
KEYSTORE="$OUT_DIR/stub-keystore.jks"
if [ ! -f "$KEYSTORE" ]; then
    echo "generating throwaway signing key in $KEYSTORE"
    keytool -genkeypair -keystore "$KEYSTORE" -storepass stubstub -keypass stubstub \
        -alias stub -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=openaap stub signing, OU=not a release key" >/dev/null 2>&1
fi

echo "building $NAME -> $PKG (minSdk $MIN_SDK, build-tools $BT)"

# aapt2 link with no resources at all. --manifest is the whole input.
"$AAPT2" link \
    -I "$ANDROID_JAR" \
    --manifest "$MANIFEST" \
    --min-sdk-version "$MIN_SDK" \
    -o "$OUT_DIR/$NAME-unaligned.apk"

"$ZIPALIGN" -f -p 4 "$OUT_DIR/$NAME-unaligned.apk" "$OUT_DIR/$NAME-unsigned.apk"

"$APKSIGNER" sign \
    --ks "$KEYSTORE" --ks-pass pass:stubstub --key-pass pass:stubstub \
    --min-sdk-version "$MIN_SDK" \
    --out "$OUT_DIR/$NAME.apk" \
    "$OUT_DIR/$NAME-unsigned.apk"

rm -f "$OUT_DIR/$NAME-unaligned.apk" "$OUT_DIR/$NAME-unsigned.apk" "$OUT_DIR/$NAME.apk.idsig"

echo "  $OUT_DIR/$NAME.apk"
echo "  install with: adb install --force-queryable $OUT_DIR/$NAME.apk"

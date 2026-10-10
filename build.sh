#!/usr/bin/env bash
# Builds build/fold-fix.apk without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
#
# Needs a JDK (17+) and an Android SDK with build-tools and a platform installed.
# Environment overrides:
#   ANDROID_HOME / ANDROID_SDK_ROOT   SDK location (else ~/Library/Android/sdk or ~/Android/Sdk)
#   BUILD_TOOLS_VERSION               e.g. 35.0.0 (default: newest installed)
#   ANDROID_PLATFORM                  e.g. android-35 (default: newest installed)
#   KEYSTORE, KEYSTORE_PASS, KEY_ALIAS, KEY_PASS   signing key (default: the debug key,
#                                     created if missing)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP="$ROOT/app"
OUT="$ROOT/build"
MIN_SDK=33

die() { echo "build.sh: $*" >&2; exit 1; }

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ]; then
  for d in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
    if [ -d "$d" ]; then SDK="$d"; break; fi
  done
fi
[ -n "$SDK" ] && [ -d "$SDK" ] || die "Android SDK not found; set ANDROID_HOME"

# Newest entry of a directory whose name matches a regex (version-sorted).
newest() { ls -1 "$1" 2>/dev/null | grep -E "$2" | sort -V | tail -n 1 || true; }

BT_VERSION="${BUILD_TOOLS_VERSION:-$(newest "$SDK/build-tools" '^[0-9]+\.[0-9]+\.[0-9]+$')}"
PLATFORM="${ANDROID_PLATFORM:-$(newest "$SDK/platforms" '^android-[0-9]+(\.[0-9]+)?$')}"
case "$PLATFORM" in android-*) ;; ?*) PLATFORM="android-$PLATFORM" ;; esac
BT="$SDK/build-tools/$BT_VERSION"
JAR="$SDK/platforms/$PLATFORM/android.jar"
[ -n "$BT_VERSION" ] && [ -x "$BT/aapt2" ] || die "no build-tools found in $SDK/build-tools"
[ -n "$PLATFORM" ] && [ -f "$JAR" ] || die "no platform android.jar found in $SDK/platforms"
echo "Using build-tools $BT_VERSION, $PLATFORM"

KEYSTORE="${KEYSTORE:-$HOME/.android/debug.keystore}"
KEYSTORE_PASS="${KEYSTORE_PASS:-android}"
KEY_ALIAS="${KEY_ALIAS:-androiddebugkey}"
KEY_PASS="${KEY_PASS:-$KEYSTORE_PASS}"
export KEYSTORE_PASS KEY_PASS  # passed to keytool/apksigner via the environment, not argv
if [ ! -f "$KEYSTORE" ]; then
  [ "$KEYSTORE" = "$HOME/.android/debug.keystore" ] || die "keystore not found: $KEYSTORE"
  echo "Creating debug keystore $KEYSTORE"
  mkdir -p "$(dirname "$KEYSTORE")"
  keytool -genkeypair -keystore "$KEYSTORE" -storepass:env KEYSTORE_PASS -keypass:env KEY_PASS \
    -alias "$KEY_ALIAS" -dname "CN=Android Debug,O=Android,C=US" \
    -keyalg RSA -keysize 2048 -validity 10000 >/dev/null
fi

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

# Resources + manifest.
"$BT/aapt2" compile --dir "$APP/res" -o "$OUT/res.zip"
"$BT/aapt2" link --manifest "$APP/AndroidManifest.xml" -I "$JAR" \
  -o "$OUT/unsigned.apk" --java "$OUT/gen" "$OUT/res.zip"

# Java -> classes -> dex.
# javac splits @file lines on whitespace, so quote each path (and escape backslashes).
find "$APP/src" "$OUT/gen" -name '*.java' | sed -e 's/\\/\\\\/g' -e 's/.*/"&"/' > "$OUT/sources.txt"
javac --release 17 -Xlint:-options -encoding UTF-8 -classpath "$JAR" -d "$OUT/classes" @"$OUT/sources.txt"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"  # d8 takes one argument per line
"$BT/d8" --release --lib "$JAR" --min-api "$MIN_SDK" --output "$OUT/dex" @"$OUT/classes.txt"

# Package, align, sign.
if command -v zip >/dev/null; then
  (cd "$OUT/dex" && zip -q -j "$OUT/unsigned.apk" classes.dex)
else
  (cd "$OUT/dex" && jar -u -M -f "$OUT/unsigned.apk" classes.dex)
fi
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass env:KEYSTORE_PASS \
  --ks-key-alias "$KEY_ALIAS" --key-pass env:KEY_PASS \
  --out "$OUT/fold-fix.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify "$OUT/fold-fix.apk"
echo "Built $OUT/fold-fix.apk"

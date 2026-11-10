#!/usr/bin/env bash
# Runs the tests of FoldFix's rules on a plain JVM: javac -> java. No Android SDK, no device.
#
# The tests (tests/) cover the classes in app/src that hold decisions and nothing of Android's.
# Only the tests are named here; javac compiles the app classes they use from app/src, and fails
# if one of those has come to need Android. The one exception is android.util.Log, which FoldLog
# writes to: tests/android/util/Log.java stands in for it. Nothing in tests/ is ever part of the
# APK.
#
# Needs a JDK (17+).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$ROOT/build/tests"

rm -rf "$OUT"
mkdir -p "$OUT/classes"

# javac splits @file lines on whitespace, so quote each path (and escape backslashes).
find "$ROOT/tests" -name '*.java' | sed -e 's/\\/\\\\/g' -e 's/.*/"&"/' > "$OUT/sources.txt"
javac --release 17 -Xlint:-options -encoding UTF-8 -implicit:class -sourcepath "$ROOT/app/src" \
  -d "$OUT/classes" @"$OUT/sources.txt"
java -classpath "$OUT/classes" app.foldfix.RunTests

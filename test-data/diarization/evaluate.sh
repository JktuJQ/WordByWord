#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
APK="${APK:-$REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk}"
PACKAGE="dev.wordbyword.app"
TEST_APK="${TEST_APK:-$REPO_ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk}"
RUNNER="$PACKAGE.test/dev.wordbyword.app.evaluation.DiarizationEvaluation"
if [ "$#" -eq 0 ]; then
  RECORDINGS=(rec_0055 rec_0273)
else
  RECORDINGS=("$@")
fi

if [ ! -f "$APK" ] || [ ! -f "$TEST_APK" ]; then
  echo "Build both APKs with ./gradlew assembleDebug assembleDebugAndroidTest (or set APK and TEST_APK)." >&2
  exit 1
fi

SCORE_DIR=$(mktemp -d)
trap 'rm -rf "$SCORE_DIR"' EXIT
javac -d "$SCORE_DIR" "$SCRIPT_DIR/ScoreDiarization.java"

adb install -r "$APK" > /dev/null
adb install -r -t "$TEST_APK" > /dev/null

for recording in "${RECORDINGS[@]}"; do
  if [[ ! "$recording" =~ ^[a-zA-Z0-9_-]+$ ]]; then
    echo "Invalid recording ID: $recording" >&2
    exit 1
  fi
  adb shell am force-stop "$PACKAGE" > /dev/null
  flac="$SCRIPT_DIR/$recording.flac"
  reference="$SCRIPT_DIR/$recording.rttm"
  meta="$SCRIPT_DIR/$recording.json"
  if [ ! -f "$flac" ] || [ ! -f "$reference" ] || [ ! -f "$meta" ]; then
    echo "Skipping $recording: missing .flac/.rttm/.json in $SCRIPT_DIR" >&2
    continue
  fi
  duration=$(grep -o '"duration": *[0-9.]*' "$meta" | grep -o '[0-9.]*$')

  echo "=== $recording (duration ${duration}s) ==="

  adb push "$flac" "/data/local/tmp/$recording.flac" > /dev/null
  adb shell run-as "$PACKAGE" cp "/data/local/tmp/$recording.flac" "cache/$recording.flac"
  adb shell run-as "$PACKAGE" rm -f "cache/$recording.hyp.rttm"

  start_time=$(date +%s)
  if ! output=$(timeout 180 adb shell am instrument -w -r \
    -e audio_uri "file:///data/user/0/$PACKAGE/cache/$recording.flac" \
    -e recording_id "$recording" "$RUNNER"); then
    adb shell am force-stop "$PACKAGE" > /dev/null 2>&1 || true
    echo "Evaluation timed out or adb failed: $output" >&2
    exit 1
  fi
  if [[ "$output" != *WBW_EVALUATION_OK* ]]; then
    echo "$output" >&2
    exit 1
  fi
  hypothesis="$SCRIPT_DIR/$recording.hyp.rttm"
  rm -f "$hypothesis"
  elapsed=$(( $(date +%s) - start_time ))

  if adb shell run-as "$PACKAGE" test -e "cache/$recording.hyp.rttm" 2>/dev/null; then
    adb exec-out run-as "$PACKAGE" cat "cache/$recording.hyp.rttm" > "$hypothesis"
    java -cp "$SCORE_DIR" ScoreDiarization "$reference" "$hypothesis" "$duration" "$elapsed"
  else
    echo "No hypothesis produced for $recording (see adb logcat -s DiarizationEvaluation:V)" >&2
    exit 1
  fi
  echo
done

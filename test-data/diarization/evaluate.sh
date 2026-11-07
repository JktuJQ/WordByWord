#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
APK="${APK:-$REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk}"
PACKAGE="dev.wordbyword.app"
ACTIVITY="$PACKAGE/.DiarizerCheckActivity"
if [ "$#" -eq 0 ]; then
  RECORDINGS=(rec_0055 rec_0273)
else
  RECORDINGS=("$@")
fi

if [ ! -f "$APK" ]; then
  echo "APK not found at $APK; run ./gradlew assembleDebug first (or set APK=...)." >&2
  exit 1
fi

javac -d "$SCRIPT_DIR" "$SCRIPT_DIR/ScoreDiarization.java"

adb install -r "$APK" > /dev/null

for recording in "${RECORDINGS[@]}"; do
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
  adb shell am start -n "$ACTIVITY" \
    --es audio_uri "file:///data/user/0/$PACKAGE/cache/$recording.flac" \
    --es recording_id "$recording" > /dev/null

  hypothesis="$SCRIPT_DIR/$recording.hyp.rttm"
  rm -f "$hypothesis"
  timeout_s=120
  while ! adb shell run-as "$PACKAGE" test -e "cache/$recording.hyp.rttm" 2>/dev/null; do
    if [ $(( $(date +%s) - start_time )) -gt "$timeout_s" ]; then
      echo "Timed out waiting for $recording.hyp.rttm after ${timeout_s}s" >&2
      break
    fi
    sleep 2
  done
  elapsed=$(( $(date +%s) - start_time ))

  if adb shell run-as "$PACKAGE" test -e "cache/$recording.hyp.rttm" 2>/dev/null; then
    adb exec-out run-as "$PACKAGE" cat "cache/$recording.hyp.rttm" > "$hypothesis"
    java -cp "$SCRIPT_DIR" ScoreDiarization "$reference" "$hypothesis" "$duration" "$elapsed"
  else
    echo "No hypothesis produced for $recording (see adb logcat -s DiarizerCheck:V)" >&2
  fi
  echo
done

#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

PACKAGE=dev.wordbyword.app
TEST_PACKAGE=dev.wordbyword.app.test
RUNNER=androidx.test.runner.AndroidJUnitRunner
RESULTS_DIR=evaluation/results

./gradlew assembleDebug assembleDebugAndroidTest

mkdir -p "$RESULTS_DIR"

adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb install -r -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

cleanup() {
  adb uninstall "$TEST_PACKAGE" >/dev/null 2>&1 || true
  adb uninstall "$PACKAGE" >/dev/null 2>&1 || true
}
trap cleanup EXIT

START_DIARIZATION=$(date +%s)
adb shell am instrument -w -e class dev.wordbyword.app.evaluation.DiarizationHypothesisTest "$TEST_PACKAGE/$RUNNER"
DIARIZATION_SECONDS=$(( $(date +%s) - START_DIARIZATION ))

adb shell am instrument -w -e class dev.wordbyword.app.evaluation.AsrHypothesisTest "$TEST_PACKAGE/$RUNNER"

adb exec-out run-as "$PACKAGE" cat /data/user/0/$PACKAGE/cache/rec_0055.hyp.rttm > "$RESULTS_DIR/rec_0055.hyp.rttm"
adb exec-out run-as "$PACKAGE" cat /data/user/0/$PACKAGE/cache/rec_0273.hyp.rttm > "$RESULTS_DIR/rec_0273.hyp.rttm"
adb exec-out run-as "$PACKAGE" cat /data/user/0/$PACKAGE/cache/asr-hypotheses.json > "$RESULTS_DIR/asr-hypotheses.json"

./gradlew :evaluation:scoreDiarization --args="evaluation/fixtures/diarization/rec_0055.rttm $RESULTS_DIR/rec_0055.hyp.rttm 60 $(( DIARIZATION_SECONDS / 2 ))"
./gradlew :evaluation:scoreDiarization --args="evaluation/fixtures/diarization/rec_0273.rttm $RESULTS_DIR/rec_0273.hyp.rttm 60 $(( DIARIZATION_SECONDS / 2 ))"
./gradlew :evaluation:scoreAsr --args="evaluation/fixtures/asr/manifest.json $RESULTS_DIR/asr-hypotheses.json $RESULTS_DIR/asr-report.json"

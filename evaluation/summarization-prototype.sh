#!/usr/bin/env bash
set -euo pipefail

if [[ $# != 2 ]]; then
  echo "Usage (inside nix develop): bash evaluation/summarization-prototype.sh DEVICE MODEL.litertlm" >&2
  exit 2
fi
device=$1
model=$2
cd "$(dirname "$0")/.."
expected=2df6821ec12702dafd33915e7a1a1adc7c4b053f3672fd9555dfaf3a114c4139
actual=$(shasum -a 256 "$model" | awk '{print $1}')
[[ "$actual" == "$expected" ]] || { echo "Unexpected model SHA-256" >&2; exit 1; }
[[ $(adb -s "$device" get-state) == device ]]
output="evaluation/results/summarization54/$(date -u +%Y%m%dT%H%M%SZ)-$$"
mkdir -p "$output"
git rev-parse HEAD > "$output/commit.txt"
git diff > "$output/working-tree.patch"
adb -s "$device" shell cat /proc/meminfo > "$output/meminfo.txt"
./gradlew --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest
adb -s "$device" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$device" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
remote=/sdcard/Android/data/dev.wordbyword.app/files
report_name="summary-$(date -u +%Y%m%dT%H%M%SZ)-$$.json"
adb -s "$device" shell mkdir -p "$remote"
adb -s "$device" push "$model" "$remote/summary.litertlm"
adb -s "$device" shell am instrument -w -r \
  -e class dev.wordbyword.app.evaluation.SummarizationPrototypeTest \
  -e runSummary true \
  -e promptComparison "${SUMMARY_COMPARE:-false}" \
  -e promptStudy "${SUMMARY_STUDY:-false}" \
  -e promptHoldout "${SUMMARY_HOLDOUT:-false}" \
  -e selectedPrompt "${SUMMARY_PROMPT:-0}" \
  -e summaryReport "$report_name" \
  dev.wordbyword.app.test/androidx.test.runner.AndroidJUnitRunner | tee "$output/instrumentation.txt"
adb -s "$device" pull "$remote/$report_name" "$output/report.json"
rg -q '^OK \(1 test\)' "$output/instrumentation.txt"
echo "Report: $output/report.json"

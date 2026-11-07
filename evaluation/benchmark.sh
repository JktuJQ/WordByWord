#!/usr/bin/env bash
set -euo pipefail

mode=${1:?Usage: benchmark.sh sequential|parallel audio_duration_seconds}
duration=${2:?Provide recording duration in seconds}
case "$mode" in sequential|parallel) ;; *) echo "Invalid mode: $mode" >&2; exit 1 ;; esac
awk -v n="$duration" 'BEGIN { exit !(n ~ /^[0-9]+([.][0-9]+)?$/ && n > 0) }'
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
output="$root/evaluation/results/$mode-$(date +%Y%m%d-%H%M%S)"
package=dev.wordbyword.app
mkdir -p "$output"
finished=false
cleanup() {
  if [[ "$finished" != true ]]; then adb shell am force-stop "$package" > /dev/null 2>&1 || true; fi
}
trap cleanup EXIT
adb shell am force-stop "$package"
adb shell am start -W -n "$package/.MainActivity" > /dev/null
pid=
for _ in {1..100}; do
  pid=$(adb shell pidof -s "$package" | tr -d '\r' || true)
  [[ -n "$pid" ]] && break
  sleep 0.1
done
[[ -n "$pid" ]] || { echo 'Application did not start' >&2; exit 1; }
printf 'elapsed_s\tpss_kib\trss_kib\n' > "$output/memory.tsv"
start=$(date +%s)
echo "Start processing ONE recording in the normal app UI. Mode label: $mode. Results: $output"
while true; do
  elapsed=$(( $(date +%s) - start ))
  if (( elapsed > ${TIMEOUT_SECONDS:-600} )); then echo 'Benchmark timed out' >&2; exit 1; fi
  current=$(adb shell pidof -s "$package" | tr -d '\r' || true)
  [[ "$current" == "$pid" ]] || { echo 'Application process exited/restarted' >&2; exit 1; }
  info=$(adb shell dumpsys meminfo --local "$pid")
  read -r pss rss < <(awk '
    { for (i=2; i<NF; i++) {
        if ($(i-1) == "TOTAL" && $i == "PSS:") pss=$(i+1)
        if ($(i-1) == "TOTAL" && $i == "RSS:") rss=$(i+1)
      }
    }
    END { print pss+0, rss+0 }' <<< "$info")
  printf '%s\t%s\t%s\n' "$elapsed" "$pss" "$rss" >> "$output/memory.tsv"
  adb logcat -d --pid="$pid" -v brief > "$output/logcat.txt"
  if grep -q 'Cannot process recording' "$output/logcat.txt"; then echo "Processing failed: $output/logcat.txt" >&2; exit 1; fi
  if grep -q 'pipeline recording=.*elapsed_ms=' "$output/logcat.txt"; then break; fi
  sleep 1
done
milliseconds=$(sed -n 's/.*pipeline recording=.*elapsed_ms=\([0-9][0-9]*\).*/\1/p' "$output/logcat.txt" | head -1)
awk -v mode="$mode" -v ms="$milliseconds" -v duration="$duration" '
  NR > 1 { if ($2 > pss) pss=$2; if ($3 > rss) rss=$3 }
  END {
    if (pss == 0 || rss == 0) { print "Could not read PSS/RSS from dumpsys meminfo" > "/dev/stderr"; exit 1 }
    printf "mode=%s elapsed_ms=%d rtf=%.4f sampled_peak_pss_mib=%.1f sampled_peak_rss_mib=%.1f\n", mode, ms, ms/1000/duration, pss/1024, rss/1024
  }' "$output/memory.tsv" | tee "$output/summary.txt"
finished=true

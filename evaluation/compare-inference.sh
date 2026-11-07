#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
root=$(cd "$script_dir/.." && pwd)

if [[ "${1:-}" != "--in-nix" ]]; then
  exec nix develop "$root" --command bash "$script_dir/compare-inference.sh" --in-nix
fi
shift

cd "$root"

timestamp=$(date +%Y%m%d-%H%M%S)
output="$root/evaluation/results/comparison-$timestamp"
mkdir -p "$output"

package=dev.wordbyword.app
duration=60
test_package=dev.wordbyword.app.test
test_runner=androidx.test.runner.AndroidJUnitRunner
test_class=dev.wordbyword.app.evaluation.InferenceBenchmarkTest
if [[ "$(uname -m)" == arm64 || "$(uname -m)" == aarch64 ]]; then
  abi=arm64-v8a
else
  abi=x86_64
fi
avd_name="wordbyword-api-36-$abi"
avd_serial=
launcher_pid=

cleanup() {
  if [[ -n "$avd_serial" ]]; then
    adb -s "$avd_serial" emu kill >/dev/null 2>&1 || true
  elif [[ -n "$launcher_pid" ]]; then
    kill "$launcher_pid" >/dev/null 2>&1 || true
  fi
  if [[ -n "$launcher_pid" ]]; then
    wait "$launcher_pid" 2>/dev/null || true
  fi
}
trap cleanup EXIT

for serial in $(adb devices | awk '$1 ~ /^emulator-/ && $2 == "device" {print $1}'); do
  running_avd=$(adb -s "$serial" emu avd name 2>/dev/null | awk 'NR == 1 { sub(/\r$/, ""); print }')
  if [[ "$running_avd" == "$avd_name" ]]; then
    echo "Stop the existing $avd_name emulator before running this comparison." >&2
    exit 1
  fi
done

echo "Building sequential APK..."
./gradlew --no-daemon --max-workers=1 -PparallelInference=false assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk "$output/sequential.apk"

echo "Building parallel APK..."
./gradlew --no-daemon --max-workers=1 -PparallelInference=true assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk "$output/parallel.apk"

echo "Building Android instrumentation test APK..."
./gradlew --no-daemon --max-workers=1 -PparallelInference=true assembleDebugAndroidTest
cp app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk "$output/benchmark-test.apk"

echo "Starting isolated $avd_name with 4 CPU cores..."
WORDBYWORD_ANDROID_HOME="$output/android-home" nix run .#start-emulator -- \
  -cores 4 -no-window -no-audio -no-boot-anim >"$output/emulator.log" 2>&1 &
launcher_pid=$!

for _ in $(seq 1 180); do
  for serial in $(adb devices | awk '$1 ~ /^emulator-/ && $2 == "device" {print $1}'); do
    running_avd=$(adb -s "$serial" emu avd name 2>/dev/null | awk 'NR == 1 { sub(/\r$/, ""); print }')
    if [[ "$running_avd" == "$avd_name" ]]; then
      avd_serial=$serial
      break
    fi
  done
  if [[ -n "$avd_serial" ]]; then break; fi
  if ! kill -0 "$launcher_pid" 2>/dev/null; then
    echo "AVD launcher exited. See $output/emulator.log" >&2
    exit 1
  fi
  sleep 1
done
if [[ -z "$avd_serial" ]]; then
  echo "Timed out waiting for $avd_name. See $output/emulator.log" >&2
  exit 1
fi

export ANDROID_SERIAL="$avd_serial"
adb wait-for-device
for _ in $(seq 1 180); do
  [[ "$(adb shell getprop sys.boot_completed | tr -d '\r')" == 1 ]] && break
  sleep 1
done
if [[ "$(adb shell getprop sys.boot_completed | tr -d '\r')" != 1 ]]; then
  echo "Android did not finish booting. See $output/emulator.log" >&2
  exit 1
fi

echo "AVD: $avd_name ($avd_serial)"
echo "CPU online: $(adb shell cat /sys/devices/system/cpu/online | tr -d '\r')"
echo "RAM: $(adb shell cat /proc/meminfo | awk '/MemTotal/ {printf "%.0f MiB", $2 / 1024}')"
adb shell cat /sys/devices/system/cpu/online | tr -d '\r' | grep -Eq '^0-3$' || {
  echo "Expected four online CPU cores." >&2
  exit 1
}
adb shell cat /proc/meminfo | awk '/MemTotal/ { exit !($2 >= 3500000) }' || {
  echo "Expected an AVD configured with 4096 MiB RAM." >&2
  exit 1
}
adb install -r "$output/benchmark-test.apk" >/dev/null

for mode in sequential parallel; do
  echo
  echo "===== $mode inference ====="
  adb install -r "$output/$mode.apk"
  adb shell pm clear "$package" >/dev/null
  run_dir="$output/$mode-run"
  mkdir -p "$run_dir"
  printf 'elapsed_s\tpss_kib\trss_kib\n' > "$run_dir/memory.tsv"
  adb logcat -c

  adb shell am instrument -w -r -e class "$test_class" "$test_package/$test_runner" \
    >"$run_dir/instrumentation.txt" 2>&1 &
  instrumentation_pid=$!
  app_pid=
  started_at=$(date +%s)

  while kill -0 "$instrumentation_pid" 2>/dev/null; do
    elapsed=$(( $(date +%s) - started_at ))
    if (( elapsed > ${TIMEOUT_SECONDS:-600} )); then
      kill "$instrumentation_pid" >/dev/null 2>&1 || true
      wait "$instrumentation_pid" 2>/dev/null || true
      echo "$mode inference timed out." >&2
      exit 1
    fi

    current_pid=$(adb shell pidof -s "$package" | tr -d '\r' || true)
    if [[ -n "$current_pid" ]]; then
      if [[ -n "$app_pid" && "$current_pid" != "$app_pid" ]]; then
        echo "$package process exited or restarted during $mode inference." >&2
        exit 1
      fi
      app_pid=$current_pid
      meminfo=$(adb shell dumpsys meminfo --local "$app_pid")
      read -r pss rss < <(awk '
        { for (i=2; i<NF; i++) {
            if ($(i-1) == "TOTAL" && $i == "PSS:") pss=$(i+1)
            if ($(i-1) == "TOTAL" && $i == "RSS:") rss=$(i+1)
          }
        }
        END { print pss+0, rss+0 }' <<< "$meminfo")
      printf '%s\t%s\t%s\n' "$elapsed" "$pss" "$rss" >> "$run_dir/memory.tsv"
      adb logcat -d --pid="$app_pid" -v brief > "$run_dir/logcat.txt"
      if grep -q 'Cannot process recording' "$run_dir/logcat.txt"; then
        echo "$mode pipeline failed; see $run_dir/logcat.txt" >&2
        exit 1
      fi
    fi
    sleep 1
  done
  if ! wait "$instrumentation_pid"; then
    cat "$run_dir/instrumentation.txt" >&2
    exit 1
  fi
  [[ -n "$app_pid" ]] || {
    cat "$run_dir/instrumentation.txt" >&2
    echo "Android app process was not observed during $mode inference." >&2
    exit 1
  }
  adb logcat -d --pid="$app_pid" -v brief > "$run_dir/logcat.txt"
  grep -q 'OK (1 test)' "$run_dir/instrumentation.txt" || {
    cat "$run_dir/instrumentation.txt" >&2
    echo "$mode instrumentation test did not pass." >&2
    exit 1
  }

  milliseconds=$(sed -n 's/.*pipeline recording=.*elapsed_ms=\([0-9][0-9]*\).*/\1/p' "$run_dir/logcat.txt" | head -1)
  [[ -n "$milliseconds" ]] || {
    echo "Could not read pipeline elapsed time for $mode; see $run_dir/logcat.txt" >&2
    exit 1
  }
  awk -v mode="$mode" -v ms="$milliseconds" -v duration="$duration" '
    NR > 1 { if ($2 > pss) pss=$2; if ($3 > rss) rss=$3 }
    END {
      if (pss == 0 || rss == 0) { print "Could not read PSS/RSS from dumpsys meminfo" > "/dev/stderr"; exit 1 }
      printf "mode=%s elapsed_ms=%d rtf=%.4f sampled_peak_pss_mib=%.1f sampled_peak_rss_mib=%.1f\n",
        mode, ms, ms/1000/duration, pss/1024, rss/1024
    }' "$run_dir/memory.tsv" | tee "$run_dir/summary.txt"
  adb exec-out run-as "$package" cat "/data/user/0/$package/cache/benchmark-transcript.json" \
    > "$output/rec_0055-$mode.json"
done

echo
echo "===== Benchmark summaries ====="
for mode in sequential parallel; do
  summary="$output/$mode-run/summary.txt"
  if [[ ! -f "$summary" ]]; then
    echo "Missing $mode benchmark summary." >&2
    exit 1
  fi
  cat "$summary"
done

echo
if diff -u "$output/rec_0055-sequential.json" "$output/rec_0055-parallel.json"; then
  echo "JSON results are byte-for-byte identical."
else
  exit 1
fi

echo "All artifacts: $output"

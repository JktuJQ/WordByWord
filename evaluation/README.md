# Evaluation

A separate JVM module providing WER/CER, cpWER, and DER scorers.
It is not included in the APK.
Published results are in [artifacts/test-results](../artifacts/test-results/); measurement conditions are in [docs/TEST_RESULTS.md](../docs/TEST_RESULTS.md).

## Data

| Directory | Contents |
| --- | --- |
| `fixtures/asr/` | MINDS-14 and a reference manifest |
| `fixtures/asr-native-rate/` | Audio for decoding checks at the original sample rate |
| `fixtures/diarization/` | Two one-minute test examples with RTTM |
| `fixtures/dialogs/` | Three assembled Russian conversations |
| `fixtures/podcasts/` | Conversation excerpts and long recordings |
| `fixtures/veche/` | Scripted meetings, raw/hard |

Sources, licenses, and annotation limitations are recorded in `SOURCE.md` beside each dataset.
ASR normalization and model comparisons are described in [asr-quality.md](../docs/asr-quality.md).
Provisional conversation references are not independently verified manual annotations.

Preparing conversation datasets requires Python 3.11+ without third-party packages and FFmpeg/FFprobe:

```sh
python3 evaluation/scripts/prepare_podcasts.py
python3 evaluation/scripts/prepare_veche.py
python3 evaluation/scripts/check_corpus.py evaluation/fixtures/podcasts/manifest.json --audio
python3 evaluation/scripts/check_corpus.py evaluation/fixtures/veche/manifest.json --audio
```

Audio and intermediate results are excluded from Git.
Dataset documents describe downloads and preparation caches.

## Scorers

Run these commands from the repository root.

```sh
./gradlew :evaluation:scoreAsr --args='evaluation/fixtures/asr/manifest.json evaluation/results/asr-hypotheses.json evaluation/results/asr-report.json'
./gradlew :evaluation:scoreDiarization --args='evaluation/fixtures/diarization/rec_0055.rttm evaluation/results/rec_0055.hyp.rttm 60'
./gradlew :evaluation:scoreTranscripts --args='evaluation/fixtures/podcasts/manifest.json evaluation/results/hypotheses evaluation/results/transcripts-report.json evaluation short --allow-provisional'
```

- `scoreAsr`: JSON with a `runs` or `samples` array containing `file` and `hypothesis` fields.
Each manifest file requires exactly one hypothesis; if `status` is present, it must be `READY`.
- `scoreDiarization`: reference RTTM, hypothesis RTTM, recording duration in seconds, and optional processing time for RTF.
- `scoreTranscripts`: a directory of exported `<id>.json` transcripts, a development/evaluation split, and a short/long/raw/hard protocol as defined in the manifest.
Use `--allow-provisional` for provisional annotations.

WER/CER measure text errors; `lexical_wer` and `lexical_cer` exclude punctuation.
Numbers are canonicalized according to scorer rules.
cpWER matches speaker text streams using an optimal permutation.
DER includes missed speech, false speech, and speaker confusion, with collar=0 and overlapping speech included.
DER from final JSON utterances includes ASR alignment effects; direct RTTM DER evaluates diarization separately.

## Android checks

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class dev.wordbyword.app.evaluation.DiarizationHypothesisTest dev.wordbyword.app.test/androidx.test.runner.AndroidJUnitRunner
```

Available checks: `AsrHypothesisTest`, `DialogsHypothesisTest`, `CommonVoiceHypothesisTest`, `DiarizationHypothesisTest`, `InferenceBenchmarkTest`, and `SummaryFeatureTest`.
Summarization requires explicit opt-in with `-e runSummary true`.
Record the device identifier and check instrumentation results, not just the adb exit code.

## Speed and memory

`benchmark.sh` records timing and sampled PSS/RSS while processing one recording through the normal UI.

```sh
bash evaluation/benchmark.sh parallel 60
```

For an automated sequential/parallel comparison:

```sh
bash evaluation/compare-inference.sh
```

The script builds both APK modes, starts an isolated AVD, and processes `rec_0055.flac` through the production pipeline.
It compares JSON output and saves reports in `evaluation/results/`.
On a memory-constrained machine, build before starting the emulator.
Sampled peak memory may miss a brief maximum.

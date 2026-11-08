# ASR quality — #32

The target device is Sony Xperia 10 V.
Record the Android version, RAM, and phone results after testing.
The measurements below are from Linux x86-64, not phone timing.

## Text comparison

The hackathon presentation requires WER but does not specify normalization or tokenization.
For team measurements, we use `ru-punctuation-v2-cardinal-integers`:

- Primary WER/CER include punctuation; punctuation marks are separate WER tokens.
- Case and the distinction between Russian е/ё are ignored; quotation marks and dashes are canonicalized.
- Non-negative cardinal integers are compared by value, including common Russian inflected forms.
- `4 892` and “четыре тысячи восемьсот девяносто два”, or `2 млн` and “двух миллионов”, are equivalent.
- `%` and the Russian word “процент” after a number are equivalent; the unit is preserved in lexical scoring too.
- Leading zeros are preserved; “тысячи лет” is not converted to “1000 лет”.
- Ordinals, fractions, and ambiguous standalone forms such as “сорока” are not automatically mapped to digits.

Normalization is applied to both sides only during scoring and does not change the transcript or export.
Additional `lexical_wer`/`lexical_cer` metrics exclude punctuation and separate word errors from formatting errors.
Historical punctuation-free metrics are not directly comparable with the new primary WER.

## References

In FLEURS, `reference` contains normalized transcription without punctuation.
For primary WER, `reference_raw` was added from the same sample's original `raw_transcription`.
For all 30 recordings, IDs and the original normalized transcription were checked against the stored reference.
The source is recorded in `evaluation/fixtures/asr/fleurs-validation.json`.
Primary WER/CER use `reference_raw`; lexical metrics use the previous `reference`.

MINDS-14 has no verified punctuation reference.
We compare lexical metrics on that dataset; primary WER is calculated but is not used for model selection.
Source annotation errors are not corrected to match a candidate's output.

## Conditions

sherpa-onnx 2.0.1, CPU, two ASR threads, Silero VAD, maximum speech duration 15 s, context 200 ms.
Input is identical PCM16 LE, 16 kHz, mono.
Timing includes VAD and ASR loading for each file but excludes external PCM preparation.
Runs are sequential, limited to 3 GB RAM and two CPUs, without an emulator.
VAD settings were unchanged between candidates.

All tables were calculated with the same final scorer from saved hypotheses.
Rescoring reports are in `evaluation/results/asr32/*-score.json`.
Original files without the `-score` suffix contain hypotheses, intervals, timing, and model SHA-256 hashes; their embedded intermediate metrics may differ.
The results directory is excluded from Git.

## FLEURS validation

30 recordings, 358.44 s; not the full official test or multi-speaker conversations.

| Model | WER with punctuation | CER with punctuation | Lexical WER | Lexical CER | Time | RTF |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| GigaAM v2 RNNT int8 | 18.23% | 4.75% | 4.25% | 2.10% | 66.64 s | 0.186 |
| GigaAM v3 RNNT int8, punctuation | 7.09% | 2.31% | 3.74% | 1.63% | 51.89 s | 0.145 |
| Parakeet TDT 0.6B v3 int8 | 9.99% | 3.14% | 6.97% | 2.45% | 88.16 s | 0.246 |

V3 improves aggregate metrics, but individual words can still be misrecognized; for example, v2 renders “оцелоты” as “ацелоты”.
Extra periods remain at VAD boundaries: “ночного видения. И почти незаметно передвигаться” instead of a single sentence.

## MINDS-14

Development: 30 recordings, 271.41 s; evaluation: a separate 60 recordings, 470.78 s.
The split was made before these experiments and was not adjusted based on candidate results.
These splits separate recordings; speaker independence has not been established.

| Model | Dev: lexical WER / CER | Evaluation: lexical WER / CER | Evaluation: time / RTF |
| --- | ---: | ---: | ---: |
| GigaAM v2 int8 | 22.43% / 13.83% | 11.89% / 5.65% | 111.16 s / 0.236 |
| GigaAM v3 punct int8 | 22.90% / 13.55% | 11.66% / 5.82% | 82.43 s / 0.175 |
| Parakeet v3 int8 | 23.13% / 14.84% | 19.00% / 11.05% | 151.17 s / 0.321 |

V3's advantage on MINDS is small and inconsistent; statistical significance was not assessed.
Using the larger Parakeet model did not improve results under these conditions.

## Decision

This work prepared GigaAM v3 punctuation int8 as a replacement for v2.
The basis was fewer punctuation and word errors on FLEURS without a substantial lexical WER regression on MINDS.
Xperia validation remains a condition for accepting the build; the issue was left open at this stage.

## Android validation

An isolated API 36 x86-64 emulator with 4096 MiB RAM and two virtual CPUs; the emulator process was limited to two CPUs and 6 GB host memory.
Wi-Fi and mobile data were disabled; models were read from the APK through `AssetManager`.
Gradle was not running during the test.

FLEURS: 30/30 successful runs; WER with punctuation 6.95%, CER 2.28%, lexical WER 3.40%, lexical CER 1.57%.
Total ASR run time was 119.02 s, RTF 0.332; this includes AndroidAudioPreparer, VAD, and model loading for each file.
Process `VmHWM` was 809.88 MiB; this is peak RSS for the ASR run, not PSS for the full pipeline.
Reports: `evaluation/results/asr32/android-fleurs-v3.json` and `android-fleurs-v3-score.json`.

The full pipeline with parallel ASR and diarization processed `rec_0055.flac` in 32.736 s, RTF 0.546.
Instrumentation completed successfully; the export contains a root `transcript` array and four utterances.
This is a functional check: no text reference for WER scoring of this recording is available here.
Phone timing and memory have not yet been measured; cancellation with the new model was not separately checked on Android.
JVM tests and `lintDebug` passed; lint retained existing UI warnings.

Candidates were downloaded from official sherpa-onnx releases:

- [GigaAM v3 RNNT punctuation](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-transducer-punct-giga-am-v3-russian-2025-12-16.tar.bz2), MIT; SHA-256 `f9620a0099019c6afcee26525ef9ed3297fa50dd5691c1902af0c948fc1a470b`.
- [Parakeet v3 int8](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2), [NVIDIA model](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3), CC-BY-4.0; SHA-256 `1495e91b59d7fc53ddca91f3431758ec99f8fa253fdb0e1e851be0f72b14b0f9`.

PR #44 requires checking v3 BPE tokens: assumptions about v2 character tokens cannot be reused unchanged.

# Test methodology and results

## Interpreting the results

WER/CER measure word/character errors; lexical metrics exclude punctuation.
cpWER accounts for speaker attribution using an optimal ID mapping over the entire recording.
DER measures missed speech, false speech, and speaker confusion; when evaluated from final JSON, it also depends on exported utterance durations.
Lower error values are better, but percentages from different datasets and normalization rules are not directly comparable.

Methods, normalization parameters, data provenance, commands, and historical measurements are documented in [evaluation/README.md](../evaluation/README.md).
Model comparisons and GigaAM v3 validation are in [asr-quality.md](asr-quality.md); measurement commands are in [evaluation/README.md](../evaluation/README.md).

## ASR

| Dataset | Size | Lexical WER | WER with punctuation | Conditions and source |
| --- | ---: | ---: | ---: | --- |
| FLEURS validation | 30 recordings | 3.40% | 6.95% | GigaAM v3 int8, Android API 36 x86-64; [comparison report](asr-quality.md#android-validation) |
| MINDS-14 | 120 recordings | 15.59% | 40.00% | User run on November 8; MINDS has no verified punctuation reference |
| Astrology | 189.778 s | 9.23% | 18.71% | Development, full emulator pipeline, [CSV](../artifacts/test-results/results.csv) |
| Movie | 183.125 s | 14.54% | 24.03% | Evaluation recording, same run |
| Mavrodi | 193.739 s | 22.83% | 29.89% | Evaluation recording, same run |

The two evaluation conversation recordings together yielded 18.92% lexical WER and 27.09% WER with punctuation; development is excluded from this aggregate.
Conversation references are provisional, including punctuation; these values are not a final accuracy assessment against manually verified annotations.
The samples are continuous prefixes of sources from PR #48, not concatenations of short independent clips.
They have already been reviewed during error analysis and are not a new blind test for further parameter tuning.
The instrumentation runner used for this run is not included in the user APK.

## Full pipeline: speakers and resources

| Recording | Speakers: reference / result | cpWER | DER from JSON utterances | Time | RTF |
| --- | ---: | ---: | ---: | ---: | ---: |
| Astrology | 3 / 4 | 46.88% | 22.10% | 100.63 s | 0.530 |
| Movie | 3 / 4 | 28.83% | 16.16% | 103.18 s | 0.563 |
| Mavrodi | 3 / 3 | 89.27% | 50.30% | 79.54 s | 0.411 |

Conditions: Android 16 / API 36, x86-64, four online CPUs, `parallelInference=true`, Wi-Fi and mobile data disabled.
Android reported MemTotal of 2,531,836 KiB; this is guest system memory, not the application's available heap.
Total audio duration was 566.642 s; the three processing runs took 283.341 s.
Timing includes import into private storage, the production pipeline, saving, and export, but excludes adb transfer and instrumentation startup.
Maximum process VmHWM was 863,468 KiB, approximately 843.2 MiB RSS; periodic PSS/RSS sampling can miss the peak.
These are emulator results, not Sony Xperia 10 V measurements.

The application base commit was `9b2ce73a2c15410a5e43ad63572514734b904950`; the test runner had local changes.
The tested APK SHA-256 was `5120dcf7ae44d48efaae1bfa09b3e19149c04623dd7e28b80302a964f1cab5be`.
Association with this APK does not automatically extend these results to later builds.
Numerical rows are retained in [artifacts/test-results/results.csv](../artifacts/test-results/results.csv); hypotheses and complete local reports are not committed.

## Build and phone validation

The owner confirmed installation and operation on a phone, but its model, Android version, RAM, checked scenarios, and metrics were not recorded for that confirmation.
Final phone validation follows the [acceptance checklist](build.md#phone-acceptance-checklist).

A new report must record:

- commit and APK SHA-256, model versions and hashes;
- phone model, Android version, RAM, and execution settings;
- recording, duration, reference, and reference verification status;
- time, RTF, memory measurement method, and peak memory;
- text, speaker, and timestamp quality;
- checked scenarios, discovered defects, and unperformed checks.

Passing JVM tests and successful instrumentation do not replace model quality measurements.

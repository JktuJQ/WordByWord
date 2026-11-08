# Dialogs: assembled test conversations

Source: [Langswap Dialogs](https://huggingface.co/datasets/langswap/dialogs-ru-emotional-conversations), by Ilya Shigabeev and Ilya Latyshev.
The source revision and downloaded file SHA-256 checksums are recorded in `composition.json`; distribution terms are in `LICENSE.md`.
The authors' description is retained in `UPSTREAM_README.md`.

These are three **artificially assembled conversations from real actor recordings**, not synthesized speech or original continuous meetings.
Files contain sequential speech without deliberately added noise or overlap.

| File | Voices | Duration | Utterance selection |
| --- | --- | --- | --- |
| `audio/dialogs_md.wav` | Masha, Dima | 71.915 s | `masha_dima_part1_1..12`, original numerical order |
| `audio/dialogs_ms.wav` | Masha, Sveta | 75.326 s | `masha_sveta_part1`: 1–7, 10–14, original numerical order |
| `audio/dialogs_msd.wav` | Masha, Sveta, Dima | 80.082 s | Cooking topic from `masha_sveta_part1` and `masha_dima_part10`; exact order in `composition.json` |

Speakers receive `speaker_1`, `speaker_2`, … in order of first appearance in each file.
The mapping to original M/S/D IDs is retained in `composition.json`.
All three voices appear in the third file.
Utterances `masha_sveta_part1_8.wav` and `_9.wav` are listed in the metadata but missing from the published WAVs at the selected revision, so they are skipped.

## Reference status

- Text comes **unchanged** from upstream `metadata.csv`; no independent listening check was performed.
Do not describe these references as error-free.
- `manifest.json` is compatible with `ScoreAsr`: `file` is relative to this directory, and `reference` concatenates source texts in playback order.
- `transcripts/*.json` contains a root `transcript` array with `speaker_id`, `start_ms`, `end_ms`, and `text` fields.
- JSON and `rttm/*.rttm` timestamps are **source clip boundaries**, including possible silence inside clips.
RTTM is provisional, not an accurate speech-activity reference for DER.
Before comparing diarization quality, check and correct speech boundaries by listening and inspecting waveforms.
- Exact clip positions in samples are stored in `composition.json`; JSON is rounded to milliseconds and RTTM to microseconds.

## Preparation

Dependencies: Python 3.10+ (standard library only), FFmpeg on PATH.
From the repository root:

```sh
python3 evaluation/scripts/prepare_dialogs.py
python3 evaluation/scripts/prepare_dialogs.py --offline
```

FFmpeg is available in `nix develop`; provide Python separately.
Only metadata and selected WAVs are downloaded, not the entire corpus.
The default cache is `evaluation/results/dialogs-source-cache` (ignored by Git); for persistent storage, pass `--cache /path/to/cache` on both runs.
When `composition.json` is present, preparation uses its pinned revision, utterance order, and source checksums.
Offline rebuilding requires the cache.

Source WAVs are converted to mono at 44,100 Hz with FFmpeg.
A 250 ms digital silence is inserted between clips; original silence is retained.
The result is PCM16 WAV without loudness normalization, crossfades, or word trimming.
Actual source WAV parameters are recorded in `composition.json`.
The 44.1 kHz rate exercises Android audio preparation, including downsampling.
This does not test phone microphone acoustics.
Check bitwise reproducibility with the same FFmpeg version.
The 28 selected unique source WAVs were all 44,100 Hz mono PCM16; the stereo recordings mentioned in the general corpus description are not present here.

Verify integrity from this directory:

```sh
sha256sum -c SHA256SUMS
```

## Usage

Process all three files through the Android app and save hypotheses in `evaluation/results/dialogs/`.
Example scoring command from the repository root:

```sh
./gradlew :evaluation:scoreAsr --args='evaluation/fixtures/dialogs/manifest.json evaluation/results/dialogs/asr-hypotheses.json evaluation/results/dialogs/asr-report.json'
```

Hypothesis `file` values must match the manifest, for example `audio/dialogs_md.wav`.
This is a **smoke-test set**, not a tuning/independent-test split: the third recording reuses utterances from the second, and voices are shared across files.
Do not treat the recordings as independent train/dev/test splits or draw statistical conclusions from three short assembled conversations.

Preserve the included license and its restrictions when distributing the data.

## Completed checks (2026-11-07)

- All three WAV headers, sample counts, 60–90 s durations, and nonzero audio content were checked.
- Each file contains 12 source clips.
Speaker ID counts are 2/2/3; each of the three voices appears at least twice in the third file.
- Texts match upstream metadata in assembly order; JSON/RTTM/sample-position consistency and digital silence between clips were checked.
- All artifact SHA-256 checksums were checked.
Rebuilding with `--offline` produced bitwise identical files using the same FFmpeg version.
- `:evaluation:test` passed.
`:evaluation:scoreAsr` accepted the new manifest in a reference-versus-itself check.
Zero WER in this check validates the format and scorer, **not ASR quality**.

## Application run (2026-11-07)

All three files were processed through the real `AndroidAudioPreparer`/`SherpaSpeechRecognizer`/`SherpaOnnxSpeakerDiarizer` using `DialogsHypothesisTest` instrumentation on an Android API 36 arm64-v8a emulator, not a physical device.

ASR (`:evaluation:scoreAsr`): WER 5.81%, CER 1.64%, exact matches 0/3.
The improvement over MINDS-14 (WER 14.09%, `evaluation/README.md`) is expected for clean studio speech without telephone compression or language switching.

Diarization (`:evaluation:scoreDiarization`) uses collar=0 and RTTM clip boundaries rather than verified speech activity (see above), so Missed/False alarm must not be interpreted as VAD accuracy here:

| File | Speakers (reference/hypothesis) | DER | Confusion | Missed |
| --- | --- | ---: | ---: | ---: |
| `dialogs_md` | 2 / 2 | 23.98% | 1.78 s | 14.39 s |
| `dialogs_ms` | 2 / **1** | 51.28% | 31.00 s | 5.97 s |
| `dialogs_msd` | 3 / **4** | 42.59% | 24.72 s | 7.98 s |

**Diarization is substantially worse than on `rec_0055`/`rec_0273`**, where speaker counts matched and DER was 13.5–28.0%.
On `dialogs_ms`, the model did not separate the two speakers at all (one cluster instead of two); on `dialogs_msd`, it produced an extra cluster.
Short, rapidly alternating actor turns, unlike the longer continuous segments in `rec_0055`/`rec_0273`, are a possible cause.
This remains a hypothesis, not a finding verified by tuning `clustering.threshold` for this dataset.
Listening and manual boundary checks were not performed; these DER values are not a final assessment of diarization on rapid speaker changes.

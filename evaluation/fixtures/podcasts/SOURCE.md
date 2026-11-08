# Continuous Russian conversations

Source: [leshinsky/ru-youtube-diarization](https://huggingface.co/datasets/leshinsky/ru-youtube-diarization), revision `e3aaa8a28211afe56c913fcacb26db029b1fb10e`.
The dataset card declares Apache-2.0; rights to the original videos have not been independently verified.
Audio is downloaded locally and is not stored in Git.
`sources.json` records source paths, checksums, and annotation validation results.

There are 24 cases from five source conversations: 20 short excerpts and four long files.
For large WAVs, only the first ten minutes are used, ending at an utterance boundary.
Opus files are retained in full if their annotations pass structural validation.
Short excerpts start after 30, 150, 300, and 450 seconds, with a target duration of 90 seconds.
Boundaries are shifted to the nearest safe utterance boundaries without cutting text or active speech according to the published annotations.
Selection was made before running models, without filtering by WER/DER.

| Source | Split | Group | Cases |
| --- | --- | --- | ---: |
| `astrology` | development | interview-shared-hosts | 4 short + 1 long |
| `yCX4MRy14zw` | development | interview-shared-hosts | 4 short |
| `p0_iIC13bs8` | development | interview-shared-hosts | 4 short + 1 long |
| `movie` | evaluation | movie | 4 short + 1 long |
| `mavrodi` | evaluation | mavrodi | 4 short + 1 long |

Grouping three interviews in development is a conservative safeguard against shared hosts and related episodes.
Voice independence across all sources has not been checked biometrically.
There is no guarantee that these public recordings are absent from model training data.
The split applies only to our parameter tuning.

## Reference quality

The source contains a Label Studio export with `manual`, `prediction`, and `prediction-changed` entries.
These are published annotations with edited predictions, not references independently verified by our team.
Texts are retained without correction based on the evaluated model's output.
JSON and RTTM timestamps come from the same intervals and remain provisional.
Their DER must not be presented as millisecond-accurate evaluation.

Validation found reversed intervals:

- `yCX4MRy14zw`: one interval at 296.423–296.403 s.
It lies outside the selected short windows; the full file is excluded from scoring.
- `sFt2qKDPrj4`: eight reversed intervals, including discrepancies exceeding ten seconds.
This source is excluded entirely; reasons and original values are retained in `sources.json`.

Intervals are not automatically reordered or stretched.
Short windows intersecting a corrupted region are rejected.
Before drawing final conclusions, check text, short-answer attribution, overlaps, music, and speech boundaries by listening across the entire evaluated section.
After verification, update references and checksums; do not change them to match the model.

## Download and preparation

Python 3.11+ with the standard library and FFmpeg/FFprobe on PATH are required.
No additional Python packages are needed.
From the repository root:

```sh
python3 evaluation/scripts/prepare_podcasts.py
python3 evaluation/scripts/check_corpus.py evaluation/fixtures/podcasts/manifest.json --audio
```

Approximately 263 MB is downloaded: two PCM WAV prefixes, three small Opus files, and annotations.
The server must support HTTP Range; if it ignores the range, downloading the entire gigabyte-scale WAV is prohibited.
Checksums are verified for annotations, Opus files, and requested WAV ranges.
Full source WAV checksums are taken from upstream; the full WAVs were not downloaded or verified locally.
Cache and audio are stored in ignored `cache/` and `audio/` directories.
Preparation used FFmpeg 8.1.3.
A mismatch with the pinned manifest stops preparation; `--refresh-manifest` is for deliberate updates after review.

With a retained cache, offline rebuilding is available:

```sh
python3 evaluation/scripts/prepare_podcasts.py --offline
```

## Preparation validation (2026-11-07)

Checksums, audio durations, intervals, and JSON/RTTM consistency were checked.
Offline rebuilding from the retained cache reproduced the pinned manifest.
Structural validation and reference-versus-itself scoring do not measure model quality.
Phone hypotheses and independent acoustic verification of references have not yet been obtained.

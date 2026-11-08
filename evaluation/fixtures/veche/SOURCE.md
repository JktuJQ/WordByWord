# Veche: scripted meetings

Source: [artem-tish/veche-bench](https://huggingface.co/datasets/artem-tish/veche-bench), revision `a0ab08d6887b64995d076b475a87310953a51138`.
Authors: the [veche-bench](https://github.com/veche-bench) project.
Declared license: [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).
All nine meetings are used in both `data_raw` and `data_hard` variants; format changes and audio conversion were performed by the WordByWord team.
Texts and IDs come from CSV; seconds are rounded to milliseconds, and names are replaced with stable `speaker_N` IDs.
These are scripts read by volunteers and assembled into meetings, not spontaneous podcasts.
LLM-generated summary references are not used.

There are 18 cases, including conversations with two to ten participants.
Raw/hard variants share text and voices; they support paired robustness comparisons rather than increasing the number of independent examples.
All meetings are in development because the same volunteers may have voiced different scripts.
Different versions or roles voiced by the same volunteer must not be treated as independent tests.

Annotations are based on the script and the assembly timing of source utterances.
Agreement between spoken text and script, and the accuracy of speech boundaries, have not been independently checked.
References are provisional; structural validation does not establish acoustic DER accuracy.

## Download and preparation

Python 3.11+ with the standard library, FFmpeg, and FFprobe are required.
From the repository root:

```sh
python3 evaluation/scripts/prepare_veche.py
python3 evaluation/scripts/check_corpus.py evaluation/fixtures/veche/manifest.json --audio
```

Source WAVs total approximately 471 MB.
Audio SHA-256 checksums and CSV Git blob IDs are verified against the pinned revision.
Git stores the manifest, source links and checksums, JSON, and RTTM; WAV, FLAC, and cache files are ignored.
Local preparation used FFmpeg 8.1.3.
To rebuild using the retained cache:

```sh
python3 evaluation/scripts/prepare_veche.py --offline
```

## Preparation validation (2026-11-07)

Checksums, audio durations, intervals, and JSON/RTTM consistency were checked.
Offline rebuilding from the retained cache reproduced the pinned manifest.
Structural validation and reference-versus-itself scoring do not measure model quality.
Phone hypotheses and independent acoustic verification of references have not yet been obtained.

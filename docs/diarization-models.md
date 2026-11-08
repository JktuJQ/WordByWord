# Diarization

The app uses sherpa-onnx 2.0.1 on CPU.
`SherpaOnnxSpeakerDiarizer` obtains speech intervals, computes voice features, and regroups intervals by speaker.

## Models

| Component | Source | License |
| --- | --- | --- |
| pyannote/segmentation-3.0 int8 segmentation | [ONNX archive](https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2) | MIT |
| ERes2Net VoxCeleb | [3D-Speaker ONNX](https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_eres2net_sv_en_voxceleb_16k.onnx) | Apache-2.0 |
| sherpa-onnx AAR/JNI | [Release 2.0.1](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v2.0.1) | Apache-2.0 |

URLs and SHA-256 checksums are pinned in `app/gradle/models.gradle`.
`prepareDiarizationModels` verifies downloaded files and places models in assets and the library in `app/vendor/`.
These directories are excluded from Git.
Weights are packaged in the APK and available offline after installation.

## Algorithm

- Segmentation processes the entire 16 kHz mono PCM recording.
- Voice features use the central portion of each interval, up to eight seconds long.
- Anchor intervals of at least 1.5 seconds are grouped with average-linkage clustering using cosine distance and a threshold of 0.45.
- Intervals of at least 0.3 seconds are assigned to the nearest established centroids.
- If voice features are insufficient, the nearest already assigned interval in time is used.
- Time boundaries are passed to `OverlapTranscriptAssembler` for text alignment.

The speaker count is inferred automatically, not supplied from the reference.
Memory use grows with recording duration: PCM is loaded entirely into `float[]`.
Cancellation does not interrupt an individual native segmentation call; cancelled results are not saved.

## Validation

`DiarizationHypothesisTest` runs the application's algorithm on Android and saves RTTM hypotheses.
`ScoreDiarization` compares them with RTTM references independently of ASR.
Commands are in [evaluation/README.md](../evaluation/README.md); published results are in [TEST_RESULTS.md](TEST_RESULTS.md).

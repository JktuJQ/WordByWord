# Speech recognition

The app uses sherpa-onnx 2.0.1, Silero VAD, and GigaAM v3 RNNT int8 with punctuation.
VAD detects speech intervals; ASR recognizes each interval with 200 ms of context and a maximum speech duration of 15 s.
ASR uses two CPU threads.
`SherpaSpeechRecognizer` produces timing information and passes it to diarization alignment.

## Sources

| Component | Source | License |
| --- | --- | --- |
| sherpa-onnx AAR/JNI | [Release 2.0.1](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v2.0.1) | Apache-2.0 |
| GigaAM v3 RNNT, punctuation | [sherpa-onnx ONNX archive](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-transducer-punct-giga-am-v3-russian-2025-12-16.tar.bz2), [original model](https://github.com/salute-developers/GigaAM) | MIT; the archive's `LICENSE` is included in assets |
| Silero VAD | [ONNX model](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx), [repository](https://github.com/snakers4/silero-vad) | MIT |

URLs and SHA-256 checksums are pinned in `prepareAsrModels` in `app/gradle/models.gradle`.
The GigaAM archive is approximately 162 MiB.
The APK includes `encoder.int8.onnx`, `decoder.onnx`, `joiner.onnx`, `tokens.txt`, `silero_vad.onnx`, and the GigaAM license.

## Model preparation

```sh
./gradlew assembleDebug
```

`prepareAsrModels` downloads and verifies files in `app/build/asr-downloads/`, then places models in `app/src/main/assets/asr/`.
Both directories are excluded from Git.
Source checksums are Gradle task inputs; prepared files are outputs.
Changing a version or output file triggers preparation again; files from the previous model do not prevent an update.

Models are read through `AssetManager` without copying them to a separate phone directory.
The first build requires network access, but installation and recording processing do not.
The APK contains the models and works offline immediately after installation.

## Quality and limitations

Comparisons with GigaAM v2 and Parakeet, normalization rules, and measurement conditions are in [asr-quality.md](asr-quality.md).
The v3 choice is based on Linux runs; validation on Sony Xperia 10 V remains required before submission.
No separate punctuation postprocessor or generative LLM is used for ASR.
Punctuation comes from the model; VAD boundaries may introduce extra periods and capital letters.
ASR text is saved without scorer normalization.

V3 uses BPE tokens, so word-level timestamp extraction must support subword tokens rather than only individual characters.
Token and timestamp handling is implemented in `SherpaSpeechRecognizer`.

Historical v2 results referenced in `evaluation/README.md` used the previous punctuation-free scorer and are not results for the new model.

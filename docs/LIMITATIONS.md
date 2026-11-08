# Current limitations

This document describes `main`, not promises made by experimental branches.
Required functionality and open questions for the organizers are recorded in [hackathon.md](hackathon.md).

## Quality

- Accuracy on read speech does not guarantee the same accuracy on conversations, noise, names, or interruptions.
- ASR returns VAD-fragment timestamps rather than individual word timestamps; a speaker change within a block can cause incorrect text attribution.
- Overlapping speech may be recognized incompletely; matching the reference speaker count does not establish correct diarization.
- Conversation datasets have provisional references; our team has not independently verified their punctuation, text, or boundaries.
- MINDS-14 has no verified punctuation reference, so lexical WER is the primary metric for that dataset.
- Small samples and public recordings do not guarantee independence from model training data or performance on unseen expert recordings.

Normalization rules and historical model comparisons are described in [asr-quality.md](asr-quality.md).
Numerical results and their conditions are recorded in [TEST_RESULTS.md](TEST_RESULTS.md).

## Resources and lifecycle

- Memory use grows with recording duration: diarization loads PCM into a `float[]` array.
- Parallel processing holds model resources simultaneously; device RAM is not the same as the application's available Java heap.
- Cancellation is checked between operations but does not interrupt an individual sherpa-onnx JNI call.
- Processing runs in the Application process; there is no foreground service or recovery of unfinished work after process termination.
- Completed results can be reopened; interrupted processing does not resume automatically.
- Phone speed, thermal behavior, and energy use cannot be inferred from emulator results.

Execution and storage details are in [ARCHITECTURE.md](ARCHITECTURE.md).

## Build and submission

Android 8.0+ and a supported ARM64 or x86-64 ABI are required.
The first build requires internet access for dependencies and models; once models are bundled in the APK, processing runs offline.
The debug APK uses a local debug key; updating with another signing key may require uninstalling the app, which deletes its library.
The APK and demo video need accessible download links; instructions and temporary CI artifacts do not replace published submission materials.
The available requirements do not specify exact expert recording durations, codecs, speaker counts, or official WER normalization.

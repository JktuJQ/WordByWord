# Architecture

The Android app is written in Java with an XML interface.
The entire user workflow runs on the device without sending audio or text to a server.

## Components

| Package | Responsibility |
| --- | --- |
| `core` | Data types, audio preparation, ASR, diarization, and utterance alignment |
| `processing` | Pipeline execution, progress, and cancellation |
| `storage`, `json` | File storage and serialization |
| `export` | JSON for external use |
| `summary` | Selection of key transcript passages using a local LLM |
| `ui` | Application state, documents, playback, and screens |
| `ui/widget` | Decorative Android views |

`WordByWordApplication` creates the controller, repository, and executors.
Activities observe controller state and forward user actions.
`PlaybackControls` connects buttons and the SeekBar to `TranscriptPlayback` on the recording and transcript screens.

## Pipeline

1. Import saves a private audio copy through `FileTranscriptRepository`.
2. `AndroidAudioPreparer` decodes the recording with MediaExtractor/MediaCodec into 16 kHz mono PCM.
A filter is applied before downsampling.
3. `SherpaSpeechRecognizer` uses Silero VAD and GigaAM v3 RNNT int8.
4. `SherpaOnnxSpeakerDiarizer` obtains speech intervals and groups ERes2Net VoxCeleb voice features by speaker.
5. `OverlapTranscriptAssembler` assigns text to speakers by temporal overlap and merges adjacent fragments from the same speaker.
6. The result is saved, displayed, and exported through `TranscriptJsonExporter`.

ASR and diarization run in parallel by default.
The `-PparallelInference=false` build option enables sequential execution.
Computation and file operations run off the UI thread.
Cancellation is checked between operations and stages; an individual JNI call is not interrupted immediately.

## Data and storage

`Recording` contains the recording ID, URI, title, and duration when available.
`Transcript` combines the recording, speakers, and utterances with text and time boundaries.
`RecognitionResult` passes text and timed fragments from recognition to alignment.

Storage creates a directory per recording with an audio copy and version 2 `recording.json`.
The previous version 1 format remains readable for compatibility.
Metadata changes use a temporary file and atomic rename.
Incomplete processing does not resume automatically after process termination.
The external JSON schema is described in [PROTOCOL.md](PROTOCOL.md).

## Summarization

`SummaryRunner` manages the operation, `LocalSummarizer` selects passages through Qwen3-0.6B INT4, and `SummaryStore` saves `summary.json` beside the recording.
Summarization does not run concurrently with audio processing.
The result is keyed to the text, speaker names, and algorithm version.
The original transcript and required export remain unchanged.

## Build and validation

`app/gradle/models.gradle` prepares models with SHA-256 verification; `app/gradle/distribution.gradle` packages the APK and build metadata.
The `evaluation` JVM module contains scorers and is not included in the APK.
Android checks run the application's models and pipeline.
Measurement conditions and results are recorded in [TEST_RESULTS.md](TEST_RESULTS.md).

# Formats and processing protocol

## Input

The user selects audio through the Android Storage Access Framework.
Codec support depends on Android MediaExtractor/MediaCodec; the supplied recordings must be tested.
After import, the app stores a private copy of the original; models receive 16 kHz mono PCM.
Temporary PCM files are deleted after completion, failure, or cancellation.

## External JSON

The required schema is recorded in [hackathon.md](hackathon.md).
The root object contains a `transcript` array:

```json
{
  "transcript": [
    {
      "speaker_id": "speaker_1",
      "start_ms": 1250,
      "end_ms": 4380,
      "text": "Good afternoon!"
    }
  ]
}
```

| Field | Meaning |
| --- | --- |
| `speaker_id` | String speaker ID, stable within the transcript |
| `start_ms` | Utterance start in milliseconds from the recording start |
| `end_ms` | Utterance end in milliseconds from the recording start |
| `text` | Utterance text |

A user-assigned speaker name does not replace the ID in the export.
For an unknown speaker, the exporter uses `unknown`, prepending underscores if it conflicts with an existing ID.
Time boundary accuracy depends on speech recognition and alignment with diarization.
Export is implemented in [TranscriptJsonExporter.java](../app/src/main/java/dev/wordbyword/app/export/TranscriptJsonExporter.java).

## Internal types

`Recording` holds the recording, `Transcript` holds speakers and utterances, and `RecognitionResult` holds recognition output.
Components use 16 kHz mono PCM and cooperative cancellation.
Internal storage and execution are described in [ARCHITECTURE.md](ARCHITECTURE.md).
Evaluation methods are described in [evaluation/README.md](../evaluation/README.md).

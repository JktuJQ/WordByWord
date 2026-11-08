# Nuclear IT Hack requirements

Source: [“Hackathon November 2026.pdf”](https://drive.google.com/file/d/1_AxAHkwwtbXcoznz4oYzHFaAC_D_Qffc/view), reviewed on November 6, 2026.
Numbers below refer to PDF pages.
This document records requirements and the team's interpretation, not implementation status.

## Required workflow

Slides 8–11 and 14–17 require an Android app that imports audio from device storage, recognizes speech locally, automatically separates speakers, timestamps utterances, displays a transcript, and exports a JSON file.

Slides 9 and 17 use the term “local LLM”.
The team interprets it as an imprecise reference to local neural speech-processing models; a separate generative LLM is not part of the mandatory plan.
This is the team's interpretation, not an answer from the organizers.
Summarization and other generative processing remain possible extensions.

All core stages run on Android without internet access.
All required models must be on the device before evaluation starts.
Pretrained models and libraries are allowed.
The following are prohibited:

- sending audio to external services;
- cloud APIs for transcription, diarization, or text processing;
- moving computation for the evaluated workflow to a server or computer;
- substituting prepared transcripts for model execution.

Desktop research and model preparation are allowed during development; evaluation must involve fresh processing on the device.
Saving previous transcripts is an app feature, but reopening a saved result does not demonstrate model execution on a new recording.

## External JSON

Slides 14–15 specify a root object containing a `transcript` array:

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

| Field | Type | Meaning |
| --- | --- | --- |
| `transcript` | array | Utterances |
| `speaker_id` | string | Stable speaker identifier |
| `start_ms` | integer | Utterance start in milliseconds |
| `end_ms` | integer | Utterance end in milliseconds |
| `text` | string | Utterance text |

Preserve the original recording timeline and speaker attribution.
Renaming a speaker in the UI does not change the ID.
The external schema does not dictate database formats or internal Java types; conversion happens on export.
Do not replace `speaker_id` with a display name or emit `null` in place of the required string.
The unknown-speaker policy must be explicitly agreed in the export task because the internal contract allows an absent ID, while the slide does not describe this case.

## Evaluation and priorities

According to slide 16, experts install the supplied APK, import three recordings shared by all teams, run transcription and diarization, export results, and compare them with references.
WER and RTF are calculated.
Evaluation cannot be limited to a team's own selected demo recording.

| Criterion, slide 22 | Points |
| --- | ---: |
| Transcription accuracy | 40 |
| Diarization accuracy | 25 |
| Timestamp accuracy | 10 |
| Processing speed | 10 |
| Functionality and UX | 10 |
| Solution defense | 5 |

First establish a working import → processing → review → JSON workflow, then improve text and speaker accuracy.
For team measurements, record the device, Android version, recording and duration, reference, model/runtime versions, settings, and timing boundaries.
RTF is processing time divided by recording duration; WER follows an agreed comparison rule.
The presentation does not specify text normalization rules or scoring thresholds.
Team measurements use a [punctuation-aware rule](asr-quality.md); this is not an official organizer formula.

Slide 18 lists punctuation, progress, and utterance playback as enhancements.
Slide 19 lists editing, interruptions and overlapping speech, noise robustness, memory optimization, and hardware acceleration as further improvements.
Renaming and reopening results remain team product goals but do not take priority over mandatory JSON export.

## Submission materials

According to slides 20–21, the Git repository must provide:

- the application APK;
- source code;
- installation and usage instructions, including model preparation for offline execution;
- architecture and model descriptions;
- known limitations;
- team test results;
- an application demo video.

The organizers request the [repository template](https://github.com/grigorevmp/hacathon_template), containing `src`, `config`, `docs`, `artifacts`, and `README.md`.
Map submission materials to this structure without moving the existing Android `app/` module merely to match a directory name.
Instructions must identify the actual code, APK, model, and report locations.
An expiring CI artifact link does not replace durable access to the submitted APK.

Team members must understand the architecture, models, limitations, and test results, including agent-written code (slides 12 and 22).

## Open questions

- Submission deadline and channel for the repository link.
- Recording formats, durations, languages, speaker counts, and evaluation device.
- Exact metric calculation, text normalization, and speaker ID matching rules.

Do not present these unknowns as agreed constraints.
Add organizer questions and answers with sources.

# Summarization

The “Подытожить” (Summarize) button opens a screen that selects key passages from a completed transcript.
Text is displayed verbatim from the transcript and can be selected and copied.
The original transcript and external JSON remain unchanged.

## Model

Qwen3-0.6B INT4 no-think runs on CPU through LiteRT-LM Android 0.17.1.
Source: [litert-community/Qwen3-0.6B-int4](https://huggingface.co/litert-community/Qwen3-0.6B-int4).
Revision: `56621f0115eba5781051ef9714c06e16e6d40c0b`.
File: `qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm`, 347251840 bytes.
SHA-256: `2df6821ec12702dafd33915e7a1a1adc7c4b053f3672fd9555dfaf3a114c4139`.
The Apache-2.0 license is included in `assets/summary/LICENSE`.

`prepareSummaryModel` in `app/gradle/models.gradle` downloads and checksum-verifies the weights into `app/build/generated/summaryAssets/`.
The model is bundled in the APK and copied to a private no-backup directory when the feature is first used.
Processing does not use the internet.

## Execution

`SummaryRunner` holds operation state in Application and uses the sequential processing executor.
The controller prevents summarization and the audio pipeline from running simultaneously.
The model is loaded once per operation and closed after completion, cancellation, or failure.

Text is split into chunks of up to 512 UTF-8 bytes, preferring sentence and utterance boundaries.
Each chunk contains up to 16 numbered fragments.
The model selects up to three numbers; the app validates the response and displays the corresponding original fragments in order.
The context is 1280 tokens, and the response is limited to 32 tokens.
The screen shows the current chunk and supports cancellation.

The completed result is saved atomically in `summary.json` beside the recording.
The key includes the source text, speaker names, and algorithm version.
Failure or cancellation does not save a partial response.
Unfinished operations do not resume automatically after process termination.

## Limitations

Passage selection can omit important information and lose connections between chunks.
This is not a freely written summary or an ASR correction step.
Weight size is not process memory use; a private model copy is needed in addition to the APK.
Successful emulator generation does not replace phone quality and resource checks.

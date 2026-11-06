package dev.wordbyword.app.core.processing;

import dev.wordbyword.app.core.recording.Recording;
import dev.wordbyword.app.core.transcript.Transcript;

public interface TranscriptionPipeline {
  Transcript transcribe(Recording recording, ProcessingContext context) throws ProcessingException;
}

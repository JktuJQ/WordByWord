package dev.wordbyword.app.core.processing;

import dev.wordbyword.app.core.model.Recording;
import dev.wordbyword.app.core.model.Transcript;

/**
 * Blocking entry point for UI/orchestration adapters. Owns its temporary resources, but not the
 * source recording. Returns a complete result, throws CancellationException for cancellation, or
 * ProcessingException for failure. Persistence and thread/service selection belong to the caller.
 */
public interface TranscriptionPipeline {
  Transcript transcribe(Recording recording, ProcessingContext context) throws ProcessingException;
}

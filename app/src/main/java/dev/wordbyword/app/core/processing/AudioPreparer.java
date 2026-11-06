package dev.wordbyword.app.core.processing;

import dev.wordbyword.app.core.audio.PcmAudio;
import java.net.URI;

/** Blocking boundary; caller owns and closes the returned prepared audio. */
public interface AudioPreparer {
  PcmAudio prepare(URI source, ProcessingContext context) throws ProcessingException;
}

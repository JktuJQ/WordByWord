package dev.wordbyword.app.core.audio;

import dev.wordbyword.app.core.processing.ProcessingContext;
import dev.wordbyword.app.core.processing.ProcessingException;
import java.net.URI;

public interface AudioPreparer {
  PcmAudio prepare(URI source, ProcessingContext context) throws ProcessingException;
}

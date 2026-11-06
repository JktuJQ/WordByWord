package dev.wordbyword.app.core.recognition;

import dev.wordbyword.app.core.audio.PcmAudio;
import dev.wordbyword.app.core.processing.ProcessingContext;
import dev.wordbyword.app.core.processing.ProcessingException;

public interface SpeechRecognizer {
  RecognitionResult recognize(PcmAudio audio, ProcessingContext context) throws ProcessingException;
}

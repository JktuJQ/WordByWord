package dev.wordbyword.app.core.processing;

import dev.wordbyword.app.core.audio.PcmAudio;
import dev.wordbyword.app.core.model.RecognitionResult;

/** Blocking recognition boundary. The caller retains ownership of the audio. */
public interface SpeechRecognizer {
  RecognitionResult recognize(PcmAudio audio, ProcessingContext context) throws ProcessingException;
}

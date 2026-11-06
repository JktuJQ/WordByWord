package dev.wordbyword.app.core.processing;

import dev.wordbyword.app.core.audio.PcmAudio;
import dev.wordbyword.app.core.model.SpeakerSegment;
import java.util.List;

/** Blocking diarization boundary. The caller retains ownership of the audio. */
public interface SpeakerDiarizer {
  List<SpeakerSegment> diarize(PcmAudio audio, ProcessingContext context)
      throws ProcessingException;
}

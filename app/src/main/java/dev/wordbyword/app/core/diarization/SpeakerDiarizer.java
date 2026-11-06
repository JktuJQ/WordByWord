package dev.wordbyword.app.core.diarization;

import dev.wordbyword.app.core.audio.PcmAudio;
import dev.wordbyword.app.core.processing.ProcessingContext;
import dev.wordbyword.app.core.processing.ProcessingException;
import java.util.List;

public interface SpeakerDiarizer {
  List<Segment> diarize(PcmAudio audio, ProcessingContext context) throws ProcessingException;

  record Segment(String speakerId, long startMs, long endMs) {}
}

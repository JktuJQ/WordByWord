package dev.wordbyword.app.core.transcript;

import dev.wordbyword.app.core.diarization.SpeakerDiarizer;
import dev.wordbyword.app.core.processing.ProcessingContext;
import dev.wordbyword.app.core.processing.ProcessingException;
import dev.wordbyword.app.core.recognition.RecognitionResult;
import dev.wordbyword.app.core.recording.Recording;
import java.util.List;

public interface TranscriptAssembler {
  Transcript assemble(
      Recording recording,
      RecognitionResult recognition,
      List<SpeakerDiarizer.Segment> diarization,
      ProcessingContext context)
      throws ProcessingException;
}

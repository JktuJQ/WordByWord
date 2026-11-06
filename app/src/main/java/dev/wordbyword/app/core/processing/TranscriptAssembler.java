package dev.wordbyword.app.core.processing;

import dev.wordbyword.app.core.model.RecognitionResult;
import dev.wordbyword.app.core.model.SpeakerSegment;
import dev.wordbyword.app.core.model.Transcript;
import java.util.List;

/** Combines results without prescribing the alignment/grouping algorithm or model choice. */
public interface TranscriptAssembler {
  Transcript assemble(
      RecognitionResult recognition, List<SpeakerSegment> diarization, ProcessingContext context)
      throws ProcessingException;
}

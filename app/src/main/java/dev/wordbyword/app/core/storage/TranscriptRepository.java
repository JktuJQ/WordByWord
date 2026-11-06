package dev.wordbyword.app.core.storage;

import dev.wordbyword.app.core.recording.Recording;
import dev.wordbyword.app.core.transcript.Transcript;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

public interface TranscriptRepository {
  List<Recording> listRecordings() throws IOException;

  Optional<Transcript> find(String recordingId) throws IOException;

  Recording saveRecording(Recording recording) throws IOException;

  Transcript save(Transcript transcript) throws IOException;

  void delete(String recordingId) throws IOException;

  Transcript update(String recordingId, UnaryOperator<Transcript> transformation)
      throws IOException;
}

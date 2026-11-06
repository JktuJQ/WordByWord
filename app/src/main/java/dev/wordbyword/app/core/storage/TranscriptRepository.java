package dev.wordbyword.app.core.storage;

import dev.wordbyword.app.core.recording.Recording;
import dev.wordbyword.app.core.transcript.Transcript;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

public interface TranscriptRepository {
  List<Recording> listRecordings() throws IOException;

  Optional<Transcript> find(String recordingId) throws IOException;

  Transcript save(Transcript transcript) throws IOException;

  void renameSpeaker(String recordingId, String speakerId, String displayName) throws IOException;
}

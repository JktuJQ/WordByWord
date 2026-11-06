package dev.wordbyword.app.core.storage;

import dev.wordbyword.app.core.model.Recording;
import dev.wordbyword.app.core.model.RecordingTranscript;
import dev.wordbyword.app.core.model.Transcript;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Blocking storage boundary, independent of a database/serialization format. Call off the UI
 * thread. Returned values are snapshots; implementations document concurrent access support.
 */
public interface TranscriptRepository {
  List<Recording> listRecordings() throws IOException;

  Optional<RecordingTranscript> find(String recordingId) throws IOException;

  /**
   * Saves a complete result under recording.id(), copying/retaining the original audio as needed
   * for reopening after process restart. Returns metadata with a durable, storage-managed audio
   * URI. On failure, must not expose a partially saved result as successfully completed.
   */
  RecordingTranscript save(Recording recording, Transcript transcript) throws IOException;

  /**
   * Renames only this recording's speaker, retaining its ID and all utterance associations. Missing
   * recording/speaker IDs are reported with NoSuchElementException.
   */
  void renameSpeaker(String recordingId, String speakerId, String displayName) throws IOException;
}

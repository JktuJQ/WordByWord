package dev.wordbyword.app.core.model;

import java.util.Objects;

/** A recording and its transcript, as returned by storage. */
public record RecordingTranscript(Recording recording, Transcript transcript) {
  public RecordingTranscript {
    Objects.requireNonNull(recording);
    Objects.requireNonNull(transcript);
  }
}

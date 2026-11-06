package dev.wordbyword.app.core.model;

import java.util.Objects;

/** Diarization output. Segments may overlap; speaker IDs are local to this recording. */
public record SpeakerSegment(String speakerId, TimeRange time) {
  public SpeakerSegment {
    Objects.requireNonNull(speakerId);
    Objects.requireNonNull(time);
  }
}

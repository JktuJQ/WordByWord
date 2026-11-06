package dev.wordbyword.app.core.model;

import java.util.Objects;
import java.util.Optional;

/** An assembled utterance. An empty speakerId means that the speaker could not be determined. */
public record Utterance(Optional<String> speakerId, String text, TimeRange time) {
  public Utterance {
    Objects.requireNonNull(speakerId);
    Objects.requireNonNull(text);
    Objects.requireNonNull(time);
  }
}

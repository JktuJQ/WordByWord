package dev.wordbyword.app.core.model;

import java.util.Objects;

/** A timestamped fragment, whose granularity is declared by its RecognitionResult. */
public record TimedText(String text, TimeRange time) {
  public TimedText {
    Objects.requireNonNull(text);
    Objects.requireNonNull(time);
  }
}

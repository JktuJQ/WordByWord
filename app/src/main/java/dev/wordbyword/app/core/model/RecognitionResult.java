package dev.wordbyword.app.core.model;

import java.util.List;
import java.util.Objects;

/** Full recognized text and any timing information actually provided by the recognizer. */
public record RecognitionResult(
    String text, List<TimedText> fragments, TimingGranularity timingGranularity) {
  public RecognitionResult {
    Objects.requireNonNull(text);
    fragments = List.copyOf(fragments);
    Objects.requireNonNull(timingGranularity);
    if (timingGranularity == TimingGranularity.UNAVAILABLE && !fragments.isEmpty()) {
      throw new IllegalArgumentException("Unavailable timing must not contain timed fragments");
    }
  }

  public enum TimingGranularity {
    WORD,
    TOKEN,
    SEGMENT,
    UNAVAILABLE
  }
}

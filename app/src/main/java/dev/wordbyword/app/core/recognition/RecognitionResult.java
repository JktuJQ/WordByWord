package dev.wordbyword.app.core.recognition;

import java.util.List;

public record RecognitionResult(String text, List<Segment> segments, Timing timing) {
  public RecognitionResult {
    segments = List.copyOf(segments);
  }

  public record Segment(String text, long startMs, long endMs) {}

  public enum Timing {
    WORD,
    TOKEN,
    SEGMENT,
    UNAVAILABLE
  }
}

package dev.wordbyword.app.core.model;

/** Half-open interval [startMs, endMs) on the original recording's timeline. */
public record TimeRange(long startMs, long endMs) {
  public TimeRange {
    if (startMs < 0 || endMs < startMs) {
      throw new IllegalArgumentException("Expected 0 <= startMs <= endMs");
    }
  }
}

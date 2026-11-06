package dev.wordbyword.app.core.processing;

import java.util.Objects;
import java.util.OptionalDouble;

/**
 * Adapter-defined stage key and its local progress. Empty fraction means indeterminate progress.
 */
public record ProcessingProgress(String stage, OptionalDouble fraction) {
  public ProcessingProgress {
    Objects.requireNonNull(stage);
    Objects.requireNonNull(fraction);
    if (fraction.isPresent() && !(fraction.getAsDouble() >= 0 && fraction.getAsDouble() <= 1)) {
      throw new IllegalArgumentException("Progress fraction must be finite and between 0 and 1");
    }
  }
}

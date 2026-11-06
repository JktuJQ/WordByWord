package dev.wordbyword.app.core.processing;

import java.util.OptionalDouble;
import java.util.concurrent.CancellationException;

public interface ProcessingContext {
  boolean isCancellationRequested();

  void reportProgress(String stage, OptionalDouble fraction);

  default void throwIfCancelled() {
    if (isCancellationRequested()) {
      throw new CancellationException("Processing cancelled");
    }
  }
}

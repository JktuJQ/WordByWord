package dev.wordbyword.app.core.processing;

import java.util.concurrent.CancellationException;

/**
 * Caller-supplied cancellation and progress boundary. Implementations must support access from
 * worker threads; reportProgress is invoked on the processing thread, not necessarily the Android
 * main thread.
 */
public interface ProcessingContext {
  boolean isCancellationRequested();

  void reportProgress(ProcessingProgress progress);

  /** Check between interruptible units of work; does not promise interruption inside a JNI call. */
  default void throwIfCancelled() {
    if (isCancellationRequested()) {
      throw new CancellationException("Processing cancelled");
    }
  }
}

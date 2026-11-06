package dev.wordbyword.app.core.processing;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.OptionalDouble;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

public class ProcessingContextTest {
  @Test
  public void cancellationIsIndependentOfProgressReporting() {
    var cancelled = new AtomicBoolean(false);
    var stages = new ArrayList<String>();
    var fractions = new ArrayList<OptionalDouble>();
    ProcessingContext context =
        new ProcessingContext() {
          @Override
          public boolean isCancellationRequested() {
            return cancelled.get();
          }

          @Override
          public void reportProgress(String stage, OptionalDouble fraction) {
            stages.add(stage);
            fractions.add(fraction);
          }
        };

    context.throwIfCancelled();
    context.reportProgress("recognition", OptionalDouble.empty());
    cancelled.set(true);

    assertThrows(CancellationException.class, context::throwIfCancelled);
    assertTrue(stages.contains("recognition"));
    assertTrue(fractions.get(0).isEmpty());
  }
}

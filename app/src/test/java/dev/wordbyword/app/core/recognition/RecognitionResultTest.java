package dev.wordbyword.app.core.recognition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class RecognitionResultTest {
  @Test
  public void modelCanReturnCoarseTimingOrOnlyText() {
    var plain =
        new RecognitionResult("Привет, мир", List.of(), RecognitionResult.Timing.UNAVAILABLE);
    var segments =
        new ArrayList<>(List.of(new RecognitionResult.Segment("Привет, мир", 1500, 3000)));
    var coarse = new RecognitionResult("Привет, мир", segments, RecognitionResult.Timing.SEGMENT);
    segments.clear();

    assertTrue(plain.segments().isEmpty());
    assertEquals(1500, coarse.segments().get(0).startMs());
    assertEquals(3000, coarse.segments().get(0).endMs());
    assertEquals(RecognitionResult.Timing.SEGMENT, coarse.timing());
  }
}

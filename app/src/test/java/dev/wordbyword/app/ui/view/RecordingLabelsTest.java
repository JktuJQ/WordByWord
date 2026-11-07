package dev.wordbyword.app.ui.view;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class RecordingLabelsTest {
  @Test
  public void russianCountsHandleZeroTeensAndCompoundNumbers() {
    assertEquals("0 реплик · 0 участников", RecordingLabels.summary(0, 0));
    assertEquals("1 реплика · 1 участник", RecordingLabels.summary(1, 1));
    assertEquals("2 реплики · 4 участника", RecordingLabels.summary(2, 4));
    assertEquals("11 реплик · 14 участников", RecordingLabels.summary(11, 14));
    assertEquals("21 реплика · 22 участника", RecordingLabels.summary(21, 22));
    assertEquals("111 реплик · 112 участников", RecordingLabels.summary(111, 112));
  }
}

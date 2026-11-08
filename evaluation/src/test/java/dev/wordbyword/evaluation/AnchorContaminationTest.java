package dev.wordbyword.evaluation;

import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;

public class AnchorContaminationTest {
  @Test
  public void actualCropCanBePureWhenWholeSpanCrossesSpeakerChange() {
    var turns = List.of(new SpeakerAttribution.Turn(0, 1, "a"), new SpeakerAttribution.Turn(1, 20, "b"));
    assertEquals(2, AnchorContamination.profile(0, 20, turns, 0).speakers());
    assertEquals(1, AnchorContamination.profile(6, 14, turns, 0).speakers());
  }

  @Test
  public void boundaryCoreDoesNotTurnSmallEdgeErrorIntoMixedSpeech() {
    var turns = List.of(new SpeakerAttribution.Turn(0, 3, "a"), new SpeakerAttribution.Turn(3, 6, "b"));
    assertEquals(0.1, AnchorContamination.profile(1, 3.1, turns, 0).otherSeconds(), 1e-9);
    assertEquals(0, AnchorContamination.profile(1, 3.1, turns, 0.125).otherSeconds(), 1e-9);
  }

  @Test
  public void repeatedReferenceIntervalsAreNotDoubleCounted() {
    var turns = List.of(new SpeakerAttribution.Turn(0, 2, "a"), new SpeakerAttribution.Turn(0, 2, "a"));
    assertEquals(2, AnchorContamination.profile(0, 3, turns, 0).labelledSeconds(), 0);
  }
}

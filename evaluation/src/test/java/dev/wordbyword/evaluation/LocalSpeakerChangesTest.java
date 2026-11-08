package dev.wordbyword.evaluation;

import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;

public class LocalSpeakerChangesTest {
  @Test
  public void localLabelPermutationPreservesChangeTime() {
    assertEquals(List.of(0.5), LocalSpeakerChanges.changes(new int[] {1,1,1,1,1,2,2,2,2,2}, 0.1, 0.05));
    assertEquals(List.of(0.5), LocalSpeakerChanges.changes(new int[] {2,2,2,2,2,1,1,1,1,1}, 0.1, 0.05));
  }

  @Test
  public void pauseWithoutSpeakerChangeDoesNotCreateCut() {
    assertTrue(LocalSpeakerChanges.changes(new int[] {1,1,1,0,0,1,1,1}, 0.1, 0.05).isEmpty());
  }

  @Test
  public void splitPreservesSpeechCoverageAndRejectsTinyFragments() {
    var original = new SpeakerAttribution.Turn(0, 2, "x");
    var pieces = LocalSpeakerChanges.split(original, List.of(new LocalSpeakerChanges.Peak(.1, 1, 3), new LocalSpeakerChanges.Peak(1, 1, 3)));
    assertEquals(2, pieces.size());
    assertEquals(2, pieces.stream().mapToDouble(t -> t.end() - t.start()).sum(), 0);
  }
}

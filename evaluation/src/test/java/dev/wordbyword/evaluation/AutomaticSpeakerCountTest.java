package dev.wordbyword.evaluation;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class AutomaticSpeakerCountTest {
  @Test
  public void identicalVoiceIsOneCluster() {
    assertEquals(1, AutomaticSpeakerCount.select(List.of(new double[] {1, 0}, new double[] {1, 0}, new double[] {1, 0}), 0.2));
  }

  @Test
  public void separatedGroupsAreDetectedWithoutTwoToFourCeiling() {
    for (int k : new int[] {2, 3, 4, 7}) {
      var vectors = new ArrayList<double[]>();
      for (int i = 0; i < k; i++) for (int repeat = 0; repeat < 3; repeat++) {
        double[] vector = new double[k]; vector[i] = 1; vectors.add(vector);
      }
      assertEquals(k, AutomaticSpeakerCount.select(vectors, 0.2));
      assertEquals(k, AutomaticSpeakerCount.selectGap(vectors, 0.2));
    }
  }
}

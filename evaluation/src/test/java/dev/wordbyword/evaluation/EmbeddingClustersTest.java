package dev.wordbyword.evaluation;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;

public class EmbeddingClustersTest {
  @Test
  public void fixedCountSeparatesTwoKnownDirections() {
    int[] labels = EmbeddingClusters.cluster(List.of(
        new double[] {1, 0}, new double[] {1, 0},
        new double[] {0, 1}, new double[] {0, 1}), 2, 0.8);
    assertEquals(labels[0], labels[1]);
    assertEquals(labels[2], labels[3]);
    assertNotEquals(labels[0], labels[2]);
  }

  @Test
  public void automaticThresholdSupportsOneAndMoreThanFourSpeakers() {
    assertArrayEquals(new int[] {0, 0}, EmbeddingClusters.cluster(
        List.of(new double[] {1, 0}, new double[] {1, 0}), -1, 0.8));
    var vectors = List.of(new double[] {1, 0, 0, 0, 0}, new double[] {0, 1, 0, 0, 0},
        new double[] {0, 0, 1, 0, 0}, new double[] {0, 0, 0, 1, 0}, new double[] {0, 0, 0, 0, 1});
    assertArrayEquals(new int[] {0, 1, 2, 3, 4}, EmbeddingClusters.cluster(vectors, -1, 0.8));
  }

  @Test
  public void invalidVectorsCannotSilentlyCreateDistances() {
    assertThrows(IllegalArgumentException.class, () -> EmbeddingClusters.normalize(new double[] {0, 0}));
    assertThrows(IllegalArgumentException.class, () -> EmbeddingClusters.normalize(new double[] {Double.NaN}));
    assertArrayEquals(new double[] {0.6, 0.8}, EmbeddingClusters.normalize(new double[] {3, 4}), 1e-10);
  }
}

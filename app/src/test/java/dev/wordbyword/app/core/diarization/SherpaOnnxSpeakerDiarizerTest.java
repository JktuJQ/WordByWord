package dev.wordbyword.app.core.diarization;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class SherpaOnnxSpeakerDiarizerTest {
  @Test
  public void roundsSecondsToNearestMillisecond() {
    SpeakerDiarizer.Segment segment = SherpaOnnxSpeakerDiarizer.toSegment(1.2504f, 4.7686f, 0);

    assertEquals(1250, segment.startMs());
    assertEquals(4769, segment.endMs());
  }

  @Test
  public void zeroDurationSegmentHasEndNotBeforeStart() {
    SpeakerDiarizer.Segment segment = SherpaOnnxSpeakerDiarizer.toSegment(2.0005f, 2.0005f, 0);

    assertEquals(segment.startMs(), segment.endMs());
  }

  @Test
  public void speakerIdIsStableAndZeroBased() {
    assertEquals("speaker_0", SherpaOnnxSpeakerDiarizer.speakerId(0));
    assertEquals("speaker_2", SherpaOnnxSpeakerDiarizer.speakerId(2));
  }

  @Test
  public void toSegmentUsesSpeakerIdFormat() {
    SpeakerDiarizer.Segment segment = SherpaOnnxSpeakerDiarizer.toSegment(0f, 1f, 3);

    assertEquals("speaker_3", segment.speakerId());
  }

  @Test
  public void normalizeProducesUnitVector() {
    double[] normalized = SherpaOnnxSpeakerDiarizer.normalize(new double[] {3, 4});

    assertEquals(0.6, normalized[0], 1e-9);
    assertEquals(0.8, normalized[1], 1e-9);
  }

  @Test
  public void cosineDistanceIsZeroForIdenticalUnitVectors() {
    double[] vector = SherpaOnnxSpeakerDiarizer.normalize(new double[] {1, 2, 3});

    assertEquals(0.0, SherpaOnnxSpeakerDiarizer.cosineDistance(vector, vector), 1e-9);
  }

  @Test
  public void cosineDistanceIsTwoForOppositeUnitVectors() {
    double[] a = SherpaOnnxSpeakerDiarizer.normalize(new double[] {1, 0});
    double[] b = SherpaOnnxSpeakerDiarizer.normalize(new double[] {-1, 0});

    assertEquals(2.0, SherpaOnnxSpeakerDiarizer.cosineDistance(a, b), 1e-9);
  }

  @Test
  public void clusterByThresholdMergesCloseVectorsAndSeparatesFarOnes() {
    double[] a = SherpaOnnxSpeakerDiarizer.normalize(new double[] {1, 0});
    double[] aClose = SherpaOnnxSpeakerDiarizer.normalize(new double[] {0.99, 0.05});
    double[] b = SherpaOnnxSpeakerDiarizer.normalize(new double[] {0, 1});

    int[] labels = SherpaOnnxSpeakerDiarizer.clusterByThreshold(List.of(a, aClose, b), 0.45);

    assertEquals(labels[0], labels[1]);
    assertTrue(labels[0] != labels[2]);
  }

  @Test
  public void clusterByThresholdKeepsSingleVectorAsOneCluster() {
    double[] a = SherpaOnnxSpeakerDiarizer.normalize(new double[] {1, 0});

    int[] labels = SherpaOnnxSpeakerDiarizer.clusterByThreshold(List.of(a), 0.45);

    assertArrayEquals(new int[] {0}, labels);
  }

  @Test
  public void nearestAssignedByTimePicksClosestAlreadyLabelledNeighbour() {
    float[][] boundaries = {{0f, 1f}, {2f, 3f}, {10f, 11f}};
    int[] labels = {5, -1, 7};

    assertEquals(5, SherpaOnnxSpeakerDiarizer.nearestAssignedByTime(boundaries, labels, 1));
  }

  @Test
  public void nearestAssignedByTimeFallsBackToZeroWhenNothingIsAssignedYet() {
    float[][] boundaries = {{0f, 1f}, {2f, 3f}};
    int[] labels = {-1, -1};

    assertEquals(0, SherpaOnnxSpeakerDiarizer.nearestAssignedByTime(boundaries, labels, 1));
  }
}

package dev.wordbyword.evaluation;

import static org.junit.Assert.assertEquals;

import java.nio.file.Path;
import java.util.List;
import org.junit.Test;

public class ScoreDiarizationTest {
  private static ScoreDiarization.Segment segment(double start, double end, String speaker) {
    return new ScoreDiarization.Segment(start, end, speaker);
  }

  private static void check(
      List<ScoreDiarization.Segment> reference,
      List<ScoreDiarization.Segment> hypothesis,
      double expectedDer) {
    assertEquals(expectedDer, ScoreDiarization.score(reference, hypothesis, 10).der(), 1e-7);
  }

  @Test
  public void speakerLabelsAreMatchedRatherThanComparedLiterally() {
    check(
        List.of(segment(0, 5, "a"), segment(5, 10, "b")),
        List.of(segment(0, 5, "y"), segment(5, 10, "x")),
        0);
  }

  @Test
  public void collarIgnoresSmallBoundaryShifts() {
    var reference = List.of(segment(1, 5, "a"), segment(5, 9, "b"));
    var hypothesis = List.of(segment(1.2, 4.8, "x"), segment(4.8, 9.2, "y"));
    assertEquals(0.6 / 8, ScoreDiarization.score(reference, hypothesis, 10).der(), 1e-7);
    assertEquals(0, ScoreDiarization.score(reference, hypothesis, 10, 0.25).der(), 1e-7);
  }

  @Test
  public void missingAllSpeechCountsAsMissed() {
    check(List.of(segment(0, 5, "a"), segment(5, 10, "b")), List.of(), 1);
  }

  @Test
  public void collapsedSpeakersCountAsConfusion() {
    check(List.of(segment(0, 5, "a"), segment(5, 10, "b")), List.of(segment(0, 10, "x")), 0.5);
  }

  @Test
  public void speechOutsideReferenceCountsAsFalseAlarm() {
    check(List.of(segment(0, 5, "a")), List.of(segment(0, 10, "x")), 1);
  }

  @Test
  public void missingOverlappingSpeakerCountsAsMissed() {
    check(List.of(segment(0, 10, "a"), segment(0, 10, "b")), List.of(segment(0, 10, "x")), 0.5);
  }

  @Test
  public void extraOverlappingSpeakersCountAsFalseAlarm() {
    check(
        List.of(segment(0, 10, "a")),
        List.of(segment(0, 10, "x"), segment(0, 10, "y"), segment(0, 10, "z")),
        2);
  }

  @Test
  public void overlappingSegmentsOfSameSpeakerAreNotCountedTwice() {
    check(List.of(segment(0, 10, "a"), segment(2, 8, "a")), List.of(segment(0, 10, "x")), 0);
  }

  @Test
  public void choosesOptimalOneToOneSpeakerMapping() {
    check(
        List.of(segment(0, 7, "a"), segment(7, 10, "b")),
        List.of(segment(0, 4, "x"), segment(4, 7, "y"), segment(7, 10, "x")),
        0.4);
  }

  @Test
  public void datasetReferencesScoreZeroAgainstThemselves() throws Exception {
    for (String name : List.of("rec_0055", "rec_0273")) {
      var data = ScoreDiarization.read(Path.of("fixtures", "diarization", name + ".rttm"), 60);
      assertEquals(name, 0, ScoreDiarization.score(data, data, 60).der(), 1e-7);
    }
  }
}

package dev.wordbyword.evaluation;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class SpeakerAttributionTest {
  @Test
  public void mappingIsGlobalAndShortAnswersCannotRemapThemselves() {
    var result = SpeakerAttribution.scoreLabels(
        List.of("a", "a", "b", "b"), List.of("x", "x", "y", "x"),
        List.of(false, false, false, true));
    assertEquals(3, result.correct());
    assertEquals(1, result.shortTurns());
    assertEquals(0, result.shortCorrect());
    assertEquals(0.5, result.recall().get("b"), 0);
  }

  @Test
  public void missingAndTiedTurnsCountAsErrors() {
    var reference = List.of(new SpeakerAttribution.Turn(0, 2, "a"),
        new SpeakerAttribution.Turn(3, 4, "b"));
    var hypothesis = List.of(new SpeakerAttribution.Turn(0, 1, "x"),
        new SpeakerAttribution.Turn(1, 2, "y"));
    var result = SpeakerAttribution.score(reference, hypothesis);
    assertEquals(0, result.correct());
    assertEquals(2, result.unassigned());
  }

  @Test
  public void collapsingTwoVoicesIsNotPerfectEvenWithAllTurnsCovered() {
    var result = SpeakerAttribution.scoreLabels(
        List.of("a", "b"), List.of("x", "x"), List.of(false, false));
    assertEquals(0.5, result.accuracy(), 0);
  }

  @Test
  public void missingLabelsNeverAcquireAReferenceIdentity() {
    var result = SpeakerAttribution.scoreLabels(
        List.of("a", "a"), Arrays.asList("x", null), List.of(true, true));
    assertEquals(1, result.correct());
    assertEquals(1, result.unassigned());
  }

  @Test
  public void duplicateHypothesisIntervalsCannotWinTheVoteTwice() {
    var reference = List.of(new SpeakerAttribution.Turn(0, 3, "a"),
        new SpeakerAttribution.Turn(3, 6, "a"));
    var hypothesis = List.of(new SpeakerAttribution.Turn(0, 1, "x"),
        new SpeakerAttribution.Turn(0, 1, "x"), new SpeakerAttribution.Turn(0, 1, "x"),
        new SpeakerAttribution.Turn(1, 3, "y"), new SpeakerAttribution.Turn(3, 6, "y"));
    assertEquals(2, SpeakerAttribution.score(reference, hypothesis).correct());
  }
}

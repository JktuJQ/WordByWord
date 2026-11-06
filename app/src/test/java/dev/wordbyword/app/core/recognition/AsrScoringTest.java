package dev.wordbyword.app.core.recognition;

import static org.junit.Assert.*;

import dev.wordbyword.evaluation.ScoreAsr;
import java.util.List;
import org.junit.Test;

public class AsrScoringTest {
  @Test
  public void countsSubstitutionDeletionAndInsertion() {
    assertEquals(new ScoreAsr.Errors(1, 0, 0), ScoreAsr.compare(List.of("a"), List.of("b")));
    assertEquals(new ScoreAsr.Errors(0, 1, 0), ScoreAsr.compare(List.of("a"), List.of()));
    assertEquals(new ScoreAsr.Errors(0, 0, 1), ScoreAsr.compare(List.of(), List.of("a")));
    assertEquals(
        3,
        ScoreAsr.compare(
                List.of("k", "i", "t", "t", "e", "n"), List.of("s", "i", "t", "t", "i", "n", "g"))
            .total());
  }

  @Test
  public void normalizesBothSides() {
    assertEquals("елка привет", ScoreAsr.normalize(" Ёлка, ПРИВЕТ! "));
  }
}

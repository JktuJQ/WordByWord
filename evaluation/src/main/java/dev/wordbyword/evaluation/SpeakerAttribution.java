package dev.wordbyword.evaluation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SpeakerAttribution {
  public record Turn(double start, double end, String speaker) {}

  public record Result(
      int turns, int correct, int shortTurns, int shortCorrect,
      int unassigned, Map<String, Double> recall, Map<String, String> mapping) {
    public double accuracy() {
      return turns == 0 ? Double.NaN : correct / (double) turns;
    }
  }

  public static Result score(List<Turn> reference, List<Turn> hypothesis) {
    var expected = new ArrayList<String>();
    var actual = new ArrayList<String>();
    var shortTurns = new ArrayList<Boolean>();
    for (var ref : reference) {
      var portions = new LinkedHashMap<String, List<double[]>>();
      for (var hyp : hypothesis) {
        double from = Math.max(ref.start(), hyp.start());
        double to = Math.min(ref.end(), hyp.end());
        if (to > from) portions.computeIfAbsent(hyp.speaker(), key -> new ArrayList<>())
            .add(new double[] {from, to});
      }
      var votes = new LinkedHashMap<String, Double>();
      portions.forEach((speaker, pieces) -> {
        pieces.sort(java.util.Comparator.comparingDouble(piece -> piece[0]));
        double end = Double.NEGATIVE_INFINITY, total = 0;
        for (var piece : pieces) {
          total += Math.max(0, piece[1] - Math.max(end, piece[0]));
          end = Math.max(end, piece[1]);
        }
        votes.put(speaker, total);
      });
      String winner = null;
      double best = 0;
      for (var entry : votes.entrySet()) {
        if (entry.getValue() > best + 1e-8) {
          winner = entry.getKey();
          best = entry.getValue();
        } else if (Math.abs(entry.getValue() - best) <= 1e-8) {
          winner = null;
        }
      }
      expected.add(ref.speaker());
      actual.add(winner);
      shortTurns.add(ref.end() - ref.start() < 2);
    }
    return scoreLabels(expected, actual, shortTurns);
  }

  public static Result scoreLabels(
      List<String> expected, List<String> actual, List<Boolean> shortMask) {
    if (expected.size() != actual.size() || expected.size() != shortMask.size())
      throw new IllegalArgumentException("Label and mask sizes differ");
    var refs = expected.stream().distinct().sorted().toList();
    var hyps = actual.stream().filter(java.util.Objects::nonNull).distinct().sorted().toList();
    int size = Math.max(refs.size(), hyps.size());
    int[][] costs = new int[size][size];
    for (int i = 0; i < expected.size(); i++) {
      if (actual.get(i) != null) costs[refs.indexOf(expected.get(i))][hyps.indexOf(actual.get(i))]--;
    }
    var assignment = ScoreTranscripts.assign(costs);
    var mapping = new LinkedHashMap<String, String>();
    for (int r = 0; r < refs.size(); r++) {
      int h = assignment.columns()[r];
      if (h < hyps.size()) mapping.put(hyps.get(h), refs.get(r));
    }
    int correct = 0, shortCount = 0, shortCorrect = 0, unassigned = 0;
    var counts = new LinkedHashMap<String, int[]>();
    for (int i = 0; i < expected.size(); i++) {
      String ref = expected.get(i), hyp = actual.get(i);
      boolean hit = hyp != null && ref.equals(mapping.get(hyp));
      int[] count = counts.computeIfAbsent(ref, key -> new int[2]);
      count[0]++;
      if (hit) { correct++; count[1]++; }
      if (hyp == null) unassigned++;
      if (shortMask.get(i)) { shortCount++; if (hit) shortCorrect++; }
    }
    var recall = new LinkedHashMap<String, Double>();
    counts.forEach((speaker, count) -> recall.put(speaker, count[1] / (double) count[0]));
    return new Result(expected.size(), correct, shortCount, shortCorrect, unassigned, recall, mapping);
  }
}

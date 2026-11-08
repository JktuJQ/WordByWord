package dev.wordbyword.evaluation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class LocalSpeakerChanges {
  public record Run(int from, int to, int mask) {}
  public record Peak(double seconds, double support, int windows) {}

  public static List<Double> changes(int[] masks, double frameSeconds, double centerSeconds) {
    var stable = new ArrayList<Run>();
    for (int from = 0; from < masks.length;) {
      int to = from + 1;
      while (to < masks.length && masks[to] == masks[from]) to++;
      int mask = masks[from];
      if (Integer.bitCount(mask) == 1 && (to - from) * frameSeconds >= 0.15)
        stable.add(new Run(from, to, mask));
      from = to;
    }
    var result = new ArrayList<Double>();
    for (int i = 1; i < stable.size(); i++) {
      var previous = stable.get(i - 1); var next = stable.get(i);
      if (previous.mask() != next.mask() && (next.from() - previous.to()) * frameSeconds <= 0.5)
        result.add(centerSeconds + (previous.to() - 1 + next.from()) * frameSeconds / 2);
    }
    return result;
  }

  public static int[] masks(float[] scores, int frames, int classes) {
    if (classes != 7 || scores.length != frames * classes)
      throw new IllegalArgumentException("Expected 3-speaker powerset output with 7 classes");
    int[] mapping = {0, 1, 2, 4, 3, 5, 6}; int[] masks = new int[frames];
    for (int frame = 0; frame < frames; frame++) {
      int best = 0;
      for (int c = 0; c < classes; c++) {
        if (!Float.isFinite(scores[frame * classes + c])) throw new IllegalArgumentException("Nonfinite segmentation score");
        if (scores[frame * classes + c] > scores[frame * classes + best]) best = c;
      }
      masks[frame] = mapping[best];
    }
    return masks;
  }

  public static List<Peak> peaks(int[] votes, int[] coverage, double stepSeconds) {
    return peaks(votes, coverage, stepSeconds, 0.5);
  }

  public static List<Peak> peaks(int[] votes, int[] coverage, double stepSeconds, double minimumSupport) {
    if (minimumSupport < 0 || minimumSupport > 1) throw new IllegalArgumentException("Invalid support threshold");
    if (votes.length != coverage.length) throw new IllegalArgumentException("Mismatched vote arrays");
    var candidates = new ArrayList<Peak>();
    for (int i = 0; i < votes.length; i++) if (coverage[i] >= 2 && votes[i] >= minimumSupport * coverage[i])
      candidates.add(new Peak(i * stepSeconds, votes[i] / (double) coverage[i], coverage[i]));
    candidates.sort(Comparator.comparingDouble(Peak::support).reversed()
        .thenComparing(Comparator.comparingInt(Peak::windows).reversed()).thenComparingDouble(Peak::seconds));
    var result = new ArrayList<Peak>();
    for (var peak : candidates)
      if (result.stream().noneMatch(other -> Math.abs(other.seconds() - peak.seconds()) < 0.3)) result.add(peak);
    result.sort(Comparator.comparingDouble(Peak::seconds));
    return result;
  }

  public static List<SpeakerAttribution.Turn> split(
      SpeakerAttribution.Turn interval, List<Peak> peaks) {
    var result = new ArrayList<SpeakerAttribution.Turn>(); double start = interval.start();
    for (var peak : peaks) if (peak.seconds() - start >= 0.3 && interval.end() - peak.seconds() >= 0.3) {
      result.add(new SpeakerAttribution.Turn(start, peak.seconds(), interval.speaker())); start = peak.seconds();
    }
    result.add(new SpeakerAttribution.Turn(start, interval.end(), interval.speaker()));
    return result;
  }
}

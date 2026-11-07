package dev.wordbyword.evaluation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

public class ScoreDiarization {
  record Segment(double start, double end, String speaker) {}

  record Result(double reference, double missed, double falseAlarm, double confusion) {
    double der() {
      return (missed + falseAlarm + confusion) / reference;
    }
  }

  public static void main(String[] args) {
    try {
      if (args.length < 3 || args.length > 4) {
        throw new IllegalArgumentException(
            "Usage: ScoreDiarization reference.rttm hypothesis.rttm duration_seconds"
                + " [processing_seconds]");
      }
      double duration = positive(args[2]);
      Double processing = args.length == 4 ? positive(args[3]) : null;
      var reference = read(Path.of(args[0]), duration);
      var hypothesis = read(Path.of(args[1]), duration);
      Result result = score(reference, hypothesis, duration);
      System.out.printf(Locale.ROOT, "DER: %.3f%%%n", 100 * result.der());
      System.out.printf(Locale.ROOT, "Reference speaker-time: %.3f s%n", result.reference());
      System.out.printf(Locale.ROOT, "Missed speech: %.3f s%n", result.missed());
      System.out.printf(Locale.ROOT, "False alarm: %.3f s%n", result.falseAlarm());
      System.out.printf(Locale.ROOT, "Speaker confusion: %.3f s%n", result.confusion());
      System.out.printf(
          "Speakers (reference / hypothesis): %d / %d%n",
          speakers(reference).size(), speakers(hypothesis).size());
      System.out.println(
          "Scoring: full recording, collar=0, overlap included, optimal one-to-one speaker"
              + " mapping");
      if (processing != null) {
        System.out.printf(Locale.ROOT, "RTF: %.4f%n", processing / duration);
      }
    } catch (Exception exception) {
      System.err.println("Error: " + exception.getMessage());
      System.exit(1);
    }
  }

  static double positive(String value) {
    double number = Double.parseDouble(value);
    if (!Double.isFinite(number) || number <= 0) {
      throw new IllegalArgumentException("Expected a finite positive number: " + value);
    }
    return number;
  }

  static List<Segment> read(Path path, double duration) throws Exception {
    var segments = new ArrayList<Segment>();
    String recording = null;
    int lineNumber = 0;
    for (String line : Files.readAllLines(path)) {
      lineNumber++;
      if (line.isBlank() || line.stripLeading().startsWith("#")) {
        continue;
      }
      String[] fields = line.trim().split("\\s+");
      try {
        if (fields.length != 10 || !fields[0].equals("SPEAKER") || !fields[2].equals("1")) {
          throw new IllegalArgumentException("Expected a 10-field SPEAKER RTTM row, channel 1");
        }
        if (recording != null && !recording.equals(fields[1])) {
          throw new IllegalArgumentException("Only one recording per RTTM is supported");
        }
        recording = fields[1];
        double start = Double.parseDouble(fields[3]);
        double length = positive(fields[4]);
        double end = start + length;
        if (!Double.isFinite(start)
            || start < 0
            || !Double.isFinite(end)
            || end > duration + 0.000001) {
          throw new IllegalArgumentException("Interval outside recording duration");
        }
        if (fields[7].equals("<NA>")) {
          throw new IllegalArgumentException("Missing speaker ID");
        }
        segments.add(new Segment(start, Math.min(end, duration), fields[7]));
      } catch (IllegalArgumentException exception) {
        throw new IllegalArgumentException(path + ":" + lineNumber + ": " + exception.getMessage());
      }
    }
    return segments;
  }

  static List<String> speakers(List<Segment> segments) {
    return segments.stream().map(Segment::speaker).distinct().sorted().toList();
  }

  static Set<String> active(List<Segment> segments, double time) {
    return segments.stream()
        .filter(segment -> segment.start() <= time && time < segment.end())
        .map(Segment::speaker)
        .collect(Collectors.toSet());
  }

  static Result score(List<Segment> reference, List<Segment> hypothesis, double duration) {
    var referenceIds = speakers(reference);
    var hypothesisIds = speakers(hypothesis);
    int size = Math.max(referenceIds.size(), hypothesisIds.size());
    if (size > 16) {
      throw new IllegalArgumentException(
          "This mini-dataset scorer supports at most 16 speakers per recording");
    }
    var boundaries = new TreeSet<Double>();
    boundaries.add(0.0);
    boundaries.add(duration);
    for (var segments : List.of(reference, hypothesis)) {
      for (var segment : segments) {
        boundaries.add(segment.start());
        boundaries.add(segment.end());
      }
    }
    var cuts = new ArrayList<>(boundaries);
    double[][] overlap = new double[size][size];
    double total = 0;
    double missed = 0;
    double falseAlarm = 0;
    double common = 0;
    for (int index = 1; index < cuts.size(); index++) {
      double start = cuts.get(index - 1);
      double length = cuts.get(index) - start;
      var expected = active(reference, start + length / 2);
      var actual = active(hypothesis, start + length / 2);
      total += length * expected.size();
      missed += length * Math.max(0, expected.size() - actual.size());
      falseAlarm += length * Math.max(0, actual.size() - expected.size());
      common += length * Math.min(expected.size(), actual.size());
      for (String ref : expected) {
        for (String hyp : actual) {
          overlap[referenceIds.indexOf(ref)][hypothesisIds.indexOf(hyp)] += length;
        }
      }
    }
    if (total == 0) {
      throw new IllegalArgumentException("Reference has no speech; DER is undefined");
    }
    double[] best = new double[1 << size];
    Arrays.fill(best, Double.NEGATIVE_INFINITY);
    best[0] = 0;
    for (int mask = 0; mask < best.length - 1; mask++) {
      int row = Integer.bitCount(mask);
      for (int column = 0; column < size; column++) {
        if ((mask & (1 << column)) == 0) {
          int next = mask | (1 << column);
          best[next] = Math.max(best[next], best[mask] + overlap[row][column]);
        }
      }
    }
    return new Result(total, missed, falseAlarm, Math.max(0, common - best[best.length - 1]));
  }
}

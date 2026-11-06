import java.util.List;

public class ScoreDiarizationTest {
  static ScoreDiarization.Segment segment(double start, double end, String speaker) {
    return new ScoreDiarization.Segment(start, end, speaker);
  }

  static void check(
      List<ScoreDiarization.Segment> reference,
      List<ScoreDiarization.Segment> hypothesis,
      double expectedDer) {
    double actual = ScoreDiarization.score(reference, hypothesis, 10).der();
    if (Math.abs(actual - expectedDer) > 0.0000001) {
      throw new AssertionError("Expected DER " + expectedDer + ", got " + actual);
    }
  }

  public static void main(String[] args) throws Exception {
    var reference = List.of(segment(0, 5, "a"), segment(5, 10, "b"));
    check(reference, List.of(segment(0, 5, "y"), segment(5, 10, "x")), 0);
    check(reference, List.of(), 1);
    check(reference, List.of(segment(0, 10, "x")), 0.5);
    check(List.of(segment(0, 5, "a")), List.of(segment(0, 10, "x")), 1);
    check(List.of(segment(0, 10, "a"), segment(0, 10, "b")), List.of(segment(0, 10, "x")), 0.5);
    check(
        List.of(segment(0, 10, "a")),
        List.of(segment(0, 10, "x"), segment(0, 10, "y"), segment(0, 10, "z")),
        2);
    check(List.of(segment(0, 10, "a"), segment(2, 8, "a")), List.of(segment(0, 10, "x")), 0);
    check(
        List.of(segment(0, 7, "a"), segment(7, 10, "b")),
        List.of(segment(0, 4, "x"), segment(4, 7, "y"), segment(7, 10, "x")),
        0.4);
    for (String name : List.of("rec_0055", "rec_0273")) {
      var data = ScoreDiarization.read(java.nio.file.Path.of(args[0], name + ".rttm"), 60);
      if (Math.abs(ScoreDiarization.score(data, data, 60).der()) > 0.0000001) {
        throw new AssertionError("Self-comparison failed for " + name);
      }
    }
    System.out.println("10 scoring checks passed");
  }
}

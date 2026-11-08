package dev.wordbyword.evaluation;

import static org.junit.Assert.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

public class SummarizeDiarizationDiagnosticsTest {
  @Test
  public void unfinishedOrSelectedCaseRunsCannotBecomeHeadlineResults() {
    var report = new JsonObject();
    report.addProperty("complete", false);
    assertThrows(IllegalArgumentException.class,
        () -> SummarizeDiarizationDiagnostics.summarize(report));
    report.addProperty("complete", true);
    report.addProperty("all_protocol_cases", false);
    assertThrows(IllegalArgumentException.class,
        () -> SummarizeDiarizationDiagnostics.summarize(report));
  }

  @Test
  public void missingCaseCannotHideBehindCompleteFlag() {
    var report = new JsonObject();
    report.addProperty("complete", true);
    report.addProperty("all_protocol_cases", true);
    var protocol = new JsonObject();
    var cases = new JsonArray();
    var sample = new JsonObject();
    sample.addProperty("id", "missing");
    cases.add(sample);
    protocol.add("cases", cases);
    report.add("protocol", protocol);
    report.add("cases", new JsonArray());
    assertThrows(IllegalArgumentException.class,
        () -> SummarizeDiarizationDiagnostics.summarize(report));
  }
}

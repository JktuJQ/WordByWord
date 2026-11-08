package dev.wordbyword.evaluation;

import static org.junit.Assert.*;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import org.junit.Test;

public class DiagnosticProtocolTest {
  @Test
  public void windowsMatchPinnedReferencesWithoutDownloadingAudio() throws Exception {
    var protocol = new Gson().fromJson(Files.readString(Path.of("diarization-diagnostics.json")), JsonObject.class);
    var ids = new HashSet<String>();
    var splits = new HashMap<String, String>();
    for (var value : protocol.getAsJsonArray("cases")) {
      var sample = value.getAsJsonObject();
      assertTrue(ids.add(sample.get("id").getAsString()));
      double start = sample.get("start_seconds").getAsDouble();
      double end = sample.get("end_seconds").getAsDouble();
      assertTrue(start >= 0 && end > start && end - start <= 300);
      var reference = Path.of("..").resolve(sample.get("reference").getAsString());
      var hash = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(Files.readAllBytes(reference)));
      assertEquals(sample.get("reference_sha256").getAsString(), hash);
      long speakers = ScoreDiarization.read(reference, Double.MAX_VALUE).stream()
          .filter(turn -> turn.start() < end && turn.end() > start)
          .map(ScoreDiarization.Segment::speaker).distinct().count();
      assertEquals(sample.get("expected_speakers").getAsInt(), speakers);
      assertEquals(speakers >= 2 && speakers <= 4 ? "target" : "control", sample.get("role").getAsString());
      String group = sample.get("group").getAsString();
      String split = sample.get("split").getAsString();
      assertEquals(split, splits.computeIfAbsent(group, key -> split));
    }
  }
}

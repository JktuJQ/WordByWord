package dev.wordbyword.app.summary;

import static org.junit.Assert.*;

import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class SummaryStoreTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  @Test
  public void reopensResultOnlyForMatchingTranscript() throws Exception {
    var root = temporary.getRoot().toPath();
    Files.createDirectory(root.resolve(SummaryText.key("recording")));
    new SummaryStore(root).save("recording", "source-v1", "Встреча завтра.");
    var reopened = new SummaryStore(root);
    assertEquals("Встреча завтра.", reopened.load("recording", "source-v1"));
    assertEquals("", reopened.load("recording", "source-v2"));
    assertEquals(1, Files.list(root.resolve(SummaryText.key("recording"))).count());
  }

  @Test
  public void missingRecordingIsNotRecreated() throws Exception {
    var root = temporary.getRoot().toPath();
    assertThrows(
        java.io.IOException.class, () -> new SummaryStore(root).save("deleted", "key", "text"));
    assertFalse(Files.exists(root.resolve(SummaryText.key("deleted"))));
  }
}

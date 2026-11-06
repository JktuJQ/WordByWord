package dev.wordbyword.app.storage;

import static org.junit.Assert.*;

import dev.wordbyword.app.core.recording.Recording;
import dev.wordbyword.app.core.transcript.Transcript;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class FileTranscriptRepositoryTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  private Path root() {
    return temporary.getRoot().toPath().resolve("library");
  }

  private FileTranscriptRepository repository() {
    return new FileTranscriptRepository(root(), uri -> Files.newInputStream(Paths.get(uri)));
  }

  private Recording recording(String id) throws IOException {
    Path source = temporary.newFile().toPath();
    Files.write(source, new byte[] {1, 2, 3, 4});
    return new Recording(id, source.toUri(), "Разговор 😀", OptionalLong.of(1000));
  }

  private Transcript transcript(Recording recording) {
    return new Transcript(
        recording,
        List.of(new Transcript.Speaker("a", "Участник 1")),
        List.of(new Transcript.Utterance(Optional.of("a"), "Текст \"реплики\"\n😀", 100, 900)));
  }

  @Test
  public void importedRecordingSurvivesNewRepositoryAndLossOfSource() throws Exception {
    Recording original = recording("first");
    Recording saved = repository().saveRecording(original);
    Files.delete(Paths.get(original.audioUri()));
    assertNotEquals(original.audioUri(), saved.audioUri());
    assertArrayEquals(new byte[] {1, 2, 3, 4}, Files.readAllBytes(Paths.get(saved.audioUri())));
    assertEquals(List.of(saved), repository().listRecordings());
    assertTrue(repository().find(saved.id()).isEmpty());
    assertEquals(saved, repository().saveRecording(original));
  }

  @Test
  public void transcriptAndRenameSurviveRestartAndStayWithinRecording() throws Exception {
    var first = repository().save(transcript(recording("first")));
    var second = repository().save(transcript(recording("second")));
    repository().renameSpeaker(first.recording().id(), "a", "Анна");
    var loaded = repository().find("first").orElseThrow();
    assertEquals("Анна", loaded.speakers().get(0).displayName());
    assertEquals(first.utterances(), loaded.utterances());
    assertEquals(second, repository().find("second").orElseThrow());
    assertEquals(first.recording(), loaded.recording());
  }

  @Test
  public void invalidReplacementPreservesCommittedTranscript() throws Exception {
    var saved = repository().save(transcript(recording("first")));
    var invalid =
        new Transcript(
            saved.recording(),
            saved.speakers(),
            List.of(new Transcript.Utterance(Optional.of("missing"), "Broken", 500, 100)));
    assertThrows(IOException.class, () -> repository().save(invalid));
    assertEquals(saved, repository().find("first").orElseThrow());
    try (var paths = Files.walk(root())) {
      assertFalse(paths.anyMatch(path -> path.getFileName().toString().startsWith(".metadata-")));
    }
  }

  @Test
  public void failedCopyNeverPublishesRecordingAndRemovesTemporaryFiles() throws Exception {
    var failing =
        new FileTranscriptRepository(
            root(),
            uri ->
                new InputStream() {
                  int count;

                  @Override
                  public int read() throws IOException {
                    if (count++ >= 20) throw new IOException("No space or source disconnected");
                    return 1;
                  }
                });
    assertThrows(IOException.class, () -> failing.saveRecording(recording("broken")));
    assertTrue(repository().listRecordings().isEmpty());
    try (var files = Files.list(root())) {
      assertEquals(0, files.count());
    }
  }

  @Test
  public void emptySourceIsRejected() throws Exception {
    Path source = temporary.newFile().toPath();
    assertThrows(
        IOException.class,
        () ->
            repository()
                .saveRecording(
                    new Recording("empty", source.toUri(), "empty", OptionalLong.empty())));
    assertTrue(repository().listRecordings().isEmpty());
  }

  @Test
  public void corruptMetadataDoesNotHideHealthyRecordsOrDeleteDamagedData() throws Exception {
    Recording broken = repository().saveRecording(recording("broken"));
    Recording healthy = repository().saveRecording(recording("healthy"));
    Path metadata = Paths.get(broken.audioUri()).getParent().resolve("recording.json");
    Files.write(metadata, "{broken".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertEquals(List.of(healthy), repository().listRecordings());
    assertThrows(IOException.class, () -> repository().find("broken"));
    assertTrue(Files.exists(metadata));
    assertTrue(Files.exists(Paths.get(broken.audioUri())));
  }

  @Test
  public void missingAudioIsNotReportedAsAvailableTranscript() throws Exception {
    var saved = repository().save(transcript(recording("first")));
    Files.delete(Paths.get(saved.recording().audioUri()));
    assertEquals(List.of(saved.recording()), repository().listRecordings());
    assertThrows(IOException.class, () -> repository().find("first"));
  }

  @Test
  public void deleteIsDurableIdempotentAndDoesNotAffectOtherRecordings() throws Exception {
    var first = repository().save(transcript(recording("first")));
    var second = repository().save(transcript(recording("second")));
    repository().delete("first");
    repository().delete("first");
    assertFalse(Files.exists(Paths.get(first.recording().audioUri())));
    assertEquals(List.of(second.recording()), repository().listRecordings());
    assertTrue(repository().find("first").isEmpty());
  }

  @Test
  public void interruptedTransactionsAreCleanedOnLoad() throws Exception {
    Recording saved = repository().saveRecording(recording("first"));
    Path pending = Files.createDirectory(root().resolve(".pending-interrupted"));
    Files.write(pending.resolve("audio"), new byte[] {1});
    Path unfinished = Paths.get(saved.audioUri()).getParent().resolve(".metadata-interrupted.tmp");
    Files.write(unfinished, "unfinished".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertEquals(List.of(saved), repository().listRecordings());
    assertFalse(Files.exists(pending));
    assertFalse(Files.exists(unfinished));
  }

  @Test
  public void recordingIdsCannotEscapeLibraryDirectory() throws Exception {
    Recording saved = repository().saveRecording(recording("../../outside"));
    assertTrue(Paths.get(saved.audioUri()).startsWith(root()));
    assertEquals(List.of(saved), repository().listRecordings());
  }

  @Test
  public void silenceAndVirtualDemoRemainDistinctFromUnprocessedImport() throws Exception {
    var demo =
        new Recording("demo", URI.create("demo://conversation"), "Пример", OptionalLong.empty());
    repository().save(new Transcript(demo, List.of(), List.of()));
    assertEquals(List.of(), repository().find("demo").orElseThrow().utterances());
    assertEquals(List.of(demo), repository().listRecordings());
  }

  @Test
  public void unsupportedVersionAndFractionalTimesAreRejectedWithoutRewriting() throws Exception {
    var saved = repository().save(transcript(recording("first")));
    Path metadata = Paths.get(saved.recording().audioUri()).getParent().resolve("recording.json");
    String original =
        new String(Files.readAllBytes(metadata), java.nio.charset.StandardCharsets.UTF_8);
    for (String invalid :
        List.of(
            original.replace("\"version\":1", "\"version\":2"),
            original.replace("\"start_ms\":100", "\"start_ms\":100.5"))) {
      Files.write(metadata, invalid.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      assertThrows(IOException.class, () -> repository().find("first"));
      assertEquals(
          invalid,
          new String(Files.readAllBytes(metadata), java.nio.charset.StandardCharsets.UTF_8));
    }
  }

  @Test
  public void completedTranscriptCanFillDurationWhileKeepingOwnedAudio() throws Exception {
    var input = recording("first");
    var imported = new Recording(input.id(), input.audioUri(), input.title(), OptionalLong.empty());
    var owned = repository().saveRecording(imported);
    var result = repository().save(transcript(input));
    assertEquals(owned.audioUri(), result.recording().audioUri());
    assertEquals(
        OptionalLong.of(1000), repository().find("first").orElseThrow().recording().durationMs());
  }
}

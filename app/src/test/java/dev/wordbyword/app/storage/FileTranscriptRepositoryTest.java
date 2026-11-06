package dev.wordbyword.app.storage;

import static org.junit.Assert.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.wordbyword.app.core.recording.Recording;
import dev.wordbyword.app.core.transcript.Transcript;
import dev.wordbyword.app.core.transcript.TranscriptEditor;
import dev.wordbyword.app.export.TranscriptJsonExporter;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.NoSuchElementException;
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
    repository()
        .update(
            first.recording().id(),
            value -> new TranscriptEditor(value).renameSpeaker("a", "Анна").build());
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
    Files.write(metadata, "{broken".getBytes(StandardCharsets.UTF_8));
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
    Files.write(unfinished, "unfinished".getBytes(StandardCharsets.UTF_8));
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
  public void silenceRemainsDistinctFromUnprocessedImport() throws Exception {
    var saved = repository().save(new Transcript(recording("silence"), List.of(), List.of()));
    assertEquals(List.of(), repository().find("silence").orElseThrow().utterances());
    assertEquals(List.of(saved.recording()), repository().listRecordings());
  }

  @Test
  public void unsupportedVersionAndFractionalTimesAreRejectedWithoutRewriting() throws Exception {
    var saved = repository().save(transcript(recording("first")));
    Path metadata = Paths.get(saved.recording().audioUri()).getParent().resolve("recording.json");
    String original = new String(Files.readAllBytes(metadata), StandardCharsets.UTF_8);
    for (String invalid :
        List.of(
            original.replace("\"version\":2", "\"version\":999"),
            original.replace("\"start_ms\":100", "\"start_ms\":100.5"))) {
      Files.write(metadata, invalid.getBytes(StandardCharsets.UTF_8));
      assertThrows(IOException.class, () -> repository().find("first"));
      assertEquals(invalid, new String(Files.readAllBytes(metadata), StandardCharsets.UTF_8));
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

  @Test
  public void storedUtterancesUseTheSameSchemaAsExport() throws Exception {
    var saved = repository().save(transcript(recording("schema")));
    Path metadata = Paths.get(saved.recording().audioUri()).getParent().resolve("recording.json");
    var stored =
        JsonParser.parseString(new String(Files.readAllBytes(metadata), StandardCharsets.UTF_8))
            .getAsJsonObject();
    var writer = new StringWriter();
    TranscriptJsonExporter.write(saved, writer);
    var exported = JsonParser.parseString(writer.toString()).getAsJsonObject();
    assertEquals(2, stored.get("version").getAsInt());
    assertFalse(stored.has("utterances"));
    assertEquals(exported.get("transcript"), stored.get("transcript"));
  }

  @Test
  public void unknownSpeakerStaysNullInStorageAndGetsExportLabelOnlyAtExport() throws Exception {
    var saved =
        repository()
            .save(
                new Transcript(
                    recording("unknown"),
                    List.of(),
                    List.of(new Transcript.Utterance(Optional.empty(), "Речь", 0, 100))));
    Path metadata = Paths.get(saved.recording().audioUri()).getParent().resolve("recording.json");
    var stored =
        JsonParser.parseString(new String(Files.readAllBytes(metadata), StandardCharsets.UTF_8))
            .getAsJsonObject();
    assertTrue(
        stored
            .getAsJsonArray("transcript")
            .get(0)
            .getAsJsonObject()
            .get("speaker_id")
            .isJsonNull());
    assertEquals(saved, repository().find("unknown").orElseThrow());
    var writer = new StringWriter();
    TranscriptJsonExporter.write(saved, writer);
    var exported = JsonParser.parseString(writer.toString()).getAsJsonObject();
    assertEquals(
        "unknown",
        exported
            .getAsJsonArray("transcript")
            .get(0)
            .getAsJsonObject()
            .get("speaker_id")
            .getAsString());
    assertTrue(
        repository().find("unknown").orElseThrow().utterances().get(0).speakerId().isEmpty());
  }

  @Test
  public void legacyStorageIsReadableAndUpgradedOnNextSave() throws Exception {
    var saved = repository().save(transcript(recording("legacy")));
    Path metadata = Paths.get(saved.recording().audioUri()).getParent().resolve("recording.json");
    var legacy =
        JsonParser.parseString(new String(Files.readAllBytes(metadata), StandardCharsets.UTF_8))
            .getAsJsonObject();
    legacy.addProperty("version", 1);
    var utterances = legacy.remove("transcript").getAsJsonArray();
    var unknown = new JsonObject();
    unknown.addProperty("text", "Без участника");
    unknown.addProperty("start_ms", 900);
    unknown.addProperty("end_ms", 1000);
    utterances.add(unknown);
    legacy.add("utterances", utterances);
    Files.write(metadata, legacy.toString().getBytes(StandardCharsets.UTF_8));
    var restored = repository().find("legacy").orElseThrow();
    assertEquals(saved.utterances().get(0), restored.utterances().get(0));
    assertTrue(restored.utterances().get(1).speakerId().isEmpty());
    repository()
        .update("legacy", value -> new TranscriptEditor(value).renameSpeaker("a", "Анна").build());
    var upgraded =
        JsonParser.parseString(new String(Files.readAllBytes(metadata), StandardCharsets.UTF_8))
            .getAsJsonObject();
    assertEquals(2, upgraded.get("version").getAsInt());
    assertTrue(upgraded.has("transcript"));
    assertFalse(upgraded.has("utterances"));
    assertEquals(restored.utterances(), repository().find("legacy").orElseThrow().utterances());
  }

  @Test
  public void failedBatchDoesNotPersistAnyEdits() throws Exception {
    var saved = repository().save(transcript(recording("batch")));
    assertThrows(
        NoSuchElementException.class,
        () ->
            repository()
                .update(
                    "batch",
                    value ->
                        new TranscriptEditor(value)
                            .renameSpeaker("a", "Анна")
                            .renameSpeaker("missing", "Борис")
                            .build()));
    assertEquals(saved, repository().find("batch").orElseThrow());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            repository()
                .update(
                    "batch",
                    value ->
                        new Transcript(
                            new Recording(
                                "other",
                                value.recording().audioUri(),
                                "Other",
                                OptionalLong.empty()),
                            value.speakers(),
                            value.utterances())));
    assertEquals(saved, repository().find("batch").orElseThrow());
    assertThrows(
        NoSuchElementException.class, () -> repository().update("missing", value -> value));
  }

  @Test
  public void batchUpdateUsesLatestSavedTranscript() throws Exception {
    var saved = repository().save(transcript(recording("batch")));
    var repo = repository();
    repo.update("batch", value -> new TranscriptEditor(value).renameSpeaker("a", "Анна").build());
    var updated =
        repo.update(
            "batch",
            value -> {
              assertEquals("Анна", value.speakers().get(0).displayName());
              return new TranscriptEditor(value).renameSpeaker("a", "Аня").build();
            });
    assertEquals(updated, repository().find("batch").orElseThrow());
    assertEquals(saved.utterances(), updated.utterances());
  }
}

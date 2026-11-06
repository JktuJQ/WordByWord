package dev.wordbyword.app.ui;

import static org.junit.Assert.*;

import dev.wordbyword.app.core.processing.ProcessingException;
import dev.wordbyword.app.core.recording.Recording;
import dev.wordbyword.app.core.transcript.Transcript;
import dev.wordbyword.app.storage.FileTranscriptRepository;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class AppControllerTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  private FileTranscriptRepository repository() {
    return new FileTranscriptRepository(
        temporary.getRoot().toPath().resolve("library"),
        uri -> Files.newInputStream(Paths.get(uri)));
  }

  private Recording recording() throws Exception {
    var source = temporary.newFile().toPath();
    Files.write(source, new byte[] {1, 2, 3, 4});
    return repository()
        .saveRecording(
            new Recording(
                UUID.randomUUID().toString(), source.toUri(), "Запись", OptionalLong.of(18000)));
  }

  @Test
  public void successfulTaskCanBeOpenedAndSurvivesListenerReplacement() throws Exception {
    var worker = new ArrayDeque<Runnable>();
    var repository = repository();
    var recording = recording();
    var result = new Transcript(recording, List.of(), List.of());
    var controller =
        new AppController(
            repository,
            (input, context) -> {
              context.reportProgress("ASR", OptionalDouble.of(0.5));
              return result;
            },
            worker::add,
            Runnable::run);
    controller.add(recording);
    controller.start(recording.id());
    controller.start(recording.id());
    assertEquals(1, worker.size());
    assertTrue(controller.state().busy());
    controller.detach();
    worker.remove().run();
    assertFalse(controller.state().busy());
    assertEquals(result, repository.find(recording.id()).orElseThrow());
    controller.attach(state -> assertEquals(AppState.Status.READY, state.items().get(0).status()));
    controller.select(recording.id());
    assertEquals(result, controller.state().selected().transcript());
    controller.showLibrary();
    assertNull(controller.state().selected());
  }

  @Test
  public void cancelledTaskNeverSavesEvenIfPipelineIgnoresCancellation() throws Exception {
    var worker = new ArrayDeque<Runnable>();
    var repository = repository();
    Recording recording = recording();
    var controller =
        new AppController(
            repository,
            (input, context) -> new Transcript(input, List.of(), List.of()),
            worker::add,
            Runnable::run);
    controller.add(recording);
    controller.start(recording.id());
    controller.cancel();
    assertTrue(controller.state().cancelling());
    worker.remove().run();
    assertTrue(repository.find(recording.id()).isEmpty());
    assertEquals(AppState.Status.CANCELLED, controller.state().items().get(0).status());
    controller.start(recording.id());
    worker.remove().run();
    assertEquals(AppState.Status.READY, controller.state().items().get(0).status());
  }

  @Test
  public void failedTaskKeepsRecordingForRetryAndDoesNotSave() throws Exception {
    var worker = new ArrayDeque<Runnable>();
    var repository = repository();
    var recording = recording();
    var controller =
        new AppController(
            repository,
            (input, context) -> {
              throw new ProcessingException("Модель недоступна");
            },
            worker::add,
            Runnable::run);
    controller.add(recording);
    controller.start(recording.id());
    worker.remove().run();
    var item = controller.state().items().get(0);
    assertEquals(AppState.Status.ERROR, item.status());
    assertEquals("Модель недоступна", item.detail());
    assertTrue(repository.find(recording.id()).isEmpty());
    assertEquals(List.of(recording), repository.listRecordings());
    assertFalse(controller.state().busy());
    controller.select(recording.id());
    assertNull(controller.state().selected());
    controller.start(recording.id());
    assertEquals(1, worker.size());
  }

  @Test
  public void renamingSpeakerUpdatesAllVisibleUtterancesAfterSave() throws Exception {
    var worker = new ArrayDeque<Runnable>();
    var repository = repository();
    var recording = recording();
    var transcript =
        new Transcript(
            recording,
            List.of(new Transcript.Speaker("one", "Участник 1")),
            List.of(
                new Transcript.Utterance(Optional.of("one"), "Привет", 0, 1000),
                new Transcript.Utterance(Optional.of("one"), "До встречи", 2000, 3000)));
    var controller =
        new AppController(repository, (input, context) -> transcript, worker::add, Runnable::run);
    controller.add(recording);
    controller.start(recording.id());
    worker.remove().run();
    controller.select(recording.id());
    controller.renameSpeaker("one", "Анна");
    worker.remove().run();
    assertEquals(
        "Анна", controller.state().selected().transcript().speakers().get(0).displayName());
    assertEquals(
        "Анна", repository.find(recording.id()).orElseThrow().speakers().get(0).displayName());
    assertEquals(2, controller.state().selected().transcript().utterances().size());
  }

  @Test
  public void loadRestoresReadyAndUnprocessedEntriesWithoutRunningPipeline() throws Exception {
    var worker = new ArrayDeque<Runnable>();
    var repository = repository();
    var ready = recording();
    var imported = new Recording("pending", ready.audioUri(), "Импорт", ready.durationMs());
    repository.save(new Transcript(ready, List.of(), List.of()));
    repository.saveRecording(imported);
    var controller =
        new AppController(
            repository,
            (input, context) -> {
              throw new AssertionError("Unexpected processing");
            },
            worker::add,
            Runnable::run);
    controller.load();
    assertTrue(controller.state().items().isEmpty());
    worker.remove().run();
    assertEquals(AppState.Status.READY, controller.state().find(ready.id()).status());
    assertEquals(AppState.Status.IMPORTED, controller.state().find(imported.id()).status());
    controller.select(ready.id());
    assertNotNull(controller.state().selected().transcript());
  }

  @Test
  public void pendingDeleteBlocksProcessingAndNeverResurrectsEntry() throws Exception {
    var worker = new ArrayDeque<Runnable>();
    var repository = repository();
    var recording = recording();
    repository.saveRecording(recording);
    var controller =
        new AppController(
            repository,
            (input, context) -> {
              throw new AssertionError("Deleted recording processed");
            },
            worker::add,
            Runnable::run);
    controller.add(recording);
    controller.delete(Set.of(recording.id()));
    controller.start(recording.id());
    assertTrue(controller.state().busy());
    assertEquals(1, worker.size());
    worker.remove().run();
    assertFalse(controller.state().busy());
    assertTrue(controller.state().items().isEmpty());
    controller.load();
    worker.remove().run();
    assertTrue(controller.state().items().isEmpty());
  }
}

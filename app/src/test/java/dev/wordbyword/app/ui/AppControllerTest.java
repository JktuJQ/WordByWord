package dev.wordbyword.app.ui;

import static org.junit.Assert.*;

import dev.wordbyword.app.core.processing.ProcessingException;
import dev.wordbyword.app.core.recording.Recording;
import dev.wordbyword.app.core.transcript.Transcript;
import dev.wordbyword.app.export.TranscriptJsonExporter;
import dev.wordbyword.app.storage.FileTranscriptRepository;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
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
    var calls = new AtomicInteger();
    var controller =
        new AppController(
            repository,
            (input, context) -> {
              calls.incrementAndGet();
              return new Transcript(input, List.of(), List.of());
            },
            worker::add,
            worker::add,
            Runnable::run);
    controller.add(recording);
    controller.start(recording.id());
    controller.cancel();
    assertTrue(controller.state().cancelling());
    worker.remove().run();
    assertEquals(0, calls.get());
    assertTrue(repository.find(recording.id()).isEmpty());
    assertEquals(AppState.Status.CANCELLED, controller.state().items().get(0).status());
    controller.start(recording.id());
    worker.remove().run();
    assertEquals(AppState.Status.READY, controller.state().items().get(0).status());
    assertEquals(1, calls.get());
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
        new AppController(
            repository, (input, context) -> transcript, worker::add, worker::add, Runnable::run);
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

  @Test(timeout = 20000)
  public void libraryOperationsProceedWhileProcessingIsBlocked() throws Exception {
    var processing = Executors.newSingleThreadExecutor();
    var io = Executors.newSingleThreadExecutor();
    var ui = new LinkedBlockingQueue<Runnable>();
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try {
      var repository = repository();
      var active = recording();
      var ready = recording();
      repository.save(
          new Transcript(ready, List.of(new Transcript.Speaker("a", "Первый")), List.of()));
      var controller =
          new AppController(
              repository,
              (input, context) -> {
                started.countDown();
                try {
                  if (!release.await(15, TimeUnit.SECONDS))
                    throw new AssertionError("Processing never released");
                } catch (InterruptedException exception) {
                  Thread.currentThread().interrupt();
                  throw new CancellationException();
                }
                return new Transcript(input, List.of(), List.of());
              },
              processing,
              io,
              ui::add);
      controller.add(active);
      controller.start(active.id());
      assertTrue(started.await(5, TimeUnit.SECONDS));
      controller.load();
      drainUntil(ui, () -> controller.state().find(ready.id()) != null);
      assertEquals(AppState.Status.PROCESSING, controller.state().find(active.id()).status());
      controller.select(ready.id());
      controller.renameSpeaker("a", "Анна");
      drainUntil(
          ui,
          () ->
              controller
                  .state()
                  .selected()
                  .transcript()
                  .speakers()
                  .get(0)
                  .displayName()
                  .equals("Анна"));
      var snapshot = controller.state().selected().transcript();
      String exported =
          io.submit(
                  () -> {
                    var writer = new StringWriter();
                    TranscriptJsonExporter.write(snapshot, writer);
                    return writer.toString();
                  })
              .get(5, TimeUnit.SECONDS);
      assertTrue(exported.contains("transcript"));
      var imported =
          new Recording("parallel-import", ready.audioUri(), "Импорт", OptionalLong.empty());
      io.execute(
          () -> {
            try {
              var saved = repository.saveRecording(imported);
              ui.add(() -> controller.add(saved));
            } catch (Exception exception) {
              throw new AssertionError(exception);
            }
          });
      drainUntil(ui, () -> controller.state().find(imported.id()) != null);
      assertTrue(controller.state().busy());
      assertFalse(controller.canDelete(Set.of(active.id())));
      assertFalse(controller.canDelete(Set.of(active.id(), ready.id())));
      controller.delete(Set.of(active.id()));
      assertTrue(
          repository.listRecordings().stream().anyMatch(item -> item.id().equals(active.id())));
      assertTrue(controller.canDelete(Set.of(ready.id())));
      controller.delete(Set.of(ready.id()));
      drainUntil(ui, () -> controller.state().find(ready.id()) == null);
      assertTrue(repository.find(ready.id()).isEmpty());
      assertTrue(controller.state().busy());
      controller.cancel();
      release.countDown();
      drainUntil(ui, () -> !controller.state().busy());
      assertEquals(AppState.Status.CANCELLED, controller.state().find(active.id()).status());
      assertTrue(repository.find(active.id()).isEmpty());
      controller.start(active.id());
      drainUntil(ui, () -> !controller.state().busy());
      assertEquals(AppState.Status.READY, controller.state().find(active.id()).status());
    } finally {
      release.countDown();
      processing.shutdownNow();
      io.shutdownNow();
      assertTrue(processing.awaitTermination(5, TimeUnit.SECONDS));
      assertTrue(io.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  @Test
  public void rejectedProcessingSubmissionDoesNotLeaveControllerBusy() throws Exception {
    var repository = repository();
    var recording = recording();
    var controller =
        new AppController(
            repository,
            (input, context) -> {
              throw new AssertionError("Unexpected processing");
            },
            task -> {
              throw new RejectedExecutionException("Queue unavailable");
            },
            Runnable::run,
            Runnable::run);
    controller.add(recording);
    controller.start(recording.id());
    assertFalse(controller.state().busy());
    assertEquals(AppState.Status.ERROR, controller.state().find(recording.id()).status());
    controller.start(recording.id());
    assertFalse(controller.state().busy());
    assertTrue(repository.find(recording.id()).isEmpty());
  }

  @Test
  public void cancellationTakesPrecedenceOverLaterPipelineFailure() throws Exception {
    var worker = new ArrayDeque<Runnable>();
    var recording = recording();
    var current = new AppController[1];
    current[0] =
        new AppController(
            repository(),
            (input, context) -> {
              current[0].cancel();
              throw new ProcessingException("Failure after cancel");
            },
            worker::add,
            worker::add,
            Runnable::run);
    current[0].add(recording);
    current[0].start(recording.id());
    worker.remove().run();
    assertEquals(AppState.Status.CANCELLED, current[0].state().find(recording.id()).status());
    assertFalse(current[0].state().busy());
  }

  private static void drainUntil(LinkedBlockingQueue<Runnable> ui, BooleanSupplier condition)
      throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean()) {
      long remaining = deadline - System.nanoTime();
      assertTrue("UI operation timed out", remaining > 0);
      Runnable callback = ui.poll(remaining, TimeUnit.NANOSECONDS);
      assertNotNull("UI callback never arrived", callback);
      callback.run();
    }
  }
}

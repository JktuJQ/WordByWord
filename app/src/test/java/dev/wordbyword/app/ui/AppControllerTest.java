package dev.wordbyword.app.ui;

import static org.junit.Assert.*;

import dev.wordbyword.app.core.processing.ProcessingException;
import dev.wordbyword.app.core.recording.Recording;
import dev.wordbyword.app.core.transcript.Transcript;
import dev.wordbyword.app.demo.DemoBackend;
import java.util.ArrayDeque;
import java.util.List;
import java.util.OptionalDouble;
import org.junit.Test;

public class AppControllerTest {
  @Test
  public void successfulTaskCanBeOpenedAndSurvivesListenerReplacement() {
    var worker = new ArrayDeque<Runnable>();
    var repository = new DemoBackend();
    var recording = DemoBackend.recording();
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
    controller.attach(
        state -> assertEquals(AppController.Status.READY, state.items().get(0).status()));
    controller.select(recording.id());
    assertEquals(result, controller.state().selected().transcript());
    controller.showLibrary();
    assertNull(controller.state().selected());
  }

  @Test
  public void cancelledTaskNeverSavesEvenIfPipelineIgnoresCancellation() {
    var worker = new ArrayDeque<Runnable>();
    var repository = new DemoBackend();
    Recording recording = DemoBackend.recording();
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
    assertEquals(AppController.Status.CANCELLED, controller.state().items().get(0).status());
    controller.start(recording.id());
    worker.remove().run();
    assertEquals(AppController.Status.READY, controller.state().items().get(0).status());
  }

  @Test
  public void failedTaskKeepsRecordingForRetryAndDoesNotSave() {
    var worker = new ArrayDeque<Runnable>();
    var repository = new DemoBackend();
    var recording = DemoBackend.recording();
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
    assertEquals(AppController.Status.ERROR, item.status());
    assertEquals("Модель недоступна", item.detail());
    assertTrue(repository.find(recording.id()).isEmpty());
    assertFalse(controller.state().busy());
    controller.select(recording.id());
    assertNull(controller.state().selected());
    controller.start(recording.id());
    assertEquals(1, worker.size());
  }
}

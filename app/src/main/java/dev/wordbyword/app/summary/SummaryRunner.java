package dev.wordbyword.app.summary;

import dev.wordbyword.app.core.transcript.Transcript;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class SummaryRunner {
  public record State(String key, String text, String detail, boolean busy) {}

  private final LocalSummarizer model;
  private final SummaryStore store;
  private final Executor worker;
  private final Executor ui;
  private final Map<String, State> states = new HashMap<>();
  private AtomicBoolean cancelled;
  private String activeId;
  private Runnable changed = () -> {};

  public SummaryRunner(LocalSummarizer model, SummaryStore store, Executor worker, Executor ui) {
    this.model = model;
    this.store = store;
    this.worker = worker;
    this.ui = ui;
  }

  public void onChange(Runnable changed) {
    this.changed = changed;
  }

  public boolean isBusy() {
    return activeId != null;
  }

  public State state(String id) {
    return states.getOrDefault(id, new State("", "", "", false));
  }

  public void start(Transcript transcript) {
    run(transcript, true);
  }

  public void open(Transcript transcript) {
    String key = SummaryText.key("qwen3-extractive-v1\n" + SummaryText.source(transcript));
    if (!key.equals(state(transcript.recording().id()).key())) run(transcript, false);
  }

  private void run(Transcript transcript, boolean generate) {
    if (isBusy()) return;
    String id = transcript.recording().id();
    String source = SummaryText.source(transcript);
    String key = SummaryText.key("qwen3-extractive-v1\n" + source);
    activeId = id;
    cancelled = new AtomicBoolean();
    var cancellation = cancelled;
    var old = state(id);
    String previous = key.equals(old.key()) ? old.text() : "";
    states.put(id, new State(key, previous, "Загружаем краткое содержание…", true));
    changed.run();
    try {
      worker.execute(
          () -> {
            String text = previous;
            String detail = "";
            try {
              String saved;
              try {
                saved = store.load(id, key);
              } catch (IOException exception) {
                if (!generate) throw exception;
                saved = "";
              }
              if (!saved.isBlank()) text = saved;
              else if (generate) {
                text =
                    model.summarize(
                        source,
                        cancellation,
                        progress ->
                            ui.execute(
                                () -> {
                                  var current = state(id);
                                  states.put(id, new State(key, current.text(), progress, true));
                                  changed.run();
                                }));
                if (cancellation.get()) throw new CancellationException();
                store.save(id, key, text);
              }
            } catch (CancellationException exception) {
              text = previous;
              detail = "Создание отменено";
            } catch (Exception | LinkageError exception) {
              text = previous;
              detail = "Не удалось создать краткое содержание. Можно повторить попытку.";
              Logger.getLogger(SummaryRunner.class.getName())
                  .log(Level.WARNING, "Summary failed", exception);
            }
            String result = text;
            String status = detail;
            ui.execute(() -> finish(id, key, result, status));
          });
    } catch (RuntimeException exception) {
      finish(id, key, previous, "Не удалось запустить обработку");
    }
  }

  public void cancel() {
    if (cancelled != null) cancelled.set(true);
  }

  private void finish(String id, String key, String text, String detail) {
    states.put(id, new State(key, text, detail, false));
    activeId = null;
    cancelled = null;
    changed.run();
  }
}

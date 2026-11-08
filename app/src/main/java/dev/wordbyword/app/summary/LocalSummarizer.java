package dev.wordbyword.app.summary;

import android.content.Context;
import com.google.ai.edge.litertlm.Backend;
import com.google.ai.edge.litertlm.ConversationConfig;
import com.google.ai.edge.litertlm.Engine;
import com.google.ai.edge.litertlm.EngineConfig;
import com.google.ai.edge.litertlm.Message;
import com.google.ai.edge.litertlm.MessageCallback;
import com.google.ai.edge.litertlm.SamplerConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class LocalSummarizer {
  private final Context context;

  public LocalSummarizer(Context context) {
    this.context = context.getApplicationContext();
  }

  public String summarize(String source, AtomicBoolean cancelled, Consumer<String> progress)
      throws Exception {
    checkCancelled(cancelled);
    progress.accept("Подготавливаем модель…");
    Path model = context.getNoBackupFilesDir().toPath().resolve("summary-qwen3-v1.litertlm");
    if (!Files.isRegularFile(model) || Files.size(model) != 347251840L) {
      Path temporary = model.resolveSibling(model.getFileName() + ".tmp");
      try {
        try (var input = context.getAssets().open("summary/model.litertlm")) {
          Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
        }
        checkCancelled(cancelled);
        Files.move(temporary, model, StandardCopyOption.REPLACE_EXISTING);
      } finally {
        Files.deleteIfExists(temporary);
      }
    }
    checkCancelled(cancelled);
    var chunks = SummaryText.chunks(source);
    if (chunks.isEmpty()) throw new IOException("В стенограмме нет текста");
    try (var engine =
        new Engine(
            new EngineConfig(
                model.toString(),
                new Backend.CPU(),
                null,
                null,
                1280,
                null,
                context.getCacheDir().toString()))) {
      engine.initialize();
      StringBuilder result = new StringBuilder();
      for (int index = 0; index < chunks.size(); index++) {
        checkCancelled(cancelled);
        progress.accept("Часть " + (index + 1) + " из " + chunks.size());
        String answer = generate(engine, chunks.get(index), cancelled);
        if (chunks.size() > 1) result.append("Часть ").append(index + 1).append('\n');
        result.append(answer).append("\n\n");
      }
      checkCancelled(cancelled);
      return result.toString().trim();
    }
  }

  private static String generate(Engine engine, String input, AtomicBoolean cancelled)
      throws Exception {
    try (var conversation =
        engine.createConversation(
            new ConversationConfig(
                null, List.of(), List.of(), new SamplerConfig(1, 1.0, 0.0, 0)))) {
      var completed = new CountDownLatch(1);
      var failure = new AtomicReference<Throwable>();
      var text = new StringBuffer();
      var passages = SummaryText.passages(input);
      StringBuilder prompt =
          new StringBuilder(
              "Выбери до трёх самых важных фрагментов разговора. Верни только их номера через"
                  + " запятую, без пояснений.\n");
      for (int index = 0; index < passages.size(); index++)
        prompt.append(index + 1).append(") ").append(passages.get(index)).append('\n');
      conversation.sendMessageAsync(
          prompt.toString(),
          new MessageCallback() {
            @Override
            public void onMessage(Message message) {
              text.append(message.toString());
            }

            @Override
            public void onDone() {
              completed.countDown();
            }

            @Override
            public void onError(Throwable error) {
              failure.set(error);
              completed.countDown();
            }
          },
          Map.of(),
          null,
          null,
          null,
          32);
      boolean finished = false;
      try {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(180);
        while (!(finished = completed.await(100, TimeUnit.MILLISECONDS))) {
          checkCancelled(cancelled);
          if (System.nanoTime() >= deadline)
            throw new IOException("Превышено время ожидания модели");
        }
        checkCancelled(cancelled);
        if (failure.get() != null) throw new IOException("Ошибка генерации", failure.get());
        String answer = text.toString().trim();
        if (answer.isEmpty()) throw new IOException("Модель вернула пустой ответ");
        return SummaryText.select(passages, answer);
      } finally {
        if (!finished) conversation.cancelProcess();
      }
    }
  }

  private static void checkCancelled(AtomicBoolean cancelled) {
    if (cancelled.get()) throw new CancellationException();
  }
}

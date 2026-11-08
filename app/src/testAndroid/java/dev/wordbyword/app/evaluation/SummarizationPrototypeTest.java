package dev.wordbyword.app.evaluation;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Debug;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.ai.edge.litertlm.Backend;
import com.google.ai.edge.litertlm.ConversationConfig;
import com.google.ai.edge.litertlm.Engine;
import com.google.ai.edge.litertlm.EngineConfig;
import com.google.ai.edge.litertlm.Message;
import com.google.ai.edge.litertlm.MessageCallback;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class SummarizationPrototypeTest {
  private static final String[] COMPARISON_INPUTS = {
    "Анна: Встречу перенесли на пятницу, в 15:00. Бюджет — 120 тысяч рублей. "
        + "Борис: Я подготовлю смету к четвергу. Новый сервер пока не покупаем. "
        + "Анна: Да, сначала проверим старый. Ольга пришлёт результаты проверки завтра.",
    "Ирина: Запуск был назначен на 12 мая, но мы перенесли его на 19 мая. "
        + "Максим: Тесты ещё не закончены. Я пришлю отчёт в понедельник. "
        + "Ирина: До отчёта клиентам ничего не обещаем. На рекламу выделили 35 тысяч рублей.",
    "Сергей: Может быть, купим десять ноутбуков? "
        + "Мария: Нет, покупку не согласовали. Пока арендуем три ноутбука на две недели. "
        + "Сергей: Хорошо. Я уточню стоимость аренды завтра. "
        + "Мария: Поставщика ещё не выбрали."
  };
  private static final String[] COMPARISON_PROMPTS = {
    "Сделай краткую выжимку разговора на русском в 3–5 предложениях. "
        + "Сохрани важные имена, числа и отрицания. Не добавляй фактов.\nРазговор:\n",
    "Составь конспект: выпиши только факты из разговора, по одному в строке. "
        + "Сохраняй точные даты, суммы, имена и отрицания. Будущее действие не описывай "
        + "как выполненное. Предложение не выдавай за решение. Не делай выводов. "
        + "Если дату исправили, укажи новую дату. Ответ — только список фактов.\nРазговор:\n",
    "Составь краткий конспект разговора. Сохраняй имена, числа, отрицания и сроки. "
        + "Отличай предложения от решений, планы от выполненных действий. Не додумывай.\n"
        + "Пример разговора:\nЛена: Релиз не готов. Переносим на 8 июня.\n"
        + "Павел: Пришлю тесты завтра.\nЛена: Рекламу не запускаем.\n"
        + "Пример конспекта:\n- Релиз не готов, перенесён на 8 июня.\n"
        + "- Павел пришлёт тесты завтра.\n- Рекламу не запускают.\n"
        + "Теперь составь конспект другого разговора. Ответ — только список фактов.\nРазговор:\n"
  };

  @Test
  public void summarizeRussianConversation() throws Exception {
    var instrumentation = InstrumentationRegistry.getInstrumentation();
    var context = instrumentation.getTargetContext();
    var arguments = InstrumentationRegistry.getArguments();
    org.junit.Assume.assumeTrue(
        "Explicit opt-in required", "true".equals(arguments.getString("runSummary")));
    assertTrue(
        "Target must have no network permission",
        context.checkSelfPermission("android.permission.INTERNET")
            == PackageManager.PERMISSION_DENIED);
    var model = context.getExternalFilesDir(null).toPath().resolve("summary.litertlm");
    assertTrue(
        "Prepare summary.litertlm in target external files before running",
        Files.isRegularFile(model));
    String input =
        arguments.getString(
            "summaryInput",
            "Анна: Встречу перенесли на пятницу, в 15:00. Бюджет — 120 тысяч рублей. "
                + "Борис: Я подготовлю смету к четвергу. Новый сервер пока не покупаем. "
                + "Анна: Да, сначала проверим старый. Ольга пришлёт результаты проверки завтра.");
    assertTrue("Prototype accepts only a short input", input.length() <= 900);
    String prompt =
        arguments.getString(
                "summaryPrompt",
                "Перечисли кратко основные факты разговора на русском языке. Отдельный пункт для"
                    + " каждого факта: что решили, кто что сделает, когда, суммы. Обязательно"
                    + " сохрани числа, сроки и запреты. Не добавляй своих выводов.\n"
                    + "Разговор:\n")
            + input;
    var report = new JsonObject();
    report.addProperty("device", Build.MODEL);
    report.addProperty("android", Build.VERSION.RELEASE);
    report.addProperty("runtime", "LiteRT-LM 0.17.1 CPU");
    report.addProperty("model_revision", "56621f0115eba5781051ef9714c06e16e6d40c0b");
    report.addProperty("input", input);
    report.addProperty("prompt", prompt);
    var runs = new JsonArray();
    report.add("runs", runs);
    String reportName = arguments.getString("summaryReport", "summary-prototype.json");
    assertTrue("Invalid report name", reportName.matches("[A-Za-z0-9_.-]+\\.json"));
    var reportPath = context.getExternalFilesDir(null).toPath().resolve(reportName);
    boolean comparison = "true".equals(arguments.getString("promptComparison"));
    boolean study = "true".equals(arguments.getString("promptStudy"));
    boolean holdout = "true".equals(arguments.getString("promptHoldout"));
    int selectedPrompt = Integer.parseInt(arguments.getString("selectedPrompt", "0"));
    JsonArray corpus = null;
    if ("true".equals(arguments.getString("summaryCorpus"))) {
      try (var reader =
          Files.newBufferedReader(
              context.getExternalFilesDir(null).toPath().resolve("summary-corpus.json"),
              StandardCharsets.UTF_8)) {
        corpus = com.google.gson.JsonParser.parseReader(reader).getAsJsonArray();
      }
    }
    try {
      for (int index = 0;
          index < (corpus != null ? corpus.size() : holdout ? 1 : study ? 6 : comparison ? 9 : 3);
          index++) {
        var run = new JsonObject();
        runs.add(run);
        String caseInput = comparison ? COMPARISON_INPUTS[index % 3] : input;
        String casePrompt = comparison ? COMPARISON_PROMPTS[index / 3] + caseInput : prompt;
        if (study || holdout) {
          caseInput = holdout ? SummaryPromptStudy.HOLDOUT : COMPARISON_INPUTS[index % 3];
          casePrompt = SummaryPromptStudy.PROMPTS[holdout ? selectedPrompt : index / 3] + caseInput;
        }
        if (corpus != null) {
          var source = corpus.get(index).getAsJsonObject();
          caseInput = source.get("text").getAsString();
          assertTrue("Corpus input too long for this prototype", caseInput.length() <= 1800);
          casePrompt = SummaryPromptStudy.PROMPTS[selectedPrompt] + caseInput;
          run.add("source", source);
        }
        run.addProperty("phase", holdout ? "holdout" : "development");
        run.addProperty("case_index", holdout ? -1 : (comparison || study) ? index % 3 : 0);
        run.addProperty(
            "prompt_index", holdout ? selectedPrompt : (comparison || study) ? index / 3 : 0);
        run.addProperty("input", caseInput);
        run.addProperty("prompt", casePrompt);
        long started = SystemClock.elapsedRealtime();
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
          run.addProperty("load_ms", SystemClock.elapsedRealtime() - started);
          try (var conversation = engine.createConversation(new ConversationConfig())) {
            var completed = new CountDownLatch(1);
            var failure = new AtomicReference<Throwable>();
            var text = new StringBuffer();
            long generationStarted = SystemClock.elapsedRealtime();
            conversation.sendMessageAsync(
                casePrompt,
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
                java.util.Map.of(),
                null,
                null,
                null,
                256);
            boolean finished = completed.await(180, TimeUnit.SECONDS);
            if (!finished) conversation.cancelProcess();
            assertTrue("Generation timed out after 180 seconds", finished);
            if (failure.get() != null)
              throw new AssertionError("LLM callback failed", failure.get());
            run.addProperty("generation_ms", SystemClock.elapsedRealtime() - generationStarted);
            run.addProperty("output", text.toString());
            run.addProperty("pss_kb_after_generation", Debug.getPss());
            run.addProperty(
                "process_status",
                new String(
                    Files.readAllBytes(Paths.get("/proc/self/status")), StandardCharsets.UTF_8));
            assertFalse("Empty model answer", text.toString().isBlank());
          }
        }
      }
    } finally {
      Files.write(reportPath, report.toString().getBytes(StandardCharsets.UTF_8));
    }
  }
}

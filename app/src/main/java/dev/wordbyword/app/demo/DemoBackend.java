package dev.wordbyword.app.demo;

import dev.wordbyword.app.core.processing.ProcessingContext;
import dev.wordbyword.app.core.processing.ProcessingException;
import dev.wordbyword.app.core.processing.TranscriptionPipeline;
import dev.wordbyword.app.core.recording.Recording;
import dev.wordbyword.app.core.storage.TranscriptRepository;
import dev.wordbyword.app.core.transcript.Transcript;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;

public final class DemoBackend implements TranscriptRepository, TranscriptionPipeline {
  private final Map<String, Recording> recordings = new LinkedHashMap<>();
  private final Map<String, Transcript> transcripts = new LinkedHashMap<>();

  public static Recording recording() {
    return new Recording(
        "demo",
        URI.create("demo://conversation"),
        "Знакомство с приложением",
        OptionalLong.of(18000));
  }

  @Override
  public Transcript transcribe(Recording recording, ProcessingContext context)
      throws ProcessingException {
    if (!recording.audioUri().getScheme().equals("demo")) {
      throw new ProcessingException(
          "Распознавание аудио ещё не подключено. Пока можно открыть встроенный пример.");
    }
    for (int index = 0; index <= 20; index++) {
      context.throwIfCancelled();
      context.reportProgress(
          "Подготовка демонстрационного примера", OptionalDouble.of(index / 20.0));
      try {
        Thread.sleep(100);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new java.util.concurrent.CancellationException();
      }
    }
    context.throwIfCancelled();
    return new Transcript(
        recording,
        List.of(
            new Transcript.Speaker("speaker_1", "Участник 1"),
            new Transcript.Speaker("speaker_2", "Участник 2")),
        List.of(
            new Transcript.Utterance(
                Optional.of("speaker_1"), "Давайте соберём все мысли в одном месте.", 500, 4200),
            new Transcript.Utterance(
                Optional.of("speaker_2"),
                "Хорошо. Здесь мы увидим реплики и время каждого фрагмента.",
                4600,
                9700),
            new Transcript.Utterance(
                Optional.of("speaker_1"),
                "А результат сможем сохранить в JSON и передать дальше.",
                10200,
                15100),
            new Transcript.Utterance(
                Optional.of("speaker_2"), "Да, начнём с этого.", 15500, 18000)));
  }

  @Override
  public synchronized List<Recording> listRecordings() {
    return List.copyOf(recordings.values());
  }

  @Override
  public synchronized Optional<Transcript> find(String recordingId) {
    return Optional.ofNullable(transcripts.get(recordingId));
  }

  @Override
  public synchronized Recording saveRecording(Recording recording) {
    recordings.put(recording.id(), recording);
    return recording;
  }

  @Override
  public synchronized Transcript save(Transcript transcript) {
    saveRecording(transcript.recording());
    transcripts.put(transcript.recording().id(), transcript);
    return transcript;
  }

  @Override
  public synchronized void delete(String recordingId) {
    recordings.remove(recordingId);
    transcripts.remove(recordingId);
  }

  @Override
  public synchronized void renameSpeaker(String recordingId, String speakerId, String displayName) {
    Transcript transcript = find(recordingId).orElseThrow(NoSuchElementException::new);
    if (transcript.speakers().stream().noneMatch(speaker -> speaker.id().equals(speakerId))) {
      throw new NoSuchElementException(speakerId);
    }
    var speakers =
        transcript.speakers().stream()
            .map(
                speaker ->
                    speaker.id().equals(speakerId)
                        ? new Transcript.Speaker(speakerId, displayName)
                        : speaker)
            .collect(java.util.stream.Collectors.toList());
    save(new Transcript(transcript.recording(), speakers, transcript.utterances()));
  }
}

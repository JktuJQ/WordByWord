package dev.wordbyword.app.processing;

import dev.wordbyword.app.core.audio.AudioPreparer;
import dev.wordbyword.app.core.processing.ProcessingContext;
import dev.wordbyword.app.core.processing.ProcessingException;
import dev.wordbyword.app.core.processing.TranscriptionPipeline;
import dev.wordbyword.app.core.recognition.SpeechRecognizer;
import dev.wordbyword.app.core.recording.Recording;
import dev.wordbyword.app.core.transcript.Transcript;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.stream.Collectors;

public final class LocalTranscriptionPipeline implements TranscriptionPipeline {
  private final AudioPreparer audioPreparer;
  private final SpeechRecognizer recognizer;
  private final TranscriptionPipeline demo;

  public LocalTranscriptionPipeline(
      AudioPreparer audioPreparer, SpeechRecognizer recognizer, TranscriptionPipeline demo) {
    this.audioPreparer = audioPreparer;
    this.recognizer = recognizer;
    this.demo = demo;
  }

  @Override
  public Transcript transcribe(Recording recording, ProcessingContext context)
      throws ProcessingException {
    if ("demo".equals(recording.audioUri().getScheme())) return demo.transcribe(recording, context);
    context.throwIfCancelled();
    try (var audio = audioPreparer.prepare(recording.audioUri(), context)) {
      var result = recognizer.recognize(audio, context);
      context.throwIfCancelled();
      var duration =
          audio.sampleCount().isPresent()
              ? OptionalLong.of(audio.sampleCount().getAsLong() * 1000 / 16000)
              : recording.durationMs();
      var savedRecording =
          new Recording(recording.id(), recording.audioUri(), recording.title(), duration);
      var utterances =
          result.segments().stream()
              .map(
                  s -> new Transcript.Utterance(Optional.empty(), s.text(), s.startMs(), s.endMs()))
              .collect(Collectors.toList());
      return new Transcript(savedRecording, List.of(), utterances);
    } catch (IOException exception) {
      throw new ProcessingException("Не удалось освободить временное аудио", exception);
    }
  }
}

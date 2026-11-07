package dev.wordbyword.app.processing;

import dev.wordbyword.app.core.audio.AudioPreparer;
import dev.wordbyword.app.core.audio.PcmAudio;
import dev.wordbyword.app.core.diarization.SpeakerDiarizer;
import dev.wordbyword.app.core.processing.ProcessingContext;
import dev.wordbyword.app.core.processing.ProcessingException;
import dev.wordbyword.app.core.processing.TranscriptionPipeline;
import dev.wordbyword.app.core.recognition.SpeechRecognizer;
import dev.wordbyword.app.core.recording.Recording;
import dev.wordbyword.app.core.transcript.Transcript;
import dev.wordbyword.app.core.transcript.TranscriptAssembler;
import java.io.IOException;
import java.util.OptionalDouble;
import java.util.OptionalLong;

public final class LocalTranscriptionPipeline implements TranscriptionPipeline {
  private final AudioPreparer audioPreparer;
  private final SpeechRecognizer recognizer;
  private final SpeakerDiarizer diarizer;
  private final TranscriptAssembler assembler;

  public LocalTranscriptionPipeline(
      AudioPreparer audioPreparer,
      SpeechRecognizer recognizer,
      SpeakerDiarizer diarizer,
      TranscriptAssembler assembler) {
    this.audioPreparer = audioPreparer;
    this.recognizer = recognizer;
    this.diarizer = diarizer;
    this.assembler = assembler;
  }

  @Override
  public Transcript transcribe(Recording recording, ProcessingContext context)
      throws ProcessingException {
    context.throwIfCancelled();
    context.reportProgress("Подготавливаем аудио", OptionalDouble.empty());
    try (var audio = audioPreparer.prepare(recording.audioUri(), context)) {
      context.throwIfCancelled();
      var recognition = recognizer.transcribe(audio, context);
      context.throwIfCancelled();
      context.reportProgress("Подготавливаем диаризацию", OptionalDouble.empty());
      var diarization = diarizer.diarize(audio, context);
      context.throwIfCancelled();
      var duration =
          audio.sampleCount().isPresent()
              ? OptionalLong.of(audio.sampleCount().getAsLong() * 1000 / PcmAudio.SAMPLE_RATE_HZ)
              : recording.durationMs();
      var completedRecording =
          new Recording(recording.id(), recording.audioUri(), recording.title(), duration);
      context.reportProgress("Собираем стенограмму", OptionalDouble.empty());
      var result = assembler.assemble(completedRecording, recognition, diarization, context);
      context.throwIfCancelled();
      return result;
    } catch (IOException exception) {
      throw new ProcessingException("Не удалось освободить временное аудио", exception);
    }
  }
}

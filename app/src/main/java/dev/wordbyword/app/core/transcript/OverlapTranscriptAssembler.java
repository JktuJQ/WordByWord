package dev.wordbyword.app.core.transcript;

import dev.wordbyword.app.core.diarization.SpeakerDiarizer;
import dev.wordbyword.app.core.processing.ProcessingContext;
import dev.wordbyword.app.core.processing.ProcessingException;
import dev.wordbyword.app.core.recognition.RecognitionResult;
import dev.wordbyword.app.core.recording.Recording;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class OverlapTranscriptAssembler implements TranscriptAssembler {
  private static final long MAX_GAP_MS = 2000;

  @Override
  public Transcript assemble(
      Recording recording,
      RecognitionResult recognition,
      List<SpeakerDiarizer.Segment> diarization,
      ProcessingContext context)
      throws ProcessingException {
    context.throwIfCancelled();

    List<RecognitionResult.Segment> asrSegments = recognition.segments();
    if (asrSegments.isEmpty()) {
      if (recognition.text().isBlank()) {
        return new Transcript(recording, List.of(), List.of());
      }
      long endMs = recording.durationMs().orElse(0L);
      var utterance = new Transcript.Utterance(Optional.empty(), recognition.text(), 0L, endMs);
      return new Transcript(recording, List.of(), List.of(utterance));
    }

    List<Transcript.Utterance> utterances = new ArrayList<>();
    Optional<String> currentSpeaker = null;
    StringBuilder currentText = null;
    long currentStart = 0;
    long currentEnd = 0;

    for (RecognitionResult.Segment segment : asrSegments) {
      Optional<String> speaker = assignSpeaker(segment, diarization);
      boolean sameKnownSpeaker =
          currentSpeaker != null && currentSpeaker.isPresent() && currentSpeaker.equals(speaker);
      boolean withinGap = sameKnownSpeaker && segment.startMs() - currentEnd <= MAX_GAP_MS;

      if (withinGap) {
        currentText.append(' ').append(segment.text());
        currentEnd = segment.endMs();
      } else {
        if (currentSpeaker != null) {
          utterances.add(
              new Transcript.Utterance(
                  currentSpeaker, currentText.toString(), currentStart, currentEnd));
        }
        currentSpeaker = speaker;
        currentText = new StringBuilder(segment.text());
        currentStart = segment.startMs();
        currentEnd = segment.endMs();
      }
    }
    utterances.add(
        new Transcript.Utterance(currentSpeaker, currentText.toString(), currentStart, currentEnd));

    return new Transcript(recording, buildSpeakers(utterances), utterances);
  }

  private static Optional<String> assignSpeaker(
      RecognitionResult.Segment segment, List<SpeakerDiarizer.Segment> diarization) {
    SpeakerDiarizer.Segment bestOverlap = null;
    long bestOverlapMs = 0;
    SpeakerDiarizer.Segment nearest = null;
    long nearestDistanceMs = Long.MAX_VALUE;

    for (SpeakerDiarizer.Segment candidate : diarization) {
      long overlap =
          overlapMs(segment.startMs(), segment.endMs(), candidate.startMs(), candidate.endMs());
      if (overlap > bestOverlapMs
          || (overlap == bestOverlapMs
              && overlap > 0
              && candidate.startMs() < bestOverlap.startMs())) {
        bestOverlap = candidate;
        bestOverlapMs = overlap;
      }
      long distance =
          distanceMs(segment.startMs(), segment.endMs(), candidate.startMs(), candidate.endMs());
      if (distance < nearestDistanceMs) {
        nearest = candidate;
        nearestDistanceMs = distance;
      }
    }

    if (bestOverlap != null) {
      return Optional.of(bestOverlap.speakerId());
    }
    return nearest == null ? Optional.empty() : Optional.of(nearest.speakerId());
  }

  private static long overlapMs(long aStart, long aEnd, long bStart, long bEnd) {
    return Math.max(0, Math.min(aEnd, bEnd) - Math.max(aStart, bStart));
  }

  private static long distanceMs(long aStart, long aEnd, long bStart, long bEnd) {
    if (aEnd <= bStart) {
      return bStart - aEnd;
    }
    if (bEnd <= aStart) {
      return aStart - bEnd;
    }
    return 0;
  }

  private static List<Transcript.Speaker> buildSpeakers(List<Transcript.Utterance> utterances) {
    List<Transcript.Speaker> speakers = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    int index = 1;
    for (Transcript.Utterance utterance : utterances) {
      if (utterance.speakerId().isPresent() && seen.add(utterance.speakerId().get())) {
        speakers.add(new Transcript.Speaker(utterance.speakerId().get(), "Участник " + index));
        index++;
      }
    }
    return speakers;
  }
}

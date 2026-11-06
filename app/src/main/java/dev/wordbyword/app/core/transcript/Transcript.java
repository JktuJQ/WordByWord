package dev.wordbyword.app.core.transcript;

import dev.wordbyword.app.core.recording.Recording;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.stream.Collectors;

public record Transcript(Recording recording, List<Speaker> speakers, List<Utterance> utterances) {
  public Transcript {
    speakers = List.copyOf(speakers);
    utterances = List.copyOf(utterances);
  }

  public Transcript withSpeakerName(String speakerId, String displayName) {
    if (displayName == null || displayName.isBlank())
      throw new IllegalArgumentException("Empty name");
    if (speakers.stream().noneMatch(speaker -> speaker.id().equals(speakerId)))
      throw new NoSuchElementException(speakerId);
    var renamed =
        speakers.stream()
            .map(
                speaker ->
                    speaker.id().equals(speakerId)
                        ? new Speaker(speakerId, displayName.trim())
                        : speaker)
            .collect(Collectors.toList());
    return new Transcript(recording, renamed, utterances);
  }

  public record Speaker(String id, String displayName) {}

  public record Utterance(Optional<String> speakerId, String text, long startMs, long endMs) {}
}

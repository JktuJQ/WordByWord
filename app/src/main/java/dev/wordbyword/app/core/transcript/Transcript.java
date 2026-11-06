package dev.wordbyword.app.core.transcript;

import dev.wordbyword.app.core.recording.Recording;
import java.util.List;
import java.util.Optional;

public record Transcript(Recording recording, List<Speaker> speakers, List<Utterance> utterances) {
  public Transcript {
    speakers = List.copyOf(speakers);
    utterances = List.copyOf(utterances);
  }

  public record Speaker(String id, String displayName) {}

  public record Utterance(Optional<String> speakerId, String text, long startMs, long endMs) {}
}

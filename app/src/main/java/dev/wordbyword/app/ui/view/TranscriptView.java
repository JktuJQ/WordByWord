package dev.wordbyword.app.ui.view;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import dev.wordbyword.app.R;
import dev.wordbyword.app.core.transcript.Transcript;
import java.util.HashMap;
import java.util.Locale;

public final class TranscriptView {
  private final View root;
  private final LinearLayout items;

  public TranscriptView(View root, LinearLayout items) {
    this.root = root;
    this.items = items;
  }

  public void render(Transcript transcript) {
    text(R.id.screen_title, transcript.recording().title());
    text(
        R.id.screen_description,
        "demo".equals(transcript.recording().audioUri().getScheme())
            ? items.getContext().getString(R.string.demo_transcript)
            : items
                .getContext()
                .getString(
                    R.string.recording_summary,
                    transcript.utterances().size(),
                    transcript.speakers().size()));
    if (transcript.utterances().isEmpty()) {
      var empty = new TextView(items.getContext());
      empty.setText(R.string.transcript_empty);
      items.addView(empty);
    }
    var speakers = new HashMap<String, Transcript.Speaker>();
    var colors = new HashMap<String, Integer>();
    for (int index = 0; index < transcript.speakers().size(); index++) {
      var speaker = transcript.speakers().get(index);
      speakers.put(speaker.id(), speaker);
      colors.put(speaker.id(), index);
    }
    for (var utterance : transcript.utterances()) {
      View card =
          LayoutInflater.from(items.getContext()).inflate(R.layout.item_utterance, items, false);
      var participant = utterance.speakerId().map(speakers::get).orElse(null);
      TextView speaker = card.findViewById(R.id.speaker);
      speaker.setText(
          participant == null
              ? items.getContext().getString(R.string.unknown_speaker)
              : participant.displayName());
      int index = utterance.speakerId().map(id -> colors.getOrDefault(id, 0)).orElse(0);
      speaker.setTextColor(
          items.getContext().getColor(index % 2 == 0 ? R.color.accent : R.color.secondary_speaker));
      ((TextView) card.findViewById(R.id.timing))
          .setText(
              items
                  .getContext()
                  .getString(
                      R.string.time_range, time(utterance.startMs()), time(utterance.endMs())));
      ((TextView) card.findViewById(R.id.utterance_text)).setText(utterance.text());
      items.addView(card);
    }
  }

  private static String time(long milliseconds) {
    long seconds = milliseconds / 1000;
    return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
  }

  private void text(int id, String value) {
    ((TextView) root.findViewById(id)).setText(value);
  }
}

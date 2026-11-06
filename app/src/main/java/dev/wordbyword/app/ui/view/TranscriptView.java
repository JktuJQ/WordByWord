package dev.wordbyword.app.ui.view;

import android.app.AlertDialog;
import android.content.Context;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import dev.wordbyword.app.R;
import dev.wordbyword.app.core.transcript.Transcript;
import dev.wordbyword.app.ui.AppController;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Locale;

public final class TranscriptView {
  private final View root;
  private final LinearLayout items;
  private final AppController controller;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final ArrayList<CardRange> cards = new ArrayList<>();
  private final ScrollView scroll;
  private MediaPlayer player;
  private String playerRecordingId;
  private int activeCard = -1;
  private TextView positionLabel;
  private TextView playbackToggle;
  private SeekBar positionSeekbar;
  private Transcript currentTranscript;
  private int durationMs;
  private final Runnable positionTicker =
      new Runnable() {
        @Override
        public void run() {
          updatePlaybackUi();
          if (player != null && player.isPlaying()) handler.postDelayed(this, 200);
        }
      };

  private record CardRange(long startMs, long endMs, View card) {}

  public TranscriptView(View root, LinearLayout items, AppController controller) {
    this.root = root;
    this.items = items;
    this.controller = controller;
    this.scroll = root.findViewById(R.id.content_scroll);
    positionLabel = root.findViewById(R.id.playback_position);
    playbackToggle = root.findViewById(R.id.playback_toggle);
    positionSeekbar = root.findViewById(R.id.playback_seekbar);
    playbackToggle.setOnClickListener(view -> togglePlayback());
    root.findViewById(R.id.playback_back).setOnClickListener(view -> seek(-5000));
    root.findViewById(R.id.playback_forward).setOnClickListener(view -> seek(5000));
  }

  public void release() {
    releasePlayer();
    cards.clear();
    currentTranscript = null;
    activeCard = -1;
  }

  private void releasePlayer() {
    handler.removeCallbacks(positionTicker);
    if (player != null) {
      player.release();
      player = null;
    }
    playerRecordingId = null;
    durationMs = 0;
  }

  public void render(Transcript transcript) {
    if (playerRecordingId != null && !playerRecordingId.equals(transcript.recording().id()))
      release();
    currentTranscript = transcript;
    text(R.id.screen_title, transcript.recording().title());
    text(
        R.id.screen_description,
        items
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
    cards.clear();
    activeCard = -1;
    durationMs =
        player != null
            ? player.getDuration()
            : (int) Math.min(Integer.MAX_VALUE, transcript.recording().durationMs().orElse(0));
    positionSeekbar.setMax(Math.max(1, durationMs));
    positionSeekbar.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          @Override
          public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
            if (fromUser && preparePlayer(transcript)) seekTo(progress);
          }

          @Override
          public void onStartTrackingTouch(SeekBar bar) {}

          @Override
          public void onStopTrackingTouch(SeekBar bar) {}
        });
    var speakers = new HashMap<String, Transcript.Speaker>();
    var colors = new HashMap<String, Integer>();
    for (int index = 0; index < transcript.speakers().size(); index++) {
      var speaker = transcript.speakers().get(index);
      speakers.put(speaker.id(), speaker);
      colors.put(speaker.id(), index);
    }
    LinearLayout speakerRow = new LinearLayout(items.getContext());
    speakerRow.setOrientation(LinearLayout.HORIZONTAL);
    speakerRow.setPadding(0, 18, 0, 6);
    for (var speaker : transcript.speakers()) {
      TextView chip = new TextView(items.getContext());
      chip.setText(speaker.displayName() + " ✎");
      chip.setTextColor(items.getContext().getColor(R.color.accent));
      chip.setBackgroundResource(R.drawable.notice);
      chip.setPadding(20, 12, 20, 12);
      LinearLayout.LayoutParams params =
          new LinearLayout.LayoutParams(
              LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
      params.setMargins(0, 0, 12, 0);
      speakerRow.addView(chip, params);
      chip.setOnClickListener(view -> rename(speaker));
    }
    items.addView(speakerRow);
    for (var utterance : transcript.utterances()) {
      View card =
          LayoutInflater.from(items.getContext()).inflate(R.layout.item_utterance, items, false);
      var participant = utterance.speakerId().map(speakers::get).orElse(null);
      TextView speaker = card.findViewById(R.id.speaker);
      speaker.setText(
          participant == null
              ? items.getContext().getString(R.string.unknown_speaker)
              : participant.displayName());
      if (participant != null) speaker.setOnClickListener(view -> rename(participant));
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
      card.setOnClickListener(view -> playFrom(transcript, utterance.startMs()));
      cards.add(new CardRange(utterance.startMs(), utterance.endMs(), card));
      items.addView(card);
    }
    updatePlaybackUi();
  }

  private void rename(Transcript.Speaker speaker) {
    Context context = items.getContext();
    EditText input = new EditText(context);
    input.setSingleLine(true);
    input.setText(speaker.displayName());
    input.setSelectAllOnFocus(true);
    LinearLayout holder = new LinearLayout(context);
    holder.setPadding(40, 0, 40, 0);
    holder.addView(input);
    new AlertDialog.Builder(context)
        .setTitle(R.string.rename_participant)
        .setView(holder)
        .setNegativeButton(R.string.not_now, null)
        .setPositiveButton(
            R.string.save_name,
            (dialog, which) -> {
              String name = input.getText().toString().trim();
              if (!name.isEmpty()) controller.renameSpeaker(speaker.id(), name);
            })
        .show();
  }

  private boolean preparePlayer(Transcript transcript) {
    if (player != null && transcript.recording().id().equals(playerRecordingId)) return true;
    releasePlayer();
    try {
      player =
          MediaPlayer.create(
              items.getContext(), Uri.parse(transcript.recording().audioUri().toString()));
      if (player == null) throw new IllegalStateException();
      playerRecordingId = transcript.recording().id();
      durationMs = player.getDuration();
      player.setOnCompletionListener(
          finished -> {
            handler.removeCallbacks(positionTicker);
            updatePlaybackUi();
          });
      updatePlaybackUi();
      return true;
    } catch (Exception exception) {
      releasePlayer();
      controller.notifyMessage(items.getContext().getString(R.string.audio_unavailable));
      return false;
    }
  }

  private void togglePlayback() {
    if (currentTranscript == null || !preparePlayer(currentTranscript)) return;
    if (player.isPlaying()) {
      player.pause();
      handler.removeCallbacks(positionTicker);
      updatePlaybackUi();
    } else {
      if (player.getCurrentPosition() >= durationMs) seekTo(0);
      player.start();
      handler.removeCallbacks(positionTicker);
      handler.post(positionTicker);
      updatePlaybackUi();
    }
  }

  private void playFrom(Transcript transcript, long startMs) {
    if (!preparePlayer(transcript)) return;
    seekTo(startMs);
    player.start();
    handler.removeCallbacks(positionTicker);
    handler.post(positionTicker);
    updatePlaybackUi();
  }

  private void seek(long offsetMs) {
    if (currentTranscript == null || !preparePlayer(currentTranscript)) return;
    long current = player.getCurrentPosition();
    long target = Math.max(0, Math.min(player.getDuration(), current + offsetMs));
    seekTo(target);
    updatePlaybackUi();
  }

  private void seekTo(long targetMs) {
    if (player == null) return;
    long bounded = Math.max(0, Math.min(durationMs, targetMs));
    player.seekTo(bounded, MediaPlayer.SEEK_CLOSEST);
  }

  private void updatePlaybackUi() {
    if (positionLabel == null || positionSeekbar == null) return;
    int position = player == null ? 0 : Math.max(0, player.getCurrentPosition());
    positionLabel.setText(
        items
            .getContext()
            .getString(R.string.playback_position_format, time(position), time(durationMs)));
    positionSeekbar.setMax(Math.max(1, durationMs));
    positionSeekbar.setProgress(Math.min(position, durationMs));
    boolean playing = player != null && player.isPlaying();
    int newActive = -1;
    for (int index = 0; index < cards.size(); index++) {
      CardRange range = cards.get(index);
      boolean active = playing && position >= range.startMs() && position < range.endMs();
      range.card().findViewById(R.id.utterance_text).setBackgroundResource(
          active ? R.drawable.active_utterance : R.drawable.utterance_bubble);
      if (active) newActive = index;
    }
    playbackToggle.setText(playing ? "Ⅱ" : "▶");
    playbackToggle.setContentDescription(
        items.getContext().getString(playing ? R.string.pause_utterance : R.string.play_utterance));
    if (newActive != activeCard) {
      activeCard = newActive;
      if (activeCard >= 0) {
        View card = cards.get(activeCard).card();
        scroll.smoothScrollTo(0, Math.max(0, items.getTop() + card.getTop() - scroll.getHeight() / 3));
      }
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

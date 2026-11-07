package dev.wordbyword.app.core.diarization;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SherpaOnnxSpeakerDiarizerTest {
  @Test
  public void roundsSecondsToNearestMillisecond() {
    SpeakerDiarizer.Segment segment = SherpaOnnxSpeakerDiarizer.toSegment(1.2504f, 4.7686f, 0);

    assertEquals(1250, segment.startMs());
    assertEquals(4769, segment.endMs());
  }

  @Test
  public void zeroDurationSegmentHasEndNotBeforeStart() {
    SpeakerDiarizer.Segment segment = SherpaOnnxSpeakerDiarizer.toSegment(2.0005f, 2.0005f, 0);

    assertEquals(segment.startMs(), segment.endMs());
  }

  @Test
  public void speakerIdIsStableAndZeroBased() {
    assertEquals("speaker_0", SherpaOnnxSpeakerDiarizer.speakerId(0));
    assertEquals("speaker_2", SherpaOnnxSpeakerDiarizer.speakerId(2));
  }

  @Test
  public void toSegmentUsesSpeakerIdFormat() {
    SpeakerDiarizer.Segment segment = SherpaOnnxSpeakerDiarizer.toSegment(0f, 1f, 3);

    assertEquals("speaker_3", segment.speakerId());
  }
}

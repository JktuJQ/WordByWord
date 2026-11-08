package dev.wordbyword.app.evaluation;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.wordbyword.app.core.audio.AndroidAudioPreparer;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class FilteredDialogsHypothesisTest {
  @Test
  public void processAllDialogs() throws Exception {
    var targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
    DialogsHypothesisTest.run(
        new AndroidAudioPreparer(targetContext, true),
        "dialogs-filtered-asr-hypotheses.json",
        "-filtered");
  }
}

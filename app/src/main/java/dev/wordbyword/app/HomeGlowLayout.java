package dev.wordbyword.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.LinearLayout;

public final class HomeGlowLayout extends LinearLayout {
  private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Shader orange = new RadialGradient(0, 0, 1,
      new int[] {0xFFFF6A00, 0xEBFF8300, 0x65FFAC24, 0x00FFAC24},
      new float[] {0, 0.28f, 0.63f, 1}, Shader.TileMode.CLAMP);
  private final Shader yellow = new RadialGradient(0, 0, 1,
      new int[] {0xFFFFE64A, 0xE6FFD52A, 0x60FFD940, 0x00FFD940},
      new float[] {0, 0.3f, 0.66f, 1}, Shader.TileMode.CLAMP);
  private final Shader amber = new RadialGradient(0, 0, 1,
      new int[] {0xD9FF8A00, 0x70FFB21C, 0x00FFB21C},
      new float[] {0, 0.4f, 1}, Shader.TileMode.CLAMP);
  private ValueAnimator animator;
  private boolean homeVisible = true;
  private float phase;
  private boolean voiceMode;
  private float voiceTarget;
  private float voiceLevel;

  public HomeGlowLayout(Context context, AttributeSet attributes) {
    super(context, attributes);
    setWillNotDraw(false);
  }

  public void setVoiceMode(boolean enabled) {
    voiceMode = enabled;
    invalidate();
  }

  public void setVoiceLevel(float level) {
    voiceTarget = Math.max(0, Math.min(1, level));
    invalidate();
  }

  public void setHomeVisible(boolean visible) {
    if (homeVisible == visible) return;
    homeVisible = visible;
    updateAnimation();
    invalidate();
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    if (!homeVisible) return;
    canvas.drawColor(voiceMode ? 0xFFFFFFFF : 0xFFFCFBF8);
    voiceLevel += (voiceTarget - voiceLevel) * 0.14f;
    if (voiceLevel < 0.003f && voiceTarget == 0) voiceLevel = 0;
    glow.setAlpha(voiceMode ? Math.round(voiceLevel * 230) : 255);
    if (voiceMode && voiceLevel == 0) return;
    if (voiceMode) {
      float radius = getWidth() * (0.65f + voiceLevel * 0.4f);
      float centerY = getHeight() * 0.4f;
      float movement = (float) Math.sin(phase * 2) * getWidth() * 0.1f * voiceLevel;
      drawGlow(canvas, orange, getWidth() * 0.4f + movement, centerY, radius, radius);
      drawGlow(canvas, yellow, getWidth() * 0.65f - movement, centerY - radius * 0.15f,
          radius * 0.85f, radius * 0.85f);
      return;
    }
    float width = getWidth();
    float height = getHeight();
    float drift = (float) Math.sin(phase);
    float sway = (float) Math.cos(phase);
    drawGlow(canvas, orange, width * (0.26f + 0.28f * drift),
        height * (0.45f + 0.16f * sway), width * 0.78f, height * 0.29f);
    drawGlow(canvas, yellow, width * (0.73f + 0.3f * sway),
        height * (0.38f + 0.2f * drift), width * 0.72f, height * 0.28f);
    drawGlow(canvas, amber, width * (0.5f + 0.08f * drift),
        height * 0.82f, width * 1.05f, height * 0.24f);
  }

  private void drawGlow(Canvas canvas, Shader shader, float x, float y, float rx, float ry) {
    int saved = canvas.save();
    canvas.translate(x, y);
    canvas.scale(rx, ry);
    glow.setShader(shader);
    canvas.drawCircle(0, 0, 1, glow);
    canvas.restoreToCount(saved);
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    updateAnimation();
  }

  @Override
  protected void onDetachedFromWindow() {
    stopAnimation();
    super.onDetachedFromWindow();
  }

  @Override
  protected void onWindowVisibilityChanged(int visibility) {
    super.onWindowVisibilityChanged(visibility);
    updateAnimation();
  }

  private void updateAnimation() {
    boolean enabled = homeVisible && isAttachedToWindow()
        && getWindowVisibility() == View.VISIBLE && ValueAnimator.areAnimatorsEnabled();
    if (!enabled) {
      stopAnimation();
      return;
    }
    if (animator != null) return;
    animator = ValueAnimator.ofFloat(phase, phase + (float) (Math.PI * 2));
    animator.setDuration(26000);
    animator.setRepeatCount(ValueAnimator.INFINITE);
    animator.setInterpolator(new LinearInterpolator());
    animator.addUpdateListener(animation -> {
      phase = (float) animation.getAnimatedValue();
      invalidate();
    });
    animator.start();
  }

  private void stopAnimation() {
    if (animator == null) return;
    animator.cancel();
    animator = null;
  }
}

package dev.wordbyword.app.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

public final class VoiceOrbView extends View {
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Path outline = new Path();
  private Shader glow;
  private Shader sphere;
  private float level;
  private float phase;
  private float wavePhase;
  private float target;
  private long lastFrame;

  public VoiceOrbView(Context context, AttributeSet attributes) {
    super(context, attributes);
  }

  public void setLevel(float value) {
    target = Math.max(0f, Math.min(1f, value));
    invalidate();
  }

  @Override
  protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
    float cx = width / 2f;
    float cy = height / 2f;
    float radius = Math.min(cx, cy) * 0.76f;
    glow =
        new RadialGradient(
            cx, cy, radius * 1.3f, new int[] {0x45FF8A27, 0x00FF8A27}, null, Shader.TileMode.CLAMP);
    sphere =
        new RadialGradient(
            cx - radius * 0.35f,
            cy - radius * 0.45f,
            radius * 1.75f,
            new int[] {Color.rgb(255, 238, 180), Color.rgb(255, 170, 36), Color.rgb(255, 153, 0)},
            new float[] {0f, 0.42f, 1f},
            Shader.TileMode.CLAMP);
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    long now = android.os.SystemClock.uptimeMillis();
    float elapsed = lastFrame == 0 ? 0.016f : Math.min(0.05f, (now - lastFrame) / 1000f);
    lastFrame = now;
    level += (target - level) * (1 - (float) Math.exp(-elapsed * 12));
    if (level < 0.003f && target == 0) level = 0;
    phase += elapsed * (1.5f + level * 5);
    wavePhase = (wavePhase + elapsed * (0.5f + level * 0.5f)) % 1f;
    float cx = getWidth() / 2f;
    float cy = getHeight() / 2f;
    float radius = Math.min(cx, cy) * (0.62f + 0.035f * level);
    paint.setShader(glow);
    paint.setAlpha(Math.round(255 * level));
    canvas.drawCircle(cx, cy, radius * 1.3f, paint);
    paint.setShader(null);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(getResources().getDisplayMetrics().density * 1.5f);
    paint.setColor(Color.rgb(255, 180, 20));
    for (int ring = 0; ring < 3; ring++) {
      float progress = (wavePhase + ring / 3f) % 1f;
      paint.setAlpha(Math.round(110 * level * (1 - progress) * (1 - progress)));
      canvas.drawCircle(cx, cy, radius * (1.02f + progress * 0.46f), paint);
    }
    paint.setStyle(Paint.Style.FILL);
    paint.setAlpha(255);
    outline.reset();
    for (int i = 0; i <= 120; i++) {
      double angle = i * Math.PI * 2 / 120;
      float deformation =
          level
              * (0.29f * (float) Math.sin(angle * 3 + phase)
                  + 0.12f * (float) Math.cos(angle * 5 - phase * 0.7f));
      float r = radius * (1 + deformation);
      float x = cx + r * (float) Math.cos(angle);
      float y = cy + r * (float) Math.sin(angle);
      if (i == 0) outline.moveTo(x, y);
      else outline.lineTo(x, y);
    }
    outline.close();
    float hue = 51f - 22f * level + 2f * (float) Math.sin(now / 4500.0);
    sphere =
        new RadialGradient(
            cx - radius * 0.35f,
            cy - radius * 0.45f,
            radius * 1.75f,
            new int[] {
              Color.HSVToColor(new float[] {Math.min(60f, hue + 10), 0.45f, 1f}),
              Color.HSVToColor(new float[] {hue, 0.85f, 1f}),
              Color.HSVToColor(new float[] {hue - 5, 0.98f, 1f})
            },
            new float[] {0f, 0.42f, 1f},
            Shader.TileMode.CLAMP);
    paint.setShader(sphere);
    canvas.drawPath(outline, paint);
    paint.setShader(null);
    if (isShown()) postInvalidateOnAnimation();
  }
}

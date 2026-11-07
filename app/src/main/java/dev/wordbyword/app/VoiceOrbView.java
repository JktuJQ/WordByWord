package dev.wordbyword.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

public final class VoiceOrbView extends View {
  private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
  private final Paint orbPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
  private final Path outline = new Path();
  private final Bitmap artwork;
  private final BitmapShader orbShader;
  private float level;
  private float phase;

  public VoiceOrbView(Context context, AttributeSet attributes) {
    super(context, attributes);
    artwork = BitmapFactory.decodeResource(getResources(), R.drawable.voice_orb_reference);
    orbShader = new BitmapShader(artwork, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
    orbPaint.setShader(orbShader);
    glowPaint.setAlpha(125);
  }

  public void setLevel(float value) {
    level = level * 0.7f + Math.max(0f, Math.min(1f, value)) * 0.3f;
    phase += 0.12f;
    invalidate();
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    float width = getWidth();
    float height = getHeight();
    float scale = Math.min(width / artwork.getWidth(), height / artwork.getHeight());
    Matrix matrix = new Matrix();
    matrix.setScale(scale, scale);
    matrix.postTranslate((width - artwork.getWidth() * scale) / 2f,
        (height - artwork.getHeight() * scale) / 2f);
    canvas.drawBitmap(artwork, matrix, glowPaint);
    orbShader.setLocalMatrix(matrix);
    float cx = width / 2f;
    float cy = height / 2f;
    float radius = Math.min(width, height) * (0.385f + level * 0.035f);
    outline.reset();
    for (int i = 0; i <= 96; i++) {
      double angle = i * Math.PI * 2 / 96;
      double ripple = Math.sin(angle * 5 + phase) * 0.07 + Math.sin(angle * 8 - phase * 0.7) * 0.025;
      float r = radius * (1f + (float) ripple * level);
      float x = cx + r * (float) Math.cos(angle);
      float y = cy + r * (float) Math.sin(angle);
      if (i == 0) outline.moveTo(x, y);
      else outline.lineTo(x, y);
    }
    outline.close();
    canvas.drawPath(outline, orbPaint);
  }
}

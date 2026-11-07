package dev.wordbyword.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.widget.LinearLayout;

public final class GlassPanelLayout extends LinearLayout {
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF ellipse = new RectF();
  private Shader fill;

  public GlassPanelLayout(Context context, AttributeSet attrs) {
    super(context, attrs);
    setWillNotDraw(false);
  }

  @Override
  protected void onMeasure(int widthSpec, int heightSpec) {
    super.onMeasure(widthSpec, heightSpec);
    int size = Math.max(getMeasuredWidth(), getMeasuredHeight());
    super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY));
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    float density = getResources().getDisplayMetrics().density;
    float diameter = Math.min(w, h) - density * 2;
    ellipse.set((w - diameter) / 2, (h - diameter) / 2, (w + diameter) / 2, (h + diameter) / 2);
    fill =
        new LinearGradient(
            0,
            0,
            w,
            h,
            new int[] {0xBFFFFFFF, 0x50FFFFFF, 0x9CFFFFFF},
            null,
            Shader.TileMode.CLAMP);
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    paint.setStyle(Paint.Style.FILL);
    paint.setShader(fill);
    canvas.drawOval(ellipse, paint);
  }
}

package dev.wordbyword.app.ui.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.LinearLayout;
import dev.wordbyword.app.R;

public final class GlassNavigationLayout extends LinearLayout {
  private final Drawable glass;
  private ValueAnimator animator;
  private int selected;
  private float position;
  private float expansion = 1;

  public GlassNavigationLayout(Context context, AttributeSet attrs) {
    super(context, attrs);
    glass = context.getDrawable(R.drawable.home_liquid_glass);
    setWillNotDraw(false);
  }

  public void select(int index) {
    if (index == selected) return;
    selected = index;
    if (animator != null) animator.cancel();
    if (!ValueAnimator.areAnimatorsEnabled()) {
      position = index;
      expansion = 1;
      invalidate();
      return;
    }
    float from = position;
    animator = ValueAnimator.ofFloat(0, 1);
    animator.setDuration(480);
    animator.setInterpolator(new AccelerateDecelerateInterpolator());
    animator.addUpdateListener(
        value -> {
          float t = (float) value.getAnimatedValue();
          float travel = Math.max(0, (t - 0.15f) / 0.85f);
          position = from + (index - from) * travel;
          expansion = 1 + 0.07f * (float) Math.sin(Math.PI * t);
          invalidate();
        });
    animator.start();
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    if (getChildCount() < 2) return;
    View first = getChildAt(0);
    View second = getChildAt(1);
    float left = first.getLeft() + (second.getLeft() - first.getLeft()) * position;
    float width = first.getWidth();
    float extra = width * (expansion - 1) / 2;
    glass.setBounds(
        Math.round(left - extra),
        first.getTop(),
        Math.round(left + width + extra),
        first.getBottom());
    glass.draw(canvas);
  }

  @Override
  protected void onDetachedFromWindow() {
    if (animator != null) animator.cancel();
    position = selected;
    expansion = 1;
    super.onDetachedFromWindow();
  }
}

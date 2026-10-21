package com.gathin.tvhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.animation.LinearInterpolator;
import android.widget.TextView;

/**
 * A name too long for its tile, read in full without a marquee running all the time: it shows truncated with an
 * ellipsis, and only after a focused second and a half does it slide once to its end, rest there a second, then
 * fade out and back in at its start, truncated again, rather than jump there. Android's marquee drops the ellipsis while it waits to start and loops; this does neither.
 * The slide moves the text's scroll offset, so it redraws only while it moves and lays out only at both ends.
 */
final class NameScroller {
    private static final long DELAY_MS = 1500, HOLD_MS = 1000;
    /** Reading speed, in dp per second. */
    private static final float SPEED_DP = 42f;

    private NameScroller() { }

    /** Starts the wait, then the slide, for a name that has just been focused. */
    static void start(TextView t) {
        stop(t);
        if (t.getVisibility() != android.view.View.VISIBLE) return;   // the dock's hidden names
        Runnable go = () -> slide(t);
        t.setTag(R.id.name_scroller, go);
        t.postDelayed(go, DELAY_MS);
    }

    /** Stops any wait or slide and shows the name truncated again. */
    static void stop(TextView t) {
        Object o = t.getTag(R.id.name_scroller);
        if (o instanceof Runnable) t.removeCallbacks((Runnable) o);
        if (o instanceof ValueAnimator) ((ValueAnimator) o).cancel();
        t.setTag(R.id.name_scroller, null);
        reset(t);
    }

    private static void slide(TextView t) {
        CharSequence text = t.getText();
        int avail = t.getWidth() - t.getPaddingLeft() - t.getPaddingRight();
        float full = t.getPaint().measureText(text, 0, text.length());
        if (avail <= 0 || full <= avail) return;
        int distance = (int) Math.ceil(full - avail);
        float density = t.getResources().getDisplayMetrics().density;
        t.setTag(R.id.name_gravity, t.getGravity());
        t.setEllipsize(null);
        t.setHorizontallyScrolling(true);
        t.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        ValueAnimator a = ValueAnimator.ofInt(0, distance);
        a.setDuration((long) (distance / (SPEED_DP * density) * 1000));
        a.setInterpolator(new LinearInterpolator());
        a.addUpdateListener(v -> t.setScrollX((int) v.getAnimatedValue()));
        a.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;
            @Override public void onAnimationCancel(Animator animation) { cancelled = true; }
            @Override public void onAnimationEnd(Animator animation) {
                if (cancelled) return;
                Runnable back = () -> {
                    float a0 = t.getAlpha();
                    t.animate().alpha(0f).setDuration(140).withEndAction(() -> {
                        stop(t);
                        t.animate().alpha(a0).setDuration(180).start();
                    }).start();
                };
                t.setTag(R.id.name_scroller, back);
                t.postDelayed(back, HOLD_MS);
            }
        });
        t.setTag(R.id.name_scroller, a);
        a.start();
    }

    private static void reset(TextView t) {
        if (t.getScrollX() != 0) t.setScrollX(0);
        Object g = t.getTag(R.id.name_gravity);
        if (g instanceof Integer) {
            t.setGravity((Integer) g);
            t.setTag(R.id.name_gravity, null);
        }
        // singleLine turns horizontal scrolling on, which lays a centred name out on a line a million pixels
        // wide: it sat far off to the right, so a name showed nothing until it slid. Off, the line is the
        // label's width and the ellipsis shows. Both calls do nothing when nothing changes.
        t.setHorizontallyScrolling(false);
        t.setEllipsize(TextUtils.TruncateAt.END);
    }
}

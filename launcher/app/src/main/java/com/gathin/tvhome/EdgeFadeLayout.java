package com.gathin.tvhome;

import android.content.Context;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.RecyclerView;

/**
 * The grid's frame. A row scrolling up under the header fades out as it goes, by the share of it still below the
 * edge, instead of being cut off on a straight line at full strength. Each fading row draws through a hardware
 * layer, which is rendered once and then only blended, so the fade costs no offscreen pass per frame. A gradient
 * mask over the edge looked the same but cost a render target switch every frame on the Mali-G52: row scrolls went
 * from 0.10% to 0.84% janky frames, 26 to 46 ms at the 99th percentile.
 *
 * The fade uses transition alpha, which multiplies with the alpha the reorder mode dims tiles with, so the two never
 * fight. Android 10 and later; earlier versions keep the plain edge.
 */
public final class EdgeFadeLayout extends FrameLayout {
    private final int band;
    /** The dock band drawn by DockDecoration, which is not a child and fades through it. */
    private DockDecoration dock;
    private final ViewTreeObserver.OnPreDrawListener tick = () -> {
        apply();
        return true;
    };

    public EdgeFadeLayout(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        band = Math.round(44 * ctx.getResources().getDisplayMetrics().density);
    }

    /** The room kept between the header and a focused row, in px. */
    int fadeLength() { return band; }

    void setDock(DockDecoration d) { dock = d; }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (Build.VERSION.SDK_INT >= 29) getViewTreeObserver().addOnPreDrawListener(tick);
    }

    @Override
    protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnPreDrawListener(tick);
        super.onDetachedFromWindow();
    }

    /** How much of a span from top to bottom (grid coordinates) shows: 1 until it reaches the edge, then its share. */
    static float visible(float top, float bottom) {
        float h = bottom - top;
        if (top >= 0 || h <= 0) return 1f;
        // Full while four fifths show, gone with under a third left, so the cut against the header is already faint.
        return Math.max(0f, Math.min(1f, (bottom - h * 0.3f) / (h * 0.5f)));
    }

    private void apply() {
        if (getChildCount() == 0 || !(getChildAt(0) instanceof RecyclerView)) return;
        RecyclerView rv = (RecyclerView) getChildAt(0);
        for (int i = 0; i < rv.getChildCount(); i++) {
            View c = rv.getChildAt(i);
            float top = c.getTop() + c.getTranslationY();
            float a = visible(top, top + c.getHeight());
            if (c.getTransitionAlpha() == a) continue;
            c.setTransitionAlpha(a);
            int type = a < 1f && a > 0f ? View.LAYER_TYPE_HARDWARE : View.LAYER_TYPE_NONE;
            if (c.getLayerType() != type) c.setLayerType(type, null);
        }
        if (dock != null && dock.fadeTo(rv)) rv.invalidate();   // the band draws in the grid's onDraw
    }
}

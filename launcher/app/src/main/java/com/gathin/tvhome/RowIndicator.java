package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * A slim scroll indicator on a channel row's title line, as iOS draws one: a faint capsule track and a brighter
 * thumb whose width is the share of the row on screen and whose place follows the scroll. It only shows when the
 * row has more programs than columns, so it says "there is more this way" without a card hanging off the edge.
 */
final class RowIndicator extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private float start, size = 1f;

    public RowIndicator(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        track.setColor(0x2EFFFFFF);
        thumb.setColor(0xB3FFFFFF);
    }

    /** The row scrolled to {@code offset} cells, {@code extent} of its {@code range} cards on screen. */
    void set(float offset, int extent, int range) {
        if (range <= 0 || extent >= range) {
            if (getVisibility() != GONE) setVisibility(GONE);
            return;
        }
        if (getVisibility() != VISIBLE) setVisibility(VISIBLE);
        float s = extent / (float) range;
        float st = offset / (float) range;
        if (s != size || st != start) {
            size = s;
            start = st;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        float h = getHeight(), w = getWidth(), r = h / 2f;
        rect.set(0, 0, w, h);
        c.drawRoundRect(rect, r, r, track);
        float tw = Math.max(h * 2, w * size);
        float x = Math.min(w - tw, (w - 0) * start);
        rect.set(x, 0, x + tw, h);
        c.drawRoundRect(rect, r, r, thumb);
    }
}

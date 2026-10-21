package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;

/**
 * Weather glyphs in the spirit of SF Symbols' filled set: solid shapes, round ends, one colour. Drawn from a few
 * circles and lines in a 24-unit box, so they stay sharp at any size and need no image files.
 */
final class WeatherIcon extends Drawable {
    static final int SUN = 0, MOON = 1, SUN_CLOUD = 2, MOON_CLOUD = 3, CLOUD = 4, RAIN = 5, STORM = 6, FOG = 7;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int size;
    private int kind;
    /** The filled part of the current glyph, built once per kind. */
    private Path shape;

    WeatherIcon(int sizePx, int color) {
        size = sizePx;
        fill.setColor(color);
        line.setColor(color);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        setBounds(0, 0, sizePx, sizePx);
    }

    void setKind(int k) {
        if (kind != k) {
            kind = k;
            shape = null;
            invalidateSelf();
        }
    }

    @Override public int getIntrinsicWidth() { return size; }
    @Override public int getIntrinsicHeight() { return size; }

    @Override
    public void draw(@NonNull Canvas c) {
        Rect b = getBounds();
        float u = b.width() / 24f;
        c.save();
        c.translate(b.left, b.top);
        c.scale(u, u);
        line.setStrokeWidth(1.9f);
        if (shape == null) shape = build();
        c.drawPath(shape, fill);
        if (kind == RAIN) {
            for (int i = 0; i < 3; i++) c.drawLine(8 + i * 4.2f, 18.2f, 6.8f + i * 4.2f, 21.6f, line);
        } else if (kind == FOG) {
            for (int i = 0; i < 4; i++) c.drawLine(4 + (i % 2) * 2, 6 + i * 4, 20 - ((i + 1) % 2) * 2, 6 + i * 4, line);
        }
        c.restore();
    }

    private Path build() {
        line.setStrokeWidth(1.9f);
        switch (kind) {
            case SUN: return sun(12, 12, 4.6f);
            case MOON: return moon(12, 12, 8f);
            case SUN_CLOUD: return behind(sun(15.5f, 8.5f, 3.4f), 1.5f, 7.5f, 0.82f);
            case MOON_CLOUD: return behind(moon(15.5f, 8f, 5.4f), 1.5f, 7.5f, 0.82f);
            case CLOUD: return cloud(1f, 3.5f, 1f, 0);
            case RAIN: return cloud(1f, 0.5f, 1f, 0);
            case STORM: {
                Path p = cloud(1f, 0.5f, 1f, 0), bolt = new Path();
                bolt.moveTo(13.4f, 14.6f); bolt.lineTo(9.6f, 19.4f); bolt.lineTo(12.2f, 19.4f);
                bolt.lineTo(10.8f, 23.4f); bolt.lineTo(15.2f, 17.8f); bolt.lineTo(12.6f, 17.8f); bolt.close();
                // The bolt hangs below the cloud with a gap, as the sun does behind it.
                bolt.op(cloud(1f, 0.5f, 1f, 1.3f), Path.Op.DIFFERENCE);
                p.op(bolt, Path.Op.UNION);
                return p;
            }
            default: return new Path();
        }
    }

    /** A sun or moon partly behind a cloud, with a thin gap round the cloud as SF Symbols draws it. */
    private Path behind(Path back, float x, float y, float s) {
        back.op(cloud(x, y, s, 1.3f), Path.Op.DIFFERENCE);
        back.op(cloud(x, y, s, 0), Path.Op.UNION);
        return back;
    }

    /** A disc with eight short rays, as one filled path. */
    private Path sun(float cx, float cy, float r) {
        Path p = new Path(), ray = new Path(), out = new Path();
        p.addCircle(cx, cy, r, Path.Direction.CW);
        for (int i = 0; i < 8; i++) {
            double a = Math.PI / 4 * i;
            float i0 = r + 2.0f, i1 = r + 3.7f;
            ray.reset();
            ray.moveTo(cx + (float) Math.cos(a) * i0, cy + (float) Math.sin(a) * i0);
            ray.lineTo(cx + (float) Math.cos(a) * i1, cy + (float) Math.sin(a) * i1);
            out.reset();
            line.getFillPath(ray, out);
            p.op(out, Path.Op.UNION);
        }
        return p;
    }

    /** A crescent: a disc with a smaller disc taken out of its upper right. */
    private Path moon(float cx, float cy, float r) {
        Path a = new Path(), cut = new Path();
        a.addCircle(cx, cy, r, Path.Direction.CW);
        cut.addCircle(cx + r * 0.55f, cy - r * 0.45f, r * 0.82f, Path.Direction.CW);
        a.op(cut, Path.Op.DIFFERENCE);
        return a;
    }

    /** A cloud in a 22 x 13 box at (x, y), scaled by s, grown by g on every side: three bumps on a rounded base. */
    private Path cloud(float x, float y, float s, float g) {
        Path p = new Path(), t = new Path();
        p.addRoundRect(new RectF(x + 1 * s - g, y + 7 * s - g, x + 21 * s + g, y + 15 * s + g), 4 * s + g, 4 * s + g, Path.Direction.CW);
        t.addCircle(x + 7 * s, y + 9.5f * s, 4.6f * s + g, Path.Direction.CW); p.op(t, Path.Op.UNION);
        t.reset(); t.addCircle(x + 12.5f * s, y + 7.5f * s, 6f * s + g, Path.Direction.CW); p.op(t, Path.Op.UNION);
        t.reset(); t.addCircle(x + 17.2f * s, y + 10.5f * s, 3.8f * s + g, Path.Direction.CW); p.op(t, Path.Op.UNION);
        return p;
    }

    @Override public void setAlpha(int a) { fill.setAlpha(a); line.setAlpha(a); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter cf) { fill.setColorFilter(cf); line.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}

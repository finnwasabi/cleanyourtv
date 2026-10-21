package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.annotation.NonNull;

/**
 * A liquid glass surface: the frosted wallpaper behind it (Backdrop), a light tint, a specular sheen over the top,
 * and a rim that is bright where light would catch it (top left) and fades towards the bottom right. Everything is
 * a shader on one round rect per layer: no clipPath, no offscreen pass, so it stays on the GPU's fast path.
 * Without a wallpaper it falls back to the tint alone, which still reads as glass over the plain background.
 */
final class LiquidGlassDrawable extends Drawable {
    private final View host;
    private final float radius;
    private final float density;
    private final int tint;
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final int[] loc = new int[2];
    private int alpha = 255;
    /**
     * The frost, tint and sheen drawn into a half-size bitmap off the main thread and reused; the rim is drawn live.
     * Replaying everything on every frame cost a fifth of the frames on the MT9676 (20.91% janky against 0.80%
     * without glass), and a redraw on the UI thread took 13-16 ms each time the dock band came to rest, at up to nine
     * places per trip down the grid and back. One bitmap per place on screen (to the nearest 24px), the last six
     * kept; a place not drawn yet shows the nearest one until its own is ready. Nothing is drawn off screen.
     */
    private static final int KEEP = 6;
    private static final java.util.concurrent.ExecutorService RENDERER = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final java.util.LinkedHashMap<Long, Bitmap> cache = new java.util.LinkedHashMap<Long, Bitmap>(8, 0.75f, true) {
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<Long, Bitmap> eldest) { return size() > KEEP; }
    };
    private final java.util.Set<Long> pending = new java.util.HashSet<>();
    private Bitmap cacheBackdrop;
    private int cacheW, cacheH;
    /** Bumped when the size or the wallpaper changes, so a render started before that is thrown away. */
    private int generation;
    private Bitmap shown;
    private final Paint blit = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect dst = new Rect();

    /**
     * @param host   the view the drawable is drawn in, for its position over the wallpaper
     * @param radius corner radius in px; a pill passes half its height
     * @param tint   ARGB laid over the frost (white at low alpha for clear glass, darker for a dimmer panel)
     */
    LiquidGlassDrawable(View host, float radius, float density, int tint) {
        this.host = host;
        this.radius = radius;
        this.density = density;
        this.tint = tint;
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(1.2f * density);
        Backdrop.register(host);
    }

    @Override
    protected void onBoundsChange(@NonNull Rect b) {
        super.onBoundsChange(b);
        rim.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom,
                new int[]{0xD9FFFFFF, 0x40FFFFFF, 0x1AFFFFFF, 0x59FFFFFF},
                new float[]{0f, 0.35f, 0.7f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect b = getBounds();
        if (b.width() <= 0 || b.height() <= 0) return;
        host.getLocationOnScreen(loc);
        int screenTop = loc[1] + b.top, screenBottom = loc[1] + b.bottom;
        int screenH = host.getResources().getDisplayMetrics().heightPixels;
        if (screenBottom <= 0 || screenTop >= screenH) return;   // scrolled out: nothing to draw or prepare
        int qx = Math.round((loc[0] + b.left) / 24f), qy = Math.round(screenTop / 24f);
        long key = ((long) qx << 32) ^ (qy & 0xFFFFFFFFL);
        Bitmap bd = Backdrop.bitmap();
        if (cacheW != b.width() || cacheH != b.height() || cacheBackdrop != bd) {
            cache.clear();
            pending.clear();
            shown = null;
            cacheW = b.width();
            cacheH = b.height();
            cacheBackdrop = bd;
            generation++;
        }
        Bitmap hit = cache.get(key);
        if (hit == null) {
            if (shown == null) {
                // First draw: nothing to stand in, so draw this one now (once per size and wallpaper).
                hit = render(new Rect(b), loc[0], loc[1], bd);
                cache.put(key, hit);
            } else if (pending.add(key)) {
                final Rect rb = new Rect(b);
                final int lx = loc[0], ly = loc[1], gen = generation;
                RENDERER.execute(() -> {
                    Bitmap done = render(rb, lx, ly, bd);
                    host.post(() -> {
                        pending.remove(key);
                        if (gen != generation) return;
                        cache.put(key, done);
                        host.invalidate();
                    });
                });
            }
        }
        if (hit != null) shown = hit;
        blit.setAlpha(alpha);
        dst.set(b);
        canvas.drawBitmap(shown, null, dst, blit);
        // The rim live: a thin gradient stroke is cheap for the GPU and stays sharp at full size.
        float r = Math.min(radius, b.height() / 2f);
        float inset = rim.getStrokeWidth() / 2f;
        rect.set(b);
        rect.inset(inset, inset);
        rim.setAlpha(alpha);
        canvas.drawRoundRect(rect, r - inset, r - inset, rim);
    }

    /**
     * The frost, tint and sheen of the surface at {@code b} at half size, for a host at (lx, ly) on screen. Uses only
     * its own paints, so it can run off the main thread.
     */
    private Bitmap render(Rect b, int lx, int ly, Bitmap bd) {
        Bitmap out = Bitmap.createBitmap(Math.max(1, b.width() / 2), Math.max(1, b.height() / 2), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.scale(0.5f, 0.5f);
        canvas.translate(-b.left, -b.top);
        float r = Math.min(radius, b.height() / 2f);
        RectF box = new RectF(b);
        if (bd != null) {
            Paint frostPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            BitmapShader shader = new BitmapShader(bd, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            // The slice of the wallpaper behind this view: backdrop -> screen, then screen -> this view.
            Matrix m = new Matrix(Backdrop.matrix());
            m.postTranslate(-lx, -ly);
            shader.setLocalMatrix(m);
            frostPaint.setShader(shader);
            canvas.drawRoundRect(box, r, r, frostPaint);
        }
        Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setShader(new LinearGradient(0, b.top, 0, b.bottom,
                tint, (tint & 0x00FFFFFF) | (((tint >>> 24) / 2) << 24), Shader.TileMode.CLAMP));
        canvas.drawRoundRect(box, r, r, fillPaint);
        Paint sheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        sheenPaint.setShader(new LinearGradient(0, b.top, 0, b.top + b.height() * 0.55f,
                0x40FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(box, r, r, sheenPaint);
        return out;
    }

    @Override public void setAlpha(int a) { alpha = a; invalidateSelf(); }

    @Override public void setColorFilter(ColorFilter cf) { }

    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}

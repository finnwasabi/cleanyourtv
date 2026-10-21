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
    private final Paint frost = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Matrix matrix = new Matrix();
    private final int[] loc = new int[2];
    private Bitmap shaderBitmap;
    private int alpha = 255;
    /**
     * The surface drawn once into a bitmap and reused: the dock band sits under tiles that animate on every focus
     * move, and replaying the frost, sheen and rim on each of those frames cost a fifth of the frames on the
     * MT9676 (20.91% janky against 0.80% without glass). Redrawn only when the size, the wallpaper or the place
     * on screen (to the nearest 24px; the frost is too soft to show less) changes.
     */
    private Bitmap cache;
    private Bitmap cacheBackdrop;
    private int cacheW, cacheH, cacheQx = Integer.MIN_VALUE, cacheQy = Integer.MIN_VALUE;
    private final Paint blit = new Paint(Paint.FILTER_BITMAP_FLAG);
    /** A move alone waits until the surface has stopped moving: mid-scroll the old frost is close enough. */
    private boolean refreshQueued;
    private boolean forceRender;
    private final Runnable refresh;

    /**
     * @param host   the view the drawable is drawn in, for its position over the wallpaper
     * @param radius corner radius in px; a pill passes half its height
     * @param tint   ARGB laid over the frost (white at low alpha for clear glass, darker for a dimmer panel)
     */
    LiquidGlassDrawable(View host, float radius, float density, int tint) {
        this.host = host;
        this.refresh = () -> {
            refreshQueued = false;
            forceRender = true;
            host.invalidate();
        };
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
        fill.setShader(new LinearGradient(0, b.top, 0, b.bottom,
                tint, (tint & 0x00FFFFFF) | (((tint >>> 24) / 2) << 24), Shader.TileMode.CLAMP));
        sheen.setShader(new LinearGradient(0, b.top, 0, b.top + b.height() * 0.55f,
                0x40FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        rim.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom,
                new int[]{0xD9FFFFFF, 0x40FFFFFF, 0x1AFFFFFF, 0x59FFFFFF},
                new float[]{0f, 0.35f, 0.7f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect b = getBounds();
        if (b.width() <= 0 || b.height() <= 0) return;
        host.getLocationOnScreen(loc);
        int qx = Math.round((loc[0] + b.left) / 24f), qy = Math.round((loc[1] + b.top) / 24f);
        Bitmap bd = Backdrop.bitmap();
        boolean moved = cacheQx != qx || cacheQy != qy;
        boolean stale = cache == null || cacheW != b.width() || cacheH != b.height() || cacheBackdrop != bd;
        if (moved && !stale && !forceRender) {
            host.removeCallbacks(refresh);
            host.postDelayed(refresh, 160);
            refreshQueued = true;
        }
        if (stale || (moved && forceRender)) {
            forceRender = false;
            if (cache == null || cache.getWidth() != b.width() || cache.getHeight() != b.height()) {
                cache = Bitmap.createBitmap(b.width(), b.height(), Bitmap.Config.ARGB_8888);
            } else {
                cache.eraseColor(0);
            }
            Canvas c = new Canvas(cache);
            c.translate(-b.left, -b.top);
            render(c, b, bd);
            cacheW = b.width();
            cacheH = b.height();
            cacheBackdrop = bd;
            cacheQx = qx;
            cacheQy = qy;
        }
        blit.setAlpha(alpha);
        canvas.drawBitmap(cache, b.left, b.top, blit);
    }

    /** One full draw of the surface at {@code b}, with the host's on-screen place already in {@code loc}. */
    private void render(Canvas canvas, Rect b, Bitmap bd) {
        float r = Math.min(radius, b.height() / 2f);
        rect.set(b);
        if (bd != null) {
            if (bd != shaderBitmap) {
                shaderBitmap = bd;
                frost.setShader(new BitmapShader(bd, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
            }
            // The slice of the wallpaper behind this view: backdrop -> screen, then screen -> this view.
            matrix.set(Backdrop.matrix());
            matrix.postTranslate(-loc[0], -loc[1]);
            frost.getShader().setLocalMatrix(matrix);
            canvas.drawRoundRect(rect, r, r, frost);
        }
        canvas.drawRoundRect(rect, r, r, fill);
        canvas.drawRoundRect(rect, r, r, sheen);
        float inset = rim.getStrokeWidth() / 2f;
        rect.inset(inset, inset);
        canvas.drawRoundRect(rect, r - inset, r - inset, rim);
    }

    @Override public void setAlpha(int a) { alpha = a; invalidateSelf(); }

    @Override public void setColorFilter(ColorFilter cf) { }

    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}

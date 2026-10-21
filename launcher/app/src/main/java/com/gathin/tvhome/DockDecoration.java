package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/** Draws the tvOS style lighter band behind the first (dock) row and adds a gap below it. */
public class DockDecoration extends RecyclerView.ItemDecoration {

    private final int dockSize;
    private final float density;
    private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    /** Liquid glass band (theme key liquid_glass), drawn over the grid it decorates; null keeps the flat band. */
    private final LiquidGlassDrawable glass;
    private final android.graphics.Rect bounds = new android.graphics.Rect();
    /** EdgeFadeLayout's fade for the band as it scrolls up under the header; 1 on screen. */
    private float fade = 1f;
    private final int bandAlpha, edgeAlpha;
    private final RectF last = new RectF();

    /** Extra band below the tiles for their names (always_show_names), in px. */
    private final float namesRoom;

    /**
     * @param translucent the cinematic shelf's band: the artwork and the glow show through it.
     * @param names       the dock tiles show their names, so the band reaches down over them.
     * @param glassHost   the view a liquid glass band is drawn in (theme key liquid_glass); null for the flat band.
     */
    DockDecoration(int dockSize, float density, boolean translucent, boolean names, View glassHost) {
        this.glass = glassHost == null ? null : new LiquidGlassDrawable(glassHost, 26 * density, density, 0x1FFFFFFF);
        this.dockSize = dockSize;
        this.density = density;
        this.namesRoom = names ? 22 * density : 0;
        band.setColor(translucent ? 0x8C121014 : 0xFF141416);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(density);
        edge.setColor(translucent ? 0x26FFFFFF : 0x1FFFFFFF);
        bandAlpha = band.getAlpha();
        edgeAlpha = edge.getAlpha();
    }

    /** Works out the band's fade from where it was last drawn; true when it changed and the band needs a redraw. */
    boolean fadeTo(RecyclerView parent) {
        float f = 1f;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            int pos = parent.getChildAdapterPosition(child);
            if (pos < 0 || pos >= dockSize) continue;
            float t = child.getTop() + child.getTranslationY();
            f = EdgeFadeLayout.visible(t, t + child.getHeight());
            break;
        }
        if (f == fade) return false;
        fade = f;
        return true;
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent,
                               @NonNull RecyclerView.State state) {
        int pos = parent.getChildAdapterPosition(view);
        if (pos >= 0 && pos < dockSize) {
            outRect.top = (int) (6 * density);
            outRect.bottom = (int) (12 * density);
        }
    }

    @Override
    public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        boolean any = false;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            int pos = parent.getChildAdapterPosition(child);
            if (pos < 0 || pos >= dockSize) continue;
            RecyclerView.ViewHolder vh = parent.getChildViewHolder(child);
            View card = vh instanceof AppAdapter.Holder ? ((AppAdapter.Holder) vh).card : child.findViewById(R.id.card);
            if (card == null) continue;
            float l = child.getLeft() + card.getLeft();
            float t = child.getTop() + card.getTop() + child.getTranslationY();
            float r = l + card.getWidth();
            float b = t + card.getHeight();
            if (!any) {
                rect.set(l, t, r, b);
                any = true;
            } else {
                rect.union(l, t, r, b);
            }
        }
        if (!any) return;
        float pad = 14 * density;
        // The band can only draw inside the grid, and the first and last dock tiles sit 12dp in from its
        // edges: a full 14dp at the sides sliced the rounded ends flat (#454). Keep the sides, with the
        // 1dp edge line, inside the grid; the top and bottom have room.
        float side = Math.max(0f, Math.min(pad, Math.min(rect.left, parent.getWidth() - rect.right) - density));
        rect.inset(-side, -pad);
        rect.bottom += namesRoom;
        float radius = 26 * density;
        if (fade <= 0f) return;
        if (glass != null) {
            rect.roundOut(bounds);
            glass.setBounds(bounds);
            glass.setAlpha(Math.round(255 * fade));
            glass.draw(c);
            return;
        }
        band.setAlpha(Math.round(bandAlpha * fade));
        edge.setAlpha(Math.round(edgeAlpha * fade));
        c.drawRoundRect(rect, radius, radius, band);
        c.drawRoundRect(rect, radius, radius, edge);
    }
}

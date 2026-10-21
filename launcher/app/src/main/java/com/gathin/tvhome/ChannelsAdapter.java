package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * The channel rows (ChannelRows) between the dock and the app grid, placed by HomeAdapter: one item per channel,
 * its programs in a horizontal list that sits in the grid's columns at tile size, so the rows read as more of the
 * grid. Focus follows the grid's rules: Up and Down move one row, the ends of a row hold focus, the glass highlight
 * and the name under the card come from Host. Posters load off the main thread into a small memory cache.
 * A row runs from screen edge to screen edge and scrolls a whole cell at a time (ColumnLayoutManager), so its cards
 * stay in the grid's columns. A card outside the columns is dimmed and comes up to full as it scrolls in, and a slim
 * indicator on the title line (RowIndicator) shows where the row is, so "there is more" reads without clutter.
 */
final class ChannelsAdapter extends RecyclerView.Adapter<ChannelsAdapter.RowHolder> {

    interface Host {
        /** A program card got focus: move the glass highlight onto it, the glow to its poster's colour (0: none yet). */
        void onChannelFocused(View card, int color);
        /** The focused card's row scrolled sideways: keep the glass on the card. */
        void onChannelScrolled(View card);
        /** Up from the first channel row, from this column. */
        void onChannelsExitUp(int column);
        /** Down from the last channel row, from this column. */
        void onChannelsExitDown(int column);
        /** Tiles per row in the app grid. */
        int columns();
        /** Grid position of the first channel row. */
        int channelStart();
        /** The app grid's tile size and cell width, in px, so programs line up with its columns. */
        int tileWidth();
        int tileHeight();
        int cellWidth();
        float focusScale();
    }

    private final Context ctx;
    private final RecyclerView grid;
    private final float density;
    private final boolean animations;
    private final boolean reducedMotion;
    private Host host;
    private List<ChannelRows.Row> rows = Collections.emptyList();
    private String signature = "";
    /** Last focused program per row, so Up and Down come back to the column they left. */
    private final Map<Long, Integer> lastColumn = new HashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = BackgroundThreads.single("channels");
    private final ExecutorService posters = BackgroundThreads.pool(2, "posters");
    private boolean loading;
    /** Off (dock_row false): no rows load, so none show. */
    private boolean disabled;
    /** Rows that arrived while a program had focus, applied once focus leaves the rows (applyPending). */
    private List<ChannelRows.Row> pendingRows;
    private String pendingSignature;
    /** The row of the last focused program, for focusLast. */
    private int lastRow = -1;
    /** Posters decoded at card size; a few MB, enough for every row on screen and a scroll each way. */
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };

    ChannelsAdapter(Context ctx, RecyclerView grid, boolean animations, boolean reducedMotion) {
        this.ctx = ctx;
        this.grid = grid;
        this.animations = animations;
        this.reducedMotion = reducedMotion;
        this.density = ctx.getResources().getDisplayMetrics().density;
        setHasStableIds(true);
    }

    void setHost(Host host) { this.host = host; }

    /** Drops the posters kept for scrolling (Home in the background, memory short); those on cards stay. */
    void trimMemory() {
        cache.evictAll();
        colors.clear();
    }

    int rowCount() { return rows.size(); }

    /** Reloads the rows in the background; the list changes only when what it shows changed. */
    void disable() { disabled = true; }

    /** Stops the loader and poster threads with the activity, so a recreate does not leave them behind. */
    void close() {
        loader.shutdownNow();
        posters.shutdownNow();
    }

    /** Focuses the program that had focus last (back from the app it opened); false when there is none. */
    boolean focusLast() {
        if (lastRow < 0 || lastRow >= rows.size()) return false;
        focusRow(lastRow, -1);
        return true;
    }

    /** Applies rows that arrived while a program had focus; called when focus moves to the app grid. */
    void applyPending() {
        if (pendingRows == null || hasFocus()) return;
        rows = pendingRows;
        signature = pendingSignature;
        pendingRows = null;
        notifyDataSetChanged();
    }

    void refresh() {
        if (loading || disabled) return;
        loading = true;
        loader.execute(() -> {
            List<ChannelRows.Row> next = ChannelRows.load(ctx);
            StringBuilder sb = new StringBuilder();
            for (ChannelRows.Row r : next) sb.append(r.signature()).append('\n');
            String sig = sb.toString();
            main.post(() -> {
                loading = false;
                if (sig.equals(signature)) return;
                // A rebind under the focused program would drop focus; keep them until focus leaves the rows.
                if (hasFocus()) {
                    pendingRows = next;
                    pendingSignature = sig;
                    return;
                }
                pendingRows = null;
                signature = sig;
                rows = next;
                notifyDataSetChanged();
            });
        });
    }

    boolean hasFocus() {
        View f = grid.findFocus();
        return f != null && f.getTag(R.id.program_tag) != null;
    }

    /**
     * Focuses row {@code index}: the card in grid column {@code column} as the row is scrolled now, or, when column
     * is -1, the program it last had focus on.
     */
    void focusRow(int index, int column) {
        if (index < 0 || index >= rows.size() || host == null) return;
        final int col = column;
        final int last = columnFor(index);
        int position = host.channelStart() + index;
        cancelPendingScroll();
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(position);
        if (vh instanceof RowHolder) {
            ((RowHolder) vh).focus(col, last);
            return;
        }
        // The row was scrolled out and recycled (back up from the bottom of the grid). Jumping to it with
        // scrollToPosition snapped the whole screen; glide there like AppAdapter tiles do, then focus.
        if (!animations) {
            grid.scrollToPosition(position);
            grid.post(() -> {
                RecyclerView.ViewHolder h = grid.findViewHolderForAdapterPosition(position);
                if (h instanceof RowHolder) ((RowHolder) h).focus(col, last);
            });
            return;
        }
        pendingScroll = new RecyclerView.OnScrollListener() {
            @Override public void onScrollStateChanged(@NonNull RecyclerView rv, int state) {
                if (state != RecyclerView.SCROLL_STATE_IDLE) return;
                rv.removeOnScrollListener(this);
                if (pendingScroll != this) return;
                pendingScroll = null;
                RecyclerView.ViewHolder h = rv.findViewHolderForAdapterPosition(position);
                if (h instanceof RowHolder) ((RowHolder) h).focus(col, last);
            }
        };
        grid.addOnScrollListener(pendingScroll);
        grid.smoothScrollToPosition(position);
    }

    private RecyclerView.OnScrollListener pendingScroll;

    private void cancelPendingScroll() {
        if (pendingScroll != null) {
            grid.removeOnScrollListener(pendingScroll);
            pendingScroll = null;
        }
    }

    private int columnFor(int index) {
        Integer c = lastColumn.get(rows.get(index).id);
        return c == null ? 0 : Math.min(c, rows.get(index).programs.size() - 1);
    }

    /** Left inset of a row title, so it lines up with the first card (and the first tile) under it. */
    int titleInset() {
        return host == null ? 0 : Math.max(0, (host.cellWidth() - host.tileWidth()) / 2);
    }

    @Override public long getItemId(int position) { return rows.get(position).id; }

    @Override public int getItemCount() { return rows.size(); }

    @NonNull
    @Override
    public RowHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new RowHolder(LayoutInflater.from(ctx).inflate(R.layout.row_channel, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RowHolder holder, int position) {
        holder.bind(position, rows.get(position));
    }

    final class RowHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final RecyclerView list;
        final RowIndicator indicator;
        final ProgramAdapter programs = new ProgramAdapter();
        final ColumnLayoutManager layout = new ColumnLayoutManager();
        int rowIndex;

        RowHolder(View v) {
            super(v);
            title = v.findViewById(R.id.channelTitle);
            list = v.findViewById(R.id.programs);
            indicator = v.findViewById(R.id.rowIndicator);
            layout.owner = this;
            list.setLayoutManager(layout);
            list.setAdapter(programs);
            list.setItemAnimator(null);
            list.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                    View f = rv.findFocus();
                    if (dx != 0 && f != null && host != null) host.onChannelScrolled(f);
                    shade();
                }
            });
        }

        void bind(int index, ChannelRows.Row row) {
            rowIndex = index;
            title.setText(row.name);
            // Line the title up with the first card's left edge, as the grid's first column.
            if (host != null) {
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) title.getLayoutParams();
                lp.setMarginStart(titleInset());
                title.setLayoutParams(lp);
                ViewGroup.MarginLayoutParams ilp = (ViewGroup.MarginLayoutParams) indicator.getLayoutParams();
                if (ilp.getMarginEnd() != titleInset()) {
                    ilp.setMarginEnd(titleInset());
                    indicator.setLayoutParams(ilp);
                }
            }
            // Edge to edge: the list reaches into the grid's side padding, which keeps the cards in the columns.
            int start = grid.getPaddingLeft(), end = grid.getPaddingRight();
            ViewGroup.MarginLayoutParams llp = (ViewGroup.MarginLayoutParams) list.getLayoutParams();
            if (llp.leftMargin != -start || llp.rightMargin != -end) {
                llp.leftMargin = -start;
                llp.rightMargin = -end;
                list.setLayoutParams(llp);
            }
            list.setPadding(start, list.getPaddingTop(), end, list.getPaddingBottom());
            // The same row again (Home rebinding everything on resume): leave the cards, their posters and focus.
            if (programs.row != row) programs.set(row);
        }

        /**
         * Dims the cards outside the columns (the one peeking in at either edge) and brings each up to full as it
         * scrolls in, and moves the indicator. A dimmed card draws through a hardware layer, so its poster and the
         * label under it fade as one; cards in the columns draw without one.
         */
        void shade() {
            int cols = host != null ? host.columns() : 4;
            float cell = layout.cellWidth();
            for (int i = 0; i < list.getChildCount(); i++) {
                View c = list.getChildAt(i);
                float col = (c.getLeft() + c.getTranslationX() - list.getPaddingLeft()) / cell;
                float out = Math.max(0f, Math.max(-col, col - (cols - 1)));
                float a = 1f - PEEK_DIM * Math.min(1f, out);
                if (c.getAlpha() == a) continue;
                c.setAlpha(a);
                int type = a < 1f ? View.LAYER_TYPE_HARDWARE : View.LAYER_TYPE_NONE;
                if (c.getLayerType() != type) c.setLayerType(type, null);
            }
            // Counted in cells, not RecyclerView's scroll estimate, which the side padding throws off.
            int n = programs.getItemCount();
            View c0 = list.getChildCount() > 0 ? list.getChildAt(0) : null;
            float first = c0 == null ? 0f
                    : layout.getPosition(c0) - (c0.getLeft() + c0.getTranslationX() - list.getPaddingLeft()) / cell;
            indicator.set(Math.max(0f, first), Math.min(cols, n), n);
        }

        /** Focuses the card in grid column {@code column} (>= 0), else the program at {@code last}. */
        void focus(int column, int last) {
            int n = programs.getItemCount();
            if (n == 0) return;
            int index = column >= 0 ? layout.firstPosition() + column : last;
            focusColumn(Math.max(0, Math.min(index, n - 1)));
        }

        /** Grid column of a laid out card, 0 to columns - 1. */
        int columnOf(View item) {
            int cols = host != null ? host.columns() : 4;
            return Math.max(0, Math.min(cols - 1, layout.columnOf(item)));
        }

        void focusColumn(int column) {
            RecyclerView.ViewHolder h = list.findViewHolderForAdapterPosition(column);
            if (h instanceof ProgramHolder) {
                take((ProgramHolder) h);
                return;
            }
            list.scrollToPosition(column);
            list.post(() -> {
                RecyclerView.ViewHolder p = list.findViewHolderForAdapterPosition(column);
                if (p instanceof ProgramHolder) take((ProgramHolder) p);
            });
        }

        /**
         * Focuses a card. One that kept focus while Home was away (back from the program it opened) gets no new
         * focus event, so the glass and the glow are put on it here.
         */
        private void take(ProgramHolder p) {
            if (p.card.isFocused()) {
                if (host != null) host.onChannelFocused(p.card, posterColor(p));
            } else {
                p.card.requestFocus();
            }
        }

        final class ProgramAdapter extends RecyclerView.Adapter<ProgramHolder> {
            ChannelRows.Row row;

            ProgramAdapter() { setHasStableIds(true); }

            void set(ChannelRows.Row r) {
                row = r;
                notifyDataSetChanged();
            }

            @Override public long getItemId(int position) { return row.programs.get(position).id; }

            @Override public int getItemCount() { return row == null ? 0 : row.programs.size(); }

            @NonNull
            @Override
            public ProgramHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                return new ProgramHolder(LayoutInflater.from(ctx).inflate(R.layout.item_program, parent, false));
            }

            @Override
            public void onBindViewHolder(@NonNull ProgramHolder h, int position) {
                h.bind(row, row.programs.get(position), position);
            }
        }

        final class ProgramHolder extends RecyclerView.ViewHolder {
            final View card;
            final ImageView poster;
            final TextView fallback;
            final TextView name;
            final View progressTrack;
            final View progress;
            String url;

            ProgramHolder(View v) {
                super(v);
                card = v.findViewById(R.id.card);
                poster = v.findViewById(R.id.poster);
                fallback = v.findViewById(R.id.posterLabel);
                name = v.findViewById(R.id.name);
                // No fading edges on the scrolling name: they draw through an offscreen layer every frame.
                name.setHorizontalFadingEdgeEnabled(false);
                progressTrack = v.findViewById(R.id.progressTrack);
                progress = v.findViewById(R.id.progress);
                card.setOutlineProvider(new ViewOutlineProvider() {
                    @Override public void getOutline(View view, Outline outline) {
                        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                                AppAdapter.cornerRadius(view.getWidth(), density));
                    }
                });
                card.setClipToOutline(true);
            }

            void bind(ChannelRows.Row row, ChannelRows.Program p, int column) {
                // One grid cell per program; the card is a tile's height, and a tile's width for 16:9 art.
                int cell = host != null ? host.cellWidth() : Math.round(213 * density);
                int h = host != null ? host.tileHeight() : Math.round(101 * density);
                int tileW = host != null ? host.tileWidth() : Math.round(180 * density);
                float aspect = Math.max(0.5f, Math.min(16f / 9f, p.aspect));
                int w = Math.min(tileW, Math.round(h * aspect));
                // Always one cell, narrow art centred in it, so a row scrolled by whole cells stays in the columns.
                int itemW = cell;
                ViewGroup.LayoutParams ilp = itemView.getLayoutParams();
                if (ilp.width != itemW) {
                    ilp.width = itemW;
                    itemView.setLayoutParams(ilp);
                }
                ViewGroup.LayoutParams clp = card.getLayoutParams();
                if (clp.width != w || clp.height != h) {
                    clp.width = w;
                    clp.height = h;
                    card.setLayoutParams(clp);
                }
                ViewGroup.MarginLayoutParams nlp = (ViewGroup.MarginLayoutParams) name.getLayoutParams();
                if (nlp.width != itemW || nlp.topMargin != h + Math.round(7 * density)) {
                    nlp.width = itemW;
                    nlp.topMargin = h + Math.round(7 * density);
                    name.setLayoutParams(nlp);
                }
                card.setTag(R.id.program_tag, p);
                name.setText(p.subtitle == null || p.subtitle.isEmpty() ? p.title : p.title + " · " + p.subtitle);
                fallback.setText(p.title);
                fallback.setVisibility(View.VISIBLE);
                if (p.progress >= 0) {
                    progressTrack.setVisibility(View.VISIBLE);
                    ViewGroup.LayoutParams plp = progress.getLayoutParams();
                    plp.width = Math.max(1, (int) ((w - 20 * density) * p.progress / 1000f));
                    progress.setLayoutParams(plp);
                } else {
                    progressTrack.setVisibility(View.GONE);
                }
                loadPoster(this, p.poster, w, h);
                boolean focused = card.isFocused();
                float scale = host != null ? host.focusScale() : 1.10f;
                card.setScaleX(focused ? scale : 1f);
                card.setScaleY(focused ? scale : 1f);
                name.setAlpha(focused ? 1f : 0f);
                name.setSelected(focused);
                card.setOnClickListener(v -> open(row, p));
                card.setOnFocusChangeListener((v, has) -> {
                    v.animate().cancel();
                    name.animate().cancel();
                    name.setSelected(has);   // a long title scrolls by twice, as a tile's name does
                    float s = has ? scale : 1f;
                    if (!animations) {
                        name.setAlpha(has ? 1f : 0f);
                    } else if (reducedMotion) {
                        v.animate().scaleX(s).scaleY(s).setDuration(has ? 120 : 80)
                                .setInterpolator(new DecelerateInterpolator()).withLayer().start();
                        name.animate().alpha(has ? 1f : 0f).setDuration(100).start();
                        if (has) name.setTranslationY(density * 4f);
                    } else {
                        // The app tiles' motion (AppAdapter.Holder): a small overshoot in, a plain settle out.
                        v.animate().scaleX(s).scaleY(s).setDuration(has ? 260 : 180)
                                .setInterpolator(new OvershootInterpolator(has ? 1.6f : 0f)).withLayer().start();
                        name.animate().alpha(has ? 1f : 0f).translationY(has ? density * 4f : -density * 6f)
                                .setDuration(220).start();
                    }
                    if (!has) return;
                    lastColumn.put(row.id, column);
                    lastRow = rowIndex;
                    if (host != null) host.onChannelFocused(v, posterColor(this));
                });
                card.setOnKeyListener((v, key, event) -> {
                    if (event.getAction() != KeyEvent.ACTION_DOWN) {
                        return key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN;
                    }
                    switch (key) {
                        case KeyEvent.KEYCODE_DPAD_UP: {
                            int col = columnOf(itemView);
                            if (rowIndex == 0) { if (host != null) host.onChannelsExitUp(col); }
                            else focusRow(rowIndex - 1, col);
                            return true;
                        }
                        case KeyEvent.KEYCODE_DPAD_DOWN: {
                            int col = columnOf(itemView);
                            if (rowIndex < rows.size() - 1) focusRow(rowIndex + 1, col);
                            else if (host != null) host.onChannelsExitDown(col);
                            return true;
                        }
                        case KeyEvent.KEYCODE_DPAD_LEFT:
                            return column == 0;   // the row's ends hold focus, as in the app grid
                        case KeyEvent.KEYCODE_DPAD_RIGHT:
                            return column == row.programs.size() - 1;
                        default:
                            return false;
                    }
                });
            }
        }
    }

    /** How much a card outside the columns is dimmed: enough to read as "next", not as a choice. */
    private static final float PEEK_DIM = 0.65f;
    private static final PathInterpolator GLIDE = new PathInterpolator(0.4f, 0f, 0.2f, 1f);

    /**
     * A row that scrolls a whole cell at a time. Focus on a card past the last column scrolls the row until that card
     * sits in the last column; focus before the first column, until it sits in the first. Cards stay where the app
     * tiles are, and the card past either end peeks in from the screen edge.
     */
    final class ColumnLayoutManager extends LinearLayoutManager {
        RowHolder owner;

        ColumnLayoutManager() { super(ctx, LinearLayoutManager.HORIZONTAL, false); }

        private int cell() { return host != null ? host.cellWidth() : Math.round(213 * density); }

        int cellWidth() { return cell(); }

        @Override
        public void onLayoutCompleted(RecyclerView.State state) {
            super.onLayoutCompleted(state);
            if (owner != null) owner.shade();
        }

        /**
         * One more card each side than fits between the paddings: the peeking cards sit in the padding, which a
         * scroll does not fill on its own, so without this the card past the last column vanished after a scroll.
         */
        @Override
        protected void calculateExtraLayoutSpace(@NonNull RecyclerView.State state, @NonNull int[] extra) {
            extra[0] = getPaddingLeft() + cell();
            extra[1] = getPaddingRight() + cell();
        }

        /** Grid column an item sits in now, unclamped: -1 is the card peeking in on the left. */
        int columnOf(View item) {
            return Math.round((getDecoratedLeft(item) - getPaddingLeft()) / (float) cell());
        }

        /** Position of the program in the first grid column. */
        int firstPosition() {
            if (getChildCount() == 0) return 0;
            View c = getChildAt(0);
            return Math.max(0, getPosition(c) - columnOf(c));
        }

        @Override
        public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent, @NonNull View child,
                @NonNull Rect rect, boolean immediate, boolean focusedChildVisible) {
            int cols = host != null ? host.columns() : 4;
            int target = Math.max(0, Math.min(columnOf(child), cols - 1));
            int dx = getDecoratedLeft(child) - getPaddingLeft() - target * cell();
            if (dx == 0) return false;
            if (immediate || !animations) parent.scrollBy(dx, 0);
            else parent.smoothScrollBy(dx, 0, GLIDE, reducedMotion ? 200 : 320);
            return true;
        }
    }

    /** Poster colours already worked out, by poster URL: the glow behind the grid takes them, as tiles' banners. */
    private final Map<String, Integer> colors = new HashMap<>();

    /** The vivid colour of a card's poster, as AppAdapter picks one from a banner; 0 while the poster loads. */
    private int posterColor(RowHolder.ProgramHolder h) {
        if (h.url == null) return 0;
        Integer c = colors.get(h.url);
        if (c != null) return c;
        android.graphics.drawable.Drawable d = h.poster.getDrawable();
        if (d == null || h.poster.getVisibility() != View.VISIBLE) return 0;
        int v = AppAdapter.vividColor(d);
        if (colors.size() > 200) colors.clear();
        colors.put(h.url, v);
        return v;
    }

    /** Opens a program the way the stock launcher does: its intent_uri, else the app itself. */
    private void open(ChannelRows.Row row, ChannelRows.Program p) {
        // A Watch Next row mixes apps: each program opens in the app that published it.
        String pkg = p.pkg != null ? p.pkg : row.pkg;
        if (pkg == null) return;
        Intent i = null;
        if (p.intentUri != null && !p.intentUri.isEmpty()) {
            try {
                i = Intent.parseUri(p.intentUri, Intent.URI_INTENT_SCHEME);
                // A program may only start the app that published it.
                if (i.getComponent() != null && !pkg.equals(i.getComponent().getPackageName())) i = null;
                else if (i.getComponent() == null) i.setPackage(pkg);
                if (i != null) i.setSelector(null);
            } catch (Exception e) {
                i = null;
            }
        }
        if (i == null) i = ctx.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) return;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Intent app = ctx.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
            if (app != null) {
                try { ctx.startActivity(app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) { }
            }
        }
    }

    // ---- posters -----------------------------------------------------------------------------------

    private void loadPoster(RowHolder.ProgramHolder h, String url, int w, int hgt) {
        // Already showing this poster: keep it, rather than blank the card and fetch it again.
        if (url != null && url.equals(h.url) && h.poster.getDrawable() != null) return;
        h.url = url;
        h.poster.setImageDrawable(null);
        if (url == null || url.isEmpty()) return;
        Bitmap cached = cache.get(url);
        if (cached != null) {
            h.poster.setImageBitmap(cached);
            h.fallback.setVisibility(View.GONE);
            return;
        }
        posters.execute(() -> {
            Bitmap b = decode(url, w, hgt);
            if (b == null) return;
            // Upload the texture now, on the render thread, not in the frame that first draws the card: that
            // upload was most of the 36 slow draw frames on the first trip through the rows after a cold start.
            b.prepareToDraw();
            cache.put(url, b);
            main.post(() -> {
                if (!url.equals(h.url)) return;   // the holder was rebound to another program meanwhile
                h.poster.setImageBitmap(b);
                h.fallback.setVisibility(View.GONE);
            });
        });
    }

    /** Downloads (http/https) or opens (content, android.resource, file) and decodes near the card size. */
    private Bitmap decode(String url, int w, int h) {
        byte[] data;
        try {
            data = read(url);
        } catch (Exception e) {
            return null;
        }
        if (data == null || data.length == 0) return null;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        try {
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (OutOfMemoryError e) {
            return null;
        }
    }

    private byte[] read(String url) throws Exception {
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme();
        InputStream in;
        HttpURLConnection conn = null;
        if (scheme.equals("http") || scheme.equals("https")) {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            conn.setInstanceFollowRedirects(true);
            in = new BufferedInputStream(conn.getInputStream());
        } else {
            in = ctx.getContentResolver().openInputStream(uri);
        }
        if (in == null) return null;
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n, total = 0;
            while ((n = s.read(buf)) > 0) {
                total += n;
                if (total > 6 * 1024 * 1024) return null;   // not a poster
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}

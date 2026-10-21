package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
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
import java.util.concurrent.Executors;

/**
 * The channel rows (ChannelRows) between the dock and the app grid, placed by HomeAdapter: one item per channel,
 * its programs in a horizontal list that sits in the grid's columns at tile size, so the rows read as more of the
 * grid. Focus follows the grid's rules: Up and Down move one row, the ends of a row hold focus, the glass highlight
 * and the name under the card come from Host. Posters load off the main thread into a small memory cache.
 */
final class ChannelsAdapter extends RecyclerView.Adapter<ChannelsAdapter.RowHolder> {

    interface Host {
        /** A program card got focus: move the glass highlight onto it. */
        void onChannelFocused(View card);
        /** The focused card's row scrolled sideways: keep the glass on the card. */
        void onChannelScrolled(View card);
        /** Up from the first channel row, from this column. */
        void onChannelsExitUp(int column);
        /** Down from the last channel row, from this column. */
        void onChannelsExitDown(int column);
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
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final ExecutorService posters = Executors.newFixedThreadPool(2);
    private boolean loading;
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

    int rowCount() { return rows.size(); }

    /** Reloads the rows in the background; the list changes only when what it shows changed. */
    void refresh() {
        if (loading) return;
        loading = true;
        loader.execute(() -> {
            List<ChannelRows.Row> next = ChannelRows.load(ctx);
            StringBuilder sb = new StringBuilder();
            for (ChannelRows.Row r : next) sb.append(r.signature()).append('\n');
            String sig = sb.toString();
            main.post(() -> {
                loading = false;
                if (sig.equals(signature)) return;
                // A rebind under the focused program would drop focus; try again on the next refresh.
                if (hasFocus()) return;
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

    /** Focuses row {@code index}, at the column it last had focus in, or at {@code column} when given (>= 0). */
    void focusRow(int index, int column) {
        if (index < 0 || index >= rows.size() || host == null) return;
        int col = column >= 0 ? Math.min(column, rows.get(index).programs.size() - 1) : columnFor(index);
        int position = host.channelStart() + index;
        RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(position);
        if (vh instanceof RowHolder) {
            ((RowHolder) vh).focusColumn(col);
            return;
        }
        grid.scrollToPosition(position);
        grid.post(() -> {
            RecyclerView.ViewHolder h = grid.findViewHolderForAdapterPosition(position);
            if (h instanceof RowHolder) ((RowHolder) h).focusColumn(col);
        });
    }

    private int columnFor(int index) {
        Integer c = lastColumn.get(rows.get(index).id);
        return c == null ? 0 : Math.min(c, rows.get(index).programs.size() - 1);
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
        final ProgramAdapter programs = new ProgramAdapter();
        int rowIndex;

        RowHolder(View v) {
            super(v);
            title = v.findViewById(R.id.channelTitle);
            list = v.findViewById(R.id.programs);
            list.setLayoutManager(new LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false));
            list.setAdapter(programs);
            list.setItemAnimator(null);
            list.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                    View f = rv.findFocus();
                    if (dx != 0 && f != null && host != null) host.onChannelScrolled(f);
                }
            });
        }

        void bind(int index, ChannelRows.Row row) {
            rowIndex = index;
            title.setText(row.name);
            // Line the title up with the first card's left edge, as the grid's first column.
            if (host != null) {
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) title.getLayoutParams();
                lp.setMarginStart(Math.max(0, (host.cellWidth() - host.tileWidth()) / 2));
                title.setLayoutParams(lp);
            }
            programs.set(row);
        }

        void focusColumn(int column) {
            RecyclerView.ViewHolder h = list.findViewHolderForAdapterPosition(column);
            if (h instanceof ProgramHolder) {
                ((ProgramHolder) h).card.requestFocus();
                return;
            }
            list.scrollToPosition(column);
            list.post(() -> {
                RecyclerView.ViewHolder p = list.findViewHolderForAdapterPosition(column);
                if (p instanceof ProgramHolder) ((ProgramHolder) p).card.requestFocus();
            });
        }

        final class ProgramAdapter extends RecyclerView.Adapter<ProgramHolder> {
            ChannelRows.Row row;

            void set(ChannelRows.Row r) {
                row = r;
                notifyDataSetChanged();
            }

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
                int itemW = aspect >= 16f / 9f - 0.01f ? cell : w + (cell - tileW);
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
                card.setOnClickListener(v -> open(row, p));
                card.setOnFocusChangeListener((v, has) -> {
                    v.animate().cancel();
                    name.animate().cancel();
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
                    if (host != null) host.onChannelFocused(v);
                });
                card.setOnKeyListener((v, key, event) -> {
                    if (event.getAction() != KeyEvent.ACTION_DOWN) {
                        return key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN;
                    }
                    switch (key) {
                        case KeyEvent.KEYCODE_DPAD_UP:
                            if (rowIndex == 0) { if (host != null) host.onChannelsExitUp(column); }
                            else focusRow(rowIndex - 1, column);
                            return true;
                        case KeyEvent.KEYCODE_DPAD_DOWN:
                            if (rowIndex < rows.size() - 1) focusRow(rowIndex + 1, column);
                            else if (host != null) host.onChannelsExitDown(column);
                            return true;
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

    /** Opens a program the way the stock launcher does: its intent_uri, else the app itself. */
    private void open(ChannelRows.Row row, ChannelRows.Program p) {
        Intent i = null;
        if (p.intentUri != null && !p.intentUri.isEmpty()) {
            try {
                i = Intent.parseUri(p.intentUri, Intent.URI_INTENT_SCHEME);
                // A program may only start the app that published it.
                if (i.getComponent() != null && !row.pkg.equals(i.getComponent().getPackageName())) i = null;
                else if (i.getComponent() == null) i.setPackage(row.pkg);
                if (i != null) i.setSelector(null);
            } catch (Exception e) {
                i = null;
            }
        }
        if (i == null) i = ctx.getPackageManager().getLeanbackLaunchIntentForPackage(row.pkg);
        if (i == null) i = ctx.getPackageManager().getLaunchIntentForPackage(row.pkg);
        if (i == null) return;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Intent app = ctx.getPackageManager().getLeanbackLaunchIntentForPackage(row.pkg);
            if (app != null) {
                try { ctx.startActivity(app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) { }
            }
        }
    }

    // ---- posters -----------------------------------------------------------------------------------

    private void loadPoster(RowHolder.ProgramHolder h, String url, int w, int hgt) {
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

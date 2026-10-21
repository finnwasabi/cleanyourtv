package com.gathin.tvhome;

import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * The home screen's one list: the dock row, then the channel rows, then the rest of the app grid and the library
 * pill. AppAdapter and the rest of the launcher keep counting in app positions (0 is the first dock tile); grid
 * positions are what the RecyclerView sees. toGrid and toApp convert between the two.
 */
final class HomeAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final int TYPE_CHANNEL = 100;

    private final AppAdapter apps;
    private final ChannelsAdapter channels;
    /** App positions before the channel rows: the dock row. */
    private final int split;

    HomeAdapter(AppAdapter apps, ChannelsAdapter channels, int dockSize) {
        this.apps = apps;
        this.channels = channels;
        this.split = dockSize;
        setHasStableIds(true);
        // Any change on either side: the grid positions after it move, so rebind in one go. Reorder moves inside
        // the app grid are passed on as moves, so the drag animation keeps working.
        apps.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override public void onChanged() { notifyDataSetChanged(); }
            @Override public void onItemRangeChanged(int start, int count) {
                for (int i = start; i < start + count; i++) notifyItemChanged(toGrid(i));
            }
            @Override public void onItemRangeChanged(int start, int count, Object payload) {
                for (int i = start; i < start + count; i++) notifyItemChanged(toGrid(i), payload);
            }
            @Override public void onItemRangeInserted(int start, int count) { notifyDataSetChanged(); }
            @Override public void onItemRangeRemoved(int start, int count) { notifyDataSetChanged(); }
            @Override public void onItemRangeMoved(int from, int to, int count) {
                if ((from < splitAt()) == (to < splitAt())) notifyItemMoved(toGrid(from), toGrid(to));
                else notifyDataSetChanged();
            }
        });
        channels.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override public void onChanged() { notifyDataSetChanged(); }
            @Override public void onItemRangeChanged(int start, int count) { notifyItemRangeChanged(channelStart() + start, count); }
            @Override public void onItemRangeInserted(int start, int count) { notifyDataSetChanged(); }
            @Override public void onItemRangeRemoved(int start, int count) { notifyDataSetChanged(); }
            @Override public void onItemRangeMoved(int from, int to, int count) { notifyDataSetChanged(); }
        });
    }

    /** The dock row, or every app when there are fewer: the channel rows follow it. */
    private int splitAt() { return Math.min(split, apps.appCount()); }

    /** Grid position of the first channel row. */
    int channelStart() { return splitAt(); }

    int channelCount() { return channels.getItemCount(); }

    /** Grid position of an app position (the library pill counts as the app position after the last app). */
    int toGrid(int app) {
        return app < splitAt() ? app : app + channels.getItemCount();
    }

    /** App position of a grid position, or RecyclerView.NO_POSITION for a channel row. */
    int toApp(int grid) {
        if (grid == RecyclerView.NO_POSITION || grid < splitAt()) return grid;
        int c = channels.getItemCount();
        return grid < splitAt() + c ? RecyclerView.NO_POSITION : grid - c;
    }

    boolean isChannelAt(int grid) {
        return grid >= splitAt() && grid < splitAt() + channels.getItemCount();
    }

    @Override public int getItemCount() { return apps.getItemCount() + channels.getItemCount(); }

    @Override
    public int getItemViewType(int position) {
        return isChannelAt(position) ? TYPE_CHANNEL : apps.getItemViewType(toApp(position));
    }

    @Override
    public long getItemId(int position) {
        // Channel ids come from the TV provider; keep them apart from the apps' component hashes.
        return isChannelAt(position) ? Long.MAX_VALUE - channels.getItemId(position - splitAt())
                : apps.getItemId(toApp(position));
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return viewType == TYPE_CHANNEL ? channels.onCreateViewHolder(parent, viewType)
                : apps.onCreateViewHolder(parent, viewType);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (isChannelAt(position)) channels.onBindViewHolder((ChannelsAdapter.RowHolder) holder, position - splitAt());
        else apps.onBindViewHolder(holder, toApp(position));
    }
}

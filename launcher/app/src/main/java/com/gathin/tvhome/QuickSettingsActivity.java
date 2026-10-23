package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The panel the remote's Settings button opens when {@code settings_key_panel} is on (HomeKeyService):
 * a glass card at the right edge, over Home or whatever app is playing. Translucent and in a task of its
 * own, so the app behind keeps running and Back returns to it. Pressing Settings again closes the panel.
 */
public final class QuickSettingsActivity extends Activity {

    /** android.settings.SOUND_SETTINGS opens the combined Display & Sound page; TV vendors answer this one. */
    private static final String ACTION_TV_SOUND = "com.android.tv.action.VIEW_SOUND_SETTINGS";

    private float density;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_quick_settings);
        density = getResources().getDisplayMetrics().density;
        findViewById(R.id.scrim).setOnClickListener(v -> finish());
        LinearLayout rows = findViewById(R.id.rows);
        if (addInputs(rows)) addSeparator(rows);
        // Picture takes the focus, so Settings then OK lands where the gear went before. The TV's full
        // Settings last, under a rule. Launcher settings stay on Home (hold OK on the Settings pill).
        View picture = addRow(rows, R.drawable.ic_picture, getString(R.string.quick_picture), null,
                SystemSettings.intent(this, Settings.ACTION_DISPLAY_SETTINGS));
        addRow(rows, R.drawable.ic_sound, getString(R.string.quick_sound), null, soundSettings());
        addRow(rows, R.drawable.ic_wifi, getString(R.string.quick_network), null,
                SystemSettings.intent(this, Settings.ACTION_WIFI_SETTINGS));
        addRow(rows, R.drawable.ic_apps, getString(R.string.quick_apps), null,
                SystemSettings.intent(this, Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS));
        addSeparator(rows);
        addRow(rows, R.drawable.ic_settings, getString(R.string.tv_settings), null,
                SystemSettings.intent(this, Settings.ACTION_SETTINGS));
        picture.requestFocus();
        slideIn(findViewById(R.id.panel), reducedMotion());
    }

    /** Settings pressed again while the panel is up. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        finish();
    }

    /** Anything else taking the screen (Home, an app, a screensaver) closes the panel. */
    @Override
    protected void onPause() {
        super.onPause();
        finish();
        overridePendingTransition(0, 0);
    }

    /**
     * The HDMI ports under an "Inputs" label, named as in Settings > Inputs ("PC", HDMI 1 under it), with
     * the state when something is plugged in. Opens them the way app_keys' "input:" targets do. A short
     * version of the Home Inputs menu: no CEC device rows, AV jacks or vendor source panels.
     */
    private boolean addInputs(LinearLayout rows) {
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        if (tim == null) return false;
        List<TvInputInfo> all = tim.getTvInputList();
        Set<String> ids = new HashSet<>();
        for (TvInputInfo info : all) ids.add(info.getId());
        List<TvInputInfo> ports = new ArrayList<>();
        for (TvInputInfo info : all) {
            if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI || info.isHidden(this)) continue;
            if (info.getParentId() != null && ids.contains(info.getParentId())) continue;
            ports.add(info);
        }
        if (ports.isEmpty()) return false;
        ports.sort((a, b) -> String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this))));
        int connected = 0;
        for (TvInputInfo info : ports) if (tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED) connected++;
        // Some TVs call every port connected whatever is plugged in; the state means nothing then.
        boolean stateBogus = ports.size() > 1 && connected == ports.size();

        addLabel(rows, R.string.inputs);
        for (TvInputInfo info : ports) {
            CharSequence l = info.loadLabel(this);
            String port = l == null ? info.getId() : l.toString();
            if (!port.toUpperCase(Locale.ROOT).contains("HDMI")) port = "HDMI " + port;
            CharSequence custom = info.loadCustomLabel(this);
            boolean named = custom != null && custom.length() > 0 && !custom.toString().equals(port);
            View row = addRow(rows, R.drawable.ic_input, named ? custom.toString() : port, named ? port : null,
                    null);
            String id = info.getId();
            row.setOnClickListener(v -> {
                RemoteKeys.launch(this, "input:" + id);
                finish();
            });
            if (!stateBogus && tim.getInputState(id) == TvInputManager.INPUT_STATE_CONNECTED) {
                TextView status = row.findViewById(R.id.status);
                status.setText(R.string.connected);
                status.setVisibility(View.VISIBLE);
                row.findViewById(R.id.dot).setVisibility(View.VISIBLE);
                row.findViewById(R.id.subRow).setVisibility(View.VISIBLE);
                if (!named) ((ViewGroup.MarginLayoutParams) row.findViewById(R.id.dot).getLayoutParams()).setMarginStart(0);
            }
        }
        return true;
    }

    private Intent soundSettings() {
        Intent tv = new Intent(ACTION_TV_SOUND);
        if (getPackageManager().resolveActivity(tv, PackageManager.MATCH_DEFAULT_ONLY) != null) return tv;
        return SystemSettings.intent(this, Settings.ACTION_SOUND_SETTINGS);
    }

    /**
     * reduced_motion from config.xml or the theme. Config.load would also list the wallpapers folder and rewrite its
     * copy of config.json, on the main thread, before this panel over a playing app draws its first frame.
     */
    private boolean reducedMotion() {
        boolean def = getResources().getBoolean(R.bool.reduced_motion);
        org.json.JSONObject json = Config.theme(this);
        Object v = json == null ? null : json.opt("reduced_motion");
        return v instanceof Boolean ? (Boolean) v : def;
    }

    /** Fade in while sliding a little way in from the right edge. */
    private void slideIn(View panel, boolean reduced) {
        panel.setAlpha(0f);
        panel.setTranslationX(24 * density);
        panel.animate().alpha(1f).translationX(0f).setDuration(reduced ? Motion.REDUCED_MS : 220)
                .setInterpolator(new DecelerateInterpolator(1.6f)).start();
    }

    /** A small section label, in the spaced capitals of the Home date line. */
    private void addLabel(LinearLayout rows, int text) {
        TextView label = new TextView(this);
        label.setText(getString(text).toUpperCase(Locale.getDefault()));
        label.setTextColor(0x99FFFFFF);
        label.setTextSize(11);
        label.setLetterSpacing(0.12f);
        label.setTypeface(getResources().getFont(R.font.bevietnam_medium));
        label.setIncludeFontPadding(false);
        int h = (int) (12 * density);
        label.setPadding(h, (int) (6 * density), h, (int) (6 * density));
        rows.addView(label);
    }

    private void addSeparator(LinearLayout rows) {
        // No width of its own: the panel wraps its rows, and a plain View would claim the whole screen.
        View line = new View(this) {
            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                boolean exact = MeasureSpec.getMode(widthSpec) == MeasureSpec.EXACTLY;
                setMeasuredDimension(exact ? MeasureSpec.getSize(widthSpec) : 0, (int) density);
            }
        };
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
        int m = (int) (6 * density), h = (int) (12 * density);
        lp.setMargins(h, m, h, m);
        rows.addView(line, lp);
    }

    /** A row with the title alone, centred on its icon, or with a smaller line under it. */
    private View addRow(LinearLayout rows, int icon, String title, String sub, Intent target) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        row.findViewById(R.id.status).setVisibility(View.GONE);
        if (sub != null) ((TextView) row.findViewById(R.id.sub)).setText(sub);
        else row.findViewById(R.id.subRow).setVisibility(View.GONE);
        if (target != null) row.setOnClickListener(v -> {
            try {
                startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException | SecurityException e) {
                Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
            }
            finish();
        });
        rows.addView(row);
        return row;
    }
}

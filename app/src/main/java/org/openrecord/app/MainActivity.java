package org.openrecord.app;

import android.app.*;
import android.content.*;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;

/** Settings stay here; the Quick Settings tile opens CaptureActivity directly. */
public class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private Button record;
    private Spinner audio, quality;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            boolean active = RecordingService.active;
            long seconds = active ? Math.max(0, (SystemClock.elapsedRealtime() - RecordingService.startedAt) / 1000) : 0;
            status.setText(RecordingService.countingDown ? "Starting in 3 seconds…" : active ? String.format(java.util.Locale.ROOT, "Recording · %02d:%02d", seconds / 60, seconds % 60) : "Ready to record");
            record.setText(RecordingService.countingDown ? "Cancel recording" : active ? "Stop recording" : "Start recording");
            audio.setEnabled(!active); quality.setEnabled(!active);
            handler.postDelayed(this, 500);
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(0);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));
        scroll.addView(root); setContentView(scroll);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                v.setPadding(dp(24) + bars.left, dp(24) + bars.top, dp(24) + bars.right, dp(24) + bars.bottom);
            } else v.setPadding(dp(24) + insets.getSystemWindowInsetLeft(), dp(24) + insets.getSystemWindowInsetTop(), dp(24) + insets.getSystemWindowInsetRight(), dp(24) + insets.getSystemWindowInsetBottom());
            return insets;
        });
        TextView title = text(root, "OpenRecord", 28); title.setTypeface(null, Typeface.BOLD);
        status = text(root, "Ready to record", 14);
        space(root, 24);
        text(root, "Audio", 14);
        audio = spinner(root, new String[]{"Device audio", "Microphone", "Device audio + microphone", "No audio"});
        text(root, "Quality", 14);
        quality = spinner(root, new String[]{"1080p · 30 fps", "720p · 30 fps"});
        SharedPreferences preferences = getSharedPreferences("settings", 0);
        audio.setSelection(preferences.getInt("audio", 0)); quality.setSelection(preferences.getInt("quality", 0));
        AdapterView.OnItemSelectedListener save = new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                preferences.edit().putInt("audio", audio.getSelectedItemPosition()).putInt("quality", quality.getSelectedItemPosition()).apply();
            }
            public void onNothingSelected(AdapterView<?> parent) {}
        };
        audio.setOnItemSelectedListener(save); quality.setOnItemSelectedListener(save);
        space(root, 16);
        record = button(root, "Start recording");
        record.setOnClickListener(v -> {
            if (RecordingService.active) startService(new Intent(this, RecordingService.class).setAction(RecordingService.STOP));
            else startActivityForResult(new Intent(this, CaptureActivity.class), 20);
        });
        button(root, "Add to Quick Settings").setOnClickListener(v -> addTile());
        button(root, "Live notification settings").setOnClickListener(v -> {
            Intent i = Build.VERSION.SDK_INT >= 36
                ? new Intent("android.settings.MANAGE_APP_PROMOTED_NOTIFICATIONS").putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())
                : notificationSettings();
            try { startActivity(i); } catch (ActivityNotFoundException e) { startActivity(notificationSettings()); }
        });
        space(root, 16);
        text(root, "The Quick Settings tile starts recording with these settings. Stop from the tile or the live notification.\n\nVideos are saved to Movies/OpenRecord. Some apps block screen or audio capture.", 13);
    }
    private Intent notificationSettings() { return new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()); }
    private void addTile() {
        if (Build.VERSION.SDK_INT >= 33) {
            getSystemService(StatusBarManager.class).requestAddTileService(new ComponentName(this, RecordTileService.class),
                "Screen recording", Icon.createWithResource(this, R.drawable.ic_record), getMainExecutor(), result -> {
                    if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED || result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED)
                        Toast.makeText(this, "Tile ready. Open Quick Settings to start recording.", Toast.LENGTH_LONG).show();
                });
        } else new AlertDialog.Builder(this).setTitle("Add tile").setMessage("Open Quick Settings, tap Edit, then drag Screen recording into your tiles.").setPositiveButton("OK", null).show();
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 20 && result == RESULT_OK) moveTaskToBack(true);
    }
    @Override protected void onResume() {
        super.onResume(); handler.post(refresh);
        String error = getSharedPreferences("state", 0).getString("error", null);
        if (error != null) { new AlertDialog.Builder(this).setTitle("Recording failed").setMessage(error).setPositiveButton("OK", null).show(); getSharedPreferences("state", 0).edit().remove("error").apply(); }
    }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density); }
    private void space(LinearLayout root, int height) { root.addView(new View(this), new LinearLayout.LayoutParams(1, dp(height))); }
    private TextView text(LinearLayout root, String value, int size) { TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setPadding(0, dp(4), 0, dp(4)); root.addView(t); return t; }
    private Spinner spinner(LinearLayout root, String[] options) { Spinner s = new Spinner(this); s.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, options)); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(56)); p.bottomMargin = dp(12); root.addView(s, p); return s; }
    private Button button(LinearLayout root, String title) { Button b = new Button(this); b.setText(title); b.setAllCaps(false); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(56)); p.topMargin = dp(6); root.addView(b, p); return b; }
}

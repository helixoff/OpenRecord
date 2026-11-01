package org.openrecord.app;

import android.Manifest;
import android.app.*;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.*;
import android.widget.Toast;

/** Transparent entry point: the tile shows only Android's capture chooser. */
public class CaptureActivity extends Activity {
    private int mode, maxSize;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (RecordingService.active) { finish(); return; }
        android.content.SharedPreferences settings = getSharedPreferences("settings", 0);
        mode = settings.getInt("audio", 0);
        maxSize = settings.getInt("quality", 0) == 0 ? 1080 : 720;
        if (state != null) { mode = state.getInt("mode"); maxSize = state.getInt("maxSize"); return; }
        java.util.ArrayList<String> permissions = new java.util.ArrayList<>();
        if (mode != 3 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!permissions.isEmpty()) requestPermissions(permissions.toArray(new String[0]), 10); else capture();
    }
    @Override protected void onSaveInstanceState(Bundle state) { state.putInt("mode", mode); state.putInt("maxSize", maxSize); super.onSaveInstanceState(state); }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code != 10) return;
        if (mode != 3 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Allow audio recording or select No audio in OpenRecord.", Toast.LENGTH_LONG).show(); finish(); return;
        }
        capture();
    }
    private void capture() {
        try { startActivityForResult(getSystemService(MediaProjectionManager.class).createScreenCaptureIntent(), 20); }
        catch (Exception e) { Toast.makeText(this, "Could not open the screen picker.", Toast.LENGTH_LONG).show(); finish(); }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 20 && result == RESULT_OK && data != null) {
            try {
                startForegroundService(new Intent(this, RecordingService.class).setAction(RecordingService.START)
                    .putExtra("result", result).putExtra("data", data).putExtra("audio", mode).putExtra("maxSize", maxSize));
                setResult(RESULT_OK);
            } catch (Exception e) { Toast.makeText(this, "Could not start recording: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
        }
        finish();
    }
}

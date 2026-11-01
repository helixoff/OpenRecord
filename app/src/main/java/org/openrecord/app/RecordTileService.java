package org.openrecord.app;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.*;

public class RecordTileService extends TileService {
    @Override public void onStartListening() {
        Tile tile = getQsTile(); if (tile == null) return;
        tile.setState(RecordingService.active ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel(RecordingService.countingDown ? "Cancel recording" : RecordingService.active ? "Stop recording" : "Screen recording"); tile.updateTile();
    }
    // Intent overload is required on Android 10–13; PendingIntent was added in 14.
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    @Override public void onClick() {
        if (RecordingService.active) { startService(new Intent(this, RecordingService.class).setAction(RecordingService.STOP)); onStartListening(); return; }
        if (isLocked()) { unlockAndRun(this::launchCapture); return; }
        launchCapture();
    }
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    private void launchCapture() {
        Intent intent = new Intent(this, CaptureActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 7, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        else startActivityAndCollapse(intent);
    }
}

package org.openrecord.app;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.media.projection.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.service.quicksettings.TileService;
import android.widget.Toast;

public class RecordingService extends Service {
    public static final String START = "org.openrecord.START", STOP = "org.openrecord.STOP";
    public static volatile boolean active;
    public static volatile boolean countingDown;
    public static volatile long startedAt;
    private static final String CHANNEL = "recording";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaProjection projection;
    private RecorderEngine engine;
    private Uri output;
    private ParcelFileDescriptor descriptor;
    private boolean finishing;
    private Runnable pendingStart;
    private final MediaProjection.Callback callback = new MediaProjection.Callback() {
        @Override public void onStop() { finishRecording(); }
    };
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Screen recording", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Recording timer and stop button");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        if (STOP.equals(intent.getAction())) { finishRecording(); return START_NOT_STICKY; }
        if (!START.equals(intent.getAction()) || active || finishing) return START_NOT_STICKY;
        int mode = intent.getIntExtra("audio", 0);
        startedAt = SystemClock.elapsedRealtime() + 3000; active = true; countingDown = true;
        try {
            int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            if (Build.VERSION.SDK_INT >= 30 && (mode == 1 || mode == 2)) types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            startForeground(1, notification(), types);
            Intent grant = Build.VERSION.SDK_INT >= 33 ? intent.getParcelableExtra("data", Intent.class) : intent.getParcelableExtra("data");
            projection = getSystemService(MediaProjectionManager.class).getMediaProjection(intent.getIntExtra("result", Activity.RESULT_OK), grant);
            projection.registerCallback(callback, handler);
            int maxSize = intent.getIntExtra("maxSize", 1080);
            pendingStart = () -> beginRecording(mode, maxSize);
            handler.postDelayed(pendingStart, 3000);
            TileService.requestListeningState(this, new ComponentName(this, RecordTileService.class));
        } catch (Exception e) { complete(e); }
        return START_NOT_STICKY;
    }
    private void beginRecording(int mode, int maxSize) {
        if (finishing) return;
        countingDown = false; startedAt = SystemClock.elapsedRealtime();
        if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED)
            getSystemService(NotificationManager.class).notify(1, notification());
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, "OpenRecord_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.ROOT).format(new java.util.Date()) + ".mp4");
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/OpenRecord");
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
            output = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (output == null) throw new java.io.IOException("Could not create a video in the gallery.");
            descriptor = getContentResolver().openFileDescriptor(output, "rw");
            engine = new RecorderEngine(this, projection, descriptor.getFileDescriptor(), mode, maxSize);
            engine.start((error) -> handler.post(() -> complete(error)));
            TileService.requestListeningState(this, new ComponentName(this, RecordTileService.class));
        } catch (Exception e) { complete(e); }
    }
    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 2, new Intent(this, RecordingService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
            .setSmallIcon(org.openrecord.app.R.drawable.ic_record).setContentTitle(countingDown ? "Recording starts in 3 seconds" : "Recording screen")
            .setContentText(countingDown ? "Tap Cancel to stop the countdown" : "Tap Stop to save your video")
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(Build.VERSION.SDK_INT >= 31 ? Notification.CATEGORY_STOPWATCH : Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC).setContentIntent(open)
            .setWhen(System.currentTimeMillis() + (countingDown ? 3000 : 0)).setUsesChronometer(true).setChronometerCountDown(countingDown)
            .addAction(new Notification.Action.Builder(null, countingDown ? "Cancel" : "Stop", stop).build());
        if (Build.VERSION.SDK_INT >= 36) {
            // Same request key as AndroidX; system decides whether to promote it.
            b.getExtras().putBoolean("android.requestPromotedOngoing", true);
        }
        return b.build();
    }
    private void finishRecording() {
        if (finishing) return;
        finishing = true;
        if (pendingStart != null) handler.removeCallbacks(pendingStart);
        if (engine != null) engine.stop(); else complete(null);
    }
    private void complete(Exception error) {
        finishing = true;
        active = false; countingDown = false;
        if (pendingStart != null) handler.removeCallbacks(pendingStart);
        boolean hadOutput = output != null;
        if (projection != null) { projection.unregisterCallback(callback); projection.stop(); projection = null; }
        if (descriptor != null) { try { descriptor.close(); } catch (Exception ignored) {} descriptor = null; }
        if (output != null) {
            try {
                if (error == null) { ContentValues ready = new ContentValues(); ready.put(MediaStore.Video.Media.IS_PENDING, 0); getContentResolver().update(output, ready, null, null); }
                else getContentResolver().delete(output, null, null);
            } catch (Exception e) { error = e; }
            output = null;
        }
        if (error != null) {
            android.util.Log.e("OpenRecord", "Recording failed", error);
            String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            getSharedPreferences("state", 0).edit().putString("error", message).apply();
            Toast.makeText(this, "Recording failed: " + message, Toast.LENGTH_LONG).show();
        } else if (hadOutput) {
            String warning = engine == null ? null : engine.audioWarning();
            if (warning != null) getSharedPreferences("state", 0).edit().putString("warning", warning).apply();
            Toast.makeText(this, warning == null ? "Video saved to Movies/OpenRecord" : warning, Toast.LENGTH_LONG).show();
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        TileService.requestListeningState(this, new ComponentName(this, RecordTileService.class));
        stopSelf();
    }
    @Override public void onDestroy() {
        active = false; countingDown = false;
        if (pendingStart != null) handler.removeCallbacks(pendingStart);
        if (engine != null) engine.stop();
        super.onDestroy();
    }
}

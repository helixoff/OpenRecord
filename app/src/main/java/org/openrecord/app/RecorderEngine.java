package org.openrecord.app;

import android.content.Context;
import android.graphics.Rect;
import android.hardware.display.*;
import android.media.*;
import android.media.projection.MediaProjection;
import android.view.*;
import java.io.FileDescriptor;
import java.nio.ByteBuffer;
import java.util.ArrayList;

/** H.264 surface encoder + PCM capture / AAC encoder, muxed into a local MP4. */
final class RecorderEngine {
    interface Completion { void done(Exception error); }
    private static final int RATE = 48000, SAMPLES = 1024;
    private final Context context;
    private final MediaProjection projection;
    private final FileDescriptor file;
    private final int mode, maxSize;
    private MediaCodec video, audio;
    private MediaMuxer muxer;
    private Surface surface;
    private VirtualDisplay display;
    private AudioRecord internal, microphone;
    private volatile boolean stopping;
    private volatile Exception audioError;
    private volatile boolean deviceAudioDetected;
    private volatile long deviceAudioSamples;
    private Thread feeder;
    private int videoTrack = -1, audioTrack = -1;
    private boolean muxStarted;
    private long firstVideoPts = -1;
    private long lastVideoPts = -1, lastAudioPts = -1;
    private final ArrayList<Sample> pending = new ArrayList<>();
    private long pendingBytes;
    private volatile long lastOutputAt;
    private record Sample(boolean video, byte[] bytes, long pts, int flags) {}

    RecorderEngine(Context context, MediaProjection projection, FileDescriptor file, int mode, int maxSize) {
        this.context = context; this.projection = projection; this.file = file; this.mode = mode; this.maxSize = maxSize;
    }
    void start(Completion completion) {
        new Thread(() -> {
            Exception failure = null;
            try { prepare(); encode(); } catch (Exception e) { failure = e; }
            finally {
                stopping = true;
                if (internal != null) try { internal.stop(); } catch (Exception ignored) {}
                if (microphone != null) try { microphone.stop(); } catch (Exception ignored) {}
                if (feeder != null) try { feeder.join(3000); } catch (InterruptedException ignored) {}
                if (display != null) display.release();
                if (surface != null) surface.release();
                if (video != null) { try { video.stop(); } catch (Exception ignored) {} video.release(); }
                if (audio != null) { try { audio.stop(); } catch (Exception ignored) {} audio.release(); }
                if (internal != null) internal.release();
                if (microphone != null) microphone.release();
                if (muxer != null) {
                    if (muxStarted) try { muxer.stop(); } catch (Exception e) { if (failure == null) failure = e; }
                    else if (failure == null) failure = new java.io.IOException("Recording too short. Try recording for a few seconds.");
                    muxer.release();
                }
            }
            completion.done(failure);
        }, "OpenRecord-encoder").start();
    }
    void stop() { stopping = true; }
    String audioWarning() {
        return (mode == 0 || mode == 2) && deviceAudioSamples >= RATE * 3L && !deviceAudioDetected
            ? "Video saved, but no device audio was detected. If sound was playing, check media volume and try again without Bluetooth headphones. The playing app or Android firmware may block playback capture."
            : null;
    }

    private void prepare() throws Exception {
        WindowManager windows = context.getSystemService(WindowManager.class);
        Rect bounds;
        if (android.os.Build.VERSION.SDK_INT >= 30) bounds = windows.getMaximumWindowMetrics().getBounds();
        else { android.util.DisplayMetrics metrics = new android.util.DisplayMetrics(); windows.getDefaultDisplay().getRealMetrics(metrics); bounds = new Rect(0, 0, metrics.widthPixels, metrics.heightPixels); }
        double scale = Math.min(1.0, (double) maxSize / Math.min(bounds.width(), bounds.height()));
        int width = Math.max(16, ((int)(bounds.width() * scale) / 16) * 16);
        int height = Math.max(16, ((int)(bounds.height() * scale) / 16) * 16);
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, maxSize == 1080 ? 8_000_000 : 4_000_000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 60);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        video = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        video.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        surface = video.createInputSurface(); video.start();
        muxer = new MediaMuxer(file, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        if (mode != 3) {
            if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                throw new SecurityException("Audio recording permission is required.");
            MediaFormat af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 1);
            af.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            af.setInteger(MediaFormat.KEY_BIT_RATE, 128000); af.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, SAMPLES * 2);
            audio = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            audio.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); audio.start();
            AudioFormat pcm = new AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build();
            int buffer = Math.max(AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 4, 16384);
            if (mode == 0 || mode == 2) {
                AudioPlaybackCaptureConfiguration capture = new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME).addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build();
                internal = new AudioRecord.Builder().setAudioFormat(pcm).setBufferSizeInBytes(buffer).setAudioPlaybackCaptureConfig(capture).build();
                checkAudio(internal);
            }
            if (mode == 1 || mode == 2) {
                microphone = new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC).setAudioFormat(pcm).setBufferSizeInBytes(buffer).build();
                checkAudio(microphone);
            }
        }
        display = projection.createVirtualDisplay("OpenRecord", width, height, context.getResources().getDisplayMetrics().densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null);
        // Start capture only after the projection's display session is active.
        if (internal != null) startAudio(internal);
        if (microphone != null) startAudio(microphone);
        if (audio != null) { feeder = new Thread(this::feedAudio, "OpenRecord-PCM"); feeder.start(); }
    }
    private static void checkAudio(AudioRecord r) throws Exception {
        if (r.getState() != AudioRecord.STATE_INITIALIZED) throw new java.io.IOException("Could not open the selected audio source.");
    }
    private static void startAudio(AudioRecord source) throws Exception {
        source.startRecording();
        if (source.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
            throw new java.io.IOException("The selected audio source did not start. Check audio permission and try again.");
    }
    private void feedAudio() {
        short[] system = new short[SAMPLES], mic = new short[SAMPLES];
        long samples = 0;
        try {
            while (!stopping) {
                if (internal != null) {
                    readFully(internal, system);
                    for (short value : system) if (value != 0) { deviceAudioDetected = true; break; }
                    deviceAudioSamples += SAMPLES;
                }
                if (microphone != null) readFully(microphone, mic);
                int index;
                do { index = audio.dequeueInputBuffer(10000); } while (index < 0 && !stopping);
                if (index < 0) break;
                ByteBuffer input = audio.getInputBuffer(index); input.clear(); input.order(java.nio.ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < SAMPLES; i++) {
                    int value = mode == 2 ? Math.round(system[i] * 0.65f + mic[i] * 0.65f) : mode == 0 ? system[i] : mic[i];
                    input.putShort((short)Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value)));
                }
                audio.queueInputBuffer(index, 0, SAMPLES * 2, samples * 1_000_000 / RATE, 0);
                samples += SAMPLES;
            }
        } catch (Exception e) { if (!stopping) audioError = e; }
        finally {
            // Revoking MediaProjection can make AudioRecord.read throw instead of
            // returning EOF. AAC must receive EOS even on that shutdown path.
            try {
                int end; long deadline = System.nanoTime() + 2_000_000_000L;
                do { end = audio.dequeueInputBuffer(10000); } while (end < 0 && System.nanoTime() < deadline);
                if (end >= 0) audio.queueInputBuffer(end, 0, 0, samples * 1_000_000 / RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            } catch (Exception e) { if (!stopping) audioError = e; }
        }
    }
    private void readFully(AudioRecord source, short[] data) throws Exception {
        int count = 0;
        while (count < data.length && !stopping) {
            int read = source.read(data, count, data.length - count, AudioRecord.READ_BLOCKING);
            if (read < 0) throw new java.io.IOException("Audio read failed: " + read);
            count += read;
        }
    }
    private void encode() throws Exception {
        boolean sentEnd = false, videoEnd = false, audioEnd = audio == null;
        long deadline = Long.MAX_VALUE;
        while (!videoEnd || !audioEnd) {
            if (audioError != null) throw audioError;
            if (stopping && !sentEnd) {
                try { video.signalEndOfInputStream(); } catch (IllegalStateException ignored) { /* Surface already revoked by Android. */ }
                sentEnd = true; deadline = System.nanoTime() + 6_000_000_000L;
                lastOutputAt = System.nanoTime();
            }
            if (!videoEnd) videoEnd = drain(video, true);
            if (!audioEnd) audioEnd = drain(audio, false);
            // Some vendor codecs never emit video EOS after the system revokes
            // the display. Once buffered samples are drained, finalize the MP4.
            if (sentEnd && muxStarted && (feeder == null || !feeder.isAlive()) && System.nanoTime() - lastOutputAt > 800_000_000L) break;
            if (System.nanoTime() > deadline) {
                if (muxStarted) break;
                throw new java.io.IOException("Could not finish the video stream.");
            }
            Thread.sleep(3);
        }
    }
    private boolean drain(MediaCodec codec, boolean isVideo) throws Exception {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int index = codec.dequeueOutputBuffer(info, 0);
        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            lastOutputAt = System.nanoTime();
            if (isVideo) videoTrack = muxer.addTrack(codec.getOutputFormat()); else audioTrack = muxer.addTrack(codec.getOutputFormat());
            if (!muxStarted && videoTrack >= 0 && (audio == null || audioTrack >= 0)) {
                muxer.start(); muxStarted = true;
                for (Sample sample : pending) write(sample);
                pending.clear(); pendingBytes = 0;
            }
        } else if (index >= 0) {
            lastOutputAt = System.nanoTime();
            try {
                if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    ByteBuffer buffer = codec.getOutputBuffer(index); buffer.position(info.offset); buffer.limit(info.offset + info.size);
                    byte[] bytes = new byte[info.size]; buffer.get(bytes);
                    long pts = info.presentationTimeUs;
                    if (isVideo) { if (firstVideoPts < 0) firstVideoPts = pts; pts -= firstVideoPts; }
                    Sample sample = new Sample(isVideo, bytes, pts, info.flags);
                    if (muxStarted) write(sample);
                    else { pending.add(sample); pendingBytes += bytes.length; if (pendingBytes > 32_000_000) throw new java.io.IOException("Could not start the audio stream."); }
                }
            } finally { codec.releaseOutputBuffer(index, false); }
            return (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
        }
        return false;
    }
    private void write(Sample s) {
        long pts = Math.max(s.pts, (s.video ? lastVideoPts : lastAudioPts) + 1);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo(); info.set(0, s.bytes.length, pts, s.flags);
        muxer.writeSampleData(s.video ? videoTrack : audioTrack, ByteBuffer.wrap(s.bytes), info);
        if (s.video) lastVideoPts = pts; else lastAudioPts = pts;
    }
}

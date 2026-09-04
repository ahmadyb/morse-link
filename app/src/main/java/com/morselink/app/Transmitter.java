package com.morselink.app;

import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;

import java.util.ArrayList;
import java.util.List;

/**
 * Keys a message out through the torch, the speaker, the screen and the vibrator.
 * Runs on its own thread; every callback is delivered on the main thread.
 */
public final class Transmitter {

    public static final int CH_TORCH = 1;
    public static final int CH_SOUND = 2;
    public static final int CH_SCREEN = 4;
    public static final int CH_VIBRATE = 8;

    private static final int SAMPLE_RATE = 44100;
    private static final long MAX_TOTAL_MS = 60_000L;
    private static final int FADE_MS = 5;
    private static final int MIN_MARK_MS = 15;

    public interface Callback {
        /** Called when transmission starts; {@code morse} is the plain '.'/'-' form. */
        void onStarted(String morse, long totalMs, boolean truncated);

        /** Called for every key down / key up. {@code morseIndex} is -1 for key up. */
        void onTick(int morseIndex, boolean on);

        /** Called once the transmission finished or was stopped. */
        void onStopped();

        /** Non fatal problem worth telling the user about (missing torch, ...). */
        void onError(String message);
    }

    /** One element of a transmission: key down for {@code ms}, or silence for {@code ms}. */
    public static final class Ev {
        public final boolean on;
        public final long ms;
        public final int morseIndex;

        Ev(boolean on, long ms, int morseIndex) {
            this.on = on;
            this.ms = ms;
            this.morseIndex = morseIndex;
        }
    }

    /** A fully expanded transmission: the morse text plus the flat on/off timeline. */
    public static final class Plan {
        public final String morse;
        public final List<Ev> events;
        public final long totalMs;
        public final boolean truncated;

        Plan(String morse, List<Ev> events, long totalMs, boolean truncated) {
            this.morse = morse;
            this.events = events;
            this.totalMs = totalMs;
            this.truncated = truncated;
        }
    }

    private final Context appContext;
    private final Callback callback;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CameraManager cameraManager;
    private final Vibrator vibrator;

    private String torchCameraId;
    private boolean torchUnavailable;
    private volatile boolean running;
    private Thread thread;
    private AudioTrack track;

    private int channels = CH_TORCH | CH_SOUND;
    private int wpm = 15;
    private int toneHz = 600;
    private boolean loop;

    public Transmitter(Context context, Callback callback) {
        this.appContext = context.getApplicationContext();
        this.callback = callback;
        this.cameraManager = (CameraManager) appContext.getSystemService(Context.CAMERA_SERVICE);
        Vibrator vib = (Vibrator) appContext.getSystemService(Context.VIBRATOR_SERVICE);
        this.vibrator = vib != null && vib.hasVibrator() ? vib : null;
    }

    public void configure(int channels, int wpm, int toneHz, boolean loop) {
        this.channels = channels;
        this.wpm = wpm;
        this.toneHz = toneHz;
        this.loop = loop;
    }

    public boolean isRunning() {
        return running;
    }

    public boolean torchAvailable() {
        return findTorchCamera() != null;
    }

    /** Builds the on/off timeline for {@code text} at the given speed. */
    public static Plan plan(String text, int wpm) {
        long unit = MorseCodec.dotMillis(wpm);
        StringBuilder morse = new StringBuilder();
        List<Ev> events = new ArrayList<>();
        String trimmed = text == null ? "" : text.trim();
        String[] words = trimmed.isEmpty() ? new String[0] : trimmed.split("\\s+");
        for (String word : words) {
            for (int i = 0; i < word.length(); i++) {
                String code = MorseCodec.code(word.charAt(i));
                if (code == null) {
                    continue;
                }
                for (int j = 0; j < code.length(); j++) {
                    int index = morse.length();
                    morse.append(code.charAt(j));
                    events.add(new Ev(true, code.charAt(j) == '-' ? unit * 3 : unit, index));
                    events.add(new Ev(false, unit, index));
                }
                morse.append(' ');
                events.add(new Ev(false, unit * 2, -1));
            }
            if (morse.length() > 0) {
                morse.append("/ ");
            }
            events.add(new Ev(false, unit * 4, -1));
        }

        long total = 0;
        boolean truncated = false;
        List<Ev> kept = new ArrayList<>(events.size());
        int lastIndex = 0;
        for (Ev ev : events) {
            if (total + ev.ms > MAX_TOTAL_MS) {
                truncated = true;
                break;
            }
            total += ev.ms;
            kept.add(ev);
            if (ev.morseIndex >= 0) {
                lastIndex = ev.morseIndex;
            }
        }
        String morseOut = morse.length() == 0 ? "" : morse.substring(0, Math.min(morse.length(), lastIndex + 1));
        return new Plan(morseOut, kept, total, truncated);
    }

    public void start(String text) {
        stop();
        final Plan plan = plan(text, wpm);
        if (plan.events.isEmpty()) {
            return;
        }
        running = true;
        thread = new Thread(() -> run(plan), "morselink-tx");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        Thread t = thread;
        thread = null;
        if (t != null) {
            t.interrupt();
        }
        releaseTrack();
        torch(false);
        vibratorCancel();
    }

    private void run(Plan plan) {
        try {
            final short[] wave = (channels & CH_SOUND) != 0 ? render(plan) : null;
            notifyStarted(plan);
            int pass = 0;
            while (running) {
                if (wave != null) {
                    releaseTrack();
                    track = buildTrack(wave);
                    if (track != null) {
                        track.play();
                    }
                }
                long startNs = System.nanoTime();
                long elapsed = 0;
                for (Ev ev : plan.events) {
                    long target = elapsed + ev.ms;
                    if (ev.on) {
                        keyDown(ev);
                    }
                    sleepUntil(startNs + target * 1_000_000L);
                    if (!running) {
                        break;
                    }
                    if (ev.on) {
                        keyUp(ev);
                    }
                    elapsed = target;
                }
                releaseTrack();
                pass++;
                if (!loop || !running) {
                    break;
                }
            }
        } catch (InterruptedException ignored) {
            // stopped on purpose
        } catch (Throwable t) {
            notifyError(t.getMessage());
        } finally {
            releaseTrack();
            torch(false);
            vibratorCancel();
            running = false;
            notifyStopped();
        }
    }

    private void keyDown(Ev ev) {
        if ((channels & CH_TORCH) != 0) {
            torch(true);
        }
        if ((channels & CH_VIBRATE) != 0) {
            vibrate(ev.ms);
        }
        notifyTick(ev.morseIndex, true);
    }

    private void keyUp(Ev ev) {
        if ((channels & CH_TORCH) != 0) {
            torch(false);
        }
        notifyTick(-1, false);
    }

    private void sleepUntil(long targetNs) throws InterruptedException {
        long remaining = targetNs - System.nanoTime();
        while (remaining > 0 && running) {
            Thread.sleep(remaining / 1_000_000L, (int) (remaining % 1_000_000L));
            remaining = targetNs - System.nanoTime();
        }
    }

    private void torch(boolean on) {
        if (torchUnavailable) {
            return;
        }
        String id = findTorchCamera();
        if (id == null) {
            if (on) {
                torchUnavailable = true;
                notifyError("no_torch");
            }
            return;
        }
        try {
            cameraManager.setTorchMode(id, on);
        } catch (Throwable t) {
            torchUnavailable = true;
            notifyError(t.getMessage());
        }
    }

    private String findTorchCamera() {
        if (torchCameraId != null) {
            return torchCameraId;
        }
        try {
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics chars = cameraManager.getCameraCharacteristics(id);
                Boolean available = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (Boolean.TRUE.equals(available)) {
                    torchCameraId = id;
                    return id;
                }
            }
        } catch (Throwable ignored) {
            // no camera service on this device
        }
        return null;
    }

    private void vibrate(long ms) {
        if (vibrator == null) {
            return;
        }
        try {
            vibrator.vibrate(VibrationEffect.createOneShot(
                    Math.max(MIN_MARK_MS, (int) ms), VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Throwable ignored) {
            // vibration is a nice-to-have
        }
    }

    private void vibratorCancel() {
        if (vibrator == null) {
            return;
        }
        try {
            vibrator.cancel();
        } catch (Throwable ignored) {
            // ignore
        }
    }

    private short[] render(Plan plan) {
        int totalSamples = (int) (plan.totalMs * SAMPLE_RATE / 1000L);
        short[] out = new short[Math.max(1, totalSamples)];
        int cursor = 0;
        for (Ev ev : plan.events) {
            int len = (int) (ev.ms * SAMPLE_RATE / 1000L);
            if (!ev.on || len <= 0) {
                cursor += len;
                continue;
            }
            int fade = Math.min(len / 2, FADE_MS * SAMPLE_RATE / 1000);
            for (int i = 0; i < len && cursor + i < out.length; i++) {
                double envelope = 1.0;
                if (fade > 0) {
                    if (i < fade) {
                        envelope = i / (double) fade;
                    } else if (i > len - fade) {
                        envelope = (len - i) / (double) fade;
                    }
                }
                double phase = 2.0 * Math.PI * toneHz * i / SAMPLE_RATE;
                out[cursor + i] = (short) (Math.sin(phase) * envelope * Short.MAX_VALUE * 0.7);
            }
            cursor += len;
        }
        return out;
    }

    private AudioTrack buildTrack(short[] wave) {
        try {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            AudioFormat format = new AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build();
            AudioTrack local = new AudioTrack(attrs, format, wave.length * 2,
                    AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE);
            local.write(wave, 0, wave.length);
            return local;
        } catch (Throwable t) {
            notifyError(t.getMessage());
            return null;
        }
    }

    private void releaseTrack() {
        AudioTrack local = track;
        track = null;
        if (local == null) {
            return;
        }
        try {
            local.stop();
        } catch (Throwable ignored) {
            // ignore
        }
        try {
            local.release();
        } catch (Throwable ignored) {
            // ignore
        }
    }

    private void notifyStarted(Plan plan) {
        main.post(() -> callback.onStarted(plan.morse, plan.totalMs, plan.truncated));
    }

    private void notifyTick(int index, boolean on) {
        main.post(() -> callback.onTick(index, on));
    }

    private void notifyStopped() {
        main.post(() -> callback.onStopped());
    }

    private void notifyError(String message) {
        final String safe = message == null ? "" : message;
        main.post(() -> callback.onError(safe));
    }
}

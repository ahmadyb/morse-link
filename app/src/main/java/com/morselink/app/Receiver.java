package com.morselink.app;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

/**
 * Listens to the microphone for an on/off keyed tone and turns it back into text.
 *
 * The detector is a Goertzel filter locked to a single frequency plus a small
 * timing state machine, which is why it likes clear, steady tones in a quiet room.
 */
public final class Receiver {

    private static final int SAMPLE_RATE = 8000;
    private static final int BLOCK = 80;               // 10 ms of audio
    private static final double MIN_UNIT_MS = 30.0;
    private static final double MAX_UNIT_MS = 400.0;
    private static final long MIN_MARK_MS = 15;
    private static final long LEVEL_INTERVAL_MS = 60;

    public interface Callback {
        void onLevel(int percent);

        void onMorseChanged(String morse, String text);

        void onError(String message);

        void onStopped();
    }

    private final Callback callback;
    private final Handler main = new Handler(Looper.getMainLooper());

    private AudioRecord recorder;
    private Thread thread;
    private volatile boolean running;

    public Receiver(Callback callback) {
        this.callback = callback;
    }

    public boolean isRunning() {
        return running;
    }

    public void start(int freqHz, int gainPercent) {
        stop();
        final int buffer = Math.max(AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), 2048);
        try {
            recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buffer);
        } catch (Throwable t) {
            notifyError(t.getMessage());
            return;
        }
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            recorder.release();
            recorder = null;
            notifyError("microphone unavailable");
            return;
        }
        running = true;
        thread = new Thread(() -> run(freqHz, gainPercent, buffer), "morselink-rx");
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
        AudioRecord local = recorder;
        recorder = null;
        if (local != null) {
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
    }

    private void run(int freqHz, int gainPercent, int bufferSize) {
        AudioRecord local = recorder;
        if (local == null) {
            return;
        }
        try {
            local.startRecording();
        } catch (Throwable t) {
            notifyError(t.getMessage());
            running = false;
            notifyStopped();
            return;
        }

        double unitMs = 60.0;
        double floorLevel = 0.05;
        double peakLevel = 0.0;
        long nowMs = 0;
        long lastChangeMs = 0;
        boolean lastOn = false;
        long lastLevelPost = 0;
        StringBuilder morse = new StringBuilder();
        short[] buf = new short[Math.max(bufferSize, BLOCK * 8)];
        final double thresholdFactor = 0.52 - (gainPercent / 100.0) * 0.44;

        try {
            while (running) {
                int read = local.read(buf, 0, buf.length);
                if (read <= 0) {
                    continue;
                }
                for (int offset = 0; offset + BLOCK <= read; offset += BLOCK) {
                    double mag = goertzel(buf, offset, BLOCK, freqHz) / (BLOCK / 2.0) / 32768.0;

                    if (mag > peakLevel) {
                        peakLevel = mag;
                    } else {
                        peakLevel += (mag - peakLevel) * 0.001;
                    }
                    if (mag < floorLevel) {
                        floorLevel += (mag - floorLevel) * 0.2;
                    } else {
                        floorLevel += (mag - floorLevel) * 0.005;
                    }
                    double spread = Math.max(peakLevel - floorLevel, 0.0005);
                    double level = (mag - floorLevel) / spread;
                    boolean on = level > thresholdFactor && spread > 0.002;

                    nowMs += BLOCK * 1000L / SAMPLE_RATE;

                    if (on != lastOn) {
                        long duration = nowMs - lastChangeMs;
                        if (lastOn) {
                            if (duration >= MIN_MARK_MS && duration < 2000) {
                                if (duration < unitMs * 1.8) {
                                    morse.append('.');
                                    unitMs = adapt(unitMs, duration);
                                } else {
                                    morse.append('-');
                                    unitMs = adapt(unitMs, duration / 3.0);
                                }
                                postMorse(morse);
                            }
                        } else if (duration >= MIN_MARK_MS) {
                            if (duration > unitMs * 4.5) {
                                appendSeparator(morse, true);
                                postMorse(morse);
                            } else if (duration > unitMs * 1.8) {
                                appendSeparator(morse, false);
                                postMorse(morse);
                            }
                        }
                        lastOn = on;
                        lastChangeMs = nowMs;
                    }

                    if (nowMs - lastLevelPost >= LEVEL_INTERVAL_MS) {
                        lastLevelPost = nowMs;
                        postLevel(on ? 100 : (int) Math.max(0.0, Math.min(100.0, level * 100.0)));
                    }
                }
            }
        } catch (Throwable t) {
            notifyError(t.getMessage());
        } finally {
            try {
                local.stop();
            } catch (Throwable ignored) {
                // ignore
            }
            running = false;
            notifyStopped();
        }
    }

    private static double adapt(double current, double observed) {
        double next = current * 0.8 + observed * 0.2;
        return Math.max(MIN_UNIT_MS, Math.min(MAX_UNIT_MS, next));
    }

    private static void appendSeparator(StringBuilder morse, boolean word) {
        while (morse.length() > 0 && morse.charAt(morse.length() - 1) == ' ') {
            morse.setLength(morse.length() - 1);
        }
        if (morse.length() == 0) {
            return;
        }
        char last = morse.charAt(morse.length() - 1);
        if (word) {
            if (last != '/') {
                morse.append(" / ");
            }
        } else if (last != '/') {
            morse.append(' ');
        }
    }

    /** Goertzel magnitude for a single frequency bin. */
    private static double goertzel(short[] data, int offset, int n, double freq) {
        double k = Math.round((double) n * freq / SAMPLE_RATE);
        double w = 2.0 * Math.PI * k / n;
        double cosW = Math.cos(w);
        double sinW = Math.sin(w);
        double coeff = 2.0 * cosW;
        double s1 = 0.0;
        double s2 = 0.0;
        for (int i = 0; i < n; i++) {
            double s0 = data[offset + i] + coeff * s1 - s2;
            s2 = s1;
            s1 = s0;
        }
        double real = s1 - s2 * cosW;
        double imag = s2 * sinW;
        return Math.sqrt(real * real + imag * imag);
    }

    private void postMorse(StringBuilder morse) {
        final String raw = morse.toString();
        main.post(() -> callback.onMorseChanged(raw, MorseCodec.decode(raw)));
    }

    private void postLevel(int percent) {
        main.post(() -> callback.onLevel(percent));
    }

    private void notifyError(String message) {
        final String safe = message == null ? "" : message;
        main.post(() -> callback.onError(safe));
    }

    private void notifyStopped() {
        main.post(() -> callback.onStopped());
    }
}

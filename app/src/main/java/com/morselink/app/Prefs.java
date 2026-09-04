package com.morselink.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Tiny typed wrapper around the app's SharedPreferences. */
public final class Prefs {

    private static final String FILE = "morselink";

    private static final String KEY_WPM = "wpm";
    private static final String KEY_TONE = "tone";
    private static final String KEY_CHANNELS = "channels";
    private static final String KEY_LOOP = "loop";
    private static final String KEY_MESSAGE = "message";
    private static final String KEY_TAB = "tab";
    private static final String KEY_RECV_FREQ = "recv_freq";
    private static final String KEY_RECV_GAIN = "recv_gain";
    private static final String KEY_BEST_STREAK = "best_streak";

    private static final int DEFAULT_WPM = 15;
    private static final int DEFAULT_TONE = 600;
    private static final int DEFAULT_CHANNELS = Transmitter.CH_TORCH | Transmitter.CH_SOUND;
    private static final int DEFAULT_RECV_FREQ = 600;
    private static final int DEFAULT_RECV_GAIN = 40;

    private final SharedPreferences prefs;

    public Prefs(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public int wpm() {
        return prefs.getInt(KEY_WPM, DEFAULT_WPM);
    }

    public void wpm(int value) {
        prefs.edit().putInt(KEY_WPM, value).apply();
    }

    public int tone() {
        return prefs.getInt(KEY_TONE, DEFAULT_TONE);
    }

    public void tone(int value) {
        prefs.edit().putInt(KEY_TONE, value).apply();
    }

    public int channels() {
        return prefs.getInt(KEY_CHANNELS, DEFAULT_CHANNELS);
    }

    public void channels(int mask) {
        prefs.edit().putInt(KEY_CHANNELS, mask).apply();
    }

    public boolean loop() {
        return prefs.getBoolean(KEY_LOOP, false);
    }

    public void loop(boolean value) {
        prefs.edit().putBoolean(KEY_LOOP, value).apply();
    }

    public String message() {
        return prefs.getString(KEY_MESSAGE, "");
    }

    public void message(String value) {
        prefs.edit().putString(KEY_MESSAGE, value).apply();
    }

    public int tab() {
        return prefs.getInt(KEY_TAB, 0);
    }

    public void tab(int value) {
        prefs.edit().putInt(KEY_TAB, value).apply();
    }

    public int recvFreq() {
        return prefs.getInt(KEY_RECV_FREQ, DEFAULT_RECV_FREQ);
    }

    public void recvFreq(int value) {
        prefs.edit().putInt(KEY_RECV_FREQ, value).apply();
    }

    public int recvGain() {
        return prefs.getInt(KEY_RECV_GAIN, DEFAULT_RECV_GAIN);
    }

    public void recvGain(int value) {
        prefs.edit().putInt(KEY_RECV_GAIN, value).apply();
    }

    public int bestStreak() {
        return prefs.getInt(KEY_BEST_STREAK, 0);
    }

    public void bestStreak(int value) {
        prefs.edit().putInt(KEY_BEST_STREAK, value).apply();
    }
}

package com.morselink.app;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * International Morse code tables plus text/morse conversion helpers.
 * Pure Java - no Android dependencies, so it is trivially unit testable.
 */
public final class MorseCodec {

    public static final char DOT = '.';
    public static final char DASH = '-';
    public static final String WORD_SEP = "/";

    private static final String[] TABLE = {
            "A", ".-",      "B", "-...",    "C", "-.-.",    "D", "-..",
            "E", ".",       "F", "..-.",    "G", "--.",     "H", "....",
            "I", "..",      "J", ".---",    "K", "-.-",     "L", ".-..",
            "M", "--",      "N", "-.",      "O", "---",     "P", ".--.",
            "Q", "--.-",    "R", ".-.",     "S", "...",     "T", "-",
            "U", "..-",     "V", "...-",    "W", ".--",     "X", "-..-",
            "Y", "-.--",    "Z", "--..",
            "0", "-----",   "1", ".----",   "2", "..---",   "3", "...--",
            "4", "....-",   "5", ".....",   "6", "-....",   "7", "--...",
            "8", "---..",   "9", "----.",
            ".", ".-.-.-",  ",", "--..--",  "?", "..--..",  "'", ".----.",
            "!", "-.-.--",  ":", "---...",  ";", "-.-.-.",  "(", "-.--.",
            ")", "-.--.-",  "\"", ".-..-.", "-", "-....-",  "_", "..--.-",
            "+", ".-.-.",   "=", "-...-",   "$", "...-..-", "@", ".--.-.",
            "&", ".-...",   "/", "-..-."
    };

    private static final Map<Character, String> TO_MORSE = new HashMap<>();
    private static final Map<String, Character> TO_TEXT = new HashMap<>();
    private static final List<Entry> ENTRIES = new ArrayList<>();

    /** A single character and its Morse representation, used by the reference chart. */
    public static final class Entry {
        public final char character;
        public final String code;

        Entry(char character, String code) {
            this.character = character;
            this.code = code;
        }
    }

    static {
        for (int i = 0; i < TABLE.length; i += 2) {
            char ch = TABLE[i].charAt(0);
            String code = TABLE[i + 1];
            TO_MORSE.put(ch, code);
            TO_TEXT.put(code, ch);
            ENTRIES.add(new Entry(ch, code));
        }
    }

    private MorseCodec() {
    }

    /** Every character MorseLink knows how to encode, in table order. */
    public static List<Entry> entries() {
        return Collections.unmodifiableList(ENTRIES);
    }

    /** Morse for a single character, or {@code null} when it has no Morse equivalent. */
    public static String code(char c) {
        return TO_MORSE.get(Character.toUpperCase(c));
    }

    /** Encode text into Morse: letters separated by a space, words by " / ". */
    public static String encode(CharSequence text) {
        if (text == null) {
            return "";
        }
        String clean = stripAccents(text.toString()).toUpperCase();
        StringBuilder out = new StringBuilder();
        boolean pendingSpace = false;
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            if (c == ' ' || c == '\n' || c == '\t' || c == '\r') {
                pendingSpace = out.length() > 0;
                continue;
            }
            String code = TO_MORSE.get(c);
            if (code == null) {
                continue;
            }
            if (pendingSpace) {
                out.append(" / ");
                pendingSpace = false;
            } else if (out.length() > 0) {
                out.append(' ');
            }
            out.append(code);
        }
        return out.toString();
    }

    /** Decode Morse back into text. Unknown sequences become '?'. */
    public static String decode(CharSequence morse) {
        if (morse == null) {
            return "";
        }
        String normalized = normalize(morse.toString());
        StringBuilder out = new StringBuilder();
        StringBuilder token = new StringBuilder();
        for (int i = 0; i <= normalized.length(); i++) {
            char c = i < normalized.length() ? normalized.charAt(i) : ' ';
            if (c == '.' || c == '-') {
                token.append(c);
                continue;
            }
            if (c == '/' ) {
                flushToken(token, out);
                if (out.length() > 0 && out.charAt(out.length() - 1) != ' ') {
                    out.append(' ');
                }
                continue;
            }
            flushToken(token, out);
        }
        return out.toString().trim();
    }

    private static void flushToken(StringBuilder token, StringBuilder out) {
        if (token.length() > 0) {
            Character decoded = TO_TEXT.get(token.toString());
            out.append(decoded == null ? '?' : decoded);
            token.setLength(0);
        }
    }

    /** Turn anything that looks like a dot or a dash into the canonical '.' and '-'. */
    public static String normalize(String morse) {
        if (morse == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(morse.length());
        for (int i = 0; i < morse.length(); i++) {
            char c = morse.charAt(i);
            if (c == '.' || c == '\u00b7' || c == '\u2022' || c == '*' || c == '\u2027') {
                out.append('.');
            } else if (c == '-' || c == '_' || c == '\u2013' || c == '\u2014' || c == '\u2012') {
                out.append('-');
            } else if (c == '/' || c == '|' || c == '\n' || c == '\t' || c == ' ') {
                out.append(c == '|' ? '/' : c);
            }
            // everything else is dropped
        }
        return out.toString();
    }

    /** Pretty-print Morse with typographic dots and dashes. */
    public static String pretty(CharSequence morse) {
        if (morse == null) {
            return "";
        }
        String normalized = normalize(morse.toString());
        StringBuilder out = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (c == '.') {
                out.append('\u00b7');
            } else if (c == '-') {
                out.append('\u2014');
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** True when the string only contains Morse symbols and separators. */
    public static boolean isMorse(String s) {
        if (s == null || s.trim().isEmpty()) {
            return false;
        }
        return normalize(s).matches("[.\\-/\\s]+");
    }

    public static String stripAccents(String s) {
        String decomposed = Normalizer.normalize(s, Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
    }

    /** Length of one dot in milliseconds for the given speed, using the PARIS standard. */
    public static int dotMillis(int wpm) {
        int clamped = Math.max(1, wpm);
        return Math.round(1200.0f / clamped);
    }
}

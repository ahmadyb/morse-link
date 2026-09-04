package com.morselink.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Tests for the pure-Java Morse engine (no Android code involved). */
public class MorseCodecTest {

    @Test
    public void encodesLettersAndWords() {
        assertEquals("... --- ...", MorseCodec.encode("SOS"));
        assertEquals(".... . .-.. .-.. --- / .-- --- .-. .-.. -..", MorseCodec.encode("Hello World"));
        assertEquals("-.-. --.- --.-", MorseCodec.encode("CQQ"));
    }

    @Test
    public void encodesDigitsAndPunctuation() {
        assertEquals(".---- ..--- ...--", MorseCodec.encode("123"));
        assertEquals(".-.-.-", MorseCodec.encode("."));
        assertEquals("..--..", MorseCodec.encode("?"));
    }

    @Test
    public void lowerCaseAndAccentsAreNormalised() {
        assertEquals("... --- ...", MorseCodec.encode("sos"));
        assertEquals("... --- ...", MorseCodec.encode("SÖS"));
    }

    @Test
    public void unknownCharactersAreDropped() {
        assertEquals(".-", MorseCodec.encode("a€"));
    }

    @Test
    public void decodesMorse() {
        assertEquals("SOS", MorseCodec.decode("... --- ..."));
        assertEquals("HELLO WORLD", MorseCodec.decode(".... . .-.. .-.. --- / .-- --- .-. .-.. -.."));
    }

    @Test
    public void decodesTypographicSymbols() {
        assertEquals("SOS", MorseCodec.decode("\u00b7\u00b7\u00b7 \u2014\u2014\u2014 \u00b7\u00b7\u00b7"));
        assertEquals("SOS", MorseCodec.decode("*** ___ ***"));
    }

    @Test
    public void roundTripsEveryCharacter() {
        for (MorseCodec.Entry entry : MorseCodec.entries()) {
            String encoded = MorseCodec.encode(String.valueOf(entry.character));
            assertEquals("code for " + entry.character, entry.code, encoded);
            assertEquals("decode " + entry.code, String.valueOf(entry.character),
                    MorseCodec.decode(entry.code));
        }
    }

    @Test
    public void everyCodeIsUnique() {
        java.util.Set<String> codes = new java.util.HashSet<>();
        for (MorseCodec.Entry entry : MorseCodec.entries()) {
            assertTrue("duplicate code " + entry.code, codes.add(entry.code));
        }
    }

    @Test
    public void prettyPrintingUsesTypographicGlyphs() {
        assertEquals("\u00b7\u00b7\u00b7 \u2014\u2014\u2014 \u00b7\u00b7\u00b7", MorseCodec.pretty("... --- ..."));
    }

    @Test
    public void timingFollowsParisStandard() {
        assertEquals(80, MorseCodec.dotMillis(15));
        assertEquals(60, MorseCodec.dotMillis(20));
        assertEquals(1200, MorseCodec.dotMillis(1));
    }

    @Test
    public void emptyInputIsSafe() {
        assertEquals("", MorseCodec.encode(""));
        assertEquals("", MorseCodec.decode(""));
        assertEquals("", MorseCodec.encode(null));
        assertEquals("", MorseCodec.pretty(null));
    }
}

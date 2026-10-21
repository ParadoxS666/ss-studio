package com.pocketkeys.piano;

/**
 * Piano keyboard geometry, in units of one white-key width, measured from the
 * C of MIDI octave 0 (note 0). Pure Java (no Android) so it can be unit tested.
 *
 * Black keys sit on the boundary between their two white neighbours, nudged the
 * way a real piano (and Ableton's keyboard) draws them: C♯/F♯ slightly left,
 * D♯/A♯ slightly right, G♯ centred.
 */
final class KeyboardLayout {
    static final float BLACK_WIDTH = 0.58f;   // relative to a white key
    static final float BLACK_HEIGHT = 0.62f;  // relative to the white-key length

    private static final int[] WHITE_OF = {0, -1, 1, -1, 2, 3, -1, 4, -1, 5, -1, 6};

    private KeyboardLayout() { }

    static boolean isBlack(int note) { return WHITE_OF[Math.floorMod(note, 12)] < 0; }

    /** Left edge of a white key (white keys are 1 wide). */
    static float whiteLeft(int note) {
        return Math.floorDiv(note, 12) * 7 + WHITE_OF[Math.floorMod(note, 12)];
    }

    /** Centre of a black key. */
    static float blackCenter(int note) {
        float octave = Math.floorDiv(note, 12) * 7;
        switch (Math.floorMod(note, 12)) {
            case 1: return octave + 1 - 0.07f;   // C♯: between C and D
            case 3: return octave + 2 + 0.07f;   // D♯: between D and E
            case 6: return octave + 4 - 0.08f;   // F♯: between F and G
            case 8: return octave + 5;           // G♯: between G and A
            case 10: return octave + 6 + 0.08f;  // A♯: between A and B
            default: throw new IllegalArgumentException("not a black key: " + note);
        }
    }

    static float keyLeft(int note) {
        return isBlack(note) ? blackCenter(note) - BLACK_WIDTH / 2 : whiteLeft(note);
    }

    static float keyRight(int note) {
        return isBlack(note) ? blackCenter(note) + BLACK_WIDTH / 2 : whiteLeft(note) + 1;
    }

    // ---- the MPK mini's 25 keys ----
    /** Keys on the MPK mini: two octaves, C to C. */
    static final int MPK_KEYS = 25;
    /** White keys among them. */
    static final int MPK_WHITES = 15;
    /** Lowest key at the MPK's default octave (OCT 0): C2 in Ableton/Akai names, C3 scientific. */
    static final int MPK_DEFAULT_LO = 48;
    /** OCT −4 and +4. */
    static final int MPK_MIN_LO = MPK_DEFAULT_LO - 48, MPK_MAX_LO = MPK_DEFAULT_LO + 48;

    /**
     * The MPK sends no message when you press OCT, so the app follows the notes:
     * the 25 keys shown, {@code lo..lo+24}, move by whole octaves only when a played
     * note falls outside them.
     */
    static int followKeys(int lo, int note) {
        while (note < lo && lo - 12 >= MPK_MIN_LO) lo -= 12;
        while (note > lo + MPK_KEYS - 1 && lo + 12 <= MPK_MAX_LO) lo += 12;
        return lo;
    }

    /** A valid 25-key window: a C, within OCT −4..+4. */
    static int clampKeys(int lo) {
        lo = MPK_DEFAULT_LO + 12 * Math.round((lo - MPK_DEFAULT_LO) / 12f);
        return Math.max(MPK_MIN_LO, Math.min(MPK_MAX_LO, lo));
    }

    /**
     * Which key is under a point. {@code x} in white-key units, {@code y} from 0
     * (top of the keys) to 1 (bottom). Returns -1 outside 0..127.
     */
    static int noteAt(float x, float y) {
        if (y < BLACK_HEIGHT) {
            int oct = (int) Math.floor(x / 7);
            for (int n = oct * 12 - 2; n <= oct * 12 + 13; n++) {
                if (n < 0 || n > 127 || !isBlack(n)) continue;
                if (x >= keyLeft(n) && x < keyRight(n)) return n;
            }
        }
        int wIndex = (int) Math.floor(x);
        int oct = Math.floorDiv(wIndex, 7), w = Math.floorMod(wIndex, 7);
        for (int k = 0; k < 12; k++) {
            if (WHITE_OF[k] == w) {
                int n = oct * 12 + k;
                return n >= 0 && n <= 127 ? n : -1;
            }
        }
        return -1;
    }
}

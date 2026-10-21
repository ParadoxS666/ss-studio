package com.pocketkeys.piano;

/**
 * Fits a piece onto the MPK mini's 25 keys (C to C, two octaves). Pure Java.
 *
 * The notes you play (and see falling) are moved onto keys LO..HI:
 *  - each hand moves by whole octaves, choosing the octave where most of its
 *    notes already fit (so melodies keep their shape);
 *  - the few notes still outside are folded in by octaves;
 *  - two notes that land on the same key at the same moment become one, and a
 *    note is shortened if the same key comes again before it ends.
 * Notes the app plays for you keep sounding, moved by the same octaves as the hand
 * you play so the harmony stays together. Their pitch is kept in Song.sound.
 *
 * Fitted notes are numbered for the MPK at its default octave (keys 48..72). When
 * the MPK is set to another octave, the view adds the difference.
 */
final class KeyFit {
    static final int KEYS = 25, LO = 48, HI = LO + KEYS - 1;
    private static final int MAX_SHIFT = 96;            // ±8 octaves is plenty for any MIDI note
    private static final float SAME_TIME = 0.035f;       // closer than this counts as "together"

    private KeyFit() { }

    /** True if this hand setting has you play (and see) note i. */
    static boolean shown(byte hand, int hands) {
        return hands == LearnSession.HANDS_BOTH ? hand != Song.BACKING : hand == hands;
    }

    static Song fit(Song s, int hands) {
        int n = s.size();
        int shiftR, shiftL;
        if (hands == LearnSession.HANDS_BOTH) {
            int[] both = bothShifts(s);
            shiftR = both[0];
            shiftL = both[1];
        } else {
            byte h = hands == LearnSession.HANDS_LEFT ? Song.LEFT : Song.RIGHT;
            shiftR = shiftL = bestShift(s, h);
        }
        int other = hands == LearnSession.HANDS_LEFT ? shiftL : shiftR;   // backing tracks

        float[] t = new float[n], d = new float[n];
        byte[] note = new byte[n], hand = new byte[n], sound = new byte[n];
        boolean[] show = new boolean[n];
        for (int i = 0; i < n; i++) {
            byte h = s.hand[i];
            int sh = h == Song.RIGHT ? shiftR : h == Song.LEFT ? shiftL : other;
            t[i] = s.t[i];
            d[i] = s.d[i];
            hand[i] = h;
            show[i] = shown(h, hands);
            int v = s.note[i] + sh;
            if (show[i]) {
                v = fold(v, LO, HI);
                sound[i] = (byte) v;
            } else {
                sound[i] = (byte) fold(v, 28, 108);   // on the piano, and no lower than E1 so the bass stays clear
                v = sound[i];
            }
            note[i] = (byte) v;
        }

        // one note per key at a time (s.t is sorted by time)
        boolean[] drop = new boolean[n];
        int[] last = new int[KEYS];
        java.util.Arrays.fill(last, -1);
        int kept = 0;
        for (int i = 0; i < n; i++) {
            if (!show[i]) { kept++; continue; }
            int k = note[i] - LO;
            int j = last[k];
            if (j >= 0 && t[i] - t[j] < SAME_TIME) {          // same key, same moment: keep one
                d[j] = Math.max(d[j], d[i]);
                drop[i] = true;
                continue;
            }
            if (j >= 0 && t[j] + d[j] > t[i] - 0.03f) d[j] = Math.max(0.06f, t[i] - t[j] - 0.03f);
            last[k] = i;
            kept++;
        }
        float[] t2 = new float[kept], d2 = new float[kept];
        byte[] n2 = new byte[kept], h2 = new byte[kept], s2 = new byte[kept];
        int w = 0;
        for (int i = 0; i < n; i++) {
            if (drop[i]) continue;
            t2[w] = t[i];
            d2[w] = d[i];
            n2[w] = note[i];
            h2[w] = hand[i];
            s2[w] = sound[i];
            w++;
        }
        return new Song(s.id, s.title, s.composer, s.group, s.credit, s.bpm, t2, d2, n2, h2, s2, s.imported);
    }

    /** Moves v by octaves into lo..hi (hi - lo must be at least 11). */
    static int fold(int v, int lo, int hi) {
        while (v < lo) v += 12;
        while (v > hi) v -= 12;
        return v;
    }

    /** Octave shift (a multiple of 12) that puts the most notes of one hand on the 25 keys. */
    static int bestShift(Song s, byte hand) {
        int best = 0;
        long bestScore = Long.MIN_VALUE;
        int med = median(s, hand);
        if (med < 0) return 0;
        for (int sh = -MAX_SHIFT; sh <= MAX_SHIFT; sh += 12) {
            // most notes on the keys; then the hand nearest the middle; then the smallest move
            long score = (long) inRange(s, hand, sh) * 1_000_000L
                    - Math.abs(med + sh - (LO + 12)) * 1000L - Math.abs(sh);
            if (score > bestScore) { bestScore = score; best = sh; }
        }
        return best;
    }

    /** {right shift, left shift} for playing both hands on the 25 keys. */
    static int[] bothShifts(Song s) {
        int medR = median(s, Song.RIGHT), medL = median(s, Song.LEFT);
        if (medR < 0) return new int[]{bestShift(s, Song.LEFT), bestShift(s, Song.LEFT)};
        if (medL < 0) return new int[]{bestShift(s, Song.RIGHT), bestShift(s, Song.RIGHT)};
        int steps = 2 * MAX_SHIFT / 12 + 1;
        int[] inR = new int[steps], inL = new int[steps];
        for (int k = 0; k < steps; k++) {
            inR[k] = inRange(s, Song.RIGHT, -MAX_SHIFT + 12 * k);
            inL[k] = inRange(s, Song.LEFT, -MAX_SHIFT + 12 * k);
        }
        int bestR = 0, bestL = 0;
        long bestScore = Long.MIN_VALUE;
        for (int a = 0; a < steps; a++) {
            for (int b = 0; b < steps; b++) {
                int sr = -MAX_SHIFT + 12 * a, sl = -MAX_SHIFT + 12 * b;
                boolean leftBelow = medL + sl < medR + sr;    // keep the left hand under the right
                int mid = (medL + sl + medR + sr) / 2;
                // the left hand under the right comes first, then the most notes on the keys
                long score = (leftBelow ? 1L << 50 : 0)
                        + (long) (inR[a] + inL[b]) * 1_000_000L
                        - Math.abs((sr - sl) / 12) * 1000L     // keep the hands' octave relation
                        - Math.abs(mid - (LO + 12)) * 10L
                        - Math.abs(sr) - Math.abs(sl);
                if (score > bestScore) { bestScore = score; bestR = sr; bestL = sl; }
            }
        }
        return new int[]{bestR, bestL};
    }

    private static int inRange(Song s, byte hand, int sh) {
        int c = 0;
        for (int i = 0; i < s.size(); i++) {
            if (s.hand[i] != hand) continue;
            int v = s.note[i] + sh;
            if (v >= LO && v <= HI) c++;
        }
        return c;
    }

    /** Median pitch of one hand, or -1 if it has no notes. */
    private static int median(Song s, byte hand) {
        int c = 0;
        for (int i = 0; i < s.size(); i++) if (s.hand[i] == hand) c++;
        if (c == 0) return -1;
        int[] v = new int[c];
        c = 0;
        for (int i = 0; i < s.size(); i++) if (s.hand[i] == hand) v[c++] = s.note[i];
        java.util.Arrays.sort(v);
        return v[c / 2];
    }
}

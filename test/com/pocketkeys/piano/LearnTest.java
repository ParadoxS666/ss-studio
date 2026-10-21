package com.pocketkeys.piano;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Learn mode tests (desktop JVM).
 * args: <songs.pks> <dir with compiled Mutopia .mid files>
 */
public class LearnTest {
    static int fails = 0;

    static void check(String name, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + name);
        if (!ok) fails++;
    }

    static final class Rec implements LearnSession.Output {
        final List<String> log = new ArrayList<>();
        int sounding;
        public void autoOn(int n, int v) { log.add("on " + n); sounding++; }
        public void autoOff(int n) { log.add("off " + n); sounding--; }
        public void autoAllOff() { log.add("alloff"); sounding = 0; }
    }

    static Song tiny() {
        // RH: C4 D4 E4 (beats 0,1,2) ; LH: C3 held 0-3 ; at 1 s per beat
        float[] t = {0, 1, 2, 0};
        float[] d = {0.9f, 0.9f, 0.9f, 2.9f};
        byte[] n = {60, 62, 64, 48};
        byte[] h = {Song.RIGHT, Song.RIGHT, Song.RIGHT, Song.LEFT};
        return new Song("t", "Tiny", "", "", "", 60, t, d, n, h, false);
    }

    static void run(LearnSession s, float seconds) {
        for (float x = 0; x < seconds; x += 0.01f) s.step(0.01f);
    }

    public static void main(String[] a) throws Exception {
        System.out.println("Library");
        List<Song> lib = SongLibrary.readBuiltIn(Files.readAllBytes(Paths.get(a[0])));
        check("32 built-in songs", lib.size() == 32);
        boolean sorted = true, sane = true, hands = true;
        for (Song s : lib) {
            for (int i = 1; i < s.size(); i++) if (s.t[i] < s.t[i - 1]) sorted = false;
            for (int i = 0; i < s.size(); i++) if (s.d[i] <= 0 || s.note[i] < 21 || s.note[i] > 108) sane = false;
            if (!s.hasHand(Song.RIGHT) || !s.hasHand(Song.LEFT)) hands = false;
        }
        check("notes sorted by time in every song", sorted);
        check("every note on the 88 keys, positive length", sane);
        check("every song has a right and a left hand part", hands);
        Song minuet = null, elise = null;
        for (Song s : lib) { if (s.id.equals("minuet-g")) minuet = s; if (s.id.equals("fur-elise")) elise = s; }
        check("Minuet in G opens D5 G4 A4 B4 C5 D5 G4 G4", minuet != null && rh(minuet, 8).equals("74 67 69 71 72 74 67 67"));
        check("Für Elise opens E5 D#5 E5 D#5 E5 B4 D5 C5 A4", elise != null && rh(elise, 9).equals("76 75 76 75 76 71 74 72 69"));

        System.out.println("MIDI file import");
        Song parsed = SongLibrary.parseMidi(Files.readAllBytes(Paths.get(a[1], "anna-magdalena-04.mid")), "minuet.mid");
        check("parsed file: 2 tracks -> right + left hands", parsed.hasHand(Song.RIGHT) && parsed.hasHand(Song.LEFT));
        check("parsed file: same right-hand notes as the library (first 60)", rh(parsed, 60).equals(rh(minuet, 60)));
        check("parsed file: same number of notes", parsed.size() == minuet.size());
        check("parsed file starts at 0 s", parsed.t[0] == 0f);
        boolean threw = false;
        try { SongLibrary.parseMidi(new byte[]{'M', 'T', 'h', 'd', 0, 0, 0, 6, 0, 1, 0, 9}, "x.mid"); } catch (java.io.IOException e) { threw = true; }
        check("cut-short file is rejected with a message, no crash", threw);
        threw = false;
        try { SongLibrary.parseMidi("hello".getBytes(), "x.mid"); } catch (java.io.IOException e) { threw = true; }
        check("non-MIDI file is rejected", threw);
        // random corruption never crashes with anything but IOException
        byte[] good = Files.readAllBytes(Paths.get(a[1], "fur_Elise_WoO59.mid"));
        java.util.Random rnd = new java.util.Random(1);
        boolean robust = true;
        for (int k = 0; k < 400; k++) {
            byte[] bad = good.clone();
            for (int j = 0; j < 20; j++) bad[14 + rnd.nextInt(bad.length - 14)] = (byte) rnd.nextInt(256);
            if (k % 3 == 0) bad = java.util.Arrays.copyOf(bad, rnd.nextInt(bad.length));
            try { SongLibrary.parseMidi(bad, "x.mid"); } catch (java.io.IOException e) { /* fine */ } catch (RuntimeException e) { robust = false; System.out.println("    " + e); break; }
        }
        check("400 corrupted files: never crashes", robust);

        // a valid file with the top MIDI note, and a damaged one with a note byte >= 0x80
        Song top = SongLibrary.parseMidi(midi(new int[]{0x90, 127, 100}, new int[]{0x80, 127, 0}), "top.mid");
        Song bottom = SongLibrary.parseMidi(midi(new int[]{0x90, 0, 100}, new int[]{0x80, 0, 0}), "low.mid");
        boolean edges = true;
        for (Song x : new Song[]{top, bottom})
            for (int h = 0; h < 3; h++) if (!onKeys(KeyFit.fit(x, h), h)) edges = false;
        check("notes 0 and 127 fit on the 25 keys", edges);
        threw = false;
        try { SongLibrary.parseMidi(midi(new int[]{0x90, 0xF4, 100}, new int[]{0x80, 0xF4, 0}), "bad.mid"); } catch (java.io.IOException e) { threw = true; }
        check("note number above 127 is rejected as damaged", threw);
        boolean allInRange = true;
        for (int k = 0; k < 3000; k++) {
            byte[] bad = good.clone();
            for (int j = 0; j < 30; j++) bad[14 + rnd.nextInt(bad.length - 14)] = (byte) rnd.nextInt(256);
            try {
                Song x = SongLibrary.parseMidi(bad, "x.mid");
                for (int i = 0; i < x.size(); i++) if (x.note[i] < 0) allInRange = false;
                if (!onKeys(KeyFit.fit(x, LearnSession.HANDS_BOTH), LearnSession.HANDS_BOTH)) allInRange = false;
            } catch (java.io.IOException e) { /* fine */ }
        }
        check("3000 corrupted files: every accepted note is 0..127 and fits the 25 keys", allInRange);

        System.out.println("Fitting pieces to the MPK's 25 keys");
        boolean allOnKeys = true, oneAtATime = true, sounding = true;
        int total = 0, folded = 0;
        for (Song s : lib) {
            for (int h = 0; h < 3; h++) {
                Song f = KeyFit.fit(s, h);
                if (!onKeys(f, h)) { allOnKeys = false; System.out.println("    off the keys: " + s.title + " hands " + h); }
                float[] lastT = new float[25];
                java.util.Arrays.fill(lastT, -9);
                for (int i = 0; i < f.size(); i++) {
                    if (!KeyFit.shown(f.hand[i], h)) {
                        if (f.sound[i] < 21 || f.sound[i] > 108) sounding = false;
                        continue;
                    }
                    int k = f.note[i] - KeyFit.LO;
                    if (f.t[i] - lastT[k] < 0.035f) oneAtATime = false;
                    lastT[k] = f.t[i];
                }
                // how much of each hand keeps its shape (moved as a whole, not folded note by note)
                if (h != LearnSession.HANDS_BOTH) {
                    byte hand = h == LearnSession.HANDS_LEFT ? Song.LEFT : Song.RIGHT;
                    int sh = KeyFit.bestShift(s, hand), n = 0, out = 0;
                    for (int i = 0; i < s.size(); i++) {
                        if (s.hand[i] != hand) continue;
                        n++;
                        int v = s.note[i] + sh;
                        if (v < KeyFit.LO || v > KeyFit.HI) out++;
                    }
                    total += n;
                    folded += out;
                    if (n > 0 && out > 0.25 * n) System.out.println(String.format("    %-45s %s: %d of %d notes folded", s.title, h == 0 ? "R" : "L", out, n));
                }
            }
        }
        check("all 32 pieces x 3 hand settings: every note you play is on the 25 keys", allOnKeys);
        check("never two notes on one key at the same moment", oneAtATime);
        check("notes played for you stay on the 88 keys", sounding);
        System.out.println(String.format("    %.1f%% of single-hand notes fit without folding", 100.0 * (total - folded) / total));
        check("most notes keep their shape (over 90% fit without folding)", total - folded > 0.9 * total);
        Song elise0 = KeyFit.fit(elise, LearnSession.HANDS_RIGHT);
        check("Für Elise right hand on 25 keys opens E D♯ E D♯ E B D C A", names(rh(elise0, 9)).equals("E D# E D# E B D C A"));
        boolean leftBelow = true;
        int bothN = 0, bothOut = 0;
        for (Song s : lib) {
            int[] sh = KeyFit.bothShifts(s);
            for (int i = 0; i < s.size(); i++) {
                if (s.hand[i] == Song.BACKING) continue;
                int v = s.note[i] + (s.hand[i] == Song.RIGHT ? sh[0] : sh[1]);
                bothN++;
                if (v < KeyFit.LO || v > KeyFit.HI) bothOut++;
            }
            Song b = KeyFit.fit(s, LearnSession.HANDS_BOTH);
            if (median(b, Song.LEFT) >= median(b, Song.RIGHT)) { leftBelow = false; System.out.println("    left above right: " + s.title); }
        }
        check("both hands on 25 keys: the left hand sits below the right in every piece", leftBelow);
        System.out.println(String.format("    both hands: %.1f%% of notes fit without folding", 100.0 * (bothN - bothOut) / bothN));

        System.out.println("Wait mode");
        Rec rec = new Rec();
        LearnSession w = new LearnSession(tiny(), LearnSession.WAIT, LearnSession.HANDS_RIGHT, rec);
        w.togglePlay();
        run(w, 3f);
        check("song holds at the first note until you play it", Math.abs(w.t - 0f) < 1e-4);
        check("while it waits, nothing plays on its own (not even the left hand's chord)", rec.log.isEmpty());
        w.keyDown(61, w.t);
        check("wrong key counted, no advance", w.counts[LearnSession.G_WRONG] == 1 && w.t == 0f);
        run(w, 1f);
        check("...still nothing plays after a wrong key", rec.log.isEmpty());
        w.keyDown(60, w.t);
        run(w, 0.02f);
        check("the left hand comes in together with your key", rec.log.contains("on 48"));
        run(w, 0.5f);
        check("right key: moves on", w.t > 0.4f && w.hits == 1);
        run(w, 1f);
        check("holds at the 2nd note", Math.abs(w.t - 1f) < 1e-4);
        w.keyDown(74, w.t);   // D5: right note, other octave
        run(w, 1.2f);
        check("any octave accepted by default", w.hits == 2 && Math.abs(w.t - 2f) < 1e-4);
        w.anyOctave = false;
        w.keyDown(76, w.t);
        check("exact octave when 'any octave' is off", w.hits == 2 && w.counts[LearnSession.G_WRONG] == 2);
        w.keyDown(64, w.t);
        run(w, 4f);
        check("song finishes", w.finished && !w.playing);
        check("all backing notes released", rec.sounding <= 0);
        check("accuracy 3/5 = 2 stars", Math.abs(w.accuracy() - 0.6f) < 1e-4 && LearnSession.stars(w.accuracy()) == 2);
        Rec silent = new Rec();
        LearnSession off = new LearnSession(tiny(), LearnSession.WAIT, LearnSession.HANDS_RIGHT, silent);
        off.backing = false;
        off.togglePlay();
        run(off, 3f);
        for (int k = 0; k < 3; k++) { for (int i : off.nextGroup()) off.keyDown(off.song.note[i], off.t); run(off, 1.5f); }
        check("Other hand off: the app never plays a note (Wait)", silent.log.isEmpty() && off.finished);
        LearnSession offPlay = new LearnSession(tiny(), LearnSession.PLAY, LearnSession.HANDS_RIGHT, silent);
        offPlay.backing = false;
        offPlay.togglePlay();
        run(offPlay, 7f);
        check("Other hand off: the app never plays a note (Play)", silent.log.isEmpty() && offPlay.finished);

        System.out.println("Play mode");
        rec = new Rec();
        LearnSession p = new LearnSession(tiny(), LearnSession.PLAY, LearnSession.HANDS_RIGHT, rec);
        p.togglePlay();
        run(p, 2f);                 // lead-in over, t = 0
        p.keyDown(60, p.t + 0.02f); // 20 ms late: perfect
        run(p, 1.08f);              // t ≈ 1.08
        p.keyDown(62, p.t);         // 80 ms late: great
        run(p, 1.5f);               // miss E4 at 2.0
        check("perfect, great, then a miss", p.counts[0] == 1 && p.counts[1] == 1 && p.counts[3] == 1);
        check("score uses points x multiplier", p.score == 170);
        check("streak reset by the miss", p.streak == 0 && p.bestStreak == 2);
        p.speed = 0.5f;
        LearnSession p2 = new LearnSession(tiny(), LearnSession.PLAY, LearnSession.HANDS_RIGHT, rec);
        p2.speed = 0.5f;
        p2.togglePlay();
        run(p2, 4f);                // half speed: lead-in takes 4 s
        p2.keyDown(60, p2.t + 0.04f); // 40 ms of song time = 80 ms real at half speed -> great
        check("windows are in real time at slow speed", p2.counts[1] == 1);

        System.out.println("Listen, hands, loop");
        rec = new Rec();
        LearnSession l = new LearnSession(tiny(), LearnSession.LISTEN, LearnSession.HANDS_RIGHT, rec);
        l.togglePlay();
        run(l, 5.5f);
        long ons = rec.log.stream().filter(x -> x.startsWith("on")).count();
        check("listen plays every note", ons == 4);
        LearnSession b = new LearnSession(tiny(), LearnSession.WAIT, LearnSession.HANDS_BOTH, new Rec());
        check("both hands: chord of C4+C3 to start", b.nextGroup().length == 2);
        LearnSession left = new LearnSession(tiny(), LearnSession.WAIT, LearnSession.HANDS_LEFT, new Rec());
        check("left hand only: one note to play", left.groupCount() == 1);
        LearnSession lp = new LearnSession(tiny(), LearnSession.PLAY, LearnSession.HANDS_RIGHT, new Rec());
        lp.togglePlay();
        run(lp, 2.5f);
        lp.setLoopA();
        run(lp, 1f);
        lp.setLoopB();
        float a1 = lp.loopA, b1 = lp.loopB;
        run(lp, 3f);
        check("loop A–B keeps time inside the loop", lp.t >= a1 - 0.02f && lp.t <= b1 + 0.02f && !lp.finished);
        lp.setLoopB();
        check("second Loop B press clears the loop", Float.isNaN(lp.loopA) && Float.isNaN(lp.loopB));

        System.out.println("Seek in wait mode");
        LearnSession sk = new LearnSession(tiny(), LearnSession.WAIT, LearnSession.HANDS_RIGHT, new Rec());
        sk.togglePlay();
        sk.seek(1.5f);
        run(sk, 1f);
        check("after seeking forward, waits at the next note (2.0), not a skipped one", Math.abs(sk.t - 2f) < 1e-4);
        check("a run you jumped in is marked as practice (not saved as a best)", sk.practice && !w.practice);
        check("a loop that repeats marks the run as practice", lp.practice);

        System.out.println("Every built-in song plays through in listen mode");
        boolean allOk = true;
        for (Song s : lib) {
            Rec r = new Rec();
            LearnSession x = new LearnSession(s, LearnSession.LISTEN, LearnSession.HANDS_BOTH, r);
            x.togglePlay();
            int steps = 0;
            while (!x.finished && steps < 200000) { x.step(0.05f); steps++; }
            long on = r.log.stream().filter(q -> q.startsWith("on")).count();
            if (!x.finished || on != x.song.size() || r.sounding > 0) { allOk = false; System.out.println("    " + s.title + " on=" + on + "/" + x.song.size()); }
        }
        check("all 32 songs: every note played once, nothing left sounding", allOk);
        allOk = true;
        for (Song s : lib) {
            for (int h = 0; h < 3; h++) {
                Rec r = new Rec();
                LearnSession x = new LearnSession(s, LearnSession.WAIT, h, r);
                x.togglePlay();
                int steps = 0;
                while (!x.finished && steps < 400000) {
                    x.step(0.05f);
                    for (int i : x.nextGroup()) x.keyDown(x.song.note[i], x.t);
                    steps++;
                }
                int toPlay = 0;
                for (boolean m : x.mine) if (m) toPlay++;
                if (!x.finished || x.hits != toPlay || r.sounding > 0) {
                    allOk = false;
                    System.out.println("    " + s.title + " hands " + h + " hits=" + x.hits + "/" + toPlay);
                }
            }
        }
        check("all 32 songs x 3 hand settings: Wait mode can be played to the end on 25 keys", allOk);

        System.out.println(fails == 0 ? "ALL PASSED" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }

    /** Every note you play (for these hands) is one of the MPK's 25 keys at OCT 0. */
    static boolean onKeys(Song f, int hands) {
        for (int i = 0; i < f.size(); i++) {
            if (KeyFit.shown(f.hand[i], hands) && (f.note[i] < KeyFit.LO || f.note[i] > KeyFit.HI)) return false;
            if (f.sound[i] < 0 || f.sound[i] > 127) return false;
        }
        return true;
    }

    static int median(Song s, byte hand) {
        java.util.List<Integer> v = new ArrayList<>();
        for (int i = 0; i < s.size(); i++) if (s.hand[i] == hand) v.add((int) s.note[i]);
        java.util.Collections.sort(v);
        return v.get(v.size() / 2);
    }

    static String names(String notes) {
        String[] pc = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
        StringBuilder sb = new StringBuilder();
        for (String n : notes.split(" ")) sb.append(sb.length() > 0 ? " " : "").append(pc[Integer.parseInt(n) % 12]);
        return sb.toString();
    }

    /** A one-track MIDI file: note on at 0, note off a beat later. */
    static byte[] midi(int[] on, int[] off) {
        int[] trk = {0, on[0], on[1], on[2], 96, off[0], off[1], off[2], 0, 0xFF, 0x2F, 0};
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        int[] head = {'M', 'T', 'h', 'd', 0, 0, 0, 6, 0, 0, 0, 1, 0, 96, 'M', 'T', 'r', 'k', 0, 0, 0, trk.length};
        for (int x : head) b.write(x);
        for (int x : trk) b.write(x);
        return b.toByteArray();
    }

    static String rh(Song s, int count) {
        StringBuilder sb = new StringBuilder();
        int c = 0;
        for (int i = 0; i < s.size() && c < count; i++) {
            if (s.hand[i] != Song.RIGHT) continue;
            if (c++ > 0) sb.append(' ');
            sb.append(s.note[i]);
        }
        return sb.toString();
    }
}

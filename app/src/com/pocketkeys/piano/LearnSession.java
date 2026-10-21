package com.pocketkeys.piano;

/**
 * Learn mode game logic, ported from the Key Falls web page. Pure Java (no
 * Android) so it can be unit tested. Single-threaded: call everything from the
 * UI thread.
 *
 *   Wait   — the song stops at each note (or chord) until you play it.
 *   Play   — notes keep falling; hit them on the line: Perfect / Great / Good.
 *   Listen — the whole piece plays for you.
 * The hand you're not practising is played for you ("backing").
 *
 * The piece is first fitted onto the MPK mini's 25 keys (KeyFit), so every note
 * you're asked to play is on a key you have.
 */
final class LearnSession {
    interface Output {
        void autoOn(int note, int velocity);
        void autoOff(int note);
        void autoAllOff();
    }

    static final int WAIT = 0, PLAY = 1, LISTEN = 2;
    static final int HANDS_RIGHT = 0, HANDS_LEFT = 1, HANDS_BOTH = 2;
    static final float LEAD_IN = 2f;                                     // seconds before the first note
    static final float WIN_PERFECT = 0.05f, WIN_GREAT = 0.10f, WIN_GOOD = 0.16f; // real seconds
    static final int G_PERFECT = 0, G_GREAT = 1, G_GOOD = 2, G_MISS = 3, G_WRONG = 4;
    static final int[] POINTS = {100, 70, 40};
    static final byte PENDING = 0, HIT = 1, MISSED = 2;

    /** The piece fitted to the 25 keys: what falls, what you play, what is played for you. */
    final Song song;
    /** The piece as written (title, credits). */
    final Song original;
    final int mode, hands;
    private final Output out;

    // settings that may change while playing
    float speed = 1f;
    boolean anyOctave = true;
    boolean backing = true;   // play the hand you're not playing (the view's "Other hand", off by default)
    float audioLead;          // real seconds: play backing early to cancel output latency
    /** Semitones from the fitted keys (48..72) to what the MPK sends at its current octave. */
    int keyShift;

    // per note
    final boolean[] shown;     // on the keys for the hands you chose (also shown in Listen)
    final boolean[] mine;      // yours to play (shown, and not in Listen)
    final byte[] status;
    final byte[] grade;
    private final boolean[] played;

    // chords to play in wait mode: [groupStart[g], groupStart[g+1]) index into groupNotes
    private final int[] groupNotes, groupStart;
    private final float[] groupTime;
    int gi;

    float t = -LEAD_IN;
    boolean playing, finished, started;
    /** Set once you jump with the timeline or a loop repeats: the run isn't saved as a best. */
    boolean practice;
    float loopA = Float.NaN, loopB = Float.NaN;

    int score, streak, bestStreak;
    final int[] counts = new int[5];
    int hits;

    private int autoCursor, missCursor;
    private double clock;                                   // real seconds since start
    private final int[] activeNote = new int[64];
    private final double[] activeEnd = new double[64];
    private int activeCount;

    // judgement pop-ups for the view
    static final class Flash {
        final int note, kind;
        final double at;
        Flash(int note, int kind, double at) { this.note = note; this.kind = kind; this.at = at; }
    }
    final java.util.ArrayDeque<Flash> flashes = new java.util.ArrayDeque<>();

    LearnSession(Song original, int mode, int hands, Output out) {
        this.original = original;
        this.song = KeyFit.fit(original, hands);
        this.mode = mode;
        this.hands = hands;
        this.out = out;
        Song song = this.song;
        int n = song.size();
        shown = new boolean[n];
        mine = new boolean[n];
        status = new byte[n];
        grade = new byte[n];
        played = new boolean[n];
        int count = 0;
        for (int i = 0; i < n; i++) {
            shown[i] = KeyFit.shown(song.hand[i], hands);
            mine[i] = mode != LISTEN && shown[i];
            if (mine[i]) count++;
        }
        groupNotes = new int[count];
        int[] gs = new int[count + 1];
        float[] gt = new float[count + 1];
        int g = 0, k = 0;
        for (int i = 0; i < n; i++) {
            if (!mine[i]) continue;
            if (g == 0 || song.t[i] - gt[g - 1] >= 0.03f) { gs[g] = k; gt[g] = song.t[i]; g++; }
            groupNotes[k++] = i;
        }
        gs[g] = k;
        groupStart = java.util.Arrays.copyOf(gs, g + 1);
        groupTime = java.util.Arrays.copyOf(gt, g);
    }

    int groupCount() { return groupTime.length; }

    /** Indexes of the notes in the chord you have to play next (wait mode), or empty. */
    int[] nextGroup() {
        advanceGroups();
        if (mode != WAIT || gi >= groupTime.length) return new int[0];
        return java.util.Arrays.copyOfRange(groupNotes, groupStart[gi], groupStart[gi + 1]);
    }

    float nextGroupTime() {
        advanceGroups();
        return gi < groupTime.length ? groupTime[gi] : Float.NaN;
    }

    int multiplier() { return Math.min(4, 1 + streak / 10); }

    // ------------------------------------------------------------------
    // Transport
    // ------------------------------------------------------------------
    void togglePlay() {
        if (finished) return;
        playing = !playing;
        started = true;
        if (!playing) stopAuto();
    }

    void pause() {
        if (playing) { playing = false; stopAuto(); }
    }

    void seek(float to) {
        to = Math.max(-LEAD_IN, Math.min(song.duration, to));
        practice = true;
        stopAuto();
        t = to;
        for (int i = 0; i < song.size(); i++) {
            if (song.t[i] >= to - 0.001f) { status[i] = PENDING; played[i] = false; }
            else played[i] = true;
        }
        // the next chord to wait for is the first one from here on (never one we skipped past)
        gi = 0;
        while (gi < groupTime.length && groupTime[gi] < to - 0.001f) gi++;
        advanceGroups();
        autoCursor = firstAtOrAfter(to);
        missCursor = autoCursor;
        finished = false;
    }

    void setLoopA() {
        loopA = Math.max(0, t);
        if (!Float.isNaN(loopB) && loopB <= loopA) loopB = Float.NaN;
    }

    /** Sets loop end; a second press clears the loop. */
    void setLoopB() {
        if (!Float.isNaN(loopB)) { loopA = loopB = Float.NaN; return; }
        if (!Float.isNaN(loopA) && t > loopA) loopB = t;
    }

    private int firstAtOrAfter(float time) {
        int lo = 0, hi = song.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (song.t[mid] < time) lo = mid + 1; else hi = mid;
        }
        return lo;
    }

    private void advanceGroups() {
        while (gi < groupTime.length) {
            boolean done = true;
            for (int k = groupStart[gi]; k < groupStart[gi + 1]; k++) if (status[groupNotes[k]] != HIT) { done = false; break; }
            if (!done) break;
            gi++;
        }
    }

    /** Advances the song by {@code dtReal} seconds of real time. */
    void step(float dtReal) {
        dtReal = Math.min(0.1f, Math.max(0f, dtReal));
        clock += dtReal;
        expireAuto();
        if (!playing) return;
        float nt = t + dtReal * speed;
        if (mode == WAIT) {
            advanceGroups();
            if (gi < groupTime.length && nt > groupTime[gi]) nt = groupTime[gi]; // hold until you play it
        }
        // backing: the hand you're not playing (everything in listen mode), started a little
        // early so it is heard exactly when it reaches the line despite output latency
        float lead = audioLead * speed;
        while (autoCursor < song.size() && song.t[autoCursor] <= nt + lead) {
            int i = autoCursor++;
            if (mine[i] || played[i]) continue;
            // wait mode: nothing that goes with the chord you're waiting for sounds before you
            // play it; it comes in with your keys
            if (mode == WAIT && gi < groupTime.length && song.t[i] > groupTime[gi] - 0.035f) { autoCursor--; break; }
            played[i] = true;
            if (backing || mode == LISTEN) {
                // your hand (in Listen) sounds on the keys you'd press; the rest at its own pitch
                int pitch = shown[i] ? song.note[i] + keyShift : song.sound[i];
                startAuto(Math.max(0, Math.min(127, pitch)), song.hand[i] == Song.BACKING ? 55 : 75, song.d[i] / speed);
            }
        }
        if (mode == PLAY) {
            while (missCursor < song.size() && song.t[missCursor] < nt - WIN_GOOD * speed) {
                int i = missCursor++;
                if (mine[i] && status[i] == PENDING) {
                    status[i] = MISSED;
                    grade[i] = G_MISS;
                    counts[G_MISS]++;
                    streak = 0;
                    flash(song.note[i] + keyShift, G_MISS);
                }
            }
        }
        t = nt;
        if (!Float.isNaN(loopA) && !Float.isNaN(loopB) && t >= loopB) {
            seek(loopA);
            return;
        }
        if (t >= song.duration + 0.6f) {
            playing = false;
            finished = true;
            stopAuto();
        }
    }

    // ------------------------------------------------------------------
    // Your keys
    // ------------------------------------------------------------------
    private boolean matches(int pressed, int expected) {
        expected += keyShift;
        return anyOctave ? Math.floorMod(pressed - expected, 12) == 0 : pressed == expected;
    }

    /** A key went down at song time {@code atSongTime}. */
    void keyDown(int n, float atSongTime) {
        if (!playing || mode == LISTEN) return;
        if (mode == WAIT) judgeWait(n, atSongTime); else judgePlay(n, atSongTime);
    }

    private void judgeWait(int n, float at) {
        advanceGroups();
        if (gi >= groupTime.length) return;
        if (at < groupTime[gi] - 0.35f * speed) return; // playing around before the note arrives is free
        int target = -1;
        boolean repeat = false;
        for (int k = groupStart[gi]; k < groupStart[gi + 1]; k++) {
            int i = groupNotes[k];
            if (!matches(n, song.note[i])) continue;
            if (status[i] == PENDING && target < 0) target = i;
            if (status[i] == HIT) repeat = true;
        }
        if (target >= 0) { award(target, G_PERFECT); return; }
        if (repeat) return; // pressing a chord note again
        wrong(n);
    }

    private void judgePlay(int n, float at) {
        int best = -1;
        float bestDt = Float.MAX_VALUE;
        for (int i = Math.max(0, missCursor - 64); i < song.size(); i++) {
            if (song.t[i] > at + WIN_GOOD * speed) break;
            if (!mine[i] || status[i] != PENDING || !matches(n, song.note[i])) continue;
            float dt = Math.abs(song.t[i] - at) / speed;
            if (dt <= WIN_GOOD && dt < bestDt) { best = i; bestDt = dt; }
        }
        if (best < 0) {
            if (at > -WIN_GOOD * speed) wrong(n);
            return;
        }
        award(best, bestDt <= WIN_PERFECT ? G_PERFECT : bestDt <= WIN_GREAT ? G_GREAT : G_GOOD);
    }

    private void award(int i, int g) {
        status[i] = HIT;
        grade[i] = (byte) g;
        counts[g]++;
        hits++;
        streak++;
        bestStreak = Math.max(bestStreak, streak);
        score += POINTS[g] * multiplier();
        flash(song.note[i] + keyShift, g);
    }

    private void wrong(int n) {
        counts[G_WRONG]++;
        streak = 0;
        flash(n, G_WRONG);
    }

    private void flash(int note, int kind) {
        flashes.addLast(new Flash(note, kind, clock));
        while (flashes.size() > 40) flashes.removeFirst();
    }

    double clock() { return clock; }

    // ------------------------------------------------------------------
    // Results
    // ------------------------------------------------------------------
    /** 0..1, or NaN before any judgement. */
    float accuracy() {
        if (mode == WAIT) {
            int tot = hits + counts[G_WRONG];
            return tot == 0 ? Float.NaN : hits / (float) tot;
        }
        float tot = counts[G_PERFECT] + counts[G_GREAT] + counts[G_GOOD] + counts[G_MISS] + counts[G_WRONG] * 0.5f;
        return tot == 0 ? Float.NaN : (counts[G_PERFECT] + 0.8f * counts[G_GREAT] + 0.5f * counts[G_GOOD]) / tot;
    }

    static int stars(float a) {
        if (Float.isNaN(a)) return 0;
        return a >= 0.95f ? 5 : a >= 0.85f ? 4 : a >= 0.7f ? 3 : a >= 0.5f ? 2 : 1;
    }

    // ------------------------------------------------------------------
    // Backing notes
    // ------------------------------------------------------------------
    private void startAuto(int note, int vel, double realDur) {
        if (activeCount == activeNote.length) { out.autoOff(activeNote[0]); removeActive(0); }
        for (int k = 0; k < activeCount; k++) {               // same note again: restart it
            if (activeNote[k] == note) { out.autoOff(note); removeActive(k); break; }
        }
        out.autoOn(note, vel);
        activeNote[activeCount] = note;
        activeEnd[activeCount] = clock + Math.max(0.05, realDur);
        activeCount++;
    }

    private void expireAuto() {
        for (int k = activeCount - 1; k >= 0; k--) {
            if (activeEnd[k] <= clock) { out.autoOff(activeNote[k]); removeActive(k); }
        }
    }

    private void removeActive(int k) {
        System.arraycopy(activeNote, k + 1, activeNote, k, activeCount - k - 1);
        System.arraycopy(activeEnd, k + 1, activeEnd, k, activeCount - k - 1);
        activeCount--;
    }

    void stopAuto() {
        if (activeCount > 0) out.autoAllOff();
        activeCount = 0;
    }

}

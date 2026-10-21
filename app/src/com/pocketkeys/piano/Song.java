package com.pocketkeys.piano;

/** A piece for Learn mode: notes sorted by start time. Pure Java. */
final class Song {
    static final byte RIGHT = 0, LEFT = 1, BACKING = 2;

    final String id, title, composer, group, credit;
    final int bpm;
    final float[] t, d;        // start and length, seconds
    final byte[] note, hand;
    /** The pitch to sound when the app plays a note for you (see KeyFit); usually the same as note. */
    final byte[] sound;
    final float duration;
    final boolean imported;

    Song(String id, String title, String composer, String group, String credit, int bpm,
         float[] t, float[] d, byte[] note, byte[] hand, boolean imported) {
        this(id, title, composer, group, credit, bpm, t, d, note, hand, null, imported);
    }

    Song(String id, String title, String composer, String group, String credit, int bpm,
         float[] t, float[] d, byte[] note, byte[] hand, byte[] sound, boolean imported) {
        this.id = id;
        this.title = title;
        this.composer = composer;
        this.group = group;
        this.credit = credit;
        this.bpm = bpm;
        this.imported = imported;
        // sort by time, then pitch
        int n = t.length;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> t[a] != t[b] ? Float.compare(t[a], t[b]) : Integer.compare(note[a], note[b]));
        this.t = new float[n];
        this.d = new float[n];
        this.note = new byte[n];
        this.hand = new byte[n];
        this.sound = new byte[n];
        float end = 0;
        for (int i = 0; i < n; i++) {
            int k = order[i];
            this.t[i] = t[k];
            this.d[i] = d[k];
            this.note[i] = note[k];
            this.hand[i] = hand[k];
            this.sound[i] = sound != null ? sound[k] : note[k];
            end = Math.max(end, t[k] + d[k]);
        }
        duration = end;
    }

    int size() { return t.length; }

    boolean hasHand(byte h) {
        for (byte x : hand) if (x == h) return true;
        return false;
    }

    private float maxDur = -1;

    /** Longest note, in seconds (how far back a note can start and still be sounding). */
    float maxDur() {
        if (maxDur < 0) {
            float m = 0;
            for (float x : d) m = Math.max(m, x);
            maxDur = m;
        }
        return maxDur;
    }

    /** "1:36" */
    String lengthLabel() {
        int s = Math.round(duration);
        return s / 60 + ":" + (s % 60 < 10 ? "0" : "") + s % 60;
    }
}

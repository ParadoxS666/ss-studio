package com.pocketkeys.piano;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the built-in library (assets/songs.pks, see buildtools/songs/make_songs.py)
 * and Standard MIDI Files the player opens. Pure Java.
 */
final class SongLibrary {
    private SongLibrary() { }

    static List<Song> readBuiltIn(byte[] file) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(file));
        byte[] magic = new byte[4];
        in.readFully(magic);
        if (magic[0] != 'P' || magic[1] != 'K' || magic[2] != 'S' || magic[3] != 'G') throw new IOException("not a song library");
        int version = in.readUnsignedShort();
        if (version != 1) throw new IOException("unsupported song library version " + version);
        int count = in.readUnsignedShort();
        List<Song> out = new ArrayList<>(count);
        for (int s = 0; s < count; s++) {
            String id = in.readUTF(), title = in.readUTF(), composer = in.readUTF(), group = in.readUTF(), credit = in.readUTF();
            int bpm = in.readUnsignedShort();
            in.readUnsignedShort(); // beats per bar x100 (reserved)
            in.readInt();           // first bar line, ms (reserved)
            int n = in.readInt();
            if (n < 0 || n > 200000) throw new IOException("bad note count");
            float[] t = new float[n], d = new float[n];
            byte[] note = new byte[n], hand = new byte[n];
            for (int i = 0; i < n; i++) {
                t[i] = in.readInt() / 1000f;
                d[i] = in.readInt() / 1000f;
                note[i] = (byte) in.readUnsignedByte();
                hand[i] = (byte) in.readUnsignedByte();
            }
            out.add(new Song(id, title, composer, group, credit, bpm, t, d, note, hand, false));
        }
        return out;
    }

    /**
     * Parses a Standard MIDI File (format 0 or 1). The drum channel is dropped.
     * With two or more note tracks the first is the right hand, the second the
     * left hand and the rest backing; with one track, notes from middle C up are
     * the right hand.
     */
    static Song parseMidi(byte[] buf, String fileName) throws IOException {
        Reader r = new Reader(buf);
        if (!"MThd".equals(r.str(4))) throw new IOException("This isn't a MIDI file.");
        int hlen = r.u32();
        int start = r.p;
        r.u16(); // format
        int ntrk = r.u16(), division = r.u16();
        r.p = start + hlen;
        if ((division & 0x8000) != 0) throw new IOException("SMPTE-timed MIDI files aren't supported.");
        if (division == 0) throw new IOException("Broken MIDI header.");

        List<long[]> tempos = new ArrayList<>(); // {tick, microseconds per quarter}
        tempos.add(new long[]{0, 500000});
        List<List<long[]>> tracks = new ArrayList<>(); // notes: {startTick, endTick, note, channel}
        List<String> names = new ArrayList<>();
        for (int ti = 0; ti < ntrk && r.p + 8 <= buf.length; ti++) {
            String id = r.str(4);
            int len = r.u32();
            int end = (int) Math.min((long) r.p + len, buf.length);
            if (!"MTrk".equals(id)) { r.p = end; continue; }
            List<long[]> notes = new ArrayList<>();
            String name = "";
            java.util.Map<Integer, java.util.ArrayDeque<Long>> open = new java.util.HashMap<>();
            long tick = 0;
            int status = 0;
            while (r.p < end) {
                tick += r.vlq();
                if (r.p >= end) break;
                int b = r.u8peek();
                if ((b & 0x80) != 0) { status = b; r.p++; }
                else if (status == 0) { r.p++; continue; }
                int type = status & 0xF0, ch = status & 0x0F;
                if (status == 0xFF) {
                    int mt = r.u8(), ml = r.vlq();
                    if (mt == 0x51 && ml == 3) tempos.add(new long[]{tick, (r.at(r.p) << 16) | (r.at(r.p + 1) << 8) | r.at(r.p + 2)});
                    if (mt == 0x03 && name.isEmpty()) name = new String(buf, r.p, Math.max(0, Math.min(ml, buf.length - r.p)), "ISO-8859-1");
                    r.p += ml;
                    status = 0;
                } else if (status == 0xF0 || status == 0xF7) {
                    r.p += r.vlq();
                    status = 0;
                } else if (type == 0x90 || type == 0x80) {
                    int n = r.u8(), vel = r.u8();
                    if (n > 127 || vel > 127) throw new IOException("This MIDI file is damaged.");
                    int key = ch * 128 + n;
                    if (type == 0x90 && vel > 0) {
                        open.computeIfAbsent(key, k -> new java.util.ArrayDeque<>()).add(tick);
                    } else {
                        java.util.ArrayDeque<Long> q = open.get(key);
                        if (q != null && !q.isEmpty()) notes.add(new long[]{q.poll(), tick, n, ch});
                    }
                } else if (type == 0xC0 || type == 0xD0) {
                    r.p += 1;
                } else if (type >= 0x80) {
                    r.p += 2;
                } else {
                    r.p++;
                }
            }
            r.p = end;
            tracks.add(notes);
            names.add(name);
        }

        // tick -> seconds through the tempo map
        tempos.sort((a, b) -> Long.compare(a[0], b[0]));
        int nt = tempos.size();
        long[] segTick = new long[nt], segUs = new long[nt];
        double[] segSec = new double[nt];
        double sec = 0;
        for (int i = 0; i < nt; i++) {
            if (i > 0) sec += (tempos.get(i)[0] - tempos.get(i - 1)[0]) * (double) tempos.get(i - 1)[1] / 1e6 / division;
            segTick[i] = tempos.get(i)[0];
            segUs[i] = tempos.get(i)[1];
            segSec[i] = sec;
        }

        // keep tracks with notes, minus the drum channel
        List<List<long[]>> noteTracks = new ArrayList<>();
        for (List<long[]> tr : tracks) {
            List<long[]> keep = new ArrayList<>();
            for (long[] x : tr) if (x[3] != 9) keep.add(x);
            if (!keep.isEmpty()) noteTracks.add(keep);
        }
        if (noteTracks.isEmpty()) throw new IOException("No playable notes in this file.");
        int total = 0;
        for (List<long[]> tr : noteTracks) total += tr.size();
        if (total > 200000) throw new IOException("This file has too many notes.");

        float[] t = new float[total], d = new float[total];
        byte[] note = new byte[total], hand = new byte[total];
        int k = 0;
        float first = Float.MAX_VALUE;
        for (int i = 0; i < noteTracks.size(); i++) {
            for (long[] x : noteTracks.get(i)) {
                float st = (float) toSec(x[0], segTick, segUs, segSec, division);
                float et = (float) toSec(x[1], segTick, segUs, segSec, division);
                t[k] = st;
                d[k] = Math.max(0.06f, et - st);
                note[k] = (byte) x[2];
                if (noteTracks.size() == 1) hand[k] = x[2] >= 60 ? Song.RIGHT : Song.LEFT;
                else hand[k] = i == 0 ? Song.RIGHT : (i == 1 ? Song.LEFT : Song.BACKING);
                first = Math.min(first, st);
                k++;
            }
        }
        for (int i = 0; i < total; i++) t[i] -= first; // start promptly
        String title = fileName.replaceAll("(?i)\\.midi?$", "");
        String info = noteTracks.size() == 1 ? "One track, split at middle C."
                : "Right hand: first track, left hand: second track"
                + (noteTracks.size() > 2 ? ", " + (noteTracks.size() - 2) + " more as backing." : ".");
        return new Song("file:" + fileName, title, "Your MIDI file", "Your MIDI files", info, 0,
                t, d, note, hand, true);
    }

    private static double toSec(long tick, long[] segTick, long[] segUs, double[] segSec, int division) {
        int s = 0;
        for (int i = 0; i < segTick.length; i++) { if (segTick[i] <= tick) s = i; else break; }
        return segSec[s] + (tick - segTick[s]) * (double) segUs[s] / 1e6 / division;
    }

    /** Bounds-checked big-endian reader (malformed files throw IOException, never crash). */
    private static final class Reader {
        final byte[] b;
        int p;

        Reader(byte[] b) { this.b = b; }

        int at(int i) throws IOException {
            if (i < 0 || i >= b.length) throw new IOException("The MIDI file is cut short.");
            return b[i] & 0xFF;
        }

        int u8() throws IOException { return at(p++); }

        int u8peek() throws IOException { return at(p); }

        int u16() throws IOException { int v = (at(p) << 8) | at(p + 1); p += 2; return v; }

        int u32() throws IOException {
            long v = ((long) at(p) << 24) | (at(p + 1) << 16) | (at(p + 2) << 8) | at(p + 3);
            p += 4;
            if (v > Integer.MAX_VALUE) throw new IOException("Broken MIDI chunk.");
            return (int) v;
        }

        String str(int n) throws IOException {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < n; i++) sb.append((char) u8());
            return sb.toString();
        }

        int vlq() throws IOException {
            int x = 0, c, guard = 0;
            do {
                c = u8();
                x = (x << 7) | (c & 0x7F);
                if (++guard > 4) throw new IOException("Broken MIDI timing.");
            } while ((c & 0x80) != 0);
            return x;
        }
    }
}

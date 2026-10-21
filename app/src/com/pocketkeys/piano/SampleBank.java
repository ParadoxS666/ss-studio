package com.pocketkeys.piano;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Stereo int16 multisample bank (see buildtools/prep_piano.py for the format). */
final class SampleBank {
    final int count;
    final int[] root, sampleRate, frames, loopStart, loopEnd, offset;
    final float[] tailDecayDb;
    final float[] tuneCents; // how sharp each recording is (corrected on playback)
    final short[] data; // interleaved stereo

    SampleBank(byte[] file) {
        ByteBuffer b = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
        if (b.getInt() != 0x4E504B50) throw new IllegalArgumentException("bad magic"); // 'PKPN'
        int version = b.getInt();
        if (version < 1 || version > 2) throw new IllegalArgumentException("unsupported bank version " + version);
        count = b.getInt();
        root = new int[count];
        sampleRate = new int[count];
        frames = new int[count];
        loopStart = new int[count];
        loopEnd = new int[count];
        offset = new int[count];
        tailDecayDb = new float[count];
        tuneCents = new float[count];
        for (int i = 0; i < count; i++) {
            root[i] = b.get() & 0xFF;
            b.get(); b.get(); b.get();
            sampleRate[i] = b.getInt();
            frames[i] = b.getInt();
            loopStart[i] = b.getInt();
            loopEnd[i] = b.getInt();
            tailDecayDb[i] = b.getFloat();
            offset[i] = b.getInt();
            if (version >= 2) tuneCents[i] = b.getFloat();
        }
        int headerBytes = b.position();
        int samples = (file.length - headerBytes) / 2;
        data = new short[samples];
        b.asShortBuffer().get(data);
    }

    /** Index of the sample whose root is closest to {@code note}. */
    int nearest(int note) {
        int best = 0, bestD = Integer.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            int d = Math.abs(root[i] - note);
            if (d < bestD) { bestD = d; best = i; }
        }
        return best;
    }
}

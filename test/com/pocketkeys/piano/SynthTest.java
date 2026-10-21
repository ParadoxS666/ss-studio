package com.pocketkeys.piano;

import java.io.*;
import java.nio.*;
import java.nio.file.*;

/** Offline checks for the synth (runs on a desktop JVM, not shipped). */
public class SynthTest {
    static final int SR = 48000;

    public static void main(String[] a) throws Exception {
        byte[] bank = Files.readAllBytes(Paths.get(a[0]));
        String outDir = a[1];
        SampleBank sb = new SampleBank(bank);
        System.out.println("bank: " + sb.count + " samples, " + sb.data.length / 2 + " frames");

        for (int p = 0; p < Synth.PRESET_COUNT; p++) {
            Synth s = new Synth(SR);
            s.bank = sb;
            s.setPreset(p);
            s.setReverb(0.3f);
            float[] audio = phrase(s);
            stats(Synth.NAMES[p], audio);
            writeWav(outDir + "/preset_" + p + ".wav", audio);
        }
        // retrigger / stealing torture test
        {
            Synth s = new Synth(SR);
            s.bank = sb;
            float[] out = new float[SR * 6 * 2];
            float[] blk = new float[192 * 2];
            int pos = 0, step = 0;
            java.util.Random r = new java.util.Random(1);
            s.setSustain(true);
            while (pos < out.length) {
                if (step % 3 == 0) s.noteOn(36 + r.nextInt(60), 20 + r.nextInt(107));
                if (step % 5 == 0) s.noteOff(36 + r.nextInt(60));
                if (step % 97 == 0) s.setPreset(r.nextInt(Synth.PRESET_COUNT));
                if (step == 900) s.setSustain(false);
                s.render(blk, 192);
                System.arraycopy(blk, 0, out, pos, Math.min(blk.length, out.length - pos));
                pos += blk.length;
                step++;
            }
            stats("torture", out);
            writeWav(outDir + "/torture.wav", out);
        }
        // CPU benchmark: 32 held voices per preset, render 10 s in 96-frame bursts
        for (int p = 0; p < Synth.PRESET_COUNT; p++) {
            Synth s = new Synth(SR);
            s.bank = sb;
            s.setPreset(p);
            float[] blk = new float[96 * 2];
            for (int w = 0; w < 2; w++) { // warm-up then measure
                s.allNotesOff();
                for (int k = 0; k < 2000; k++) s.render(blk, 96);
                s.setSustain(true);
                for (int k = 0; k < 32; k++) s.noteOn(40 + k, 100);
                long t0 = System.nanoTime();
                int blocks = SR * 10 / 96;
                for (int k = 0; k < blocks; k++) {
                    if (p == Synth.P_PIANO && k % 500 == 0) { // keep piano voices alive
                        for (int j = 0; j < 32; j++) s.noteOn(40 + j, 100);
                    }
                    s.render(blk, 96);
                }
                long t1 = System.nanoTime();
                if (w == 1) System.out.printf("cpu %-15s 32 voices: %.1f%% of one core (%d active at end)%n",
                        Synth.NAMES[p], (t1 - t0) / 1e9 / 10.0 * 100, s.activeVoices());
            }
        }
    }

    static float[] phrase(Synth s) {
        int total = SR * 7;
        float[] out = new float[total * 2];
        float[] blk = new float[96 * 2];
        int frame = 0;
        int[][] ev = {
                // time(ms), type(0 on,1 off,2 sus,3 bend,4 mod), a, b
                {0, 0, 48, 90}, {0, 0, 55, 80}, {0, 0, 60, 85}, {0, 0, 64, 85},
                {600, 1, 48, 0}, {600, 1, 55, 0}, {600, 1, 60, 0}, {600, 1, 64, 0},
                {700, 0, 72, 110}, {900, 1, 72, 0}, {900, 0, 74, 60}, {1100, 1, 74, 0},
                {1100, 0, 76, 30}, {1300, 1, 76, 0}, {1300, 0, 79, 127}, {1500, 1, 79, 0},
                {1600, 2, 1, 0}, {1600, 0, 36, 100}, {1600, 0, 43, 90}, {1700, 1, 36, 0}, {1700, 1, 43, 0},
                {1800, 0, 84, 100}, {1900, 1, 84, 0}, {2000, 0, 88, 100}, {2100, 1, 88, 0},
                {2200, 0, 96, 110}, {2300, 1, 96, 0}, {2400, 0, 21, 120}, {2500, 1, 21, 0},
                {2600, 0, 108, 120}, {2700, 1, 108, 0},
                {4200, 2, 0, 0},
                {4400, 0, 67, 100}, {4600, 3, 16383, 0}, {4900, 3, 8192, 0}, {5000, 4, 127, 0},
                {5800, 1, 67, 0}, {5800, 4, 0, 0},
                {5900, 0, 60, 100}, {5905, 0, 60, 100}, {5910, 0, 60, 100}, {6100, 1, 60, 0},
        };
        int e = 0;
        while (frame < total) {
            int ms = frame * 1000 / SR;
            while (e < ev.length && ev[e][0] <= ms) {
                int[] x = ev[e++];
                switch (x[1]) {
                    case 0: s.noteOn(x[2], x[3]); break;
                    case 1: s.noteOff(x[2]); break;
                    case 2: s.setSustain(x[2] == 1); break;
                    case 3: s.setBend(x[2]); break;
                    case 4: s.setMod(x[2]); break;
                }
            }
            int n = Math.min(96, total - frame);
            s.render(blk, n);
            System.arraycopy(blk, 0, out, frame * 2, n * 2);
            frame += n;
        }
        return out;
    }

    static void stats(String name, float[] x) {
        double sum = 0, pk = 0, maxJump = 0;
        int nan = 0, clipped = 0;
        for (int i = 0; i < x.length; i++) {
            float v = x[i];
            if (Float.isNaN(v) || Float.isInfinite(v)) { nan++; continue; }
            sum += v * v;
            pk = Math.max(pk, Math.abs(v));
            if (Math.abs(v) >= 0.999f) clipped++;
            if (i >= 2) maxJump = Math.max(maxJump, Math.abs(v - x[i - 2]));
        }
        double rms = Math.sqrt(sum / x.length);
        System.out.printf("%-15s peak %.3f  rms %.4f (%.1f dBFS)  maxStep %.3f  nan %d  clipped %d%n",
                name, pk, rms, 20 * Math.log10(rms + 1e-12), maxJump, nan, clipped);
    }

    static void writeWav(String path, float[] x) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(44 + x.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + x.length * 2).put("WAVE".getBytes());
        b.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 2).putInt(SR)
                .putInt(SR * 4).putShort((short) 4).putShort((short) 16);
        b.put("data".getBytes()).putInt(x.length * 2);
        for (float v : x) b.putShort((short) Math.max(-32768, Math.min(32767, Math.round(v * 32767))));
        Files.write(Paths.get(path), b.array());
    }
}

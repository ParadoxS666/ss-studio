package com.pocketkeys.piano;

import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Plays every sound at many notes and measures the pitch you'd hear with YIN
 * over the full range (catches octave errors, unlike a narrow search).
 * Fails if any sound is more than 35 cents from the key's pitch.
 */
public class PitchTest {
    static final int SR = 48000;

    public static void main(String[] a) throws Exception {
        SampleBank sb = new SampleBank(Files.readAllBytes(Paths.get(a[0])));
        int[] notes = {28, 33, 36, 40, 45, 48, 52, 55, 57, 60, 62, 64, 67, 69, 71, 72, 76, 79, 81, 84, 88, 91, 96};
        int fails = 0;
        for (int p = 0; p < Synth.PRESET_COUNT; p++) {
            StringBuilder sbOut = new StringBuilder();
            int worst = 0;
            for (int n : notes) {
                Synth s = new Synth(SR);
                s.bank = sb;
                s.setPreset(p);
                s.setReverb(0f);
                s.noteOn(n, 100);
                int N = SR; // 1 s
                float[] buf = new float[N * 2], blk = new float[192];
                for (int i = 0; i < N * 2; i += 192) { s.render(blk, 96); System.arraycopy(blk, 0, buf, i, 192); }
                float[] mono = new float[N];
                for (int i = 0; i < N; i++) mono[i] = buf[2 * i] + buf[2 * i + 1];
                // analyse after the attack (slow sounds need longer)
                int start = (p == Synth.P_PAD || p == Synth.P_STRINGS) ? SR * 6 / 10 : SR / 8;
                double f = yin(mono, start, SR / 4);
                double exp = 440 * Math.pow(2, (n - 69) / 12.0);
                double cents = f > 0 ? 1200 * Math.log(f / exp) / Math.log(2) : 9999;
                int c = (int) Math.round(cents);
                if (Math.abs(c) > Math.abs(worst)) worst = c;
                boolean bad = Math.abs(cents) > 35;
                if (bad) fails++;
                sbOut.append(String.format(" %d:%s%+d", n, bad ? "!" : "", c));
            }
            System.out.printf("%-15s worst %+5d c |%s%n", Synth.NAMES[p], worst, sbOut);
        }
        System.out.println(fails == 0 ? "ALL PASSED" : fails + " notes off pitch");
        System.exit(fails == 0 ? 0 : 1);
    }

    /** YIN fundamental estimate (Hz) over 25 Hz .. 2.5 kHz, or -1. */
    static double yin(float[] x, int start, int len) {
        int maxLag = SR / 25, minLag = SR / 2500;
        double[] d = new double[maxLag + 2];
        for (int tau = 1; tau <= maxLag + 1; tau++) {
            double s = 0;
            for (int i = start; i < start + len; i++) {
                double diff = x[i] - x[i + tau];
                s += diff * diff;
            }
            d[tau] = s;
        }
        double run = 0;
        double[] cmnd = new double[maxLag + 2];
        cmnd[0] = 1;
        for (int tau = 1; tau <= maxLag + 1; tau++) {
            run += d[tau];
            cmnd[tau] = d[tau] * tau / (run + 1e-20);
        }
        int best = -1;
        for (int tau = minLag; tau <= maxLag; tau++) {
            if (cmnd[tau] < 0.12) {
                while (tau + 1 <= maxLag && cmnd[tau + 1] < cmnd[tau]) tau++;
                best = tau;
                break;
            }
        }
        if (best < 0) { // no clear dip: take the global minimum
            double m = 1e9;
            for (int tau = minLag; tau <= maxLag; tau++) if (cmnd[tau] < m) { m = cmnd[tau]; best = tau; }
        }
        // parabolic interpolation
        double y0 = cmnd[best - 1], y1 = cmnd[best], y2 = cmnd[best + 1];
        double denom = y0 - 2 * y1 + y2;
        double t = denom != 0 ? best + 0.5 * (y0 - y2) / denom : best;
        return SR / t;
    }
}

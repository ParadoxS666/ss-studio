package com.pocketkeys.piano;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Checks the on-screen keyboard geometry, then renders a preview PNG with
 * Ableton-style note names (C3 = middle C) and some lit keys.
 */
public class KeyboardTest {
    static int fails = 0;

    static void check(String name, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + name);
        if (!ok) fails++;
    }

    public static void main(String[] a) throws Exception {
        String[] names = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
        // 1. every black key sits on the boundary between its two white neighbours
        boolean between = true;
        for (int n = 1; n < 127; n++) {
            if (!KeyboardLayout.isBlack(n)) continue;
            float boundary = KeyboardLayout.whiteLeft(n - 1) + 1;
            boolean ok = boundary == KeyboardLayout.whiteLeft(n + 1)
                    && Math.abs(KeyboardLayout.blackCenter(n) - boundary) <= 0.1f;
            if (!ok) { between = false; System.out.println("    " + names[n % 12] + " (" + n + ") misplaced"); }
        }
        check("every black key sits between its two white neighbours", between);
        // 2. white keys are contiguous and in pitch order
        boolean contiguous = true;
        int prev = -1;
        for (int n = 0; n <= 127; n++) {
            if (KeyboardLayout.isBlack(n)) continue;
            if (prev >= 0 && KeyboardLayout.whiteLeft(n) != KeyboardLayout.whiteLeft(prev) + 1) contiguous = false;
            prev = n;
        }
        check("white keys are contiguous, in order", contiguous);
        // 3. 7 white + 5 black per octave
        int w = 0, b = 0;
        for (int n = 60; n < 72; n++) if (KeyboardLayout.isBlack(n)) b++; else w++;
        check("7 white and 5 black keys per octave", w == 7 && b == 5);
        // 4. tapping the middle of every key finds that key (black: upper half; white: lower part)
        boolean roundTrip = true;
        for (int n = 0; n <= 127; n++) {
            float cx = (KeyboardLayout.keyLeft(n) + KeyboardLayout.keyRight(n)) / 2;
            float cy = KeyboardLayout.isBlack(n) ? 0.3f : 0.85f;
            if (KeyboardLayout.noteAt(cx, cy) != n) { roundTrip = false; System.out.println("    " + n + " -> " + KeyboardLayout.noteAt(cx, cy)); }
        }
        check("each key's centre maps back to that key (all 128)", roundTrip);
        // 5. black keys never overlap each other
        boolean noOverlap = true;
        for (int n = 0; n < 127; n++) {
            if (!KeyboardLayout.isBlack(n)) continue;
            for (int m = n + 1; m <= Math.min(127, n + 3); m++)
                if (KeyboardLayout.isBlack(m) && KeyboardLayout.keyRight(n) > KeyboardLayout.keyLeft(m)) noOverlap = false;
        }
        check("black keys never overlap", noOverlap);

        // 6. the MPK's 25 keys: two octaves C..C, 15 white keys
        int whites = 0;
        for (int n = 48; n <= 72; n++) if (!KeyboardLayout.isBlack(n)) whites++;
        check("25 keys from C to C: 15 white, 10 black", whites == KeyboardLayout.MPK_WHITES
                && KeyboardLayout.whiteLeft(72) + 1 - KeyboardLayout.whiteLeft(48) == KeyboardLayout.MPK_WHITES);
        // 7. following the MPK's OCT through the notes: always contains the note, always a C, only octave moves
        int lo = KeyboardLayout.MPK_DEFAULT_LO;
        boolean contains = true, onC = true, minimal = true;
        java.util.Random rnd = new java.util.Random(3);
        for (int i = 0; i < 5000; i++) {
            int note = rnd.nextInt(121);
            int nl = KeyboardLayout.followKeys(lo, note);
            if (note < nl || note > nl + 24) contains = false;
            if (nl % 12 != 0 || nl < KeyboardLayout.MPK_MIN_LO || nl > KeyboardLayout.MPK_MAX_LO) onC = false;
            if (note >= lo && note <= lo + 24 && nl != lo) minimal = false;
            lo = nl;
        }
        check("every note 0..120 lands on the 25 keys shown", contains);
        check("the keys always start on a C, OCT -4..+4", onC);
        check("the keys only move when a note is outside them", minimal);
        check("clampKeys snaps to an octave of the default", KeyboardLayout.clampKeys(53) == 48 && KeyboardLayout.clampKeys(200) == 96);

        // preview: the MPK's 25 keys, C2..C4 (Ableton names), keys C3 E3 G#3 A#3 held
        int[] lit = {60, 64, 68, 70};
        lo = 48;
        int hi = 72;
        float x0 = KeyboardLayout.whiteLeft(lo), x1 = KeyboardLayout.whiteLeft(hi) + 1;
        int W = 1200, H = 260, top = 40;
        float u = W / (x1 - x0);
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x07080A));
        g.fillRect(0, 0, W, H);
        g.setFont(new Font("SansSerif", Font.BOLD, 15));
        for (int n = lo; n <= hi; n++) {
            if (KeyboardLayout.isBlack(n)) continue;
            int x = Math.round((KeyboardLayout.whiteLeft(n) - x0) * u);
            boolean on = java.util.Arrays.stream(lit).anyMatch(v -> v == 0) || contains(lit, n);
            g.setColor(on ? new Color(0xFF9F43) : new Color(0xE9E9EA));
            g.fillRoundRect(x + 1, top, Math.round(u) - 2, H - top - 4, 6, 6);
            g.setColor(new Color(0x444444));
            String label = n % 12 == 0 ? "C" + (n / 12 - 2) : "";
            if (on) label = names[n % 12] + (n / 12 - 2);
            g.drawString(label, x + 6, H - 14);
        }
        for (int n = lo; n <= hi; n++) {
            if (!KeyboardLayout.isBlack(n)) continue;
            int xl = Math.round((KeyboardLayout.keyLeft(n) - x0) * u), xr = Math.round((KeyboardLayout.keyRight(n) - x0) * u);
            g.setColor(contains(lit, n) ? new Color(0xFF9F43) : new Color(0x16171A));
            g.fillRoundRect(xl, top, xr - xl, Math.round((H - top) * KeyboardLayout.BLACK_HEIGHT), 5, 5);
            if (contains(lit, n)) {
                g.setColor(Color.WHITE);
                g.drawString(names[n % 12] + (n / 12 - 2), xl - 4, top - 8);
            }
        }
        g.setColor(Color.WHITE);
        g.drawString("Held: C3  E3  G#3  A#3   (Ableton names: C3 = note 60)", 10, 20);
        g.dispose();
        javax.imageio.ImageIO.write(img, "png", new File(a[0]));
        System.out.println(fails == 0 ? "ALL PASSED" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }

    static boolean contains(int[] a, int v) {
        for (int x : a) if (x == v) return true;
        return false;
    }
}

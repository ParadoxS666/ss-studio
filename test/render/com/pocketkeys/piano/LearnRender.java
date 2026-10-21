package com.pocketkeys.piano;

import android.graphics.Canvas;
import android.graphics.RectF;
import android.view.MotionEvent;

import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * Renders the real Learn screen with desktop stand-ins for Android's drawing
 * classes, driving it through taps and key presses, and saves PNGs.
 * args: <assets dir> <out dir>
 */
public class LearnRender {
    static final float DENSITY = 2.625f;

    static class Prefs implements android.content.SharedPreferences {
        final Map<String, Object> m = new HashMap<>();
        public int getInt(String k, int d) { Object o = m.get(k); return o == null ? d : (Integer) o; }
        public boolean getBoolean(String k, boolean d) { Object o = m.get(k); return o == null ? d : (Boolean) o; }
        public float getFloat(String k, float d) { Object o = m.get(k); return o == null ? d : (Float) o; }
        public String getString(String k, String d) { Object o = m.get(k); return o == null ? d : (String) o; }
        public boolean contains(String k) { return m.containsKey(k); }
        public Editor edit() {
            return new Editor() {
                public Editor putInt(String k, int v) { m.put(k, v); return this; }
                public Editor putBoolean(String k, boolean v) { m.put(k, v); return this; }
                public Editor putFloat(String k, float v) { m.put(k, v); return this; }
                public Editor putString(String k, String v) { if (v == null) m.remove(k); else m.put(k, v); return this; }
                public void apply() { }
            };
        }
    }

    static class Host implements LearnView.Host {
        int closes, opens;
        public void closeLearn() { closes++; }
        public void openMidiFile() { opens++; }
    }

    static LearnView v;
    static Prefs prefs;
    static Host host;
    static File out;
    static int fails;

    static void check(String n, boolean ok) { System.out.println((ok ? "  ok   " : "  FAIL ") + n); if (!ok) fails++; }

    static Object get(String f) throws Exception { Field x = LearnView.class.getDeclaredField(f); x.setAccessible(true); return x.get(v); }
    static void set(String f, Object val) throws Exception { Field x = LearnView.class.getDeclaredField(f); x.setAccessible(true); x.set(v, val); }

    static BufferedImage draw(float dtSeconds) throws Exception {
        set("lastFrameNs", System.nanoTime() - (long) (dtSeconds * 1e9));
        BufferedImage img = new BufferedImage(v.getWidth(), v.getHeight(), BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        v.draw(new Canvas(g));
        g.dispose();
        return img;
    }

    static void save(String name) throws Exception {
        BufferedImage img = draw(0);
        javax.imageio.ImageIO.write(img, "png", new File(out, name + ".png"));
    }

    /** Centre of the last target with this id, or null. */
    static float[] target(int id) throws Exception {
        int n = (Integer) get("nT");
        int[] ids = (int[]) get("tId");
        RectF[] rs = (RectF[]) get("tRect");
        for (int i = n - 1; i >= 0; i--) if (ids[i] == id) return new float[]{rs[i].centerX(), rs[i].centerY()};
        return null;
    }

    static boolean tap(int id) throws Exception {
        draw(0);
        float[] p = target(id);
        if (p == null) { System.out.println("    (no target " + id + ")"); return false; }
        v.onTouchEvent(new MotionEvent(MotionEvent.ACTION_DOWN, p[0], p[1]));
        v.onTouchEvent(new MotionEvent(MotionEvent.ACTION_UP, p[0], p[1]));
        return true;
    }

    static void run(float seconds) throws Exception { for (float t = 0; t < seconds; t += 1 / 60f) draw(1 / 60f); }

    static int sint(String f) throws Exception { Field x = LearnSession.class.getDeclaredField(f); x.setAccessible(true); return ((Number) x.get(get("session"))).intValue(); }
    static float sfloat(String f) throws Exception { Field x = LearnSession.class.getDeclaredField(f); x.setAccessible(true); return ((Number) x.get(get("session"))).floatValue(); }
    static boolean sbool(String f) throws Exception { Field x = LearnSession.class.getDeclaredField(f); x.setAccessible(true); return (Boolean) x.get(get("session")); }

    /** Plays the next chord the session is waiting for, as the MIDI thread would. */
    static void playNext() throws Exception {
        LearnSession s = (LearnSession) get("session");
        int[] g = notes(s.nextGroup());
        long now = System.nanoTime();
        for (int n : g) v.onKey(n, 90, true, now);
        draw(1 / 60f);
        for (int n : g) v.onKey(n, 0, false, now);
    }

    /** The MPK keys to press for these notes of the (fitted) piece. */
    static int[] notes(int[] idx) throws Exception {
        LearnSession s = (LearnSession) get("session");
        int[] n = new int[idx.length];
        for (int i = 0; i < idx.length; i++) n[i] = s.song.note[idx[i]] + s.keyShift;
        return n;
    }

    static int firstRow() throws Exception {
        draw(0);
        int n = (Integer) get("nT");
        int[] ids = (int[]) get("tId");
        for (int i = 0; i < n; i++) if (ids[i] >= 1000 && ids[i] < 5000) return ids[i];
        return -1;
    }

    static LearnView make(int wDp, int hDp, Prefs p) throws Exception {
        File assets = new File(argsAssets);
        File files = new File(out, "files");
        files.mkdirs();
        android.content.Context ctx = new android.content.Context(DENSITY, 1f, assets, files);
        AudioEngine eng = new AudioEngine();
        MidiRouter router = new MidiRouter(eng, p);
        host = new Host();
        LearnView lv = new LearnView(ctx, eng, router, p, host);
        lv.setSize(Math.round(wDp * DENSITY), Math.round(hDp * DENSITY));
        return lv;
    }

    static String argsAssets;

    public static void main(String[] a) throws Exception {
        argsAssets = a[0];
        out = new File(a[1]);
        out.mkdirs();
        for (String orient : new String[]{"portrait", "landscape"}) {
            boolean land = orient.equals("landscape");
            int w = land ? 915 : 412, h = land ? 412 : 915;
            System.out.println(orient);
            prefs = new Prefs();
            v = make(w, h, prefs);
            v.onShown();
            v.setKeyboardConnected(true);
            save(orient + "-1-intro");
            check("intro overlay shown first", (Integer) get("overlay") == 1);

            // the song library
            check("Songs button opens the library", tap(3) && (Integer) get("overlay") == 3);
            save(orient + "-2-library");
            // scroll the library to the end
            float[] p = target(72);
            if (p != null) {
                v.onTouchEvent(new MotionEvent(MotionEvent.ACTION_DOWN, p[0], p[1] + 200));
                for (int k = 1; k <= 30; k++) v.onTouchEvent(new MotionEvent(MotionEvent.ACTION_MOVE, p[0], p[1] + 200 - k * 300));
                v.onTouchEvent(new MotionEvent(MotionEvent.ACTION_UP, p[0], p[1] + 200 - 9000));
            }
            save(orient + "-3-library-end");
            check("library scrolled", (Float) get("libScroll") > 0);
            // pick the first song (C major scale) by scrolling back up and tapping its row
            set("libScroll", 0f);
            int row = firstRow();
            check("library has song rows to tap", row >= 1000);
            tap(row);
            check("picking a song shows its intro card", (Integer) get("overlay") == 1);
            // change song via the title (top bar works while the intro card is up)
            check("title opens the library from the intro card", tap(2) && (Integer) get("overlay") == 3);
            set("libScroll", 0f);
            draw(0);
            // Minuet in G: the first row of the "Level 1" group
            java.lang.reflect.Method m = LearnView.class.getDeclaredMethod("libRows");
            m.setAccessible(true);
            java.util.List<?> list = (java.util.List<?>) m.invoke(v);
            int minuet = -1;
            for (int i = 0; i < list.size(); i++) if (list.get(i) instanceof Song && ((Song) list.get(i)).id.equals("minuet-g")) minuet = i;
            set("libScroll", Math.max(0f, (minuet - 3) * 62 * DENSITY));
            tap(1000 + minuet);
            Song cur = (Song) get("song");
            check("picked Minuet in G from the library", cur.id.equals("minuet-g"));
            save(orient + "-4-picked");

            // play: both hands, Wait mode, 75% speed, play some notes
            tap(22);      // Both hands
            tap(30);      // speed -
            tap(30);
            tap(30);
            tap(30);
            tap(30);
            check("speed stepper: 75%", Math.abs((Float) get("speed") - 0.75f) < 1e-3);
            check("Start from the intro card", tap(60) && (Integer) get("overlay") == 0 && sbool("playing"));
            run(2.2f);
            for (int k = 0; k < 12; k++) { playNext(); run(0.25f); }
            // hold a chord the player is pressing right now, then render
            LearnSession s = (LearnSession) get("session");
            int[] g = notes(s.nextGroup());
            long now = System.nanoTime();
            if (g != null) for (int x : g) v.onKey(x, 90, true, now);
            v.onKey(61, 90, true, now);   // and a wrong key
            draw(1 / 60f);
            save(orient + "-5-playing");
            check("hits counted while playing", sint("hits") >= 12);
            for (int x = 0; x < 128; x++) v.onKey(x, 0, false, now);

            // pause
            tap(50);
            save(orient + "-6-paused");
            check("Pause button pauses", !sbool("playing"));

            // loop A/B in Play mode on Für Elise-like song
            tap(11);   // Play mode
            check("mode change goes back to the intro card", (Integer) get("overlay") == 1 && (Integer) get("mode") == 1);
            tap(60);
            run(3f);
            tap(52);
            run(2f);
            tap(53);
            run(0.5f);
            save(orient + "-7-play-loop");
            check("loop set", !Float.isNaN(sfloat("loopA")) && !Float.isNaN(sfloat("loopB")));
            tap(53);

            // results: play the C major scale through in Wait mode
            tap(3);
            set("libScroll", 0f);
            tap(firstRow());
            check("mode can be changed while the intro card is up", tap(10) && (Integer) get("mode") == 0 && (Integer) get("overlay") == 1);
            save(orient + "-7b-intro-wait-both-75");
            tap(60);
            run(2.5f);
            for (int k = 0; k < 200 && !sbool("finished"); k++) { playNext(); run(0.15f); }
            run(0.5f);
            check("song finished -> results card", (Integer) get("overlay") == 2);
            save(orient + "-8-results");
            check("best score saved", prefs.m.keySet().stream().anyMatch(k -> k.startsWith("best:")));
            check("stars saved", prefs.m.keySet().stream().anyMatch(k -> k.startsWith("stars:")));

            // a run where you jump with the timeline isn't saved as a best
            Object bestBefore = new HashMap<>(prefs.m);
            tap(62);   // Play again
            run(2.5f);
            float[] tl = target(54);
            v.onTouchEvent(new MotionEvent(MotionEvent.ACTION_DOWN, tl[0], tl[1]));
            v.onTouchEvent(new MotionEvent(MotionEvent.ACTION_UP, tl[0], tl[1]));
            for (int k = 0; k < 200 && !sbool("finished"); k++) { playNext(); run(0.15f); }
            run(0.5f);
            check("jumped run finishes as practice, best score untouched", sbool("practice") && (Integer) get("overlay") == 2 && prefs.m.equals(bestBefore));
            save(orient + "-8b-results-practice");

            // error card, then back closes it
            v.showError("That file isn't a MIDI file (it doesn't start with MThd).");
            save(orient + "-9-error");
            check("back closes the error card", v.handleBack() && (Integer) get("overlay") != 4);

            // library with an imported file
            byte[] mid = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(a[2]));
            v.addImportedFile("My song (test).mid", mid);
            check("imported file selected", ((Song) get("song")).id.startsWith("file:"));
            tap(3);
            set("libScroll", 1e6f);
            draw(0);
            save(orient + "-10-library-imported");
            check("library close button", tap(70) && (Integer) get("overlay") != 3);
            check("back button closes Learn", tap(1) && host.closes == 1);
            for (File f : new File(out, "files/learn_songs").listFiles()) f.delete();
        }
        System.out.println(fails == 0 ? "ALL PASSED" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}

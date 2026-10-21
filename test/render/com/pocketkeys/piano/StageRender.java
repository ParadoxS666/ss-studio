package com.pocketkeys.piano;

import android.graphics.Canvas;

import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;

/** Renders the main screen (header with the Learn button, the button pop-up, settings). args: <out dir> */
public class StageRender {
    static StageView v;

    static void set(String f, Object val) throws Exception { Field x = StageView.class.getDeclaredField(f); x.setAccessible(true); x.set(v, val); }
    static Object get(String f) throws Exception { Field x = StageView.class.getDeclaredField(f); x.setAccessible(true); return x.get(v); }

    static void save(File out, String name) throws Exception {
        BufferedImage img = new BufferedImage(v.getWidth(), v.getHeight(), BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        v.draw(new Canvas(g));
        g.dispose();
        javax.imageio.ImageIO.write(img, "png", new File(out, name + ".png"));
    }

    public static void main(String[] a) throws Exception {
        File out = new File(a[0]);
        File assets = new File(a.length > 1 ? a[1] : "app/assets");
        out.mkdirs();
        float dens = 2.625f;
        for (String orient : new String[]{"portrait", "landscape"}) {
            boolean land = orient.equals("landscape");
            LearnRender.Prefs p = new LearnRender.Prefs();
            android.content.Context ctx = new android.content.Context(dens, 1f, assets, out);
            AudioEngine eng = new AudioEngine();
            MidiRouter router = new MidiRouter(eng, p);
            v = new StageView(ctx, eng, router, p);
            v.setSize(Math.round((land ? 915 : 412) * dens), Math.round((land ? 412 : 915) * dens));
            set("devices", java.util.Arrays.asList("MPK mini IV"));
            set("bankLoaded", true);
            set("latency", 38f);
            set("route", "Headphones · WH-1000XM6 (LE Audio)");
            save(out, "stage-" + orient + "-1-main");
            v.onControlCandidate("Knob K3 (CC 72)");
            save(out, "stage-" + orient + "-2-banner");
            set("candidate", null);
            set("sheetOpen", true);
            for (int k = 0; k < 8; k++) {
                set("sheetScroll", k * 500f * dens);
                save(out, "stage-" + orient + "-3-settings-" + k);
                float max = (Float) get("sheetContentH");
                if (k * 500f * dens > max) break;
            }
        }
        System.out.println("rendered");
    }
}

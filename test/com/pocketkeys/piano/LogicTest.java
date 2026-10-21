package com.pocketkeys.piano;

/** Unit tests for velocity curves (desktop JVM). */
public class LogicTest {
    static int failures = 0;

    static void check(String name, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + name);
        if (!ok) failures++;
    }

    static String roles(String[] names) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.length; i++) sb.append(PortRoles.roleFor(names, i) == PortRoles.KEYS ? 'K' : 'C');
        return sb.toString();
    }

    public static void main(String[] a) {
        long t = 0;
        final long S = 1_000_000_000L;

        System.out.println("VelocityCurve");
        VelocityCurve n = VelocityCurve.preset(VelocityCurve.P_NORMAL, null);
        check("normal is identity-ish (64 -> 64)", Math.abs(n.map(64) - 64) <= 1);
        check("normal 127 -> 127, 1 -> <=2", n.map(127) == 127 && n.map(1) <= 2);
        VelocityCurve l = VelocityCurve.preset(VelocityCurve.P_LIGHT, n);
        check("light: 64 sounds louder than normal", l.map(64) > n.map(64) + 10);
        check("light: full force at 110", l.map(110) == 127 && l.map(127) == 127);
        VelocityCurve h = VelocityCurve.preset(VelocityCurve.P_HEAVY, n);
        check("heavy: 64 sounds softer than normal", h.map(64) < n.map(64) - 10);
        VelocityCurve f = VelocityCurve.preset(VelocityCurve.P_FIXED, n.withFixedValue(90));
        check("fixed always 90", f.map(5) == 90 && f.map(127) == 90);
        VelocityCurve r = n.withSoftest(40).withLoudest(100);
        check("range 40..100", r.map(1) >= 40 && r.map(127) == 100);
        check("softest > loudest is reordered", n.withSoftest(120).withLoudest(50).softest <= n.withSoftest(120).withLoudest(50).loudest);
        boolean mono = true;
        for (float c = -1f; c <= 1f; c += 0.25f) {
            VelocityCurve q = n.withCurve(c);
            for (int v = 1; v < 127; v++) if (q.map(v + 1) < q.map(v)) mono = false;
        }
        check("all curves are monotonic", mono);
        check("presets recognised", n.matchingPreset() == VelocityCurve.P_NORMAL
                && l.matchingPreset() == VelocityCurve.P_LIGHT && h.matchingPreset() == VelocityCurve.P_HEAVY
                && f.matchingPreset() == VelocityCurve.P_FIXED && r.matchingPreset() == VelocityCurve.P_CUSTOM);

        System.out.println("PortRoles");
        String[] akai = {"MPK Mini IV MIDI Port", "MPK Mini IV DAW Port", "MPK Mini IV Plugin Port",
                "MPK Mini IV Software Control Port", "MPK Mini IV Din Port"};
        check("Akai names: only Port 1 plays", roles(akai).equals("KCCCC"));
        check("no names (5 ports): only port 1 plays", roles(new String[5]).equals("KCCCC"));
        check("generic names: only port 1 plays", roles(new String[]{"MPK mini IV 1", "MPK mini IV 2", "MPK mini IV 3"}).equals("KCC"));
        check("single-port keyboard plays", roles(new String[]{null}).equals("K"));
        check("Ableton-style names (Port 2 DAW)", roles(new String[]{"MPK mini IV", "MPK mini IV (Port 2) DAW"}).equals("KC"));
        check("MIDI Port + DAW + unknown name: only MIDI Port plays", roles(new String[]{"X MIDI Port", "X DAW Port", "X Port 3"}).equals("KCC"));
        check("every name looks like control: first port plays", roles(new String[]{"KeyLab Control 1", "KeyLab Control 2"}).equals("KC"));
        check("control port listed first: the other one plays", roles(new String[]{"X DAW", "X Keys"}).equals("CK"));
        boolean exactlyOne = true;
        java.util.Random rnd = new java.util.Random(5);
        String[] pool = {null, "", "MIDI Port", "DAW", "Plugin Port", "Software Control", "Din Port", "Keys", "Port 7", "MIDI 1"};
        for (int trial = 0; trial < 3000; trial++) {
            String[] nm = new String[1 + rnd.nextInt(5)];
            for (int i = 0; i < nm.length; i++) nm[i] = pool[rnd.nextInt(pool.length)];
            int k = 0;
            for (int i = 0; i < nm.length; i++) if (PortRoles.roleFor(nm, i) == PortRoles.KEYS) k++;
            if (k != 1) exactlyOne = false;
        }
        check("always exactly one port plays notes (3000 random name sets)", exactlyOne);
        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}

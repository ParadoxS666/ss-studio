package com.pocketkeys.piano;

import android.content.SharedPreferences;

/**
 * Turns raw MIDI from the keyboard into instrument events:
 *  - keys always play exactly the note the keyboard sends (no app transpose, no octave guessing):
 *    like Ableton Live, the MPK applies its own OCT/transpose and the app just shows the real notes
 *  - teachable keyboard controls: Sound −/+ (pad note, CC or SysEx)
 *  - pads: ignore / play / pick a sound; Program Change picks a sound
 *  - velocity response curve
 *  - sustain, bend, mod wheel; key state for the UI; a small MIDI monitor
 */
final class MidiRouter implements MidiInput.Listener {
    interface Ui {
        void onKeysChanged();                   // any thread
        void onLearned(int which);              // any thread
        void onPresetFromKeyboard(int preset);  // any thread
        void onPresetStep(int dir);             // any thread
        void onMonitor();                       // any thread
        /** An MPK button nobody has mapped yet was pressed (null = withdraw the prompt). */
        void onControlCandidate(String description); // any thread
    }

    static final int B_SOUND_DOWN = 0, B_SOUND_UP = 1, NB = 2;

    /** Learn mode listens to the notes you play. Called on a MIDI thread. */
    interface KeyListener {
        void onKey(int note, int velocity, boolean down, long nanoTime);
    }

    private volatile KeyListener keyListener;
    private volatile boolean askButtons;

    void setKeyListener(KeyListener l) { keyListener = l; }

    /** Whether an unmapped MPK button pops up "what should this do?". */
    boolean askButtons() { return askButtons; }

    synchronized void setAskButtons(boolean on) {
        askButtons = on;
        prefs.edit().putBoolean("askButtons", on).apply();
        if (!on && candType != BIND_NONE) {
            candType = BIND_NONE;
            if (ui != null) ui.onControlCandidate(null);
        }
    }
    static final int LEARN_NONE = -1;                     // or a binding index
    static final int BIND_NONE = 0, BIND_NOTE = 1, BIND_CC = 2, BIND_SYSEX = 3;
    static final int PADS_IGNORE = 0, PADS_PLAY = 1, PADS_SOUNDS = 2;
    static final int PAD_CHANNEL = 9;                     // MIDI channel 10

    private final AudioEngine engine;
    private final SharedPreferences prefs;
    private Ui ui;

    private volatile int learn = LEARN_NONE;
    private volatile int padMode;
    private volatile VelocityCurve vel;
    private volatile int dynRange;

    // bindings: {type, channel, number, source (role|port<<4), press value}; SysEx keeps its bytes as hex
    private final int[][] bind = new int[NB][5];
    private final int[] ccLast = new int[NB];
    private final long[] ccLastTime = new long[NB];
    private final String[] bindSysex = new String[NB];
    private int lastBankMsb = -1, lastBankLsb = -1;

    // an unmapped button press waiting for the user to choose what it does
    private int candType = BIND_NONE, candCh, candNum, candSrc, candValue;
    private String candSysex, candSig;
    private final java.util.Set<String> ignored = new java.util.HashSet<>();
    private final java.util.Set<String> knobs = new java.util.HashSet<>(); // CCs that behave like knobs

    private final int[] playing = new int[16 * 128];      // (channel,key) -> note sounding
    final int[] down = new int[128];                      // keys held, per sounding note (UI)
    final boolean[] pedal = new boolean[128];             // notes held only by the pedal (UI)
    volatile boolean sustainOn;
    volatile int lastNote = -1;
    /** Lowest of the MPK's 25 keys right now (it follows the MPK's OCT buttons through the notes). */
    private volatile int keysLo;
    volatile int lastRawVel = -1, lastOutVel = -1;
    volatile long midiActivity;                           // time of last message (UI)

    // MIDI monitor: newest first
    private final String[] monitor = new String[6];

    MidiRouter(AudioEngine engine, SharedPreferences prefs) {
        this.engine = engine;
        this.prefs = prefs;
        java.util.Arrays.fill(playing, -1);
        // Ableton, Akai and Logic call note 60 "C3"; that's the default (older builds stored "middleC3")
        middleC3 = prefs.getBoolean("namesC3", true);
        // old builds stored a boolean "padsPlay"; default is now "pads pick sounds"
        padMode = prefs.getInt("padMode", prefs.getBoolean("padsPlay", false) ? PADS_PLAY : PADS_SOUNDS);
        vel = new VelocityCurve(prefs.getFloat("velCurve", 0f), prefs.getInt("velFull", 127),
                prefs.getInt("velSoft", 1), prefs.getInt("velLoud", 127),
                prefs.getBoolean("velFixed", false), prefs.getInt("velFixedValue", 100));
        // carry over the old 3-way touch setting once
        if (!prefs.contains("velCurve") && prefs.contains("touch")) {
            int t = prefs.getInt("touch", 1);
            vel = VelocityCurve.preset(t == 0 ? VelocityCurve.P_LIGHT : t == 2 ? VelocityCurve.P_HEAVY : VelocityCurve.P_NORMAL, vel);
        }
        dynRange = prefs.getInt("dynRange", 30);
        for (int i = 0; i < NB; i++) {
            // v1.2 kept Sound −/+ as bindings 2 and 3; its octave bindings are dropped
            String k = prefs.contains("sbind" + i + "t") ? "sbind" + i : "bind" + (i + 2);
            bind[i][0] = prefs.getInt(k + "t", BIND_NONE);
            bind[i][1] = prefs.getInt(k + "c", 0);
            bind[i][2] = prefs.getInt(k + "n", 0);
            bind[i][3] = prefs.getInt(k + "s", MidiInput.SRC_KEYS);
            bind[i][4] = prefs.getInt(k + "v", 127);
            bindSysex[i] = prefs.getString(k + "x", null);
            if (bind[i][0] == BIND_SYSEX && bindSysex[i] == null) bind[i][0] = BIND_NONE;
            // a piano key must never be swallowed as a button
            if (bind[i][0] == BIND_NOTE && bind[i][1] != PAD_CHANNEL && (bind[i][3] & 0xF) == MidiInput.SRC_KEYS)
                bind[i][0] = BIND_NONE;
        }
        askButtons = prefs.getBoolean("askButtons", true);
        keysLo = KeyboardLayout.clampKeys(prefs.getInt("keysLo", KeyboardLayout.MPK_DEFAULT_LO));
        String ign = prefs.getString("ignoredControls", "");
        for (String sig : ign.split("\n")) if (!sig.isEmpty()) ignored.add(sig);
    }

    void setUi(Ui ui) { this.ui = ui; }

    /** Lowest note of the 25 keys on screen; the keys are keysLo() .. keysLo() + 24. */
    int keysLo() { return keysLo; }

    /** Moves the on-screen keys by octaves, to line them up with the MPK by hand (UI thread). */
    synchronized void shiftKeys(int octaves) { setKeysLo(keysLo + 12 * octaves); }

    private void setKeysLo(int lo) {
        lo = KeyboardLayout.clampKeys(lo);
        if (lo == keysLo) return;
        keysLo = lo;
        prefs.edit().putInt("keysLo", lo).apply();
        if (ui != null) ui.onKeysChanged();
    }

    // ------------------------------------------------------------------
    // Settings (UI thread)
    // ------------------------------------------------------------------
    boolean middleCIsC3() { return middleC3; }

    synchronized void setMiddleCIsC3(boolean on) {
        middleC3 = on;
        prefs.edit().putBoolean("namesC3", on).apply();
    }

    /** Notes currently held down on the keyboard, lowest first (UI thread snapshot). */
    int heldNotes(int[] out) {
        int n = 0;
        for (int i = 0; i < 128 && n < out.length; i++) if (down[i] > 0) out[n++] = i;
        return n;
    }

    int padMode() { return padMode; }

    synchronized void setPadMode(int m) {
        padMode = m;
        prefs.edit().putInt("padMode", m).apply();
    }

    VelocityCurve velocity() { return vel; }

    synchronized void setVelocity(VelocityCurve v) {
        vel = v;
        prefs.edit().putFloat("velCurve", v.curve).putInt("velFull", v.fullForceAt)
                .putInt("velSoft", v.softest).putInt("velLoud", v.loudest)
                .putBoolean("velFixed", v.fixed).putInt("velFixedValue", v.fixedValue).apply();
    }

    int dynamicRange() { return dynRange; }

    synchronized void setDynamicRange(int db) {
        dynRange = Math.max(6, Math.min(48, db));
        engine.setDynamicRange(dynRange);
        prefs.edit().putInt("dynRange", dynRange).apply();
    }

    int learning() { return learn; }

    synchronized void startLearn(int which) { learn = which; }

    synchronized void cancelLearn() { learn = LEARN_NONE; }

    /** Cancels learning only if it is still {@code which}; returns true if it did. */
    synchronized boolean cancelLearnIf(int which) {
        if (learn != which) return false;
        learn = LEARN_NONE;
        return true;
    }

    synchronized void clearBindings() {
        for (int i = 0; i < NB; i++) bind[i][0] = BIND_NONE;
        ignored.clear(); // ask about every button again
        prefs.edit().putString("ignoredControls", "").apply();
        saveBindings();
    }

    /** The pending unmapped button becomes Sound − or Sound +. */
    synchronized void assignCandidate(int which) {
        if (candType == BIND_NONE) return;
        setBinding(which, candType, candCh, candNum, candSrc, candValue, candSysex);
        candType = BIND_NONE;
    }

    /** Never ask about the pending button again. */
    synchronized void ignoreCandidate() {
        if (candType == BIND_NONE) return;
        ignored.add(candSig);
        StringBuilder sb = new StringBuilder();
        for (String sig : ignored) sb.append(sig).append('\n');
        prefs.edit().putString("ignoredControls", sb.toString()).apply();
        candType = BIND_NONE;
    }

    synchronized void dismissCandidate() { candType = BIND_NONE; }

    synchronized boolean anyBinding() {
        for (int i = 0; i < NB; i++) if (bind[i][0] != BIND_NONE) return true;
        return false;
    }

    /** Short description of a binding, or null. */
    synchronized String bindingLabel(int which) {
        int[] b = bind[which];
        if (b[0] == BIND_NOTE) return "Note " + b[2] + " · ch " + (b[1] + 1) + portLabel(b[3]);
        if (b[0] == BIND_CC) return "CC " + b[2] + " = " + b[4] + " · ch " + (b[1] + 1) + portLabel(b[3]);
        if (b[0] == BIND_SYSEX) return "SysEx " + shortHex(bindSysex[which]);
        return null;
    }

    /** Snapshot of the MIDI monitor, newest first (nulls for empty rows). */
    synchronized String[] monitorLines() { return monitor.clone(); }

    private void saveBindings() {
        SharedPreferences.Editor e = prefs.edit();
        for (int i = 0; i < NB; i++) {
            e.putInt("sbind" + i + "t", bind[i][0]);
            e.putInt("sbind" + i + "c", bind[i][1]);
            e.putInt("sbind" + i + "n", bind[i][2]);
            e.putInt("sbind" + i + "s", bind[i][3]);
            e.putInt("sbind" + i + "v", bind[i][4]);
            e.putString("sbind" + i + "x", bindSysex[i]);
        }
        e.apply();
    }

    // ------------------------------------------------------------------
    // Devices
    // ------------------------------------------------------------------
    @Override
    public void onDevicesChanged(boolean removed, boolean hotplug) {
        // a keyboard went away: release anything it was holding
        if (removed) releaseAll();
        if (ui != null) ui.onKeysChanged();
    }

    synchronized void releaseAll() {
        KeyListener kl = keyListener;
        if (kl != null) {
            long now = System.nanoTime();
            for (int n = 0; n < 128; n++) if (down[n] > 0) kl.onKey(n, 0, false, now);
        }
        java.util.Arrays.fill(playing, -1);
        java.util.Arrays.fill(down, 0);
        java.util.Arrays.fill(pedal, false);
        sustainOn = false;
        engine.allNotesOff();
        engine.pitchBend(8192);
        engine.modWheel(0);
    }

    // ------------------------------------------------------------------
    // MIDI in (MIDI threads)
    // ------------------------------------------------------------------
    private static boolean isControl(int src) { return (src & 0xF) == MidiInput.SRC_CONTROL; }

    private static String portLabel(int src) {
        int port = src >> 4;
        return port > 0 || isControl(src) ? " · port " + (port + 1) : "";
    }

    @Override
    public synchronized void onSysex(int src, byte[] data, int len) {
        midiActivity = System.nanoTime();
        String hex = hex(data, len);
        log("SysEx " + shortHex(hex) + " (" + len + " B)" + portLabel(src));
        if (learn >= 0 && learn < NB) {
            setBinding(learn, BIND_SYSEX, 0, 0, src, 127, hex);
            return;
        }
        for (int i = 0; i < NB; i++) {
            if (bind[i][0] == BIND_SYSEX && hex.equals(bindSysex[i])) {
                fire(i);
                return;
            }
        }
        if (isControl(src)) offerCandidate(BIND_SYSEX, 0, 0, src, 127, hex);
    }

    @Override
    public synchronized void onMidi(int src, int status, int d1, int d2) {
        midiActivity = System.nanoTime();
        final int type = status & 0xF0, ch = status & 0x0F;
        final boolean ctrl = isControl(src);
        log(describe(type, ch, d1, d2) + portLabel(src));

        if (type == 0xB0) trackKnob(ch, d1, src, d2);

        // teach mode: the next button-like message becomes the binding
        if (learn >= 0 && learn < NB) {
            // pads and control-port buttons can be taught; piano keys never are
            boolean noteOn = type == 0x90 && d2 > 0 && (ch == PAD_CHANNEL || ctrl);
            boolean ccPress = type == 0xB0 && d2 > 0 && d1 != 1 && d1 != 64 && d1 < 120;
            if (noteOn || ccPress) {
                setBinding(learn, noteOn ? BIND_NOTE : BIND_CC, ch, d1, src, d2, null);
                return;
            }
        }

        // taught controls (consume press and release)
        boolean consumed = false;
        for (int i = 0; i < NB; i++) {
            int[] b = bind[i];
            if (b[0] == BIND_NONE || b[0] == BIND_SYSEX || b[1] != ch || b[2] != d1 || b[3] != src) continue;
            if (b[0] == BIND_NOTE && (type == 0x90 || type == 0x80)) {
                boolean press = type == 0x90 && d2 > 0;
                if (press) { fire(i); return; }
                // a note-off for a note that is actually sounding still ends it
                if (playing[ch * 128 + d1] < 0) return;
                break;
            }
            if (b[0] == BIND_CC && type == 0xB0) {
                long now = System.nanoTime();
                if (d2 == 0) { ccLast[i] = 0; consumed = true; continue; } // release
                boolean boundHigh = b[4] >= 64, high = d2 >= 64;
                if (boundHigh != high) continue; // the other direction (e.g. −1 vs +1): maybe unmapped yet
                boolean press = boundHigh
                        ? ccLast[i] < 64 || now - ccLastTime[i] > 250_000_000L   // momentary or latching button
                        : now - ccLastTime[i] > 80_000_000L;                    // relative step (+1)
                ccLast[i] = d2;
                ccLastTime[i] = now;
                if (press) fire(i);
                return;
            }
        }
        if (consumed) return;

        // an unmapped button? offer to map it (control-port notes, button-like CCs)
        if (learn == LEARN_NONE) {
            if (ctrl && type == 0x90 && d2 > 0) offerCandidate(BIND_NOTE, ch, d1, src, d2, null);
            else if (type == 0xB0 && buttonLikeCc(ch, d1, src, d2) && (ctrl || isFreeCc(d1)))
                offerCandidate(BIND_CC, ch, d1, src, d2, null);
        }

        switch (type) {
            case 0x90:
                if (d2 > 0) { noteOn(ctrl, ch, d1, d2); break; }
                // velocity 0 = note off
            case 0x80:
                if (!ctrl) keyOff(ch, d1);
                break;
            case 0xB0:
                if (ctrl) break; // control ports: buttons only
                if (d1 == 64) {
                    boolean on = d2 >= 64;
                    if (on != sustainOn) {
                        sustainOn = on;
                        engine.sustain(on);
                        if (!on) java.util.Arrays.fill(pedal, false);
                        if (ui != null) ui.onKeysChanged();
                    }
                } else if (d1 == 1) {
                    engine.modWheel(d2);
                } else if (d1 == 0 || d1 == 32) {
                    // Bank Select: step through the sounds as the bank number moves
                    int last = d1 == 0 ? lastBankMsb : lastBankLsb;
                    if (last >= 0 && d2 != last && ui != null) ui.onPresetStep(d2 > last ? 1 : -1);
                    if (d1 == 0) lastBankMsb = d2; else lastBankLsb = d2;
                } else if (d1 == 120 || d1 == 123) {
                    releaseAll();
                    if (ui != null) ui.onKeysChanged();
                } else if (d1 == 121) { // reset all controllers
                    engine.pitchBend(8192);
                    engine.modWheel(0);
                    if (sustainOn) {
                        sustainOn = false;
                        engine.sustain(false);
                        java.util.Arrays.fill(pedal, false);
                        if (ui != null) ui.onKeysChanged();
                    }
                }
                break;
            case 0xE0:
                if (!ctrl) engine.pitchBend(d1 | (d2 << 7));
                break;
            case 0xC0:
                if (!ctrl && ui != null) ui.onPresetFromKeyboard(d1 % Synth.PRESET_COUNT);
                break;
            default:
                break;
        }
    }

    /** CCs the keyboard uses for playing, which must never be offered as buttons. */
    private static boolean isFreeCc(int cc) {
        return cc != 0 && cc != 1 && cc != 32 && cc != 64 && cc < 120;
    }

    private static String ccKey(int ch, int cc, int src) { return ch + ":" + cc + ":" + src; }

    /** A CC that takes in-between values is a knob or slider, not a button. */
    private void trackKnob(int ch, int cc, int src, int v) {
        if (v == 0 || v == 1 || v == 63 || v == 65 || v == 127) return;
        if (knobs.add(ccKey(ch, cc, src)) && candType == BIND_CC && candCh == ch && candNum == cc && candSrc == src) {
            candType = BIND_NONE; // it was a knob after all: withdraw the prompt
            if (ui != null) ui.onControlCandidate(null);
        }
    }

    private boolean buttonLikeCc(int ch, int cc, int src, int v) {
        return (v == 1 || v == 63 || v == 65 || v == 127) && !knobs.contains(ccKey(ch, cc, src));
    }

    private static String signature(int type, int ch, int num, int src, int value, String sysex) {
        switch (type) {
            case BIND_SYSEX: return "x:" + sysex;
            case BIND_NOTE: return "n:" + ch + ":" + num + ":" + src;
            default: return "c:" + ch + ":" + num + ":" + src + ":" + (value >= 64 ? "h" : "l");
        }
    }

    private void offerCandidate(int type, int ch, int num, int src, int value, String sysex) {
        if (!askButtons) return;
        String sig = signature(type, ch, num, src, value, sysex);
        if (ignored.contains(sig) || sig.equals(candSig) && candType != BIND_NONE) return;
        candType = type;
        candCh = ch;
        candNum = num;
        candSrc = src;
        candValue = value;
        candSysex = sysex;
        candSig = sig;
        String what;
        if (type == BIND_SYSEX) what = "SysEx " + shortHex(sysex);
        else if (type == BIND_NOTE) what = "Note " + num + " · ch " + (ch + 1);
        else what = "CC " + num + " = " + value + " · ch " + (ch + 1);
        if (ui != null) ui.onControlCandidate(what + portLabel(src));
    }

    private void setBinding(int which, int type, int ch, int num, int src, int value, String sysex) {
        bind[which][0] = type;
        bind[which][1] = ch;
        bind[which][2] = num;
        bind[which][3] = src;
        bind[which][4] = value;
        bindSysex[which] = sysex;
        ccLast[which] = value; // the teaching press itself is still held
        ccLastTime[which] = System.nanoTime();
        // one control can only do one thing (a relative CC's two directions count as two controls)
        for (int i = 0; i < NB; i++) {
            if (i == which || bind[i][0] != type) continue;
            boolean same = type == BIND_SYSEX ? sysex.equals(bindSysex[i])
                    : (bind[i][1] == ch && bind[i][2] == num && bind[i][3] == src
                    && (type != BIND_CC || (bind[i][4] >= 64) == (value >= 64)));
            if (same) bind[i][0] = BIND_NONE;
        }
        if (candType != BIND_NONE && candSig != null && candSig.equals(signature(type, ch, num, src, value, sysex))) {
            candType = BIND_NONE;
            if (ui != null) ui.onControlCandidate(null);
        }
        saveBindings();
        learn = LEARN_NONE;
        if (ui != null) ui.onLearned(which);
    }

    private void fire(int which) {
        switch (which) {
            case B_SOUND_DOWN: if (ui != null) ui.onPresetStep(-1); break;
            case B_SOUND_UP: if (ui != null) ui.onPresetStep(1); break;
        }
    }

    private void noteOn(boolean ctrl, int ch, int key, int rawVel) {
        if (ctrl) return; // control ports: transport and other buttons, never notes to play
        if (ch == PAD_CHANNEL) {
            if (padMode == PADS_SOUNDS) {
                if (ui != null) ui.onPresetFromKeyboard(Math.floorMod(key - 36, Synth.PRESET_COUNT));
                return;
            }
            if (padMode == PADS_IGNORE) return;
        }
        // a key outside the 25 shown means the MPK's OCT changed: follow it
        if (ch != PAD_CHANNEL) setKeysLo(KeyboardLayout.followKeys(keysLo, key));
        VelocityCurve vc = vel;
        int v = vc.map(rawVel);
        lastRawVel = rawVel;
        lastOutVel = v;
        keyOn(ch, key, v);
    }

    private void keyOn(int ch, int key, int vel) {
        int slot = ch * 128 + key;
        if (playing[slot] >= 0) keyOff(ch, key); // double note-on: end the old one
        int n = key; // exactly the key the keyboard sent
        if (n < 0 || n > 127) return;
        playing[slot] = n;
        engine.noteOn(n, vel);
        down[n]++;
        pedal[n] = false;
        lastNote = n;
        KeyListener kl = keyListener;
        if (kl != null) kl.onKey(n, vel, true, System.nanoTime());
        if (ui != null) ui.onKeysChanged();
    }

    private void keyOff(int ch, int key) {
        int slot = ch * 128 + key;
        int n = playing[slot];
        if (n < 0) return;
        playing[slot] = -1;
        if (down[n] > 0) down[n]--;
        if (down[n] == 0) {
            engine.noteOff(n);
            if (sustainOn) pedal[n] = true;
            KeyListener kl = keyListener;
            if (kl != null) kl.onKey(n, 0, false, System.nanoTime());
        }
        if (ui != null) ui.onKeysChanged();
    }

    // ------------------------------------------------------------------
    // Monitor helpers
    // ------------------------------------------------------------------
    private void log(String line) {
        System.arraycopy(monitor, 0, monitor, 1, monitor.length - 1);
        monitor[0] = line;
        if (ui != null) ui.onMonitor();
    }

    private static String describe(int type, int ch, int d1, int d2) {
        String c = " · ch " + (ch + 1);
        switch (type) {
            case 0x90: return (d2 > 0 ? "Note on " + noteName(d1) + " (" + d1 + ") vel " + d2 : "Note off " + noteName(d1)) + c;
            case 0x80: return "Note off " + noteName(d1) + c;
            case 0xB0: return "CC " + d1 + " = " + d2 + c;
            case 0xC0: return "Program " + d1 + c;
            case 0xE0: return "Pitch bend " + ((d1 | (d2 << 7)) - 8192) + c;
            case 0xD0: return "Pressure " + d1 + c;
            case 0xA0: return "Poly pressure " + noteName(d1) + " " + d2 + c;
            default: return "Status " + Integer.toHexString(type | ch);
        }
    }

    private static String hex(byte[] b, int len) {
        StringBuilder sb = new StringBuilder(len * 3);
        for (int i = 0; i < len; i++) {
            if (i > 0) sb.append(' ');
            sb.append(Character.forDigit((b[i] >> 4) & 0xF, 16)).append(Character.forDigit(b[i] & 0xF, 16));
        }
        return sb.toString().toUpperCase(java.util.Locale.ROOT);
    }

    private static String shortHex(String hex) {
        if (hex == null) return "";
        return hex.length() <= 26 ? hex : hex.substring(0, 20) + " … " + hex.substring(hex.length() - 5);
    }

    /** Middle C (note 60) named C3 (Akai/Ableton style) instead of C4 (scientific). */
    private static volatile boolean middleC3;

    static String noteName(int n) {
        // sharps, as Ableton Live and the MPK name them
        final String[] names = {"C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B"};
        return names[Math.floorMod(n, 12)] + octaveNumber(n);
    }

    /** Octave number in the chosen naming, e.g. 60 -> 4 (or 3). */
    static int octaveNumber(int n) {
        return Math.floorDiv(n, 12) - (middleC3 ? 2 : 1);
    }
}

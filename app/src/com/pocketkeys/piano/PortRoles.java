package com.pocketkeys.piano;

/**
 * Decides which of a keyboard's USB MIDI ports carry notes to play. Pure Java
 * (no Android) so it can be unit tested.
 *
 * Per Akai's setup guides the MPK mini IV sends keys, pads, wheels, sustain and
 * knobs on Port 1 "MPK Mini IV MIDI Port"; Port 2 "DAW", 3 "Plugin" and
 * 4 "Software Control" carry its buttons, screen and transport, and Port 5 is the
 * DIN jack. Like Ableton (which only takes notes from Port 1), only the MIDI port
 * plays notes; every other port is a control port.
 */
final class PortRoles {
    static final int KEYS = 0, CONTROL = 1;

    private PortRoles() { }

    /** @param names output port names in port-number order (null/empty when unknown) */
    static int roleFor(String[] names, int port) {
        if (names.length <= 1) return KEYS;
        boolean controlNamed = false;
        int midiPort = -1;
        for (int i = 0; i < names.length; i++) {
            String n = lower(names[i]);
            if (isControlName(n)) controlNamed = true;
            else if (n.contains("midi port") && midiPort < 0) midiPort = i;
        }
        if (midiPort >= 0) return port == midiPort ? KEYS : CONTROL;   // e.g. "MPK Mini IV MIDI Port"
        int keys = 0;                                                    // otherwise the first port …
        if (controlNamed) {                                              // … not named as a control port
            keys = -1;
            for (int i = 0; i < names.length && keys < 0; i++) if (!isControlName(lower(names[i]))) keys = i;
            if (keys < 0) keys = 0; // every name matched (e.g. the device is called "… Control")
        }
        return port == keys ? KEYS : CONTROL; // exactly one port plays notes
    }

    static boolean isControlName(String n) {
        return n.contains("daw") || n.contains("software") || n.contains("control") || n.contains("plugin")
                || n.contains("remote") || n.contains("script") || n.contains("din");
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(java.util.Locale.ROOT);
    }
}

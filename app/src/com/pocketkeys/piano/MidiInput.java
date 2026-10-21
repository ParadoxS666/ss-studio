package com.pocketkeys.piano;

import android.content.Context;
import android.media.midi.MidiDevice;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiManager;
import android.media.midi.MidiOutputPort;
import android.media.midi.MidiReceiver;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Finds MIDI keyboards (USB and others), keeps them connected and parses their messages. */
final class MidiInput {
    /**
     * Message source = role | (port number << 4). Roles: the keyboard's playing
     * port(s), or a control port (the MPK mini IV's DAW and Software Control ports,
     * which carry its buttons, screen and transport controls).
     */
    static final int SRC_KEYS = 0, SRC_CONTROL = 1;

    interface Listener {
        /** Called on a MIDI thread for every complete channel message. */
        void onMidi(int src, int status, int d1, int d2);

        /** Called on a MIDI thread for every complete SysEx message (F0 … F7, inclusive). */
        void onSysex(int src, byte[] data, int len);

        /**
         * Called on the main thread when keyboards connect or disconnect.
         * {@code hotplug} is true when the keyboard was physically plugged in or
         * out while the app was running (not just found when the app opened).
         */
        void onDevicesChanged(boolean removed, boolean hotplug);
    }

    private final MidiManager mm;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread thread;
    private Handler handler;
    private final Map<Integer, Opened> open = new HashMap<>();
    private final List<String> names = new ArrayList<>();
    private volatile boolean started;
    /** Device ids open when the app last went to the background (null before the first start). */
    private static java.util.Set<Integer> knownIds;

    private static final class Opened {
        MidiDevice device;
        final List<MidiOutputPort> ports = new ArrayList<>();
        String name;
    }

    private final MidiManager.DeviceCallback callback = new DeviceWatcher(this);

    // static nested classes (not inner) keep the class files simple for the dexer
    private static final class DeviceWatcher extends MidiManager.DeviceCallback {
        private final MidiInput in;

        DeviceWatcher(MidiInput in) { this.in = in; }

        @Override
        public void onDeviceAdded(MidiDeviceInfo info) { in.connect(info, true); }

        @Override
        public void onDeviceRemoved(MidiDeviceInfo info) { in.disconnect(info.getId()); }
    }

    MidiInput(Context ctx, Listener l) {
        mm = (MidiManager) ctx.getSystemService(Context.MIDI_SERVICE);
        listener = l;
    }

    boolean available() { return mm != null; }

    @SuppressWarnings("deprecation")
    void start() {
        if (mm == null || started) return;
        started = true;
        thread = new HandlerThread("PocketKeysMidi");
        thread.start();
        handler = new Handler(thread.getLooper());
        // a keyboard that wasn't there when we went to the background was (re)plugged meanwhile;
        // Android gives a device a new id every time it is plugged in
        final java.util.Set<Integer> known = knownIds;
        if (Build.VERSION.SDK_INT >= 33) {
            mm.registerDeviceCallback(MidiManager.TRANSPORT_MIDI_BYTE_STREAM, handler::post, callback);
            Set<MidiDeviceInfo> set = mm.getDevicesForTransport(MidiManager.TRANSPORT_MIDI_BYTE_STREAM);
            for (MidiDeviceInfo info : set) connect(info, known != null && !known.contains(info.getId()));
        } else {
            mm.registerDeviceCallback(callback, handler);
            for (MidiDeviceInfo info : mm.getDevices()) connect(info, known != null && !known.contains(info.getId()));
        }
    }

    void stop() {
        if (mm == null || !started) return;
        started = false;
        mm.unregisterDeviceCallback(callback);
        final List<Opened> toClose;
        synchronized (open) {
            knownIds = new java.util.HashSet<>(open.keySet());
            toClose = new ArrayList<>(open.values());
            open.clear();
            names.clear();
        }
        final HandlerThread t = thread;
        handler.post(() -> {
            for (Opened o : toClose) close(o);
            t.quitSafely();
        });
        main.post(() -> listener.onDevicesChanged(true, false));
    }

    /** Names of the connected keyboards. */
    List<String> deviceNames() {
        synchronized (open) {
            return new ArrayList<>(names);
        }
    }

    private void connect(MidiDeviceInfo info, boolean hotplug) {
        if (!started || info.getOutputPortCount() == 0) return; // stopped, or nothing to listen to
        final int id = info.getId();
        final Opened entry = new Opened();
        synchronized (open) {
            if (open.containsKey(id)) return;
            open.put(id, entry);
        }
        final String name = describe(info);
        mm.openDevice(info, device -> {
            if (device == null) {
                synchronized (open) { if (open.get(id) == entry) open.remove(id); }
                return;
            }
            synchronized (open) {
                Opened o = entry;
                // stale callback (stopped, or the keyboard was re-plugged meanwhile)
                if (open.get(id) != entry || !started) {
                    if (open.get(id) == entry) open.remove(id);
                    try { device.close(); } catch (IOException ignored) { }
                    return;
                }
                o.device = device;
                o.name = name;
                MidiDeviceInfo di = device.getInfo();
                int count = di.getOutputPortCount();
                for (int i = 0; i < count; i++) {
                    MidiOutputPort port = device.openOutputPort(i);
                    if (port != null) {
                        port.connect(new Parser(listener, roleOf(di, i, count) | (i << 4)));
                        o.ports.add(port);
                    }
                }
                rebuildNames();
            }
            main.post(() -> listener.onDevicesChanged(false, hotplug));
        }, handler);
    }

    private void disconnect(int id) {
        Opened o;
        synchronized (open) {
            o = open.remove(id);
            if (o != null) close(o);
            rebuildNames();
        }
        if (o != null) main.post(() -> listener.onDevicesChanged(true, true));
    }

    private void rebuildNames() {
        names.clear();
        for (Opened o : open.values()) if (o.device != null && o.name != null) names.add(o.name);
    }

    private static void close(Opened o) {
        for (MidiOutputPort p : o.ports) {
            try { p.close(); } catch (IOException ignored) { }
        }
        o.ports.clear();
        if (o.device != null) {
            try { o.device.close(); } catch (IOException ignored) { }
        }
    }

    /** See {@link PortRoles}: only the keyboard's MIDI port plays notes. */
    private static int roleOf(MidiDeviceInfo info, int portNumber, int outputCount) {
        String[] names = new String[outputCount];
        for (MidiDeviceInfo.PortInfo p : info.getPorts()) {
            int i = p.getPortNumber();
            if (p.getType() == MidiDeviceInfo.PortInfo.TYPE_OUTPUT && i >= 0 && i < outputCount) names[i] = p.getName();
        }
        return PortRoles.roleFor(names, portNumber) == PortRoles.KEYS ? SRC_KEYS : SRC_CONTROL;
    }

    private static String describe(MidiDeviceInfo info) {
        Bundle p = info.getProperties();
        String product = p.getString(MidiDeviceInfo.PROPERTY_PRODUCT);
        String name = p.getString(MidiDeviceInfo.PROPERTY_NAME);
        String s = product != null && !product.isEmpty() ? product : (name != null ? name : "MIDI keyboard");
        return s.trim();
    }

    /** Byte-stream parser: running status, SysEx collection, ignores real-time bytes. */
    private static final class Parser extends MidiReceiver {
        private static final int SYSEX_MAX = 256;
        private final Listener listener;
        private final int src;
        private int status;
        private int d0;
        private int count;
        private boolean sysex;
        private final byte[] sx = new byte[SYSEX_MAX];
        private int sxLen;

        Parser(Listener l, int src) {
            listener = l;
            this.src = src;
        }

        @Override
        public void onSend(byte[] msg, int offset, int len, long timestamp) {
            for (int i = offset, end = offset + len; i < end; i++) {
                int b = msg[i] & 0xFF;
                if (b >= 0xF8) continue;                 // clock, start/stop, active sensing
                if (b == 0xF0) {
                    sysex = true;
                    status = 0;
                    sxLen = 0;
                    sx[sxLen++] = (byte) b;
                    continue;
                }
                if (b == 0xF7) {
                    if (sysex) {
                        if (sxLen < SYSEX_MAX) sx[sxLen++] = (byte) b;
                        byte[] copy = java.util.Arrays.copyOf(sx, sxLen);
                        listener.onSysex(src, copy, copy.length);
                    }
                    sysex = false;
                    continue;
                }
                if (b >= 0x80) {
                    sysex = false;
                    status = b >= 0xF0 ? 0 : b;          // system common cancels running status
                    count = 0;
                    continue;
                }
                if (sysex) {
                    if (sxLen < SYSEX_MAX) sx[sxLen++] = (byte) b;
                    continue;
                }
                if (status == 0) continue;
                int type = status & 0xF0;
                if (type == 0xC0 || type == 0xD0) {
                    listener.onMidi(src, status, b, 0);
                } else if (count == 0) {
                    d0 = b;
                    count = 1;
                } else {
                    count = 0;
                    listener.onMidi(src, status, d0, b);
                }
            }
        }
    }
}

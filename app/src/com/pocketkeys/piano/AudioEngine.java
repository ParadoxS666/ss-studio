package com.pocketkeys.piano;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRouting;
import android.media.AudioTimestamp;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

/**
 * Owns the AudioTrack and the real-time render thread.
 *
 * Latency choices:
 *  - PERFORMANCE_MODE_LOW_LATENCY + native sample rate, so Android can use its
 *    fast mixer path on the speaker / wired / USB outputs.
 *  - Buffer starts at two bursts and only grows if the track underruns.
 *  - USAGE_GAME: on Bluetooth LE Audio this selects the low-latency "game"
 *    audio context instead of the higher-latency media one.
 *  - Events from the MIDI thread go through a lock-free queue and are applied
 *    at the start of every burst; the render loop never allocates or locks.
 */
final class AudioEngine {
    interface Listener {
        void onRouteChanged();
    }

    // event types
    private static final int EV_ON = 1, EV_OFF = 2, EV_SUSTAIN = 3, EV_BEND = 4, EV_MOD = 5,
            EV_PRESET = 6, EV_ALL_OFF = 7, EV_DYN = 8, EV_RESET = 9, EV_AUTO_ON = 10, EV_AUTO_OFF = 11,
            EV_AUTO_ALL_OFF = 12;

    private final Context ctx;
    private final AudioManager am;
    private final int sampleRate;
    private final int burst;
    final Synth synth;

    // single-consumer ring buffer of packed events (producers synchronize)
    private final int[] ring = new int[2048];
    private volatile int head, tail;

    private volatile boolean running;
    private volatile boolean rebuildRequested;
    private volatile float reverb = 0.3f;
    private volatile Thread thread;
    private volatile AudioTrack track;
    private volatile long framesWritten;
    private volatile int bufferFrames;
    private volatile int routedId = -1;
    private final AudioTimestamp ts = new AudioTimestamp();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Listener listener;
    private final Runnable rebuild = () -> rebuildRequested = true;

    AudioEngine(Context context) {
        ctx = context.getApplicationContext();
        am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        sampleRate = parse(am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE), 48000);
        burst = parse(am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER), 192);
        synth = new Synth(sampleRate);
    }

    private static int parse(String s, int def) {
        try {
            int v = Integer.parseInt(s);
            return v > 0 ? v : def;
        } catch (Exception e) {
            return def;
        }
    }

    void setListener(Listener l) { listener = l; }

    // ------------------------------------------------------------------
    // Event API (any thread)
    // ------------------------------------------------------------------
    void noteOn(int note, int vel) { push(EV_ON, note, vel); }
    void noteOff(int note) { push(EV_OFF, note, 0); }
    /** Notes the Learn mode plays for you (kept apart from your own keys). */
    void autoNoteOn(int note, int vel) { push(EV_AUTO_ON, note, vel); }
    void autoNoteOff(int note) { push(EV_AUTO_OFF, note, 0); }
    void autoAllOff() { push(EV_AUTO_ALL_OFF, 0, 0); }
    void sustain(boolean on) { push(EV_SUSTAIN, on ? 1 : 0, 0); }
    void pitchBend(int v14) { push(EV_BEND, v14 & 0x7F, (v14 >> 7) & 0x7F); }
    void modWheel(int v) { push(EV_MOD, v, 0); }
    void setPreset(int p) { push(EV_PRESET, p, 0); }
    void allNotesOff() { push(EV_ALL_OFF, 0, 0); }
    /** Loudness range from softest to hardest note, 6..48 dB. */
    void setDynamicRange(int db) { push(EV_DYN, db, 0); }
    void setReverb(float r) { reverb = r; }

    private synchronized void push(int type, int a, int b) {
        int h = head;
        int next = (h + 1) & (ring.length - 1);
        if (next == tail) return; // full: drop (cannot happen in practice)
        ring[h] = (type << 16) | ((a & 0xFF) << 8) | (b & 0xFF);
        head = next;
    }

    private void drain() {
        int t = tail;
        final int h = head;
        final Synth s = synth;
        while (t != h) {
            int e = ring[t];
            t = (t + 1) & (ring.length - 1);
            int type = e >>> 16, a = (e >> 8) & 0xFF, b = e & 0xFF;
            switch (type) {
                case EV_ON: s.noteOn(a, b); break;
                case EV_OFF: s.noteOff(a); break;
                case EV_AUTO_ON: s.noteOn(a, b, true); break;
                case EV_AUTO_OFF: s.noteOff(a, true); break;
                case EV_AUTO_ALL_OFF: s.autoAllOff(); break;
                case EV_SUSTAIN: s.setSustain(a != 0); break;
                case EV_BEND: s.setBend(a | (b << 7)); break;
                case EV_MOD: s.setMod(a); break;
                case EV_PRESET: s.setPreset(a); break;
                case EV_ALL_OFF: s.allNotesOff(); break;
                case EV_DYN: s.setDynamicRange(a); break;
                case EV_RESET: s.reset(); break;
            }
        }
        tail = t;
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------
    synchronized void start() {
        if (running) return;
        running = true;
        rebuildRequested = false;
        Thread t = new Thread(this::loop, "PocketKeysAudio");
        thread = t;
        t.start();
    }

    synchronized void stop() {
        running = false;
        main.removeCallbacks(rebuild);
        rebuildRequested = false;
        Thread t = thread;
        thread = null;
        AudioTrack tr = track;
        if (tr != null) {
            try { tr.pause(); } catch (RuntimeException ignored) { } // unblocks a pending write
        }
        if (t != null) {
            try { t.join(1000); } catch (InterruptedException ignored) { }
        }
        push(EV_RESET, 0, 0); // start fresh next time (no old voices or reverb tail)
    }

    private AudioTrack buildTrack() {
        routedId = -1;
        AudioFormat fmt = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build();
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        int minBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_FLOAT);
        int bytes = Math.max(minBytes, burst * 8 * 4);
        AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(bytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build();
        if (t.getPerformanceMode() == AudioTrack.PERFORMANCE_MODE_LOW_LATENCY) {
            // fast mixer path (speaker, wired, USB): start at two bursts
            bufferFrames = t.setBufferSizeInFrames(burst * 2);
        } else {
            // normal mixer path (typical for Bluetooth): ~20 ms avoids start-up crackle;
            // the Bluetooth link itself adds far more delay than this
            int frames = Math.max(burst * 2, sampleRate / 50);
            bufferFrames = t.setBufferSizeInFrames(Math.min(frames, t.getBufferCapacityInFrames()));
        }
        t.addOnRoutingChangedListener((AudioRouting.OnRoutingChangedListener) router -> {
            AudioDeviceInfo d = router.getRoutedDevice();
            int id = d == null ? -1 : d.getId();
            if (id != -1 && routedId != -1 && id != routedId && running) {
                // re-create the track shortly after the output device changes so
                // the new route gets a freshly tuned (possibly fast-path) buffer
                main.removeCallbacks(rebuild);
                main.postDelayed(rebuild, 400);
            }
            if (id != -1) routedId = id;
            if (listener != null) listener.onRouteChanged();
        }, main);
        return t;
    }

    private static void releaseQuietly(AudioTrack t) {
        if (t == null) return;
        try { t.pause(); t.flush(); } catch (RuntimeException ignored) { }
        try { t.release(); } catch (RuntimeException ignored) { }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    private void loop() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        } catch (RuntimeException e) {
            try { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO); } catch (RuntimeException ignored) { }
        }
        final Thread self = Thread.currentThread();
        final float[] buf = new float[burst * 2];
        AudioTrack t = null;
        int lastUnderruns = 0;
        int checkCounter = 0;
        try {
            while (running && thread == self) {
                if (t == null || rebuildRequested) {
                    rebuildRequested = false;
                    releaseQuietly(t);
                    t = null;
                    try {
                        t = buildTrack();
                        track = t;
                        framesWritten = 0;
                        lastUnderruns = 0;
                        t.play();
                    } catch (RuntimeException e) {
                        // audio server restarting or route in flux: try again shortly
                        if (track == t) track = null;
                        releaseQuietly(t);
                        t = null;
                        sleep(200);
                        continue;
                    }
                    main.post(() -> { if (listener != null) listener.onRouteChanged(); });
                }
                drain();
                synth.setReverb(reverb);
                synth.render(buf, burst);
                int w = t.write(buf, 0, buf.length, AudioTrack.WRITE_BLOCKING);
                if (w < 0) { // dead track (e.g. server died): rebuild after a short pause
                    if (!running) break;
                    rebuildRequested = true;
                    sleep(50);
                    continue;
                }
                framesWritten += w / 2;
                // latency tuner: grow the buffer by one burst after an underrun
                if (++checkCounter >= 4) {
                    checkCounter = 0;
                    int u = t.getUnderrunCount();
                    if (u > lastUnderruns) {
                        lastUnderruns = u;
                        int cap = t.getBufferCapacityInFrames();
                        if (bufferFrames + burst <= cap) bufferFrames = t.setBufferSizeInFrames(bufferFrames + burst);
                    }
                }
            }
        } finally {
            if (track == t) track = null;
            releaseQuietly(t);
        }
    }

    // ------------------------------------------------------------------
    // Status for the UI
    // ------------------------------------------------------------------
    /**
     * Estimated time from a key press to sound leaving the output, in ms, or
     * -1 if unknown. Uses the track's presentation timestamp, which on Android
     * includes the Bluetooth codec/transport delay reported by the headset.
     */
    float latencyMs() {
        AudioTrack t = track;
        if (t == null) return -1;
        try {
            if (!t.getTimestamp(ts)) return -1;
        } catch (Exception e) {
            return -1;
        }
        long written = framesWritten;
        long pending = written - ts.framePosition;
        if (ts.framePosition <= 0 || pending < 0) return -1;
        double presentAt = ts.nanoTime + pending * 1e9 / sampleRate;
        double outMs = (presentAt - System.nanoTime()) / 1e6;
        // plus waiting for the next burst to start (half a burst on average)
        outMs += burst * 500.0 / sampleRate;
        return outMs > 0 ? (float) outMs : -1;
    }

    /** Human-readable output route, e.g. "Bluetooth LE · WH-1000XM6". */
    String routeLabel() {
        AudioDeviceInfo d = routedDevice();
        if (d == null) return "Starting audio…";
        String name = d.getProductName() != null ? d.getProductName().toString() : "";
        String kind;
        switch (d.getType()) {
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: kind = "Phone speaker"; name = ""; break;
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: kind = "Wired"; break;
            case AudioDeviceInfo.TYPE_USB_HEADSET:
            case AudioDeviceInfo.TYPE_USB_DEVICE: kind = "USB audio"; break;
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: kind = "Bluetooth"; break;
            case 26: /* TYPE_BLE_HEADSET */
            case 27: /* TYPE_BLE_SPEAKER */ kind = "Bluetooth LE"; break;
            default: kind = "Output"; break;
        }
        return name.isEmpty() ? kind : kind + " · " + name;
    }

    /** True when the current output is classic (non-LE) Bluetooth. */
    boolean isClassicBluetooth() {
        AudioDeviceInfo d = routedDevice();
        return d != null && d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP;
    }

    private AudioDeviceInfo routedDevice() {
        AudioTrack t = track;
        if (t == null) return null;
        try {
            return t.getRoutedDevice();
        } catch (RuntimeException e) { // track released by the audio thread meanwhile
            return null;
        }
    }

    int sampleRate() { return sampleRate; }
    int burst() { return burst; }
}

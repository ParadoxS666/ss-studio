package com.pocketkeys.piano;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.media.AudioManager;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

public final class MainActivity extends Activity implements LearnView.Host {
    private static final int REQ_MIDI_FILE = 7;
    private AudioEngine engine;
    private MidiRouter router;
    private MidiInput midi;
    private StageView stage;
    private LearnView learn;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        w.setStatusBarColor(0xFF15161A);
        w.setNavigationBarColor(0xFF07080A);

        SharedPreferences prefs = getSharedPreferences("pocketkeys", MODE_PRIVATE);
        engine = new AudioEngine(this);
        router = new MidiRouter(engine, prefs);
        stage = new StageView(this, engine, router, prefs);
        learn = new LearnView(this, engine, router, prefs, this);
        midi = new MidiInput(this, new MidiBridge(router, stage, learn));
        stage.setMidi(midi);
        stage.setOnLearn(this::openLearn);
        engine.setListener(stage::onRouteChanged);
        engine.setPreset(stage.preset());
        engine.setReverb(stage.reverb());
        engine.setDynamicRange(router.dynamicRange());
        FrameLayout root = new FrameLayout(this);
        root.addView(stage, new FrameLayout.LayoutParams(-1, -1));
        root.addView(learn, new FrameLayout.LayoutParams(-1, -1));
        learn.setVisibility(View.GONE);
        setContentView(root);
        if (saved != null && saved.getBoolean("learnOpen")) openLearn();
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        loadPianoSamples();
    }

    // ---- Learn mode ----
    private void openLearn() {
        learn.setVisibility(View.VISIBLE);
        stage.setVisibility(View.GONE);
        learn.setKeyboardConnected(!midi.deviceNames().isEmpty());
        learn.onShown();
    }

    @Override
    public void closeLearn() {
        learn.onHidden();
        learn.setVisibility(View.GONE);
        stage.setVisibility(View.VISIBLE);
    }

    private boolean learnOpen() { return learn.getVisibility() == View.VISIBLE; }

    @Override
    public void openMidiFile() {
        android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(android.content.Intent.EXTRA_MIME_TYPES,
                new String[]{"audio/midi", "audio/x-midi", "audio/mid", "application/x-midi", "audio/sp-midi"});
        try {
            startActivityForResult(i, REQ_MIDI_FILE);
        } catch (RuntimeException e) {
            learn.showError("No file picker is available on this phone.");
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_MIDI_FILE || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        android.net.Uri uri = data.getData();
        String name = "song.mid";
        try (android.database.Cursor c = getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) name = c.getString(0);
        } catch (RuntimeException ignored) { }
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Couldn't read that file.");
            learn.addImportedFile(name, LearnView.readAll(in));
        } catch (IOException | RuntimeException e) {
            learn.showError(e.getMessage() != null ? e.getMessage() : "Couldn't read that file.");
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean("learnOpen", learnOpen());
    }

    /** Forwards keyboard messages to the router and connection changes to router + UI. */
    private static final class MidiBridge implements MidiInput.Listener {
        private final MidiRouter router;
        private final StageView stage;
        private final LearnView learn;

        MidiBridge(MidiRouter r, StageView s, LearnView l) { router = r; stage = s; learn = l; }

        @Override
        public void onMidi(int src, int status, int d1, int d2) {
            router.onMidi(src, status, d1, d2);
        }

        @Override
        public void onSysex(int src, byte[] data, int len) {
            router.onSysex(src, data, len);
        }

        @Override
        public void onDevicesChanged(boolean removed, boolean hotplug) {
            router.onDevicesChanged(removed, hotplug);
            stage.onDevicesChanged();
            learn.setKeyboardConnected(stage.keyboardConnected());
        }
    }

    private void loadPianoSamples() {
        Thread t = new Thread(() -> {
            try (InputStream in = getAssets().open("piano.pk", android.content.res.AssetManager.ACCESS_STREAMING)) {
                byte[] data = readAll(in);
                engine.synth.bank = new SampleBank(data);
                runOnUiThread(stage::setBankLoaded);
            } catch (IOException | RuntimeException e) {
                android.util.Log.e("PocketKeys", "Could not load piano samples", e);
            }
        }, "PocketKeysLoader");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.start();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        int size = in.available();
        if (size > 0) {
            byte[] buf = new byte[size];
            int off = 0;
            while (off < size) {
                int n = in.read(buf, off, size - off);
                if (n < 0) break;
                off += n;
            }
            int extra = off == size ? in.read() : -1;
            if (off == size && extra < 0) return buf;
            // size was only an estimate: continue with what we have
            ByteArrayOutputStream bo = new ByteArrayOutputStream(size * 2);
            bo.write(buf, 0, off);
            if (extra >= 0) bo.write(extra);
            copy(in, bo);
            return bo.toByteArray();
        }
        ByteArrayOutputStream bo = new ByteArrayOutputStream(1 << 20);
        copy(in, bo);
        return bo.toByteArray();
    }

    private static void copy(InputStream in, ByteArrayOutputStream bo) throws IOException {
        byte[] b = new byte[1 << 16];
        int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
    }

    /** Headphones unplugged / Bluetooth dropped: stop held notes before audio jumps to the speaker. */
    private static final class NoisyReceiver extends BroadcastReceiver {
        private final MidiRouter router;

        NoisyReceiver(MidiRouter r) { router = r; }

        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) router.releaseAll();
        }
    }

    private NoisyReceiver noisy;

    @Override
    protected void onStart() {
        super.onStart();
        engine.start();
        midi.start();
        noisy = new NoisyReceiver(router);
        IntentFilter f = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisy, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(noisy, f);
    }

    @Override
    protected void onStop() {
        learn.pause();
        if (noisy != null) {
            try { unregisterReceiver(noisy); } catch (RuntimeException ignored) { }
            noisy = null;
        }
        midi.stop();
        router.releaseAll();
        engine.stop();
        super.onStop();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (learnOpen()) {
            if (!learn.handleBack()) closeLearn();
            return;
        }
        if (!stage.handleBack()) super.onBackPressed();
    }
}

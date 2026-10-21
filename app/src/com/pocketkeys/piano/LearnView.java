package com.pocketkeys.piano;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Learn mode: falling notes ("Key Falls") over the keyboard, a song library and
 * the practice controls. The game logic is in LearnSession.
 */
final class LearnView extends View implements MidiRouter.KeyListener {
    interface Host {
        void closeLearn();
        void openMidiFile();
    }

    // ---- colors (match the main screen) ----
    private static final int BG = 0xFF0F1014, PANEL = 0xFF1B1D22, PANEL_HI = 0xFF23262D, STROKE = 0xFF2A2E36;
    private static final int TEXT = 0xFFF1F1F2, TEXT2 = 0xFFA0A5AD, TEXT3 = 0xFF666B73;
    private static final int ACCENT = 0xFFFF9F43, ON_ACCENT = 0xFF1A1205;
    private static final int RIGHT = 0xFF4CC38A, LEFT = 0xFF4C9EE8, OTHER = 0xFF8A8FA0;
    private static final int PERFECT = 0xFFF5D742, GREAT = 0xFF4CC38A, GOOD = 0xFF4C9EE8, MISS = 0xFFE5534B;
    private static final String[] PC = {"C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B"};

    // ---- touch targets ----
    private static final int T_BACK = 1, T_TITLE = 2, T_SONGS = 3, T_MODE = 10, T_HANDS = 20,
            T_SPD_DOWN = 30, T_SPD_UP = 31, T_SPD_VALUE = 32, T_ANYOCT = 40, T_NAMES = 41, T_BACKING = 42,
            T_PLAY = 50, T_RESTART = 51, T_LOOPA = 52, T_LOOPB = 53, T_TIMELINE = 54,
            T_CARD_START = 60, T_CARD_SONGS = 61, T_CARD_AGAIN = 62, T_CARD_OK = 63, T_CARD_NEXT = 64,
            T_LIB_CLOSE = 70, T_LIB_OPEN = 71, T_LIB_BODY = 72, T_SHIELD = 80, T_STAGE = 90,
            T_LIB_ROW = 1000, T_LIB_DEL = 5000;

    private static final int OV_NONE = 0, OV_INTRO = 1, OV_RESULTS = 2, OV_LIBRARY = 3, OV_ERROR = 4;

    private final AudioEngine engine;
    private final MidiRouter router;
    private final SharedPreferences prefs;
    private final Host host;
    private final File songDir;
    private float d, sp;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint body = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
    private final Typeface bold = Typeface.create("sans-serif", Typeface.BOLD);
    private final RectF r = new RectF();
    private final Path path = new Path();

    // ---- songs and settings ----
    private List<Song> builtIn = new ArrayList<>();
    private final List<Song> imported = new ArrayList<>();
    private final java.util.Map<String, Song> parsed = new java.util.HashMap<>();
    private Song song;
    private LearnSession session;
    private int mode, hands;
    private float speed;
    private boolean anyOctave, names, backing;
    private boolean visible;
    private boolean keyboardConnected;

    // ---- overlays ----
    private int overlay = OV_INTRO;
    private String errorText;
    private boolean lastNewBest;
    private int lastPrevBest;
    private float libScroll, libContentH, libViewH;

    // ---- keys from the MIDI thread: {note, velocity, down, nanoTime} ----
    private final java.util.ArrayDeque<long[]> events = new java.util.ArrayDeque<>();
    private final boolean[] held = new boolean[128];
    private final int[] litKey = new int[KeyboardLayout.MPK_KEYS];   // Listen: keys sounding, by hand colour
    private long lastFrameNs;
    private long lastLatencyCheck;

    // ---- layout ----
    private final RectF rTop = new RectF(), rStage = new RectF(), rBottom = new RectF();
    private boolean land;

    // ---- touch targets registered while drawing ----
    private static final int MAX_T = 600;
    private final int[] tId = new int[MAX_T];
    private final RectF[] tRect = new RectF[MAX_T];
    private int nT;
    private int downId = -1, pressedId = -1;
    private float downX, downY, downScroll;
    private boolean dragging;

    LearnView(Context ctx, AudioEngine engine, MidiRouter router, SharedPreferences prefs, Host host) {
        super(ctx);
        this.engine = engine;
        this.router = router;
        this.prefs = prefs;
        this.host = host;
        songDir = new File(ctx.getFilesDir(), "learn_songs");
        for (int i = 0; i < MAX_T; i++) tRect[i] = new RectF();
        d = getResources().getDisplayMetrics().density;
        sp = d * Math.min(1.25f, getResources().getConfiguration().fontScale);
        stroke.setStyle(Paint.Style.STROKE);
        mode = prefs.getInt("learnMode", LearnSession.WAIT);
        hands = prefs.getInt("learnHands", LearnSession.HANDS_RIGHT);
        speed = prefs.getFloat("learnSpeed", 1f);
        anyOctave = prefs.getBoolean("learnAnyOct", true);
        names = prefs.getBoolean("learnNames", true);
        // off by default: Learn mode makes no sound of its own unless you ask for the other hand
        backing = prefs.getBoolean("learnOtherHand", false);
        setHapticFeedbackEnabled(true);
    }

    @Override
    protected void onConfigurationChanged(android.content.res.Configuration cfg) {
        super.onConfigurationChanged(cfg);
        d = getResources().getDisplayMetrics().density;
        sp = d * Math.min(1.25f, cfg.fontScale);
        invalidate();
    }

    // ------------------------------------------------------------------
    // Songs
    // ------------------------------------------------------------------
    /** Loads the built-in library (once) and your saved MIDI files. */
    void loadSongs() {
        if (builtIn.isEmpty()) {
            try (java.io.InputStream in = getContext().getAssets().open("songs.pks")) {
                builtIn = SongLibrary.readBuiltIn(readAll(in));
            } catch (Exception e) {
                android.util.Log.e("SSStudio", "song library", e);
            }
        }
        imported.clear();
        File[] files = songDir.listFiles();
        java.util.Map<String, Song> keep = new java.util.HashMap<>();
        if (files != null) {
            java.util.Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File f : files) {
                String key = f.getName() + "|" + f.length() + "|" + f.lastModified();
                Song s = parsed.get(key);   // parse each saved file once, not on every visit
                if (s == null) {
                    try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                        s = SongLibrary.parseMidi(readAll(in), f.getName());
                    } catch (Exception ignored) {
                        continue;
                    }
                }
                keep.put(key, s);
                imported.add(s);
            }
        }
        parsed.clear();
        parsed.putAll(keep);
        if (song == null) {
            String id = prefs.getString("learnSong", "minuet-g");
            Song s = find(id);
            if (s == null && !builtIn.isEmpty()) s = builtIn.get(0);
            if (s != null) selectSong(s, false);
        }
    }

    private Song find(String id) {
        for (Song s : builtIn) if (s.id.equals(id)) return s;
        for (Song s : imported) if (s.id.equals(id)) return s;
        return null;
    }

    static byte[] readAll(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(1 << 16);
        byte[] b = new byte[1 << 16];
        int n;
        while ((n = in.read(b)) > 0) {
            bo.write(b, 0, n);
            if (bo.size() > 4 << 20) throw new java.io.IOException("That file is too big for a MIDI file.");
        }
        return bo.toByteArray();
    }

    /** A MIDI file the player opened: keep a copy and select it. */
    void addImportedFile(String displayName, byte[] data) {
        String name = displayName == null ? "song.mid" : displayName.replaceAll("[^A-Za-z0-9 ._()+\\-]", "_");
        if (!name.toLowerCase(java.util.Locale.ROOT).matches(".*\\.midi?$")) name += ".mid";
        try {
            Song s = SongLibrary.parseMidi(data, name);
            if (!songDir.isDirectory() && !songDir.mkdirs()) throw new java.io.IOException("Couldn't save the file.");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(new File(songDir, name))) {
                out.write(data);
            }
            loadSongs();
            Song saved = find(s.id);
            selectSong(saved != null ? saved : s, true);
        } catch (Exception e) {
            showError(e.getMessage() != null ? e.getMessage() : "Couldn't open that file.");
        }
    }

    void showError(String msg) {
        errorText = msg;
        overlay = OV_ERROR;
        invalidate();
    }

    private void deleteImported(Song s) {
        File f = new File(songDir, s.id.substring("file:".length()));
        if (f.delete()) {
            boolean wasCurrent = song != null && song.id.equals(s.id);
            loadSongs();
            if (wasCurrent && !builtIn.isEmpty()) selectSong(builtIn.get(0), false);
            overlay = OV_LIBRARY;
        }
        invalidate();
    }

    private void selectSong(Song s, boolean showIntro) {
        song = s;
        prefs.edit().putString("learnSong", s.id).apply();
        newSession();
        overlay = showIntro || overlay != OV_LIBRARY ? OV_INTRO : overlay;
        invalidate();
    }

    private void newSession() {
        if (session != null) session.stopAuto();
        if (song == null) { session = null; return; }
        session = new LearnSession(song, mode, hands, new AutoOut(engine));
        applySettings();
        lastFrameNs = System.nanoTime();
    }

    /** Sends the notes Learn mode plays for you to the synth (kept apart from your keys). */
    private static final class AutoOut implements LearnSession.Output {
        private final AudioEngine engine;

        AutoOut(AudioEngine engine) { this.engine = engine; }

        @Override public void autoOn(int note, int velocity) { engine.autoNoteOn(note, velocity); }
        @Override public void autoOff(int note) { engine.autoNoteOff(note); }
        @Override public void autoAllOff() { engine.autoAllOff(); }
    }

    private void applySettings() {
        if (session == null) return;
        session.speed = speed;
        session.anyOctave = anyOctave;
        session.backing = backing;
    }

    private void saveSettings() {
        prefs.edit().putInt("learnMode", mode).putInt("learnHands", hands).putFloat("learnSpeed", speed)
                .putBoolean("learnAnyOct", anyOctave).putBoolean("learnNames", names)
                .putBoolean("learnOtherHand", backing).apply();
    }

    private String bestKey() { return "best:" + song.id + ":" + mode + ":" + hands; }

    private int bestStars(Song s) { return prefs.getInt("stars:" + s.id, 0); }

    // ------------------------------------------------------------------
    // Visibility / lifecycle (UI thread)
    // ------------------------------------------------------------------
    void setKeyboardConnected(boolean on) {
        keyboardConnected = on;
        if (!on) java.util.Arrays.fill(held, false);
        invalidate();
    }

    void onShown() {
        visible = true;
        loadSongs();
        synchronized (events) { events.clear(); }
        router.setKeyListener(this);
        lastFrameNs = System.nanoTime();
        java.util.Arrays.fill(held, false);
        invalidate();
    }

    void onHidden() {
        visible = false;
        router.setKeyListener(null);
        pause();
    }

    void pause() {
        if (session != null && session.playing) session.pause();
        engine.autoAllOff();
        invalidate();
    }

    /** Back button: close an overlay first. */
    boolean handleBack() {
        if (overlay == OV_LIBRARY || overlay == OV_ERROR) {
            overlay = session != null && session.started && !session.finished ? OV_NONE : OV_INTRO;
            invalidate();
            return true;
        }
        if (session != null && session.playing) { session.pause(); invalidate(); return true; }
        return false;
    }

    // ---- MidiRouter.KeyListener (MIDI thread) ----
    @Override
    public void onKey(int note, int velocity, boolean down, long nanoTime) {
        synchronized (events) {
            events.addLast(new long[]{note, velocity, down ? 1 : 0, nanoTime});
            while (events.size() > 256) events.removeFirst();
        }
        postInvalidateOnAnimation();
    }

    // ------------------------------------------------------------------
    // Frame
    // ------------------------------------------------------------------
    private void tick() {
        long now = System.nanoTime();
        if (lastFrameNs == 0) lastFrameNs = now;
        LearnSession s = session;
        if (s != null) s.keyShift = router.keysLo() - KeyFit.LO;   // the MPK's octave right now
        // your keys: judged at the moment they were pressed
        while (true) {
            long[] e;
            synchronized (events) { e = events.pollFirst(); }
            if (e == null) break;
            int n = (int) e[0];
            if (n < 0 || n > 127) continue;
            if (e[2] == 1) {
                held[n] = true;
                if (s != null && s.playing) {
                    float at = s.t + (e[3] - lastFrameNs) / 1e9f * s.speed;
                    s.keyDown(n, at);
                }
            } else {
                held[n] = false;
            }
        }
        if (s != null) {
            if (now - lastLatencyCheck > 1_000_000_000L) {
                lastLatencyCheck = now;
                float l = engine.latencyMs();
                s.audioLead = l > 0 ? Math.min(0.4f, l / 1000f) : 0f;
            }
            boolean wasFinished = s.finished;
            s.step((now - lastFrameNs) / 1e9f);
            if (!wasFinished && s.finished) onFinished();
        }
        lastFrameNs = now;
    }

    private void onFinished() {
        if (mode == LearnSession.LISTEN) { overlay = OV_RESULTS; return; }
        int prev = prefs.getInt(bestKey(), 0);
        lastPrevBest = prev;
        if (session.practice) {   // skipped or looped: show how it went, don't save it as a best
            lastNewBest = false;
            overlay = OV_RESULTS;
            return;
        }
        lastNewBest = session.score > prev;
        SharedPreferences.Editor e = prefs.edit();
        if (lastNewBest) e.putInt(bestKey(), session.score);
        int st = LearnSession.stars(session.accuracy());
        if (st > bestStars(song)) e.putInt("stars:" + song.id, st);
        e.apply();
        overlay = OV_RESULTS;
        performHapticFeedback(HapticFeedbackConstants.CONFIRM);
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------
    @Override
    protected void onDraw(Canvas c) {
        if (!visible) return;
        tick();
        nT = 0;
        int w = getWidth(), h = getHeight();
        land = w > h * 1.15f;
        fill.setColor(BG);
        c.drawRect(0, 0, w, h, fill);
        layoutBars(w, h);
        drawStage(c);
        drawTopBar(c);
        drawBottomBar(c);
        switch (overlay) {
            case OV_INTRO: drawIntro(c); break;
            case OV_RESULTS: drawResults(c); break;
            case OV_LIBRARY: drawLibrary(c); break;
            case OV_ERROR: drawError(c); break;
            default: break;
        }
        boolean animating = session != null && (session.playing || !session.flashes.isEmpty());
        if (animating || dragging) postInvalidateOnAnimation();
    }

    private void layoutBars(int w, int h) {
        float pad = 12 * d;
        if (land) {
            rTop.set(pad, 6 * d, w - pad, 6 * d + 44 * d + 6 * d + 36 * d);
            rBottom.set(pad, h - 54 * d, w - pad, h - 6 * d);
        } else {
            rTop.set(pad, 8 * d, w - pad, 8 * d + 48 * d + 8 * d + 40 * d + 8 * d + 38 * d);
            rBottom.set(pad, h - 104 * d, w - pad, h - 8 * d);
        }
        rStage.set(0, rTop.bottom + 8 * d, w, rBottom.top - 8 * d);
    }

    private void addTarget(int id, RectF rect) {
        if (nT >= MAX_T) return;
        tId[nT] = id;
        tRect[nT].set(rect);
        nT++;
    }

    private void addTarget(int id, float l, float t, float rr, float b) {
        if (nT >= MAX_T) return;
        tId[nT] = id;
        tRect[nT].set(l, t, rr, b);
        nT++;
    }

    private void setText(float sizeSp, int color, Typeface tf, Paint.Align align) {
        text.setTextSize(sizeSp * sp);
        text.setColor(color);
        text.setTypeface(tf);
        text.setTextAlign(align);
    }

    private void drawFit(Canvas c, String s, float x, float y, float maxW) {
        CharSequence t = TextUtils.ellipsize(s, text, Math.max(0, maxW), TextUtils.TruncateAt.END);
        c.drawText(t, 0, t.length(), x, y, text);
    }

    // ---- top bar ----
    private void drawTopBar(Canvas c) {
        float x = rTop.left, right = rTop.right, y = rTop.top;
        float rowH = land ? 44 * d : 48 * d;
        // back
        r.set(x, y + (rowH - 40 * d) / 2, x + 40 * d, y + (rowH + 40 * d) / 2);
        fill.setColor(pressedId == T_BACK ? PANEL_HI : PANEL);
        c.drawOval(r, fill);
        chevron(c, r.centerX() + 2 * d, r.centerY(), -1, TEXT);
        addTarget(T_BACK, r.left - 4 * d, r.top - 4 * d, r.right + 4 * d, r.bottom + 4 * d);
        // songs button
        setText(13.5f, ON_ACCENT, medium, Paint.Align.CENTER);
        float sw = text.measureText("Songs") + 44 * d;
        r.set(right - sw, y + (rowH - 38 * d) / 2, right, y + (rowH + 38 * d) / 2);
        fill.setColor(pressedId == T_SONGS ? 0xFFFFB46B : ACCENT);
        c.drawRoundRect(r, 19 * d, 19 * d, fill);
        listIcon(c, r.left + 18 * d, r.centerY(), ON_ACCENT);
        c.drawText("Songs", r.left + 28 * d + (r.width() - 28 * d) / 2 - 4 * d, r.centerY() + 5 * d, text);
        addTarget(T_SONGS, r);
        float songsLeft = r.left;
        // title
        float tx = x + 52 * d;
        String title = song != null ? song.title : "Loading…";
        String comp = song != null ? song.composer : "";
        setText(15, TEXT, medium, Paint.Align.LEFT);
        drawFit(c, title, tx, y + rowH / 2 - 1 * d, songsLeft - tx - 10 * d);
        setText(11.5f, TEXT2, regular, Paint.Align.LEFT);
        drawFit(c, comp + (song != null ? "  ·  " + song.lengthLabel() : ""), tx, y + rowH / 2 + 15 * d, songsLeft - tx - 10 * d);
        addTarget(T_TITLE, tx, y, songsLeft - 8 * d, y + rowH);

        // controls
        float cy = y + rowH + (land ? 6 : 8) * d;
        float ch = land ? 36 * d : 40 * d;
        if (land) {
            float segW = 210 * d, handsW = 170 * d;
            seg(c, new String[]{"Wait", "Play", "Listen"}, mode, T_MODE, x, cy, x + segW, cy + ch);
            seg(c, new String[]{"Right", "Left", "Both"}, hands, T_HANDS, x + segW + 8 * d, cy, x + segW + 8 * d + handsW, cy + ch);
            optionsRow(c, x + segW + handsW + 16 * d, cy, right, cy + ch);
        } else {
            float mid = x + (right - x) * 0.56f;
            seg(c, new String[]{"Wait", "Play", "Listen"}, mode, T_MODE, x, cy, mid - 4 * d, cy + ch);
            seg(c, new String[]{"Right", "Left", "Both"}, hands, T_HANDS, mid + 4 * d, cy, right, cy + ch);
            float oy = cy + ch + 8 * d;
            optionsRow(c, x, oy, right, oy + 38 * d);
        }
    }

    private void optionsRow(Canvas c, float l, float t, float rr, float b) {
        float h = b - t;
        // speed stepper
        float bw = h;
        r.set(l, t, l + bw, b);
        fill.setColor(pressedId == T_SPD_DOWN ? PANEL_HI : PANEL);
        c.drawRoundRect(r, h / 2, h / 2, fill);
        setText(18, TEXT, regular, Paint.Align.CENTER);
        c.drawText("−", r.centerX(), r.centerY() + 6 * d, text);
        addTarget(T_SPD_DOWN, r);
        float vw = 58 * d;
        r.set(l + bw + 4 * d, t, l + bw + 4 * d + vw, b);
        addTarget(T_SPD_VALUE, r);
        setText(10, TEXT3, medium, Paint.Align.CENTER);
        c.drawText("SPEED", r.centerX(), t + h * 0.38f, text);
        setText(13.5f, speed == 1f ? TEXT : ACCENT, medium, Paint.Align.CENTER);
        c.drawText(Math.round(speed * 100) + "%", r.centerX(), t + h * 0.82f, text);
        r.set(l + bw + 8 * d + vw, t, l + 2 * bw + 8 * d + vw, b);
        fill.setColor(pressedId == T_SPD_UP ? PANEL_HI : PANEL);
        c.drawRoundRect(r, h / 2, h / 2, fill);
        setText(18, TEXT, regular, Paint.Align.CENTER);
        c.drawText("+", r.centerX(), r.centerY() + 6 * d, text);
        addTarget(T_SPD_UP, r);
        // chips
        float cx = r.right + 10 * d;
        float avail = rr - cx, gap = 6 * d;
        String[] labels = {"Any octave", "Names", "Other hand"};
        boolean[] on = {anyOctave, names, backing};
        int[] ids = {T_ANYOCT, T_NAMES, T_BACKING};
        float cw = (avail - 2 * gap) / 3f;
        for (int i = 0; i < 3; i++) {
            r.set(cx + i * (cw + gap), t, cx + i * (cw + gap) + cw, b);
            fill.setColor(pressedId == ids[i] ? PANEL_HI : PANEL);
            c.drawRoundRect(r, h / 2, h / 2, fill);
            stroke.setColor(on[i] ? ACCENT : STROKE);
            stroke.setStrokeWidth((on[i] ? 1.5f : 1f) * d);
            c.drawRoundRect(r, h / 2, h / 2, stroke);
            setText(11.5f, on[i] ? ACCENT : TEXT2, medium, Paint.Align.CENTER);
            CharSequence lt = TextUtils.ellipsize(labels[i], text, cw - 8 * d, TextUtils.TruncateAt.END);
            c.drawText(lt, 0, lt.length(), r.centerX(), r.centerY() + 4.5f * d, text);
            addTarget(ids[i], r);
        }
    }

    private void seg(Canvas c, String[] labels, int sel, int idBase, float l, float t, float rr, float b) {
        r.set(l, t, rr, b);
        fill.setColor(PANEL);
        float rad = (b - t) / 2;
        c.drawRoundRect(r, rad, rad, fill);
        float w = (rr - l) / labels.length;
        for (int i = 0; i < labels.length; i++) {
            float x0 = l + i * w;
            if (i == sel || pressedId == idBase + i) {
                r.set(x0 + 3 * d, t + 3 * d, x0 + w - 3 * d, b - 3 * d);
                fill.setColor(i == sel ? ACCENT : PANEL_HI);
                c.drawRoundRect(r, rad - 3 * d, rad - 3 * d, fill);
            }
            setText(13, i == sel ? ON_ACCENT : TEXT2, medium, Paint.Align.CENTER);
            CharSequence lt = TextUtils.ellipsize(labels[i], text, w - 8 * d, TextUtils.TruncateAt.END);
            c.drawText(lt, 0, lt.length(), x0 + w / 2, (t + b) / 2 + 4.5f * d, text);
            addTarget(idBase + i, x0, t, x0 + w, b);
        }
    }

    private void chevron(Canvas c, float cx, float cy, int dir, int color) {
        stroke.setColor(color);
        stroke.setStrokeWidth(2.2f * d);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        float a = 6 * d;
        c.drawLine(cx - dir * a * 0.5f, cy - a, cx + dir * a * 0.5f, cy, stroke);
        c.drawLine(cx + dir * a * 0.5f, cy, cx - dir * a * 0.5f, cy + a, stroke);
        stroke.setStrokeCap(Paint.Cap.BUTT);
    }

    private void listIcon(Canvas c, float cx, float cy, int color) {
        stroke.setColor(color);
        stroke.setStrokeWidth(2 * d);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        for (int i = -1; i <= 1; i++) c.drawLine(cx - 6 * d, cy + i * 5 * d, cx + 6 * d, cy + i * 5 * d, stroke);
        stroke.setStrokeCap(Paint.Cap.BUTT);
    }

    // ---- stage: falling notes + keyboard ----
    /**
     * Falling notes over the MPK mini's 25 keys. The piece is already fitted to those
     * keys (KeyFit); the keys are numbered by the MPK's current octave (MidiRouter).
     */
    private void drawStage(Canvas c) {
        RectF st = rStage;
        if (song == null || session == null) return;
        LearnSession s = session;
        Song sg = s.song;
        int lo = router.keysLo(), hi = lo + KeyboardLayout.MPK_KEYS - 1;
        s.keyShift = lo - KeyFit.LO;
        float kbH = Math.max(70 * d, Math.min(150 * d, st.height() * 0.22f));
        float lineY = st.bottom - kbH;
        float origin = KeyboardLayout.whiteLeft(lo);
        float ww = st.width() / KeyboardLayout.MPK_WHITES;
        float t = s.t;
        float lookahead = 3.2f * s.speed;
        float pps = (lineY - st.top) / lookahead;
        addTarget(T_STAGE, st);

        c.save();
        c.clipRect(st);
        // lanes at every C and F
        stroke.setColor(0x0DFFFFFF);
        stroke.setStrokeWidth(1);
        for (int n = lo; n <= hi; n++) {
            int pc = n % 12;
            if (pc != 0 && pc != 5) continue;
            float x = (KeyboardLayout.whiteLeft(n) - origin) * ww;
            c.drawLine(x + 0.5f, st.top, x + 0.5f, lineY, stroke);
        }
        // loop region
        if (!Float.isNaN(s.loopA)) {
            float yA = lineY - (s.loopA - t) * pps;
            float yB = !Float.isNaN(s.loopB) ? lineY - (s.loopB - t) * pps : st.top;
            fill.setColor(0x10FF9F43);
            c.drawRect(0, Math.max(st.top, yB), st.width(), Math.min(lineY, yA), fill);
        }
        // falling notes: only the hands you chose (the rest is heard, not shown)
        java.util.Arrays.fill(litKey, 0);
        int[] want = s.mode == LearnSession.WAIT ? s.nextGroup() : new int[0];
        int first = firstNoteFrom(sg, t - sg.maxDur() - 1f);
        for (int i = first; i < sg.size(); i++) {
            float nt = sg.t[i];
            if (nt > t + lookahead + 0.1f) break;
            if (!s.shown[i]) continue;
            float nd = sg.d[i];
            if (nt + nd < t - 1f) continue;
            int n = sg.note[i] + s.keyShift;      // the key on your MPK
            if (n < lo || n > hi) continue;
            boolean black = KeyboardLayout.isBlack(n);
            float xl = (KeyboardLayout.keyLeft(n) - origin) * ww, xr = (KeyboardLayout.keyRight(n) - origin) * ww;
            float y1 = lineY - (nt - t) * pps, y0 = lineY - (nt + nd - t) * pps;
            float padX = black ? 1 * d : 2 * d;
            int col = sg.hand[i] == Song.RIGHT ? RIGHT : sg.hand[i] == Song.LEFT ? LEFT : OTHER;
            if (s.mode == LearnSession.LISTEN && nt <= t && t < nt + nd) litKey[n - lo] = col;
            int alpha = 255;
            if (s.status[i] == LearnSession.HIT) alpha = 64;
            if (s.status[i] == LearnSession.MISSED) { col = MISS; alpha = 128; }
            fill.setColor((col & 0x00FFFFFF) | (alpha << 24));
            r.set(xl + padX, y0, xr - padX, Math.max(y0 + 6 * d, y1));
            c.drawRoundRect(r, 5 * d, 5 * d, fill);
            if (contains(want, i) && s.status[i] == LearnSession.PENDING) {
                stroke.setColor(0xFFFFFFFF);
                stroke.setStrokeWidth(2 * d);
                c.drawRoundRect(r, 5 * d, 5 * d, stroke);
            }
            if (names && s.status[i] != LearnSession.HIT) {
                // the name sits at the note's leading edge; narrow (black-key) notes get a
                // small tab a little wider than the note so it stays readable
                float bw = Math.max(r.width(), 18 * d), bh = Math.min(Math.max(r.height(), 17 * d), 19 * d);
                float cx = r.centerX();
                if (bw > r.width() || bh > r.height()) {
                    r.set(cx - bw / 2, r.bottom - bh, cx + bw / 2, r.bottom);
                    c.drawRoundRect(r, 4 * d, 4 * d, fill);
                }
                setText(Math.min(12, (bw - 4 * d) / sp * 0.6f), 0xE60B0D12, bold, Paint.Align.CENTER);
                c.drawText(PC[Math.floorMod(n, 12)], cx, r.bottom - Math.min(6 * d, (bh - text.getTextSize() * 0.7f) / 2), text);
            }
        }
        // hit line
        fill.setColor(ACCENT);
        c.drawRect(0, lineY - 2 * d, st.width(), lineY + 1 * d, fill);

        // the MPK's 25 keys
        float keyTop = lineY + 1 * d, keyBottom = st.bottom;
        boolean waiting = s.mode == LearnSession.WAIT && want.length > 0 && t >= s.nextGroupTime() - 0.05f;
        for (int pass = 0; pass < 2; pass++) {
            for (int n = lo; n <= hi; n++) {
                boolean black = KeyboardLayout.isBlack(n);
                if ((pass == 0) == black) continue;
                float xl = (KeyboardLayout.keyLeft(n) - origin) * ww, xr = (KeyboardLayout.keyRight(n) - origin) * ww;
                float kb = black ? keyTop + (keyBottom - keyTop) * KeyboardLayout.BLACK_HEIGHT : keyBottom;
                int col = black ? 0xFF15171D : 0xFFF4F5F8;
                if (litKey[n - lo] != 0) col = litKey[n - lo];
                if (waiting && wants(want, n - s.keyShift)) col = black ? 0xFF5A4A20 : 0xFFFBE7B5;
                if (held[n]) col = ACCENT;
                fill.setColor(col);
                r.set(xl + (black ? 0 : 1 * d), keyTop, xr - (black ? 0 : 1 * d), kb - 1 * d);
                c.drawRoundRect(r, 3.5f * d, 3.5f * d, fill);
                if (!black && n % 12 == 0) {
                    setText(10, 0x73000000, regular, Paint.Align.CENTER);
                    c.drawText(MidiRouter.noteName(n), (xl + xr) / 2, kb - 7 * d, text);
                }
            }
        }
        // judgements
        double now = s.clock();
        for (LearnSession.Flash f : s.flashes) {
            double age = now - f.at;
            if (age > 0.7) continue;
            int dn = KeyFit.fold(f.note, lo, hi);
            float x = ((KeyboardLayout.keyLeft(dn) + KeyboardLayout.keyRight(dn)) / 2 - origin) * ww;
            int col = f.kind == LearnSession.G_PERFECT ? PERFECT : f.kind == LearnSession.G_GREAT ? GREAT
                    : f.kind == LearnSession.G_GOOD ? GOOD : MISS;
            int a = (int) (255 * (1 - age / 0.7));
            setText(14, (col & 0x00FFFFFF) | (a << 24), bold, Paint.Align.CENTER);
            String label = f.kind == LearnSession.G_PERFECT ? "Perfect" : f.kind == LearnSession.G_GREAT ? "Great"
                    : f.kind == LearnSession.G_GOOD ? "Good" : f.kind == LearnSession.G_MISS ? "Miss" : "✕";
            c.drawText(label, Math.max(28 * d, Math.min(st.width() - 28 * d, x)), lineY - 14 * d - (float) age * 40 * d, text);
        }
        while (!s.flashes.isEmpty() && now - s.flashes.peekFirst().at > 0.7) s.flashes.removeFirst();
        // count-in / what to play
        if (s.playing && t < 0) {
            setText(48, 0xCCFFFFFF, bold, Paint.Align.CENTER);
            c.drawText(String.valueOf((int) Math.ceil(-t / s.speed)), st.centerX(), (st.top + lineY) / 2, text);
        } else if (s.playing && waiting) {
            StringBuilder sb = new StringBuilder("Play ");
            boolean firstName = true;
            for (int i : want) {
                if (s.status[i] != LearnSession.PENDING) continue;
                if (!firstName) sb.append(" + ");
                sb.append(PC[Math.floorMod(sg.note[i], 12)]);
                firstName = false;
            }
            setText(15, 0xE6FFFFFF, medium, Paint.Align.CENTER);
            labelPill(c, sb.toString(), st.centerX(), st.top + 30 * d);
        } else if (!s.playing && s.started && !s.finished && overlay == OV_NONE) {
            setText(16, 0xE6FFFFFF, medium, Paint.Align.CENTER);
            labelPill(c, "Paused  ·  tap to resume", st.centerX(), (st.top + lineY) / 2);
        }
        c.restore();
    }

    /** Centred text on a dark pill, so it reads over the falling notes. */
    private void labelPill(Canvas c, String label, float cx, float baseline) {
        float tw = text.measureText(label), ts = text.getTextSize();
        r.set(cx - tw / 2 - 14 * d, baseline - ts * 0.95f - 6 * d, cx + tw / 2 + 14 * d, baseline + ts * 0.3f + 6 * d);
        fill.setColor(0xE0101216);
        c.drawRoundRect(r, r.height() / 2, r.height() / 2, fill);
        c.drawText(label, cx, baseline, text);
    }

    private static int firstNoteFrom(Song sg, float time) {
        int lo = 0, hi = sg.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sg.t[mid] < time) lo = mid + 1; else hi = mid;
        }
        return lo;
    }

    private static boolean contains(int[] a, int v) {
        for (int x : a) if (x == v) return true;
        return false;
    }

    /** Is fitted note {@code n} one of the chord's notes still to play? */
    private boolean wants(int[] group, int n) {
        for (int i : group) if (session.song.note[i] == n && session.status[i] == LearnSession.PENDING) return true;
        return false;
    }

    // ---- bottom bar: transport, timeline, stats ----
    private void drawBottomBar(Canvas c) {
        LearnSession s = session;
        float x = rBottom.left, right = rBottom.right, y = rBottom.top;
        float rowH = 48 * d;
        String playLabel = s == null ? "Start" : s.finished ? "Play again" : s.playing ? "Pause" : s.started ? "Resume" : "Start";
        setText(15, ON_ACCENT, medium, Paint.Align.CENTER);
        float pw = Math.max(96 * d, text.measureText(playLabel) + 32 * d);
        r.set(x, y, x + pw, y + rowH);
        fill.setColor(pressedId == T_PLAY ? 0xFFFFB46B : ACCENT);
        c.drawRoundRect(r, 14 * d, 14 * d, fill);
        c.drawText(playLabel, r.centerX(), r.centerY() + 5.5f * d, text);
        addTarget(T_PLAY, r);
        float bx = r.right + 8 * d;
        iconButton(c, bx, y, rowH, T_RESTART, "restart", false);
        bx += rowH + 6 * d;
        iconButton(c, bx, y, rowH, T_LOOPA, "A", s != null && !Float.isNaN(s.loopA));
        bx += rowH + 6 * d;
        iconButton(c, bx, y, rowH, T_LOOPB, "B", s != null && !Float.isNaN(s.loopB));
        bx += rowH + 10 * d;

        float statsW = land ? 300 * d : 0;
        float tlRight = land ? right - statsW - 12 * d : right;
        // timeline
        float tlY = y + rowH / 2;
        r.set(bx, tlY - 5 * d, tlRight, tlY + 5 * d);
        fill.setColor(PANEL_HI);
        c.drawRoundRect(r, 5 * d, 5 * d, fill);
        float dur = s != null ? s.song.duration : 0;
        if (s != null && dur > 0) {
            float frac = Math.max(0, Math.min(1, s.t / dur));
            fill.setColor(ACCENT);
            c.drawRoundRect(r.left, r.top, r.left + r.width() * frac, r.bottom, 5 * d, 5 * d, fill);
            fill.setColor(0xFFFFFFFF);
            for (float v : new float[]{s.loopA, s.loopB}) {
                if (Float.isNaN(v)) continue;
                float mx = r.left + r.width() * Math.max(0, Math.min(1, v / dur));
                c.drawRect(mx - 1 * d, r.top - 5 * d, mx + 1 * d, r.bottom + 5 * d, fill);
            }
        }
        addTarget(T_TIMELINE, r.left - 4 * d, y, r.right + 4 * d, y + rowH);

        // stats
        float sy, sx, sw;
        if (land) { sx = right - statsW; sy = y; sw = statsW; }
        else { sx = x; sy = y + rowH + 8 * d; sw = right - x; }
        String acc = "–";
        if (s != null) { float a = s.accuracy(); if (!Float.isNaN(a)) acc = Math.round(a * 100) + "%"; }
        String[] lab = {"SCORE", "STREAK", "MULTIPLIER", "ACCURACY"};
        String[] val = {s == null ? "0" : String.format(java.util.Locale.ROOT, "%,d", s.score),
                s == null ? "0" : String.valueOf(s.streak), "×" + (s == null ? 1 : s.multiplier()), acc};
        float cw = sw / 4f;
        for (int i = 0; i < 4; i++) {
            float cx0 = sx + i * cw;
            setText(9.5f, TEXT3, medium, Paint.Align.LEFT);
            text.setLetterSpacing(0.08f);
            drawFit(c, lab[i], cx0, sy + 14 * d, cw - 4 * d);
            text.setLetterSpacing(0f);
            setText(17, TEXT, medium, Paint.Align.LEFT);
            drawFit(c, val[i], cx0, sy + 36 * d, cw - 4 * d);
        }
    }

    private void iconButton(Canvas c, float x, float y, float s, int id, String what, boolean on) {
        r.set(x, y, x + s, y + s);
        fill.setColor(pressedId == id ? PANEL_HI : PANEL);
        c.drawRoundRect(r, 14 * d, 14 * d, fill);
        if (on) {
            stroke.setColor(ACCENT);
            stroke.setStrokeWidth(1.5f * d);
            c.drawRoundRect(r, 14 * d, 14 * d, stroke);
        }
        addTarget(id, r);
        float cx = r.centerX(), cy = r.centerY();
        if (what.equals("restart")) {
            stroke.setColor(TEXT);
            stroke.setStrokeWidth(2.2f * d);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            RectF a = new RectF(cx - 8 * d, cy - 8 * d, cx + 8 * d, cy + 8 * d);
            c.drawArc(a, -60, 290, false, stroke);
            path.reset();
            path.moveTo(cx + 9 * d, cy - 11 * d);
            path.lineTo(cx + 9 * d, cy - 4 * d);
            path.lineTo(cx + 2 * d, cy - 5 * d);
            c.drawPath(path, stroke);
            stroke.setStrokeCap(Paint.Cap.BUTT);
        } else {
            setText(15, on ? ACCENT : TEXT, bold, Paint.Align.CENTER);
            c.drawText(what, cx, cy + 5.5f * d, text);
        }
    }

    // ------------------------------------------------------------------
    // Overlays
    // ------------------------------------------------------------------
    /** Card width: wider in landscape, where height is short. */
    private float cardW() {
        return Math.min(getWidth() - 32 * d, (land ? 600 : 440) * d);
    }

    /** Space a card may use: below the top bar (which stays usable), or the whole screen. */
    private float cardAreaTop(boolean keepTop) { return keepTop ? rTop.bottom + 8 * d : 12 * d; }

    private float cardMaxH(boolean keepTop) { return getHeight() - 8 * d - cardAreaTop(keepTop); }

    /**
     * Draws the dimmed backdrop and an empty card. With keepTop, the top bar
     * (song, mode, hands, speed, options) stays bright and can be used while
     * the card is up, so you can set things up before pressing Start.
     */
    private RectF card(Canvas c, float cardH, boolean keepTop) {
        float areaTop = cardAreaTop(keepTop);
        cardH = Math.min(cardH, cardMaxH(keepTop));
        float shadeTop = keepTop ? rTop.bottom + 2 * d : 0;
        fill.setColor(0xB80A0C12);
        c.drawRect(0, shadeTop, getWidth(), getHeight(), fill);
        addTarget(T_SHIELD, 0, shadeTop, getWidth(), getHeight());
        float w = cardW();
        float top = areaTop + Math.max(0, (getHeight() - 8 * d - areaTop - cardH) / 2);
        RectF cr = new RectF((getWidth() - w) / 2, top, (getWidth() + w) / 2, top + cardH);
        fill.setColor(0xFF181B22);
        c.drawRoundRect(cr, 18 * d, 18 * d, fill);
        stroke.setColor(STROKE);
        stroke.setStrokeWidth(1 * d);
        c.drawRoundRect(cr, 18 * d, 18 * d, stroke);
        addTarget(-2, cr); // swallow taps on the card itself
        return cr;
    }

    private StaticLayout layout(String s, int width, float sizeSp, int color) {
        body.setTypeface(regular);
        body.setTextSize(sizeSp * sp);
        body.setColor(color);
        return StaticLayout.Builder.obtain(s, 0, s.length(), body, Math.max(10, width))
                .setLineSpacing(3 * d, 1f).setAlignment(Layout.Alignment.ALIGN_NORMAL).build();
    }

    private float drawLayout(Canvas c, StaticLayout l, float x, float y) {
        c.save();
        c.translate(x, y);
        l.draw(c);
        c.restore();
        return y + l.getHeight();
    }

    private void button(Canvas c, RectF b, String label, boolean primary, int id) {
        boolean down = pressedId == id;
        fill.setColor(primary ? (down ? 0xFFFFB46B : ACCENT) : (down ? PANEL_HI : PANEL));
        c.drawRoundRect(b, 13 * d, 13 * d, fill);
        setText(14.5f, primary ? ON_ACCENT : TEXT, medium, Paint.Align.CENTER);
        CharSequence t = TextUtils.ellipsize(label, text, b.width() - 12 * d, TextUtils.TruncateAt.END);
        c.drawText(t, 0, t.length(), b.centerX(), b.centerY() + 5 * d, text);
        addTarget(id, b);
    }

    private String modeText() {
        switch (mode) {
            case LearnSession.WAIT: return "The music waits at each note until you play it. Take your time.";
            case LearnSession.PLAY: return "Notes keep falling. Play each one as it reaches the orange line: Perfect, Great or Good.";
            default: return "Sit back and hear how it goes first.";
        }
    }

    private String handsText() {
        if (mode == LearnSession.LISTEN) return "";
        String other = hands == LearnSession.HANDS_RIGHT ? "left" : "right";
        if (hands == LearnSession.HANDS_BOTH) return "You play both hands.";
        String you = "You play the " + (hands == LearnSession.HANDS_RIGHT ? "right" : "left") + " hand.";
        return backing ? you + " With Other hand on, the app plays the " + other + " hand along with you."
                : you + " Only your keys make a sound; turn on Other hand to hear the " + other + " hand too.";
    }

    private void drawIntro(Canvas c) {
        if (song == null) return;
        int tw = (int) (cardW() - 44 * d);
        float gap = land ? 0.6f : 1f;   // landscape is short: tighter spacing
        StaticLayout lTitle = layout(song.title, tw, 19, TEXT);
        StaticLayout lComp = layout(song.composer + "  ·  " + song.lengthLabel(), tw, 13, TEXT2);
        StaticLayout lMode = layout(modeText() + " " + handsText(), tw, 13.5f, TEXT2);
        String legend = "The piece is fitted to your MPK's 25 keys: play the key each note falls on. "
                + "Green notes are the right hand, blue the left."
                + (anyOctave ? " With \"Any octave\" on, the same note on another key counts too." : "");
        boolean kbd = keyboardConnected;
        StaticLayout lLegend = layout(legend, tw, 12.5f, TEXT3);
        StaticLayout lKbd = kbd ? null : layout("Plug in your keyboard to play along.", tw, 12.5f, ACCENT);
        StaticLayout lCredit = layout(song.credit, tw, 11, TEXT3);
        float h = 22 * d + lTitle.getHeight() + 4 * d + lComp.getHeight() + 14 * d * gap + lMode.getHeight()
                + 10 * d * gap + lLegend.getHeight() + (lKbd != null ? 8 * d * gap + lKbd.getHeight() : 0)
                + 10 * d * gap + lCredit.getHeight() + 18 * d * gap + 48 * d + 20 * d;
        RectF cr = card(c, h, true);
        float x = cr.left + 22 * d, y = cr.top + 22 * d;
        float by = cr.bottom - 20 * d - 48 * d;
        c.save();
        c.clipRect(cr.left, cr.top, cr.right, by - 8 * d);
        y = drawLayout(c, lTitle, x, y) + 4 * d;
        y = drawLayout(c, lComp, x, y) + 14 * d * gap;
        y = drawLayout(c, lMode, x, y) + 10 * d * gap;
        y = drawLayout(c, lLegend, x, y);
        if (lKbd != null) y = drawLayout(c, lKbd, x, y + 8 * d * gap);
        drawLayout(c, lCredit, x, y + 10 * d * gap);
        c.restore();
        float bw = (cr.width() - 44 * d - 10 * d) / 2;
        button(c, new RectF(x, by, x + bw, by + 48 * d), "Songs", false, T_CARD_SONGS);
        button(c, new RectF(x + bw + 10 * d, by, x + 2 * bw + 10 * d, by + 48 * d),
                mode == LearnSession.LISTEN ? "Listen" : "Start", true, T_CARD_START);
    }

    private void drawResults(Canvas c) {
        LearnSession s = session;
        if (s == null) return;
        int tw = (int) (cardW() - 44 * d);
        boolean listen = mode == LearnSession.LISTEN;
        float a = s.accuracy();
        int stars = LearnSession.stars(a);
        String tip = tip(a);
        String sub = listen ? "Now switch to Wait and try it yourself."
                : s.practice ? "Practice run (you jumped or looped), so it isn't saved as a best. Play it start to finish to set one."
                : lastNewBest ? "New best score!" : lastPrevBest > 0 ? "Best so far: " + fmt(lastPrevBest) : "";
        // landscape: stars beside the title and the six numbers in one row
        setText(land ? 24 : 30, ACCENT, regular, Paint.Align.LEFT);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) sb.append(i < stars ? "★" : "☆");
        String starStr = sb.toString();
        float starW = listen || !land ? 0 : text.measureText(starStr) + 12 * d;
        StaticLayout lTitle = layout(listen ? "That's how it goes" : song.title, (int) (tw - starW), 19, TEXT);
        StaticLayout lSub = sub.isEmpty() ? null : layout(sub, tw, 13.5f, lastNewBest && !listen ? ACCENT : TEXT2);
        StaticLayout lTip = tip.isEmpty() ? null : layout(tip, tw, 13, TEXT2);
        int cols = land ? 6 : 3;
        float statsH = listen ? 0 : (6 / cols) * 64 * d - 8 * d;
        float h = 22 * d + lTitle.getHeight() + (listen || land ? 0 : 44 * d)
                + (lSub != null ? 6 * d + lSub.getHeight() : 0)
                + (listen ? 0 : 12 * d + statsH)
                + (lTip != null ? 10 * d + lTip.getHeight() : 0) + 18 * d + 48 * d + 20 * d;
        RectF cr = card(c, h, true);
        float x = cr.left + 22 * d, y = cr.top + 22 * d;
        float by = cr.bottom - 20 * d - 48 * d;
        c.save();
        c.clipRect(cr.left, cr.top, cr.right, by - 6 * d);
        float titleTop = y;
        y = drawLayout(c, lTitle, x, y);
        if (!listen) {
            setText(land ? 24 : 30, ACCENT, regular, land ? Paint.Align.RIGHT : Paint.Align.LEFT);
            if (land) {
                c.drawText(starStr, cr.right - 22 * d, titleTop + 22 * d, text);
            } else {
                c.drawText(starStr, x, y + 36 * d, text);
                y += 44 * d;
            }
        }
        if (lSub != null) y = drawLayout(c, lSub, x, y + 6 * d);
        if (!listen) {
            y += 12 * d;
            String[] v;
            String[] l;
            if (mode == LearnSession.PLAY) {
                v = new String[]{fmt(s.score), Float.isNaN(a) ? "–" : Math.round(a * 100) + "%", String.valueOf(s.bestStreak),
                        String.valueOf(s.counts[0]), String.valueOf(s.counts[1] + s.counts[2]), String.valueOf(s.counts[3])};
                l = new String[]{"score", "accuracy", "best streak", "perfect", "great / good", "missed"};
            } else {
                v = new String[]{fmt(s.score), Float.isNaN(a) ? "–" : Math.round(a * 100) + "%", String.valueOf(s.bestStreak),
                        String.valueOf(s.hits), String.valueOf(s.counts[LearnSession.G_WRONG]), Math.round(speed * 100) + "%"};
                l = new String[]{"score", "accuracy", "best streak", "notes", "wrong keys", "speed"};
            }
            float gw = (cr.width() - 44 * d - (cols - 1) * 8 * d) / cols;
            for (int i = 0; i < 6; i++) {
                float gx = x + (i % cols) * (gw + 8 * d), gy = y + (i / cols) * 64 * d;
                r.set(gx, gy, gx + gw, gy + 56 * d);
                fill.setColor(PANEL_HI);
                c.drawRoundRect(r, 10 * d, 10 * d, fill);
                setText(17, TEXT, medium, Paint.Align.CENTER);
                drawFit(c, v[i], r.centerX(), gy + 26 * d, gw - 8 * d);
                setText(11, TEXT3, regular, Paint.Align.CENTER);
                drawFit(c, l[i], r.centerX(), gy + 45 * d, gw - 6 * d);
            }
            y += statsH;
        }
        if (lTip != null) drawLayout(c, lTip, x, y + 10 * d);
        c.restore();
        float bw = (cr.width() - 44 * d - 10 * d) / 2;
        button(c, new RectF(x, by, x + bw, by + 48 * d), "Songs", false, T_CARD_SONGS);
        button(c, new RectF(x + bw + 10 * d, by, x + 2 * bw + 10 * d, by + 48 * d), listen ? "Listen again" : "Play again", true, T_CARD_AGAIN);
    }

    private static String fmt(int v) { return String.format(java.util.Locale.ROOT, "%,d", v); }

    private String tip(float a) {
        if (Float.isNaN(a) || mode == LearnSession.LISTEN) return "";
        if (mode == LearnSession.WAIT && a >= 0.9f) return "Ready for the next step: try Play, where the notes keep falling.";
        if (mode == LearnSession.PLAY && a >= 0.9f && speed < 1f) return "Nice. Push the speed up a notch.";
        if (hands != LearnSession.HANDS_BOTH && a >= 0.9f) return "Try the other hand, then Both.";
        if (a < 0.6f) return "Slow the speed down, or loop the tricky bit: press A at its start and B at its end.";
        return "";
    }

    private void drawError(Canvas c) {
        int tw = (int) (cardW() - 44 * d);
        StaticLayout lt = layout("Couldn't open that file", tw, 18, TEXT);
        StaticLayout lm = layout(errorText == null ? "" : errorText, tw, 13.5f, TEXT2);
        float h = 22 * d + lt.getHeight() + 8 * d + lm.getHeight() + 18 * d + 48 * d + 20 * d;
        RectF cr = card(c, h, false);
        float x = cr.left + 22 * d, y = cr.top + 22 * d;
        y = drawLayout(c, lt, x, y) + 8 * d;
        drawLayout(c, lm, x, y);
        float by = cr.bottom - 20 * d - 48 * d;
        button(c, new RectF(cr.right - 22 * d - 120 * d, by, cr.right - 22 * d, by + 48 * d), "OK", true, T_CARD_OK);
    }

    // ---- song library ----
    private List<Object> libRows() {
        List<Object> rows = new ArrayList<>();
        String group = null;
        for (Song s : builtIn) {
            if (!s.group.equals(group)) { group = s.group; rows.add(group); }
            rows.add(s);
        }
        rows.add("Your MIDI files");
        if (imported.isEmpty()) rows.add(Boolean.TRUE); // hint row
        rows.addAll(imported);
        return rows;
    }

    private void drawLibrary(Canvas c) {
        int w = getWidth(), h = getHeight();
        fill.setColor(BG);
        c.drawRect(0, 0, w, h, fill);
        addTarget(T_SHIELD, 0, 0, w, h);
        float pad = 16 * d;
        float x = pad, right = w - pad;
        // header
        setText(20, TEXT, medium, Paint.Align.LEFT);
        c.drawText("Songs", x, 40 * d, text);
        r.set(right - 44 * d, 12 * d, right, 56 * d);
        stroke.setColor(pressedId == T_LIB_CLOSE ? ACCENT : TEXT2);
        stroke.setStrokeWidth(2.2f * d);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        float cx = r.centerX(), cy = r.centerY(), a = 7 * d;
        c.drawLine(cx - a, cy - a, cx + a, cy + a, stroke);
        c.drawLine(cx - a, cy + a, cx + a, cy - a, stroke);
        stroke.setStrokeCap(Paint.Cap.BUTT);
        addTarget(T_LIB_CLOSE, r);
        // open file
        r.set(x, 64 * d, right, 64 * d + 46 * d);
        fill.setColor(pressedId == T_LIB_OPEN ? PANEL_HI : PANEL);
        c.drawRoundRect(r, 13 * d, 13 * d, fill);
        stroke.setColor(STROKE);
        stroke.setStrokeWidth(1 * d);
        c.drawRoundRect(r, 13 * d, 13 * d, stroke);
        setText(14, ACCENT, medium, Paint.Align.CENTER);
        c.drawText("Open a MIDI file…", r.centerX(), r.centerY() + 5 * d, text);
        addTarget(T_LIB_OPEN, r);

        float top = r.bottom + 10 * d, bottom = h - 8 * d;
        libViewH = bottom - top;
        addTarget(T_LIB_BODY, 0, top, w, bottom);
        c.save();
        c.clipRect(0, top, w, bottom);
        List<Object> rows = libRows();
        float y = top - libScroll;
        float start = y;
        for (int i = 0; i < rows.size(); i++) {
            Object o = rows.get(i);
            if (o instanceof String) {
                y += 14 * d;
                setText(11, TEXT3, medium, Paint.Align.LEFT);
                text.setLetterSpacing(0.12f);
                c.drawText(((String) o).toUpperCase(java.util.Locale.ROOT), x, y + 14 * d, text);
                text.setLetterSpacing(0f);
                y += 26 * d;
                continue;
            }
            if (o instanceof Boolean) {
                setText(12.5f, TEXT3, regular, Paint.Align.LEFT);
                drawFit(c, "Open a .mid file from your phone and it's saved here.", x, y + 22 * d, right - x);
                y += 36 * d;
                continue;
            }
            Song s = (Song) o;
            float rowH = 62 * d;
            if (y + rowH >= top && y <= bottom) {
                boolean cur = song != null && s.id.equals(song.id);
                r.set(x, y, right, y + rowH - 6 * d);
                fill.setColor(cur ? 0xFF2A2219 : (pressedId == T_LIB_ROW + i ? PANEL_HI : PANEL));
                c.drawRoundRect(r, 13 * d, 13 * d, fill);
                if (cur) {
                    stroke.setColor(ACCENT);
                    stroke.setStrokeWidth(1.5f * d);
                    c.drawRoundRect(r, 13 * d, 13 * d, stroke);
                }
                float textRight = right - 14 * d;
                if (s.imported) {
                    RectF del = new RectF(right - 46 * d, r.top, right, r.bottom);
                    stroke.setColor(pressedId == T_LIB_DEL + i ? MISS : TEXT3);
                    stroke.setStrokeWidth(2 * d);
                    float dx = del.centerX(), dy = del.centerY(), dd = 5.5f * d;
                    c.drawLine(dx - dd, dy - dd, dx + dd, dy + dd, stroke);
                    c.drawLine(dx - dd, dy + dd, dx + dd, dy - dd, stroke);
                    textRight = del.left;
                }
                int st = bestStars(s);
                String starStr = "";
                if (st > 0) {
                    StringBuilder sb = new StringBuilder();
                    for (int k = 0; k < 5; k++) sb.append(k < st ? "★" : "☆");
                    starStr = sb.toString();
                    setText(12, ACCENT, regular, Paint.Align.RIGHT);
                    c.drawText(starStr, textRight, r.top + 24 * d, text);
                }
                setText(12, TEXT3, regular, Paint.Align.RIGHT);
                c.drawText(s.lengthLabel(), textRight, r.top + 44 * d, text);
                float tx = r.left + 14 * d;
                setText(12, 0, regular, Paint.Align.RIGHT);
                float reserve = Math.max(text.measureText(starStr), 40 * d) + 12 * d;
                setText(14.5f, cur ? ACCENT : TEXT, medium, Paint.Align.LEFT);
                drawFit(c, s.title, tx, r.top + 24 * d, textRight - tx - reserve);
                setText(12, TEXT2, regular, Paint.Align.LEFT);
                drawFit(c, s.composer, tx, r.top + 44 * d, textRight - tx - reserve);
                addTarget(T_LIB_ROW + i, r.left, Math.max(r.top, top), s.imported ? textRight : r.right, Math.min(r.bottom, bottom));
                if (s.imported) addTarget(T_LIB_DEL + i, textRight, Math.max(r.top, top), right, Math.min(r.bottom, bottom));
            }
            y += rowH;
        }
        y += 24 * d;
        libContentH = y - start;
        c.restore();
        float max = Math.max(0, libContentH - libViewH);
        if (libScroll > max) { libScroll = max; invalidate(); }
    }

    // ------------------------------------------------------------------
    // Touch
    // ------------------------------------------------------------------
    private int hit(float x, float y) {
        for (int i = nT - 1; i >= 0; i--) if (tRect[i].contains(x, y)) return tId[i];
        return -1;
    }

    private boolean scrollable(int id) {
        return overlay == OV_LIBRARY && (id == T_LIB_BODY || (id >= T_LIB_ROW && id < T_LIB_DEL + 10000));
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downId = hit(x, y);
                pressedId = downId;
                downX = x;
                downY = y;
                downScroll = libScroll;
                dragging = false;
                if (downId == T_TIMELINE) { dragging = true; seekTo(x); }
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (downId == T_TIMELINE) {
                    seekTo(x);
                } else if (scrollable(downId) && (dragging || Math.abs(y - downY) > 10 * d)) {
                    dragging = true;
                    pressedId = -1;
                    float max = Math.max(0, libContentH - libViewH);
                    libScroll = Math.max(0, Math.min(max, downScroll - (y - downY)));
                } else if (hit(x, y) != downId) {
                    pressedId = -1;
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_UP: {
                int id = hit(x, y);
                boolean wasDrag = dragging;
                dragging = false;
                if (!wasDrag && id == downId && pressedId != -1) click(id);
                pressedId = -1;
                downId = -1;
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                pressedId = -1;
                downId = -1;
                dragging = false;
                invalidate();
                return true;
        }
        return super.onTouchEvent(e);
    }

    private void seekTo(float x) {
        if (session == null || song == null) return;
        RectF tl = null;
        for (int i = nT - 1; i >= 0; i--) if (tId[i] == T_TIMELINE) { tl = tRect[i]; break; }
        if (tl == null) return;
        float frac = (x - tl.left - 4 * d) / Math.max(1, tl.width() - 8 * d);
        session.seek(Math.max(0, Math.min(1, frac)) * session.song.duration);
        lastFrameNs = System.nanoTime();
        if (overlay == OV_RESULTS) overlay = OV_NONE;
    }

    private void click(int id) {
        if (id < 0 || id == -2 || id == T_SHIELD) return;
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        if (id >= T_LIB_DEL) {
            Object o = rowAt(id - T_LIB_DEL);
            if (o instanceof Song) deleteImported((Song) o);
            return;
        }
        if (id >= T_LIB_ROW) {
            Object o = rowAt(id - T_LIB_ROW);
            if (o instanceof Song) selectSong((Song) o, true);
            return;
        }
        if (id >= T_MODE && id < T_MODE + 3) { setMode(id - T_MODE); return; }
        if (id >= T_HANDS && id < T_HANDS + 3) { setHands(id - T_HANDS); return; }
        switch (id) {
            case T_BACK: host.closeLearn(); break;
            case T_TITLE:
            case T_SONGS:
            case T_CARD_SONGS:
                if (session != null) session.pause();
                overlay = OV_LIBRARY;
                break;
            case T_LIB_CLOSE:
                overlay = session != null && session.started && !session.finished ? OV_NONE : OV_INTRO;
                break;
            case T_LIB_OPEN: host.openMidiFile(); break;
            case T_SPD_DOWN: setSpeed(speed - 0.05f); break;
            case T_SPD_UP: setSpeed(speed + 0.05f); break;
            case T_SPD_VALUE: setSpeed(1f); break;
            case T_ANYOCT: anyOctave = !anyOctave; applySettings(); saveSettings(); break;
            case T_NAMES: names = !names; saveSettings(); break;
            case T_BACKING:
                backing = !backing;
                applySettings();
                if (!backing && session != null) session.stopAuto();
                saveSettings();
                break;
            case T_PLAY:
            case T_CARD_START:
                startOrToggle();
                break;
            case T_CARD_AGAIN:
                newSession();
                overlay = OV_NONE;
                session.togglePlay();
                break;
            case T_RESTART:
                newSession();
                overlay = OV_INTRO;
                break;
            case T_LOOPA: if (session != null) session.setLoopA(); break;
            case T_LOOPB: if (session != null) session.setLoopB(); break;
            case T_CARD_OK: overlay = session != null && session.started && !session.finished ? OV_NONE : OV_INTRO; break;
            case T_STAGE:
                if (session != null && session.started && !session.finished) startOrToggle();
                break;
            default: break;
        }
        invalidate();
    }

    private Object rowAt(int i) {
        List<Object> rows = libRows();
        return i >= 0 && i < rows.size() ? rows.get(i) : null;
    }

    private void startOrToggle() {
        if (session == null) return;
        if (session.finished) newSession();
        overlay = OV_NONE;
        session.togglePlay();
        lastFrameNs = System.nanoTime();
    }

    private void setMode(int m) {
        if (m == mode) return;
        mode = m;
        saveSettings();
        newSession();
        overlay = OV_INTRO;
    }

    private void setHands(int hnd) {
        if (hnd == hands) return;
        hands = hnd;
        saveSettings();
        newSession();
        overlay = OV_INTRO;
    }

    private void setSpeed(float s) {
        speed = Math.max(0.25f, Math.min(1.5f, Math.round(s * 20) / 20f));
        applySettings();
        saveSettings();
    }
}

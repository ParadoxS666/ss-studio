package com.pocketkeys.piano;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.List;

/** The whole UI, drawn on one canvas: status, octave, sounds, reverb, piano, settings sheet. */
final class StageView extends View implements MidiRouter.Ui {
    // ---- colors ----
    private static final int BG_TOP = 0xFF15161A, BG_BOTTOM = 0xFF0C0D0F;
    private static final int PANEL = 0xFF1B1D22, PANEL_HI = 0xFF23262D, STROKE = 0xFF2A2E36;
    private static final int TEXT = 0xFFF1F1F2, TEXT2 = 0xFFA0A5AD, TEXT3 = 0xFF666B73;
    private static final int ACCENT = 0xFFFF9F43, ON_ACCENT = 0xFF1A1205;
    private static final int GOOD = 0xFF3DDC84, WARN = 0xFFFFC145, BAD = 0xFFFF6B6B;

    // ---- targets ----
    private static final int T_NONE = 0, T_GEAR = 1, T_OCT_PANEL = 2, T_REVERB = 4,
            T_HINT = 5, T_TILE = 10, T_SHEET_BG = 30, T_SHEET_CLOSE = 31, T_SHEET_BODY = 32,
            T_VEL_PRESET = 40, T_SLIDER = 50, T_TEACH = 60, T_CLEAR = 70, T_PADS = 80, T_NAMING = 90,
            T_BAN_PREV = 100, T_BAN_NEXT = 101, T_BAN_IGNORE = 102, T_BAN_STOP = 103, T_ASK = 104,
            T_LEARN = 110, T_KEYS_DOWN = 111, T_KEYS_UP = 112;

    // ---- velocity sliders ----
    private static final int SL_CURVE = 0, SL_FULL = 1, SL_SOFT = 2, SL_LOUD = 3, SL_DYN = 4, SL_FIXED = 5, NSL = 6;
    private static final String[] SL_LABEL = {"Curve", "Full volume at", "Softest note", "Loudest note",
            "Dynamic range", "Fixed level"};
    private static final String[] TEACH_LABEL = {"Sound −", "Sound +"};

    private final AudioEngine engine;
    private final MidiRouter router;
    private final SharedPreferences prefs;
    private MidiInput midi;
    private final Handler main = new Handler(Looper.getMainLooper());
    private float d;     // density
    private float sp;    // text scale

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint body = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
    private final Typeface light = Typeface.create("sans-serif-light", Typeface.NORMAL);
    private final Typeface mono = Typeface.MONOSPACE;
    private final RectF r = new RectF();
    private final Path curvePath = new Path();

    // ---- state ----
    private int preset;
    private float reverb;
    private boolean bankLoaded;
    private float latency = -1;
    private String route = "Starting audio…";
    private boolean classicBt;
    private List<String> devices = java.util.Collections.emptyList();
    private boolean sheetOpen;
    private float sheetScroll, sheetContentH;
    private static final int SCROLL_NONE = 0, SCROLL_TIPS = 1, SCROLL_NAMES = 2;
    private int scrollTo;
    private final String[] teachMsg = new String[MidiRouter.NB];
    private String candidate;          // an unmapped MPK button waiting for a decision
    private final RectF rBanner = new RectF(), rBanPrev = new RectF(), rBanNext = new RectF(), rBanIgnore = new RectF(),
            rBanStop = new RectF(), rAsk = new RectF(), rLearn = new RectF();
    private Runnable onLearn;
    private long noteFlashAt;
    private final int[] held = new int[16];
    private long soundFlashAt;
    private final RectF rKeysBar = new RectF(), rKeysDown = new RectF(), rKeysUp = new RectF();

    // ---- layout ----
    private final RectF rHeader = new RectF(), rGear = new RectF(), rStatus = new RectF(),
            rHint = new RectF(), rOct = new RectF(),
            rGrid = new RectF(), rReverb = new RectF(), rReverbTrack = new RectF(), rPiano = new RectF();
    private final RectF[] rTiles = new RectF[Synth.PRESET_COUNT];
    private final RectF rSheet = new RectF(), rSheetClose = new RectF(), rSheetBody = new RectF();
    private final RectF[] rVelPreset = rects(4), rSlider = rects(NSL), rSliderTrack = rects(NSL),
            rTeach = rects(MidiRouter.NB), rPads = rects(3), rNaming = rects(2);
    private final RectF rClear = new RectF(), rGraph = new RectF();
    private StaticLayout lKbdHelp, lNamesHelp, lPadsHelp, lTips, lCredits;
    private int textW;

    // ---- touch ----
    private int downTarget = T_NONE, pressed = T_NONE;
    private float downX, downY, downScroll;
    private boolean dragging, sliderActive;

    private static RectF[] rects(int n) {
        RectF[] a = new RectF[n];
        for (int i = 0; i < n; i++) a[i] = new RectF();
        return a;
    }

    StageView(Context ctx, AudioEngine engine, MidiRouter router, SharedPreferences prefs) {
        super(ctx);
        this.engine = engine;
        this.router = router;
        this.prefs = prefs;
        d = getResources().getDisplayMetrics().density;
        sp = d * Math.min(1.25f, getResources().getConfiguration().fontScale);
        for (int i = 0; i < rTiles.length; i++) rTiles[i] = new RectF();
        stroke.setStyle(Paint.Style.STROKE);
        preset = prefs.getInt("preset", Synth.P_PIANO);
        reverb = prefs.getFloat("reverb", 0.3f);
        setHapticFeedbackEnabled(true);
        setFocusable(true);
        router.setUi(this);
    }

    void setMidi(MidiInput m) { midi = m; }

    void setOnLearn(Runnable r) { onLearn = r; }

    boolean keyboardConnected() { return !devices.isEmpty(); }

    @Override
    protected void onConfigurationChanged(android.content.res.Configuration cfg) {
        super.onConfigurationChanged(cfg);
        d = getResources().getDisplayMetrics().density;
        sp = d * Math.min(1.25f, cfg.fontScale);
        if (getWidth() > 0) doLayout(getWidth(), getHeight());
        invalidate();
    }

    int preset() { return preset; }
    float reverb() { return reverb; }

    void setBankLoaded() {
        bankLoaded = true;
        invalidate();
    }

    // ------------------------------------------------------------------
    // Status polling (main thread)
    // ------------------------------------------------------------------
    private final Runnable poll = this::pollStatus;

    private void pollStatus() {
        float l = engine.latencyMs();
        if (l > 0) latency = latency < 0 ? l : latency * 0.7f + l * 0.3f;
        route = engine.routeLabel();
        classicBt = engine.isClassicBluetooth();
        invalidate();
        main.postDelayed(poll, 500);
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        main.removeCallbacks(poll);
        if (visibility == VISIBLE) main.post(poll);
    }

    @Override
    protected void onDetachedFromWindow() {
        main.removeCallbacks(poll);
        super.onDetachedFromWindow();
    }

    void onDevicesChanged() {
        devices = midi != null ? midi.deviceNames() : java.util.Collections.emptyList();
        invalidate();
    }

    void onRouteChanged() {
        latency = -1;
        route = engine.routeLabel();
        classicBt = engine.isClassicBluetooth();
        invalidate();
    }

    // ---- MidiRouter.Ui (may be called from the MIDI thread) ----
    @Override
    public void onKeysChanged() { postInvalidateOnAnimation(); }

    @Override
    public void onLearned(int which) {
        main.post(() -> {
            // a newer teach may have started meanwhile: keep its timeout
            if (router.learning() == MidiRouter.LEARN_NONE) main.removeCallbacks(learnTimeout);
            if (which >= 0 && which < teachMsg.length) teachMsg[which] = null;
            performHapticFeedback(android.os.Build.VERSION.SDK_INT >= 30
                    ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.VIRTUAL_KEY);
            invalidate();
        });
    }

    @Override
    public void onPresetFromKeyboard(int p) {
        main.post(() -> selectPreset(p));
    }

    @Override
    public void onPresetStep(int dir) {
        main.post(() -> selectPreset(Math.floorMod(preset + dir, Synth.PRESET_COUNT)));
    }

    @Override
    public void onMonitor() {
        if (sheetOpen) postInvalidateOnAnimation();
    }

    @Override
    public void onControlCandidate(String description) {
        main.post(() -> {
            candidate = description;
            main.removeCallbacks(candidateTimeout);
            if (description != null) main.postDelayed(candidateTimeout, 20000);
            invalidate();
        });
    }

    private final Runnable candidateTimeout = this::onCandidateTimeout;

    private void onCandidateTimeout() {
        router.dismissCandidate();
        candidate = null;
        invalidate();
    }

    private final Runnable learnTimeout = this::onLearnTimeout;

    private void onLearnTimeout() {
        int l = router.learning();
        if (l == MidiRouter.LEARN_NONE || !router.cancelLearnIf(l)) return;
        teachMsg[l] = "Nothing arrived from that button. Switch sounds with the pads instead (Drum pads → Pick sound below).";
        invalidate();
    }

    private void selectPreset(int p) {
        if (p == preset) return;
        preset = p;
        soundFlashAt = System.currentTimeMillis();
        engine.setPreset(p);
        prefs.edit().putInt("preset", p).apply();
        invalidate();
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------
    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        doLayout(w, h);
    }

    private Shader bgShader, keyShadow;

    private void doLayout(int w, int h) {
        bgShader = null;
        keyShadow = null;
        float pad = 16 * d, gap = 10 * d;
        boolean land = w > h * 1.15f;
        if (!land) {
            rHeader.set(pad, 8 * d, w - pad, 52 * d);
            rGear.set(rHeader.right - 44 * d, rHeader.top, rHeader.right, rHeader.bottom);
            float y = rHeader.bottom + 6 * d;
            rStatus.set(pad, y, w - pad, y + 72 * d);
            y = rStatus.bottom;
            rHint.set(pad, y, w - pad, y + (classicBt ? 40 * d : 0));
            y = rHint.bottom + gap;
            rOct.set(pad, y, w - pad, y + 96 * d);
            y = rOct.bottom + gap;
            float gridTop = y, reverbH = 52 * d;
            float gridH = 3 * 80 * d + 2 * 8 * d;
            float topEnd = gridTop + gridH + gap + reverbH + gap;
            // the MPK's 25 keys, about as long for their width as its mini keys, plus the octave bar
            float pianoH = Math.max(150 * d, Math.min(Math.min(h * 0.3f, h - topEnd), (w / 15f) * 5 + 40 * d));
            gridH += (h - pianoH) - topEnd;           // absorb leftover (or shrink if short)
            gridH = Math.max(gridH, 3 * 48 * d + 16 * d);
            rGrid.set(pad, gridTop, w - pad, gridTop + gridH);
            rReverb.set(pad, rGrid.bottom + gap, w - pad, rGrid.bottom + gap + reverbH);
            rPiano.set(0, h - pianoH, w, h);
        } else {
            rHeader.setEmpty();
            float pianoH = Math.max(100 * d, Math.min(150 * d, h * 0.34f));
            rPiano.set(0, h - pianoH, w, h);
            float top = 10 * d, bottom = rPiano.top - gap;
            float colW = (w - 2 * pad - gap) * 0.42f;
            float lx = pad, rx = pad + colW + gap;
            rStatus.set(lx, top, lx + colW, top + 62 * d);
            rGear.set(rStatus.right - 46 * d, rStatus.top + 2 * d, rStatus.right - 2 * d, rStatus.top + 40 * d);
            rHint.set(lx, rStatus.bottom, lx + colW, rStatus.bottom); // landscape: hint lives in the card
            rReverb.set(lx, bottom - 48 * d, lx + colW, bottom);
            rOct.set(lx, rHint.bottom + gap, lx + colW, rReverb.top - gap);
            rGrid.set(rx, top, w - pad, bottom);
        }

        float tg = 8 * d;
        float tw = (rGrid.width() - 2 * tg) / 3f, th = (rGrid.height() - 2 * tg) / 3f;
        for (int i = 0; i < rTiles.length; i++) {
            float tx = rGrid.left + (i % 3) * (tw + tg), ty = rGrid.top + (i / 3) * (th + tg);
            rTiles[i].set(tx, ty, tx + tw, ty + th);
        }
        rReverbTrack.set(rReverb.left + 16 * d, rReverb.bottom - 18 * d, rReverb.right - 16 * d, rReverb.bottom - 14 * d);

        // settings sheet
        float sw = Math.min(w - 2 * pad, 560 * d);
        float top = land ? 12 * d : Math.max(40 * d, h * 0.06f);
        rSheet.set((w - sw) / 2, top, (w + sw) / 2, h - (land ? 12 * d : 16 * d));
        rSheetClose.set(rSheet.right - 52 * d, rSheet.top + 6 * d, rSheet.right - 6 * d, rSheet.top + 52 * d);
        rSheetBody.set(rSheet.left, rSheet.top + 58 * d, rSheet.right, rSheet.bottom - 8 * d);
        textW = (int) (sw - 40 * d);
        body.setTypeface(regular);
        body.setTextSize(13.5f * sp);
        body.setColor(TEXT2);
        lKbdHelp = layout("Press the −/+ buttons next to the MPK's screen (or any button) and SS Studio asks "
                + "what it should do: previous or next sound. You can also teach them here.", textW);
        lNamesHelp = layout("SS Studio shows your MPK's 25 keys and plays exactly the notes it sends. "
                + "The MPK sends nothing when you press OCT, so the keys on screen move an octave as soon as you "
                + "play a note outside them. If they ever don't line up, use ‹ › above the keys. Pressing both "
                + "OCT buttons on the MPK resets it to OCT 0.", textW);
        lPadsHelp = layout("Pick sound: pads 1–8 choose the first eight sounds, bank B pad 1 the ninth. "
                + "Program Change (SHIFT + PAD 2 on the MPK) also picks sounds.", textW);
        lTips = layout("1.  In the Sony Sound Connect app, turn on LE Audio (connection settings).\n"
                + "2.  On the Pixel, open the XM6 in Bluetooth settings and turn on LE audio.\n"
                + "3.  Watch the delay on the main screen — it updates live. Classic Bluetooth is usually 150 ms+, LE Audio far less.\n"
                + "Wired is always fastest: a USB-C hub with a headphone jack lets you plug in both the MPK and the XM6 cable.", textW);
        lCredits = layout("SS Studio 1.7.1. Grand Piano samples: FluidR3 GM by Frank Wen, CC BY 3.0. "
                + "All other sounds are synthesized live on the phone.", textW);
    }

    private StaticLayout layout(String s, int width) {
        return StaticLayout.Builder.obtain(s, 0, s.length(), body, Math.max(10, width))
                .setLineSpacing(3 * d, 1f).setAlignment(Layout.Alignment.ALIGN_NORMAL).build();
    }

    // ------------------------------------------------------------------
    // Drawing: main screen
    // ------------------------------------------------------------------
    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        boolean hintVisible = classicBt && !rHeader.isEmpty();
        if ((rHint.height() > 0) != hintVisible) doLayout(w, h);

        if (bgShader == null) bgShader = new LinearGradient(0, 0, 0, h, BG_TOP, BG_BOTTOM, Shader.TileMode.CLAMP);
        fill.setShader(bgShader);
        c.drawRect(0, 0, w, h, fill);
        fill.setShader(null);

        drawStatus(c);
        drawHeader(c);
        drawNotePanel(c);
        drawGrid(c);
        drawReverb(c);
        drawPiano(c);
        if (candidate != null && !sheetOpen) drawBanner(c);
        if (sheetOpen) drawSheet(c);
    }

    /** "Button pressed on your MPK — what should it do?" */
    private void drawBanner(Canvas c) {
        int w = getWidth();
        float bw = Math.min(w - 24 * d, 520 * d), bh = 128 * d;
        float bottom = rPiano.top - 8 * d;
        rBanner.set((w - bw) / 2, bottom - bh, (w + bw) / 2, bottom);
        fill.setColor(0xF0222429);
        c.drawRoundRect(rBanner, 18 * d, 18 * d, fill);
        stroke.setColor(ACCENT);
        stroke.setStrokeWidth(1.5f * d);
        c.drawRoundRect(rBanner, 18 * d, 18 * d, stroke);
        float x = rBanner.left + 16 * d, right = rBanner.right - 16 * d;
        setText(12.5f, pressed == T_BAN_STOP ? TEXT : TEXT2, medium);
        text.setTextAlign(Paint.Align.RIGHT);
        float stopW = text.measureText("Stop asking");
        c.drawText("Stop asking", right, rBanner.top + 26 * d, text);
        rBanStop.set(right - stopW - 12 * d, rBanner.top, rBanner.right, rBanner.top + 44 * d);
        setText(14.5f, TEXT, medium);
        drawTextFit(c, "Button pressed on your MPK", x, rBanner.top + 26 * d, right - x - stopW - 12 * d);
        setText(11.5f, TEXT3, mono);
        drawTextFit(c, candidate, x, rBanner.top + 46 * d, right - x);
        float by = rBanner.bottom - 12 * d - 46 * d, gap = 8 * d;
        float ignoreW = Math.min(88 * d, (right - x) * 0.24f);
        float half = (right - x - ignoreW - 2 * gap) / 2f;
        rBanPrev.set(x, by, x + half, by + 46 * d);
        rBanNext.set(x + half + gap, by, x + 2 * half + gap, by + 46 * d);
        rBanIgnore.set(right - ignoreW, by, right, by + 46 * d);
        bannerButton(c, rBanPrev, "‹ Previous sound", true, pressed == T_BAN_PREV);
        bannerButton(c, rBanNext, "Next sound ›", true, pressed == T_BAN_NEXT);
        bannerButton(c, rBanIgnore, "Ignore", false, pressed == T_BAN_IGNORE);
    }

    private void bannerButton(Canvas c, RectF b, String label, boolean primary, boolean down) {
        fill.setColor(primary ? (down ? 0xFFFFB46B : ACCENT) : (down ? PANEL_HI : PANEL));
        c.drawRoundRect(b, 12 * d, 12 * d, fill);
        setText(13.5f, primary ? ON_ACCENT : TEXT2, medium);
        text.setTextAlign(Paint.Align.CENTER);
        CharSequence t = TextUtils.ellipsize(label, text, b.width() - 10 * d, TextUtils.TruncateAt.END);
        c.drawText(t, 0, t.length(), b.centerX(), b.centerY() + 5 * d, text);
    }

    private void setText(float sizeSp, int color, Typeface tf) {
        text.setTextSize(sizeSp * sp);
        text.setColor(color);
        text.setTypeface(tf);
        text.setTextAlign(Paint.Align.LEFT);
        text.setLetterSpacing(0f);
    }

    private void drawTextFit(Canvas c, String s, float x, float y, float maxW) {
        CharSequence t = TextUtils.ellipsize(s, text, Math.max(0, maxW), TextUtils.TruncateAt.END);
        c.drawText(t, 0, t.length(), x, y, text);
    }

    private void drawHeader(Canvas c) {
        if (!rHeader.isEmpty()) {
            setText(20, TEXT, medium);
            c.drawText("SS Studio", rHeader.left, rHeader.centerY() + 7 * d, text);
        }
        // Learn mode button
        setText(13.5f, ON_ACCENT, medium);
        float lw = text.measureText("Learn") + 40 * d;
        float lh = Math.min(36 * d, rGear.height() - 4 * d);
        rLearn.set(rGear.left - 6 * d - lw, rGear.centerY() - lh / 2, rGear.left - 6 * d, rGear.centerY() + lh / 2);
        fill.setColor(pressed == T_LEARN ? 0xFFFFB46B : ACCENT);
        c.drawRoundRect(rLearn, lh / 2, lh / 2, fill);
        curvePath.reset();
        float px = rLearn.left + 14 * d, py = rLearn.centerY();
        curvePath.moveTo(px, py - 5.5f * d);
        curvePath.lineTo(px + 8 * d, py);
        curvePath.lineTo(px, py + 5.5f * d);
        curvePath.close();
        fill.setColor(ON_ACCENT);
        c.drawPath(curvePath, fill);
        c.drawText("Learn", rLearn.left + 28 * d, py + 5 * d, text);
        float cx = rGear.centerX(), cy = rGear.centerY();
        stroke.setColor(pressed == T_GEAR ? ACCENT : TEXT2);
        stroke.setStrokeWidth(2 * d);
        c.drawCircle(cx, cy, 6.5f * d, stroke);
        stroke.setStrokeWidth(3 * d);
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            c.drawLine(cx + (float) Math.cos(a) * 8.5f * d, cy + (float) Math.sin(a) * 8.5f * d,
                    cx + (float) Math.cos(a) * 11f * d, cy + (float) Math.sin(a) * 11f * d, stroke);
        }
    }

    private void panel(Canvas c, RectF rect, int color, float radius) {
        fill.setColor(color);
        c.drawRoundRect(rect, radius, radius, fill);
        stroke.setColor(STROKE);
        stroke.setStrokeWidth(1 * d);
        c.drawRoundRect(rect, radius, radius, stroke);
    }

    private void drawStatus(Canvas c) {
        r.set(rStatus.left, rStatus.top, rStatus.right, rHint.bottom);
        panel(c, r, PANEL, 16 * d);
        float x = rStatus.left + 16 * d;
        float row1 = rStatus.top + rStatus.height() * 0.40f;
        float row2 = rStatus.top + rStatus.height() * 0.78f;
        boolean connected = !devices.isEmpty();
        boolean active = System.nanoTime() - router.midiActivity < 120_000_000L;
        fill.setColor(connected ? (active ? ACCENT : GOOD) : TEXT3);
        c.drawCircle(x + 4 * d, row1 - 5 * d, 4.5f * d, fill);
        if (active) postInvalidateDelayed(130);
        float tx = x + 18 * d;
        float maxW = (rHeader.isEmpty() ? rGear.left - 4 * d - 100 * d : rStatus.right - 16 * d) - tx;
        setText(15, TEXT, medium);
        if (connected) {
            drawTextFit(c, devices.get(0) + (devices.size() > 1 ? "  +" + (devices.size() - 1) : ""), tx, row1, maxW);
        } else {
            drawTextFit(c, midi != null && midi.available() ? "Plug in your keyboard (USB-C)" : "MIDI isn't available", tx, row1, maxW);
        }
        String chip;
        int chipColor;
        if (latency > 0) {
            chip = "≈ " + Math.round(latency) + " ms";
            chipColor = latency <= 30 ? GOOD : (latency <= 75 ? WARN : BAD);
        } else {
            chip = "— ms";
            chipColor = TEXT3;
        }
        setText(12.5f, chipColor, medium);
        float cw = text.measureText(chip) + 18 * d;
        float chipR = rStatus.right - 12 * d;
        r.set(chipR - cw, row2 - 15 * d, chipR, row2 + 6 * d);
        fill.setColor((chipColor & 0x00FFFFFF) | 0x26000000);
        c.drawRoundRect(r, 10 * d, 10 * d, fill);
        text.setTextAlign(Paint.Align.CENTER);
        c.drawText(chip, r.centerX(), row2 + 0.5f * d, text);
        float chipLeft = r.left;
        boolean inlineHint = classicBt && rHeader.isEmpty();
        setText(13, inlineHint ? (pressed == T_HINT ? TEXT : WARN) : TEXT2, regular);
        drawTextFit(c, inlineHint ? "Classic Bluetooth · tap for LE Audio tips" : route, tx, row2, chipLeft - tx - 8 * d);
        if (rHint.height() > 0) {
            setText(12.5f, pressed == T_HINT ? TEXT : WARN, regular);
            drawTextFit(c, "Classic Bluetooth adds delay — tap for LE Audio tips", tx, rHint.top + rHint.height() * 0.55f, rHint.width() - 34 * d);
        }
    }

    /** What you're playing, exactly as the MPK sent it (like Ableton's incoming-note display). */
    private void drawNotePanel(Canvas c) {
        panel(c, rOct, pressed == T_OCT_PANEL ? PANEL_HI : PANEL, 18 * d);
        float cx = rOct.centerX();
        boolean compact = rOct.height() < 88 * d;
        int nHeld = router.heldNotes(held);
        int last = router.lastNote;
        setText(11, TEXT3, medium);
        text.setLetterSpacing(0.18f);
        text.setTextAlign(Paint.Align.CENTER);
        c.drawText(nHeld > 0 ? "PLAYING" : "LAST NOTE", cx, rOct.top + (compact ? 16 : 20) * d, text);
        String big;
        if (nHeld > 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < nHeld && i < 6; i++) {
                if (i > 0) sb.append("  ");
                sb.append(MidiRouter.noteName(held[i]));
            }
            if (nHeld > 6) sb.append("  +").append(nHeld - 6);
            big = sb.toString();
        } else {
            big = last >= 0 ? MidiRouter.noteName(last) : "—";
        }
        float maxW = rOct.width() - 64 * d;
        float size = compact ? 30 : 36;
        setText(size, nHeld > 0 ? TEXT : TEXT2, light);
        while (size > 16 && text.measureText(big) > maxW) {
            size -= 1;
            text.setTextSize(size * sp);
        }
        text.setTextAlign(Paint.Align.CENTER);
        CharSequence bt = TextUtils.ellipsize(big, text, Math.max(0, maxW), TextUtils.TruncateAt.END);
        c.drawText(bt, 0, bt.length(), cx, rOct.centerY() + (compact ? 12 : 13) * d, text);
        // chevron: the panel opens the note-name settings
        stroke.setColor(TEXT3);
        stroke.setStrokeWidth(2 * d);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        float chx = rOct.right - 22 * d, chy = rOct.centerY();
        c.drawLine(chx - 4 * d, chy - 7 * d, chx + 3 * d, chy, stroke);
        c.drawLine(chx + 3 * d, chy, chx - 4 * d, chy + 7 * d, stroke);
        stroke.setStrokeCap(Paint.Cap.BUTT);
        if (compact && rOct.height() < 72 * d) return;
        String sub;
        if (last >= 0) {
            sub = "Note " + last + "  ·  velocity " + router.lastRawVel
                    + (router.lastOutVel != router.lastRawVel ? " → " + router.lastOutVel : "");
        } else {
            sub = "Plays exactly the notes the MPK sends";
        }
        setText(12, TEXT2, regular);
        text.setTextAlign(Paint.Align.CENTER);
        CharSequence t = TextUtils.ellipsize(sub, text, Math.max(0, maxW), TextUtils.TruncateAt.END);
        c.drawText(t, 0, t.length(), cx, rOct.bottom - 12 * d, text);
    }

    private void roundButton(Canvas c, RectF b, boolean plus, boolean down, boolean enabled) {
        fill.setColor(down ? ACCENT : PANEL_HI);
        c.drawOval(b, fill);
        stroke.setColor(down ? ACCENT : STROKE);
        stroke.setStrokeWidth(1 * d);
        c.drawOval(b, stroke);
        stroke.setColor(down ? ON_ACCENT : (enabled ? TEXT : TEXT3));
        stroke.setStrokeWidth(2.6f * d);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        float cx = b.centerX(), cy = b.centerY(), a = b.width() * 0.17f;
        c.drawLine(cx - a, cy, cx + a, cy, stroke);
        if (plus) c.drawLine(cx, cy - a, cx, cy + a, stroke);
        stroke.setStrokeCap(Paint.Cap.BUTT);
    }

    private void drawGrid(Canvas c) {
        long since = System.currentTimeMillis() - soundFlashAt;
        float flash = since < 300 ? 1f - since / 300f : 0f;
        if (flash > 0) postInvalidateOnAnimation();
        for (int i = 0; i < rTiles.length; i++) {
            RectF t = rTiles[i];
            boolean sel = i == preset;
            fill.setColor(sel ? blend(0xFF2A2219, 0xFF4A3420, flash) : (pressed == T_TILE + i ? PANEL_HI : PANEL));
            c.drawRoundRect(t, 14 * d, 14 * d, fill);
            stroke.setColor(sel ? ACCENT : STROKE);
            stroke.setStrokeWidth((sel ? 2f : 1f) * d);
            c.drawRoundRect(t, 14 * d, 14 * d, stroke);
            float x = t.left + 12 * d, maxW = t.width() - 20 * d;
            boolean compact = t.height() < 58 * d;
            float size = compact ? 13 : 14.5f;
            setText(size, sel ? ACCENT : TEXT, medium);
            while (size > 11.5f && text.measureText(Synth.NAMES[i]) > maxW) {
                size -= 0.5f;
                text.setTextSize(size * sp);
            }
            float ny = compact ? t.centerY() + 5 * d : t.centerY() - 1 * d;
            drawTextFit(c, Synth.NAMES[i], x, ny, maxW);
            if (!compact) {
                String tag = i == Synth.P_PIANO && !bankLoaded ? "Loading…" : Synth.TAGS[i];
                setText(11.5f, sel ? 0xCCFF9F43 : TEXT3, regular);
                drawTextFit(c, tag, x, ny + 18 * d, maxW);
            }
        }
    }

    private void drawReverb(Canvas c) {
        panel(c, rReverb, PANEL, 16 * d);
        setText(13.5f, TEXT, medium);
        c.drawText("Reverb", rReverb.left + 16 * d, rReverb.top + 22 * d, text);
        setText(12.5f, TEXT2, regular);
        text.setTextAlign(Paint.Align.RIGHT);
        c.drawText(Math.round(reverb * 100) + "%", rReverb.right - 16 * d, rReverb.top + 22 * d, text);
        drawTrack(c, rReverbTrack, reverb, pressed == T_REVERB, true);
    }

    private void drawTrack(Canvas c, RectF tr, float v, boolean active, boolean enabled) {
        fill.setColor(STROKE);
        c.drawRoundRect(tr, 2 * d, 2 * d, fill);
        float x = tr.left + tr.width() * Math.max(0f, Math.min(1f, v));
        fill.setColor(enabled ? ACCENT : TEXT3);
        r.set(tr.left, tr.top, x, tr.bottom);
        c.drawRoundRect(r, 2 * d, 2 * d, fill);
        c.drawCircle(x, tr.centerY(), (active ? 10 : 8) * d, fill);
    }

    /**
     * The MPK mini's 25 keys (C to C) with exact key geometry (KeyboardLayout), showing
     * the notes exactly as received. Above them, the octave bar: the range the keys play
     * and ‹ › to line the screen up with the MPK's OCT setting by hand.
     */
    private void drawPiano(Canvas c) {
        int w = getWidth();
        fill.setColor(0xFF07080A);
        c.drawRect(rPiano, fill);
        int lo = router.keysLo(), hi = lo + KeyboardLayout.MPK_KEYS - 1;

        // octave bar
        float barH = (rHeader.isEmpty() ? 30 : 36) * d;
        rKeysBar.set(0, rPiano.top, w, rPiano.top + barH);
        float bw = 52 * d;
        rKeysDown.set(8 * d, rKeysBar.top + 3 * d, 8 * d + bw, rKeysBar.bottom - 3 * d);
        rKeysUp.set(w - 8 * d - bw, rKeysBar.top + 3 * d, w - 8 * d, rKeysBar.bottom - 3 * d);
        keysButton(c, rKeysDown, false, pressed == T_KEYS_DOWN, lo > KeyboardLayout.MPK_MIN_LO);
        keysButton(c, rKeysUp, true, pressed == T_KEYS_UP, lo < KeyboardLayout.MPK_MAX_LO);
        int oct = (lo - KeyboardLayout.MPK_DEFAULT_LO) / 12;
        String range = MidiRouter.noteName(lo) + " – " + MidiRouter.noteName(hi);
        String octS = "OCT " + (oct > 0 ? "+" + oct : String.valueOf(oct));
        setText(13, TEXT, medium);
        float rw = text.measureText(range);
        setText(12, TEXT3, medium);
        float gapW = 12 * d, ow = text.measureText(octS);
        float x0 = rKeysBar.centerX() - (rw + gapW + ow) / 2, by = rKeysBar.centerY() + 4.5f * d;
        setText(13, TEXT, medium);
        c.drawText(range, x0, by, text);
        setText(12, TEXT3, medium);
        c.drawText(octS, x0 + rw + gapW, by, text);

        // keys
        float whiteW = w / (float) KeyboardLayout.MPK_WHITES;
        float origin = KeyboardLayout.whiteLeft(lo);
        float keyTop = rKeysBar.bottom + 2 * d, keyH = rPiano.bottom - 2 * d - keyTop;
        c.save();
        c.clipRect(rPiano);
        for (int n = lo; n <= hi; n++) {                // white keys
            if (KeyboardLayout.isBlack(n)) continue;
            float x = (KeyboardLayout.whiteLeft(n) - origin) * whiteW;
            r.set(x + 1 * d, keyTop, x + whiteW - 1 * d, keyTop + keyH);
            int st = keyState(n);
            fill.setColor(st == 2 ? ACCENT : (st == 1 ? 0xFFF3D3B0 : 0xFFE9E9EA));
            c.drawRoundRect(r, 4 * d, 4 * d, fill);
            if (n % 12 == 0 || st == 2) {
                setText(st == 2 ? 11.5f : 10.5f, st == 2 ? 0xFF3A2208 : 0xFF6E7177, medium);
                text.setTextAlign(Paint.Align.CENTER);
                c.drawText(MidiRouter.noteName(n), x + whiteW / 2, keyTop + keyH - 8 * d, text);
            }
        }
        float kbw = KeyboardLayout.BLACK_WIDTH * whiteW, bh = keyH * KeyboardLayout.BLACK_HEIGHT;
        for (int n = lo; n <= hi; n++) {                // black keys on top
            if (!KeyboardLayout.isBlack(n)) continue;
            float xl = (KeyboardLayout.keyLeft(n) - origin) * whiteW;
            r.set(xl, keyTop, xl + kbw, keyTop + bh);
            int st = keyState(n);
            fill.setColor(st == 2 ? ACCENT : (st == 1 ? 0xFF8A5A2B : 0xFF16171A));
            c.drawRoundRect(r, 3 * d, 3 * d, fill);
            if (st == 2) {
                // pressed black key: its name just below it, on the white keys
                setText(11.5f, 0xFF3A2208, medium);
                text.setTextAlign(Paint.Align.CENTER);
                c.drawText(MidiRouter.noteName(n), xl + kbw / 2, keyTop + bh + 15 * d, text);
            }
        }
        if (keyShadow == null) keyShadow = new LinearGradient(0, keyTop, 0, keyTop + 10 * d, 0x66000000, 0x00000000, Shader.TileMode.CLAMP);
        fill.setShader(keyShadow);
        c.drawRect(0, keyTop, w, keyTop + 10 * d, fill);
        fill.setShader(null);
        c.restore();
    }

    /** ‹ or › on the octave bar. */
    private void keysButton(Canvas c, RectF b, boolean up, boolean down, boolean enabled) {
        fill.setColor(down ? ACCENT : PANEL);
        c.drawRoundRect(b, b.height() / 2, b.height() / 2, fill);
        stroke.setColor(down ? ON_ACCENT : (enabled ? TEXT : TEXT3));
        stroke.setStrokeWidth(2.2f * d);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        float cx = b.centerX() + (up ? 1.5f : -1.5f) * d, cy = b.centerY(), a = 5 * d;
        float dir = up ? 1 : -1;
        c.drawLine(cx - dir * a * 0.6f, cy - a, cx + dir * a * 0.6f, cy, stroke);
        c.drawLine(cx + dir * a * 0.6f, cy, cx - dir * a * 0.6f, cy + a, stroke);
        stroke.setStrokeCap(Paint.Cap.BUTT);
    }

    /** 0 up, 1 held by pedal, 2 key down */
    private int keyState(int note) {
        if (router.down[note] > 0) return 2;
        if (router.pedal[note]) return 1;
        return 0;
    }

    // ------------------------------------------------------------------
    // Drawing: settings sheet
    // ------------------------------------------------------------------
    private void drawSheet(Canvas c) {
        fill.setColor(0xB3000000);
        c.drawRect(0, 0, getWidth(), getHeight(), fill);
        fill.setColor(0xFF17191D);
        c.drawRoundRect(rSheet, 22 * d, 22 * d, fill);
        stroke.setColor(STROKE);
        stroke.setStrokeWidth(1 * d);
        c.drawRoundRect(rSheet, 22 * d, 22 * d, stroke);
        setText(19, TEXT, medium);
        c.drawText("Settings", rSheet.left + 20 * d, rSheet.top + 36 * d, text);
        stroke.setColor(pressed == T_SHEET_CLOSE ? ACCENT : TEXT2);
        stroke.setStrokeWidth(2.2f * d);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        float cx = rSheetClose.centerX(), cy = rSheetClose.centerY(), a = 7 * d;
        c.drawLine(cx - a, cy - a, cx + a, cy + a, stroke);
        c.drawLine(cx - a, cy + a, cx + a, cy - a, stroke);
        stroke.setStrokeCap(Paint.Cap.BUTT);

        c.save();
        c.clipRect(rSheetBody);
        float x = rSheet.left + 20 * d, right = rSheet.right - 20 * d;
        float y = rSheetBody.top - sheetScroll;
        float startY = y;

        y = drawVelocitySection(c, x, right, y);
        y = drawButtonsSection(c, x, right, y);
        float namesY = y - startY;
        y = drawNamesSection(c, x, right, y);
        y = drawPadsSection(c, x, right, y);
        y = drawMonitorSection(c, x, right, y);

        float tipsY = y - startY;
        y = section(c, "LOWEST DELAY WITH SONY XM6", x, y);
        y = drawLayout(c, lTips, x, y) + 22 * d;
        y = section(c, "ABOUT", x, y);
        y = drawLayout(c, lCredits, x, y) + 16 * d;

        sheetContentH = y - startY;
        c.restore();
        float max = Math.max(0, sheetContentH - rSheetBody.height());
        if (scrollTo != SCROLL_NONE) {
            sheetScroll = Math.min(scrollTo == SCROLL_TIPS ? tipsY : namesY, max);
            scrollTo = SCROLL_NONE;
            invalidate();
        } else if (sheetScroll > max) {
            sheetScroll = max;
            invalidate();
        }
        if (sheetContentH > rSheetBody.height()) {
            fill.setShader(new LinearGradient(0, rSheetBody.bottom - 24 * d, 0, rSheetBody.bottom, 0x0017191D, 0xFF17191D, Shader.TileMode.CLAMP));
            c.drawRect(rSheetBody.left, rSheetBody.bottom - 24 * d, rSheetBody.right, rSheetBody.bottom, fill);
            fill.setShader(null);
        }
    }

    private float drawLayout(Canvas c, StaticLayout l, float x, float y) {
        c.save();
        c.translate(x, y);
        l.draw(c);
        c.restore();
        return y + l.getHeight();
    }

    private float section(Canvas c, String title, float x, float y) {
        setText(11, TEXT3, medium);
        text.setLetterSpacing(0.14f);
        c.drawText(title, x, y + 14 * d, text);
        text.setLetterSpacing(0f);
        return y + 26 * d;
    }

    private float segmented(Canvas c, String[] labels, RectF[] out, int selected, int targetBase, float x, float right, float y) {
        float h = 46 * d, segW = (right - x) / labels.length;
        r.set(x, y, right, y + h);
        fill.setColor(PANEL);
        c.drawRoundRect(r, 14 * d, 14 * d, fill);
        for (int i = 0; i < labels.length; i++) {
            out[i].set(x + i * segW, y, x + (i + 1) * segW, y + h);
            boolean sel = i == selected;
            if (sel || pressed == targetBase + i) {
                r.set(out[i].left + 3 * d, y + 3 * d, out[i].right - 3 * d, y + h - 3 * d);
                fill.setColor(sel ? ACCENT : PANEL_HI);
                c.drawRoundRect(r, 11 * d, 11 * d, fill);
            }
            setText(13.5f, sel ? ON_ACCENT : TEXT2, medium);
            text.setTextAlign(Paint.Align.CENTER);
            CharSequence t = TextUtils.ellipsize(labels[i], text, segW - 10 * d, TextUtils.TruncateAt.END);
            c.drawText(t, 0, t.length(), out[i].centerX(), y + h / 2 + 5 * d, text);
        }
        return y + h;
    }

    // ---- velocity ----
    private float drawVelocitySection(Canvas c, float x, float right, float y) {
        VelocityCurve v = router.velocity();
        int match = v.matchingPreset();
        y = section(c, match == VelocityCurve.P_CUSTOM ? "KEY VELOCITY · CUSTOM" : "KEY VELOCITY", x, y);

        // graph: horizontal = how hard you hit (raw velocity), vertical = velocity that plays
        float gh = Math.min(160 * d, (right - x) * 0.45f);
        rGraph.set(x, y, right, y + gh);
        fill.setColor(PANEL);
        c.drawRoundRect(rGraph, 12 * d, 12 * d, fill);
        float gl = rGraph.left + 12 * d, gr = rGraph.right - 12 * d, gt = rGraph.top + 12 * d, gb = rGraph.bottom - 12 * d;
        stroke.setColor(STROKE);
        stroke.setStrokeWidth(1 * d);
        for (int i = 1; i < 4; i++) {
            float gx = gl + (gr - gl) * i / 4f, gy = gt + (gb - gt) * i / 4f;
            c.drawLine(gx, gt, gx, gb, stroke);
            c.drawLine(gl, gy, gr, gy, stroke);
        }
        stroke.setColor(0xFF3A3F49);
        c.drawLine(gl, gb, gr, gt, stroke); // linear reference
        curvePath.reset();
        for (int raw = 1; raw <= 127; raw++) {
            float px = gl + (gr - gl) * (raw - 1) / 126f;
            float py = gb - (gb - gt) * (v.map(raw) - 1) / 126f;
            if (raw == 1) curvePath.moveTo(px, py); else curvePath.lineTo(px, py);
        }
        stroke.setColor(ACCENT);
        stroke.setStrokeWidth(2.5f * d);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        c.drawPath(curvePath, stroke);
        int raw = router.lastRawVel, out = router.lastOutVel;
        setText(12, TEXT2, regular);
        if (raw > 0) {
            float px = gl + (gr - gl) * (raw - 1) / 126f, py = gb - (gb - gt) * (out - 1) / 126f;
            stroke.setColor(0x80FFFFFF);
            stroke.setStrokeWidth(1 * d);
            c.drawLine(px, gb, px, py, stroke);
            fill.setColor(TEXT);
            c.drawCircle(px, py, 4.5f * d, fill);
            c.drawText("Last key: " + raw + " → " + out, gl + 4 * d, gt + 14 * d, text);
        } else {
            c.drawText("Play a key to see it on the curve", gl + 4 * d, gt + 14 * d, text);
        }
        y = rGraph.bottom + 6 * d;
        setText(11.5f, TEXT3, regular);
        c.drawText("→ how hard you play     ↑ how loud it sounds", x, y + 12 * d, text);
        y += 24 * d;

        y = segmented(c, new String[]{"Light", "Normal", "Heavy", "Fixed"}, rVelPreset, match, T_VEL_PRESET, x, right, y);
        y += 10 * d;
        for (int i = 0; i < NSL; i++) y = drawSlider(c, i, v, x, right, y);
        return y + 18 * d;
    }

    private float sliderValue(int i, VelocityCurve v) {
        switch (i) {
            case SL_CURVE: return (v.curve + 1f) / 2f;
            case SL_FULL: return (v.fullForceAt - 20) / 107f;
            case SL_SOFT: return (v.softest - 1) / 126f;
            case SL_LOUD: return (v.loudest - 1) / 126f;
            case SL_DYN: return (router.dynamicRange() - 6) / 42f;
            default: return (v.fixedValue - 1) / 126f;
        }
    }

    private String sliderText(int i, VelocityCurve v) {
        switch (i) {
            case SL_CURVE:
                if (Math.abs(v.curve) < 0.02f) return "Linear";
                return (v.curve < 0 ? "Lighter " : "Heavier ") + Math.round(Math.abs(v.curve) * 100) + "%";
            case SL_FULL: return v.fullForceAt == 127 ? "Hardest hit" : "Velocity " + v.fullForceAt;
            case SL_SOFT: return String.valueOf(v.softest);
            case SL_LOUD: return String.valueOf(v.loudest);
            case SL_DYN: return router.dynamicRange() + " dB";
            default: return v.fixed ? String.valueOf(v.fixedValue) : v.fixedValue + " (when Fixed)";
        }
    }

    private void setSlider(int i, float t) {
        t = Math.max(0f, Math.min(1f, t));
        VelocityCurve v = router.velocity();
        switch (i) {
            case SL_CURVE:
                float cv = t * 2f - 1f;
                if (Math.abs(cv) < 0.04f) cv = 0f; // snap to linear
                router.setVelocity(v.withCurve(cv));
                break;
            case SL_FULL: router.setVelocity(v.withFullForceAt(20 + Math.round(t * 107))); break;
            case SL_SOFT: router.setVelocity(v.withSoftest(1 + Math.round(t * 126))); break;
            case SL_LOUD: router.setVelocity(v.withLoudest(1 + Math.round(t * 126))); break;
            case SL_DYN: router.setDynamicRange(6 + Math.round(t * 42)); break;
            default: router.setVelocity(v.withFixedValue(1 + Math.round(t * 126))); break;
        }
        invalidate();
    }

    private float drawSlider(Canvas c, int i, VelocityCurve v, float x, float right, float y) {
        float h = 54 * d;
        rSlider[i].set(x - 8 * d, y, right + 8 * d, y + h);
        boolean enabled = (i == SL_FIXED) == v.fixed || i == SL_DYN;
        setText(13.5f, enabled ? TEXT : TEXT3, medium);
        c.drawText(SL_LABEL[i], x, y + 20 * d, text);
        setText(12.5f, enabled ? TEXT2 : TEXT3, regular);
        text.setTextAlign(Paint.Align.RIGHT);
        c.drawText(sliderText(i, v), right, y + 20 * d, text);
        rSliderTrack[i].set(x + 8 * d, y + 36 * d, right - 8 * d, y + 40 * d);
        drawTrack(c, rSliderTrack[i], sliderValue(i, v), pressed == T_SLIDER + i, enabled);
        return y + h;
    }

    // ---- keyboard buttons ----
    private float drawButtonsSection(Canvas c, float x, float right, float y) {
        y = section(c, "KEYBOARD BUTTONS", x, y);
        y = drawLayout(c, lKbdHelp, x, y) + 8 * d;
        rAsk.set(x, y, right, y + 48 * d);
        setText(14.5f, TEXT, regular);
        drawTextFit(c, "Ask what new buttons do", x, y + 29 * d, right - x - 60 * d);
        drawSwitch(c, right, y + 24 * d, router.askButtons());
        y += 48 * d + 10 * d;
        float bw = (right - x - 10 * d) / 2f, bh = 64 * d;
        for (int i = 0; i < MidiRouter.NB; i++) {
            float bx = x + (i % 2) * (bw + 10 * d), by = y + (i / 2) * (bh + 10 * d);
            rTeach[i].set(bx, by, bx + bw, by + bh);
            teachButton(c, rTeach[i], i);
        }
        y += ((MidiRouter.NB + 1) / 2) * (bh + 10 * d) - 2 * d;
        for (int i = 0; i < MidiRouter.NB; i++) {
            if (teachMsg[i] == null) continue;
            body.setColor(WARN);
            StaticLayout l = layout(TEACH_LABEL[i] + ": " + teachMsg[i], textW);
            y = drawLayout(c, l, x, y) + 6 * d;
            body.setColor(TEXT2);
        }
        if (router.anyBinding()) {
            rClear.set(x, y, x + 180 * d, y + 48 * d);
            setText(13.5f, pressed == T_CLEAR ? TEXT : ACCENT, medium);
            c.drawText("Clear mappings", x, y + 29 * d, text);
            y += 48 * d;
        } else rClear.setEmpty();
        return y + 18 * d;
    }

    private void teachButton(Canvas c, RectF b, int which) {
        boolean learning = router.learning() == which;
        fill.setColor(learning ? 0xFF2A2219 : (pressed == T_TEACH + which ? PANEL_HI : PANEL));
        c.drawRoundRect(b, 14 * d, 14 * d, fill);
        stroke.setColor(learning ? ACCENT : STROKE);
        stroke.setStrokeWidth((learning ? 2 : 1) * d);
        c.drawRoundRect(b, 14 * d, 14 * d, stroke);
        setText(14, learning ? ACCENT : TEXT, medium);
        drawTextFit(c, "Teach " + TEACH_LABEL[which], b.left + 12 * d, b.top + 26 * d, b.width() - 24 * d);
        String sub;
        int col = TEXT3;
        if (learning) {
            sub = "Press it on the MPK…";
            col = ACCENT;
        } else {
            String bl = router.bindingLabel(which);
            if (bl != null) { sub = bl; col = GOOD; } else sub = "Not mapped";
        }
        setText(12, col, regular);
        drawTextFit(c, sub, b.left + 12 * d, b.top + 47 * d, b.width() - 24 * d);
    }

    // ---- note names ----
    private float drawNamesSection(Canvas c, float x, float right, float y) {
        y = section(c, "NOTE NAMES", x, y);
        y = drawLayout(c, lNamesHelp, x, y) + 12 * d;
        setText(14.5f, TEXT, regular);
        c.drawText("Middle C (note 60) is called", x, y + 14 * d, text);
        y += 24 * d;
        y = segmented(c, new String[]{"C3  (Ableton, Akai)", "C4  (scientific)"}, rNaming,
                router.middleCIsC3() ? 0 : 1, T_NAMING, x, right, y);
        return y + 22 * d;
    }

    // ---- pads ----
    private float drawPadsSection(Canvas c, float x, float right, float y) {
        y = section(c, "DRUM PADS", x, y);
        y = segmented(c, new String[]{"Ignore", "Play notes", "Pick sound"}, rPads, router.padMode(), T_PADS, x, right, y);
        y += 10 * d;
        y = drawLayout(c, lPadsHelp, x, y);
        return y + 22 * d;
    }

    // ---- MIDI monitor ----
    private float drawMonitorSection(Canvas c, float x, float right, float y) {
        y = section(c, "MIDI MONITOR", x, y);
        String[] lines = router.monitorLines();
        float lh = 20 * d, h = 16 * d + lh * lines.length;
        r.set(x, y, right, y + h);
        fill.setColor(0xFF101114);
        c.drawRoundRect(r, 12 * d, 12 * d, fill);
        float ty = y + 8 * d + 14 * d;
        boolean empty = lines[0] == null;
        if (empty) {
            setText(12.5f, TEXT3, regular);
            c.drawText("Press any key, pad or button on the MPK", x + 12 * d, ty, text);
        } else {
            for (int i = 0; i < lines.length; i++) {
                if (lines[i] == null) break;
                setText(11.5f, i == 0 ? TEXT : TEXT2, mono);
                drawTextFit(c, lines[i], x + 12 * d, ty + i * lh, right - x - 24 * d);
            }
        }
        return y + h + 22 * d;
    }

    private void drawSwitch(Canvas c, float right, float cy, boolean on) {
        r.set(right - 46 * d, cy - 13 * d, right, cy + 13 * d);
        fill.setColor(on ? ACCENT : STROKE);
        c.drawRoundRect(r, 13 * d, 13 * d, fill);
        fill.setColor(on ? ON_ACCENT : TEXT2);
        c.drawCircle(on ? r.right - 13 * d : r.left + 13 * d, cy, 9 * d, fill);
    }

    private static int blend(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return 0xFF000000 | ((int) (ar + (br - ar) * t) << 16) | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }

    // ------------------------------------------------------------------
    // Touch
    // ------------------------------------------------------------------
    private int hit(float x, float y) {
        if (sheetOpen) {
            if (rSheetClose.contains(x, y)) return T_SHEET_CLOSE;
            if (!rSheet.contains(x, y)) return T_SHEET_BG;
            if (!rSheetBody.contains(x, y)) return T_NONE;
            for (int i = 0; i < NSL; i++) if (rSlider[i].contains(x, y)) return T_SLIDER + i;
            for (int i = 0; i < rVelPreset.length; i++) if (rVelPreset[i].contains(x, y)) return T_VEL_PRESET + i;
            for (int i = 0; i < rTeach.length; i++) if (rTeach[i].contains(x, y)) return T_TEACH + i;
            if (!rClear.isEmpty() && rClear.contains(x, y)) return T_CLEAR;
            if (rAsk.contains(x, y)) return T_ASK;
            for (int i = 0; i < rNaming.length; i++) if (rNaming[i].contains(x, y)) return T_NAMING + i;
            for (int i = 0; i < rPads.length; i++) if (rPads[i].contains(x, y)) return T_PADS + i;
            return T_SHEET_BODY;
        }
        if (candidate != null) {
            if (rBanPrev.contains(x, y)) return T_BAN_PREV;
            if (rBanNext.contains(x, y)) return T_BAN_NEXT;
            if (rBanIgnore.contains(x, y)) return T_BAN_IGNORE;
            if (rBanStop.contains(x, y)) return T_BAN_STOP;
            if (rBanner.contains(x, y)) return T_NONE;
        }
        if (inflate(rLearn, 3).contains(x, y)) return T_LEARN;
        if (inflate(rKeysDown, 5).contains(x, y)) return T_KEYS_DOWN;
        if (inflate(rKeysUp, 5).contains(x, y)) return T_KEYS_UP;
        if (inflate(rGear, 4).contains(x, y)) return T_GEAR;
        if (rOct.contains(x, y)) return T_OCT_PANEL;
        if (rHint.height() > 0 && rHint.contains(x, y)) return T_HINT;
        if (classicBt && rHeader.isEmpty() && rStatus.contains(x, y)) return T_HINT;
        if (rReverb.contains(x, y)) return T_REVERB;
        for (int i = 0; i < rTiles.length; i++) if (rTiles[i].contains(x, y)) return T_TILE + i;
        return T_NONE;
    }

    private final RectF tmp = new RectF();

    private RectF inflate(RectF a, float dp) {
        tmp.set(a.left - dp * d, a.top - dp * d, a.right + dp * d, a.bottom + dp * d);
        return tmp;
    }

    private boolean isSlider(int t) { return t >= T_SLIDER && t < T_SLIDER + NSL; }

    private void dragTo(int t, float x) {
        if (t == T_REVERB) {
            reverb = Math.max(0f, Math.min(1f, (x - rReverbTrack.left) / rReverbTrack.width()));
            engine.setReverb(reverb);
        } else if (isSlider(t)) {
            int i = t - T_SLIDER;
            RectF tr = rSliderTrack[i];
            setSlider(i, (x - tr.left) / tr.width());
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downTarget = hit(x, y);
                pressed = downTarget;
                downX = x;
                downY = y;
                downScroll = sheetScroll;
                dragging = false;
                sliderActive = false;
                if (downTarget == T_REVERB) { // main screen: no scrolling to compete with
                    sliderActive = true;
                    dragTo(downTarget, x);
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = Math.abs(x - downX), dy = Math.abs(y - downY);
                if (sliderActive) {
                    dragTo(downTarget, x);
                } else if (isSlider(downTarget) && !dragging && dx > 8 * d && dx > dy) {
                    sliderActive = true; // sideways first: adjust the slider
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    dragTo(downTarget, x);
                } else if (sheetOpen && (dragging || dy > 10 * d)) {
                    dragging = true;     // up/down first: scroll the sheet
                    pressed = T_NONE;
                    float max = Math.max(0, sheetContentH - rSheetBody.height());
                    sheetScroll = Math.max(0, Math.min(max, downScroll - (y - downY)));
                } else if (hit(x, y) != downTarget) {
                    pressed = T_NONE;
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP: {
                int t = hit(x, y);
                if (downTarget == T_REVERB) {
                    prefs.edit().putFloat("reverb", reverb).apply();
                } else if (isSlider(downTarget)) {
                    // tap on the track itself sets the value (not on the label row)
                    RectF tr = rSliderTrack[downTarget - T_SLIDER];
                    if (!sliderActive && !dragging && t == downTarget && Math.abs(y - tr.centerY()) < 20 * d)
                        dragTo(downTarget, x);
                } else if (!dragging && t == downTarget && pressed != T_NONE) {
                    click(t);
                }
                pressed = T_NONE;
                downTarget = T_NONE;
                sliderActive = false;
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                if (downTarget == T_REVERB) prefs.edit().putFloat("reverb", reverb).apply();
                pressed = T_NONE;
                downTarget = T_NONE;
                sliderActive = false;
                invalidate();
                return true;
        }
        return super.onTouchEvent(e);
    }

    private void click(int t) {
        if (t == T_SHEET_BODY || t == T_NONE) return;
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        if (t >= T_TILE && t < T_TILE + Synth.PRESET_COUNT) { selectPreset(t - T_TILE); return; }
        if (t >= T_VEL_PRESET && t < T_VEL_PRESET + 4) {
            router.setVelocity(VelocityCurve.preset(t - T_VEL_PRESET, router.velocity()));
            invalidate();
            return;
        }
        if (t >= T_PADS && t < T_PADS + 3) {
            router.setPadMode(t - T_PADS);
            invalidate();
            return;
        }
        if (t >= T_TEACH && t < T_TEACH + MidiRouter.NB) {
            int which = t - T_TEACH;
            teachMsg[which] = null;
            main.removeCallbacks(learnTimeout);
            if (router.learning() == which) {
                router.cancelLearn();
            } else {
                router.startLearn(which);
                main.postDelayed(learnTimeout, 8000);
            }
            invalidate();
            return;
        }
        switch (t) {
            case T_GEAR:
            case T_HINT:
                sheetOpen = true;
                sheetScroll = 0;
                scrollTo = t == T_HINT ? SCROLL_TIPS : SCROLL_NONE;
                break;
            case T_OCT_PANEL:
                sheetOpen = true;
                sheetScroll = 0;
                scrollTo = SCROLL_NAMES;
                break;
            case T_BAN_PREV:
            case T_BAN_NEXT:
                router.assignCandidate(t == T_BAN_PREV ? MidiRouter.B_SOUND_DOWN : MidiRouter.B_SOUND_UP);
                candidate = null;
                main.removeCallbacks(candidateTimeout);
                break;
            case T_BAN_STOP:
                router.setAskButtons(false);
                candidate = null;
                main.removeCallbacks(candidateTimeout);
                break;
            case T_ASK:
                router.setAskButtons(!router.askButtons());
                if (!router.askButtons()) candidate = null;
                break;
            case T_LEARN:
                if (onLearn != null) onLearn.run();
                break;
            case T_KEYS_DOWN: router.shiftKeys(-1); break;
            case T_KEYS_UP: router.shiftKeys(1); break;
            case T_BAN_IGNORE:
                router.ignoreCandidate();
                candidate = null;
                main.removeCallbacks(candidateTimeout);
                break;
            case T_NAMING: router.setMiddleCIsC3(true); break;
            case T_NAMING + 1: router.setMiddleCIsC3(false); break;
            case T_SHEET_BG:
            case T_SHEET_CLOSE:
                closeSheet();
                break;
            case T_CLEAR:
                router.clearBindings();
                java.util.Arrays.fill(teachMsg, null);
                break;
        }
        invalidate();
    }

    private void closeSheet() {
        sheetOpen = false;
        router.cancelLearn();
        main.removeCallbacks(learnTimeout);
    }

    /** Back button closes the sheet first. */
    boolean handleBack() {
        if (!sheetOpen) return false;
        closeSheet();
        invalidate();
        return true;
    }
}

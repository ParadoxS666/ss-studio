package com.pocketkeys.piano;

/**
 * Polyphonic instrument engine. Pure Java (no Android APIs) so it can be
 * tested on a desktop JVM. Single-threaded: every method must be called from
 * the audio thread. Nothing in render() allocates memory.
 */
final class Synth {
    static final int P_PIANO = 0, P_EPIANO = 1, P_ORGAN = 2, P_STRINGS = 3, P_PAD = 4,
            P_LEAD = 5, P_BASS = 6, P_VIBES = 7, P_PLUCK = 8;
    static final int PRESET_COUNT = 9;
    static final String[] NAMES = {
            "Grand Piano", "Electric Piano", "Drawbar Organ",
            "Strings", "Warm Pad", "Synth Lead",
            "Synth Bass", "Vibraphone", "Harp Pluck"};
    static final String[] TAGS = {
            "Sampled", "Tine FM", "Tonewheel",
            "Ensemble", "Analog", "Saw + Square",
            "Resonant", "Motor trem", "Plucked string"};

    // per preset: output gain, chorus mix, reverb scale
    private static final float[] PRESET_GAIN = {1.00f, 0.62f, 0.13f, 0.25f, 0.28f, 0.31f, 0.29f, 0.60f, 0.75f};
    private static final float[] PRESET_CHORUS = {0f, 0.35f, 0.30f, 0.45f, 0.55f, 0.15f, 0f, 0.15f, 0.20f};
    private static final float[] PRESET_REVERB = {1f, 1f, 0.8f, 1.2f, 1.4f, 0.9f, 0.4f, 1.1f, 1.1f};

    static final int MAX_VOICES = 40;
    static final int POLYPHONY = 32;
    static final int CTRL = 32; // control-rate block size

    // ---- sine table, 32-bit phase ----
    static final int SIN_SIZE = 4096;
    static final float[] SIN = new float[SIN_SIZE + 1];
    static final double PHASE_PER_CYCLE = 4294967296.0;
    static final float PM_SCALE = (float) (PHASE_PER_CYCLE / (2 * Math.PI));

    static {
        for (int i = 0; i <= SIN_SIZE; i++) SIN[i] = (float) Math.sin(2 * Math.PI * i / SIN_SIZE);
    }

    static float sinp(int phase) {
        int i = phase >>> 20;
        float f = (phase & 0xFFFFF) * (1f / 1048576f);
        float a = SIN[i];
        return a + (SIN[i + 1] - a) * f;
    }

    final float sr;
    private final Voice[] voices = new Voice[MAX_VOICES];
    private final float[] mixL = new float[CTRL], mixR = new float[CTRL];
    private final Reverb reverb;
    private final Chorus chorus;

    private int preset = P_PIANO;
    private boolean sustain;
    private float bendTarget, bend; // semitones
    private float mod;               // 0..1
    private long ageCounter;
    private float dynRangeDb = 30f;
    private float reverbAmount = 0.3f;
    private float chorusMix = 0f, reverbScale = 1f;
    private float masterGain = 0.55f;
    private float limEnv = 0f;
    private final float limRelease;

    // global LFOs (phase in cycles)
    private double vibPh, tremPh, panPh, rotPh, slowPh;
    // per-block modulation values shared with voices
    float pitchMul = 1f, organVib = 1f, tremolo = 1f, autoPan = 0f, rotPan = 0f, slowLfo = 0f;

    volatile SampleBank bank;

    Synth(float sampleRate) {
        sr = sampleRate;
        for (int i = 0; i < MAX_VOICES; i++) voices[i] = new Voice(this);
        reverb = new Reverb(sampleRate);
        chorus = new Chorus(sampleRate);
        limRelease = (float) Math.exp(-1.0 / (0.15 * sampleRate));
    }

    // ------------------------------------------------------------------
    // Control
    // ------------------------------------------------------------------
    void setPreset(int p) {
        if (p >= 0 && p < PRESET_COUNT) preset = p;
    }

    int preset() { return preset; }

    /** Loudness difference between the softest and hardest note, in dB. */
    void setDynamicRange(float db) { dynRangeDb = db; }

    void setReverb(float amount) { reverbAmount = amount; }

    void setBend(int value14) {
        bendTarget = (value14 - 8192) / 8192f * 2f;
    }

    void setMod(int value7) { mod = value7 / 127f; }

    void setSustain(boolean on) {
        sustain = on;
        if (!on) {
            for (Voice v : voices) {
                if (v.active && v.pedalHeld) {
                    v.pedalHeld = false;
                    v.release();
                }
            }
        }
    }

    void noteOn(int note, int velocity) { noteOn(note, velocity, false); }

    void noteOff(int note) { noteOff(note, false); }

    /**
     * {@code auto} = a note the Learn mode plays for you (the other hand / backing).
     * Your own keys and auto notes never cut each other off.
     */
    void noteOn(int note, int velocity, boolean auto) {
        if (velocity <= 0) { noteOff(note, auto); return; }
        if (note < 0 || note > 127) return;
        // restrike: fade out any voice of the same source already sounding this note
        int sounding = 0;
        for (Voice v : voices) {
            if (!v.active) continue;
            if (v.note == note && v.auto == auto && !v.killing) v.kill();
            if (!v.killing) sounding++;
        }
        if (sounding >= POLYPHONY) stealOne();
        Voice v = freeVoice();
        if (v == null) { // all slots busy (including fades): hard steal the quietest
            v = quietest(true);
            if (v == null) return;
        }
        float vel = velocity / 127f;
        v.start(preset, note, vel, ++ageCounter);
        v.auto = auto;
    }

    void noteOff(int note, boolean auto) {
        for (Voice v : voices) {
            if (v.active && v.held && v.note == note && v.auto == auto) {
                v.held = false;
                if (sustain && !auto) v.pedalHeld = true; // your pedal holds only your notes
                else v.release();
            }
        }
    }

    /** Releases every note the Learn mode is playing for you. */
    void autoAllOff() {
        for (Voice v : voices) {
            if (v.active && v.auto && !v.killing) { v.held = false; v.release(); }
        }
    }

    void allNotesOff() {
        sustain = false;
        for (Voice v : voices) if (v.active) { v.held = false; v.pedalHeld = false; v.kill(); }
    }

    /** Instant silence: no voices, empty effect tails, controllers centred. */
    void reset() {
        for (Voice v : voices) v.active = false;
        sustain = false;
        bend = bendTarget = 0f;
        mod = 0f;
        reverb.clear();
        chorus.clear();
        limEnv = 0f;
    }

    int activeVoices() {
        int n = 0;
        for (Voice v : voices) if (v.active) n++;
        return n;
    }

    private Voice freeVoice() {
        for (Voice v : voices) if (!v.active) return v;
        return null;
    }

    private void stealOne() {
        // prefer released voices, quietest first; otherwise the oldest held voice
        Voice best = quietest(false);
        if (best == null) {
            long oldest = Long.MAX_VALUE;
            for (Voice v : voices) {
                if (v.active && !v.killing && v.age < oldest) { oldest = v.age; best = v; }
            }
        }
        if (best != null) best.kill();
    }

    private Voice quietest(boolean includeHeld) {
        Voice best = null;
        float lvl = Float.MAX_VALUE;
        for (Voice v : voices) {
            if (!v.active || (v.killing && !includeHeld)) continue;
            if (!includeHeld && (v.held || v.pedalHeld)) continue;
            if (v.level < lvl) { lvl = v.level; best = v; }
        }
        if (best != null && includeHeld) best.active = false;
        return best;
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------
    /** Renders {@code frames} stereo frames, interleaved, into {@code out}. */
    void render(float[] out, int frames) {
        int o = 0;
        for (int off = 0; off < frames; off += CTRL) {
            int n = Math.min(CTRL, frames - off);
            updateControl(n);
            java.util.Arrays.fill(mixL, 0, n, 0f);
            java.util.Arrays.fill(mixR, 0, n, 0f);
            for (Voice v : voices) if (v.active) v.render(mixL, mixR, n);

            float cm = chorusMix, rv = reverbAmount * reverbScale * 0.9f, g = masterGain;
            for (int i = 0; i < n; i++) {
                float l = mixL[i], r = mixR[i];
                if (cm > 0.001f) {
                    chorus.process(l, r);
                    l += cm * chorus.outL;
                    r += cm * chorus.outR;
                    float norm = 1f / (1f + 0.5f * cm);
                    l *= norm;
                    r *= norm;
                } else {
                    chorus.process(0f, 0f); // keep delay lines flowing/decaying
                }
                if (rv > 0.001f) {
                    reverb.process(l + r);
                    l += rv * reverb.outL;
                    r += rv * reverb.outR;
                }
                l *= g;
                r *= g;
                // peak limiter (instant attack, 150 ms release) + hard ceiling
                float pk = Math.max(Math.abs(l), Math.abs(r));
                limEnv = pk > limEnv ? pk : limEnv * limRelease + pk * (1 - limRelease);
                if (limEnv > 0.9f) {
                    float lg = 0.9f / limEnv;
                    l *= lg;
                    r *= lg;
                }
                if (l > 1f) l = 1f; else if (l < -1f) l = -1f;
                if (r > 1f) r = 1f; else if (r < -1f) r = -1f;
                out[o++] = l;
                out[o++] = r;
            }
        }
    }

    private void updateControl(int n) {
        double dt = n / (double) sr;
        // pitch bend glides quickly to target (avoids zipper noise)
        bend += (bendTarget - bend) * 0.5f;
        vibPh += 5.5 * dt;
        tremPh += 5.2 * dt;
        panPh += 3.8 * dt;
        rotPh += 6.6 * dt;
        slowPh += 0.18 * dt;
        if (vibPh > 1) vibPh -= 1;
        if (tremPh > 1) tremPh -= 1;
        if (panPh > 1) panPh -= 1;
        if (rotPh > 1) rotPh -= 1;
        if (slowPh > 1) slowPh -= 1;
        float vib = (float) Math.sin(2 * Math.PI * vibPh);
        float bendMul = (float) Math.pow(2, bend / 12.0);
        pitchMul = bendMul * (1f + mod * 0.029f * vib);
        float rot = (float) Math.sin(2 * Math.PI * rotPh);
        organVib = 1f + 0.0025f * rot;
        rotPan = 0.25f * rot;
        tremolo = 1f - 0.35f * (0.5f + 0.5f * (float) Math.sin(2 * Math.PI * tremPh));
        autoPan = 0.28f * (float) Math.sin(2 * Math.PI * panPh);
        slowLfo = (float) Math.sin(2 * Math.PI * slowPh);
        // glide effect settings toward the current preset
        chorusMix += (PRESET_CHORUS[preset] - chorusMix) * 0.02f;
        reverbScale += (PRESET_REVERB[preset] - reverbScale) * 0.02f;
    }

    float velAmp(float v) {
        return (float) Math.pow(10, (-dynRangeDb * (1 - v)) / 20);
    }

    static double noteHz(int note) {
        return 440.0 * Math.pow(2, (note - 69) / 12.0);
    }

    // ------------------------------------------------------------------
    // Voice
    // ------------------------------------------------------------------
    // drawbars 8', 4', 2⅔', 2', 1⅓', 1' — nothing below the key's own pitch (no 16' or 5⅓')
    private static final double[] ORGAN_RATIOS = {1, 2, 3, 4, 6, 8};
    private static final float[] ORGAN_AMPS = {1f, 0.65f, 0.45f, 0.4f, 0.2f, 0.12f};
    private static final double[] VIBES_RATIOS = {1, 3.98, 9.9};
    private static final double[] VIBES_T60 = {1, 0.25, 0.08};

    static final class Voice {
        private final Synth s;
        boolean active, held, pedalHeld, killing, auto;
        int preset, note;
        long age;
        float vel, level;

        // amplitude
        private float gain, env, relMul, killGain, killMul;
        private boolean released;
        private int stage; // 0 attack, 1 decay, 2 sustain, 3 release
        private float atkInc, decMul, susLevel;
        private float gainL, gainR;

        // sample playback
        private int smp;
        private double pos, posInc;
        private float tail, tailMul, lpL, lpR, lpA;
        private boolean inTail;

        // oscillators
        private final int[] ph = new int[8];
        private final double[] inc = new double[8];
        private final float[] pAmp = new float[8], pMul = new float[8];
        private int nPart;
        private float idx1, idx1Mul, idx1Floor, idx2, idx2Mul;
        private float sp0, sp1, sp2, sinc0, sinc1, sinc2;

        // filters (SVF, two channels)
        private float aZ1, aZ2, bZ1, bZ2;
        private float cut, cutTarget, cutMul, q;

        // noise / click
        private int rng = 0x12345678;
        private float click, clickMul, clickLp;

        // Karplus-Strong
        private static final int KS_SIZE = 4096, KS_MASK = KS_SIZE - 1;
        private final float[] ks = new float[KS_SIZE];
        private int ksW;
        private float ksPeriod, ksRho, ksRhoRel, ksPrev;

        Voice(Synth s) { this.s = s; }

        private float noise() {
            rng ^= rng << 13;
            rng ^= rng >>> 17;
            rng ^= rng << 5;
            return rng * (1f / 2147483648f);
        }

        private static double t60Mul(double seconds, float sr) {
            return Math.pow(10, -3.0 / (seconds * sr));
        }

        void start(int preset, int note, float vel, long age) {
            this.preset = preset;
            this.note = note;
            this.vel = vel;
            this.age = age;
            active = true;
            held = true;
            pedalHeld = false;
            killing = false;
            released = false;
            killGain = 1f;
            env = 0f;
            stage = 0;
            level = 1f;
            gainL = gainR = 1f;
            aZ1 = aZ2 = bZ1 = bZ2 = 0f;
            click = 0f;
            clickLp = 0f;
            final float sr = s.sr;
            final float amp = s.velAmp(vel) * PRESET_GAIN[preset];
            double f = noteHz(note); // every sound plays exactly the key's pitch
            gain = amp;
            relMul = (float) t60Mul(0.08, sr);
            for (int i = 0; i < 8; i++) ph[i] = (int) (noise() * 2147483647f);

            switch (preset) {
                case P_PIANO: {
                    SampleBank b = s.bank;
                    if (b == null) { active = false; return; }
                    smp = b.nearest(note);
                    // exact equal-temperament pitch: shift by the interval, minus the recording's own detune
                    double ratio = Math.pow(2, (note - b.root[smp]) / 12.0 - b.tuneCents[smp] / 1200.0);
                    posInc = ratio * b.sampleRate[smp] / sr;
                    pos = 0;
                    tail = 1f;
                    inTail = false;
                    tailMul = (float) Math.pow(10, -b.tailDecayDb[smp] * ratio / (20.0 * sr));
                    double fc = (700 + 17000 * vel * vel) * (0.7 + 0.6 * Math.min(1.0, Math.max(0.0, (note - 21) / 87.0)));
                    lpA = (float) (1 - Math.exp(-2 * Math.PI * Math.min(fc, sr * 0.45) / sr));
                    lpL = lpR = 0f;
                    // damper release time: longer in the bass; top notes have no dampers
                    double rel = note >= 89 ? 2.0 : 0.55 - 0.35 * ((note - 21) / 68.0);
                    relMul = (float) t60Mul(rel, sr);
                    gain = s.velAmp(vel) * PRESET_GAIN[P_PIANO] * (1f / 32768f);
                    env = 1f;
                    break;
                }
                case P_EPIANO: {
                    inc[0] = f / sr * PHASE_PER_CYCLE;          // carrier
                    inc[1] = f * 1.0 / sr * PHASE_PER_CYCLE;    // body modulator
                    inc[2] = f * 14.0 / sr * PHASE_PER_CYCLE;   // tine modulator
                    inc[3] = f * 1.003 / sr * PHASE_PER_CYCLE;  // detuned carrier
                    idx1 = 0.4f + 2.0f * vel;
                    idx1Floor = 0.25f + 0.3f * vel;
                    idx1Mul = (float) Math.exp(-1.0 / (0.6 * sr));
                    idx2 = f * 14 < sr * 0.45 ? 1.6f * vel : 0f;
                    idx2Mul = (float) Math.exp(-1.0 / (0.02 * sr));
                    double t60 = clamp(6.0 * Math.pow(2, -(note - 36) / 24.0), 0.9, 7.0);
                    decMul = (float) t60Mul(t60, sr);
                    atkInc = 1f / (0.001f * sr);
                    relMul = (float) t60Mul(0.28, sr);
                    break;
                }
                case P_ORGAN: {
                    final double[] ratios = ORGAN_RATIOS;
                    nPart = 0;
                    for (int i = 0; i < ratios.length; i++) {
                        double pf = f * ratios[i];
                        if (pf > sr * 0.45) break;
                        inc[nPart] = pf / sr * PHASE_PER_CYCLE;
                        pAmp[nPart] = ORGAN_AMPS[i];
                        pMul[nPart] = 1f;
                        nPart++;
                    }
                    // percussion on the 3rd harmonic (index 2 = ratio 3), like a tonewheel organ
                    idx1 = 0.7f * vel + 0.3f;
                    idx1Mul = (float) t60Mul(0.35, sr);
                    click = 0.12f + 0.1f * vel;
                    clickMul = (float) t60Mul(0.012, sr);
                    atkInc = 1f / (0.003f * sr);
                    relMul = (float) t60Mul(0.04, sr);
                    gain = PRESET_GAIN[P_ORGAN] * (0.6f + 0.4f * s.velAmp(vel)); // organs are not very touch sensitive
                    break;
                }
                case P_STRINGS: {
                    sinc0 = (float) (f * Math.pow(2, -0.08 / 12) / sr);
                    sinc1 = (float) (f / sr);
                    sinc2 = (float) (f * Math.pow(2, 0.09 / 12) / sr);
                    sp0 = noise() * 0.5f + 0.5f;
                    sp1 = noise() * 0.5f + 0.5f;
                    sp2 = noise() * 0.5f + 0.5f;
                    cutTarget = (float) Math.min(sr * 0.4, f * 6 + 600 + 2500 * vel);
                    cut = cutTarget;
                    cutMul = 1f;
                    q = 0.75f;
                    atkInc = 1f / ((0.40f - 0.25f * vel) * sr);
                    relMul = (float) t60Mul(0.9, sr);
                    break;
                }
                case P_PAD: {
                    sinc0 = (float) (f * Math.pow(2, -0.12 / 12) / sr);
                    sinc1 = (float) (f * Math.pow(2, 0.12 / 12) / sr);
                    inc[0] = f / sr * PHASE_PER_CYCLE; // sine body at the key's pitch
                    sp0 = noise() * 0.5f + 0.5f;
                    sp1 = noise() * 0.5f + 0.5f;
                    cut = 300f;
                    cutTarget = (float) Math.min(sr * 0.35, 350 + f * 3 + 1600 * vel);
                    cutMul = (float) Math.exp(-1.0 / (0.7 * sr / CTRL));
                    q = 1.0f;
                    atkInc = 1f / (0.9f * sr);
                    relMul = (float) t60Mul(2.2, sr);
                    break;
                }
                case P_LEAD: {
                    sinc0 = (float) (f / sr);
                    sinc1 = (float) (f * Math.pow(2, 0.07 / 12) / sr);
                    sp0 = 0f;
                    sp1 = 0.25f;
                    cut = (float) Math.min(sr * 0.42, f * 10 + 1500 + 3000 * vel);
                    cutTarget = (float) Math.min(sr * 0.42, f * 4 + 900);
                    cutMul = (float) Math.exp(-1.0 / (0.35 * sr / CTRL));
                    q = 1.8f;
                    atkInc = 1f / (0.004f * sr);
                    decMul = (float) Math.exp(-1.0 / (0.5 * sr));
                    susLevel = 0.8f;
                    relMul = (float) t60Mul(0.25, sr);
                    break;
                }
                case P_BASS: {
                    sinc0 = (float) (f / sr);
                    inc[0] = f / sr * PHASE_PER_CYCLE; // sine body at the key's pitch
                    sp0 = 0f;
                    cut = (float) Math.min(sr * 0.42, f * 14 + 400 + 3500 * vel);
                    cutTarget = (float) (f * 2.5 + 120);
                    cutMul = (float) Math.exp(-1.0 / (0.16 * sr / CTRL));
                    q = 2.4f;
                    atkInc = 1f / (0.002f * sr);
                    decMul = (float) Math.exp(-1.0 / (0.8 * sr));
                    susLevel = 0.6f;
                    relMul = (float) t60Mul(0.12, sr);
                    break;
                }
                case P_VIBES: {
                    double base = clamp(4.5 * Math.pow(2, -(note - 53) / 18.0), 0.8, 7.0);
                    nPart = 0;
                    for (int i = 0; i < 3; i++) {
                        double pf = f * VIBES_RATIOS[i];
                        if (pf > sr * 0.45) break;
                        inc[nPart] = pf / sr * PHASE_PER_CYCLE;
                        pAmp[nPart] = i == 0 ? 1f : (i == 1 ? 0.10f + 0.25f * vel : 0.02f + 0.08f * vel);
                        pMul[nPart] = (float) t60Mul(base * VIBES_T60[i], sr);
                        nPart++;
                    }
                    click = 0.25f * vel;
                    clickMul = (float) t60Mul(0.006, sr);
                    env = 1f;
                    relMul = (float) t60Mul(0.35, sr);
                    break;
                }
                case P_PLUCK: {
                    ksPeriod = (float) (sr / f);
                    double t60 = clamp(5.0 * Math.pow(2, -(note - 40) / 20.0), 0.6, 6.0);
                    ksRho = (float) Math.pow(10, -3.0 / (t60 * f));
                    ksRhoRel = (float) Math.pow(10, -3.0 / (0.18 * f));
                    // excitation: velocity-filtered noise with a pluck-position comb
                    int len = Math.min(KS_SIZE - 4, (int) Math.ceil(ksPeriod) + 2);
                    float a = 0.10f + 0.35f * vel, lp = 0f, lp2 = 0f, mean = 0f;
                    for (int i = 0; i < len; i++) {
                        lp += (noise() - lp) * a;
                        lp2 += (lp - lp2) * a;
                        ks[i] = lp2 * 2f;
                        mean += ks[i];
                    }
                    mean /= len;
                    int pick = Math.max(1, (int) (len * 0.13f));
                    for (int i = len - 1; i >= 0; i--) {
                        float d = i >= pick ? ks[i - pick] : 0f;
                        ks[i] = (ks[i] - mean) - 0.6f * (d - (i >= pick ? mean : 0f));
                    }
                    for (int i = len; i < KS_SIZE; i++) ks[i] = 0f;
                    ksW = len;
                    ksPrev = 0f;
                    float pan = (float) clamp((note - 60) / 60.0 * 0.6, -0.6, 0.6);
                    gainL = 1f - pan;
                    gainR = 1f + pan;
                    env = 1f;
                    break;
                }
            }
        }

        void release() {
            if (!released) released = true;
            if (stage != 3) stage = 3;
        }

        void kill() {
            killing = true;
            held = false;
            pedalHeld = false;
            killMul = (float) Math.exp(-1.0 / (0.004 * s.sr));
        }

        void render(float[] L, float[] R, int n) {
            switch (preset) {
                case P_PIANO: renderSample(L, R, n); break;
                case P_EPIANO: renderEP(L, R, n); break;
                case P_ORGAN: renderAdditive(L, R, n, true); break;
                case P_VIBES: renderAdditive(L, R, n, false); break;
                case P_STRINGS: renderStrings(L, R, n); break;
                case P_PAD: renderPad(L, R, n); break;
                case P_LEAD: renderLead(L, R, n); break;
                case P_BASS: renderBass(L, R, n); break;
                case P_PLUCK: renderPluck(L, R, n); break;
            }
            if (killing && killGain < 0.001f) active = false;
        }

        // generic ADSR step (stage 0..3); returns current envelope value
        private float adsr() {
            switch (stage) {
                case 0:
                    env += atkInc;
                    if (env >= 1f) { env = 1f; stage = 1; }
                    break;
                case 1:
                    env = susLevel + (env - susLevel) * decMul;
                    break;
                case 3:
                    env *= relMul;
                    break;
                default:
                    break;
            }
            return env;
        }

        private float killStep() {
            if (killing) killGain *= killMul;
            return killGain;
        }

        private void finishIfSilent(float threshold) {
            level = env * gain * killGain;
            if (stage == 3 && env < threshold) active = false;
        }

        // ---- sampled piano ----
        private void renderSample(float[] L, float[] R, int n) {
            final SampleBank b = s.bank;
            if (b == null) { active = false; return; }
            final short[] d = b.data;
            final int base = b.offset[smp];
            final int ls = b.loopStart[smp], le = b.loopEnd[smp];
            final int loopLen = le - ls;
            final double step = posInc * s.pitchMul;
            final float a = lpA;
            final boolean damped = stage == 3;
            for (int i = 0; i < n; i++) {
                int ip = (int) pos;
                float fr = (float) (pos - ip);
                int i0 = ip - 1, i1 = ip, i2 = ip + 1, i3 = ip + 2;
                if (i0 < 0) i0 = 0;
                if (i2 >= le) i2 -= loopLen;
                if (i3 >= le) i3 -= loopLen;
                int k0 = (base + i0) << 1, k1 = (base + i1) << 1, k2 = (base + i2) << 1, k3 = (base + i3) << 1;
                float xl = hermite(d[k0], d[k1], d[k2], d[k3], fr);
                float xr = hermite(d[k0 + 1], d[k1 + 1], d[k2 + 1], d[k3 + 1], fr);
                lpL += (xl - lpL) * a;
                lpR += (xr - lpR) * a;
                if (damped) env *= relMul;
                if (inTail) tail *= tailMul;
                float g = gain * env * tail * killStep();
                L[i] += lpL * g;
                R[i] += lpR * g;
                pos += step;
                if (pos >= ls) {
                    inTail = true;
                    if (pos >= le) pos -= loopLen;
                }
            }
            level = env * tail * killGain * s.velAmp(vel);
            if ((damped && env < 0.0005f) || tail < 0.0002f) active = false;
        }

        private static float hermite(float y0, float y1, float y2, float y3, float t) {
            float c1 = 0.5f * (y2 - y0);
            float c2 = y0 - 2.5f * y1 + 2f * y2 - 0.5f * y3;
            float c3 = 0.5f * (y3 - y0) + 1.5f * (y1 - y2);
            return ((c3 * t + c2) * t + c1) * t + y1;
        }

        // ---- FM electric piano ----
        private void renderEP(float[] L, float[] R, int n) {
            final float pm = s.pitchMul;
            final int i0 = (int) (long) (inc[0] * pm), i1 = (int) (long) (inc[1] * pm),
                    i2 = (int) (long) (inc[2] * pm), i3 = (int) (long) (inc[3] * pm);
            final float pan = s.autoPan;
            final float gl = 1f - pan, gr = 1f + pan;
            for (int i = 0; i < n; i++) {
                // envelope: 1 ms attack, then exponential decay; release faster
                if (stage == 0) {
                    env += atkInc;
                    if (env >= 1f) { env = 1f; stage = 1; }
                } else if (stage == 3) env *= relMul;
                else env *= decMul;
                idx1 = idx1Floor + (idx1 - idx1Floor) * idx1Mul;
                idx2 *= idx2Mul;
                float m = idx1 * sinp(ph[1]) + idx2 * sinp(ph[2]);
                int off = (int) (m * PM_SCALE);
                float y = 0.75f * sinp(ph[0] + off) + 0.35f * sinp(ph[3] + (off >> 1));
                ph[0] += i0;
                ph[1] += i1;
                ph[2] += i2;
                ph[3] += i3;
                float g = gain * env * killStep();
                L[i] += y * g * gl;
                R[i] += y * g * gr;
            }
            level = env * gain * killGain;
            if (env < 0.0003f) active = false;
        }

        // ---- organ (sustained) and vibraphone (decaying partials) ----
        private void renderAdditive(float[] L, float[] R, int n, boolean organ) {
            final float pm = s.pitchMul * (organ ? s.organVib : 1f);
            final int np = nPart;
            int c0 = 0, c1 = 0, c2 = 0, c3 = 0, c4 = 0, c5 = 0, c6 = 0;
            if (np > 0) c0 = (int) (long) (inc[0] * pm);
            if (np > 1) c1 = (int) (long) (inc[1] * pm);
            if (np > 2) c2 = (int) (long) (inc[2] * pm);
            if (np > 3) c3 = (int) (long) (inc[3] * pm);
            if (np > 4) c4 = (int) (long) (inc[4] * pm);
            if (np > 5) c5 = (int) (long) (inc[5] * pm);
            if (np > 6) c6 = (int) (long) (inc[6] * pm);
            final float gl, gr;
            if (organ) { gl = 1f - s.rotPan; gr = 1f + s.rotPan; }
            else { gl = s.tremolo; gr = s.tremolo; }
            for (int i = 0; i < n; i++) {
                float y = 0f;
                if (organ) {
                    if (stage == 0) {
                        env += atkInc;
                        if (env >= 1f) { env = 1f; stage = 2; }
                    } else if (stage == 3) env *= relMul;
                    idx1 *= idx1Mul; // percussion
                    if (np > 0) { y += pAmp[0] * sinp(ph[0]); ph[0] += c0; }
                    if (np > 1) { y += pAmp[1] * sinp(ph[1]); ph[1] += c1; }
                    if (np > 2) { y += (pAmp[2] + idx1) * sinp(ph[2]); ph[2] += c2; } // + percussion
                    if (np > 3) { y += pAmp[3] * sinp(ph[3]); ph[3] += c3; }
                    if (np > 4) { y += pAmp[4] * sinp(ph[4]); ph[4] += c4; }
                    if (np > 5) { y += pAmp[5] * sinp(ph[5]); ph[5] += c5; }
                    if (np > 6) { y += pAmp[6] * sinp(ph[6]); ph[6] += c6; }
                } else {
                    if (stage == 3) env *= relMul;
                    if (np > 0) { y += pAmp[0] * sinp(ph[0]); ph[0] += c0; pAmp[0] *= pMul[0]; }
                    if (np > 1) { y += pAmp[1] * sinp(ph[1]); ph[1] += c1; pAmp[1] *= pMul[1]; }
                    if (np > 2) { y += pAmp[2] * sinp(ph[2]); ph[2] += c2; pAmp[2] *= pMul[2]; }
                }
                if (click > 0.0001f) {
                    clickLp += (noise() - clickLp) * 0.35f;
                    y += clickLp * click;
                    click *= clickMul;
                }
                float g = gain * env * killStep();
                L[i] += y * g * gl;
                R[i] += y * g * gr;
            }
            level = env * gain * killGain * (organ ? 1f : pAmp[0]);
            if (stage == 3 && env < 0.0003f) active = false;
            if (!organ && pAmp[0] < 0.0003f) active = false;
        }

        // ---- shared helpers for subtractive voices ----
        private static float blep(float t, float dt) {
            if (t < dt) { t /= dt; return t + t - t * t - 1f; }
            if (t > 1f - dt) { t = (t - 1f) / dt; return t * t + t + t + 1f; }
            return 0f;
        }

        // SVF coefficients for the current block
        private float g1, a1, a2, a3, k;

        private void svfCoeffs(float fc, float Q) {
            float sr = s.sr;
            if (fc > sr * 0.45f) fc = sr * 0.45f;
            if (fc < 20f) fc = 20f;
            float g = (float) Math.tan(Math.PI * fc / sr);
            k = 1f / Q;
            a1 = 1f / (1f + g * (g + k));
            a2 = g * a1;
            a3 = g * a2;
            g1 = g;
        }

        // ---- strings: three detuned saws, panned L / C / R ----
        private void renderStrings(float[] L, float[] R, int n) {
            final float pm = s.pitchMul * (1f + 0.0015f * s.slowLfo);
            final float d0 = sinc0 * pm, d1 = sinc1 * pm, d2 = sinc2 * pm;
            svfCoeffs(cut, q);
            for (int i = 0; i < n; i++) {
                if (stage == 0) {
                    env += atkInc;
                    if (env >= 1f) { env = 1f; stage = 2; }
                } else if (stage == 3) env *= relMul;
                float s0 = 2f * sp0 - 1f - blep(sp0, d0);
                float s1 = 2f * sp1 - 1f - blep(sp1, d1);
                float s2 = 2f * sp2 - 1f - blep(sp2, d2);
                sp0 += d0; if (sp0 >= 1f) sp0 -= 1f;
                sp1 += d1; if (sp1 >= 1f) sp1 -= 1f;
                sp2 += d2; if (sp2 >= 1f) sp2 -= 1f;
                float xl = s0 + 0.6f * s1, xr = s2 + 0.6f * s1;
                // left filter
                float v3 = xl - aZ2, v1 = a1 * aZ1 + a2 * v3, v2 = aZ2 + a2 * aZ1 + a3 * v3;
                aZ1 = 2f * v1 - aZ1; aZ2 = 2f * v2 - aZ2;
                float ol = v2;
                v3 = xr - bZ2; v1 = a1 * bZ1 + a2 * v3; v2 = bZ2 + a2 * bZ1 + a3 * v3;
                bZ1 = 2f * v1 - bZ1; bZ2 = 2f * v2 - bZ2;
                float g = gain * env * killStep();
                L[i] += ol * g;
                R[i] += v2 * g;
            }
            finishIfSilent(0.0003f);
        }

        // ---- warm pad: two detuned saws (L/R) + sub, slow filter sweep ----
        private void renderPad(float[] L, float[] R, int n) {
            final float pm = s.pitchMul;
            final float d0 = sinc0 * pm, d1 = sinc1 * pm;
            final int si = (int) (long) (inc[0] * pm);
            cut = cutTarget + (cut - cutTarget) * cutMul;
            svfCoeffs(cut * (1f + 0.25f * s.slowLfo), q);
            for (int i = 0; i < n; i++) {
                if (stage == 0) {
                    env += atkInc;
                    if (env >= 1f) { env = 1f; stage = 2; }
                } else if (stage == 3) env *= relMul;
                float s0 = 2f * sp0 - 1f - blep(sp0, d0);
                float s1 = 2f * sp1 - 1f - blep(sp1, d1);
                sp0 += d0; if (sp0 >= 1f) sp0 -= 1f;
                sp1 += d1; if (sp1 >= 1f) sp1 -= 1f;
                float sub = 0.5f * sinp(ph[0]);
                ph[0] += si;
                float xl = s0 + sub, xr = s1 + sub;
                float v3 = xl - aZ2, v1 = a1 * aZ1 + a2 * v3, v2 = aZ2 + a2 * aZ1 + a3 * v3;
                aZ1 = 2f * v1 - aZ1; aZ2 = 2f * v2 - aZ2;
                float ol = v2;
                v3 = xr - bZ2; v1 = a1 * bZ1 + a2 * v3; v2 = bZ2 + a2 * bZ1 + a3 * v3;
                bZ1 = 2f * v1 - bZ1; bZ2 = 2f * v2 - bZ2;
                float g = gain * env * killStep();
                L[i] += ol * g;
                R[i] += v2 * g;
            }
            finishIfSilent(0.0003f);
        }

        // ---- lead: saw + square, resonant filter with envelope ----
        private void renderLead(float[] L, float[] R, int n) {
            final float pm = s.pitchMul;
            final float d0 = sinc0 * pm, d1 = sinc1 * pm;
            cut = cutTarget + (cut - cutTarget) * cutMul;
            svfCoeffs(cut, q);
            for (int i = 0; i < n; i++) {
                float e = adsr();
                float saw = 2f * sp0 - 1f - blep(sp0, d0);
                float p2 = sp1 + 0.5f; if (p2 >= 1f) p2 -= 1f;
                float sq = (2f * sp1 - 1f - blep(sp1, d1)) - (2f * p2 - 1f - blep(p2, d1));
                sp0 += d0; if (sp0 >= 1f) sp0 -= 1f;
                sp1 += d1; if (sp1 >= 1f) sp1 -= 1f;
                float x = 0.6f * saw + 0.5f * sq;
                float v3 = x - aZ2, v1 = a1 * aZ1 + a2 * v3, v2 = aZ2 + a2 * aZ1 + a3 * v3;
                aZ1 = 2f * v1 - aZ1; aZ2 = 2f * v2 - aZ2;
                float g = gain * e * killStep();
                L[i] += v2 * g;
                R[i] += v2 * g;
            }
            finishIfSilent(0.0003f);
        }

        // ---- bass: saw + sine body, resonant filter pluck ----
        private void renderBass(float[] L, float[] R, int n) {
            final float pm = s.pitchMul;
            final float d0 = sinc0 * pm;
            final int si = (int) (long) (inc[0] * pm);
            cut = cutTarget + (cut - cutTarget) * cutMul;
            svfCoeffs(cut, q);
            for (int i = 0; i < n; i++) {
                float e = adsr();
                float saw = 2f * sp0 - 1f - blep(sp0, d0);
                sp0 += d0; if (sp0 >= 1f) sp0 -= 1f;
                float x = 0.8f * saw;
                float v3 = x - aZ2, v1 = a1 * aZ1 + a2 * v3, v2 = aZ2 + a2 * aZ1 + a3 * v3;
                aZ1 = 2f * v1 - aZ1; aZ2 = 2f * v2 - aZ2;
                float y = v2 + 0.7f * sinp(ph[0]);
                ph[0] += si;
                float g = gain * e * killStep();
                L[i] += y * g;
                R[i] += y * g;
            }
            finishIfSilent(0.0003f);
        }

        // ---- Karplus-Strong pluck ----
        private void renderPluck(float[] L, float[] R, int n) {
            final float period = ksPeriod / s.pitchMul;
            final float d = Math.max(2f, Math.min(KS_SIZE - 4, period - 0.5f));
            final int di = (int) d;
            final float fr = d - di;
            final float rho = stage == 3 ? ksRhoRel : ksRho;
            float peak = 0f;
            for (int i = 0; i < n; i++) {
                float r0 = ks[(ksW - di) & KS_MASK];
                float r1 = ks[(ksW - di - 1) & KS_MASK];
                float y = r0 + (r1 - r0) * fr;
                float lp = 0.5f * (y + ksPrev);
                ksPrev = y;
                ks[ksW & KS_MASK] = lp * rho + 1e-20f;
                ksW++;
                float g = gain * killStep();
                L[i] += y * g * gainL;
                R[i] += y * g * gainR;
                float ay = Math.abs(y);
                if (ay > peak) peak = ay;
            }
            if (ksW > 1 << 30) ksW &= KS_MASK;
            env = Math.max(peak, env * 0.97f);
            level = env * gain * killGain;
            if (env < 0.0003f) active = false;
        }

        private static double clamp(double x, double lo, double hi) {
            return x < lo ? lo : (x > hi ? hi : x);
        }
    }

    // ------------------------------------------------------------------
    // Effects
    // ------------------------------------------------------------------
    /** Freeverb-style stereo reverb. */
    static final class Reverb {
        private static final int[] COMB = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
        private static final int[] AP = {556, 441, 341, 225};
        private static final int SPREAD = 23;
        private final float[][] cL = new float[8][], cR = new float[8][], aL = new float[4][], aR = new float[4][];
        private final int[] ciL = new int[8], ciR = new int[8], aiL = new int[4], aiR = new int[4];
        private final float[] fsL = new float[8], fsR = new float[8];
        private final float feedback = 0.82f, damp = 0.3f;
        float outL, outR;

        void clear() {
            for (float[] b : cL) java.util.Arrays.fill(b, 0f);
            for (float[] b : cR) java.util.Arrays.fill(b, 0f);
            for (float[] b : aL) java.util.Arrays.fill(b, 0f);
            for (float[] b : aR) java.util.Arrays.fill(b, 0f);
            java.util.Arrays.fill(fsL, 0f);
            java.util.Arrays.fill(fsR, 0f);
        }

        Reverb(float sr) {
            float scale = sr / 44100f;
            for (int i = 0; i < 8; i++) {
                cL[i] = new float[(int) (COMB[i] * scale)];
                cR[i] = new float[(int) ((COMB[i] + SPREAD) * scale)];
            }
            for (int i = 0; i < 4; i++) {
                aL[i] = new float[(int) (AP[i] * scale)];
                aR[i] = new float[(int) ((AP[i] + SPREAD) * scale)];
            }
        }

        void process(float in) {
            float x = in * 0.015f + 1e-20f;
            float l = 0f, r = 0f;
            for (int i = 0; i < 8; i++) {
                float[] b = cL[i];
                int p = ciL[i];
                float y = b[p];
                fsL[i] = y * (1 - damp) + fsL[i] * damp;
                b[p] = x + fsL[i] * feedback;
                if (++p >= b.length) p = 0;
                ciL[i] = p;
                l += y;
                b = cR[i];
                p = ciR[i];
                y = b[p];
                fsR[i] = y * (1 - damp) + fsR[i] * damp;
                b[p] = x + fsR[i] * feedback;
                if (++p >= b.length) p = 0;
                ciR[i] = p;
                r += y;
            }
            for (int i = 0; i < 4; i++) {
                float[] b = aL[i];
                int p = aiL[i];
                float bo = b[p];
                b[p] = l + bo * 0.5f;
                l = bo - l;
                if (++p >= b.length) p = 0;
                aiL[i] = p;
                b = aR[i];
                p = aiR[i];
                bo = b[p];
                b[p] = r + bo * 0.5f;
                r = bo - r;
                if (++p >= b.length) p = 0;
                aiR[i] = p;
            }
            outL = l;
            outR = r;
        }
    }

    /** Stereo chorus: two modulated delay lines in quadrature. */
    static final class Chorus {
        private static final int SIZE = 4096, MASK = SIZE - 1;
        private final float[] bl = new float[SIZE], br = new float[SIZE];
        private int w;
        private double ph;
        private final double phInc;
        private final float base, depth;
        float outL, outR;

        void clear() {
            java.util.Arrays.fill(bl, 0f);
            java.util.Arrays.fill(br, 0f);
        }

        Chorus(float sr) {
            phInc = 0.55 / sr;
            base = 0.013f * sr;
            depth = 0.004f * sr;
        }

        void process(float l, float r) {
            bl[w & MASK] = l;
            br[w & MASK] = r;
            ph += phInc;
            if (ph > 1) ph -= 1;
            int p = (int) (ph * 4294967296.0);
            float dl = base + depth * sinp(p);
            float dr = base + depth * sinp(p + 0x40000000);
            outL = read(bl, w - dl);
            outR = read(br, w - dr);
            w++;
            if (w > 1 << 30) w &= MASK;
        }

        private static float read(float[] b, float pos) {
            int i = (int) Math.floor(pos);
            float f = pos - i;
            float a = b[i & MASK], c = b[(i + 1) & MASK];
            return a + (c - a) * f;
        }
    }
}

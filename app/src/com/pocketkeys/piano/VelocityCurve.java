package com.pocketkeys.piano;

/**
 * Immutable key-velocity response. Pure Java so it can be unit tested.
 *
 *   x   = min(1, raw / fullForceAt)        how hard you hit, 0..1
 *   y   = x ^ gamma                        curve: gamma < 1 lighter, > 1 heavier
 *   out = softest + (loudest - softest)·y  output velocity 1..127
 *
 * "Fixed" ignores how hard you play and always sends {@code fixedValue}.
 */
final class VelocityCurve {
    static final int P_LIGHT = 0, P_NORMAL = 1, P_HEAVY = 2, P_FIXED = 3, P_CUSTOM = -1;

    final float curve;      // -1 (lightest) .. +1 (heaviest), 0 = linear
    final int fullForceAt;  // raw velocity that already gives the loudest note (20..127)
    final int softest;      // output for the gentlest touch (1..127)
    final int loudest;      // output for full force (1..127)
    final boolean fixed;
    final int fixedValue;   // 1..127

    VelocityCurve(float curve, int fullForceAt, int softest, int loudest, boolean fixed, int fixedValue) {
        this.curve = Math.max(-1f, Math.min(1f, curve));
        this.fullForceAt = clamp(fullForceAt, 20, 127);
        int lo = clamp(softest, 1, 127), hi = clamp(loudest, 1, 127);
        this.softest = Math.min(lo, hi);
        this.loudest = Math.max(lo, hi);
        this.fixed = fixed;
        this.fixedValue = clamp(fixedValue, 1, 127);
    }

    static VelocityCurve preset(int p, VelocityCurve keep) {
        int fv = keep != null ? keep.fixedValue : 100;
        switch (p) {
            case P_LIGHT: return new VelocityCurve(-0.55f, 110, 1, 127, false, fv);
            case P_HEAVY: return new VelocityCurve(0.55f, 127, 1, 127, false, fv);
            case P_FIXED: return new VelocityCurve(keep != null ? keep.curve : 0f,
                    keep != null ? keep.fullForceAt : 127, keep != null ? keep.softest : 1,
                    keep != null ? keep.loudest : 127, true, fv);
            default: return new VelocityCurve(0f, 127, 1, 127, false, fv);
        }
    }

    /** Which preset these settings match, or P_CUSTOM. */
    int matchingPreset() {
        if (fixed) return P_FIXED;
        for (int p = P_LIGHT; p <= P_HEAVY; p++) {
            VelocityCurve q = preset(p, this);
            if (Math.abs(q.curve - curve) < 0.02f && q.fullForceAt == fullForceAt
                    && q.softest == softest && q.loudest == loudest) return p;
        }
        return P_CUSTOM;
    }

    float gamma() { return (float) Math.pow(2, 1.6 * curve); }

    int map(int raw) {
        if (fixed) return fixedValue;
        float x = Math.min(1f, Math.max(0f, raw / (float) fullForceAt));
        float y = (float) Math.pow(x, gamma());
        return clamp(Math.round(softest + (loudest - softest) * y), 1, 127);
    }

    VelocityCurve withCurve(float c) { return new VelocityCurve(c, fullForceAt, softest, loudest, fixed, fixedValue); }
    VelocityCurve withFullForceAt(int v) { return new VelocityCurve(curve, v, softest, loudest, fixed, fixedValue); }
    VelocityCurve withSoftest(int v) { return new VelocityCurve(curve, fullForceAt, v, Math.max(v, loudest), fixed, fixedValue); }
    VelocityCurve withLoudest(int v) { return new VelocityCurve(curve, fullForceAt, Math.min(v, softest), v, fixed, fixedValue); }
    VelocityCurve withFixed(boolean f) { return new VelocityCurve(curve, fullForceAt, softest, loudest, f, fixedValue); }
    VelocityCurve withFixedValue(int v) { return new VelocityCurve(curve, fullForceAt, softest, loudest, fixed, v); }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}

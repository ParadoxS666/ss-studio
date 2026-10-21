"""
Builds assets/piano.pk from the FluidR3_GM acoustic grand piano renders
(gleitz/midi-js-soundfonts, FluidR3 is CC BY 3.0).

Every 3rd note is kept (max pitch shift in the app is +/-1 semitone).
Each sample gets a crossfaded, level-flattened tail loop so held notes keep
decaying naturally past the end of the 3 s recording.

Each sample's actual pitch is measured (YIN) and stored, so the app can play
every note at exact equal-temperament pitch (the renders are up to ~30 cents sharp).

File format (little endian):
  magic 'PKPN', u32 version=2, u32 count
  count x { u8 root, u8 pad[3], u32 sampleRate, u32 frames,
            u32 loopStart, u32 loopEnd, f32 tailDecayDbPerSec, u32 offsetFrames,
            f32 tuneCents (how sharp the recording is) }
  then interleaved stereo int16 frames for all samples
"""
import struct
import subprocess
import sys

import numpy as np

SRC = sys.argv[1]
OUT = sys.argv[2]
SR = 44100
NAMES = ["C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B"]


def note_name(m):
    return f"{NAMES[m % 12]}{m // 12 - 1}"


def load(m):
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", f"{SRC}/{note_name(m)}.mp3",
                          "-f", "f32le", "-acodec", "pcm_f32le", "-ar", str(SR), "-"],
                         capture_output=True, check=True).stdout
    return np.frombuffer(raw, dtype=np.float32).reshape(-1, 2).astype(np.float64)


def yin_hz(mono, sr, f_expected):
    """YIN pitch over a window after the attack, searching +/- 1.5 semitones."""
    start = int(0.12 * sr)
    n = min(int(0.25 * sr), len(mono) - start - int(sr / 20))
    x = mono[start:start + n + int(sr / 20)].astype(np.float64)
    lo = int(sr / (f_expected * 2 ** (1.5 / 12))) - 1
    hi = int(sr / (f_expected * 2 ** (-1.5 / 12))) + 2
    taus = np.arange(1, hi + 2)
    d = np.array([np.sum((x[:n] - x[t:t + n]) ** 2) for t in taus])
    cm = d * taus / np.maximum(np.cumsum(d), 1e-20)
    seg = cm[lo - 1:hi]
    i = int(np.argmin(seg)) + lo - 1
    y0, y1, y2 = cm[i - 1], cm[i], cm[i + 1]
    den = y0 - 2 * y1 + y2
    tau = taus[i] + (0.5 * (y0 - y2) / den if den != 0 else 0)
    return sr / tau


def env_db(x, win):
    m = np.sqrt(np.mean(x ** 2, axis=1))
    n = len(m) // win
    e = np.sqrt(np.mean(m[: n * win].reshape(n, win) ** 2, axis=1))
    return 20 * np.log10(e + 1e-12)


roots = list(range(22, 109, 3))  # A#0 .. C8 (covers 21..108 with +/-1)
samples = []
global_peak = 0.0
for m in roots:
    x = load(m)
    a = np.max(np.abs(x), axis=1)
    pk = a.max()
    onset = int(np.argmax(a > pk * 0.02))
    start = max(0, onset - 44)  # keep ~1 ms pre-roll
    x = x[start:]
    global_peak = max(global_peak, np.max(np.abs(x)))
    samples.append((m, x))

gain = 0.89 / global_peak
WIN = 882  # 20 ms
table = []
datas = []
offset = 0
for m, x in samples:
    x = x * gain
    e = env_db(x, WIN)
    pk_db = e.max()
    # trim once the note is 55 dB down (keep at least 0.7 s)
    below = np.where(e < pk_db - 55)[0]
    below = below[below * WIN > 0.7 * SR]
    end = len(x) if len(below) == 0 else int(below[0] * WIN)
    end = min(end, len(x) - 64)
    # fade the very end of the source a little (mp3 tail padding)
    x = x[:end].copy()

    # loop length: ~0.25 s, a whole number of periods
    f0 = 440.0 * 2 ** ((m - 69) / 12)
    period = SR / f0
    k = max(1, round(0.25 * SR / period))
    loop_len = int(round(k * period))
    loop_len = min(loop_len, end // 3)
    xf = min(2048, loop_len // 2)
    loop_end = end
    loop_start = loop_end - loop_len

    # decay rate over the last ~0.8 s
    t0 = max(0, loop_start - int(0.8 * SR))
    seg = env_db(x[t0:loop_end], WIN)
    tt = np.arange(len(seg)) * WIN / SR
    slope = np.polyfit(tt, seg, 1)[0] if len(seg) > 3 else -10.0
    rate_db = float(np.clip(-slope, 2.0, 80.0))
    rate_np = rate_db / 8.685889638

    # flatten the tail region (crossfade source + loop) to the level at loop_start
    idx = np.arange(loop_start - xf, loop_end)
    x[idx] *= np.exp(rate_np * (idx - loop_start) / SR)[:, None]
    # crossfade the end of the loop into the material just before loop_start
    w = 0.5 - 0.5 * np.cos(np.pi * np.arange(xf) / xf)
    a_idx = np.arange(loop_end - xf, loop_end)
    b_idx = np.arange(loop_start - xf, loop_start)
    x[a_idx] = x[a_idx] * (1 - w)[:, None] + x[b_idx] * w[:, None]

    pcm = np.clip(np.round(x * 32767), -32768, 32767).astype("<i2")
    f0 = 440.0 * 2 ** ((m - 69) / 12)
    tune = 1200 * np.log2(yin_hz(x.mean(axis=1), SR, f0) / f0)
    tune = float(np.clip(tune, -60, 60))
    table.append((m, SR, len(pcm), loop_start, loop_end, rate_db, offset, tune))
    datas.append(pcm.tobytes())
    offset += len(pcm)
    print(f"{note_name(m):>4} frames={len(pcm):6d} loop={loop_start}-{loop_end} "
          f"decay={rate_db:5.1f} dB/s tune={tune:+5.1f}c")

with open(OUT, "wb") as f:
    f.write(b"PKPN" + struct.pack("<II", 2, len(table)))
    for m, sr, n, ls, le, rd, off, tune in table:
        f.write(struct.pack("<B3xIIIIfIf", m, sr, n, ls, le, rd, off, tune))
    for d in datas:
        f.write(d)
print("total frames", offset, "bytes", offset * 4)

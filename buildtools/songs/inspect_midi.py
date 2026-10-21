#!/usr/bin/env python3
"""Prints tracks, ranges, lengths and opening notes of compiled MIDI files."""
import sys

import mido

NAMES = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]


def name(n):
    return f"{NAMES[n % 12]}{n // 12 - 1}"  # scientific: 60 = C4


def notes_of(track):
    t, out, open_ = 0, [], {}
    for msg in track:
        t += msg.time
        if msg.type == "note_on" and msg.velocity > 0:
            open_.setdefault(msg.note, []).append(t)
        elif msg.type in ("note_off", "note_on"):
            if open_.get(msg.note):
                st = open_[msg.note].pop(0)
                out.append((st, t - st, msg.note))
    return sorted(out)


for path in sys.argv[1:]:
    mf = mido.MidiFile(path)
    q = mf.ticks_per_beat
    tempos = [(m.tempo) for tr in mf.tracks for m in tr if m.type == "set_tempo"]
    ts = [(m.numerator, m.denominator) for tr in mf.tracks for m in tr if m.type == "time_signature"]
    keys = [m.key for tr in mf.tracks for m in tr if m.type == "key_signature"]
    print(f"\n### {path.split('/')[-1]}  tpq={q} tempo={[round(mido.tempo2bpm(x)) for x in tempos[:3]]} ts={ts[:2]} key={keys[:2]}")
    for i, tr in enumerate(mf.tracks):
        ns = notes_of(tr)
        if not ns:
            continue
        nm = next((m.name for m in tr if m.type == "track_name"), "")
        prog = next((m.program for m in tr if m.type == "program_change"), None)
        lo, hi = min(n[2] for n in ns), max(n[2] for n in ns)
        end = max(n[0] + n[1] for n in ns) / q
        head = " ".join(f"{name(n[2])}@{n[0] / q:g}" for n in ns[:14])
        print(f"  track {i} '{nm}' prog={prog} notes={len(ns)} range={name(lo)}-{name(hi)} len={end:g}q")
        print(f"     {head}")

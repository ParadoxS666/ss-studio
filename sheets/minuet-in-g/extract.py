"""Minuet in G (BWV Anh. 114) from the Mutopia MIDI, fitted to the MPK's 25 keys exactly
as SS Studio's Learn mode does it (right hand down an octave, left hand as written, notes
outside 48..72 folded in by octaves). Writes minuet.json for the printable sheet."""
import json, sys, collections, mido

mid, fit_txt, out = sys.argv[1], sys.argv[2], sys.argv[3]
m = mido.MidiFile(mid)
tpb = m.ticks_per_beat
BAR = 3 * tpb

def notes(ti):
    tick = 0; on = {}; res = []
    for e in m.tracks[ti]:
        tick += e.time
        if e.type == 'note_on' and e.velocity > 0: on.setdefault(e.note, []).append(tick)
        elif e.type in ('note_off', 'note_on') and on.get(e.note):
            s = on[e.note].pop(0); res.append((s, tick, e.note))
    return sorted(res)

SHIFT = {'R': -12, 'L': 0}
def fold(v):
    while v < 48: v += 12
    while v > 72: v -= 12
    return v

PC = ["C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B"]
def name(n): return PC[n % 12] + str(n // 12 - 2)     # Ableton/Akai names, as on the app's keys

all_notes = []
for hand, ti in (('R', 1), ('L', 2)):
    for s, e, n in notes(ti):
        k = n + SHIFT[hand]
        f = fold(k)
        all_notes.append(dict(hand=hand, start=s, end=e, orig=n, key=f, moved=(f != k)))

# check against the app (Both hands): same keys at the same times, after the app's
# "one note per key at a time" merge
app = collections.Counter()
for line in open(fit_txt):
    p = line.split()
    if len(p) == 3 and p[0][0].isdigit():
        app[(round(float(p[0]) / 0.5 * 2) / 2, int(p[2]))] += 1
mine = collections.Counter()
seen = set()
for x in sorted(all_notes, key=lambda x: (x['start'], x['hand'] != 'L')):
    k = (x['start'], x['key'])
    if k in seen: x['shared'] = True; continue
    seen.add(k)
    mine[(round(x['start'] / tpb * 2) / 2, x['key'])] += 1
for x in all_notes: x.setdefault('shared', False)
# grace note: snap app time comparison loosely
diff = (app - mine) + (mine - app)
print("app vs sheet differences:", len(diff), list(diff.items())[:6])

# printed form: bars 1-16 (A) and 33-48 (B) of the unfolded MIDI = bars 1-32 of the score
bars = []
for printed, src in list(enumerate(range(0, 16), 1)) + list(enumerate(range(32, 48), 17)):
    b0 = src * BAR
    items = []
    for x in all_notes:
        if b0 <= x['start'] < b0 + BAR:
            items.append(dict(hand=x['hand'], beat=(x['start'] - b0) / tpb, len=(x['end'] - x['start']) / tpb,
                              key=x['key'], name=name(x['key']), moved=x['moved'], shared=x['shared']))
    items.sort(key=lambda i: (i['hand'], i['beat'], -i['key']))
    bars.append(dict(n=printed, notes=items))

used = sorted({x['key'] for x in all_notes})
json.dump(dict(bars=bars, used=used, bpm=140), open(out, 'w'), ensure_ascii=False, indent=1)
print("bars", len(bars), "keys used", [name(k) for k in used])
print("moved:", sum(1 for b in bars for n in b['notes'] if n['moved']), "shared:", sum(1 for b in bars for n in b['notes'] if n['shared']))
print("max chord size per hand:", max(collections.Counter((b['n'], n['hand'], n['beat']) for b in bars for n in b['notes']).values()))
for b in bars[:4] + bars[6:8]:
    print(b['n'], [(n['hand'], n['beat'], n['len'], n['name']) for n in b['notes']])

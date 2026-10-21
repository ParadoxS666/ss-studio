"""Lays out the printable Minuet sheet (A4 landscape pages) and prints compact data
arrays for the canvas artboards."""
import json, sys
d = json.load(open(sys.argv[1]))
bars = {b['n']: b for b in d['bars']}

PAD_X, GUTTER, BAR_GAP = 40, 26, 10
CW = 1123 - 2 * PAD_X
BAR_W = (CW - GUTTER - 3 * BAR_GAP) / 4
INNER = 6
BEAT = (BAR_W - 2 * INNER) / 3
COUNT_H, LANE_GAP, ROW_GAP = 18, 6, 12
CHIP1, CHIPS, SGAP = 28, 22, 2

def prep(notes):
    """Bar 7's last eighth: G2 then a quick grace B2 -> two sixteenths for reading."""
    out = []
    for n in notes:
        n = dict(n)
        if abs(n['len'] - 0.385) < 0.01: n['len'] = 0.25
        if abs(n['len'] - 0.112) < 0.01: n['beat'] = 2.75; n['len'] = 0.25
        out.append(n)
    return out

def slots(notes):
    """Stack notes that sound at once (chords, held notes): higher notes on top."""
    res = []
    for i, a in enumerate(notes):
        s = 0
        for j, b in enumerate(notes):
            if i == j: continue
            overlap = a['beat'] < b['beat'] + b['len'] - 1e-6 and b['beat'] < a['beat'] + a['len'] - 1e-6
            if overlap and (b['key'] > a['key'] or (b['key'] == a['key'] and j < i)): s += 1
        res.append(s)
    for i, a in enumerate(notes):           # no two overlapping notes in one slot
        for j, b in enumerate(notes):
            if i < j and res[i] == res[j] and a['beat'] < b['beat'] + b['len'] - 1e-6 and b['beat'] < a['beat'] + a['len'] - 1e-6:
                raise SystemExit("slot clash")
    return res

def lane_h(k): return CHIP1 if k <= 1 else k * CHIPS + (k - 1) * SGAP

def page(bar_numbers, top):
    frames, lanes, grid, counts, nums, rowlab, chips = [], [], [], [], [], [], []
    y = top
    for r in range(0, len(bar_numbers), 4):
        row = bar_numbers[r:r + 4]
        info = {}
        k = {'R': 1, 'L': 1}
        for bn in row:
            for hand in 'RL':
                ns = prep([n for n in bars[bn]['notes'] if n['hand'] == hand])
                sl = slots(ns)
                info[(bn, hand)] = (ns, sl)
                if sl: k[hand] = max(k[hand], max(sl) + 1)
        hR, hL = lane_h(k['R']), lane_h(k['L'])
        yR = y + COUNT_H
        yL = yR + hR + LANE_GAP
        bottom = yL + hL
        rowlab.append([PAD_X, yR, hR, 'R'])
        rowlab.append([PAD_X, yL, hL, 'L'])
        for i, bn in enumerate(row):
            x = PAD_X + GUTTER + i * (BAR_W + BAR_GAP)
            frames.append([round(x, 1), y, round(BAR_W, 1), bottom - y + 4])
            nums.append([round(x, 1), y, bn])
            lanes.append([round(x + 2, 1), yR, round(BAR_W - 4, 1), hR, 0])
            lanes.append([round(x + 2, 1), yL, round(BAR_W - 4, 1), hL, 1])
            for e in range(6):
                cx = x + INNER + (e + 0.5) * BEAT / 2
                counts.append([round(cx, 1), y + 3, str(e // 2 + 1) if e % 2 == 0 else '&', 1 if e % 2 == 0 else 0])
            for b in (1, 2):
                grid.append([round(x + INNER + b * BEAT, 1), yR, bottom - yR])
            for hand, ly, lh, kk in (('R', yR, hR, k['R']), ('L', yL, hL, k['L'])):
                ns, sl = info[(bn, hand)]
                for n, s in zip(ns, sl):
                    cx0 = x + INNER + n['beat'] * BEAT + 1.5
                    w = n['len'] * BEAT - 3
                    if kk <= 1: cy, ch = ly, CHIP1
                    else: cy, ch = ly + s * (CHIPS + SGAP), CHIPS
                    chips.append([round(cx0, 1), round(cy, 1), round(w, 1), ch, n['name'],
                                  0 if hand == 'R' else 1, 1 if n['shared'] else 0, 1 if n['len'] < 0.5 else 0])
        y = bottom + 4 + ROW_GAP
    return dict(frames=frames, lanes=lanes, grid=grid, counts=counts, nums=nums, rowlab=rowlab, chips=chips, end=y)

p1 = page(list(range(1, 17)), int(sys.argv[2]))
p2 = page(list(range(17, 33)), int(sys.argv[3]))
print("page1 score ends at", p1['end'], " page2 score ends at", p2['end'], " chips", len(p1['chips']), len(p2['chips']))
print("bar width", round(BAR_W, 1), "beat", round(BEAT, 1))

# keyboard map: the MPK's 25 keys, C2..C4 (notes 48..72)
WHITE_OF = {0: 0, 2: 1, 4: 2, 5: 3, 7: 4, 9: 5, 11: 6}
BLACK_C = {1: 1 - 0.07, 3: 2 + 0.07, 6: 4 - 0.08, 8: 5, 10: 6 + 0.08}
PC = ["C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B"]
used = {'R': set(), 'L': set()}
for b in d['bars']:
    for n in b['notes']: used[n['hand']].add(n['key'])
KW, KH = float(sys.argv[4]), float(sys.argv[5])
whites, blacks = [], []
for n in range(48, 73):
    o, pc = (n - 48) // 12, n % 12
    name = PC[pc] + str(n // 12 - 2)
    if pc in WHITE_OF:
        whites.append([round((o * 7 + WHITE_OF[pc]) * KW, 1), name, 1 if n in used['R'] else 0, 1 if n in used['L'] else 0])
    else:
        c = (o * 7 + BLACK_C[pc]) * KW
        blacks.append([round(c - 0.29 * KW, 1), name, 1 if n in used['R'] else 0, 1 if n in used['L'] else 0])
json.dump(dict(p1=p1, p2=p2, whites=whites, blacks=blacks), open(sys.argv[6], 'w'), ensure_ascii=False)

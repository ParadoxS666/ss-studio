#!/usr/bin/env python3
"""
Builds app/assets/songs.pks, the Learn mode library.

Sources:
  * Mutopia Project editions (https://www.mutopiaproject.org), compiled to MIDI
    with LilyPond with repeats unfolded (compile_scores.py). Each staff/voice
    track is assigned to the right hand (R), left hand (L) or backing (O).
  * A few warm-ups written here in the compact Key Falls notation.

Times are rebuilt from the score's beats at a fixed practice-friendly tempo
(the player has a speed slider), so fermatas/ritardandos are not included.

Format (big endian, read by SongLibrary.java with DataInputStream):
  'PKSG' u16 version=1 u16 songCount
  per song: UTF id, UTF title, UTF composer, UTF group, UTF credit,
            u16 bpm, u16 beatsPerBarTimes100 (in quarter notes), i32 firstBarMs,
            i32 noteCount, noteCount x { i32 startMs, i32 durMs, u8 note, u8 hand }
  hand: 0 = right, 1 = left, 2 = backing
"""
import re
import struct
import sys

import mido

MIDI_DIR = sys.argv[1]      # compiled Mutopia MIDI (compile_scores.py output)
OUT = sys.argv[2]           # songs.pks
REPORT = sys.argv[3] if len(sys.argv) > 3 else None

HAND = {"R": 0, "L": 1, "O": 2}
NAMES = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]


def sci(n):
    return f"{NAMES[n % 12]}{n // 12 - 1}"


# --------------------------------------------------------------------------
# Compact notation (as in Key Falls): "PITCH:BEATS", "R:BEATS", "C4+E4+G4:2"
# --------------------------------------------------------------------------
def parse_pitch(s):
    m = re.fullmatch(r"([A-Ga-g])([#b]?)(-?\d)", s)
    if not m:
        raise ValueError("bad pitch " + s)
    pc = {"C": 0, "D": 2, "E": 4, "F": 5, "G": 7, "A": 9, "B": 11}[m.group(1).upper()]
    pc += {"#": 1, "b": -1, "": 0}[m.group(2)]
    return (int(m.group(3)) + 1) * 12 + pc


def seq(text, hand):
    out, beat = [], 0.0
    for tok in text.split():
        p, _, ln = tok.partition(":")
        beats = float(ln or 1)
        if p != "R":
            for pitch in p.split("+"):
                out.append((beat, beats, parse_pitch(pitch), hand))
        beat += beats
    return out, beat


def own(*parts):
    notes, total = [], []
    for text, hand in parts:
        n, b = seq(text, hand)
        notes += n
        total.append(b)
    if len(set(total)) != 1:
        raise ValueError(f"hands have different lengths: {total}")
    return notes


# --------------------------------------------------------------------------
# MIDI from LilyPond
# --------------------------------------------------------------------------
def from_midi(fname, hands):
    mf = mido.MidiFile(f"{MIDI_DIR}/{fname}")
    q = mf.ticks_per_beat
    out = []
    for ti, tr in enumerate(mf.tracks):
        hand = next((h for h, ts in hands.items() if ti in ts), None)
        t, open_ = 0, {}
        for msg in tr:
            t += msg.time
            if msg.type == "note_on" and msg.velocity > 0:
                open_.setdefault(msg.note, []).append(t)
            elif msg.type in ("note_off", "note_on") and open_.get(msg.note):
                st = open_[msg.note].pop(0)
                if hand is not None:
                    out.append((st / q, (t - st) / q, msg.note, hand))
        if hand is not None and not any(n[3] == hand for n in out):
            raise ValueError(f"{fname}: track {ti} ({hand}) has no notes")
    ts = next(((m.numerator, m.denominator) for tr in mf.tracks for m in tr if m.type == "time_signature"), (4, 4))
    return out, ts


# --------------------------------------------------------------------------
# The library
# --------------------------------------------------------------------------
G0 = "First tunes"
G1 = "Level 1 · First pieces"
G2 = "Level 2 · Building skills"
G3 = "Level 3 · Sonatinas and goal pieces"

MUT = "Notes from the Mutopia Project edition typeset by {who} ({lic})."
PD = "public domain"

SONGS = [
    # ---- first tunes (written here) ----
    dict(id="scale-c", group=G0, title="C major scale", composer="Warm-up", bpm=90, ts=(4, 4),
         credit="Both hands, one octave up and down.",
         notes=own(("C4 D4 E4 F4 G4 A4 B4 C5 B4 A4 G4 F4 E4 D4 C4:2", "R"),
                   ("C3 D3 E3 F3 G3 A3 B3 C4 B3 A3 G3 F3 E3 D3 C3:2", "L"))),
    dict(id="mary", group=G0, title="Mary Had a Little Lamb", composer="Traditional", bpm=100, ts=(4, 4),
         credit="Arranged for SS Studio.",
         notes=own(("E4 D4 C4 D4 E4 E4 E4:2 D4 D4 D4:2 E4 G4 G4:2 "
                    "E4 D4 C4 D4 E4 E4 E4 E4 D4 D4 E4 D4 C4:4", "R"),
                   ("C3:4 C3:4 G2:4 C3:4 C3:4 C3:4 G2:4 C3:4", "L"))),
    dict(id="twinkle", group=G0, title="Twinkle, Twinkle (Ah, vous dirai-je, maman)", composer="Mozart's theme",
         bpm=100, ts=(4, 4), credit="The French song Mozart wrote his variations K. 265 on. Arranged for SS Studio.",
         notes=own(("C4 C4 G4 G4 A4 A4 G4:2 F4 F4 E4 E4 D4 D4 C4:2 "
                    "G4 G4 F4 F4 E4 E4 D4:2 G4 G4 F4 F4 E4 E4 D4:2 "
                    "C4 C4 G4 G4 A4 A4 G4:2 F4 F4 E4 E4 D4 D4 C4:2", "R"),
                   ("C3:4 F3:2 C3:2 F3:2 C3:2 G2:2 C3:2 "
                    "C3:2 G2:2 C3:2 G2:2 C3:2 G2:2 C3:2 G2:2 "
                    "C3:4 F3:2 C3:2 F3:2 C3:2 G2:2 C3:2", "L"))),
    dict(id="ode-to-joy", group=G0, title="Ode to Joy", composer="Beethoven · Symphony No. 9", bpm=110, ts=(4, 4),
         credit="The full theme (A A B A). Arranged for SS Studio.",
         notes=own(("E4 E4 F4 G4 G4 F4 E4 D4 C4 C4 D4 E4 E4:1.5 D4:0.5 D4:2 "
                    "E4 E4 F4 G4 G4 F4 E4 D4 C4 C4 D4 E4 D4:1.5 C4:0.5 C4:2 "
                    "D4 D4 E4 C4 D4 E4:0.5 F4:0.5 E4 C4 D4 E4:0.5 F4:0.5 E4 D4 C4 D4 G3:2 "
                    "E4 E4 F4 G4 G4 F4 E4 D4 C4 C4 D4 E4 D4:1.5 C4:0.5 C4:2", "R"),
                   ("C3:4 G2:4 C3:4 G2:4 C3:4 G2:4 C3:4 G2:2 C3:2 "
                    "G2:4 G2:2 C3:2 G2:4 C3:2 G2:2 "
                    "C3:4 G2:4 C3:4 G2:2 C3:2", "L"))),
    dict(id="brahms-lullaby", group=G0, title="Lullaby (Wiegenlied), Op. 49 No. 4", composer="Johannes Brahms",
         bpm=80, midi="LullabyBrahms-C.mid", hands={"R": [1], "L": [2]},
         credit=MUT.format(who="Diego Guillen", lic=PD) + " Simplified, in C major."),
    dict(id="greensleeves", group=G0, title="Greensleeves", composer="Traditional (16th century)",
         bpm=120, midi="Greensleaves.mid", voices=True, hands={"R": [2], "L": [3]},
         credit=MUT.format(who="Aaron Fontaine", lic=PD)),

    # ---- level 1 ----
    dict(id="minuet-g", group=G1, title="Minuet in G major, BWV Anh. 114", composer="Christian Petzold",
         bpm=120, midi="anna-magdalena-04.mid", hands={"R": [1], "L": [2]},
         credit="From the Notebook for Anna Magdalena Bach. " + MUT.format(who="the Mutopia volunteers", lic=PD)),
    dict(id="minuet-gm", group=G1, title="Minuet in G minor, BWV Anh. 115", composer="Christian Petzold",
         bpm=120, midi="anna-magdalena-05.mid", hands={"R": [1], "L": [2]},
         credit="From the Notebook for Anna Magdalena Bach. " + MUT.format(who="the Mutopia volunteers", lic=PD)),
    dict(id="musette-d", group=G1, title="Musette in D major, BWV Anh. 126", composer="Anonymous",
         bpm=100, midi="anna-magdalena-22.mid", hands={"R": [1], "L": [2]},
         credit="From the Notebook for Anna Magdalena Bach. " + MUT.format(who="the Mutopia volunteers", lic=PD)),
    dict(id="mozart-k2", group=G1, title="Minuet in F major, K. 2", composer="Wolfgang Amadeus Mozart",
         bpm=100, midi="menuet_k2.mid", hands={"R": [1], "L": [2]},
         credit="Written at age six. " + MUT.format(who="Nicolas Morant", lic=PD)),
    dict(id="schumann-melody", group=G1, title="Melody, Op. 68 No. 1", composer="Robert Schumann",
         bpm=80, midi="schumann-op68-01-melodie.mid", hands={"R": [1], "L": [2]},
         credit="Album for the Young. " + MUT.format(who="Philippe Hézaine", lic="CC BY-SA 2.5")),
    dict(id="schumann-march", group=G1, title="Soldiers' March, Op. 68 No. 2", composer="Robert Schumann",
         bpm=100, midi="schumann-op68-02-marche-militaire.mid", hands={"R": [1], "L": [2]},
         credit="Album for the Young. " + MUT.format(who="Philippe Hézaine", lic="CC BY-SA 2.5")),
    dict(id="schumann-humming", group=G1, title="Humming Song, Op. 68 No. 3", composer="Robert Schumann",
         bpm=72, midi="schumann-op68-03-chanson-fredonnee.mid", hands={"R": [1], "L": [2]},
         credit="Album for the Young. " + MUT.format(who="Philippe Hézaine", lic="CC BY-SA 3.0")),
    dict(id="old-french-song", group=G1, title="Old French Song, Op. 39 No. 16", composer="Pyotr Ilyich Tchaikovsky",
         bpm=72, midi="16OldFrenchSong.mid", hands={"R": [1], "L": [2]},
         credit="Album for the Young. " + MUT.format(who="David McNamara", lic=PD)),
    dict(id="burgmuller-candour", group=G1, title="Candour, Op. 100 No. 1", composer="Friedrich Burgmüller",
         bpm=100, midi="25EF-01.mid", hands={"R": [1], "L": [2]},
         credit="25 Easy and Progressive Studies. " + MUT.format(who="Bas Wassink", lic=PD)),

    # ---- level 2 ----
    dict(id="bach-bwv939", group=G2, title="Little Prelude in C major, BWV 939", composer="Johann Sebastian Bach",
         bpm=72, midi="bwv-939.mid", hands={"R": [1], "L": [2]},
         credit=MUT.format(who="Hajo Dezelski", lic="CC BY-SA 3.0")),
    dict(id="schumann-farmer", group=G2, title="The Happy Farmer, Op. 68 No. 10", composer="Robert Schumann",
         bpm=100, midi="schumann-op68-10-gai-laboureur.mid", hands={"R": [1], "L": [2]},
         credit="The tune is in the left hand. Album for the Young. "
                + MUT.format(who="Philippe Hézaine", lic="CC BY-SA 2.5")),
    dict(id="schumann-horseman", group=G2, title="The Wild Horseman, Op. 68 No. 8", composer="Robert Schumann",
         bpm=150, midi="schumann-op68-08-cavalier-sauvage.mid", hands={"R": [1], "L": [2]},
         credit="Album for the Young. " + MUT.format(who="Philippe Hézaine", lic="CC BY-SA 2.5")),
    dict(id="wooden-soldiers", group=G2, title="March of the Wooden Soldiers, Op. 39 No. 5",
         composer="Pyotr Ilyich Tchaikovsky", bpm=120, midi="05MarchOfTheWoodenSoldiers.mid",
         hands={"R": [1], "L": [2]}, credit="Album for the Young. " + MUT.format(who="the Mutopia volunteers", lic=PD)),
    dict(id="burgmuller-arabesque", group=G2, title="Arabesque, Op. 100 No. 2", composer="Friedrich Burgmüller",
         bpm=120, midi="25EF-02.mid", hands={"R": [1], "L": [2]},
         credit="25 Easy and Progressive Studies. " + MUT.format(who="Bas Wassink", lic=PD)),
    dict(id="chopin-prelude-7", group=G2, title="Prelude in A major, Op. 28 No. 7", composer="Frédéric Chopin",
         bpm=80, midi="Chop-28-7.mid", hands={"R": [1], "L": [2]},
         credit=MUT.format(who="Magnus Lewis-Smith", lic=PD)),
    dict(id="chopin-prelude-4", group=G2, title="Prelude in E minor, Op. 28 No. 4", composer="Frédéric Chopin",
         bpm=60, midi="Chop-28-4.mid", hands={"R": [1], "L": [2]},
         credit=MUT.format(who="Magnus Lewis-Smith", lic=PD)),
    dict(id="fur-elise", group=G2, title="Für Elise, WoO 59", composer="Ludwig van Beethoven",
         bpm=66, midi="fur_Elise_WoO59.mid", hands={"R": [1], "L": [2]},
         credit="The complete piece. " + MUT.format(who="Stelios Samelis", lic=PD)),
    dict(id="bach-prelude-c", group=G2, title="Prelude in C major, BWV 846", composer="Johann Sebastian Bach",
         bpm=66, midi="wtk1-prelude1.mid", hands={"R": [1], "L": [2]},
         credit="The Well-Tempered Clavier, Book I. " + MUT.format(who="Tobias Erbsland", lic=PD)),
    dict(id="gymnopedie-1", group=G2, title="Gymnopédie No. 1", composer="Erik Satie",
         bpm=66, midi="gymnopedie_1.mid", voices=True, hands={"R": [1], "L": [2, 3, 4, 5]},
         credit=MUT.format(who="Evin Robertson", lic=PD)),

    # ---- level 3 ----
    dict(id="clementi-36-1-i", group=G3, title="Sonatina in C, Op. 36 No. 1 · I. Allegro", composer="Muzio Clementi",
         bpm=120, midi="sonatina-1.mid", hands={"R": [1], "L": [2]},
         credit=MUT.format(who="Brian D. Rude", lic=PD)),
    dict(id="clementi-36-1-ii", group=G3, title="Sonatina in C, Op. 36 No. 1 · II. Andante", composer="Muzio Clementi",
         bpm=80, midi="sonatina-1-1.mid", hands={"R": [1], "L": [2]},
         credit=MUT.format(who="Brian D. Rude", lic=PD)),
    dict(id="clementi-36-1-iii", group=G3, title="Sonatina in C, Op. 36 No. 1 · III. Vivace", composer="Muzio Clementi",
         bpm=100, midi="sonatina-1-2.mid", hands={"R": [1], "L": [2]},
         credit=MUT.format(who="Brian D. Rude", lic=PD)),
    dict(id="kuhlau-20-1-i", group=G3, title="Sonatina in C, Op. 20 No. 1 · I. Allegro", composer="Friedrich Kuhlau",
         bpm=120, midi="sonatine-1-allegro.mid", hands={"R": [1], "L": [2]},
         credit=MUT.format(who="Bas Wassink", lic=PD)),
    dict(id="mozart-k545-i", group=G3, title="Sonata in C, K. 545 · I. Allegro", composer="Wolfgang Amadeus Mozart",
         bpm=120, midi="K545-1.mid", hands={"R": [1], "L": [2]},
         credit="The \"Sonata facile\". " + MUT.format(who="Alejandro Sierra", lic="CC BY-SA 3.0")),
    dict(id="moonlight-i", group=G3, title="Moonlight Sonata, Op. 27 No. 2 · I. Adagio sostenuto",
         composer="Ludwig van Beethoven", bpm=54, midi="moonlight.mid", hands={"R": [1], "L": [2]},
         credit=MUT.format(who="Stewart Holmes", lic="CC BY-SA 2.5")),
    dict(id="rondo-alla-turca", group=G3, title="Rondo alla Turca, K. 331", composer="Wolfgang Amadeus Mozart",
         bpm=116, midi="KV331_3_RondoAllaTurca.mid", hands={"R": [1], "L": [2]},
         credit="Third movement of the Sonata in A, K. 331. " + MUT.format(who="Rune Zedeler and Chris Sawer", lic=PD)),
]


def build(song):
    if "midi" in song:
        notes, ts = from_midi(song["midi"], song["hands"])
    else:
        notes, ts = song["notes"], song["ts"]
    spb = 60.0 / song["bpm"]
    first = min(n[0] for n in notes)
    rows = []
    for beat, beats, note, hand in notes:
        start = (beat - first) * spb
        dur = max(0.06, beats * spb * 0.93)
        rows.append((round(start * 1000), round(dur * 1000), note, HAND[hand]))
    rows.sort(key=lambda r: (r[0], r[2]))
    bar_q = ts[0] * 4 / ts[1]
    # first bar line: notes start at beat 0 of the score; LilyPond starts at the bar
    # unless there is a pickup, which we detect as the first note not on a bar line
    first_bar_ms = round(((-first) % bar_q) * spb * 1000)
    return rows, bar_q, first_bar_ms


def main():
    blob = bytearray(b"PKSG" + struct.pack(">HH", 1, len(SONGS)))
    report = []

    def utf(s):
        b = s.encode("utf-8")
        return struct.pack(">H", len(b)) + b

    for s in SONGS:
        rows, bar_q, first_bar_ms = build(s)
        blob += utf(s["id"]) + utf(s["title"]) + utf(s["composer"]) + utf(s["group"]) + utf(s["credit"])
        blob += struct.pack(">HHii", s["bpm"], round(bar_q * 100), first_bar_ms, len(rows))
        for st, du, n, h in rows:
            blob += struct.pack(">iiBB", st, du, n, h)
        dur_s = max(r[0] + r[1] for r in rows) / 1000
        by_hand = {h: [r for r in rows if r[3] == h] for h in (0, 1, 2)}
        line = f"{s['group'][:7]:7s} | {s['title'][:52]:52s} | {dur_s:6.1f}s"
        for h, label in ((0, "R"), (1, "L"), (2, "O")):
            rs = by_hand[h]
            if rs:
                line += f" | {label} {len(rs):4d} {sci(min(r[2] for r in rs))}-{sci(max(r[2] for r in rs))}"
        report.append(line)
        first_r = " ".join(sci(r[2]) for r in by_hand[0][:10])
        report.append(f"          R starts: {first_r}")
    open(OUT, "wb").write(blob)
    text = "\n".join(report) + f"\n\n{len(SONGS)} songs, {len(blob)} bytes\n"
    print(text)
    if REPORT:
        open(REPORT, "w").write(text)


if __name__ == "__main__":
    main()

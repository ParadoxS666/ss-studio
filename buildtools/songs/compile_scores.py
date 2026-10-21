#!/usr/bin/env python3
"""
Compiles Mutopia Project LilyPond scores to MIDI with repeats unfolded
(the way the piece is actually played), for the Learn mode song library.

Usage: compile_scores.py <src-dir with one folder per score> <out-dir>
Each folder's main .ly is the one containing \\score; files are first
updated to the installed LilyPond version with convert-ly.
"""
import os
import re
import shutil
import subprocess
import sys

SRC, OUT = sys.argv[1], sys.argv[2]
os.makedirs(OUT, exist_ok=True)

# a few folders hold several pieces/movements: pick the file explicitly
MAIN = {
    "moonlight": "moonlight1-let.ly",
    "KV331_3_RondoAllaTurca": "KV331_3_RondoAllaTurca.ly",
    "Chop-28-7": "chopin_prelude_op28_no7.ly",
}


# scores that keep both hands on one staff: give every voice its own MIDI track
PER_VOICE = {"gymnopedie_1", "Greensleaves"}
VOICE_TRACKS = (' \\context { \\Staff \\remove "Staff_performer" }'
                ' \\context { \\Voice \\consists "Staff_performer" } ')


def find_main(folder):
    if os.path.basename(folder) in MAIN:
        for root, _, files in os.walk(folder):
            if MAIN[os.path.basename(folder)] in files:
                return os.path.join(root, MAIN[os.path.basename(folder)])
    cands = []
    for root, _, files in os.walk(folder):
        for f in files:
            if f.endswith(".ly"):
                p = os.path.join(root, f)
                if "\\score" in open(p, encoding="utf-8", errors="replace").read():
                    cands.append(p)
    return sorted(cands, key=len)[0] if cands else None


def skip_ws_comments(s, i):
    while i < len(s):
        if s[i].isspace():
            i += 1
        elif s.startswith("%{", i):
            j = s.find("%}", i)
            i = len(s) if j < 0 else j + 2
        elif s[i] == "%":
            j = s.find("\n", i)
            i = len(s) if j < 0 else j + 1
        else:
            break
    return i


def match_braces(s, i):
    """s[i] == '{' -> index just after the matching '}' (strings/comments aware)."""
    depth = 0
    while i < len(s):
        c = s[i]
        if c == '"':
            i += 1
            while i < len(s) and s[i] != '"':
                i += 2 if s[i] == "\\" else 1
        elif s.startswith("%{", i):
            i = s.find("%}", i) + 1
        elif c == "%":
            i = s.find("\n", i)
        elif c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    raise ValueError("unbalanced braces")


def patch(text):
    """Unfold repeats in every \\score and make sure each has a \\midi block."""
    out, pos = [], 0
    for m in re.finditer(r"\\score\s*\{", text):
        if m.start() < pos:
            continue
        start = m.end() - 1
        end = match_braces(text, start)
        body = text[start + 1:end - 1]
        # skip a leading \header { ... } inside the score
        j = skip_ws_comments(body, 0)
        if body.startswith("\\header", j):
            k = body.index("{", j)
            j = skip_ws_comments(body, match_braces(body, k))
        body = body[:j] + "\\unfoldRepeats " + body[j:]
        if "\\midi" not in body:
            body += "\n  \\midi { }\n"
        out.append(text[pos:start + 1] + body + "}")
        pos = end
    out.append(text[pos:])
    return "".join(out)


results = []
for name in sorted(os.listdir(SRC)):
    folder = os.path.join(SRC, name)
    if not os.path.isdir(folder):
        continue
    work = os.path.join(OUT, "work", name)
    shutil.rmtree(work, ignore_errors=True)
    shutil.copytree(folder, work, symlinks=True, ignore=shutil.ignore_patterns("Makefile", "*.pdf", "*.png", "*.mid", "*.midi"))
    for root, _, files in os.walk(work):
        for f in files:
            if f.endswith(".ly"):
                subprocess.run(["convert-ly", "-e", "-q", os.path.join(root, f)],
                               capture_output=True)
    main = find_main(work)
    if not main:
        results.append((name, "no \\score found"))
        continue
    src = patch(open(main, encoding="utf-8", errors="replace").read())
    if name in PER_VOICE:
        src = re.sub(r"\\midi\s*\{", lambda m: m.group(0) + VOICE_TRACKS, src)
    open(main, "w", encoding="utf-8").write(src)
    r = subprocess.run(["lilypond", "-dno-point-and-click", "-dno-print-pages", "-dmidi-extension=mid",
                        "--loglevel=ERROR", "-o", os.path.join(OUT, name), main],
                       capture_output=True, text=True, cwd=os.path.dirname(main))
    mids = sorted(f for f in os.listdir(OUT) if f.startswith(name) and f.endswith(".mid"))
    status = "ok " + ",".join(mids) if mids else "FAILED: " + (r.stderr.strip().splitlines() or ["?"])[-1][:160]
    results.append((name, status))

for name, status in results:
    print(f"{name:40s} {status}")

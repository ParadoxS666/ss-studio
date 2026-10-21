# SS Studio

A focused piano app for an Akai MPK mini IV (or any class-compliant USB MIDI keyboard) on a Pixel phone. Plug in the keyboard, pick a sound and play, or open **Learn** to practise classical pieces with falling notes.

## Download

- **[`releases/SSStudio-1.7.1.apk`](releases/SSStudio-1.7.1.apk): the current version.** It installs over any earlier SS Studio and keeps your settings.
- `releases/PocketKeys-1.0-first-version.apk`: the very first build, from before the app was renamed. Kept for reference only. It installs as a separate app.
- **[Minuet in G, key by key](sheets/minuet-in-g/README.md):** the Minuet written out as key names for the MPK's 25 keys, for players who don't read sheet music yet.

## Install

1. Copy `SSStudio-1.7.1.apk` to the phone (or download it there) and tap it.
2. If asked, allow your browser or Files app to "Install unknown apps". Play Protect may ask you to scan the app first; that's normal for apps from outside the Play Store.
3. Open **SS Studio** and plug the MPK into the phone's USB-C port. It connects on its own, with no USB pop-up or permission needed.

The app requests no permissions at all: no internet, storage, microphone or location.

## What's in it

- **9 sounds:** Grand Piano (multisampled, every sample retuned to exact pitch), Electric Piano, Drawbar Organ, Strings, Warm Pad, Synth Lead, Synth Bass, Vibraphone and Harp Pluck.
  - Every sound plays exactly the pitch of the key you press, checked by an automated pitch test (all within ±6 cents).
- **The screen shows your MPK's 25 keys, and they play exactly what the MPK sends, like Ableton Live.**
  - The on-screen keyboard is the MPK mini's own layout: 25 keys, C to C. The bar above it shows which notes they play (C2 – C4 at the MPK's default OCT 0).
  - The app never transposes. The MPK applies its OCT buttons, KTrans, Scales and Chords modes inside the keyboard and sends the resulting note numbers on its MIDI port (Port 1).
  - The MPK sends nothing when you press OCT, so the 25 keys on screen move an octave as soon as you play a note outside them. If they ever don't line up with the MPK, tap ‹ or › on the bar. Pressing both OCT buttons on the MPK resets it to OCT 0.
  - The **Playing** panel shows the held notes by name (for example "C3  E3  G3"), plus the last note's MIDI number and velocity.
  - Note names follow Ableton, Akai and Logic: middle C (note 60) is **C3**, with sharps (C♯, D♯ …). Settings → Note names can switch to scientific naming (C4).
  - Only the MPK's MIDI port plays notes. Its DAW, Plugin and Software Control ports carry buttons, so those never trigger notes.
- **Switching sounds from the keyboard:**
  - Press the **−/+ buttons next to the MPK's screen** (or any other button). SS Studio pops up "Button pressed on your MPK" and asks whether it should go to the previous or next sound. You choose once and it's remembered. These buttons report on the MPK's Software Control USB port, which SS Studio now listens to. "Ignore" stops it asking about a button; Settings → Clear mappings brings the questions back.
  - To turn the pop-up off altogether, tap **Stop asking** on it, or switch off **Settings → Keyboard buttons → Ask what new buttons do**. Buttons you already mapped keep working.
  - Pads 1–8 pick the first eight sounds (Settings → Drum pads → Pick sound). Bank B pad 1 picks the ninth.
  - Program Change messages pick sounds too (SHIFT + PAD 2 on the MPK).
  - Any pad or button that does send MIDI can be taught as **Sound −/+** (pad note, CC or SysEx). Piano keys can never be taught by accident.
  - The **MIDI monitor** in Settings shows exactly what each button sends.
- **Keyboard controls:** sustain pedal (CC64), pitch bend (±2 semitones) and the mod wheel (vibrato).
- **Key velocity, full control:** a live curve graph plus Light / Normal / Heavy / Fixed presets and these sliders:

  | Slider | What it sets |
  | --- | --- |
  | Curve | Lighter or heavier response |
  | Full volume at | How hard you must hit to reach the loudest note |
  | Softest note | Output for the gentlest touch |
  | Loudest note | Output at full force |
  | Dynamic range | dB from softest to loudest |
  | Fixed level | The velocity sent in Fixed mode |
- **Reverb amount.**
- **Live display:** which keys are held, and the measured sound delay for the current output.

## Learn mode

Tap **▶ Learn** at the top of the main screen. It's the "Key Falls" trainer, built into the app and played with the MPK.

- **Made for 25 keys.** The screen shows only your MPK's 25 keys, and every piece is fitted onto them: each hand moves by whole octaves to where most of its notes fit, so melodies keep their shape, and the few notes that still don't fit are folded in by an octave. About 94% of notes fit without folding when you play one hand, and 91% with both hands. With both hands, the left hand always sits below the right. The hand the app plays for you moves by the same octave, so the harmony stays together.
- **Falling notes** land on the key to press. Green notes are the right hand and blue the left; names on the notes show the note. Only the hands you're learning are drawn.
- **Three modes:**
  - **Wait:** the music stops at each note or chord until you play it.
  - **Play:** the notes keep falling, and each one is graded Perfect, Great, Good or Miss.
  - **Listen:** the app plays the piece for you.
- **Hands:** Right, Left or Both. By default only your own keys make a sound. Turn on **Other hand** to have the app play the hand you're not playing along with you. In Wait mode it comes in with your keys and never plays ahead of you.
- **Practice tools:**
  - Speed from 25% to 150% in 5% steps (tap the number to go back to 100%).
  - **Any octave** also accepts the right note on another of your keys.
  - **A / B** loop a passage: tap A at its start and B at its end, and B again to clear.
  - Drag the timeline to jump anywhere.
- **Scores:** points, streak and multiplier (up to ×4), accuracy and 1–5 stars. Your best score is kept per song, mode and hand, and stars show in the song list. Runs where you jumped or looped count as practice and aren't saved as a best.
- **Your own songs:** Songs → **Open a MIDI file…** imports any .mid file. It's kept in the list, with × to remove it.
- Notes are timed at the moment you press the key, and the backing is sent early by the measured output delay, so Bluetooth latency doesn't count against you.

### The song library (32 pieces, easiest first)

| Level | Pieces |
| --- | --- |
| First tunes | C major scale · Mary Had a Little Lamb · Twinkle, Twinkle (Mozart's theme) · Ode to Joy · Brahms's Lullaby · Greensleeves |
| Level 1 · First pieces | Petzold: Minuets in G major and G minor (BWV Anh. 114, 115) · Musette in D (BWV Anh. 126) · Mozart: Minuet in F, K. 2 · Schumann: Melody, Soldiers' March, Humming Song (Op. 68 Nos. 1–3) · Tchaikovsky: Old French Song · Burgmüller: Candour |
| Level 2 · Building skills | Bach: Little Prelude BWV 939 · Schumann: The Happy Farmer, The Wild Horseman · Tchaikovsky: March of the Wooden Soldiers · Burgmüller: Arabesque · Chopin: Preludes in A major and E minor (Op. 28 Nos. 7, 4) · Beethoven: Für Elise (complete) · Bach: Prelude in C, BWV 846 · Satie: Gymnopédie No. 1 |
| Level 3 · Sonatinas and goal pieces | Clementi: Sonatina Op. 36 No. 1 (all three movements) · Kuhlau: Sonatina Op. 20 No. 1, I · Mozart: Sonata K. 545, I · Beethoven: Moonlight Sonata, I · Mozart: Rondo alla Turca |

Each piece plays at a practice tempo; use the speed control to go slower or faster.

## Latency with Sony WH-1000XM6

The app's own delay is a few milliseconds:

- It renders audio at the phone's native rate in two-burst buffers.
- It uses Android's low-latency path.
- It requests the "game" audio context, which selects the low-latency mode on LE Audio.

**Bluetooth itself adds most of the delay:**

| Connection | Typical delay |
| --- | --- |
| Classic Bluetooth (AAC/LDAC) | 150 ms or more |
| LE Audio | Much less |

**To get the lowest delay:**

1. In the **Sony Sound Connect** app, turn on **LE Audio**.
2. On the Pixel, open the XM6 in Bluetooth settings and turn on **LE audio**.
3. Check the delay readout on the main screen. It updates live.

Wired is always fastest. A USB-C hub with a headphone jack lets you plug in both the MPK and the XM6's cable.

## Version history

| Version | What changed |
| --- | --- |
| 1.7.1 | Learn mode makes no sound of its own unless you turn on **Other hand**. When it's on, Wait mode brings the other hand in with your keys instead of ahead of them. |
| 1.7 | Every screen shows only the MPK's 25 keys. Learn pieces are fitted onto them. The octave bar has ‹ › to line the keys up with the MPK. |
| 1.6 | Learn mode with 32 classical pieces and MIDI file import. Switch to turn off the "button pressed" pop-up. |
| 1.5 | Keys drawn exactly like Ableton's keyboard. C3 = middle C naming. Organ, Pad and Bass corrected to the right octave. |
| 1.0–1.4 | First versions (as PocketKeys, then renamed SS Studio): the 9 sounds, low-latency audio, velocity controls and sound switching from the MPK. |

## Credits

Grand Piano samples come from **FluidR3 GM** by Frank Wen, licensed CC BY 3.0. They were taken from the per-note renders in gleitz/midi-js-soundfonts (MIT). All other sounds are synthesized live.

The Learn pieces come from **The Mutopia Project** (mutopiaproject.org). Each one's typesetter and licence are shown on its intro card. The first four tunes were written for SS Studio.

- **CC BY-SA**, used with credit:
  - Schumann's Op. 68 Nos. 1, 2, 3, 8 and 10, by Philippe Hézaine.
  - Bach's BWV 939, by Hajo Dezelski.
  - Mozart's K. 545, by Alejandro Sierra.
  - Beethoven's Moonlight Sonata, by Stewart Holmes.
- **Public domain:** all the other editions.

## Building from source

The build doesn't use Gradle. It runs javac, then d8, then writes the binary manifest and resources itself, then signs with apksigner.

```
python3 buildtools/build.py <tools-dir>
```

**Requirements:**

- `<tools-dir>` must contain `android.jar` (API 34), `d8.jar` and `apksigner.jar`.
- JDK 17 or newer.
- To regenerate `app/assets/piano.pk` from the FluidR3 MP3s with `buildtools/prep_piano.py`, you also need Python with numpy and ffmpeg.

**Notes:**

- The first build creates a signing key in `signing/`. Keep it to install updates over an existing copy. It is deliberately not in this repository: a build from a fresh clone makes a new key, and the phone will then ask you to uninstall the old app before installing.
- `sh buildtools/run_tests.sh` runs every desktop test (no phone needed):
  - `SynthTest`: renders every sound to WAV and measures CPU use.
  - `PitchTest`: checks every sound plays the key's exact pitch.
  - `KeyboardTest`: checks the on-screen key geometry and renders a preview.
  - `RouterTest` (with stubs in `test/routerstubs/`): plays MIDI through the real router.
  - `LogicTest`: velocity curves and port roles.
  - `LearnTest`: the song library, MIDI import (including thousands of corrupted files), and the Wait, Play and Listen modes, hands, loops and seeking. It also plays all 32 pieces through.
  - `LearnRender` and `StageRender`: draw the real Learn and main screens at phone size in portrait and landscape, using desktop stand-ins for Android's drawing classes in `test/shim/`. They tap through the screens and save PNGs to `build/tests/screens`.
- `buildtools/songs/` rebuilds `app/assets/songs.pks`:
  1. `compile_scores.py` compiles the Mutopia LilyPond scores listed in `mutopia_sources.txt` to MIDI with LilyPond 2.24.
  2. `make_songs.py` converts them, along with the hand-written first tunes, into the library.

**Layout:**

| Path | Contents |
| --- | --- |
| `app/src/.../Synth.java` | Voices, effects and limiter (pure Java, real-time safe) |
| `app/src/.../AudioEngine.java` | AudioTrack and render thread, latency tuning, route handling |
| `app/src/.../MidiInput.java` | USB MIDI discovery and parsing |
| `app/src/.../MidiRouter.java` | Exact note routing, button mapping, sustain and key state |
| `app/src/.../KeyboardLayout.java` | On-screen keyboard geometry (unit-tested) |
| `app/src/.../PortRoles.java` | Which USB MIDI port plays notes (unit-tested) |
| `app/src/.../StageView.java` | The main screen and settings |
| `app/src/.../LearnView.java` | Learn mode screen: falling notes, song list, cards |
| `app/src/.../LearnSession.java` | Learn mode rules: timing, grading, waiting, loops (unit-tested) |
| `app/src/.../Song.java`, `SongLibrary.java` | Song data, the built-in library reader and the MIDI file parser |
| `app/src/.../KeyFit.java` | Fits a piece onto the MPK's 25 keys (unit-tested) |
| `releases/` | Built, signed APKs |
| `sheets/minuet-in-g/` | The key-by-key Minuet: readable notation, plus the scripts that extract it from the score (`extract.py`) and lay out the printable pages (`layout.py`) |

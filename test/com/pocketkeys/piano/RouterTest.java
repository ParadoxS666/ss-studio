package com.pocketkeys.piano;
import java.util.*;
public class RouterTest {
  static class Prefs implements android.content.SharedPreferences {
    final Map<String,Object> m = new HashMap<>();
    public int getInt(String k,int d){Object o=m.get(k);return o==null?d:(Integer)o;}
    public boolean getBoolean(String k,boolean d){Object o=m.get(k);return o==null?d:(Boolean)o;}
    public float getFloat(String k,float d){Object o=m.get(k);return o==null?d:(Float)o;}
    public String getString(String k,String d){Object o=m.get(k);return o==null?d:(String)o;}
    public boolean contains(String k){return m.containsKey(k);}
    public Editor edit(){ return new Editor(){
      public Editor putInt(String k,int v){m.put(k,v);return this;} public Editor putBoolean(String k,boolean v){m.put(k,v);return this;}
      public Editor putFloat(String k,float v){m.put(k,v);return this;} public Editor putString(String k,String v){if(v==null)m.remove(k);else m.put(k,v);return this;}
      public void apply(){} }; }
  }
  static int fails=0;
  static void check(String n, boolean ok){ System.out.println((ok?"  ok   ":"  FAIL ")+n); if(!ok)fails++; }
  public static void main(String[] a){
    // settings an older version could have left behind: app octave +1, a piano key (E4) taught as "Octave +",
    // and v1.2 octave/sound bindings
    Prefs p = new Prefs();
    p.m.put("octave", 1);
    p.m.put("bind1t", 1); p.m.put("bind1c", 0); p.m.put("bind1n", 64);      // old: key E4 = Octave +
    p.m.put("bind2t", 2); p.m.put("bind2c", 0); p.m.put("bind2n", 22); p.m.put("bind2s", 0); // v1.2: CC22 = Sound -
    AudioEngine e = new AudioEngine();
    MidiRouter r = new MidiRouter(e, p);
    final int[] step = {0}; final int[] pick = {-1}; final String[] cand = {null};
    r.setUi(new MidiRouter.Ui(){ public void onKeysChanged(){} public void onLearned(int w){}
      public void onPresetFromKeyboard(int pr){pick[0]=pr;} public void onPresetStep(int d){step[0]+=d;} public void onMonitor(){} public void onControlCandidate(String c){cand[0]=c;} });

    // every one of the 25 keys, at several MPK octaves (the MPK shifts the note numbers itself)
    boolean exact = true;
    for (int hw = -2; hw <= 2; hw++) for (int k = 48; k <= 72; k++) {
      int n = k + 12 * hw;
      e.log.clear();
      r.onMidi(0, 0x90, n, 100); r.onMidi(0, 0x80, n, 0);
      if (!e.log.equals(Arrays.asList("on " + n + " 100", "off " + n))) { exact = false; System.out.println("    key " + n + " -> " + e.log); }
    }
    check("all keys at all MPK octaves play exactly the note sent (old app octave +1 ignored)", exact);
    e.log.clear(); r.onMidi(0, 0x90, 64, 90); r.onMidi(0, 0x80, 64, 0);
    check("E4 (taught as a button by an old version) plays normally", e.log.equals(Arrays.asList("on 64 90", "off 64")));
    r.onMidi(0, 0xB0, 22, 127);
    check("v1.2 Sound − binding (CC22) still works", step[0] == -1);
    // teaching must refuse piano keys but accept a pad or CC
    r.startLearn(MidiRouter.B_SOUND_UP);
    e.log.clear(); r.onMidi(0, 0x90, 60, 80); r.onMidi(0, 0x80, 60, 0);
    check("a piano key pressed while teaching still plays (not swallowed)", e.log.equals(Arrays.asList("on 60 80", "off 60")));
    check("...and teaching is still waiting", r.learning() == MidiRouter.B_SOUND_UP);
    r.onMidi(0, 0x99, 43, 100); r.onMidi(0, 0x89, 43, 0);
    check("pad (ch10 note 43) taught as Sound +", r.learning() == MidiRouter.LEARN_NONE && r.bindingLabel(MidiRouter.B_SOUND_UP) != null);
    step[0] = 0; r.onMidi(0, 0x99, 43, 100);
    check("pad now steps the sound up", step[0] == 1);
    // pads pick sounds by default; other pads unaffected
    r.onMidi(0, 0x99, 37, 100);
    check("pad 2 picks sound 2", pick[0] == 1);
    // DAW port notes never play
    e.log.clear(); r.onMidi(1 | (1 << 4), 0x90, 60, 100);
    check("DAW-port (port 2) note does not play", e.log.isEmpty());
    r.dismissCandidate(); cand[0] = null;
    // what the screen shows = exactly what was played
    int[] h = new int[8];
    e.log.clear();
    r.onMidi(0, 0x90, 60, 100); r.onMidi(0, 0x90, 64, 90); r.onMidi(0, 0x90, 68, 80);
    int nh = r.heldNotes(h);
    check("held notes shown = notes played (60 64 68)", nh == 3 && h[0] == 60 && h[1] == 64 && h[2] == 68
            && e.log.equals(Arrays.asList("on 60 100", "on 64 90", "on 68 80")));
    r.onMidi(0, 0x80, 60, 0); r.onMidi(0, 0x80, 64, 0); r.onMidi(0, 0x80, 68, 0);
    check("all released", r.heldNotes(h) == 0);
    // MPK OCT+ simply makes it send 72 instead of 60: shown and played as 72, nothing guessed
    e.log.clear(); r.onMidi(0, 0x90, 72, 100);
    check("after MPK OCT+, key sends 72 -> plays and shows 72", e.log.equals(Arrays.asList("on 72 100")) && r.heldNotes(h) == 1 && h[0] == 72);
    r.onMidi(0, 0x80, 72, 0);
    // note naming: Ableton/Akai by default
    check("default naming: note 60 = C3 (Ableton, Akai)", MidiRouter.noteName(60).equals("C3") && MidiRouter.noteName(48).equals("C2")
            && MidiRouter.noteName(36).equals("C1") && MidiRouter.noteName(69).equals("A3") && MidiRouter.noteName(68).equals("G♯3"));
    r.setMiddleCIsC3(false);
    check("scientific naming: note 60 = C4", MidiRouter.noteName(60).equals("C4") && MidiRouter.noteName(69).equals("A4"));
    r.setMiddleCIsC3(true);
    // ---- the MPK's -/+ buttons on its Software Control port (port 4) ----
    final int P4 = 1 | (3 << 4);
    step[0] = 0; cand[0] = null;
    r.onMidi(P4, 0xB0, 20, 127);
    check("unmapped -/+ press offers a mapping (" + cand[0] + ")", cand[0] != null && cand[0].contains("port 4"));
    r.assignCandidate(MidiRouter.B_SOUND_UP);
    r.onMidi(P4, 0xB0, 20, 0);
    r.onMidi(P4, 0xB0, 20, 127); r.onMidi(P4, 0xB0, 20, 0);
    r.onMidi(P4, 0xB0, 20, 127); r.onMidi(P4, 0xB0, 20, 0);
    check("mapped + button steps the sound once per press", step[0] == 2);
    cand[0] = null;
    r.onMidi(P4, 0xB0, 21, 127);
    r.assignCandidate(MidiRouter.B_SOUND_DOWN);
    r.onMidi(P4, 0xB0, 21, 0); step[0] = 0;
    r.onMidi(P4, 0xB0, 21, 127); r.onMidi(P4, 0xB0, 21, 0);
    check("mapped − button steps down; + still mapped", step[0] == -1 && r.bindingLabel(MidiRouter.B_SOUND_UP) != null);
    // relative style: one CC, value 1 = +, value 127 = −
    r.clearBindings(); step[0] = 0;
    r.onMidi(P4, 0xB0, 30, 1); r.assignCandidate(MidiRouter.B_SOUND_UP);
    try { Thread.sleep(100); } catch (InterruptedException ex) {}
    r.onMidi(P4, 0xB0, 30, 127); r.assignCandidate(MidiRouter.B_SOUND_DOWN);
    check("relative CC: both directions mapped side by side", r.bindingLabel(0) != null && r.bindingLabel(1) != null);
    try { Thread.sleep(100); } catch (InterruptedException ex) {}
    step[0] = 0;
    r.onMidi(P4, 0xB0, 30, 1);
    try { Thread.sleep(100); } catch (InterruptedException ex) {}
    r.onMidi(P4, 0xB0, 30, 1);
    try { Thread.sleep(300); } catch (InterruptedException ex) {}
    r.onMidi(P4, 0xB0, 30, 127);
    check("relative CC: +,+,− = +1 net (" + step[0] + ")", step[0] == 1);
    // control-port notes never play; ignoring stops the prompt
    cand[0] = null; e.log.clear();
    r.onMidi(P4, 0x90, 50, 127); r.onMidi(P4, 0x80, 50, 0);
    check("control-port note does not play but is offered", e.log.isEmpty() && cand[0] != null);
    r.ignoreCandidate(); cand[0] = null;
    r.onMidi(P4, 0x90, 50, 127);
    check("ignored button is not offered again", cand[0] == null);
    // knobs are never offered
    cand[0] = null;
    for (int v = 60; v <= 70; v++) r.onMidi(0, 0xB0, 70, v);
    check("turning a knob (CC70 sweep) is not offered as a button", cand[0] == null);
    // only the MIDI port plays notes
    e.log.clear(); r.onMidi(0, 0x90, 62, 90);
    check("keys on port 1 play", e.log.equals(java.util.Arrays.asList("on 62 90")));
    // ---- pop-up switch ----
    check("asking about new buttons is on by default", r.askButtons());
    r.setAskButtons(false);
    cand[0] = null;
    r.onMidi(P4, 0xB0, 41, 127);
    check("switched off: a new button press is not offered", cand[0] == null);
    check("switch is saved", Boolean.FALSE.equals(p.m.get("askButtons")));
    r.setAskButtons(true);
    r.onMidi(P4, 0xB0, 42, 127);
    check("switched back on: offered again", cand[0] != null);
    // ---- Learn mode hears exactly your keys ----
    r.releaseAll();                              // start with no keys held
    final java.util.List<String> heard = new java.util.ArrayList<>();
    r.setKeyListener((n, v, down, ts) -> heard.add((down ? "down " : "up ") + n));
    r.onMidi(0, 0x90, 65, 90); r.onMidi(0, 0x80, 65, 0);
    r.onMidi(P4, 0x90, 66, 90);                 // control port: not a key
    r.onMidi(0, 0x90, 67, 90);
    r.releaseAll();                              // keyboard unplugged while holding
    check("Learn mode gets key down/up, never control buttons, and a release on unplug",
            heard.equals(java.util.Arrays.asList("down 65", "up 65", "down 67", "up 67")));
    r.setKeyListener(null);
    // ---- the 25 keys on screen follow the MPK's octave through the notes ----
    Prefs p2 = new Prefs();
    MidiRouter k = new MidiRouter(new AudioEngine(), p2);
    check("starts on the MPK's default keys (48..72)", k.keysLo() == 48);
    k.onMidi(0, 0x90, 60, 90); k.onMidi(0, 0x90, 72, 90);
    check("notes inside the 25 keys don't move them", k.keysLo() == 48);
    k.onMidi(0, 0x90, 84, 90);
    check("OCT +1 (a note above C4) moves the keys up an octave", k.keysLo() == 60);
    k.onMidi(0, 0x90, 60, 90);
    check("...and the bottom key of that octave keeps them there", k.keysLo() == 60);
    k.onMidi(0, 0x90, 30, 90);
    check("a low note jumps down as far as needed", k.keysLo() == 24 && 30 <= 24 + 24);
    k.onMidi(0, 0x99, 120, 90);
    check("pads (ch 10) never move the keys", k.keysLo() == 24);
    k.onMidi(1 | (1 << 4), 0x90, 120, 90);
    check("control-port notes never move the keys", k.keysLo() == 24);
    k.shiftKeys(1);
    check("‹ › on screen move the keys by an octave", k.keysLo() == 36);
    for (int i = 0; i < 20; i++) k.shiftKeys(1);
    check("...but not past OCT +4", k.keysLo() == 96);
    k.onMidi(0, 0x90, 127, 90);
    check("a note above the top still leaves the keys at OCT +4", k.keysLo() == 96);
    for (int i = 0; i < 20; i++) k.shiftKeys(-1);
    check("...or below OCT -4", k.keysLo() == 0);
    k.shiftKeys(5);
    check("the keys' octave is remembered", new MidiRouter(new AudioEngine(), p2).keysLo() == 60);
    System.out.println(fails == 0 ? "ALL PASSED" : fails + " FAILED");
  }
}

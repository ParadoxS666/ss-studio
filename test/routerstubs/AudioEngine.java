package com.pocketkeys.piano;
/** Test stub: records what the router asks the synth to play. */
class AudioEngine {
  final java.util.List<String> log = new java.util.ArrayList<>();
  void noteOn(int n,int v){log.add("on "+n+" "+v);} void noteOff(int n){log.add("off "+n);}
  void sustain(boolean on){} void pitchBend(int v){} void modWheel(int v){} void setPreset(int p){}
  void allNotesOff(){} void autoNoteOn(int n,int v){} void autoNoteOff(int n){} void autoAllOff(){} void setDynamicRange(int db){} float latencyMs(){return 0;} boolean isClassicBluetooth(){return false;} String routeLabel(){return "Headphones · WH-1000XM6 (LE Audio)";} void setReverb(float r){}
}

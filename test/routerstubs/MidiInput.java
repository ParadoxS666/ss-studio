package com.pocketkeys.piano;
final class MidiInput {
  boolean available(){return true;} java.util.List<String> deviceNames(){return java.util.Collections.emptyList();}
  static final int SRC_KEYS = 0, SRC_CONTROL = 1;
  interface Listener { void onMidi(int src,int status,int d1,int d2); void onSysex(int src,byte[] d,int len); void onDevicesChanged(boolean removed,boolean hotplug); }
}

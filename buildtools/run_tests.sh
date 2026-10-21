#!/bin/sh
# Runs every desktop test (no phone needed). Usage: sh buildtools/run_tests.sh
set -e
cd "$(dirname "$0")/.."
OUT=build/tests
rm -rf "$OUT"
mkdir -p "$OUT/core" "$OUT/router" "$OUT/render"
A=app/src/com/pocketkeys/piano
T=test/com/pocketkeys/piano
J="java -Djava.awt.headless=true"

echo "== compile"
javac -nowarn -d "$OUT/core" $A/Synth.java $A/SampleBank.java $A/KeyboardLayout.java $A/VelocityCurve.java \
    $A/PortRoles.java $A/LearnSession.java $A/Song.java $A/SongLibrary.java $A/KeyFit.java \
    $T/SynthTest.java $T/PitchTest.java $T/KeyboardTest.java $T/LogicTest.java $T/LearnTest.java
STUBS="test/routerstubs/AudioEngine.java test/routerstubs/MidiInput.java test/routerstubs/android/content/SharedPreferences.java"
javac -nowarn -d "$OUT/router" $STUBS $A/MidiRouter.java $A/PortRoles.java $A/VelocityCurve.java $A/Synth.java \
    $A/SampleBank.java $A/KeyboardLayout.java $T/RouterTest.java
# the real Learn and main screens, drawn with desktop stand-ins for Android's drawing classes
javac -nowarn -d "$OUT/render" $(find test/shim -name '*.java') $STUBS $A/StageView.java $A/LearnView.java \
    $A/LearnSession.java $A/Song.java $A/SongLibrary.java $A/KeyFit.java $A/KeyboardLayout.java $A/MidiRouter.java \
    $A/PortRoles.java $A/VelocityCurve.java $A/Synth.java $A/SampleBank.java test/render/com/pocketkeys/piano/*.java

echo "== logic";    $J -cp "$OUT/core" com.pocketkeys.piano.LogicTest
echo "== keyboard"; $J -cp "$OUT/core" com.pocketkeys.piano.KeyboardTest "$OUT/keyboard.png"
echo "== router";   $J -cp "$OUT/router" com.pocketkeys.piano.RouterTest
echo "== learn";    $J -cp "$OUT/core" com.pocketkeys.piano.LearnTest app/assets/songs.pks test/fixtures
echo "== screens";  $J -cp "$OUT/render" com.pocketkeys.piano.LearnRender app/assets "$OUT/screens" test/fixtures/anna-magdalena-04.mid
                    $J -cp "$OUT/render" com.pocketkeys.piano.StageRender "$OUT/screens"
echo "== pitch";    $J -cp "$OUT/core" com.pocketkeys.piano.PitchTest app/assets/piano.pk
mkdir -p "$OUT/wav"
echo "== synth";    $J -cp "$OUT/core" com.pocketkeys.piano.SynthTest app/assets/piano.pk "$OUT/wav"
echo "All test suites passed. Screens are in $OUT/screens."

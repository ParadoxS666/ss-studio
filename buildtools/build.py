#!/usr/bin/env python3
"""
Builds PocketKeys.apk without Gradle:
  javac -> d8 -> binary manifest + resources (apktools.py) -> zip -> apksigner

Usage: python3 buildtools/build.py <tools-dir>
  <tools-dir> must contain android.jar (API 34), d8.jar and apksigner.jar.
"""
import os
import secrets
import subprocess
import sys

sys.path.insert(0, os.path.dirname(__file__))
from apktools import (A, E, TYPE_INT_BOOLEAN, TYPE_INT_DEC, TYPE_INT_HEX, TYPE_REFERENCE,  # noqa: E402
                      TYPE_STRING, DENSITY_ANY, build_arsc, encode_axml, write_apk)

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
TOOLS = os.path.abspath(sys.argv[1])
ANDROID_JAR = os.path.join(TOOLS, "android.jar")
BUILD = os.path.join(ROOT, "build")
PACKAGE = "com.pocketkeys.piano"
VERSION_CODE = 9
VERSION_NAME = "1.7.1"
MIN_SDK, TARGET_SDK = 29, 34

# framework attribute / style IDs (from android.jar, API 34)
ATTR = {
    "theme": 0x01010000, "label": 0x01010001, "icon": 0x01010002, "name": 0x01010003,
    "exported": 0x01010010, "launchMode": 0x0101001d, "configChanges": 0x0101001f,
    "versionCode": 0x0101021b, "versionName": 0x0101021c, "minSdkVersion": 0x0101020c,
    "targetSdkVersion": 0x01010270, "required": 0x0101028e, "allowBackup": 0x01010280,
    "hardwareAccelerated": 0x010102d3, "appCategory": 0x01010545, "roundIcon": 0x0101052c,
    "drawable": 0x01010199,
}
THEME_DEVICE_DEFAULT_NO_ACTION_BAR = 0x01030129


def run(cmd, **kw):
    print("+", " ".join(cmd[:6]), "..." if len(cmd) > 6 else "")
    subprocess.run(cmd, check=True, **kw)


def java_sources():
    out = []
    for d, _, files in os.walk(os.path.join(ROOT, "app", "src")):
        out += [os.path.join(d, f) for f in files if f.endswith(".java")]
    return sorted(out)


def compile_and_dex():
    stubs = os.path.join(BUILD, "stubs")
    classes = os.path.join(BUILD, "classes")
    dex = os.path.join(BUILD, "dex")
    for p in (stubs, classes, dex):
        subprocess.run(["rm", "-rf", p], check=True)
        os.makedirs(p)
    run(["javac", "-source", "8", "-target", "8", "-proc:none", "-Xlint:-options",
         "-bootclasspath", ANDROID_JAR, "-d", stubs,
         os.path.join(ROOT, "buildtools/stubs/java/lang/invoke/LambdaMetafactory.java")])
    run(["javac", "-source", "8", "-target", "8", "-proc:none", "-Xlint:-options", "-encoding", "UTF-8",
         "-bootclasspath", stubs + os.pathsep + ANDROID_JAR, "-d", classes] + java_sources())
    class_files = []
    for d, _, files in os.walk(classes):
        class_files += [os.path.join(d, f) for f in files if f.endswith(".class")]
    run(["java", "-Xmx1g", "-cp", os.path.join(TOOLS, "d8.jar"), "com.android.tools.r8.D8",
         "--release", "--min-api", str(MIN_SDK), "--lib", ANDROID_JAR, "--output", dex] + sorted(class_files))
    return open(os.path.join(dex, "classes.dex"), "rb").read()


def resources():
    icon_dir = os.path.join(BUILD, "icon")
    os.makedirs(icon_dir, exist_ok=True)
    run([sys.executable, os.path.join(ROOT, "buildtools/make_icon.py"), icon_dir])
    types = [
        ("drawable", [
            ("ic_bg", 640, 4, "res/drawable-xxxhdpi-v4/ic_bg.png"),
            ("ic_fg", 640, 4, "res/drawable-xxxhdpi-v4/ic_fg.png"),
            ("ic_mono", 640, 4, "res/drawable-xxxhdpi-v4/ic_mono.png"),
        ]),
        ("mipmap", [
            ("ic_launcher", DENSITY_ANY, 26, "res/mipmap-anydpi-v26/ic_launcher.xml"),
        ]),
    ]
    arsc, ids = build_arsc(PACKAGE, 0x7F, types)
    adaptive = encode_axml(E("adaptive-icon", [], [
        E("background", [A("drawable", TYPE_REFERENCE, ids["drawable/ic_bg"], ATTR["drawable"])]),
        E("foreground", [A("drawable", TYPE_REFERENCE, ids["drawable/ic_fg"], ATTR["drawable"])]),
        E("monochrome", [A("drawable", TYPE_REFERENCE, ids["drawable/ic_mono"], ATTR["drawable"])]),
    ]))
    files = [
        ("res/drawable-xxxhdpi-v4/ic_bg.png", open(os.path.join(icon_dir, "ic_bg.png"), "rb").read(), True),
        ("res/drawable-xxxhdpi-v4/ic_fg.png", open(os.path.join(icon_dir, "ic_fg.png"), "rb").read(), True),
        ("res/drawable-xxxhdpi-v4/ic_mono.png", open(os.path.join(icon_dir, "ic_mono.png"), "rb").read(), True),
        ("res/mipmap-anydpi-v26/ic_launcher.xml", adaptive, False),
    ]
    return arsc, ids, files


def manifest(ids):
    a = ATTR
    root = E("manifest", [
        A("versionCode", TYPE_INT_DEC, VERSION_CODE, a["versionCode"]),
        A("versionName", TYPE_STRING, VERSION_NAME, a["versionName"]),
        A("package", TYPE_STRING, PACKAGE, None, ns=None),
    ], [
        E("uses-sdk", [
            A("minSdkVersion", TYPE_INT_DEC, MIN_SDK, a["minSdkVersion"]),
            A("targetSdkVersion", TYPE_INT_DEC, TARGET_SDK, a["targetSdkVersion"]),
        ]),
        E("uses-feature", [
            A("name", TYPE_STRING, "android.software.midi", a["name"]),
            A("required", TYPE_INT_BOOLEAN, True, a["required"]),
        ]),
        E("uses-feature", [
            A("name", TYPE_STRING, "android.hardware.usb.host", a["name"]),
            A("required", TYPE_INT_BOOLEAN, False, a["required"]),
        ]),
        E("application", [
            A("theme", TYPE_REFERENCE, THEME_DEVICE_DEFAULT_NO_ACTION_BAR, a["theme"]),
            A("label", TYPE_STRING, "SS Studio", a["label"]),
            A("icon", TYPE_REFERENCE, ids["mipmap/ic_launcher"], a["icon"]),
            A("allowBackup", TYPE_INT_BOOLEAN, False, a["allowBackup"]),
            A("hardwareAccelerated", TYPE_INT_BOOLEAN, True, a["hardwareAccelerated"]),
            A("roundIcon", TYPE_REFERENCE, ids["mipmap/ic_launcher"], a["roundIcon"]),
            A("appCategory", TYPE_INT_DEC, 1, a["appCategory"]),  # CATEGORY_AUDIO
        ], [
            E("activity", [
                A("name", TYPE_STRING, PACKAGE + ".MainActivity", a["name"]),
                A("exported", TYPE_INT_BOOLEAN, True, a["exported"]),
                A("launchMode", TYPE_INT_DEC, 2, a["launchMode"]),  # singleTask
                # orientation|screenSize|screenLayout|keyboard|keyboardHidden|navigation|
                # smallestScreenSize|uiMode|density: rotate without restarting audio
                A("configChanges", TYPE_INT_HEX, 0x1FF0, a["configChanges"]),
            ], [
                E("intent-filter", [], [
                    E("action", [A("name", TYPE_STRING, "android.intent.action.MAIN", a["name"])]),
                    E("category", [A("name", TYPE_STRING, "android.intent.category.LAUNCHER", a["name"])]),
                ]),
            ]),
        ]),
    ])
    return encode_axml(root)


def keystore():
    sdir = os.path.join(ROOT, "signing")
    os.makedirs(sdir, exist_ok=True)
    ks = os.path.join(sdir, "pocketkeys.jks")
    pw_file = os.path.join(sdir, "keystore-password.txt")
    if not os.path.exists(ks):
        pw = secrets.token_urlsafe(18)
        run(["keytool", "-genkeypair", "-keystore", ks, "-storetype", "PKCS12", "-storepass", pw,
             "-keypass", pw, "-alias", "pocketkeys", "-keyalg", "RSA", "-keysize", "3072",
             "-validity", "12000", "-dname", "CN=Pocket Keys"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        with open(pw_file, "w") as f:
            f.write(pw + "\n")
    return ks, open(pw_file).read().strip()


def main():
    os.makedirs(BUILD, exist_ok=True)
    dex = compile_and_dex()
    arsc, ids, res_files = resources()
    man = manifest(ids)
    open(os.path.join(BUILD, "AndroidManifest.xml"), "wb").write(man)
    entries = [
        ("AndroidManifest.xml", man, False),
        ("classes.dex", dex, False),
        ("resources.arsc", arsc, True),
    ] + res_files + [
    ] + [
        ("assets/" + f, open(os.path.join(ROOT, "app/assets", f), "rb").read(), True)
        for f in sorted(os.listdir(os.path.join(ROOT, "app/assets")))
    ]
    unsigned = os.path.join(BUILD, "unsigned.apk")
    write_apk(unsigned, entries)
    ks, pw = keystore()
    out = os.path.join(BUILD, "SSStudio.apk")
    if os.path.exists(out):
        os.remove(out)
    run(["java", "-jar", os.path.join(TOOLS, "apksigner.jar"), "sign", "--ks", ks,
         "--ks-pass", "pass:" + pw, "--ks-key-alias", "pocketkeys", "--min-sdk-version", str(MIN_SDK),
         "--v1-signing-enabled", "false", "--v2-signing-enabled", "true", "--v3-signing-enabled", "true",
         "--out", out, unsigned])
    print("built", out, os.path.getsize(out), "bytes")


if __name__ == "__main__":
    main()

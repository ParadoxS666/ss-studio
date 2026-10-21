# Working on SS Studio

Android piano app for the Akai MPK mini IV (see README.md). No Gradle: `python3 buildtools/build.py <tools-dir>`, where the tools dir holds android.jar (API 34), d8.jar and apksigner.jar.

## After every major change, update this repo

The owner wants the repository kept current. After a change that alters what the app does:

1. Bump `VERSION_CODE` and `VERSION_NAME` in `buildtools/build.py`, and the version in StageView's credits text.
2. Run `sh buildtools/run_tests.sh`. Every suite must pass.
3. Build, then put the signed APK in `releases/` as `SSStudio-<version>.apk`. Remove the previous release APK, but keep `PocketKeys-1.0-first-version.apk`.
4. Update README.md: the download link, the feature text, and a new row at the top of the version history.
5. Commit with a message saying what changed, then push to `main`.

## Signing key

APKs must be signed with the same key, or they won't install over the owner's copy. The key lives in `signing/`, which is git-ignored and must never be committed. If `signing/pocketkeys.jks` is missing, ask the owner for it before building a release. Don't let the build make a new key without telling them.

## Code rules that bite

- d8 crashes on anonymous classes and non-static inner classes. Use static nested classes, lambdas or method references.
- Notes play exactly what the MPK sends. Never transpose keys in the router.
- The screens show only the MPK's 25 keys. Learn pieces are fitted onto them by KeyFit.

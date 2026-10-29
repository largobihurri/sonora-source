# Sonora

Sonora is a music player for Android: a library of your own audio files, a batch audio converter that keeps tags and
artwork, DJ and DUB performance modes, and an export of the library to a USB stick. It works offline: the Google Play
build has no internet permission.

This repository holds the source code of each release published on Google Play, one commit per release.

## Licence

Sonora is free software, released under the GNU General Public License, version 3 or (at your option) any later
version (see `LICENSE`). The third-party
components it bundles and their licences are listed in `THIRD_PARTY_NOTICES.txt`; the app shows the same list under
Settings > About > Open-source licences. The MP3 encoder (LAME 3.100, LGPL) is included unmodified in
`core/mp3/src/main/cpp/lame`.

The privacy policy is in `PRIVACY.md` (Spanish: `PRIVACY.es.md`).

## Building

Requirements: JDK 17 or later, the Android SDK (compile SDK 37) and the Android NDK 30.0.16248370 (the native audio
analysis and the MP3 encoder).

Sonora uses Kora, the Bihurri apps' communication library (Apache-2.0, source at
https://github.com/largobihurri/kora-public), which the build downloads from https://largobihurri.github.io/kora-public/maven
with no login. Then:

```bash
./gradlew :app:assemblePlayDebug -Dorg.gradle.jvmargs="-Xmx3g -XX:+UseParallelGC -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8"
```

The APK is in `app/build/outputs/apk/play/debug/`. The build downloads the DJ beat model (Beat This! `small0.onnx`,
MIT, 10 MB) and checks its sha256 against `tools/beatthis/model.json`; offline, run `python3 tools/beatthis/fetch_model.py`
once, or pass `-Psonora.beatmodel.optional=true` to build without it.

Unit tests: `./gradlew :app:testPlayDebugUnitTest` for the app, `:<module>:testDebugUnitTest` for an Android module.

### Flavors and signing

- `play` is the Google Play build: no Google Play services and no internet access.
- `full` adds casting, a Wear OS companion and an online lyrics search. It links Google Play services, which are not
  free software, so it is for building and installing on your own devices only; do not distribute it.

Builds are signed with the key named in `~/.bihurri/signing/signing.properties` when that file exists, and otherwise
with the Android debug key. An APK you build yourself therefore replaces the Google Play one only after you uninstall
it: Android does not update an app with one signed by another key.

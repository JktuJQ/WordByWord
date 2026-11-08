# Building and installation

## Android Studio

Open the project root and select Gradle JDK 21.
In SDK Manager, install Android SDK Platform 36, Build Tools 36.0.0, and Platform Tools.
The SDK path is stored in `local.properties`.
For an emulator, also install Android Emulator and an API 36 system image.
On Windows, use the Run button to build and launch the app.
Use `gradlew.bat` for command-line builds.

## Command line without Nix

Install JDK 21 and [Android SDK Command-line Tools](https://developer.android.com/studio#command-tools).
Set `JAVA_HOME` to the JDK path and `ANDROID_HOME` to the SDK path.
Typical SDK locations are `$HOME/Android/Sdk` on Linux and `$HOME/Library/Android/sdk` on macOS.
The commands below use Bash and Command-line Tools installed under `cmdline-tools/latest`:

```sh
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
sdkmanager --licenses
sdkmanager "platforms;android-36" "build-tools;36.0.0" "platform-tools"
./gradlew --no-daemon --no-parallel --max-workers=1 :app:packageDemoApk
```

The project uses Java/XML, AGP 8.13.2, and Gradle 8.14.6.
The first build requires internet access.
`prepareAsrModels` and `prepareDiarizationModels` download models, verify SHA-256 checksums, and bundle them in the APK.
No additional model installation on the phone is required.
sherpa-onnx is included as an AAR with JNI libraries, so NDK and CMake are not required for the build.
Python and FFmpeg are not required either.
Model sources and versions are documented under [ASR](asr-models.md) and [diarization](diarization-models.md).

## Build output

`:app:packageDemoApk` writes the following files to `artifacts/apk/`:

- `wordbyword-0.1.0-debug.apk`, signed with a debug key;
- a `.sha256` checksum file;
- JSON containing the commit, source state, ABIs, build options, and model hashes.

Verify the checksum from `artifacts/apk/`:

```sh
sha256sum -c wordbyword-0.1.0-debug.apk.sha256
```

On macOS, use `shasum -a 256 -c wordbyword-0.1.0-debug.apk.sha256`.
Binaries are excluded from Git; GitHub does not accept regular Git files larger than 100 MiB, so publish the APK separately with a permanent link.
Rebuilding the same version replaces these files.
The standard `assembleDebug` output is `app/build/outputs/apk/debug/app-debug.apk`.
Parallel processing is enabled by default.
Pass `-PparallelInference=false` to build with sequential processing.
Measurement conditions are in [TEST_RESULTS.md](TEST_RESULTS.md).

Debug signing uses `~/.android/debug.keystore`.
A release build requires a persistent signing key kept outside Git.
Android will not update an installed app if the new APK has a different signature.
Export any needed results before uninstalling the old version: uninstalling deletes the library.

## Emulator

Install a system image and create an AVD.
The commands below use x86-64.
On Apple Silicon, replace `x86_64` with `arm64-v8a` in both system image references.
Virtualization requirements are in the [Android Emulator documentation](https://developer.android.com/studio/run/emulator-acceleration).

```sh
sdkmanager "emulator" "system-images;android-36;google_apis;x86_64"
avdmanager create avd --name wordbyword-api36 --package "system-images;android-36;google_apis;x86_64"
emulator -avd wordbyword-api36 -memory 4096 -cores 2 -no-snapshot
```

Answer `no` when asked about a custom hardware profile.
Display settings can be changed in Device Manager or in `~/.android/avd/wordbyword-api36.avd/config.ini` while the emulator is stopped:

```ini
hw.lcd.width=1080
hw.lcd.height=1920
hw.lcd.density=420
```

After Android boots, install and open the app from another terminal with the same SDK PATH:

```sh
adb -e install -r artifacts/apk/wordbyword-0.1.0-debug.apk
adb -e shell am start -n dev.wordbyword.app/.MainActivity
```

With multiple connected devices, use `adb -s SERIAL`.
List devices with `adb devices`.
With Nix, `nix run .#start-emulator` creates and launches the AVD.
On a memory-constrained machine, build the APK while the emulator is stopped.

## Phone acceptance checklist

Install the APK on a 64-bit phone running Android 8.0+ and enable airplane mode.
Test recordings are documented under [dialogs](../evaluation/fixtures/dialogs/SOURCE.md) and [diarization](../evaluation/fixtures/diarization/SOURCE.md).

1. Import a recording and start processing.
2. Check the text, speakers, and timestamps.
3. Export JSON and verify the `transcript` array with `speaker_id`, `start_ms`, `end_ms`, and `text` fields.
4. Tap an utterance and check the playback position.
5. Rename a speaker and check their other utterances.
6. Restart the app and open the saved recording.
7. Check screen rotation, backgrounding, and processing cancellation.
8. Test silence and a corrupted file.

Record the commit and APK SHA-256, phone model, Android version, RAM, file, and duration.
Also record processing time, RTF, memory use, and discovered errors.
Results and reporting requirements are collected in [TEST_RESULTS.md](TEST_RESULTS.md).
Place raw local reports and JSON hypotheses in `evaluation/results/` or `artifacts/test-results/`.
Scoring rules are described in the [evaluation guide](../evaluation/README.md).

`measure-on-device.sh` uninstalls the app on exit.
Do not run it on a device whose library you need to preserve.

## Validation

```sh
./gradlew :evaluation:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
./gradlew :app:assembleDebugAndroidTest
```

CI runs the main checks and publishes APKs and reports as workflow artifacts.
Configuration is in [.github/workflows/android.yml](../.github/workflows/android.yml).
Model preparation and APK packaging are defined in `app/gradle/models.gradle` and `app/gradle/distribution.gradle`.

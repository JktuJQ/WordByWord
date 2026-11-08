# AGENTS.md

## Project

WordByWord is a Java/XML Android app for on-device processing of Russian conversations.
Main workflow: import audio → recognition and diarization → transcript review → JSON export.
Audio and text are not sent to external services.
Models are bundled in the APK at build time and run offline.

The organizers' requirements are recorded in [docs/hackathon.md](docs/hackathon.md).
The required export is an object with a `transcript` array; utterance fields are `speaker_id`, `start_ms`, `end_ms`, and `text`.
A prepared transcript must not substitute for running the models.

## Structure

- `app/src/main/java/` — application, processing, storage, and UI.
- `app/src/main/res/` — XML UI resources.
- `app/src/test/` — application JVM tests.
- `app/src/testAndroid/` — Android model and pipeline checks.
- `app/gradle/` — model preparation and APK packaging.
- `evaluation/` — scorers, datasets, and measurement scripts.
- `artifacts/test-results/` — published measurements.
- `docs/` — architecture, models, limitations, and build instructions.

## Changes

Check the existing implementation and its consumers before making changes.
Reuse shared types rather than introducing parallel storage implementations or APIs.
Agree on contract changes and new dependencies with the user.
Work on a separate branch and publish changes within the user's requested scope.
Do not create Issues or PRs for internal notes or temporary plans.

Do not add code comments.
Keep architecture explanations in the documentation.
Place each Markdown sentence on a separate source line.
Do not commit models, APKs, local logs, or intermediate reports.
Preserve licenses and provenance information for models and test data.

## Validation

Run checks appropriate to the change and review the final diff.
Main checks: `./gradlew :evaluation:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`.
Build Android tests separately: `./gradlew :app:assembleDebugAndroidTest`.

On a memory-constrained machine, use `--no-daemon --no-parallel --max-workers=1` and limit the Gradle heap.
Do not run a heavy build alongside the emulator.
Do not present emulator measurements as phone measurements.
Record the device, model, configuration, and tested APK version.
Do not alter measured values to improve a report or mark unperformed checks as complete.

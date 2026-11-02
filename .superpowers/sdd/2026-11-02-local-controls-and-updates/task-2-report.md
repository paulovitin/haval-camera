# Task 2 — Visible Back navigation

## Status

Implemented and compiled on the authorized Mac Mini. The visible `Voltar` button is constructed by `ActivityNavigation.backButton(Activity)` and added at the top left of `CameraActivity`, `VideoGalleryActivity`, `VideoPlaybackActivity`, and `VideoSettingsActivity`. Its click handler invokes the supplied `Activity.finish()` action once. No hardware Back override or `TripVideoService` stop was added. Physical visibility, touch behavior, and recording continuity on Android 9 remain for Task 5.

## Commits

| Commit | Purpose |
| --- | --- |
| `d5d3cac67f0ea3cb4a915946f5a3f4d5a7de0146` | RED test and inert listener baseline |
| `dbd398bf227934af2ad5849b33aaebc2fbbb343b` | GREEN implementation, `feat: add visible back navigation` |
| `6b75e7134ff0f767856158206c5714ab16007e1f` | README behavior and physical-validation limit |

## TDD and authorized queue evidence

The new `ActivityNavigationTest.backClickInvokesFinishOnce` calls the real click listener with a counting finish action. It failed against an inert handler, then passed after the handler invoked `finish.run()`. It does not claim to prove Android view visibility or service lifecycle. Those are covered by compilation, source review, and the planned physical inspection. No Robolectric, source-text UI assertion, or dependency was added.

**RED:**

```text
git push origin HEAD:codex/continuous-trip-video-impl
/usr/local/bin/mac-mini-build submit-git /root/projects/haval-camera android-camera-verify d5d3cac
content_files_scanned=88
job=haval-camera-d5d3cac67f0ea3cb-20261102T225513Z-3086 pid=4886 state=queued
archive_sha256=133cb34325ba63a3f834d934b6f6ae267ef3d855a0ff5f4015f9dc98a7c6d661
/usr/local/bin/mac-mini-build status haval-camera-d5d3cac67f0ea3cb-20261102T225513Z-3086
state=failed
ActivityNavigationTest > backClickInvokesFinishOnce FAILED
    java.lang.AssertionError at ActivityNavigationTest.java:14
137 tests completed, 1 failed
BUILD FAILED in 16s
finished=2026-11-02T22:55:32Z rc=1
/usr/local/bin/mac-mini-build fetch haval-camera-d5d3cac67f0ea3cb-20261102T225513Z-3086 /root/projects/haval-camera/.artifacts/back-navigation-red-d5d3cac
./build.log: OK
```

RED log SHA-256: `5d6a29085f2e38fe292928ed1993a636b5fcfd2a3716f48feae592e904fbc74c`. The other 136 tests passed; the queue stopped at the expected unit-test failure before debug assembly.

**GREEN:**

```text
git push origin HEAD:codex/continuous-trip-video-impl
/usr/local/bin/mac-mini-build submit-git /root/projects/haval-camera android-camera-verify dbd398b
content_files_scanned=88
job=haval-camera-dbd398bf227934af-20261102T225616Z-30875 pid=6513 state=queued
archive_sha256=7ea1f90cfa607bcbe881003266a28ffc8cdb2631c26bab8619904c5657f0fec2
/usr/local/bin/mac-mini-build status haval-camera-dbd398bf227934af-20261102T225616Z-30875
state=success
> Task :app:testDebugUnitTest
> Task :app:assembleDebug
BUILD SUCCESSFUL in 15s
> Task :app:lintVitalRelease
> Task :app:assembleRelease
BUILD SUCCESSFUL in 16s
No errors detected in compressed data of /Users/consorciolimpagyn/BuildQueue/work/haval-camera-dbd398bf227934af-20261102T225616Z-30875/source/build/haval-camera.apk.
finished=2026-11-02T22:56:51Z rc=0
/usr/local/bin/mac-mini-build fetch haval-camera-dbd398bf227934af-20261102T225616Z-30875 /root/projects/haval-camera/.artifacts/back-navigation-green-dbd398b
./build.log: OK
./haval-camera.apk: OK
```

The approved `android-camera-verify` job runs `./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug` and then `bash build.sh` on the Mini. `build.sh` checks release signer certificate, ZIP alignment, and ZIP integrity before returning the APK. GREEN log SHA-256: `9cba51f362f463643c9aa16f86193e6a6e0f4055dd154e3751c02f3904994ec7`. Fetched release APK SHA-256: `79d1e57291219a6057f2d903c1617773a81a257f61b45346623ea88811be0e6e`.

## Files and self-review

- `app/src/main/java/br/com/redesurftank/havalcamera/ActivityNavigation.java`: shared `Voltar` button and finish-only listener.
- `app/src/main/java/br/com/redesurftank/havalcamera/CameraActivity.java`: button above the status overlay; existing `Fechar` and capture lifecycle remain.
- `app/src/main/java/br/com/redesurftank/havalcamera/VideoGalleryActivity.java`: button before the existing controls; static child count changes from five to six so `render()` still preserves controls and the active-Wi-Fi sharing UI.
- `app/src/main/java/br/com/redesurftank/havalcamera/VideoPlaybackActivity.java`: button above the error text in a top-left overlay.
- `app/src/main/java/br/com/redesurftank/havalcamera/VideoSettingsActivity.java`: fixed button above the scrollable settings.
- `app/src/test/java/br/com/redesurftank/havalcamera/ActivityNavigationTest.java`: real listener callback assertion.
- `README.md`: behavior and validation status.

Self-review checked `git diff 09b81cc..dbd398b --check`, all four factory call sites, the gallery child index, and the listener body. The new click path has only `finish.run()` and has no service reference; normal Activity teardown still releases the camera preview, playback player, or temporary LAN sharing as before. Hardware Back was not overridden. No independent reviewer was dispatched, as requested. No coordinator Android/Gradle/Kotlin/SDK build ran, and no controller ledger was changed.

## Concerns

The GREEN queue proves compilation, JVM listener behavior, and package checks. It does not prove the button is visible or tappable on the physical Android 9 display, nor that an active automatic recording continues after each tap. Task 5 must inspect all four screens and the service state on the vehicle. The Mini log retains the existing seven AndroidX annotation warnings, deprecated-API note, and `extractNativeLibs` manifest warning.

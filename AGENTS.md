# Working on Haval Camera

This repository contains the standalone Android camera example and its native stream adapter. Keep changes scoped to camera capture, rendering, build, and integration documentation.

## Read before changing behavior

- For capture, color, startup, or native-process failures, read `docs/CAMERA.md` before changing the stream path.
- For embedding in another app, changing package names, or publishing an API, read `docs/INTEGRATION.md`. Preserve root-service path validation when adapting the host application ID.
- For claims about supported hardware or a release, read `docs/VALIDATION.md`. Keep observed results separate from behavior inferred from code.

## Preserve the working capture path

Input 4 supplies packed BGR24, 1760 × 720, 3,801,600 bytes per frame on the investigated firmware. The source runs at 30 fps; `-dumpFrame=2` forwards about 15 fps. Camera2 produced a green image on that device. Use the existing AIS client path unless new device evidence justifies replacing it.

The native adapter belongs only in the private child process. Keep frame output in a pipe and logs on stderr. Retain `3>&1 1>&2` on the same `exec` command, the exact dump-path/mode checks, and the parent-death handling. Preserve validation of canonical paths before launching anything as root. Treat a change to these checks as a security change.

Keep the image blank on stream errors instead of presenting an old frame as live. The Activity owns capture lifetime; changes must account for pause, permission prompts, disconnect, reconnect, and process termination.

## Build and verify

Never run Android, Gradle, Kotlin, Xcode, SDK tools, Android javac, CMake/NDK, `./gradlew`, or `build.sh` on the coordinator. Use an audited immutable Git commit on the authorized Mac Mini; never send uncommitted sources or secrets, and never fall back locally. Only shell syntax, XML/TOML/text inspection, archive/checksum inspection, and Git diff checks are allowed on the coordinator for this migration.

On the authorized Mac Mini, use JDK 17 to run Gradle 8.14.3 / AGP 8.12.1, Java source/target 11, SDK 36, build tools 36.0.0, min/target 28, NDK 27.0.12077973, CMake 3.22.1, and arm64-v8a only. Run `./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug`, then `bash build.sh`. Completion requires real Java/AIDL/native compilation, a passing smoke test, signature verification, ZIP alignment, and ZIP integrity. The historical GitHub Actions success proves only the old manual baseline.

The official checksum-verified Gradle wrapper was regenerated on the Mini, and `gradle/verification-metadata.xml` covers the test, debug, and release graphs. See README.md for the regeneration commands required after dependency or wrapper changes. Normal builds and CI must use strict verification; never use lenient/off mode, trusted-artifact wildcards, ignored checksums, or CI checksum generation. Audit generated checksums, preserve the original four Shizuku AAR hashes, commit the completed metadata, and verify again without metadata generation.

`build.sh` assembles the release variant and preserves `build/haval-camera.apk` plus `apksigner verify`, `zipalign -c 4`, and `unzip -tq`. Debug builds keep Gradle debug signing. Optional release signing consumes only the four existing `CAMERA_*` variables together; keys and passwords never enter source control. The default Mini queue does not implement release signing. Preserve AGPL-3.0, the Shizuku MIT notice, and packaged third-party notices.

Before writing mobile UI tests, explore the real interactions with ARTEMIS as required by the task environment. Check `adb devices -l`, target the user's explicit serial, and ask when device choice is ambiguous. An emulator build or screenshot does not validate this hardware camera path. If ARTEMIS or the target device is unavailable, report that limit and do not invent device-test results.

For a hardware check, use a parked vehicle in P and follow the procedure in `docs/CAMERA.md`. Verify actual camera frames, continuous updates, the visible APK controls, and the absence of raw dumps. Then verify pause/close, absence of an orphan capture child, and successful reopen. Record which checks ran; unavailable hardware leaves those checks pending.

## Publication

Preserve `LICENSE` and the Shizuku notice. Publish only this project's source, documentation, and generated APK assets. Keep signing keys, IP addresses, raw frames, user photographs, full vehicle logs, and proprietary OEM binaries out of commits and releases.

Before tagging a release, verify the source revision and build output, publish the APK SHA-256, and state the exact scope of hardware validation. A release built from changed capture code needs a new hardware check before being described as tested. The build workflow alone cannot establish compatibility.

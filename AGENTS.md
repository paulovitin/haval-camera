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

Run `bash build.sh` using the SDK versions in the script. Completion requires successful Java/AIDL/native compilation, APK signature verification, and ZIP alignment. For CMake changes, also build `app/src/main/cpp/CMakeLists.txt` with the Android NDK for `arm64-v8a` and API 28.

Before writing mobile UI tests, explore the real interactions with ARTEMIS as required by the task environment. Check `adb devices -l`, target the user's explicit serial, and ask when device choice is ambiguous. An emulator build or screenshot does not validate this hardware camera path. If ARTEMIS or the target device is unavailable, report that limit and do not invent device-test results.

For a hardware check, use a parked vehicle in P and follow the procedure in `docs/CAMERA.md`. Verify actual camera frames, continuous updates, the visible APK controls, and the absence of raw dumps. Then verify pause/close, absence of an orphan capture child, and successful reopen. Record which checks ran; unavailable hardware leaves those checks pending.

## Publication

Preserve `LICENSE` and the Shizuku notice. Publish only this project's source, documentation, and generated APK assets. Keep signing keys, IP addresses, raw frames, user photographs, full vehicle logs, and proprietary OEM binaries out of commits and releases.

Before tagging a release, verify the source revision and build output, publish the APK SHA-256, and state the exact scope of hardware validation. A release built from changed capture code needs a new hardware check before being described as tested. The build workflow alone cannot establish compatibility.

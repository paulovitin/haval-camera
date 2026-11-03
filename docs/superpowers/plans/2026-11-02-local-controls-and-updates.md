# Local Controls and Updates Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship one signed Haval Camera APK with active-Wi-Fi sharing, visible navigation, confirmed GitHub updates, and proven local/steering camera controls.

**Architecture:** Keep the standalone app and existing capture path. Add small platform adapters for active Wi-Fi, OTA metadata, and BeanInput keys; Activities remain thin. Camera source changes enter the capture pipeline only after the car proves the firmware source mapping.

**Tech Stack:** Java 11, Android 9/API 28, `ConnectivityManager`, existing OkHttp, BeanInput AIDL, JUnit 4, Gradle/NDK on authorized Mac Mini only.

**Spec:** `docs/superpowers/specs/2026-11-02-local-controls-and-updates-design.md`

## Global Constraints

- Keep Haval Camera usable without EcoTrip or Haval Impulse.
- Bind sharing only to the active `TRANSPORT_WIFI` IPv4 address; never cellular, Ethernet, Tailscale, or wildcard.
- Never install silently. Require user confirmation, verified size, SHA-256, and certificate SHA-256 `5efec0f94d84d90e769635a81d51e2f25953d5d5b3cea950a115705d3b5e83e0`.
- Preserve QCarCam root path validation, pipe transport, frame size, and blank-on-error behavior.
- Build, test, sign, and package only through the authorized Mac Mini queue.
- Do not publish or claim a camera-source mapping until validated on the parked car.

## Review Focus

- Active private IPv4 on Ethernet or Tailscale must be rejected even when it appears before Wi-Fi.
- A malformed or ambiguous GitHub release must never select an APK.
- An APK with correct checksum but wrong signing certificate must be rejected and deleted.
- Repeated BeanInput `ACTION_DOWN` events must change source once, without callback-thread blocking.
- App pause, source switch, or update must not leave a capture child or corrupt an active segment.

---

### Task 1: Active Wi-Fi sharing

**Files:**
- Create: `app/src/main/java/br/com/redesurftank/havalcamera/ActiveWifiAddress.java`
- Modify: `app/src/main/java/br/com/redesurftank/havalcamera/VideoGalleryActivity.java`
- Modify: `app/src/main/java/br/com/redesurftank/havalcamera/LocalShareServer.java`
- Test: `app/src/test/java/br/com/redesurftank/havalcamera/ActiveWifiAddressTest.java`
- Test: `app/src/test/java/br/com/redesurftank/havalcamera/LocalShareServerTest.java`

**Interfaces:**
- Produces: `ActiveWifiAddress.select(boolean wifiTransport, List<InetAddress> addresses): InetAddress` and `ActiveWifiAddress.from(Context): InetAddress`.
- Consumes: existing `LocalShareServer(InetAddress, File, VideoCatalog)`.

- [ ] **Step 1: Add failing selection tests** proving active Wi-Fi chooses private IPv4 and rejects non-Wi-Fi, loopback, IPv6-only, and public addresses.
- [ ] **Step 2: Submit the immutable RED commit to the Mac Mini test queue**; expect only the new tests to fail.
- [ ] **Step 3: Implement `ActiveWifiAddress`** with `ConnectivityManager.getActiveNetwork()`, `NetworkCapabilities.hasTransport(TRANSPORT_WIFI)`, and `LinkProperties.getLinkAddresses()`.
- [ ] **Step 4: Remove `NetworkInterface` name-prefix validation** from `LocalShareServer`; retain private-address and exact-address bind checks.
- [ ] **Step 5: Use `ActiveWifiAddress.from(this)` in `VideoGalleryActivity`** and show the selected IPv4 in status.
- [ ] **Step 6: Submit GREEN commit**; expect all unit tests and debug assembly to pass.
- [ ] **Step 7: Commit** as `fix: bind sharing to active wifi`.

### Task 2: Visible Back navigation

**Files:**
- Create: `app/src/main/java/br/com/redesurftank/havalcamera/ActivityNavigation.java`
- Modify: `app/src/main/java/br/com/redesurftank/havalcamera/CameraActivity.java`
- Modify: `app/src/main/java/br/com/redesurftank/havalcamera/VideoGalleryActivity.java`
- Modify: `app/src/main/java/br/com/redesurftank/havalcamera/VideoPlaybackActivity.java`
- Modify: `app/src/main/java/br/com/redesurftank/havalcamera/VideoSettingsActivity.java`
- Test: `app/src/test/java/br/com/redesurftank/havalcamera/ActivityNavigationTest.java`

**Interfaces:**
- Produces: `ActivityNavigation.backButton(Activity): Button` which calls `finish()`.

- [ ] **Step 1: Add failing contract tests** proving every user-facing Activity creates a visible `Voltar` action and no action stops `TripVideoService`.
- [ ] **Step 2: Submit RED commit**; expect navigation tests to fail.
- [ ] **Step 3: Add one shared Back-button factory** and place it consistently in all four Activities without changing hardware Back behavior.
- [ ] **Step 4: Submit GREEN commit**; expect all tests and debug assembly to pass.
- [ ] **Step 5: Commit** as `feat: add visible back navigation`.

### Task 3: Confirmed GitHub OTA

**Files:**
- Create: `app/src/main/java/br/com/redesurftank/havalcamera/UpdateManifest.java`
- Create: `app/src/main/java/br/com/redesurftank/havalcamera/UpdateVerifier.java`
- Create: `app/src/main/java/br/com/redesurftank/havalcamera/AppUpdater.java`
- Create: `app/src/main/res/xml/update_paths.xml`
- Create: `scripts/write-update-manifest.sh`
- Modify: `app/src/main/java/br/com/redesurftank/havalcamera/VideoSettingsActivity.java`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/build.gradle.kts`
- Modify: `build.sh`
- Test: `app/src/test/java/br/com/redesurftank/havalcamera/UpdateManifestTest.java`
- Test: `app/src/test/java/br/com/redesurftank/havalcamera/UpdateVerifierTest.java`

**Interfaces:**
- Produces: `UpdateManifest.parse(String): UpdateManifest`, `UpdateManifest.isNewerThan(int): boolean`, `UpdateVerifier.verify(File, UpdateManifest, PackageManager): Verification`, and `AppUpdater.check(boolean includePrerelease, Callback): void`.
- Consumes: GitHub releases API for `rafaelcs28/haval-camera`, existing OkHttp, Android `FileProvider`, and idle state from `TripVideoService.LocalBinder`.

- [ ] **Step 1: Add failing parser and verifier tests** covering version ordering, missing/duplicate asset names, bad size, bad SHA-256, and wrong certificate.
- [ ] **Step 2: Submit RED commit**; expect only updater tests to fail.
- [ ] **Step 3: Implement strict manifest parsing** for `versionCode`, `versionName`, `apk`, `size`, and lowercase 64-character `sha256`; reject unknown schema versions.
- [ ] **Step 4: Implement GitHub release selection and download** with the existing OkHttp client; stable by default, prerelease only when enabled.
- [ ] **Step 5: Verify file size, SHA-256, and archive signer certificate** before exposing an install Intent; delete invalid downloads.
- [ ] **Step 6: Add settings UI** for `Verificar atualização` and `Receber versões de teste`, with confirmation and idle-capture gating.
- [ ] **Step 7: Add `FileProvider`, `REQUEST_INSTALL_PACKAGES`, cache-only path, and release manifest generation** from the signed APK.
- [ ] **Step 8: Submit GREEN commit**; expect all tests, debug assembly, signed release checks, ZIP alignment, and ZIP integrity to pass.
- [ ] **Step 9: Commit** as `feat: add confirmed signed updates`.

### Task 4: BeanInput steering and camera sources

**Files:**
- Create: `app/src/main/aidl/com/beantechs/inputservice/IInputService.aidl`
- Create: `app/src/main/aidl/com/beantechs/inputservice/IInputListener.aidl`
- Create: `app/src/main/java/br/com/redesurftank/havalcamera/CameraSource.java`
- Create: `app/src/main/java/br/com/redesurftank/havalcamera/CameraSourceMap.java`
- Create: `app/src/main/java/br/com/redesurftank/havalcamera/SteeringCameraController.java`
- Modify: `app/src/main/java/br/com/redesurftank/havalcamera/CameraActivity.java`
- Modify: `app/src/main/java/br/com/redesurftank/havalcamera/CameraCaptureClient.java`
- Modify: `app/src/main/java/br/com/redesurftank/havalcamera/TripVideoService.java`
- Test: `app/src/test/java/br/com/redesurftank/havalcamera/CameraSourceMapTest.java`
- Test: `app/src/test/java/br/com/redesurftank/havalcamera/SteeringCameraControllerTest.java`

**Interfaces:**
- Produces: `CameraSource` values `OEM_DEFAULT`, `FRONT`, `REAR`, `LEFT_MIRROR`, `RIGHT_MIRROR`; `CameraSourceMap.fromBeanKey(int): CameraSource`; `CameraCaptureClient.selectSource(CameraSource): void`.
- Key contract: BeanInput `1024/+ -> FRONT`, `1025/− -> REAR`, `1026/← -> LEFT_MIRROR`, `1027/→ -> RIGHT_MIRROR`.

- [ ] **Step 1: Capture controlled hardware evidence** while parked: register a read-only BeanInput listener, press all four keys, query QCarCam inputs, and record the command/input mapping in `docs/VALIDATION.md`. Stop if source mapping is not proven.
- [ ] **Step 2: Add failing mapping and lifecycle tests** for the four exact key codes, `ACTION_DOWN` de-duplication, listener unregister, persisted last source, and segment rotation before source restart.
- [ ] **Step 3: Submit RED commit**; expect only source-control tests to fail.
- [ ] **Step 4: Add copied BeanInput AIDL contracts and a non-blocking controller** that registers codes `1024..1027`, posts work off the binder callback, and unregisters on pause/destroy.
- [ ] **Step 5: Add four on-screen buttons** calling the same selector and preserve `OEM_DEFAULT` until first explicit selection.
- [ ] **Step 6: Implement only the hardware-proven source mapping** by stopping capture, closing the current recording segment, writing the validated QCarCam config, and restarting capture blank.
- [ ] **Step 7: Submit GREEN commit**; expect all tests and debug assembly to pass.
- [ ] **Step 8: Commit** as `feat: add steering camera selection`.

### Task 5: Signed installable release

**Files:**
- Modify: `README.md`
- Modify: `docs/VALIDATION.md`
- Modify: `app/build.gradle.kts`
- Generated outside Git: signed APK, `update-manifest.json`, SHA-256 file, Mac Mini logs.

**Interfaces:**
- Consumes: Tasks 1–4 and authorized `android-camera-verify` queue.
- Produces: one GitHub prerelease with uniquely named APK and matching OTA manifest.

- [ ] **Step 1: Bump `versionCode` and `versionName`** once, after all feature commits.
- [ ] **Step 2: Submit final immutable commit** to `android-camera-verify`; require complete unit suite, debug build, release build, signer pin, alignment, and ZIP integrity.
- [ ] **Step 3: Install the exact signed artifact on car** and verify package signer and version before launch.
- [ ] **Step 4: Run parked hardware checks** for startup, Starlink Wi-Fi sharing from phone, visible Back, update confirmation, four screen buttons, four steering keys, pause/close, and orphan-process absence.
- [ ] **Step 5: Record failures honestly**; recording remains unvalidated until the separate `Muxer is closed` defect passes a real 10-second capture.
- [ ] **Step 6: Publish prerelease assets**: unique APK, `update-manifest.json`, and checksums. Verify each downloaded asset byte-for-byte.
- [ ] **Step 7: Commit docs** as `docs: record local controls release validation` and push the branch.

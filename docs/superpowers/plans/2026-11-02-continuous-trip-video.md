# Continuous Trip Video Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a separate Haval Camera APK that records every accepted trip as segmented H.264, supports safe automatic retention, relays near-real-time video, and lets Haval Hub retrieve completed recordings.

**Architecture:** Haval Camera owns QCarCam, hardware AVC encoding, files, and transfer producers. Haval EcoTrip supplies authoritative trip state and authenticated remote commands through signature-protected IPC. The Node bridge coordinates short-lived sessions and bounded WebSocket relays; Haval Hub decodes live AVC and downloads verified completed segments.

**Tech Stack:** Android API 28/SDK 36, Java 11, Shizuku 13.1.5, OpenGL ES/EGL, MediaCodec/MediaMuxer, OkHttp 5.5.1, Kotlin 2.2.10, Node 24 with `ws`, Swift 5.10, SwiftUI, VideoToolbox/AVFoundation, Xcode 26.3.

**Spec:** `docs/superpowers/specs/2026-11-02-continuous-trip-video-design.md`

## Global Constraints

- Keep Haval Camera as package `br.com.redesurftank.havalcamera`, minSdk 28, targetSdk 28, compileSdk 36, ARM64 only.
- Production Haval Camera and Haval EcoTrip APKs must use the same approved certificate; never commit signing files or passwords.
- Preserve the exact QCarCam input 4, 1760 x 720 BGR24, `-dumpFrame=2`, `3>&1 1>&2`, canonical-path checks, private OEM client copy, pipe validation, and parent-death behavior.
- Never write BGR24/raw frames, use a software encoder, commit OEM binaries, or log video payloads.
- Use AVC surface encoding at 1760 x 720, 15 fps, 4 Mbps, one-second keyframes; fail visibly if no hardware surface encoder works.
- Write 60-second MP4 segments and preserve at least 5 GB free by deleting oldest completed whole trips only.
- Local recording must not wait for or fail because of network, bridge, viewer, or transfer state.
- Live and transfer use TLS, short-lived scoped credentials, one viewer, one producer, and one transfer; live pauses transfer.
- Bridge never persists live frames or recording bytes; only completed-trip metadata may be cached.
- Parked live viewing works only while the head unit is already powered and expires after 30 minutes unless renewed.
- Keep camera faults isolated from Haval EcoTrip telemetry, trips, controls, and updates.
- Haval Camera remains AGPL-3.0 and must ship source/notices; preserve the Shizuku MIT notice and add third-party notices for new dependencies.
- Never run Android, Gradle, Kotlin, or Xcode builds on the coordinator. Use an audited Git commit on the authorized Mac Mini; do not fall back locally.
- Before Android/iOS implementation, restore `/root/projects/haval-ecotrip` to an authorized Git repository. Before bridge implementation, restore `/opt/bridge` source to an authorized Git repository. Do not patch either production snapshot directly.
- Validate the first release only on the investigated head-unit/firmware. Other vehicles remain disabled until separate hardware validation.

## Review Focus

- Delayed, duplicated, or out-of-order trip commands must never restart an old trip or delete the current trip; Tasks 2 and 6 pin revision and reconciliation behavior.
- Low storage during an active recording must preserve active/transferring data and stop safely if 5 GB cannot be recovered; Task 3 pins deletion selection.
- Slow or disconnected viewers must never block encoder output or grow memory without bound; Tasks 7 and 8 pin bounded queue behavior.
- Missing, malformed, or changed AVC configuration and keyframes must reset cleanly instead of showing stale video; Tasks 2, 4, and 9 pin framing and decoder reset.
- Process death during segment close must preserve completed MP4s, remove unusable partials, and resume into a new segment; Tasks 3, 5, and 13 pin recovery.

---

## File Structure

### Haval Camera repository

- `app/src/main/java/br/com/redesurftank/havalcamera/VideoContract.java`: stable actions, extras, permissions, and protocol constants.
- `app/src/main/java/br/com/redesurftank/havalcamera/CommandJournal.java`: persisted command revision and replay decisions.
- `app/src/main/java/br/com/redesurftank/havalcamera/H264WireCodec.java`: live-message framing and AVC payload validation.
- `app/src/main/java/br/com/redesurftank/havalcamera/VideoCatalog.java`: trip/segment metadata and atomic index writes.
- `app/src/main/java/br/com/redesurftank/havalcamera/RetentionPlanner.java`: pure oldest-trip deletion selection.
- `app/src/main/java/br/com/redesurftank/havalcamera/SegmentMuxer.java`: 60-second `.partial` to MP4 lifecycle.
- `app/src/main/java/br/com/redesurftank/havalcamera/CameraCaptureClient.java`: Shizuku capture binding and complete BGR24 frame reads.
- `app/src/main/java/br/com/redesurftank/havalcamera/EglFrameEncoder.java`: BGR texture upload, EGL surface render, and MediaCodec output.
- `app/src/main/java/br/com/redesurftank/havalcamera/RecordingCoordinator.java`: capture/record/live state reducer and sink priority.
- `app/src/main/java/br/com/redesurftank/havalcamera/TripVideoService.java`: foreground lifecycle, commands, notification, and recovery.
- `app/src/main/java/br/com/redesurftank/havalcamera/VideoRelayClient.java`: live and transfer WebSocket producer.
- `app/src/main/java/br/com/redesurftank/havalcamera/VideoCatalogProvider.java`: signature-only metadata and content URI access.
- `app/src/main/java/br/com/redesurftank/havalcamera/VideoGalleryActivity.java`: local trip gallery.
- `app/src/main/java/br/com/redesurftank/havalcamera/VideoPlaybackActivity.java`: ordered local segment playback.
- `tests/`: pure JVM tests for contracts, state, retention, framing, and transfer chunks.

### Haval EcoTrip repository

- `app/src/main/java/br/com/redesurftank/ecotrip/managers/TripVideoBridge.kt`: authoritative trip reconciliation and explicit IPC.
- `TripManager.kt`: expose active trip ID only; do not move video logic into trip accounting.
- `CarTelemetryService.kt`: periodic reconciliation independent of UI.
- `MqttManager.kt`: route authenticated video live/transfer commands and results.
- `AndroidManifest.xml`: request signature permissions and register the explicit catalog-change receiver.

### Bridge repository

- `video-relay.js`: live/transfer sessions, scoped tokens, queue limits, and catalog cache.
- `server.js`: authenticated REST routes, MQTT control adapter, and WebSocket upgrade routing.
- `test/video-relay.test.js`: Node test runner coverage without production MQTT or filesystem video writes.

### Haval Hub iOS repository

- `CameraVideoProtocol.swift`: live/transfer binary parsers.
- `CameraLiveSession.swift`: API control, WebSocket receive loop, and decoder lifecycle.
- `CameraVideoDecoder.swift`: VideoToolbox/AVSampleBufferDisplayLayer adapter.
- `CameraVideoStore.swift`: catalog, resumable downloads, checksums, and local index.
- `CameraVideoView.swift`: live status/player.
- `CameraRecordingsView.swift`: remote/local gallery and download progress.
- `CameraVideoTests/`: protocol, catalog, and resume tests registered as an XcodeGen unit-test target.

---

### Task 1: Reproducible Camera Build and Test Harness

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle/libs.versions.toml`
- Create: `gradle/wrapper/gradle-wrapper.properties`
- Create: `gradle/wrapper/gradle-wrapper.jar`
- Create: `gradlew`
- Create: `gradlew.bat`
- Create: `app/build.gradle.kts`
- Create: `app/proguard-rules.pro`
- Create: `gradle/verification-metadata.xml`
- Create: `app/src/test/java/br/com/redesurftank/havalcamera/BuildSmokeTest.java`
- Modify: `build.sh`
- Modify: `.github/workflows/build.yml`
- Modify: `README.md`
- Modify: `AGENTS.md`
- Remove: `dependencies.sha256`

**Interfaces:**
- Consumes: existing Java/AIDL/C sources and approved signing environment variables.
- Produces: `./gradlew :app:testDebugUnitTest`, `./gradlew :app:assembleDebug`, and `bash build.sh` as reproducible entry points.

- [ ] **Step 1: Add a failing smoke test**

Create `BuildSmokeTest.existingFrameSizeIsStable()` asserting `CameraPreview.WIDTH == 1760`, `HEIGHT == 720`, and `FRAME_SIZE == 3_801_600`.

- [ ] **Step 2: Add the Gradle project without changing source behavior**

Use Gradle 8.14.3, AGP 8.12.1, Java 11, SDK 36, min/target 28, NDK `27.0.12077973`, ABI `arm64-v8a`, AIDL, external CMake, legacy JNI extraction, Shizuku 13.1.5, OkHttp 5.5.1, and JUnit 4.13.2. Generate the wrapper on the authorized Mac Mini.

- [ ] **Step 3: Preserve dependency verification and signing**

Generate strict Gradle verification metadata. Configure optional release signing only from `CAMERA_KEYSTORE`, `CAMERA_KEY_ALIAS`, `CAMERA_STORE_PASS`, and `CAMERA_KEY_PASS`. Keep debug signing for debug builds.

- [ ] **Step 4: Make `build.sh` a verified wrapper**

Have it run the Gradle release assembly on the authorized host, copy the APK to `build/haval-camera.apk`, then run `apksigner verify`, `zipalign -c 4`, and `unzip -tq` exactly as today.

- [ ] **Step 5: Update CI and instructions**

Pin actions by commit, install SDK/NDK versions above, run unit tests before `build.sh`, and publish only the APK artifact. Document that coordinator builds remain forbidden.

- [ ] **Step 6: Verify on the authorized Mac Mini**

Run: `./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug`

Expected: `BUILD SUCCESSFUL`; smoke test passes.

Run: `bash build.sh`

Expected: signed/aligned `build/haval-camera.apk` and successful verification commands.

- [ ] **Step 7: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle gradlew gradlew.bat app/build.gradle.kts app/proguard-rules.pro app/src/test build.sh .github/workflows/build.yml README.md AGENTS.md dependencies.sha256
git commit -m "build: migrate camera app to verified Gradle build"
```

### Task 2: Stable Commands and Binary Protocol

**Files:**
- Create: `app/src/main/java/br/com/redesurftank/havalcamera/VideoContract.java`
- Create: `app/src/main/java/br/com/redesurftank/havalcamera/CommandJournal.java`
- Create: `app/src/main/java/br/com/redesurftank/havalcamera/H264WireCodec.java`
- Create: `app/src/test/java/br/com/redesurftank/havalcamera/CommandJournalTest.java`
- Create: `app/src/test/java/br/com/redesurftank/havalcamera/H264WireCodecTest.java`
- Create: `docs/VIDEO_PROTOCOL.md`

**Interfaces:**
- Consumes: none.
- Produces: `CommandJournal.apply(long revision, String requestId, VideoContract.Command command, String tripId) -> Decision`; `H264WireCodec.encodeConfig(...)`, `encodeAccessUnit(...)`, and `decode(ByteBuffer) -> Frame`.

- [ ] **Step 1: Write failing command-order tests**

Assert duplicate request IDs are ignored, lower revisions are ignored after restart, stale stop/discard cannot affect a newer trip, and `RECONCILE` supersedes incremental commands at the same or lower revision.

- [ ] **Step 2: Implement `VideoContract` and `CommandJournal`**

Use explicit action names under `br.com.redesurftank.havalcamera.action.*`, permission `br.com.redesurftank.havalcamera.permission.CONTROL`, protocol version 1, and SharedPreferences-backed revision/request persistence.

- [ ] **Step 3: Write failing framing tests**

Pin a 24-byte big-endian `HVC1` header: magic, version, type, flags, sequence, PTS microseconds, payload length. Test CONFIG SPS/PPS round-trip, keyframe flag, malformed length, wrong magic/version, missing SPS/PPS, and oversized payload rejection.

- [ ] **Step 4: Implement `H264WireCodec`**

Use CONFIG payload `u16 spsLength + SPS + u16 ppsLength + PPS`; use AVCC length-prefixed NAL units for ACCESS_UNIT payloads. Set maximum live payload to 4 MiB.

- [ ] **Step 5: Document the protocol**

Document message types, byte order, limits, token scopes, close codes, keyframe behavior, and synthetic examples. Do not include captured footage.

- [ ] **Step 6: Verify**

Run on Mac Mini: `./gradlew --no-daemon :app:testDebugUnitTest --tests '*CommandJournalTest' --tests '*H264WireCodecTest'`

Expected: all tests pass.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/br/com/redesurftank/havalcamera/VideoContract.java app/src/main/java/br/com/redesurftank/havalcamera/CommandJournal.java app/src/main/java/br/com/redesurftank/havalcamera/H264WireCodec.java app/src/test docs/VIDEO_PROTOCOL.md
git commit -m "feat: define camera commands and video protocol"
```

### Task 3: Atomic Catalog, Recovery, and Retention

**Files:**
- Create: `app/src/main/java/br/com/redesurftank/havalcamera/VideoCatalog.java`
- Create: `app/src/main/java/br/com/redesurftank/havalcamera/RetentionPlanner.java`
- Create: `app/src/main/java/br/com/redesurftank/havalcamera/SegmentRotationPolicy.java`
- Create: `app/src/test/java/br/com/redesurftank/havalcamera/VideoCatalogTest.java`
- Create: `app/src/test/java/br/com/redesurftank/havalcamera/RetentionPlannerTest.java`
- Create: `app/src/test/java/br/com/redesurftank/havalcamera/SegmentRotationPolicyTest.java`

**Interfaces:**
- Consumes: trip IDs and completed segment metadata.
- Produces: `VideoCatalog.beginTrip`, `completeSegment`, `completeTrip`, `discardTrip`, `recover`; `RetentionPlanner.selectDeletions`; 60-second `SegmentRotationPolicy.shouldRotate`.

- [ ] **Step 1: Write failing catalog and crash-recovery tests**

Assert atomic index replacement, completed-segment ordering, SHA-256 retention, active-trip exclusion from remote snapshots, stale `.partial` deletion, and completed MP4 preservation.

- [ ] **Step 2: Implement `VideoCatalog`**

Use `files/trip-video/<tripId>/`, `trip.json`, ordered six-digit segment names, a temp index followed by atomic rename, and metadata fields fixed by the design. Reject path separators and non-numeric trip IDs.

- [ ] **Step 3: Write failing retention tests**

Cover deleting oldest completed whole trips until 5 GB free, preserving active and transferring trips, no middle-segment deletion, and returning `cannotRecoverReserve` when unrelated data prevents recovery.

- [ ] **Step 4: Implement `RetentionPlanner`**

Keep it pure: input catalog entries, free bytes, and reserve bytes; output ordered trip IDs plus whether recording may continue.

- [ ] **Step 5: Write and implement 60-second rotation policy**

Use encoded presentation timestamps, not wall-clock callbacks. Rotate at `>= 60_000_000` microseconds after the first written keyframe and start the next segment on a keyframe.

- [ ] **Step 6: Verify**

Run on Mac Mini: `./gradlew --no-daemon :app:testDebugUnitTest --tests '*VideoCatalogTest' --tests '*RetentionPlannerTest' --tests '*SegmentRotationPolicyTest'`

Expected: all tests pass, including Review Focus storage and partial-file cases.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/br/com/redesurftank/havalcamera/VideoCatalog.java app/src/main/java/br/com/redesurftank/havalcamera/RetentionPlanner.java app/src/main/java/br/com/redesurftank/havalcamera/SegmentRotationPolicy.java app/src/test
git commit -m "feat: add crash-safe trip video storage"
```

### Task 4: Hardware AVC Capture and MP4 Segmentation

**Files:**
- Create: `app/src/main/java/br/com/redesurftank/havalcamera/EncodedAccessUnit.java`
- Create: `app/src/main/java/br/com/redesurftank/havalcamera/CameraCaptureClient.java`
- Create: `app/src/main/java/br/com/redesurftank/havalcamera/EglFrameEncoder.java`
- Create: `app/src/main/java/br/com/redesurftank/havalcamera/SegmentMuxer.java`
- Create: `app/src/main/java/br/com/redesurftank/havalcamera/RecordingCoordinator.java`
- Create: `app/src/test/java/br/com/redesurftank/havalcamera/RecordingCoordinatorTest.java`
- Modify: `app/src/main/java/br/com/redesurftank/havalcamera/CameraService.java`
- Modify: `app/src/main/java/br/com/redesurftank/havalcamera/CameraPreview.java`

**Interfaces:**
- Consumes: complete 3,801,600-byte BGR24 frames from existing Shizuku pipe.
- Produces: ordered `EncodedAccessUnit` values to independent muxer and live sinks; completed segment callbacks to `VideoCatalog`.

- [ ] **Step 1: Write failing coordinator tests**

Assert recorder sink always receives every encoded unit, blocked live sink cannot block recorder, live attach requests a keyframe, sink failure detaches only that sink, and codec failure stops capture without raw fallback.

- [ ] **Step 2: Extract headless capture from `CameraActivity`**

Implement `CameraCaptureClient.start(FrameConsumer)` and `stop()` using the existing Shizuku UserService, `readFully`, frame size, generation guard, and deterministic unbind. Keep `CameraActivity` as a consumer, not owner of root-process rules.

- [ ] **Step 3: Implement `EglFrameEncoder`**

Select only a hardware AVC encoder supporting `COLOR_FormatSurface`; configure exact design values; upload BGR once; correct channels in shader; set presentation timestamps; drain codec on its own thread; expose SPS/PPS and request-sync-frame.

- [ ] **Step 4: Implement `SegmentMuxer`**

Open `current.partial`, add the AVC track only after output format is known, begin each segment at a keyframe, stop/close before atomic rename, compute SHA-256 off the codec thread, and update `VideoCatalog`.

- [ ] **Step 5: Preserve root security checks**

Adapt only the canonical package paths required by the final APK. Keep private copy, mode `0700`, positional shell parameters, exact redirection, stderr logging, pipe validation, and parent-death checks unchanged.

- [ ] **Step 6: Verify host tests and Android build**

Run on Mac Mini: `./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug`

Expected: coordinator tests pass and APK builds. Do not claim camera compatibility yet.

- [ ] **Step 7: Perform Gate 1 on parked hardware**

Install the debug APK on the explicitly selected authorized head unit. Record 10 minutes in P. Verify hardware codec name, 15 fps target, six or more playable segments, colors, monotonic timestamps, hashes, no `/data/vendor/camera` dumps, no orphan child, CPU/memory/temperature evidence, and successful reopen.

- [ ] **Step 8: Commit evidence and code**

```bash
git add app/src/main app/src/test docs/VALIDATION.md
git commit -m "feat: record hardware encoded camera segments"
```

### Task 5: Foreground Lifecycle and Local Gallery

**Files:**
- Create: `app/src/main/java/br/com/redesurftank/havalcamera/ServiceStateReducer.java`
- Create: `app/src/main/java/br/com/redesurftank/havalcamera/TripVideoService.java`
- Create: `app/src/main/java/br/com/redesurftank/havalcamera/VideoCatalogProvider.java`
- Create: `app/src/main/java/br/com/redesurftank/havalcamera/VideoGalleryActivity.java`
- Create: `app/src/main/java/br/com/redesurftank/havalcamera/VideoPlaybackActivity.java`
- Create: `app/src/test/java/br/com/redesurftank/havalcamera/ServiceStateReducerTest.java`
- Modify: `app/src/main/java/br/com/redesurftank/havalcamera/CameraActivity.java`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: Task 2 commands, Task 3 catalog, Task 4 coordinator.
- Produces: signature-protected explicit service commands; read-only provider URIs; local gallery/player.

- [ ] **Step 1: Write failing service-state tests**

Cover idle, recording, live-only, recording-live, transfer flag, 15-second viewer grace, 30-minute parked expiry, live-pauses-transfer, viewer loss preserving recording, and restart reconciliation.

- [ ] **Step 2: Implement the pure reducer**

Define state and effects separately so Android callbacks only execute emitted start/stop/attach/detach effects.

- [ ] **Step 3: Implement `TripVideoService`**

Register it as exported only behind the signature permission. Start foreground before capture work, process commands through `CommandJournal`, persist active trip/revision, recover catalog on startup, and expose visible recording/live/transfer/error notification states.

- [ ] **Step 4: Implement provider, gallery, and playback**

Provider exports metadata and content URIs read-only under a separate signature read permission. Gallery groups completed trips and active state. Player advances ordered local MP4 segments and blanks on errors.

- [ ] **Step 5: Verify**

Run on Mac Mini: `./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug`

Expected: reducer tests pass; manifest merge shows both permissions; APK verification passes.

- [ ] **Step 6: Hardware lifecycle check**

Verify close/reopen, service stop, process death, Shizuku restart, app restart, and head-unit restart. Confirm completed segments survive and only `.partial` is discarded.

- [ ] **Step 7: Commit**

```bash
git add app/src/main app/src/test docs/VALIDATION.md
git commit -m "feat: add camera recording service and gallery"
```

### Task 6: Haval EcoTrip Trip and Remote-Control Bridge

**Files:**
- Create: `/root/projects/haval-ecotrip/app/src/main/java/br/com/redesurftank/ecotrip/managers/TripVideoBridge.kt`
- Create: `/root/projects/haval-ecotrip/app/src/test/java/br/com/redesurftank/ecotrip/managers/TripVideoBridgeTest.kt`
- Modify: `/root/projects/haval-ecotrip/app/src/main/java/br/com/redesurftank/ecotrip/managers/TripManager.kt`
- Modify: `/root/projects/haval-ecotrip/app/src/main/java/br/com/redesurftank/ecotrip/services/CarTelemetryService.kt`
- Modify: `/root/projects/haval-ecotrip/app/src/main/java/br/com/redesurftank/ecotrip/managers/MqttManager.kt`
- Modify: `/root/projects/haval-ecotrip/app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `TripManager.getActiveAutoTripStartMs(): Long?`, completed history, MQTT video commands, Task 2 explicit actions.
- Produces: `TripVideoBridge.reconcile(activeTripId, completedIds)`, `handleRemoteCommand(command, payload)`, catalog publication, and status results.

- [ ] **Step 1: Restore authorized Git source before editing**

Verify a clean Git commit exists and Mac Mini queue accepts its audited build-control digest. Stop if source remains the sanitized no-Git snapshot.

- [ ] **Step 2: Write failing reconciliation tests**

Use a fake `CameraCommandSink`. Cover start, normal stop, discard, geofence close-and-open between polls, duplicate poll, process restart, missing camera package, stale revision, and active trip preserved when camera fails.

- [ ] **Step 3: Add the narrow TripManager getter**

Implement `fun getActiveAutoTripStartMs(): Long?` under the existing lock. Do not add camera code to TripManager or reuse the single UI-owned `onAutoTripCompleted` callback.

- [ ] **Step 4: Implement periodic authoritative reconciliation**

Persist last commanded trip and revision. Have `TripVideoBridge.start(context)` own a main-looper two-second reconciliation runnable. Start it from `CarTelemetryService.onCreate()` and stop it from `onDestroy()`. Compare active ID and history each cycle. Send STOP when the prior ID exists in history, DISCARD otherwise, then START for a new ID. Send full RECONCILE at service startup.

- [ ] **Step 5: Route remote video commands**

Add `video_live_start`, `video_live_stop`, `video_transfer`, `video_transfer_cancel`, and `video_catalog` handlers to the existing heavy-command executor. Validate JSON, expiry, HTTPS/WSS host, token length, and session IDs before sending explicit intents.

- [ ] **Step 6: Add permissions and catalog forwarding**

Request camera control/read signature permissions. Register an explicit signature-protected catalog-change receiver, query the provider, and publish only completed-trip metadata through existing MQTT result patterns.

- [ ] **Step 7: Verify on Mac Mini**

Run the focused unit test task for `TripVideoBridgeTest`, then the repository's authorized Android debug build.

Expected: tests and build pass; installing without Haval Camera leaves telemetry healthy and logs one bounded availability error.

- [ ] **Step 8: Commit**

```bash
git add app/src/main app/src/test
git commit -m "feat: bridge trip video lifecycle to camera app"
```

### Task 7: Bridge Live Session and Bounded Relay

**Files:**
- Create: `/opt/bridge/video-relay.js`
- Create: `/opt/bridge/test/video-relay.test.js`
- Modify: `/opt/bridge/server.js`
- Modify: `/opt/bridge/package.json`
- Modify: `/opt/bridge/docs/video.md`

**Interfaces:**
- Consumes: authenticated owner request, MQTT command adapter, HVC1 producer frames.
- Produces: `POST /api/video/live/session`, `DELETE /api/video/live/session/:id`, `/ws/video/live/car`, `/ws/video/live/viewer`.

- [ ] **Step 1: Restore source-controlled bridge before editing**

Do not edit `/opt/bridge` production files in place. Restore or clone the authoritative repository, branch from the deployed commit, and verify Node 24.22.2.

- [ ] **Step 2: Write failing Node tests**

Use `node:test` and `ws`. Cover owner auth, random scoped producer/viewer tokens, one-time token use, 60-second connect expiry, one producer/viewer limit, wrong role/session rejection, 30-minute parked expiry, no payload logging, viewer join keyframe control, and cleanup after disconnect.

- [ ] **Step 3: Write failing backpressure tests**

Set viewer `bufferedAmount` above 1 MiB and assert queued non-keyframes drop, config/keyframe recovery is requested, producer remains writable, and no unbounded array grows.

- [ ] **Step 4: Implement `video-relay.js`**

Inject clock, randomness, logger, and car-command sender for tests. Keep video bytes in socket calls only. Parse only the fixed HVC1 header needed for bounds/type/keyframe decisions.

- [ ] **Step 5: Add REST and upgrade routes**

Reuse existing `requireAuth`, gateway routing, token comparison, MQTT prefix, and single HTTP server. Add paths to the existing manual upgrade router without changing `/ws`, `/ws/audio`, clockin, or OCPP behavior.

- [ ] **Step 6: Verify**

Run: `/root/.local/share/node24/bin/node --test test/video-relay.test.js`

Expected: all session, auth, expiry, and backpressure tests pass.

Run: `/root/.local/share/node24/bin/node --check server.js && /root/.local/share/node24/bin/node --check video-relay.js`

Expected: both parse successfully.

- [ ] **Step 7: Commit without deployment**

```bash
git add server.js video-relay.js test/video-relay.test.js package.json docs/video.md
git commit -m "feat: add bounded live camera relay"
```

### Task 8: Camera Live Producer

**Files:**
- Create: `app/src/main/java/br/com/redesurftank/havalcamera/BoundedFrameQueue.java`
- Create: `app/src/main/java/br/com/redesurftank/havalcamera/VideoRelayClient.java`
- Create: `app/src/test/java/br/com/redesurftank/havalcamera/BoundedFrameQueueTest.java`
- Create: `app/src/test/java/br/com/redesurftank/havalcamera/VideoRelayClientTest.java`
- Modify: `app/src/main/java/br/com/redesurftank/havalcamera/RecordingCoordinator.java`
- Modify: `app/src/main/java/br/com/redesurftank/havalcamera/TripVideoService.java`

**Interfaces:**
- Consumes: short-lived WSS URL/token from Task 6; encoded config/access units from Task 4.
- Produces: authenticated HVC1 producer stream and live status callbacks.

- [ ] **Step 1: Write failing queue tests**

Pin capacity by bytes and frames. Assert config and newest keyframe survive pressure, old non-keyframes drop, local recorder is untouched, and queue clears on session end.

- [ ] **Step 2: Implement `BoundedFrameQueue`**

Limit to 1 MiB or 30 messages, whichever comes first. After a drop burst, reject non-keyframes until a new keyframe arrives and request encoder sync once.

- [ ] **Step 3: Write failing WebSocket tests**

Use MockWebServer WebSocket support. Cover producer auth query, config-first send, reconnect with fresh token only, ping timeout, server keyframe request, close-code mapping, and no retry after credential expiry.

- [ ] **Step 4: Implement `VideoRelayClient`**

Use OkHttp 5.5.1, one dispatcher thread, bounded queue, TLS-only URL validation, no payload logs, and status callbacks. Do not store producer tokens after session close.

- [ ] **Step 5: Integrate live-only and recording-live modes**

Starting parked live opens capture/encoder without muxer. Viewer loss waits 15 seconds before stopping live-only capture. Trip recording continues regardless of network state.

- [ ] **Step 6: Verify**

Run on Mac Mini: `./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug`

Expected: queue/WebSocket tests pass and APK builds.

- [ ] **Step 7: Commit**

```bash
git add app/src/main app/src/test
git commit -m "feat: stream live camera video securely"
```

### Task 9: Haval Hub Live Decoder and UI

**Files:**
- Create: `/root/projects/haval-ecotrip/ios-app/HavalEcoTrip/CameraVideoProtocol.swift`
- Create: `/root/projects/haval-ecotrip/ios-app/HavalEcoTrip/CameraVideoDecoder.swift`
- Create: `/root/projects/haval-ecotrip/ios-app/HavalEcoTrip/CameraLiveSession.swift`
- Create: `/root/projects/haval-ecotrip/ios-app/HavalEcoTrip/CameraVideoView.swift`
- Create: `/root/projects/haval-ecotrip/ios-app/CameraVideoTests/CameraVideoProtocolTests.swift`
- Create: `/root/projects/haval-ecotrip/ios-app/CameraVideoTests/CameraLiveSessionTests.swift`
- Modify: `/root/projects/haval-ecotrip/ios-app/project.yml`
- Modify: `/root/projects/haval-ecotrip/ios-app/HavalEcoTrip/EscutaSheetV2.swift`

**Interfaces:**
- Consumes: Task 7 live REST/WebSocket APIs and HVC1 messages.
- Produces: `CameraLiveSession.start()`, `stop()`, state/latency publications, and a SwiftUI live player.

- [ ] **Step 1: Add the XcodeGen test target and failing parser tests**

Use synthetic SPS/PPS and access units matching Task 2. Cover endian fields, malformed lengths, wrong magic/version, discontinuity, config change, and missing keyframe.

- [ ] **Step 2: Implement `CameraVideoProtocol`**

Match HVC1 exactly. Keep parsing off the main actor and cap messages at 4 MiB.

- [ ] **Step 3: Write failing session tests with injected URLSession/WebSocket transport**

Cover authenticated start, offline response, reconnect, stale-frame blanking, viewer cancellation, 30-minute renewal, and showing estimated 4 Mbps data use.

- [ ] **Step 4: Implement decoder and live session**

Build `CMVideoFormatDescription` from SPS/PPS, convert AVCC units into `CMSampleBuffer`, use `AVSampleBufferDisplayLayer`, flush on discontinuity/config change, and blank after timeout. Never show the last frame as live after disconnect.

- [ ] **Step 5: Add the SwiftUI live view**

Show connecting/live/degraded/offline/error states, explicit stop, latency, and mobile-data warning. Reuse current bridge URL/token handling and app design components.

- [ ] **Step 6: Verify on Mac Mini**

Regenerate with XcodeGen, run the focused test target on Simulator, then build scheme `HavalEcoTrip` with `CODE_SIGNING_ALLOWED=NO`.

Expected: parser/session tests and simulator build pass.

- [ ] **Step 7: Commit**

```bash
git add ios-app/HavalEcoTrip ios-app/CameraVideoTests ios-app/project.yml
git commit -m "feat: add live vehicle camera viewer"
```

### Task 10: Bridge Catalog and Resumable Transfer Relay

**Files:**
- Modify: `/opt/bridge/video-relay.js`
- Modify: `/opt/bridge/server.js`
- Modify: `/opt/bridge/test/video-relay.test.js`
- Modify: `/opt/bridge/docs/video.md`

**Interfaces:**
- Consumes: complete catalog snapshots and HVT1 chunks.
- Produces: `GET /api/video/catalog`, `POST /api/video/transfer/session`, `/ws/video/transfer/car`, `/ws/video/transfer/viewer`.

- [ ] **Step 1: Write failing catalog tests**

Assert atomic revision replacement, completed trips only, stale `updatedAt`, retention deletion propagation, invalid path/size rejection, and no video byte persistence.

- [ ] **Step 2: Write failing transfer tests**

Cover 256 KiB maximum chunks, segment ordinal/offset bounds, per-chunk SHA-256, acknowledged offset, reconnect with new token bound to the same transfer ID, expired token rejection, one transfer, live-pauses-transfer, cancellation, and cleanup.

- [ ] **Step 3: Implement catalog cache and transfer sessions**

Keep metadata JSON in existing mutable data storage using atomic writes. Keep chunk bytes only in socket writes. Never use the existing `rec_cache` or `express.raw` upload path for video.

- [ ] **Step 4: Add routes and MQTT control**

Reuse owner auth and result correlation, but use distinct `video_*` topics and session state so audio recording behavior remains unchanged.

- [ ] **Step 5: Verify**

Run: `/root/.local/share/node24/bin/node --test test/video-relay.test.js`

Expected: catalog and transfer tests pass with no video files created.

- [ ] **Step 6: Commit**

```bash
git add server.js video-relay.js test/video-relay.test.js docs/video.md
git commit -m "feat: relay resumable camera recordings"
```

### Task 11: Camera Catalog Publication and Transfer Producer

**Files:**
- Create: `app/src/main/java/br/com/redesurftank/havalcamera/TransferChunkCodec.java`
- Create: `app/src/main/java/br/com/redesurftank/havalcamera/RecordingTransfer.java`
- Create: `app/src/test/java/br/com/redesurftank/havalcamera/TransferChunkCodecTest.java`
- Create: `app/src/test/java/br/com/redesurftank/havalcamera/RecordingTransferTest.java`
- Modify: `app/src/main/java/br/com/redesurftank/havalcamera/VideoRelayClient.java`
- Modify: `app/src/main/java/br/com/redesurftank/havalcamera/TripVideoService.java`
- Modify: `app/src/main/java/br/com/redesurftank/havalcamera/VideoCatalog.java`

**Interfaces:**
- Consumes: scoped transfer request and completed catalog files.
- Produces: HVT1 chunks, ACK-driven resume, catalog-change notification to Haval EcoTrip.

- [ ] **Step 1: Write failing HVT1 tests**

Pin big-endian header with magic/version, segment ordinal, 64-bit offset, length, and 32-byte SHA-256. Reject traversal IDs, active trips, partial files, offsets past EOF, chunks over 256 KiB, and wrong ACK offsets.

- [ ] **Step 2: Implement transfer codec and reader**

Use `RandomAccessFile.seek`, one 256 KiB buffer, per-chunk SHA-256, whole-segment metadata SHA-256, and explicit EOF. Lock only the file being transferred against retention.

- [ ] **Step 3: Write failing transfer-state tests**

Cover pause for live, network loss, new-token resume, cancel, retention lock release, catalog deletion during idle, and no camera/encoder startup for transfer.

- [ ] **Step 4: Implement `RecordingTransfer` and catalog publication**

Run file I/O on one worker. Publish a complete completed-trip snapshot after completion, retention, startup reconnect, and deletion. Send only explicit catalog-change IPC; Haval EcoTrip reads provider data.

- [ ] **Step 5: Verify**

Run on Mac Mini: `./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug`

Expected: transfer tests pass and no test writes outside temporary directories.

- [ ] **Step 6: Commit**

```bash
git add app/src/main app/src/test
git commit -m "feat: publish and transfer trip recordings"
```

### Task 12: Haval Hub Remote Gallery, Download, and Playback

**Files:**
- Create: `/root/projects/haval-ecotrip/ios-app/HavalEcoTrip/CameraVideoStore.swift`
- Create: `/root/projects/haval-ecotrip/ios-app/HavalEcoTrip/CameraRecordingsView.swift`
- Create: `/root/projects/haval-ecotrip/ios-app/CameraVideoTests/CameraVideoStoreTests.swift`
- Modify: `/root/projects/haval-ecotrip/ios-app/HavalEcoTrip/CameraVideoProtocol.swift`
- Modify: `/root/projects/haval-ecotrip/ios-app/HavalEcoTrip/GravacoesV2View.swift`

**Interfaces:**
- Consumes: Task 10 catalog/transfer APIs and HVT1 chunks.
- Produces: remote/local gallery, resumable downloads, verified local files, ordered playback, and share/export.

- [ ] **Step 1: Write failing store tests**

Cover stale/offline catalog labels, active trip absent, expired footage, offset persistence, new-token resume, chunk/whole-file SHA-256 mismatch, insufficient iPhone storage, cancellation, duplicate segment, and atomic promotion from `.partial`.

- [ ] **Step 2: Implement `CameraVideoStore`**

Keep downloaded video under Application Support, store a small local index, reserve iPhone free space before each segment, acknowledge only fsynced offsets, and expose progress per trip/segment.

- [ ] **Step 3: Implement gallery and playback**

Add a distinct “Vídeos” section beside existing cabin-audio recordings. Show car-only, downloading, local, expired, stale, and error states. Play ordered local MP4 segments; permit system share only after checksum verification.

- [ ] **Step 4: Verify on Mac Mini**

Run focused CameraVideoTests, then build `HavalEcoTrip` for Simulator with signing disabled.

Expected: all download/catalog tests pass and existing audio recording views still compile unchanged.

- [ ] **Step 5: Commit**

```bash
git add ios-app/HavalEcoTrip ios-app/CameraVideoTests
git commit -m "feat: add trip video gallery and downloads"
```

### Task 13: End-to-End Safety Gates and Controlled Rollout

**Files:**
- Modify: `docs/VALIDATION.md`
- Create: `docs/VIDEO_OPERATIONS.md`
- Modify: `/root/projects/haval-ecotrip/docs/architecture.md`
- Modify: bridge `docs/video.md`

**Interfaces:**
- Consumes: all prior tasks.
- Produces: evidence-backed enablement decision, rollback steps, hashes, and operator runbook.

- [ ] **Step 1: Run all software gates from immutable commits**

Camera on Mac Mini: unit tests, debug APK, release APK signature/alignment, native ARM64 build.

EcoTrip on Mac Mini: focused Android tests and authorized debug build.

Bridge on Node 24: full `node --test` video suite plus syntax/dependency checks.

iOS on Mac Mini: CameraVideoTests and Simulator build with signing disabled.

Expected: every command passes from recorded commits; no coordinator Android/Xcode build.

- [ ] **Step 2: Run OEM AVM coexistence gate while parked**

Repeatedly trigger reverse and surround views during recording and live viewing. Verify OEM view priority, responsiveness, camera recovery, no child leak, and no raw dumps. Any suppression, delay, freeze, or corruption blocks rollout.

- [ ] **Step 3: Run lifecycle and storage gate**

Exercise both app restarts, Shizuku restart, head-unit restart, network loss, viewer loss, low storage, retention during idle, and transfer lock. Verify completed MP4 preservation and safe stop below reserve.

- [ ] **Step 4: Run 4G live and transfer gate**

Measure glass-to-glass latency, bitrate, dropped frames, queue memory, reconnect time, 30-minute expiry, transfer pause/resume, and final SHA-256. Pass only if latency remains bounded near 1-2 seconds and local recording has no gaps attributable to network.

- [ ] **Step 5: Run controlled route gate**

After parked gates pass, record a short controlled route, then at least 60 minutes. Verify automatic start/stop, geofence close-and-open, gallery in both apps, live while moving/parked, completed-trip-only retrieval, temperature, and OEM AVM.

- [ ] **Step 6: Record rollout and rollback**

Document APK/IPA/source commit hashes, certificate SHA-256, supported head-unit firmware, bridge backup, PM2 rollback, feature-disable command, storage cleanup, and exact unverified scenarios. Automatic recording remains disabled until all gates pass.

- [ ] **Step 7: Commit documentation**

Commit validation evidence separately in each source repository. Do not commit footage, logs containing identifiers, IPs, tokens, raw frames, APK signing material, or OEM binaries.

---

## Self-Review Result

- Spec coverage: every goal, non-goal, security rule, lifecycle, live, transfer, UI, retention, and hardware gate maps to Tasks 1-13.
- Step scan: each task ends in one independently reviewable deliverable and focused verification.
- Type consistency: HVC1, HVT1, command revision, trip ID, catalog revision, and transfer ID names remain stable across Android, Node, and Swift tasks.
- Review Focus: all five risks have explicit failing tests in their owning tasks.
- Proportion: protocol bodies remain in implementation; the plan fixes signatures, values, failure behavior, and proof commands only.


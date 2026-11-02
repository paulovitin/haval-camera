# Task 6 Review Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task by task.

**Goal:** Make EcoTrip/Camera commands acknowledged, resumable, crash-safe, and meaningfully covered by pure contract tests.

**Architecture:** Haval Camera sends a signature-protected explicit result broadcast after command journal/effect processing. EcoTrip correlates bounded waits by request ID and revision on background executors. Camera reopens only an exact completed trip identity and appends segment ordinals. EcoTrip publishes a catalog revision only after its body/revision pair commits atomically.

**Tech Stack:** Android Java/Kotlin, explicit intents/broadcasts, SharedPreferences, JUnit 4, authorized Mac Mini queues.

---

### Task 1: Camera contract, acknowledgement, and stop/resume

**Files:**
- Modify: `app/src/main/java/br/com/redesurftank/havalcamera/VideoContract.java`
- Modify: `app/src/main/java/br/com/redesurftank/havalcamera/TripVideoService.java`
- Modify: `app/src/main/java/br/com/redesurftank/havalcamera/VideoCatalog.java`
- Modify: `app/src/test/java/br/com/redesurftank/havalcamera/CommandJournalTest.java`
- Modify: `app/src/test/java/br/com/redesurftank/havalcamera/VideoCatalogTest.java`
- Create: `app/src/test/java/br/com/redesurftank/havalcamera/CommandAcknowledgementTest.java`
- Modify: `docs/VIDEO_PROTOCOL.md`

1. Add failing tests for exact acknowledgement constants/outcome mapping and completed-trip reopen with segment preservation/recovery.
2. Push immutable test commit and run `android-camera-verify`; confirm RED for missing contract/reopen behavior.
3. Add pure acknowledgement mapping, explicit protected result broadcast after processing, and exact-identity completed-trip reopen.
4. Push immutable implementation commit and run `android-camera-verify`; require tests, debug/release builds, signing/alignment/integrity checks to pass.

### Task 2: EcoTrip acknowledgement and pure protocol contracts

**Files:**
- Modify: `app/src/main/java/br/com/redesurftank/ecotrip/managers/TripVideoBridge.kt`
- Modify: `app/src/main/java/br/com/redesurftank/ecotrip/managers/CameraSettingsReader.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/test/java/br/com/redesurftank/ecotrip/managers/TripVideoBridgeTest.kt`
- Modify: `app/src/test/java/br/com/redesurftank/ecotrip/managers/CameraSettingsReaderTest.kt`
- Create: `app/src/test/java/br/com/redesurftank/ecotrip/managers/CameraVideoContractTest.kt`
- Modify: `docs/architecture.md`

1. Add failing tests for APPLIED/NO_OP/stale/rejected/timeout correlation, no persistence before confirmation, exact wire serialization, URL/token/expiry/session validation, failed catalog revision commit, stop/resume identity, settings conflict semantics, and credential-safe logging boundary.
2. Push immutable test commit and run `android-test-debug`; confirm RED for missing protocol behavior.
3. Implement a pure acknowledgement broker and serializer, explicit receiver, 1.5-second bounded wait, background reconciliation with the existing two-second main-loop cadence, pure validation helpers, atomic catalog revision store, and confirmed settings response mapping.
4. Push immutable implementation commit and run `android-test-debug`; require `testDebugUnitTest assembleDebug` to pass.

### Task 3: Cross-repo verification and report

1. Compare all action/extra/status literals between both contracts.
2. Verify both branches are clean and pushed, and fetch/checksum both final job artifacts.
3. Append both RED/GREEN commit and job chains to the Task 6 report.
4. Record hardware/real cross-process validation as pending; do not install or publish.

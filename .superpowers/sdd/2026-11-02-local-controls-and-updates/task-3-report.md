# Task 3 — Confirmed GitHub OTA

## Status and scope

**DONE_WITH_CONCERNS.** Implemented, self-reviewed, committed, pushed to `origin/codex/continuous-trip-video-impl`, and verified through the authorized Mac Mini queue. Final executable source: `8753405da5a6bcb4a7c69d06b987e6684020246e`. The final report/documentation commit follows that build and does not change executable sources. Baseline: `dd777dd` (reviewed Tasks 1–2).

The final Mini results are **171 tests in 28 suites, zero failures, errors, or skipped tests**, followed by debug assembly, signed release assembly, lint vital, approved signer verification, ZIP alignment, ZIP integrity, and real publication-manifest generation. Physical Android 9 installation and UI/provider lifecycle checks remain pending. No release was published, no tag created, and no device installation attempted.

## Observations, decisions, and scope adjustments

- Observed: the approved brief/spec require stable-by-default GitHub updates, explicit confirmation, exact-asset selection, versionCode ordering, byte and signer verification, and idle gating. The installed project has OkHttp and JUnit, but no AndroidX Core/FileProvider or JVM JSON library.
- Observed: `TripVideoService.LocalBinder` originally exposes only preview subscription. The existing `CameraActivity.onPause()` releases its preview when Settings opens.
- Decision: add only a read-only `isUpdateIdle()` binder query; no stop command or change to capture/native functions. The query runs on the main thread and requires an initialized service, no capture, IDLE reducer mode, and no active trip/encoder/coordinator.
- Decision: implement a platform `ContentProvider` named `UpdateFileProvider`, restricted to `cache/updates/`, because adding AndroidX would violate the no-new-dependency requirement. It reads and rejects any broadening of `update_paths.xml`. It is not the AndroidX FileProvider class. This is the explicit file-list adjustment needed to preserve the dependency boundary.
- Decision: keep `app/build.gradle.kts` unchanged despite its inclusion in the brief's suggested file list. Installed version metadata comes from `PackageManager`; no BuildConfig generation or dependency change is needed, so the approved build-control digest stays unchanged.
- Decision: the updater uses the existing OkHttp dependency through its own bounded HTTP client instance. The existing relay's private, session-owned WebSocket client cannot be reused directly. No tokens or credentials are added.
- Evidence: Android 9 r1's `getPackageArchiveInfo` only invokes certificate collection when `GET_SIGNATURES` is present. The adapter requests both that flag and `GET_SIGNING_CERTIFICATES`, then accepts only the current `SigningInfo.getApkContentsSigners()` array. It does not accept a historical rotation certificate. [Android 9 PackageManager source](https://android.googlesource.com/platform/frameworks/base/+/android-9.0.0_r1/core/java/android/content/pm/PackageManager.java). A dedicated failing lookup-contract test preceded this fix.
- Assumption/limit: the GitHub releases list is consumed in its supplied newest-first order, with `per_page=100`. Stable checks use `/releases/latest`. Opt-in permits the first non-draft result, including prereleases; it does not force a prerelease when a stable release comes first. [GitHub releases API](https://docs.github.com/en/rest/releases/releases).

## Security decisions and behavior

1. `UpdateManifest.parse` is JVM-only and strict: exactly schemaVersion=1, positive bounded integer versionCode, nonempty bounded versionName, one safe APK basename, size in 1..1 GiB, and exactly 64 lowercase hexadecimal SHA-256 characters. It rejects unknown/missing/duplicate/extra fields, wrong types, fractional/exponential integer fields, trailing JSON, malformed escapes, and traversal names. JSON has a size/depth bound; fetched manifests are additionally limited to 64 KiB, release responses to 2 MiB.
2. Stable mode is the persisted default. Drafts are excluded; prereleases require opt-in. Asset names must be unique; the manifest asset is exactly `update-manifest.json`, and the APK is selected by its exact manifest name from the same release's assets. Unrelated APKs are not guessed. Selected asset URLs must be HTTPS URLs in the official repository's release-download path, without credentials/query/fragment/alternate port.
3. Checking fetches only release metadata and the manifest, never the APK. Download starts only after **Baixar** confirmation and an idle check. Streaming enforces the declared upper bound even without Content-Length, with connection/read/total timeouts. HTTPS-to-HTTP redirects are disabled; final responses must remain HTTPS. [OkHttp redirect behavior](https://square.github.io/okhttp/5.x/okhttp/okhttp3/-ok-http-client/-builder/follow-ssl-redirects.html).
4. Verification checks exact byte count and computed SHA-256 before inspecting archive metadata. It requires the same package, exact versionCode and versionName, and exactly one current signer matching `5efec0f94d84d90e769635a81d51e2f25953d5d5b3cea950a115705d3b5e83e0`. Null/corrupt archives, signer failures, exceptions, wrong versions, multiple signers, truncated/oversized downloads, and bad hashes are rejected and their files deleted. Deletion failures are surfaced and never expose an install action.
5. After verification, the user separately confirms **Instalar**. The file is re-verified before handoff; the main thread rechecks idle state and the installed version immediately before `ACTION_INSTALL_PACKAGE`. Only the specific content URI receives a read grant through Intent flags and ClipData. No silent install API, root install command, or broad storage provider is used.
6. The provider accepts only single `update-*.apk` filenames beneath canonical cache/updates, rejects traversal and symlinks, opens only mode `r`, and refuses inserts/updates/deletes. It is not exported. Its XML permits only the dedicated cache directory.
7. A missing Android unknown-source permission opens system settings only after another explicit choice. Returning requires a fresh check/download/confirmation; nothing auto-installs on resume. Pausing Settings cancels updater HTTP/work, dismisses dialogs, discards unhanded-off APKs, and guards callbacks from a prior Activity session. Valid APKs already handed to the installer remain in cache for the system to read.
8. Capture/recording is never stopped by the updater. Busy or disconnected service state blocks download/installation; if capture starts during download, the completion/installation gates refuse the handoff and discard the APK. Idle is sampled at the handoff; no service-wide reservation is held while the external installer is open.
9. Publication script accepts APK/output/build-tools paths only, not manual version/hash fields. On the Mini it verifies the pinned certificate, alignment and ZIP, extracts package/version from `aapt dump badging`, computes bytes/hash from that APK, and atomically writes schema 1. `build.sh` invokes it after its existing release checks. No dependency, signing environment, checksum policy, or queue allowlist was changed.

## TDD sequence and immutable queue evidence

All commits were pushed before submission. Every submitted build used an immutable Git commit and source scanning. No Android/Gradle/Kotlin/SDK/javac/native build ran on the coordinator.

The first RED used inert parser/verifier methods. The later RED snapshots added the transfer/cache and publication/network contracts. The final feature RED (`b14eca1`) fails exactly 27 new assertions; the original 142 tests and the positive permissive-verifier fixture pass. This positive fixture alone was not treated as security proof: negative fixtures prove byte/signature rejection, and the real positive transfer path fails before implementation. The Android 9 follow-up RED (`71f5617`) has all prior 170 tests passing and exactly the new certificate-collection test failing.

The publication fixture initially used Files.readString/writeString, absent from this Android test compile classpath; those runs are compilation failures, not successful RED evidence. The fixture was corrected to byte-array I/O. An inert downloader also initially dereferenced a null callback; `b14eca1` corrects that stub and records assertion-only RED failures. The first GREEN candidate failed compilation because `Intent.setClipData` returns void; `71f5617` separates that call and adds the Android 9 regression. These failed attempts are retained below rather than omitted.

Two preflight submissions produced no job: an initial `HEAD` resolved the parent checkout's unrelated commit and was rejected; another `6fc4587` invocation from the coordinator default working directory reported an unapproved digest. Resubmitting the explicit SHA from the requested worktree passed the unchanged policy. Neither rejection was bypassed and neither caused a local build.

| Commit | Job | Result | Artifact directory |
| --- | --- | --- | --- |
| `8bef79a` | `haval-camera-8bef79a6a83d647e-20261102T232405Z-21117` | RED: 156 tests, 13 updater assertion failures | `/root/projects/haval-camera/.artifacts/ota-red-8bef79a` |
| `6fc4587` | `haval-camera-6fc45873c078335f-20261102T232603Z-17337` | RED: 160 tests, 17 updater assertion failures | `/root/projects/haval-camera/.artifacts/ota-red-6fc4587` |
| `e8cdf2b` | `haval-camera-e8cdf2bcd057603e-20261102T232642Z-13587` | Fixture compilation failure; not valid RED evidence | `/root/projects/haval-camera/.artifacts/ota-compile-e8cdf2b` |
| `828be42` | `haval-camera-828be42dec160554-20261102T232726Z-662` | Same fixture compilation failure; not valid RED evidence | `/root/projects/haval-camera/.artifacts/ota-compile-828be42` |
| `6229b9b` | `haval-camera-6229b9b40f0bb21c-20261102T232810Z-1256` | 167 tests, 24 updater failures; one null-callback stub error corrected next | `/root/projects/haval-camera/.artifacts/ota-red-6229b9b` |
| `b14eca1` | `haval-camera-b14eca1fb4189297-20261102T232928Z-22142` | Final feature RED: 170 tests, 27 updater assertion failures | `/root/projects/haval-camera/.artifacts/ota-red-b14eca1` |
| `3151774` | `haval-camera-3151774646b5c3af-20261102T233133Z-14529` | GREEN attempt: compilation failed on chained void Intent.setClipData | `/root/projects/haval-camera/.artifacts/ota-green-attempt-3151774` |
| `71f5617` | `haval-camera-71f561700c000ff4-20261102T233252Z-27514` | Android 9 RED: 171 tests, exactly 1 assertion failure | `/root/projects/haval-camera/.artifacts/ota-android9-red-71f5617` |
| `8753405` | `haval-camera-8753405da5a6bcb4-20261102T233341Z-27239` | GREEN: 171 tests, 0 failures/errors/skips; debug/release/package checks passed | `/root/projects/haval-camera/.artifacts/ota-green-8753405` |

The commands below were executed for the corresponding snapshots (all submissions from `/root/projects/haval-camera/.worktrees/continuous-trip-video` after correcting the initial preflight invocation). Fetch destinations were new directories:

```text
git push origin HEAD:codex/continuous-trip-video-impl
/usr/local/bin/mac-mini-build submit-git /root/projects/haval-camera android-camera-verify 8bef79a
/usr/local/bin/mac-mini-build status haval-camera-8bef79a6a83d647e-20261102T232405Z-21117
/usr/local/bin/mac-mini-build fetch haval-camera-8bef79a6a83d647e-20261102T232405Z-21117 /root/projects/haval-camera/.artifacts/ota-red-8bef79a
/usr/local/bin/mac-mini-build submit-git /root/projects/haval-camera android-camera-verify 6fc4587
/usr/local/bin/mac-mini-build status haval-camera-6fc45873c078335f-20261102T232603Z-17337
/usr/local/bin/mac-mini-build fetch haval-camera-6fc45873c078335f-20261102T232603Z-17337 /root/projects/haval-camera/.artifacts/ota-red-6fc4587
/usr/local/bin/mac-mini-build submit-git /root/projects/haval-camera android-camera-verify e8cdf2b
/usr/local/bin/mac-mini-build status haval-camera-e8cdf2bcd057603e-20261102T232642Z-13587
/usr/local/bin/mac-mini-build fetch haval-camera-e8cdf2bcd057603e-20261102T232642Z-13587 /root/projects/haval-camera/.artifacts/ota-compile-e8cdf2b
/usr/local/bin/mac-mini-build submit-git /root/projects/haval-camera android-camera-verify 828be42
/usr/local/bin/mac-mini-build status haval-camera-828be42dec160554-20261102T232726Z-662
/usr/local/bin/mac-mini-build fetch haval-camera-828be42dec160554-20261102T232726Z-662 /root/projects/haval-camera/.artifacts/ota-compile-828be42
/usr/local/bin/mac-mini-build submit-git /root/projects/haval-camera android-camera-verify 6229b9b
/usr/local/bin/mac-mini-build status haval-camera-6229b9b40f0bb21c-20261102T232810Z-1256
/usr/local/bin/mac-mini-build fetch haval-camera-6229b9b40f0bb21c-20261102T232810Z-1256 /root/projects/haval-camera/.artifacts/ota-red-6229b9b
/usr/local/bin/mac-mini-build submit-git /root/projects/haval-camera android-camera-verify b14eca1
/usr/local/bin/mac-mini-build status haval-camera-b14eca1fb4189297-20261102T232928Z-22142
/usr/local/bin/mac-mini-build fetch haval-camera-b14eca1fb4189297-20261102T232928Z-22142 /root/projects/haval-camera/.artifacts/ota-red-b14eca1
/usr/local/bin/mac-mini-build submit-git /root/projects/haval-camera android-camera-verify 3151774
/usr/local/bin/mac-mini-build status haval-camera-3151774646b5c3af-20261102T233133Z-14529
/usr/local/bin/mac-mini-build fetch haval-camera-3151774646b5c3af-20261102T233133Z-14529 /root/projects/haval-camera/.artifacts/ota-green-attempt-3151774
/usr/local/bin/mac-mini-build submit-git /root/projects/haval-camera android-camera-verify 71f5617
/usr/local/bin/mac-mini-build status haval-camera-71f561700c000ff4-20261102T233252Z-27514
/usr/local/bin/mac-mini-build fetch haval-camera-71f561700c000ff4-20261102T233252Z-27514 /root/projects/haval-camera/.artifacts/ota-android9-red-71f5617
/usr/local/bin/mac-mini-build submit-git /root/projects/haval-camera android-camera-verify 8753405
/usr/local/bin/mac-mini-build status haval-camera-8753405da5a6bcb4-20261102T233341Z-27239
/usr/local/bin/mac-mini-build fetch haval-camera-8753405da5a6bcb4-20261102T233341Z-27239 /root/projects/haval-camera/.artifacts/ota-green-8753405
```

The approved `android-camera-verify` job runs on the Mini:

```sh
./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug
bash build.sh
```

`build.sh` runs release assembly with strict dependency verification and retains apksigner, approved signer, zipalign, and unzip checks. Its new manifest script repeats the package checks before deriving the publication asset. No lenient verification or checksum regeneration was used.

Selected actual output from final feature RED:

```text
AppUpdaterTest > deletesOversizedDownload FAILED
AppUpdaterTest > downloadRequiresConsentAndIdleBeforeNetwork FAILED
AppUpdaterTest > optInUsesReleasesAndComparesInstalledCode FAILED
AppUpdaterTest > deletesCorruptDownload FAILED
AppUpdaterTest > stableCheckUsesLatestAndDoesNotDownloadApk FAILED
AppUpdaterTest > httpFailureDoesNotOfferUpdate FAILED
AppUpdaterTest > downloadsAndVerifiesExactApk FAILED
UpdateCacheTest > resolvesOnlySingleApkWithinUpdatesCache FAILED
UpdateCacheTest > refusesWriteModes FAILED
UpdateDownloadTest > streamsWithinBoundAndRejectsExtraByte FAILED
UpdateDownloadTest > refusesWithoutConsentOrWhileBusy FAILED
UpdateManifestTest > rejectsMalformedJsonAndWrongTypes FAILED
UpdateManifestTest > rejectsUnknownSchemaMissingDuplicateAndExtraFields FAILED
UpdateManifestTest > stableExcludesPrereleaseAndDraft FAILED
UpdateManifestTest > selectsExactApkEvenWithOtherApksPresent FAILED
UpdateManifestTest > rejectsMissingAndDuplicateAssets FAILED
UpdateManifestTest > rejectsAssetsOutsideOfficialHttpsRepository FAILED
UpdateManifestTest > rejectsInvalidSizesHashesAndPaths FAILED
UpdateManifestTest > ordersOnlyByVersionCode FAILED
UpdatePublicationTest > refusesDifferentPackage FAILED
UpdatePublicationTest > derivesManifestFromVerifiedApk FAILED
UpdatePublicationTest > refusesWrongCertificate FAILED
UpdateVerifierTest > requiresConnectedIdleService FAILED
UpdateVerifierTest > deletesWrongMissingMultipleSignerAndMismatchedPackageVersion FAILED
UpdateVerifierTest > deletesWhenArchiveReaderFails FAILED
UpdateVerifierTest > hashesCertificateBytesWithSha256 FAILED
UpdateVerifierTest > deletesWrongSizeAndHashBeforeInspectingSigner FAILED
170 tests completed, 27 failed
BUILD FAILED in 11s
finished=2026-11-02T23:29:42Z rc=1
```

Selected actual output from Android 9 RED:

```text
UpdateVerifierTest > android9ArchiveLookupCollectsAndExposesCurrentCertificates FAILED
171 tests completed, 1 failed
BUILD FAILED in 13s
finished=2026-11-02T23:33:08Z rc=1
```

Selected actual GREEN output:

```text
state=success
> Task :app:testDebugUnitTest
> Task :app:assembleDebug
BUILD SUCCESSFUL in 17s
> Task :app:lintVitalRelease
> Task :app:assembleRelease
BUILD SUCCESSFUL in 17s
Update manifest: /Users/consorciolimpagyn/BuildQueue/work/haval-camera-8753405da5a6bcb4-20261102T233341Z-27239/source/build/update-manifest.json
finished=2026-11-02T23:34:19Z rc=0
./build.log: OK
./haval-camera.apk: OK
```

Read-only inspection of the Mini's JUnit XML after GREEN returned:

```text
suites=28
{'tests': 171, 'failures': 0, 'errors': 0, 'skipped': 0}
```

This used `python3`/ElementTree on the Mini to sum `tests`, `failures`, `errors`, and `skipped` across `source/app/build/test-results/testDebugUnitTest/*.xml` in the GREEN job directory. It ran no additional build. The generated manifest was separately fetched over the already-authorized build-host SSH/SCP connection because the queue artifact copier returns only APK/log. On the coordinator, archive/checksum-only inspection confirmed its size/hash match the fetched APK:

```json
{
  "schemaVersion": 1,
  "versionCode": 2,
  "versionName": "0.2",
  "apk": "haval-camera.apk",
  "size": 3530029,
  "sha256": "d27fb192db1c6c868eaefdba7bc95fd1543651843d59aed2e38e807959d780bf"
}
```

GREEN archive SHA-256: `4cc60d8ef86a1e12e053bbc18e05021dd8477e826742a40e8e94cda4a14a63f2`.

| Evidence | SHA-256 |
| --- | --- |
| `8bef79a` build.log | `a9262f6d89fd813d8f54d5fb81c1f2299dc38fd5824df15166b593e366aba1d7` |
| `6fc4587` build.log | `916e918b648ea1ef7bdeec0c7068e7a1962d7057ad860b12258b6e7ab8c3f5fe` |
| `e8cdf2b` build.log | `e15b019114040bbaa72f17d0ba100fee24d940d437f88877e9ea30e4c84c2ee1` |
| `828be42` build.log | `e44ca8bfad85fa78a4702dfba19e683fd3fb65ae74f31c4dc647825e2b498c17` |
| `6229b9b` build.log | `7fb9c90ac3c2aab3a86af557e71655685cf52be9f2586a7e07ea3004e0523ae5` |
| `b14eca1` build.log | `eca3c841f0b1747abc82e024913b6dcb34d8f3cbdc22a38d3a7cc4f88c2b612b` |
| `3151774` build.log | `f660ca496d1a91e8f1f7ace9be22cd0760448c4771fcd927bc63c8210ebede66` |
| `71f5617` build.log | `3b5fd72f69969bf65ca4bfe1caee73327b95e49d3b4f774f326ad6471d2ee104` |
| `8753405` build.log | `8d37dd7134cfd3d7043bf0be8298662bef7813b3b8b46f5ae4043ba125a11e10` |
| GREEN `haval-camera.apk` | `d27fb192db1c6c868eaefdba7bc95fd1543651843d59aed2e38e807959d780bf` |
| GREEN `update-manifest.json` | `0421e8c225d92db6ddc9b720d94b74ad4dd78a3e76ee5136e64392bab5067546` |

## Files and commits

- `README.md`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/br/com/redesurftank/havalcamera/AppUpdater.java`
- `app/src/main/java/br/com/redesurftank/havalcamera/TripVideoService.java`
- `app/src/main/java/br/com/redesurftank/havalcamera/UpdateCache.java`
- `app/src/main/java/br/com/redesurftank/havalcamera/UpdateDownload.java`
- `app/src/main/java/br/com/redesurftank/havalcamera/UpdateFileProvider.java`
- `app/src/main/java/br/com/redesurftank/havalcamera/UpdateManifest.java`
- `app/src/main/java/br/com/redesurftank/havalcamera/UpdateVerifier.java`
- `app/src/main/java/br/com/redesurftank/havalcamera/VideoSettingsActivity.java`
- `app/src/main/res/xml/update_paths.xml`
- `app/src/test/java/br/com/redesurftank/havalcamera/AppUpdaterTest.java`
- `app/src/test/java/br/com/redesurftank/havalcamera/UpdateCacheTest.java`
- `app/src/test/java/br/com/redesurftank/havalcamera/UpdateDownloadTest.java`
- `app/src/test/java/br/com/redesurftank/havalcamera/UpdateManifestTest.java`
- `app/src/test/java/br/com/redesurftank/havalcamera/UpdatePublicationTest.java`
- `app/src/test/java/br/com/redesurftank/havalcamera/UpdateVerifierTest.java`
- `build.sh`
- `scripts/write-update-manifest.sh`

`UpdateManifest` owns strict JSON/release/asset decisions; `UpdateVerifier` owns pure hash/identity/signer decisions and a thin PackageManager adapter; `AppUpdater` owns HTTP and cancellable file transfer. `UpdateDownload`/`UpdateCache` isolate small streaming/consent/path decisions for actual JVM behavior tests. `VideoSettingsActivity` owns the dialogs, permission flow, and last-moment binder gate. `TripVideoService` gains only the read-only query. The manifest/XML/provider are the cache handoff. The publication script/build.sh derive the release JSON. README documents commands, behavior, limits, and deliberate Gradle non-change. `docs/VALIDATION.md` and this report record the final evidence.

```text
8bef79a test: define strict signed update boundary
6fc4587 test: require consent and bounded cache downloads
e8cdf2b test: require verified APK publication metadata
828be42 test: define GitHub check and download consent flow
6229b9b test: use Android-compatible publication fixture IO
b14eca1 test: exercise APK transfer verification and cleanup
3151774 feat: add confirmed signed updates
71f5617 test: require Android 9 archive certificate collection
8753405 fix: collect verified APK certificates on Android 9
```

## Self-review and verification limits

- `git diff dd777dd..8753405 --check` and `bash -n build.sh scripts/write-update-manifest.sh` passed. Both changed XML documents parsed successfully on the coordinator. These are allowed static checks, not a local Android build.
- Reviewed request/response bounds, strict JSON types/duplicates, exact asset and official URL checks, verifier ordering and deletion, current signer pinning, Android 9 certificate collection, canonical provider paths/read mode, lifecycle callback guards, both confirmation points, and idle recheck before installer handoff.
- The shared Back wrapper, ActivityNavigation, active-Wi-Fi sharing, CameraActivity, CameraCaptureClient, and native capture sources are unchanged. Existing navigation/Wi-Fi tests passed in GREEN. Only the binder query reads service state; it cannot stop or start capture.
- No controller ledger changed and no subagent/independent reviewer was dispatched, as instructed.
- JVM tests execute real parsing, hashing, file deletion, bounded streams, path/mode decisions, consent guards, and the updater using real OkHttp with injected HTTP responses. PackageManager archive facts are injected in those tests; the actual Android archive parser, dialogs, intent resolver, provider grants and installer are not executed by JVM tests. Publication tests run the actual shell script with controlled SDK-tool fixtures; GREEN additionally runs it with real SDK tools and the signed APK on the Mini.
- The queue retained seven existing AndroidX annotation warnings, deprecated-API notes, and the existing extractNativeLibs packaging warning. There were no GREEN test failures or lint/build failures.
- Hardware/ARTEMIS UI and installation checks were not run. Android 9 physical confirmation/permission/cancel/resume behavior, OEM installer acceptance of the read-only URI, signing-info runtime behavior, capture transition races around the external installer, and recording continuity remain device-validation concerns. No hardware-compatibility claim is made.
- The separate `Muxer is closed` capture defect is untouched. Updating from a differently signed debug install will still be refused by Android; the updater does not weaken Android's signer rules. GitHub release publication/network availability and API rate limits were not tested live; errors fail closed.
- No version bump is included: the verified current artifact remains versionCode 2 / versionName 0.2. A future authorized release must have a greater versionCode and attach the matching APK plus generated manifest to be offered as an update.


## Fix round 1/5 — guided viewing closure and recording idle wait

**Status: DONE_WITH_CONCERNS.** Both review findings are addressed. Final executable source for this round is `e76c929bc27f060176db9165c82a14b32539c82b`, pushed to `origin/codex/continuous-trip-video-impl`. This section supersedes the original report's immediate-busy-rejection behavior and its statement that the binder only has a read-only query. The baseline for this round is `42666c0`.

### Review findings, verbatim

```text
1. Important — VideoSettingsActivity.java:103-106,115-116,135-143,152-174: Busy state immediately blocks checking, downloading, or installation and merely tells the user to retry. Impact: there is no required guided flow to end viewing and wait for recording to finish; continuous recording can leave updates indefinitely inaccessible. Fix: offer an explicit “encerrar visualização” action, close only viewing, enter a waiting state, observe service idleness without interrupting recording, and open the installer only after idle is confirmed.
2. Important — UpdateVerifierTest.java:62-67: The idle test asserts only a pure Boolean helper, not the required user flow. Impact: all tests can pass while the viewing offer and idle wait are entirely absent. Fix: add a behavior-level test covering busy → offer to end viewing → wait while recording remains active → idle → installer handoff, including proof that recording is never stopped.
```

### Changed behavior and responsible boundaries

- `VideoSettingsActivity` permits metadata checks during recording. After **Baixar** or **Instalar** is confirmed, a busy service produces an explicit **Encerrar visualização** offer explaining that the app will restart and that recordings continue. Acceptance closes viewing and enters a visible waiting state with **Cancelar espera**. The same confirmed action resumes when the service becomes idle; the user does not need to keep retrying.
- `UpdateIdleFlow` is a small JVM-only controller used by the actual Activity for both download and installation. It captures the confirmed continuation, closes viewing only after acceptance, polls idleness through the Host boundary, resumes once, and invalidates stale acceptance/poll callbacks after cancellation or completion. It exposes no recording-stop operation. The Activity supplies a main-thread Handler observation every 500 ms and stops it on cancellation/pause.
- `TripVideoService.LocalBinder.closeViewingForUpdate()` clears only preview consumers, closes the live relay, applies the new recording-preserving `viewerDismissed` event, and invokes the existing `stopCaptureIfUnused()` check. It does not call any recording-stop command, force capture stop, or service shutdown.
- `FrameRouter.closePreviews()` clears only the preview set; its encoder destination remains attached. `ServiceStateReducer.VIEWER_DISMISSED` removes the viewer and clears the viewer-loss grace timestamp while preserving trip identity, recording mode, manual/parked flags, and the parked recording's existing deadline. Reusing VIEWER_LOST would have ended parked recording after the grace period, so a separate event is necessary. The existing recording owner and normal service timing still decide when recording ends; the updater never adds a forced timeout.
- A disconnect is not idle. The pending flow keeps observing and can resume after reconnection. Canceling the wait or pausing Settings clears the continuation/observer and discards an unhanded-off APK. No installer is opened by a stale poll.
- If recording starts during download, the verified APK is retained and the install confirmation uses the same guided gate. If capture restarts during asynchronous final verification, the Activity re-enters the guided gate, waits, and verifies the APK again before handoff. The installed-version check, current signer pin, content URI grant, Android permission flow, and explicit Android installer confirmation remain intact.
- Active-Wi-Fi selection, the shared Back wrapper, CameraActivity, CameraCaptureClient, native source, build controls, signing policy, and dependencies are unchanged. No subagent or controller-ledger change was made.

### Focused behavioral proof

`UpdateIdleFlowTest` has seven tests. Its main scenario executes the real controller together with real `ServiceStateReducer` and `FrameRouter` instances for automatic, manual, and parked recording. It verifies this sequence:

1. Busy → the end-viewing offer is emitted, without closing viewing or handing off installation yet.
2. Accept → previews disappear and the reducer becomes RECORDING, preserving the same trip. Publishing a real frame still reaches the encoder but no preview consumer.
3. Wait → repeated scheduled checks and a tick beyond the old viewer-loss grace period leave the recording/trip intact and do not hand off the installer. No STOP_RECORDING, PAUSE_RECORDING, START_RECORDING, or FINALIZE_TRIP effect is emitted by this operation.
4. An explicit test event from the external recording owner ends recording; the controller never invokes that event. The next idle observation invokes the installer continuation exactly once, clears observation, and ignores an old queued poll.

Additional cases cover viewing-only closure, an initially idle service, canceling the offer and wait, disconnected/reconnected state, recording becoming busy again before install, and failure to close viewing. These are executable behavior tests, not Boolean-helper assertions or source-text checks.

`UpdateSettingsWiringTest` adds two compiled call-path checks on the Mini's JDK 17 `javap` output: the Activity implements the controller Host, routes download/install through `whenIdle`, cancels on pause, builds the explicit end-viewing action, and wires the Handler observation and binder action. The binder invokes preview closure and the recording-preserving reducer event. These checks connect the behavior test to the shipped Android adapter, but do not execute Android widgets, Binder lifecycle, or the system installer. No mobile UI test or device validation is claimed; ARTEMIS is not exposed in this session.

### Immutable RED and GREEN commands/output

RED commit: `bbe645d5b571e05bea1f27de59c2ba63913a8899` — tests plus inert new flow/preview methods and a viewerDismissed baseline mapped to viewerLost. The existing Activity is unchanged in this RED snapshot. The new initial-idle test passes, while eight new regressions fail; all 171 previous tests pass.

Executed from `/root/projects/haval-camera/.worktrees/continuous-trip-video`:

```text
git push origin HEAD:codex/continuous-trip-video-impl
/usr/local/bin/mac-mini-build submit-git /root/projects/haval-camera android-camera-verify bbe645d
content_files_scanned=108
job=haval-camera-bbe645d5b571e05b-20261102T234529Z-5925 pid=83948 state=queued
job=haval-camera-bbe645d5b571e05b-20261102T234529Z-5925 archive_sha256=06cf0e6f1f6c600ecac571a442840f15634895f6c037e43edd902a5d7e76035f
/usr/local/bin/mac-mini-build status haval-camera-bbe645d5b571e05b-20261102T234529Z-5925
state=failed
UpdateIdleFlowTest > dismissesViewingOnlyWithoutStartingOrStoppingRecording FAILED
UpdateIdleFlowTest > cancellationInvalidatesOfferAndQueuedPoll FAILED
UpdateIdleFlowTest > disconnectedServiceCannotCompleteWait FAILED
UpdateIdleFlowTest > failedViewingCloseNeverContinues FAILED
UpdateIdleFlowTest > busyOffersViewingThenWaitsForRecordingBeforeInstallerHandoff FAILED
UpdateIdleFlowTest > busyAgainBeforeInstallOffersAndWaitsAgain FAILED
UpdateSettingsWiringTest > binderClosesOnlyViewingThroughRecordingPreservingEvent FAILED
UpdateSettingsWiringTest > settingsUsesGuidedGateForDownloadAndInstallAndCancelsOnPause FAILED
180 tests completed, 8 failed
BUILD FAILED in 18s
finished=2026-11-02T23:45:50Z rc=1
/usr/local/bin/mac-mini-build fetch haval-camera-bbe645d5b571e05b-20261102T234529Z-5925 /root/projects/haval-camera/.artifacts/ota-fix1-red-bbe645d
./build.log: OK
```

GREEN commit: `e76c929bc27f060176db9165c82a14b32539c82b` — production flow, Activity/service integration, view-only reducer/fanout implementation, and README behavior update.

```text
git push origin HEAD:codex/continuous-trip-video-impl
/usr/local/bin/mac-mini-build submit-git /root/projects/haval-camera android-camera-verify e76c929
content_files_scanned=108
job=haval-camera-e76c929bc27f0601-20261102T234743Z-18918 pid=87510 state=queued
job=haval-camera-e76c929bc27f0601-20261102T234743Z-18918 archive_sha256=e7601d8dd85f0588a6936e876103bc14572f5374969135346b17b55dac7c6212
/usr/local/bin/mac-mini-build status haval-camera-e76c929bc27f0601-20261102T234743Z-18918
state=success
> Task :app:testDebugUnitTest
> Task :app:assembleDebug
BUILD SUCCESSFUL in 22s
> Task :app:lintVitalRelease
> Task :app:assembleRelease
BUILD SUCCESSFUL in 17s
Update manifest: /Users/consorciolimpagyn/BuildQueue/work/haval-camera-e76c929bc27f0601-20261102T234743Z-18918/source/build/update-manifest.json
finished=2026-11-02T23:48:27Z rc=0
/usr/local/bin/mac-mini-build fetch haval-camera-e76c929bc27f0601-20261102T234743Z-18918 /root/projects/haval-camera/.artifacts/ota-fix1-green-e76c929
./build.log: OK
./haval-camera.apk: OK
```

The unchanged authorized job runs `./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug`, followed by `bash build.sh`. GREEN passed unit tests, debug, signed release, lint vital, pinned signer verification, alignment, ZIP integrity, and real manifest generation. No coordinator build or SDK command ran. `git diff 42666c0..e76c929 --check` also passed.

Read-only inspection of the GREEN Mini JUnit XML returned:

```text
suites=30
{'tests': 180, 'failures': 0, 'errors': 0, 'skipped': 0}
[('br.com.redesurftank.havalcamera.UpdateIdleFlowTest', '7', '0'), ('br.com.redesurftank.havalcamera.UpdateSettingsWiringTest', '2', '0')]
```

| Evidence | SHA-256 |
| --- | --- |
| RED build.log | `cba959e3bcb2f83876ed7ac61d8cf51d84eef26819acd6cc359b4260646c5cad` |
| GREEN build.log | `accea8b187cba9a1b320fd7949ab8654c4c4d2dccae656a843c6c9d3546c8374` |
| GREEN signed APK | `a7052fc7148c886653da76cf39765d6b33e32deaa38f7b310c47c0d037e26003` |

### Files, self-review, and concerns

Changed executable files are `UpdateIdleFlow.java`, `VideoSettingsActivity.java`, `TripVideoService.java` (local binder method only), `FrameRouter.java` (preview-consumer clearing only), and `ServiceStateReducer.java` (new view-dismissal event). New focused tests are `UpdateIdleFlowTest.java` and `UpdateSettingsWiringTest.java`. README documents the guided behavior; docs/VALIDATION.md and this appended section record evidence. This round's report commit contains documentation only after GREEN.

Self-review traced both Activity gates, all cancellation paths, the post-verification busy race, the main-thread observer, and the binder → live-sink release / preview removal → reducer-effects path. The real reducer/fanout tests prove preservation of recording state and encoder frame delivery at the tested boundary. No stop-recording API was added to the controller or invoked by the updater. The prior pure Boolean helper remains as a low-level test; it is no longer the only flow evidence.

Concerns remain limited to the unexecuted physical Android 9 flow: rendering/taps, actual Binder/service lifecycle, camera/encoder continuity on hardware, and OEM installer/URI handling. The JVM wiring check is a compiled structure check, not a UI runtime test. The existing parked recording deadline is preserved, and a recording that does not end keeps the update waiting until the user cancels or recording ends normally; there is deliberately no forced recording stop. Known annotation/deprecated-API/extractNativeLibs warnings remain. No installation/publication, dependency addition, native capture change, controller-ledger edit, or subagent dispatch occurred.

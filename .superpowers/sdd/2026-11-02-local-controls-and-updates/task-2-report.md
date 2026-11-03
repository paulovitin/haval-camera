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

## Fix round 1 — complete the contract proof

The original direct-listener test could pass if one Activity omitted its button. The four Activities now inherit `BackNavigationActivity`. Its final `setContentView(View)` wraps their existing content with a top-left `ActivityNavigation.backButton(this)`, so the current content path installs the same action for every screen. The Android factory delegates label and click configuration to a JVM-testable `configureBack` method; the test observes the actual `Voltar` label and invokes the configured click to check one finish callback. Reflection tests require all four Activity classes to inherit the base and its `setContentView(View)` implementation to be final. These are JVM contract checks, not Android view execution or physical visibility proof. The gallery's five static children and active-Wi-Fi sharing logic remain unchanged because the Back button sits outside its list.

**Fix-round commits:**

| Commit | Purpose |
| --- | --- |
| `f36367c75845322b6a8b47a642369c04fb724d9d` | RED tests for all four screens and configured label/click, with inert stubs |
| `f62700c17590cfccbac86919fc57e9e7a17a0927` | RED test requiring a final shared `setContentView(View)` method |
| `6dc12e32741435403bc2e83617b5ad714f9d08c8` | Shared layout insertion and finish-only configuration |

**RED 1 commands and output:**

```text
git push origin HEAD:codex/continuous-trip-video-impl
/usr/local/bin/mac-mini-build submit-git /root/projects/haval-camera android-camera-verify f36367c
content_files_scanned=90
job=haval-camera-f36367c75845322b-20261102T230323Z-3820 pid=17688 state=queued
archive_sha256=dae94daca9e0c1b5eaa9cb1f53c7d3a7b356728641d4a788e87674e64a799195
/usr/local/bin/mac-mini-build status haval-camera-f36367c75845322b-20261102T230323Z-3820
state=failed
ActivityNavigationTest > backButtonConfigurationLabelsAndFinishes FAILED
    java.lang.AssertionError at ActivityNavigationTest.java:29
ActivityNavigationTest > everyScreenInheritsAutomaticBackLayout FAILED
    java.lang.AssertionError at ActivityNavigationTest.java:16
139 tests completed, 2 failed
BUILD FAILED in 16s
finished=2026-11-02T23:03:42Z rc=1
/usr/local/bin/mac-mini-build fetch haval-camera-f36367c75845322b-20261102T230323Z-3820 /root/projects/haval-camera/.artifacts/back-navigation-fix1-red-f36367c
./build.log: OK
```

RED 1 log SHA-256: `593a260596154a531a01a4e5ab8f73d6c65ffecc042e6c9d2e17f1459467bc6c`.

**RED 2 commands and output:**

```text
git push origin HEAD:codex/continuous-trip-video-impl
/usr/local/bin/mac-mini-build submit-git /root/projects/haval-camera android-camera-verify f62700c
content_files_scanned=90
job=haval-camera-f62700c17590cfcc-20261102T230437Z-2829 pid=19630 state=queued
archive_sha256=a941cce130abaec090b4054f9fc2261a56fa5c0f9b4adeb5294cf20d7eb389e9
/usr/local/bin/mac-mini-build status haval-camera-f62700c17590cfcc-20261102T230437Z-2829
state=failed
ActivityNavigationTest > backButtonConfigurationLabelsAndFinishes FAILED
    java.lang.AssertionError at ActivityNavigationTest.java:38
ActivityNavigationTest > sharedContentViewInstallationIsFinal FAILED
    java.lang.AssertionError at ActivityNavigationTest.java:16
ActivityNavigationTest > everyScreenInheritsAutomaticBackLayout FAILED
    java.lang.AssertionError at ActivityNavigationTest.java:25
140 tests completed, 3 failed
BUILD FAILED in 10s
finished=2026-11-02T23:04:50Z rc=1
/usr/local/bin/mac-mini-build fetch haval-camera-f62700c17590cfcc-20261102T230437Z-2829 /root/projects/haval-camera/.artifacts/back-navigation-fix1-red-f62700c
./build.log: OK
```

RED 2 log SHA-256: `c0b8d29f2acfde825c3e5005651ee6ad0ac728f81f8bd69c373f2ede63faba64`.

**GREEN commands and output:**

```text
git push origin HEAD:codex/continuous-trip-video-impl
/usr/local/bin/mac-mini-build submit-git /root/projects/haval-camera android-camera-verify 6dc12e3
content_files_scanned=90
job=haval-camera-6dc12e3274143540-20261102T230556Z-15963 pid=21719 state=queued
archive_sha256=110c372b7138021436b8efc6c5cdbf8d5259fa487074bdb522530b6988db1a3f
/usr/local/bin/mac-mini-build status haval-camera-6dc12e3274143540-20261102T230556Z-15963
state=success
> Task :app:testDebugUnitTest
> Task :app:assembleDebug
BUILD SUCCESSFUL in 19s
> Task :app:lintVitalRelease
> Task :app:assembleRelease
BUILD SUCCESSFUL in 17s
No errors detected in compressed data of /Users/consorciolimpagyn/BuildQueue/work/haval-camera-6dc12e3274143540-20261102T230556Z-15963/source/build/haval-camera.apk.
APK: /Users/consorciolimpagyn/BuildQueue/work/haval-camera-6dc12e3274143540-20261102T230556Z-15963/source/build/haval-camera.apk
finished=2026-11-02T23:06:37Z rc=0
/usr/local/bin/mac-mini-build fetch haval-camera-6dc12e3274143540-20261102T230556Z-15963 /root/projects/haval-camera/.artifacts/back-navigation-fix1-green-6dc12e3
./build.log: OK
./haval-camera.apk: OK
```

GREEN log SHA-256: `e68b0d21d7cf7410878a61e38d5b60903767507aaa513e34f265786d47cbd95d`. Fetched release APK SHA-256: `9da1173126e623bdd8f853a9f252711949f718588268a51a448b20d89aed948d`. The full JVM suite, debug build, release build, lint vital, release signer check, ZIP alignment, and ZIP integrity passed in the authorized queue; the known annotation/deprecation/manifest warnings remain.

**Self-review and limits:** `git diff ac855ce..6dc12e3 --check` passed. All four current screens call `setContentView(View)`, the final base method inserts exactly one Back button before their content, and `configureBack` attaches only a finish callback. The gallery renderer again retains its original five static children; no Task 1 Wi-Fi selection or sharing code changed. No local build, new dependency, source-text UI test, controller-ledger edit, or subagent dispatch occurred. The contract is limited to the current `setContentView(View)` path. The Android `Button` and layout remain unexecuted by JVM tests; Task 5 must inspect visibility, touch, and service continuity on the Android 9 unit.

## Fix round 2 — compiled production call path

**Status: DONE_WITH_CONCERNS.** The existing Gradle JVM test stack cannot instantiate Android `Activity`, `Button`, or layout views without an Android runtime or a new test dependency. The new checks inspect the compiled production `.class` files with the existing JDK 17 `javap`, not source text. They check that each Activity's `onCreate` calls `setContentView(View)`, the final wrapper calls `ActivityNavigation.backButton` before a `LinearLayout.addView` call and installs the root, and `backButton(Activity)` calls `configureBack`. They also check the compiled adapter's `Button.setText` and `Button.setOnClickListener` calls, `configureBack`'s `Voltar` label and `backClick` call, and the factory class's `Activity.finish` method handle. The existing JVM listener test executes the configured click. This is a structural call-path contract; it does not prove operand-level data flow, execute Android UI, or prove physical visibility, touch response, or recording continuity.

To prove the checks can fail, the RED commit deliberately removed the wrapper's button insertion and changed `backButton(Activity)` to call `Activity.recreate()` directly. The queue ran 142 JVM tests and failed exactly the two new bytecode checks. The GREEN commit restored the original production code with the checks retained; the authorized queue then passed. The final source diff for this round contains only the test additions. No dependency, controller ledger, or other production behavior remains changed by this round.

**Commits:** `61246fc18ae9d2706c2fc7b100102e05584ecddf` (RED mutation and tests); `48bae5c3026b2215837075911b845295334e70ac` (GREEN restoration).

**RED commands and output:**

```text
git push origin HEAD:codex/continuous-trip-video-impl
/usr/local/bin/mac-mini-build submit-git /root/projects/haval-camera android-camera-verify 61246fc
content_files_scanned=90
job=haval-camera-61246fc18ae9d270-20261102T231123Z-22631 pid=30297 state=queued
archive_sha256=862bd5808fc4518f0675cd7d0d0564b1c2e17c2ed1d6698e06f4e668cb720dcc
/usr/local/bin/mac-mini-build status haval-camera-61246fc18ae9d270-20261102T231123Z-22631
state=failed
ActivityNavigationTest > compiledFactoryConnectsVoltarToActivityFinish FAILED
    java.lang.AssertionError at ActivityNavigationTest.java:36
ActivityNavigationTest > compiledScreenLayoutsInstallTheBackButton FAILED
    java.lang.AssertionError at ActivityNavigationTest.java:23
142 tests completed, 2 failed
BUILD FAILED in 14s
finished=2026-11-02T23:11:41Z rc=1
/usr/local/bin/mac-mini-build fetch haval-camera-61246fc18ae9d270-20261102T231123Z-22631 /root/projects/haval-camera/.artifacts/back-navigation-fix2-red-61246fc
./build.log: OK
```

RED build-log SHA-256: `11932bceff3e8e050d283ec6f49dba861995946e8a457dc178dfc44caac255ac`.

**GREEN commands and output:**

```text
git push origin HEAD:codex/continuous-trip-video-impl
/usr/local/bin/mac-mini-build submit-git /root/projects/haval-camera android-camera-verify 48bae5c
content_files_scanned=90
job=haval-camera-48bae5c3026b2215-20261102T231202Z-2852 pid=31365 state=queued
archive_sha256=4481cbf359975d8bbbc8b36fb6a16790ff26be2b916ca74ba46fd7325a86bec0
/usr/local/bin/mac-mini-build status haval-camera-48bae5c3026b2215-20261102T231202Z-2852
state=success
> Task :app:testDebugUnitTest
> Task :app:assembleDebug
BUILD SUCCESSFUL in 20s
> Task :app:lintVitalRelease
> Task :app:assembleRelease
BUILD SUCCESSFUL in 17s
No errors detected in compressed data of /Users/consorciolimpagyn/BuildQueue/work/haval-camera-48bae5c3026b2215-20261102T231202Z-2852/source/build/haval-camera.apk.
finished=2026-11-02T23:12:44Z rc=0
/usr/local/bin/mac-mini-build fetch haval-camera-48bae5c3026b2215-20261102T231202Z-2852 /root/projects/haval-camera/.artifacts/back-navigation-fix2-green-48bae5c
./build.log: OK
./haval-camera.apk: OK
```

GREEN build-log SHA-256: `adce5febfae80d560c7a5a3d8c5a66d2c46c9b0ff208700894126f5d15fab308`; release APK SHA-256: `6daba7d44a3bd09e3de5a11c47d5144ef21b2f1280be4aa516c9c53cb951687f`. The queue completed the unit suite, debug build, release build, lint vital, signature pin, alignment, and ZIP integrity checks. Existing annotation, deprecated-API, and manifest warnings remain. `git diff b2ee151..48bae5c --check` passed. No local Android build or subagent ran.

## Fix round 3 — operand and listener installation checks

**Status: DONE_WITH_CONCERNS.** The compiled wrapper check now verifies that the root and Activity are loaded before `backButton`, and that only `LinearLayout.LayoutParams` construction occurs between the factory return and the *first* `addView`. No `pop`, store, or alternate View load can discard or replace that return value. It also verifies the second `addView` consumes the original content afterward. The factory check requires one `configureBack` call and one `Button.setOnClickListener` instruction in the compiled class; the adapter loads the Button and configured listener arguments directly into that setter. The existing JVM behavior test executes that configured listener and observes one finish callback. These checks depend on the current JDK 17 `javap` instruction format and prove the named compiled call paths, not Android UI rendering or physical recording continuity.

The first RED commit discarded the factory result, inserted a different `View`, and overwrote the click listener with `Activity.recreate()`. The queue ran 142 tests and failed exactly the layout and factory checks. After restoration, GREEN passed. A second RED commit invoked `configureBack` twice with the same installer, leaving only one setter instruction in the class; the new invocation-count assertion failed. Final GREEN restored the single call. The final production files match the pre-round code; only `ActivityNavigationTest.java` changed. `git diff 0caabc7..e422f9d --check` passed. No dependency, source-text UI test, local build, ledger edit, or subagent was added.

**Commits:** `45e370863354d141e7b41a78188c45e472235875` (RED discarded button and replaced click); `7f7b7722d77502f323ea234b764b3f0c0f9a651f` (GREEN restoration); `419ef237bffc4b2b9c4fc212c2d9d2e1691d4ab9` (RED duplicate configuration); `e422f9d0ca3b87e926ac3eb0019d307a12670269` (final GREEN).

**RED 1 commands and output:**

```text
git push origin HEAD:codex/continuous-trip-video-impl
/usr/local/bin/mac-mini-build submit-git /root/projects/haval-camera android-camera-verify 45e3708
content_files_scanned=90
job=haval-camera-45e370863354d141-20261102T231650Z-4640 pid=38884 state=queued
archive_sha256=e7324756fd9c90adfaf5a783431be4f0ca904bbb6cc2900ad724b9a9878b8999
/usr/local/bin/mac-mini-build status haval-camera-45e370863354d141-20261102T231650Z-4640
state=failed
ActivityNavigationTest > compiledFactoryConnectsVoltarToActivityFinish FAILED
    java.lang.AssertionError at ActivityNavigationTest.java:57
ActivityNavigationTest > compiledScreenLayoutsInstallTheBackButton FAILED
    org.junit.ComparisonFailure at ActivityNavigationTest.java:29
142 tests completed, 2 failed
BUILD FAILED in 15s
finished=2026-11-02T23:17:07Z rc=1
/usr/local/bin/mac-mini-build fetch haval-camera-45e370863354d141-20261102T231650Z-4640 /root/projects/haval-camera/.artifacts/back-navigation-fix3-red-45e3708
./build.log: OK
```

RED 1 log SHA-256: `0ef078510d8dabf34d2109cde4b1e4c57bd0555950efd19b1aba1ab50d88c5aa`.

**GREEN 1 commands and output:**

```text
git push origin HEAD:codex/continuous-trip-video-impl
/usr/local/bin/mac-mini-build submit-git /root/projects/haval-camera android-camera-verify 7f7b772
content_files_scanned=90
job=haval-camera-7f7b7722d77502f3-20261102T231731Z-11129 pid=39944 state=queued
archive_sha256=08257b4ce8921d78a877e205c54f539cf6a1e5397d0fa8b2bdcd94ae826fd26e
/usr/local/bin/mac-mini-build status haval-camera-7f7b7722d77502f3-20261102T231731Z-11129
state=success
> Task :app:testDebugUnitTest
> Task :app:assembleDebug
BUILD SUCCESSFUL in 15s
> Task :app:lintVitalRelease
> Task :app:assembleRelease
BUILD SUCCESSFUL in 17s
finished=2026-11-02T23:18:07Z rc=0
/usr/local/bin/mac-mini-build fetch haval-camera-7f7b7722d77502f3-20261102T231731Z-11129 /root/projects/haval-camera/.artifacts/back-navigation-fix3-green-7f7b772
./build.log: OK
./haval-camera.apk: OK
```

GREEN 1 log SHA-256: `276d87a73020fb128cb86698280c2bfad9f7fd273f829b407ef8869aae69d089`; APK SHA-256: `c789fd62c0223248920914b4a770cca429aac6c56d5b29128186e74229bc4c71`.

**RED 2 commands and output:**

```text
git push origin HEAD:codex/continuous-trip-video-impl
/usr/local/bin/mac-mini-build submit-git /root/projects/haval-camera android-camera-verify 419ef23
content_files_scanned=90
job=haval-camera-419ef237bffc4b2b-20261102T231907Z-24777 pid=42376 state=queued
archive_sha256=f1d7a967f77d7c4e2549ae8f60d5d54a5508f6546e9deff7471738a33777c54d
/usr/local/bin/mac-mini-build status haval-camera-419ef237bffc4b2b-20261102T231907Z-24777
state=failed
ActivityNavigationTest > compiledFactoryConnectsVoltarToActivityFinish FAILED
    java.lang.AssertionError at ActivityNavigationTest.java:55
142 tests completed, 1 failed
BUILD FAILED in 11s
finished=2026-11-02T23:19:20Z rc=1
/usr/local/bin/mac-mini-build fetch haval-camera-419ef237bffc4b2b-20261102T231907Z-24777 /root/projects/haval-camera/.artifacts/back-navigation-fix3-red-duplicate-419ef23
./build.log: OK
```

RED 2 log SHA-256: `a6823ea02ac9b1057f91de78f71f6549c862db1b0638e38831210dc04f5a8d99`.

**Final GREEN commands and output:**

```text
git push origin HEAD:codex/continuous-trip-video-impl
/usr/local/bin/mac-mini-build submit-git /root/projects/haval-camera android-camera-verify e422f9d
content_files_scanned=90
job=haval-camera-e422f9d0ca3b87e9-20261102T231943Z-28867 pid=43346 state=queued
archive_sha256=9b945ca749df807caf1f8c9050f1e2794a33e0cd74523340003ab14851773f44
/usr/local/bin/mac-mini-build status haval-camera-e422f9d0ca3b87e9-20261102T231943Z-28867
state=success
> Task :app:testDebugUnitTest
> Task :app:assembleDebug
BUILD SUCCESSFUL in 15s
> Task :app:lintVitalRelease
> Task :app:assembleRelease
BUILD SUCCESSFUL in 17s
No errors detected in compressed data of /Users/consorciolimpagyn/BuildQueue/work/haval-camera-e422f9d0ca3b87e9-20261102T231943Z-28867/source/build/haval-camera.apk.
finished=2026-11-02T23:20:19Z rc=0
/usr/local/bin/mac-mini-build fetch haval-camera-e422f9d0ca3b87e9-20261102T231943Z-28867 /root/projects/haval-camera/.artifacts/back-navigation-fix3-green-final-e422f9d
./build.log: OK
./haval-camera.apk: OK
```

Final GREEN log SHA-256: `e323dc29424ee9dc203bf4af278034701e980900653e97a59bf6b744da205073`; APK SHA-256: `8a39215ee0463238e098483b78f153bef47b59396e0a07c003f339dd114a849a`. Authorized queue passed JVM, debug, release, lint vital, signer pin, ZIP alignment, and ZIP integrity. The known annotation/deprecation/manifest warnings remain. Android 9 physical visibility, touch, and active-recording continuity remain Task 5 evidence.

# Task 1 report: Active Wi-Fi sharing

Status: COMPLETE — implementation, immutable RED/GREEN queue verification, and author self-review. Physical Android/vehicle validation remains pending.

## Scope and baseline

- Worktree: `/root/projects/haval-camera/.worktrees/continuous-trip-video`.
- Branch: `codex/continuous-trip-video-impl`.
- Start commit: `b0df973ecafc8e3f76739a100d494ad9fbe99bd2`.
- Read `AGENTS.md`, the exact Task 1 brief, the approved `docs/superpowers/specs/2026-11-02-local-controls-and-updates-design.md`, the Task 1 plan, existing ledger, callers, server/security implementation, manifest, build policy, and existing tests before changes. Requirements were clear; no clarification was needed.
- The existing ledger identifies the unchanged product-tree baseline as Mac Mini job `haval-camera-3a07077da19f71a7-20261102T222305Z-16002`, with 126 passing tests. The RED run independently executed those existing tests again.
- No subagents were dispatched. No Android, Gradle, Kotlin, SDK, Android javac, NDK/CMake, Xcode, or application tests ran on the coordinator. No installation, vehicle access, release publication, or signing material transfer occurred.

## Commits

| Role | Commit | Subject |
|---|---|---|
| Immutable tests-only RED | `5f4f93ac40dced1a31530baeb528aaf9fa750504` | `test: define active wifi sharing contracts` |
| Immutable implementation GREEN | `79fe2b5e47a9787b9e879a9e2042c07942737390` | `fix: bind sharing to active wifi` |
| Evidence/report only | The commit introducing this report | `docs: record active wifi sharing verification` |

The RED and GREEN commits were pushed, in order, to the existing feature branch so the queue could verify their advertised immutable Git refs under its existing approved policy. No history was rewritten. The final evidence/report commit changes documentation only; the tested product tree is GREEN above.

## Implementation and files

| File | Change |
|---|---|
| `app/src/main/java/br/com/redesurftank/havalcamera/ActiveWifiAddress.java` | Adds exactly `public static InetAddress select(boolean wifiTransport, List<InetAddress> addresses)` and `public static InetAddress from(Context context)`. Uses the active Android network, requires `TRANSPORT_WIFI`, and reads only that network's `LinkProperties.getLinkAddresses()`. Selects the first non-loopback RFC1918 IPv4. Invalid transport or no eligible address throws `IllegalStateException`. Missing service/network/capabilities/link properties fail closed with specific errors. |
| `app/src/main/java/br/com/redesurftank/havalcamera/VideoGalleryActivity.java` | Replaces interface enumeration with `ActiveWifiAddress.from(this)`. Status identifies active Wi-Fi and shows the selected IPv4 URL, port, pairing code, and countdown. Selection errors remain visible. Stops the previous session/countdown before a restart attempt. |
| `app/src/main/java/br/com/redesurftank/havalcamera/LocalShareServer.java` | Removes only interface lookup/name-prefix enforcement. Retains private-address validation and `socket.bind(new InetSocketAddress(wifiAddress, 0), 1)`, with address reuse disabled. Explicitly requires IPv4 and rejects loopback. |
| `app/src/main/AndroidManifest.xml` | Adds normal `ACCESS_NETWORK_STATE` and `INTERNET` permissions, required for the requested platform lookup and local socket. Existing signature protections are preserved. |
| `app/src/test/java/br/com/redesurftank/havalcamera/ActiveWifiAddressTest.java` | Eight behavioral selection tests, listed below. |
| `app/src/test/java/br/com/redesurftank/havalcamera/LocalShareServerTest.java` | Adds constructor rejection and real-socket exact-binding/closure regressions. Existing six security/session tests remain. |
| `README.md` | Documents active-Wi-Fi selection, permissions, status, and remaining physical validation. |
| `docs/VALIDATION.md` | Records RED/GREEN evidence and hardware limits. |
| `.superpowers/sdd/2026-11-02-local-controls-and-updates/task-1-report.md` | This full report, explicitly included despite the scratch-directory ignore rule. |

No dependencies, build controls, wrapper, verification metadata, native capture code, AIDL, root/path validation, pairing behavior, expiration, request/range limits, client restrictions, or catalog access rules changed. Logs and APKs remain outside the worktree in the evidence directories below.

## TDD: RED before implementation

Before writing production code, committed the two test files. `git diff b0df973..5f4f93a -- app/src/main README.md build.gradle.kts app/build.gradle.kts settings.gradle.kts gradle build.sh` was empty.

To execute the complete existing suite while the new production class was absent, RED selection tests used a small test-only reflection bridge. Missing `ActiveWifiAddress` produced an explicit assertion failure, rather than blocking test compilation. GREEN removed that bridge and statically imported the real selector. All eight test bodies, assertions, and fixtures stayed unchanged. No production stub or test-only production hook was added.

The selection regressions catch these breaks:

1. Choosing wildcard, loopback, public IPv4, or private IPv6 ahead of an eligible private IPv4.
2. Rejecting valid addresses in any of the three RFC1918 ranges, including both ends of `172.16/12`.
3. Ignoring the first eligible address in the active network's ordered candidates.
4. Accepting private addresses when Wi-Fi transport is false, covering the non-Wi-Fi boundary independently of address order.
5. Accepting loopback-only Wi-Fi.
6. Accepting IPv6-only Wi-Fi, including ULA and link-local IPv6.
7. Accepting public, wildcard, IPv4 link-local, carrier-grade NAT/Tailscale-range, multicast, or addresses just outside private ranges.
8. Accepting an active Wi-Fi with no addresses.

The constructor rejection test covers null, wildcard, public IPv4, loopback, wildcard IPv6, loopback IPv6, and ULA IPv6. The real-socket test starts the real server on a host-private IPv4, requests the pairing form, checks that loopback cannot connect to the same port, closes the server, and checks that its selected address/port no longer accepts connections. It would fail if binding became wildcard or interface names were still required. It skips only if the test host has no private IPv4; both actual queue runs had **zero skips**, so the real-socket assertions ran on GREEN and the old prefix restriction failed on RED.

### Exact RED commands

From the task worktree:

```sh
git diff --check
git add app/src/test/java/br/com/redesurftank/havalcamera/ActiveWifiAddressTest.java app/src/test/java/br/com/redesurftank/havalcamera/LocalShareServerTest.java
git commit -m 'test: define active wifi sharing contracts'
git push origin HEAD:refs/heads/codex/continuous-trip-video-impl
/usr/local/bin/mac-mini-build submit-git /root/projects/haval-camera android-camera-verify 5f4f93ac40dced1a31530baeb528aaf9fa750504
/usr/local/bin/mac-mini-build status haval-camera-5f4f93ac40dced1a-20261102T224352Z-14036
/usr/local/bin/mac-mini-build fetch haval-camera-5f4f93ac40dced1a-20261102T224352Z-14036 /root/projects/haval-camera/.artifacts/active-wifi-red-5f4f93a
```

- Job: `haval-camera-5f4f93ac40dced1a-20261102T224352Z-14036`.
- Archive SHA-256: `a101238fc606a40bb677591b338c94fe6c2dcf214d18ce68b3c07de2a63b2453`.
- Source scan: `content_files_scanned=84`.
- Queue: `state=failed`, expected test failure; started `2026-11-02T22:43:54Z`, finished `2026-11-02T22:44:11Z`, exit 1.

Relevant output:

```text
> Task :app:testDebugUnitTest FAILED
ActiveWifiAddressTest > acceptsEachPrivateIpv4Range FAILED
ActiveWifiAddressTest > rejectsPrivateAddressesWithoutWifiTransport FAILED
ActiveWifiAddressTest > rejectsWifiWithoutAddresses FAILED
ActiveWifiAddressTest > selectsFirstEligibleAddressOnTheActiveWifi FAILED
ActiveWifiAddressTest > rejectsPublicAndNonPrivateIpv4Addresses FAILED
ActiveWifiAddressTest > rejectsLoopbackOnlyWifi FAILED
ActiveWifiAddressTest > selectsPrivateIpv4AfterUnusableAddresses FAILED
ActiveWifiAddressTest > rejectsIpv6OnlyWifiIncludingPrivateAddresses FAILED
LocalShareServerTest > bindsOnlyTheSelectedPrivateAddressRegardlessOfInterfaceName FAILED
136 tests completed, 9 failed
BUILD FAILED in 15s
21 actionable tasks: 21 executed
finished=2026-11-02T22:44:11Z rc=1
```

Inspected the actual JUnit XML on the authorized Mini, not just Gradle's console summary:

```text
suites=21 tests=136 failures=9 errors=0 skipped=0
```

All eight selector failures were caused by the missing selector (`ClassNotFoundException` through the explicit assertion). The server regression failed with `IllegalArgumentException: Address is not on active Wi-Fi`. Exactly the nine new regressions failed; all 126 baseline tests plus the new constructor rejection test passed. This was the expected feature-absence failure, with real compilation and suite execution; implementation started only after inspecting it.

## GREEN verification

### Exact GREEN commands

The manifest received an XML well-formedness inspection on the coordinator; `git diff --check` passed. Then:

```sh
git add README.md app/src/main/AndroidManifest.xml app/src/main/java/br/com/redesurftank/havalcamera/ActiveWifiAddress.java app/src/main/java/br/com/redesurftank/havalcamera/LocalShareServer.java app/src/main/java/br/com/redesurftank/havalcamera/VideoGalleryActivity.java app/src/test/java/br/com/redesurftank/havalcamera/ActiveWifiAddressTest.java
git commit -m 'fix: bind sharing to active wifi'
git push origin HEAD:refs/heads/codex/continuous-trip-video-impl
/usr/local/bin/mac-mini-build submit-git /root/projects/haval-camera android-camera-verify 79fe2b5e47a9787b9e879a9e2042c07942737390
/usr/local/bin/mac-mini-build status haval-camera-79fe2b5e47a9787b-20261102T224536Z-23926
/usr/local/bin/mac-mini-build fetch haval-camera-79fe2b5e47a9787b-20261102T224536Z-23926 /root/projects/haval-camera/.artifacts/active-wifi-green-79fe2b5
```

- Job: `haval-camera-79fe2b5e47a9787b-20261102T224536Z-23926`.
- Archive SHA-256: `27b96010eb5ab0805a35b253a22033109a2a16ada2daf377eb2402f7eb4323ad`.
- Source scan: `content_files_scanned=85`.
- Existing approved build-control digest, unchanged: `4e5fe967266c4e8608f9e619c227302c4e9bdfb010879a674ae47f60c8fed38a`.
- Queue: `state=success`, finished `2026-11-02T22:46:15Z`, exit 0.

The policy-approved `android-camera-verify` workflow ran the complete unit suite and debug assembly, followed by the existing release verification script. Required build steps execute on the Mini only:

```sh
./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug
bash build.sh
```

`build.sh` runs `./gradlew --no-daemon --dependency-verification strict :app:assembleRelease`, then `apksigner verify`, signer-certificate comparison with approved SHA-256 `5efec0f94d84d90e769635a81d51e2f25953d5d5b3cea950a115705d3b5e83e0`, `zipalign -c 4`, and `unzip -tq`. Its unchanged fail-fast checks completed successfully. Debug signing remains Gradle debug signing; release signing material remained on the Mini.

Relevant GREEN output:

```text
> Task :app:testDebugUnitTest
> Task :app:configureCMakeDebug[arm64-v8a]
> Task :app:buildCMakeDebug[arm64-v8a][camera_stream]
> Task :app:assembleDebug
BUILD SUCCESSFUL in 19s
> Task :app:configureCMakeRelWithDebInfo[arm64-v8a]
> Task :app:buildCMakeRelWithDebInfo[arm64-v8a][camera_stream]
> Task :app:assembleRelease
BUILD SUCCESSFUL in 16s
50 actionable tasks: 50 executed
No errors detected in compressed data of .../build/haval-camera.apk.
finished=2026-11-02T22:46:15Z rc=0
```

Read-only Python/XML inspection on the Mini confirmed:

```text
TEST-br.com.redesurftank.havalcamera.ActiveWifiAddressTest.xml {'tests': '8', 'failures': '0', 'errors': '0', 'skipped': '0'}
TEST-br.com.redesurftank.havalcamera.LocalShareServerTest.xml {'tests': '8', 'failures': '0', 'errors': '0', 'skipped': '0'}
suites= 21 {'tests': 136, 'failures': 0, 'errors': 0, 'skipped': 0}
```

XML inspection used the existing authenticated SSH configuration and `xml.etree.ElementTree`, reading only `TEST-*.xml` beneath each job's `source/app/build/test-results/testDebugUnitTest` directory on the authorized Mini. It summed the `tests`, `failures`, `errors`, and `skipped` attributes and inspected every failed testcase on RED. No build/test command ran through this inspection path.

### Fetched evidence

| Evidence | Path | SHA-256 |
|---|---|---|
| RED log | `/root/projects/haval-camera/.artifacts/active-wifi-red-5f4f93a/build.log` | `74e6534e8b9f91f8ec37e5f1fb90a34ea948861be28276dc2c9dc8fb03b27e5f` |
| GREEN log | `/root/projects/haval-camera/.artifacts/active-wifi-green-79fe2b5/build.log` | `28b5e9415cc4bee36ab2a4ed87e9c78ebd58781d0ebebcd0484da725de29b14e` |
| GREEN signed release APK | `/root/projects/haval-camera/.artifacts/active-wifi-green-79fe2b5/haval-camera.apk` | `e8718da41b3a5248af5e0f4544daa261b87d9000430193d7de7d510d28f37110` |

Both fetch operations verified their returned `SHA256SUMS`: RED `./build.log: OK`; GREEN `./build.log: OK` and `./haval-camera.apk: OK`. No generated artifact or raw build log was committed.

## Self-review

Reviewed the full Task 1 diff from `b0df973` to GREEN and connected callers against the brief and approved design, then reviewed the evidence/documentation changes. No unresolved blocking code finding was identified.

- `ActiveWifiAddress.from` uses only `getActiveNetwork`, that network's capabilities, and that network's link addresses; it does not scan other networks or interface names. Non-Wi-Fi default networks fail closed before reading their addresses. Public, IPv6, loopback, link-local, and CGNAT candidates cannot be selected.
- The sole production `LocalShareServer` constructor caller is the gallery and now supplies the platform-selected address. The server independently rejects non-private/invalid IPv4 bindings, and exact-address binding/reuse rules are preserved.
- Existing pairing, authorization, expiration, client binding, range bounds, completed-file restrictions, canonical path checks, and socket closure code stayed intact; their tests passed.
- Status contains the selected IPv4, transport, URL/port, code, and countdown; resetting the previous sharing state prevents its countdown from replacing a new lookup error.
- Manifest additions are the two normal permissions required for this behavior, with no signature-permission or component-export changes.
- TDD assertions were preserved when replacing the RED reflection bridge with the direct selector import. No failed assertion was removed or weakened.
- `git diff b0df973..79fe2b5 -- build.gradle.kts app/build.gradle.kts settings.gradle.kts gradle gradlew build.sh app/src/main/cpp app/src/main/aidl` was empty. No dependency or build-control change was needed.
- `git diff --check` and manifest XML parsing passed. Full mobile compilation, unit tests, assembly, and package checks ran only through the authorized queue.
- Independent review was intentionally not dispatched, per the user's instruction. Author self-review is the review limitation.

## Decisions and concerns

1. **Manifest scope:** added `ACCESS_NETWORK_STATE` and `INTERNET` beyond the brief's enumerated Java files because the required APIs/socket cannot work without them. The Android API reference documents the network-state permission: [ConnectivityManager](https://developer.android.com/reference/android/net/ConnectivityManager). No runtime permission dialog or dependency was added.
2. **Queue choice:** used existing approved `android-camera-verify` from the primary repository path, passing the exact worktree commit. This preserves queue allowlists and strict dependency verification and also verifies release packaging beyond Task 1's required debug assembly. No queue policy or security control was modified.
3. **Runtime RED:** a temporary test-only reflection bridge let the whole suite execute before the class existed; GREEN removed it without changing expectations.
4. **Restart cleanup:** reused `stopSharing()` before selecting a replacement address so a failed restart cannot retain a closed session/countdown and obscure the specific error. Actual Activity behavior still needs device validation.
5. **Hardware/integration limits:** no physical Android 9 `ConnectivityManager` execution, UI exploration/test, Starlink Mini/head-unit/phone routing, network-loss/switch behavior, or real recording download was exercised. The platform adapter is compiled, while selection policy and server socket behavior are JVM-tested. Those facts do not establish vehicle compatibility.
6. **Network lifetime scope:** selection occurs on explicit sharing start/restart. No network-change observer or automatic rebinding was requested or added. A change to the active network after start remains a physical validation concern.
7. **Test-host dependency:** the real-socket test uses an available private IPv4 on the authorized host and can skip on a host without one. There were zero skips in both actual runs.
8. **Existing warnings:** seven AndroidX annotation warnings per compilation, deprecated-API notes, and the existing `extractNativeLibs` manifest warning remain. No new warning category or verification failure appeared.
9. **Separate known defect:** the existing `Muxer is closed` physical recording failure was not changed or claimed fixed.

No known blocking Task 1 implementation concern remains; physical validation and independent review remain explicit limits. Tasks 2–5 were not implemented.

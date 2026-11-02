# Continuous trip video and remote access design

## Intent

Record the Haval camera composition continuously for each automatic trip, keep the footage on the car with automatic retention, and let the owner watch live video or retrieve recordings through Haval Hub.

The camera recorder remains a separate Android application. Haval EcoTrip supplies trip lifecycle and remote-control commands, but failures in camera capture, encoding, storage, or transfer must not interrupt telemetry, trip persistence, vehicle controls, updates, or the OEM camera interface.

## Confirmed evidence

- The investigated head unit exposes Qualcomm AIS/QCarCam input 4 as packed BGR24 at 1760 x 720 and 30 fps.
- `qcarcam_test -dumpFrame=2` delivers approximately 15 fps through the existing native pipe adapter.
- The stream is already composed: 360-degree view, rear view, vehicle drawing, and guidance lines. Separate lenses are not available through the current path.
- The standalone preview worked for approximately 30 seconds on one `msmnile_gvmq_for_arm64` head unit.
- Closing and reopening, reboot recovery, concurrent OEM AVM use, long duration, motion, hardware encoding, and thermal behavior remain unverified.
- Haval EcoTrip already uses Shizuku, receives `driving_ready`, owns automatic-trip lifecycle, and maintains an authenticated bridge connection.
- The bridge already relays authenticated binary WebSocket audio. It has no existing video or WebRTC relay.

## Goals

- Record H.264 video continuously while an automatic trip is active.
- Keep recording with the camera UI closed.
- Store playable 60-second MP4 segments grouped by trip ID.
- Recover after app, Shizuku, or head-unit restart without corrupting completed segments.
- Delete oldest completed trips automatically to preserve at least 5 GB of free internal storage.
- Show the local gallery and playback in Haval Camera.
- Show the remote gallery and playback entry points in Haval Hub.
- Provide authenticated live video with a target glass-to-glass latency of 1-2 seconds.
- Allow resumable retrieval of recordings while the car is online.
- Allow on-demand live viewing while parked when the head unit is already powered.
- Let the owner control safe recording, retention, live-view, and transfer settings inside Haval Camera.
- Preserve strict root-path validation and never create raw camera dumps.

## Non-goals

- No audio in the first version.
- No separate front, rear, left, or right lens selection.
- No cloud backup or permanent bridge-side video storage.
- No waking a powered-off head unit or vehicle.
- No recording from a software H.264 encoder.
- No fallback that writes BGR24 frames to disk.
- No public camera endpoint, unauthenticated share link, or arbitrary third-party consumer.
- No collision detection, sentry mode, or automatic incident preservation in this delivery.
- No seamless multi-camera mosaic beyond the composition supplied by input 4.

## Approaches considered

### Embed capture inside Haval EcoTrip

This minimizes IPC, but a camera or encoder failure shares process and release risk with telemetry and trips. It also couples AGPL camera code directly to the main application. Rejected.

### Separate recorder with WebRTC

WebRTC provides congestion control and sub-second media transport, but adds a large native dependency, signaling, ICE, STUN/TURN, and a second complex native stack on the Android 9 head unit. The existing bridge does not provide this infrastructure. Rejected for the first version.

### Separate recorder with authenticated H.264 WebSocket relay

The car and iPhone both open outbound TLS WebSockets to the existing bridge, so CGNAT does not require TURN. The same encoded H.264 access units feed local MP4 recording and the live relay. The bridge forwards bounded binary messages and never persists live video. Selected because it meets the requested latency with the smallest new runtime surface.

HLS was also considered. It reuses file segments but adds approximately 6-12 seconds of latency, which does not meet the requested near-real-time behavior.

## Applications and ownership

### Haval Camera Android app

Haval Camera owns:

- QCarCam process lifecycle and native pipe adapter.
- BGR24 frame ingestion.
- headless OpenGL/EGL rendering.
- hardware H.264 encoding.
- MP4 segmentation and local retention.
- local metadata index.
- local gallery and player.
- signature-protected control and metadata IPC.
- live-video producer and recording-transfer producer.

The package remains `br.com.redesurftank.havalcamera`. Production builds use the same signing certificate as Haval EcoTrip so Android signature permissions can protect all cross-app calls.

### Haval EcoTrip Android app

Haval EcoTrip remains the control-plane owner. It:

- sends idempotent trip start, stop, discard, and reconcile commands to Haval Camera;
- forwards authenticated remote live and transfer requests received from the bridge;
- supplies short-lived, scope-limited bridge session credentials to Haval Camera;
- reports camera availability and transfer progress as diagnostics;
- continues normally when Haval Camera is absent, stopped, full, or failing.

Haval EcoTrip never reads raw frames and never stores the bridge master credential in Haval Camera.

### Bridge

The bridge owns authenticated session coordination and bounded relay only. It:

- authorizes the Haval Hub user with existing authentication;
- issues one-time producer and viewer credentials scoped to one session;
- relays live H.264 messages from one car producer to one authorized viewer;
- relays metadata and requested recording chunks;
- applies rate, size, duration, and connection limits;
- stores recording metadata only, not video bytes;
- expires sessions and credentials automatically.

### Haval Hub iOS app

Haval Hub owns:

- live-session start and stop controls;
- H.264 decoding and display;
- remote trip/segment listing;
- resumable recording downloads;
- local download state and playback;
- clear offline, unavailable, and transfer-progress states.

## User settings and safe defaults

Haval Camera exposes a local settings screen. Settings persist in private app storage and remain available without Haval EcoTrip.

- Automatic trip recording: on by default. Haval Camera still stores the latest authoritative trip state while disabled, so enabling it can start the current trip without replaying stale commands.
- Record parked live sessions: off by default. When enabled, only an authenticated parked live session is recorded; this is not continuous sentry recording.
- Remote live viewing: on by default. Disabling it rejects new sessions and closes an active session.
- Remote recording downloads: on by default. Disabling it cancels active transfer reads without deleting local files.
- Quality preset: Standard by default. Economy is 1760 x 720 at 10 fps and 2 Mbps; Standard is 15 fps and 4 Mbps; High is 15 fps and 6 Mbps. Every preset keeps one-second keyframes and hardware AVC. Unsupported presets fail visibly without software fallback.
- Minimum free space: 5 GB by default, with 10 GB and 20 GB choices.
- Automatic deletion: on by default. When disabled, no completed trip is deleted automatically; recording stops visibly before violating the selected free-space reserve.
- Delete recordings: deletes completed local trips only after explicit confirmation. Active and transferring trips remain protected.

Quality changes apply to the next recording or live-only encoder start. Storage settings apply before the next segment opens. Feature toggles apply immediately through the service reducer. Fixed source resolution, QCarCam input, root paths, protocol limits, authentication, and signature permissions are not user-configurable.

## Android component design

### Capture user service

The current Shizuku `CameraService` becomes a capture-only private UserService. It keeps:

- canonical library and XML path validation for the production package;
- the private `qcarcam_test` copy with mode `0700`;
- the exact `LD_PRELOAD`, `LD_LIBRARY_PATH`, and `3>&1 1>&2` process contract;
- the pipe-only constructor check and parent-death signal;
- logs on stderr and frames on the pipe;
- deterministic child termination.

Changing these checks is a security change. No generic caller-supplied executable or path is accepted.

### Recording foreground service

A non-exported recording foreground service owns capture, encoder, muxer, and recovery. A separate exported command receiver or service accepts only callers holding the custom `signature` permission.

The foreground notification shows one of: recording trip, live viewing, transferring recording, or error. Remote live viewing while parked always produces a visible notification.

The capture modes are:

- `idle`: no QCarCam child and no encoder;
- `recording`: capture, encode, and write MP4 segments;
- `live-only`: capture and encode only while at least one authorized viewer exists;
- `recording-live`: one encoder feeds both MP4 and live relay;

Only one capture child and one encoder exist at a time.

Recording transfer is an independent file-I/O state. It may run while recording because it reads only completed trips. Recording always has priority. Starting live viewing pauses an active transfer; the transfer resumes from its acknowledged offset after live viewing ends. This avoids simultaneous live and download traffic on the car's mobile uplink.

### Frame and encoder path

The source remains fixed at 1760 x 720 BGR24 and approximately 15 fps. A headless EGL context uploads each complete frame once, performs the BGR-to-RGB channel correction in the shader, and renders into a `MediaCodec` input surface.

Standard encoder settings:

- MIME: `video/avc`;
- resolution: 1760 x 720;
- frame rate: 15 fps;
- bitrate: 4 Mbps;
- keyframe interval: 1 second;
- surface input;
- no B-frame requirement;
- low-latency mode when the Qualcomm codec exposes it.

The implementation selects a hardware codec that supports AVC surface input. If none can be configured, recording and live viewing fail visibly. It must not fall back to CPU BGR-to-YUV conversion or raw storage.

Each encoded access unit is consumed once, then routed to active sinks:

- `MediaMuxer` for local MP4;
- the bounded live WebSocket sender when a viewer exists.

Codec configuration and the latest keyframe are retained in memory. A new viewer triggers an encoder sync-frame request so playback starts without waiting for the normal GOP boundary.

### Trip command contract

All cross-app commands are explicit and protected by a custom signature permission. Commands include a protocol version, request ID, and monotonic state revision generated by Haval EcoTrip.

- `START_TRIP(tripId, startMs)`: start or reconcile recording for the trip.
- `STOP_TRIP(tripId, endMs)`: finish the current segment and mark the trip complete.
- `DISCARD_TRIP(tripId)`: stop and delete footage for a trip rejected by trip rules.
- `RECONCILE(activeTripId?)`: make recorder state match Haval EcoTrip after either app restarts.
- `START_LIVE(sessionUrl, producerToken, expiresAt)`: attach live relay, starting live-only capture if needed.
- `STOP_LIVE(sessionId)`: detach relay and stop live-only capture when no viewer remains.
- `TRANSFER(requestId, tripId, segmentIds, viewerSession)`: send completed files from requested offsets.
- `CANCEL_TRANSFER(requestId)`: stop file reads and network transfer.

Rules:

- Repeating the same start or stop is a no-op.
- Haval Camera persists the last applied revision and request IDs needed for deduplication across restart.
- Commands older than the persisted revision are ignored.
- `RECONCILE` carries the complete authoritative state and supersedes delayed incremental commands at the same or lower revision.
- Starting a different trip finalizes the previous active segment before switching.
- A stop or discard for a stale trip ID cannot affect the current trip.
- A trip shorter than Haval EcoTrip acceptance rules is deleted after `DISCARD_TRIP`.
- If Haval Camera is missing or unavailable, Haval EcoTrip logs the condition and continues.
- Binder, service, or network work never runs while holding the TripManager state lock.

Haval EcoTrip sends `RECONCILE` on telemetry-service startup and after every automatic-trip state transition. This covers OTA restart and mid-trip process recovery.

## Local storage and recovery

Private app storage uses this logical layout:

```text
trip-video/
  index.json
  <tripId>/
    trip.json
    000001.mp4
    000002.mp4
    current.partial
```

Completed segments are 60 seconds except the final segment. `current.partial` is renamed atomically only after `MediaMuxer.stop()` succeeds. Boot recovery deletes unusable partial files, preserves completed MP4 files, and starts a new segment when the trip remains active.

Metadata contains identifiers, times, duration, byte size, codec properties, completion state, and SHA-256. It contains no location, VIN, bridge token, or user image thumbnail by default.

Only completed trips enter the remote catalog or become downloadable. Segments from an active trip remain local and unavailable through the recording-transfer API. If Haval EcoTrip later discards a provisional trip, no remote catalog entry or downloadable file has been exposed.

Retention runs before recording starts, after every segment closes, and when storage warnings occur:

1. Calculate free bytes on the recording filesystem.
2. Exclude the active trip and active transfer.
3. When automatic deletion is enabled, delete the oldest completed trip directories atomically until the selected reserve is free.
4. When deletion is disabled or the selected reserve cannot be recovered, stop recording before the filesystem fills and report the error.

Retention deletes whole trips, not isolated middle segments. It never deletes an active trip or file being transferred. Pinning or permanent retention is not part of the first version.

## Local gallery and playback

Haval Camera provides the canonical gallery and player. It groups segments by trip, displays recording completeness and size, and plays segments in order.

A signature-protected ContentProvider exposes read-only metadata and content URIs to Haval EcoTrip on the car. No filesystem path is exported. Delete operations remain inside Haval Camera.

The Haval Hub iOS gallery uses bridge metadata for remote files and its own local index for downloaded files. Downloaded trips play from local storage. The first version does not promise seamless playback before all requested segments have downloaded.

Haval Camera sends a complete completed-trip catalog snapshot to Haval EcoTrip after trip completion, retention deletion, and reconnect. Haval EcoTrip forwards that small JSON snapshot through the existing authenticated control plane. The bridge replaces its cached catalog atomically and records `catalogRevision` and `updatedAt`. Haval Hub labels stale metadata when the car is offline. Active trips never appear in this catalog.

## Near-real-time live relay

Live viewing is on demand only. Continuous trip recording does not consume mobile data until an authorized viewer starts a session.

Flow:

1. Haval Hub requests a live session from the authenticated bridge API.
2. Bridge creates one producer token and one viewer token with short expiry.
3. Bridge sends a control command through the existing authenticated car channel.
4. Haval EcoTrip validates it and sends `START_LIVE` to Haval Camera through signature IPC.
5. Haval Camera opens an outbound `wss://` producer connection.
6. Haval Hub opens the matching viewer connection.
7. Bridge relays bounded binary messages without persistence or parsing frame contents.
8. Closing the viewer stops relay immediately; parked live-only capture stops after a 15-second disconnect grace period.

The bridge permits one producer and one viewer per vehicle in the first version. A parked live session expires after 30 minutes unless the authenticated viewer renews it. The system never wakes a powered-off head unit.

Each binary video message carries protocol version, session ID, sequence, presentation timestamp, flags, and one AVC access unit. Flags identify codec configuration and keyframes. TLS supplies transport confidentiality.

Backpressure policy favors freshness:

- maintain a small bounded queue;
- drop queued non-keyframes when socket buffering crosses the limit;
- never block the encoder or local recorder on network writes;
- request a new keyframe after a drop burst or viewer join;
- close a stalled producer or viewer rather than accumulating latency.

The iOS client decodes with VideoToolbox or `AVSampleBufferDisplayLayer`. It resets its decoder when codec configuration changes and shows connection quality, not a stale frame, after timeout.

At Standard quality, live viewing uses approximately 1.8 GB per hour before protocol overhead. The UI displays estimated mobile-data use for the selected preset.

## Recording retrieval

Video retrieval uses a separate authenticated WebSocket relay and does not compete with the live queue.

1. Haval Hub queries cached recording metadata from the bridge.
2. User selects a trip for download.
3. Bridge creates a scoped transfer ID and short-lived credentials, then asks the car for the requested segments.
4. Haval Camera reads only completed MP4 files and sends fixed-size chunks with segment ID, offset, length, and checksum.
5. Bridge forwards chunks without storing them.
6. Haval Hub writes to a partial local file and acknowledges committed offsets.
7. Reconnection requests new one-time credentials bound to the same transfer ID and resumes from the last acknowledged offset. Expired credentials are never reused.
8. Final SHA-256 must match the car metadata before the local file becomes playable.

Only one recording transfer runs initially. Transfers pause cleanly when the car loses connectivity or a live session starts. Missing local footage returns an explicit `expired` result because retention may have removed an old trip.

## Security and privacy

- Haval Camera and Haval EcoTrip must share the approved production signing certificate.
- Cross-app control and content access require Android signature permissions.
- Haval Camera stores no long-lived bridge master token.
- Live and transfer credentials are random, scoped, one-time, and short-lived.
- Bridge APIs retain existing owner authentication and add rate limits.
- Video WebSockets require TLS.
- No public port opens on the car.
- No live frame, recording chunk, or thumbnail is written to bridge logs.
- Bridge diagnostics contain counts, byte totals, latency, and error codes only.
- Remote starts and downloads are recorded in a minimal local audit log.
- A visible foreground notification identifies live transmission and recording.
- Raw frames, OEM binaries, tokens, IP addresses, and user footage remain excluded from source control and releases.

The camera project remains AGPL-3.0. Keeping it as a separate APK and communicating over a documented IPC boundary reduces source coupling, but release obligations still require a license review. The Haval Camera corresponding source and notices must remain available with distributed builds.

## Failure behavior

- QCarCam failure: blank preview, close current partial segment, preserve completed segments, retry with bounded backoff.
- Encoder failure: stop camera capture and report unavailable; never switch to raw or software encoding.
- Muxer or disk failure: stop local recording before storage exhaustion; live may continue only if encoder remains healthy.
- Network loss: local recording continues; live shows offline; transfer remains resumable.
- Bridge loss: no effect on local recording or retention.
- Haval EcoTrip loss: active recording continues from persisted state; reconciliation occurs when it returns.
- Haval Camera loss: Haval EcoTrip telemetry and trips continue unaffected.
- Viewer loss: drop live network sink; keep trip recording active.
- Head-unit restart: completed segments survive; partial segment is discarded; active trip resumes into a new segment after reconciliation.

## Rollout and validation

No release is described as camera-safe until hardware checks pass on the target car. The first release supports only the single investigated head-unit and firmware combination recorded during validation. Other vehicles or firmware versions require a new capability and safety validation before enablement.

### Gate 1: parked capture and encoding

- Verify the exact head unit and firmware identity.
- Verify a hardware AVC surface encoder at 1760 x 720, 15 fps, and 4 Mbps.
- Record for 10 minutes while parked in P.
- Verify six or more segment boundaries, timestamps, colors, playback, and checksums.
- Confirm `/data/vendor/camera` remains empty.
- Confirm closing and reopening leaves no orphan child.
- Measure dropped frames, CPU, memory, temperature, and storage rate.

### Gate 2: OEM AVM coexistence

- While parked, trigger reverse and surround-camera views repeatedly.
- Confirm the OEM safety view always appears and remains responsive.
- Stop immediately if the recorder suppresses, delays, freezes, or corrupts the OEM view.
- Verify capture reconnects after OEM AVM activity.

### Gate 3: lifecycle and retention

- Restart each app independently during recording.
- Restart Shizuku and the head unit.
- Verify trip start, normal stop, discard, and mid-trip reconciliation.
- Simulate low storage and prove only oldest completed trips are deleted.
- Verify active and transferring trips are preserved.

### Gate 4: live and transfer

- Test LAN, good 4G, degraded 4G, reconnection, and viewer cancellation.
- Measure glass-to-glass latency; target 1-2 seconds, fail if buffering grows without bound.
- Verify local recording remains complete while live frames are dropped.
- Resume a multi-segment download after disconnect and verify final SHA-256.
- Confirm the bridge retains no video bytes after sessions end.

### Gate 5: controlled route

- Only after parked AVM coexistence passes, run a short controlled route.
- Verify recording throughout the trip, automatic stop, gallery visibility, live view, and remote retrieval.
- Repeat for at least 60 minutes before enabling automatic recording by default.

## Acceptance criteria

- Every accepted automatic trip produces ordered, playable MP4 segments or a visible failure reason.
- Rejected trips leave no retained footage.
- Completed segments survive app and network failures.
- Storage remains at or above the selected reserve unless unrelated data already prevents it; recording then stops safely.
- Live viewing starts only for an authenticated request and reaches the viewer without unbounded queue growth.
- Parked live viewing works only while the head unit is already powered.
- Downloads resume by offset and pass SHA-256 verification.
- Both Haval Camera and Haval Hub can reach gallery and playback flows.
- Camera faults never stop Haval EcoTrip telemetry or trip persistence.
- OEM AVM remains fully functional during all approved tests.
- No raw frame dumps, long-lived camera credentials, or video payload logs are created.

## Builder decisions left open

The builder may choose internal class names, JSON serialization library already present in each project, exact notification wording, and visual layout. These choices must preserve the contracts, safety gates, and limits above.


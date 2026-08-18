# MoboSafe Pocket Dashcam — Android

Submission for the **Pocket Dashcam Challenge**, Movozen Private Limited, 18 August 2026.

Roll number: **BTECH2502322**
Streams published: `BTECH2502322_front` · `BTECH2502322_back`

---

## What this is

A native Android application, written from scratch for this challenge, that turns the phone
into a connected dashcam: it captures the cameras and the microphone, encodes to **H.264 +
AAC** on-device, and publishes live over **RTMP** to `15.207.177.194:1936/hackathon/`.

No pre-built streaming app is used. The only third-party component is
[RootEncoder](https://github.com/pedroSG94/RootEncoder) (Apache-2.0), used as an encoding/RTMP
*library* — the capture pipeline, stream lifecycle, dual-feed logic, reconnect policy,
foreground-service model and UI are all written here.

---

## Architecture

```
Camera2Source ─┐
               ├─► GenericStream ─► H.264 encoder ─┐
MicrophoneSource┘                  AAC encoder ────┴─► RTMP ─► {ROLLNO}_front
                                                                    (feed A)

Camera2Source ─┐
               ├─► GenericStream (2nd instance) ──────► RTMP ─► {ROLLNO}_back
MicrophoneSource┘                                                   (feed B)
```

| File | Responsibility |
|---|---|
| `Config.kt` | Endpoints, encoder profile, stream-key variant generation |
| `StreamService.kt` | Foreground service owning both streams: prepare, connect, reconnect, dual-camera negotiation, wake lock, notification |
| `MainActivity.kt` | Runtime permissions, preview surface lifecycle, controls, event log |
| `res/` | Dark operator-console UI (status pill, REC badge, uptime/bitrate/feed counter) |

### Key design decisions (for the interview)

**Streaming lives in a foreground service, not the Activity.** Tier 3 requires surviving screen
lock and backgrounding. An Activity-owned encoder dies with the surface. The service holds the
`GenericStream` instances plus a `PARTIAL_WAKE_LOCK`, and is declared
`foregroundServiceType="camera|microphone"` as Android 14 requires for background capture.

**Surface loss stops the preview only.** `surfaceDestroyed` calls `stopPreview()` and
deliberately not `stopStream()` — this is what makes screen-lock survival work rather than
silently dropping the feed.

**Reconnect is implemented locally, not delegated to the library.** A 3-second retry loop keyed
per feed, driven by `onConnectionFailed` / `onDisconnect`, with an attempt counter surfaced in
the UI. Local control means the retry policy is explicit and observable in the log.

**The second camera is attempted, never assumed.** Many phones cannot open both Camera2 sensors
concurrently. `startBack()` builds an independent stream and, on failure, releases it and leaves
feed A untouched, falling back to fast camera switching — which the brief accepts for partial
credit. The failure path is isolated so a Tier 2 attempt can never cost a working Tier 1 run.

**Stream-key variants.** The roll number is normalised into several candidate keys because `/`
in a roll number is a path separator in RTMP and would be parsed as an app name by the server.

---

## Build

```bash
git clone <this-repo>
```

Open in Android Studio (JDK 17, compileSdk 34, minSdk 24) and Run. JitPack is already declared
in `settings.gradle.kts` for the RootEncoder dependency.

## Verify

```
http://15.207.177.194:8081/web/player.html      # enter BTECH2502322, press Unmute
http://15.207.177.194:8081/hackathon/BTECH2502322_front.flv   # or open in VLC
```

## Encoder profile

1280×720 · 2 Mbps H.264 · AAC 44.1 kHz stereo 128 kbps · landscape · keyframe every 2 s.

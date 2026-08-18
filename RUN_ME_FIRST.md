# MoboSafe Pocket Dashcam — 10 minute runbook

Native Android + Kotlin + RootEncoder 2.8.0 → RTMP (H.264 / AAC) → `15.207.177.194:1936`.

---

## ⚠️ READ THIS BEFORE YOU BUILD — your roll number has slashes

Your roll number is `BTECH/25002/23`. In an RTMP URL, `/` is a **path separator**, so

```
rtmp://15.207.177.194:1936/hackathon/BTECH/25002/23_front
```

is not read by the server as `app=hackathon, stream=BTECH/25002/23_front`. Most RTMP servers
(nginx-rtmp, SRS) will read `app = hackathon/BTECH/25002` — an app that does not exist — and
**refuse the connection**. This is the single most likely reason you get "connection failed"
while everyone else is live.

**Two actions, do both, right now:**

1. **Message the organiser immediately** (before you even finish building):
   > "My roll number is BTECH/25002/23 and contains slashes, which break the RTMP path.
   > Should I publish as `BTECH_25002_23_front` or does your server accept the literal slashes?"

2. The app has a **KEY** button that cycles three candidates. Try each and watch the log:
   - `BTECH_25002_23`  ← underscores (default, most likely to work)
   - `BTECH/25002/23`  ← literal
   - `BTECH2500223`    ← stripped

   Whichever one prints `FRONT CONNECTED ✔` is your key. Use that one in the viewer too.

---

## 1 · Open and build (3 min)

**Fastest path — open the folder directly:**

1. Android Studio → **File ▸ Open** → select the unzipped `MoboSafeDashcam` folder.
2. If it asks about the Gradle wrapper, let it **download/regenerate** it. Sync.
3. Phone: Developer Options → **USB debugging** on → plug in → accept the RSA prompt.
4. **Run ▸ Run 'app'** (Shift+F10).

**Plan B if the wrapper misbehaves (also 3 min, zero risk):**

1. New Project → **Empty Views Activity** → Kotlin → Package `com.movozen.dashcam` → Min SDK 24.
2. Overwrite these from the zip:
   - `app/src/main/java/com/movozen/dashcam/*.kt` (3 files)
   - `app/src/main/res/` (whole folder)
   - `app/src/main/AndroidManifest.xml`
   - `app/build.gradle.kts`
3. Add JitPack to `settings.gradle.kts` inside `dependencyResolutionManagement { repositories { … } }`:
   ```kotlin
   maven { url = uri("https://jitpack.io") }
   ```
4. Sync and Run.

---

## 2 · First live test (2 min) — this is your 45 points

On the **phone**:
1. Allow Camera + Microphone (and Notifications).
2. Confirm the KEY button reads `KEY: BTECH_25002_23`.
3. Tap **START STREAM**. Watch the top-right pill go `CONNECTING → LIVE`.

On the **laptop**, open the viewer and type the **same key** you used:

```
http://15.207.177.194:8081/web/player.html
```

Fallback if the web player is fussy — VLC ▸ Media ▸ Open Network Stream:

```
http://15.207.177.194:8081/hackathon/BTECH_25002_23_front.flv
```

You must see your camera **and hear your mic — press Unmute, the viewer starts muted.**

### The moment you see and hear yourself → STOP AND ANNOUNCE.

That is Tier 1 (30) + audio (15) = **45 points banked**, and the brief says submission order
ranks you. Announce first, improve after. Do not sit on a working build.

---

## 3 · Tier 2 — both cameras (25 pts)

With the stream running, tap **DUAL: OFF** → it becomes **DUAL: ON** and the app opens a second
independent RTMP publish to `…_back`. The log tells you which happened:

- `BACK feed live — both cameras publishing` → full Tier 2. Both feeds show in the viewer.
- `Concurrent cameras unsupported on this device` → the phone physically can't run both
  Camera2 sensors. **Do not fight this.** Use **SWITCH CAM** instead — the brief explicitly
  gives partial credit for fast switching. Demo it once during your run.

The front feed is never torn down when the back attempt fails, so this is safe to try live.

---

## 4 · Tier 3 — stability (15 pts)

Already built in, nothing to configure:
- Streaming runs inside a **foreground service** (`camera|microphone`) + partial wake lock, so
  it survives **screen lock and backgrounding**.
- Losing the surface only stops the *preview* — the stream keeps publishing.
- **Auto-reconnect** every 3s on disconnect or failure, with attempt counter in the log.

Demo for the judges: start → lock the screen for ~30s → unlock (viewer never dropped) →
toggle aeroplane mode 5s → watch `RECONNECTING` → `LIVE`. Then let it run **10+ minutes**.

---

## 5 · Bonus +20 — ignore for now

The vehicle telematics protocol swap is a time trap. Only ask the organisers for the spec pack
**after** your scored run is announced and verified.

---

## Troubleshooting (fix in place, do not redesign)

| Symptom | Fix |
|---|---|
| Gradle can't resolve `RootEncoder:library:2.8.0` | In `app/build.gradle.kts` change `val rootEncoder` to `"2.7.3"`, then `"2.6.7"`. |
| `Unresolved reference: GenericStream` | Change the import to `com.pedro.library.rtmp.RtmpStream` and swap the two constructor calls in `StreamService.kt` — identical signature. |
| `prepareVideo` overload mismatch | It is called with 3 args on purpose (`w, h, bitrate`). If you want exactly 25 fps, try `prepareVideo(1280, 720, 2000*1000, 25, 2, 0)`. |
| `ConnectChecker` "must override" error | Your version's callback list differs slightly — accept the IDE's *Implement members* quick-fix, keep the bodies. |
| Preview black, no error | Grant permissions in Settings ▸ Apps ▸ MoboSafe Dashcam, force-stop, reopen. |
| Connects then instantly drops | Wrong stream key — cycle the **KEY** button. Also confirm the phone is on mobile data or a network that allows outbound **1936**. College Wi-Fi often blocks it. |
| Viewer shows video, no sound | Press **Unmute** in the browser. |

**If a Gradle / Kotlin / RootEncoder error appears, paste it straight back to me. Don't debug it yourself.**

---

## Announcement message to send the organisers

> Roll number: BTECH/25002/23 — publishing as `<the key that worked>`
> Streams: `<key>_front` (and `<key>_back` if dual worked)
> Tier 1 live with audio ✔ · Tier 2 `<both cameras / fast switching>` ✔ · Tier 3 auto-reconnect + screen-lock survival ✔
> Android native (Kotlin), RootEncoder 2.8.0, Camera2 + Microphone, H.264 720p25 @2 Mbps, AAC 128 kbps.
> My run is ready now — please score from this timestamp.

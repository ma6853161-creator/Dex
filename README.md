# Universal DeX Receiver — Phase 1

Turns a rooted (or even non-rooted) Android tablet into a "Screen In" receiver:
it listens on the network, accepts a video stream from a phone, and decodes it
full-screen with the lowest latency the platform allows.

## Legal / clean-room notice

The `com.samsung.android.smartmirroring` APK supplied by the user was inspected
**at the manifest level only** (`aapt dump badging` / `xmltree`) to understand
its permission model and component naming — no `.smali`/`.dex`/native code was
decompiled or reused. Findings:

- It requests `signature|system`-level permissions (`WRITE_SECURE_SETTINGS`,
  `INTERACT_ACROSS_USERS_FULL`, `INTERNAL_SYSTEM_WINDOW`, `NETWORK_SETTINGS`,
  `MANAGE_USERS`). A third-party app — even with root shell — cannot obtain
  these without the Samsung platform signing key. **This is why this project
  does not attempt to replicate DeX's exact wire protocol**, and instead
  implements an independent, documented protocol (see below) plus standard
  `Wi-Fi Display` framework APIs for Phase 4.
- It bundles `com.hpplay.component.screencapture` — a third-party mirroring
  SDK (HPPlay) — meaning even Samsung doesn't implement 100% of this in-house.
- Component names like `ScreenSharingTile` / `SecondScreenPlayer` confirmed
  the receive-mode UX pattern (a quick-settings toggle that arms a foreground
  service) — we reimplemented that *pattern*, not the code, as
  `ScreenInTileService` / `ReceiverService`.

## What's implemented in this phase

| Component | File | Role |
|---|---|---|
| Wire protocol | `core/HandshakeProtocol.kt` | JSON handshake + length-prefixed H.264 framing (our own design) |
| Transport | `core/TransportServer.kt` | TCP server, coroutine-based accept/read loop |
| Decoder | `receiver/VideoDecoder.kt` | MediaCodec, async callback, zero-copy decode-to-Surface, `KEY_LOW_LATENCY` |
| Service | `receiver/ReceiverService.kt` | Foreground+bound service wiring transport → decoder, exposes `StateFlow<ReceiverState>` |
| UI | `ui/MainActivity.kt` | Fullscreen `SurfaceView` + status line |
| "Screen In" toggle | `ui/ScreenInTileService.kt` | Quick Settings tile shortcut |

**Not yet implemented** (by design, later phases): the phone-side sender app,
input injection back to the phone, clipboard/file transfer, adaptive bitrate,
Wi-Fi Display fallback, WebRTC/RTSP alternates, root-based optimizations.

## Why TCP + Annex-B framing for Phase 1

Simplest possible transport that still lets us measure real glass-to-glass
latency before adding complexity. `tcpNoDelay = true` disables Nagle's
algorithm since we care about per-frame latency, not throughput efficiency.
This will likely be replaced or supplemented by a UDP-based low-latency
transport (like RTP, or a custom ARQ scheme) once we have baseline numbers —
TCP head-of-line blocking on a dropped/reordered packet is the single biggest
latency risk in this design.

## Build

Requires Android Studio (Koala+) or `gradle` with an Android SDK installed
locally — this container has no Android SDK, so the project has **not** been
compiled here. Open the folder in Android Studio and let it sync; `minSdk 26`,
`compileSdk/targetSdk 34`.

### Building with no computer (GitHub Actions)

`.github/workflows/build.yml` builds the APK automatically on every push to
`main`, or on demand from the **Actions** tab -> *Build APK* -> *Run workflow*
(works from the GitHub mobile app or mobile browser, no desktop needed).

Two ways to get the built APK on your phone afterward:
1. **Releases page** (easiest on mobile) — the workflow publishes a rolling
   `latest` release with the `.apk` attached directly; open it in the GitHub
   app/browser and tap the file to download.
2. **Actions artifact** — under the workflow run, `UniversalDexReceiver-debug-apk`
   is attached as a zip (requires being logged in; needs unzipping after
   download).

No `gradlew`/`gradle-wrapper.jar` is committed to this repo on purpose — a
wrapper jar is a binary file, awkward to hand-create without a local Gradle
install. The workflow installs Gradle itself directly via
`gradle/actions/setup-gradle`, so plain `gradle assembleDebug` works with no
wrapper.

### Uploading this project to GitHub from a phone only

1. Create a new empty repo on github.com (mobile browser or the GitHub app).
2. Extract this zip locally on the phone (any file manager with "Extract"),
   then in the repo's web UI: **Add file -> Upload files**, and select the
   extracted folder contents (most mobile browsers preserve subfolder paths
   when you pick a folder from "Upload files"; if yours doesn't, upload the
   `.github`, `app`, and root files as separate batches — GitHub keeps the
   paths as long as the picker reports them).
3. Commit directly to `main`. The workflow above kicks off automatically.

If a Termux + `git` setup is available, that's more reliable than the mobile
upload UI for preserving folder structure:
```
pkg install git
git clone https://github.com/<you>/<repo>.git
cd <repo>
# copy the extracted project files in here
git add .
git commit -m "Phase 1: receiver skeleton"
git push
```

## Roadmap (unchanged from the original proposal)

- **Phase 2** — Phone-side sender: `MediaProjection` capture → H.264 encode → same protocol
- **Phase 3** — Input injection: second TCP channel, touch/mouse/keyboard events, root-based `InputManager` injection on the phone side when the sender itself is rooted
- **Phase 4** — Native Wi-Fi Display (Miracast) support for actual Samsung DeX interoperability
- **Phase 5** — WebRTC/RTSP alternates, clipboard sync, file transfer, adaptive bitrate

## Suggested next step

Test Phase 1 end-to-end needs a *sender*. Fastest way to validate this
receiver right now, before building the phone-side app: point `ffmpeg` or a
throwaway `adb shell screenrecord`-based script at the receiver's handshake +
framing format to confirm decode works, then move to Phase 2 for the real
phone-side app.

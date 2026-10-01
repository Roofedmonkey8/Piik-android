# Piik for Android (unofficial port)

An Android app for [Piik](https://github.com/TNTcraftHIM/Piik), the open-source screen-sharing app. With it you can:

- **Watch** any Piik invite link in a full-screen player, with picture-in-picture when you leave the app.
- **Host** from the phone: run a Piik room on it and share the phone's screen and sound with friends.

It is a port, not a rewrite. The phone runs **Piik's own engine** (its Go App, cross-compiled for Android) and shows **Piik's own web interface**. The new part is Android screen capture, which plugs into the same capture contract Piik's Windows, macOS and Linux helpers use. So rooms, invites, passwords, Public invite links, quality settings and viewers all behave exactly like desktop Piik.

Not affiliated with the Piik project. MIT licensed, like Piik.

## Getting the APK

The APK is built by GitHub Actions (it needs Google's Android SDK, Go and Node):

1. Push this folder to a GitHub repository.
2. Open **Actions → Android APK → Run workflow** (it also runs on every push).
3. Download **piik-android-apk** from the run, copy it to your phone, and open it to install (allow "install unknown apps" for your file manager or browser).

Every build on `main` is also published under **Releases** with the APK attached, so you can download it straight from your phone.

**Updates:** CI signs with a throwaway debug key unless you add a keystore, and Android refuses to update an app signed with a different key. For installable updates, add the repository secrets `PIIK_KEYSTORE_BASE64` (base64 of a `.jks`), `PIIK_KEYSTORE_PASSWORD`, `PIIK_KEY_ALIAS` and `PIIK_KEY_PASSWORD`.

### Building locally

Needs Go ≥ 1.26, Node ≥ 22, git, and Android Studio (or the Android SDK with platform 35).

```sh
scripts/build-native.sh        # Piik engine + capture shim + cloudflared → app/src/main/jniLibs
./gradlew assembleRelease      # → app/build/outputs/apk/release/app-release.apk
```

`ABIS="arm64-v8a x86_64" scripts/build-native.sh` adds an x86_64 build for the Android emulator. `SKIP_CLOUDFLARED=1` leaves out Public invite support (Local and Site modes still work).

## Using it

**Watch:** paste an invite link, or share it to Piik from Discord, WhatsApp or similar. Links to `*.trycloudflare.com` (Piik Public invites) can open straight in the app. Leaving the app while a stream plays switches to picture-in-picture.

**Host:**

1. Tap **Start hosting**. Allow notifications and audio recording; audio lets shares include the phone's sound and your microphone.
2. Piik's launcher appears. Pick a mode:
   - **Local room:** same Wi-Fi.
   - **Public invite:** anyone with the link, no port forwarding, works on mobile data.
   - **Connect to Site:** a Piik server you run.
3. **Start sharing → System screen or window picker.** Android's own dialog asks what to share. On Android 14+ you can share a single app instead of the whole screen.
4. Switch to the app or game you want to share. A notification keeps hosting alive; **Stop** is in the notification and on the home screen.

Quality changes, viewer management and invite links all happen in Piik's normal host page.

## How it works

```
 WebView (Piik's web UI) ──localhost──▶ libpiik.so  (Piik's Go App engine, child process)
                                           │  spawns its "native capture process":
                                           ▼
                                      libpiikcapture.so (shim) ──abstract Unix socket──▶ CaptureServer (Kotlin)
                                                                                           │
   MediaProjection ─▶ VirtualDisplay ─▶ GL (scale + letterbox) ─▶ MediaCodec H.264 per layer ─┤ SMED frames
   AudioPlaybackCapture / microphone ─▶ 48 kHz PCM ─────────────────────────────────────────┘
```

- **Capture contract.** `CaptureServer` implements Piik's protocol 7, the same as `native/capture/linux/main.c`:
  - `--probe`, `--list`, `--list-microphones`, `--capture-video`, `--encoded-video`, `--capture-audio`, `--capture-microphone`
  - 32-byte "SMED" frames
  - starting and active status
  - Begin frames
  - per-layer simulcast with `A`/`K`/`B`/`Q` control
- **Encoding.** One GL thread renders each frame into one hardware encoder per simulcast layer. Hardware encoders' SPS is normalized to the constrained-baseline `42c0xx` profile-level-id Piik requires.
- **One display, kept alive.** Android 14+ allows one VirtualDisplay per screen-capture consent, so the screen source outlives Piik's capture processes, which restart on every quality change. You aren't asked again on each change.
- **Patch to Piik** (`native/piik-patches/`, about 40 lines):
  - Accepts `platform: "android"` from the capture helper.
  - Hands page-open requests to the app instead of `xdg-open`.
  - Takes LAN addresses and DNS servers from the app. On Android, pure-Go binaries can't read either: there's no `/etc/resolv.conf`, and Android 11+ blocks netlink.
- **cloudflared** is built from source with the same DNS fix.
- **WebView and local addresses.** WebView hides local addresses behind mDNS names. Resolving them needs multicast, which mobile data never carries. Piik's host page reaches the engine over WebRTC, so in host mode the app briefly opens the microphone (stopped immediately), which makes WebView use real addresses. A Wi-Fi multicast lock covers the case where the microphone permission is denied.

## What has been verified, and what hasn't

Verified off-device:

- The patched Piik engine and cloudflared cross-compile for Android, and Piik's own tests pass with the patch.
- All the Kotlin compiles against the Android 15 SDK.
- Piik's capture client and its strict validators accept the app's protocol output through the real shim:
  - probe, sources, starting and active status, and SPS normalization
  - three simulcast layers, including mid-stream layer activation opening on a key frame
  - live bitrate and key-frame control
  - system audio
- Piik's launcher → Local room → host page → native share flow works end to end in Chromium (WebView's engine), using a stand-in for the Android capture service.

**Not yet run on a real phone or emulator.** MediaProjection, MediaCodec, the GL path, the foreground service and the WebView itself have only been compile-checked. Expect a first round of device fixes. `adb logcat -s PiikService PiikCapture PiikVideo PiikEncoder PiikGl PiikProjection PiikWeb` shows what's happening.

## Limitations

- Apps that block screenshots (banking, Netflix and other DRM video) appear black. Apps that opt out of audio capture are silent. Calls and notification sounds are never captured.
- Sharing pauses when the screen turns off.
- The microphone indicator flashes briefly when the host page loads (see above).
- Hosting several viewers on mobile data uses a lot of upload. Piik's quality settings apply.
- The APK is about 75 MB (arm64), because it carries the full Piik engine and cloudflared.

## Licences

This port is MIT. Piik is MIT (© its authors), and its third-party notices are built into the web interface at `/third-party-licenses.txt`. cloudflared is Apache-2.0 (© Cloudflare).

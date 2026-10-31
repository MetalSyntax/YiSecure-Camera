<h1 align="center">YiSecure · Unofficial app for Yi Home cameras</h1>

<p align="center">
  <img src="https://img.shields.io/badge/status-v0.1-c62828" alt="v0.1">
  <img src="https://img.shields.io/badge/Android-7.0%2B-3ddc84?logo=android&logoColor=white" alt="Android 7.0+">
  <img src="https://img.shields.io/badge/Kotlin-Compose-7f52ff?logo=kotlin&logoColor=white" alt="Kotlin + Compose">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue" alt="MIT License"></a>
</p>

**YiSecure** is a clean, ad-free Android app for **Yi Home** security cameras, a sibling of [Mandix](https://github.com/MetalSyntax/Mandix-Multicontrol) and [Pulsix](https://github.com/MetalSyntax/Pulsix-Multiwatch). It signs in with your Yi account, lists your cameras and plays them live, without the upsells of the stock app.

The cameras speak Yi's own P2P protocol (TUTK / TNP), so the app talks to them through Yi's SDK, which **each user extracts from their own Yi Home install** (see [How to compile](#build-instructions-for-developers)). No Yi code or keys live in this repository.

> Not affiliated with, endorsed by or connected to Yi Technology or Kami Vision. "Yi" and "Yi Home" are trademarks of their owners.

---

## ✨ Status

**Current version: v0.1** — tested on the developer's own camera (Yi Home 2K Pro / `y623`).

- [x] **Sign in** with your Yi account (email + password) and **create a password** for accounts made with Google.
- [x] **Camera list** with online state; **rename**, **remove** and **add** cameras (pairing QR the camera reads).
- [x] **Live view** (H.264 / H.265, hardware decoding), full screen, **snapshot** and **MP4 recording** (video only).
- [x] **Events**: cloud alerts with pictures and clips (motion, person, sound, vehicle, pet…).
- [ ] *Untested yet:* push-to-talk, per-camera settings (power, privacy, LED, night vision, sensitivity, microSD).
- [ ] HD/2K stream (only the 640×360 substream arrives), audio, direct LAN connection, microSD playback.

## Build instructions for developers

Requirements: Android SDK, JDK 17+, `adb`, `d2j-dex2jar` (`brew install dex2jar`) and an **arm64** phone with **Yi Home** (`com.ants360.yicamera.international`, tested with 6.9.9) installed.

```bash
# 1. extract Yi's SDK, native libs and keys from YOUR phone (never committed, see .gitignore)
tools/extract_sdk.sh                      # phone connected over adb
# or from APKs you already have:
tools/extract_sdk.sh base.apk split_config.arm64_v8a.apk

# 2. build and install
./gradlew installDebug
```

`extract_sdk.sh` generates `app/libs/yisdk.jar`, `app/src/main/jniLibs/arm64-v8a/*.so` and `yi.properties`. The project does not compile without them.

## License

[MIT](LICENSE) for the code in this repository. Yi's SDK and native libraries are proprietary and are **not** covered by this license nor distributed here.

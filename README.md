<h1 align="center">YiSecure · Unofficial app for Yi Home cameras</h1>

<p align="center">
  <img src="https://img.shields.io/badge/status-v1.0-c62828" alt="v1.0">
  <img src="https://img.shields.io/badge/Android-7.0%2B-3ddc84?logo=android&logoColor=white" alt="Android 7.0+">
  <img src="https://img.shields.io/badge/Kotlin-Compose-7f52ff?logo=kotlin&logoColor=white" alt="Kotlin + Compose">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue" alt="MIT License"></a>
</p>

**YiSecure** is a clean, ad-free Android app for **Yi Home** security cameras, a sibling of [Mandix](https://github.com/MetalSyntax/Mandix-Multicontrol) and [Pulsix](https://github.com/MetalSyntax/Pulsix-Multiwatch). It signs in with your Yi account, lists your cameras and plays them live, without the upsells of the stock app.

The cameras speak Yi's own P2P protocol (TUTK / TNP), so the app talks to them through Yi's SDK, which **each user extracts from their own Yi Home install** (see [How to compile](#build-instructions-for-developers)). No Yi code or keys live in this repository.

> Not affiliated with, endorsed by or connected to Yi Technology or Kami Vision. "Yi" and "Yi Home" are trademarks of their owners.

---

## ✨ Status

**Current version: v1.0** — built and tested on the developer's own cameras (Yi Home 2K Pro / `y623`). See [PLAN_FASES.md](PLAN_FASES.md) for what is confirmed on hardware and what is still experimental.

- [x] **Sign in** with your Yi account and **create a password** for accounts made with Google.
- [x] **Cameras**: home / away / night **profiles**, **panic** button, big main camera + grid with status tags, online state, add / rename / remove.
- [x] **Live view** with audio, push-to-talk, hands-free call, snapshot, MP4 recording, pinch zoom, **eye toggle** to hide every overlay, **auto-reconnect** and a **Wi-Fi local / P2P / relay** connection tag (the SDK always tries the local Wi-Fi first).
- [x] **On-phone AI** (TensorFlow Lite): people, vehicles, pets (cats, dogs, birds), motion, shadows; approach / leave, loitering; **watch zone** drawn over the picture.
- [x] **microSD NVR**: continuous recording as a thin track, events as tall bars, ±30 s / ±5 min / next event, pause, **download MP4**, usage bar, format.
- [x] **Events**: cloud alerts and AI detections by day, new / read, favourites, share, save to gallery, "view on microSD", pet summary.
- [x] **Notifications** of new cloud alerts (every 15 min), global silent mode, app lock with fingerprint, **home-screen widget**, wall-tablet **monitor mode**, ZIP **backup** of AI events.
- [x] Per-camera settings with an **Advanced** section: detections, glass mode, firmware update, reboot, privacy, LED, night vision, sensitivity.
- [ ] Not possible with Yi's SDK: playback speed (2x/4x/8x). Real PTZ is left out until a motorised camera is available. Siren pending a model that has one.

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

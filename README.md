# DiAuto Pro

**Android Auto on your BYD display. Wireless or USB.**

> **This repository is a maintained Chinese-localized fork** of
> [shihabal3amri/DiAuto](https://github.com/shihabal3amri/DiAuto), which itself is an
> independent fork of [Open Headunit](https://github.com/andreknieriem/open-headunit).
> See [Credits and license](#credits-and-license) below.
>
> **BYD support scope:** These projects focus on BYD cars. They may work on other brands,
> but other brands are unsupported and there are no plans to add support or fix
> brand-specific incompatibilities.

[Download & installation](https://zutaozhong-hash.github.io/DiAuto-Pro/) ·
[Latest release](https://github.com/zutaozhong-hash/DiAuto-Pro/releases/latest) ·
[Telegram updates](https://t.me/DiAutoPro)

[English](https://zutaozhong-hash.github.io/DiAuto-Pro/) · [العربية](https://zutaozhong-hash.github.io/DiAuto-Pro/ar/) · [Русский](https://zutaozhong-hash.github.io/DiAuto-Pro/ru/) · [Español](https://zutaozhong-hash.github.io/DiAuto-Pro/es/) · [简体中文](https://zutaozhong-hash.github.io/DiAuto-Pro/zh-Hans/)

Install DiAuto Pro **on the car**, then connect a compatible Android phone using its
built-in Android Auto support. **No additional phone companion app, dongle, external
hardware, root or firmware modification is required.** An Android phone is still
required; this is not Apple CarPlay.

![Android Auto with the current music card and a freely panned map, without a destination](site/assets/projection-music.png)

## What's new in 0.3.12

- Renamed the debug build to **DiAuto Pro** while keeping the coexisting debug
  application ID, so it can be installed alongside the stable release.
- Fixed the desktop long-press shortcuts menu, where every entry failed silently on the
  debug build because the shortcut target package was pinned to the release ID.
- Added a build-time guard that fails the debug build if `shortcuts.xml` and the
  variant application ID ever drift apart again.
- Added a dedicated release signing key; release APKs are now signed v1 + v2.
- Portuguese (Brazil), Vietnamese and other community translations kept in sync.

## Features

- Native wireless Android Auto, with USB data-cable support.
- BYD windshield navigation arrows, distance and street names on verified firmware, without ADB.
- Improved recovery when the Wi-Fi Direct interface appears late; explicit Static BSSID remains supported.
- Automatic Wi-Fi Direct address recovery on supported DiLink firmware, without ADB setup.
- Car-friendly home screen, simplified settings and redesigned option dialogs.
- Music-through-car-Bluetooth mode to avoid competing media audio focus.
- Automatic preference for the car's existing non-DFS 5 GHz Wi-Fi channel when supported,
  with normal band selection as a fallback.
- Bounded SurfaceView frame pacing, full-screen 1080p and corrected touch alignment.
- English, Simplified Chinese, Arabic, Russian and Spanish site coverage, Arabic RTL
  layout, and 20+ community translations in the app.

## Compatibility and setup

Tested on **BYD DiLink 5.1 with Android 13**, using wireless and physical USB connections.
Other models and firmware versions are not yet verified. A compatible Android phone
with functioning Android Auto is required. The app and website support English,
Simplified Chinese, Arabic, Russian and Spanish. Other existing community translations remain available.

See [BYD HUD compatibility and cleanup limits](docs/BYD_NAVIGATION.md).

See the [installation guide](docs/INSTALL.md), including permissions, Static BSSID and
upgrading from private previews. APK installation must be allowed by your head unit.
Set up while parked.

The tested panel exposes approximately 58 Hz. Recent wireless map-drag samples reached
47–58 displayed FPS after Wi-Fi channel alignment, with remaining variation and occasional
stalls. **Constant 60 FPS is not promised.** See [validation notes](docs/REVIEW.md).

## Build

Requires JDK 17, Android SDK 36, NDK `29.0.14206865`, and CMake `3.22.1`.
Set `ANDROID_HOME` or an untracked `local.properties` with `sdk.dir=...`.

```sh
./gradlew :app:testGithubDebugUnitTest :app:assembleGithubDebug :app:assembleGithubRelease
```

APKs are under `app/build/outputs/apk/github/`. Release builds use R8/resource shrinking.
They are unsigned unless an untracked `key.properties` provides:

```properties
storeFile=/absolute/path/to/your-release-keystore.jks
storePassword=YOUR_PRIVATE_PASSWORD
keyAlias=YOUR_KEY_ALIAS
keyPassword=YOUR_PRIVATE_PASSWORD
```

`storeFile` may be relative to either the repository root or the `app/` module; both
locations are tried.

Never commit signing keys or passwords. Your self-built APK will not upgrade a public
release unless signed with the same key. The public release signing key is held privately
by the maintainer.

Application IDs:

| Build | Application ID | Label |
| --- | --- | --- |
| Release | `com.andrerinas.headunitrevived` | DiAuto Pro |
| Debug (coexist) | `com.andrerinas.headunitrevived.bydhudtest` | DiAuto Pro |

The debug ID lets a test build sit next to a stable install on the same head unit.
Only the `github` flavor is covered by this fork's CI.

## Website

Five static language editions live under `site/` and are generated by
`scripts/build_site.py`. Edit `site/content.json` and the `BASE` / `REPO` / `VERSION`
constants near the top of that script, then run:

```sh
python3 scripts/build_site.py
```

Do not hand-edit `site/*/index.html` — the next build overwrites them.
GitHub Actions deploys only `site/` to GitHub Pages.

## Credits and license

DiAuto Pro is a Chinese-localized fork of
[shihabal3amri/DiAuto](https://github.com/shihabal3amri/DiAuto), which is an independent
fork of [Open Headunit](https://github.com/andreknieriem/open-headunit),
based on work by its contributors and [Michael Reid](https://github.com/mikereidis/headunit).
The local DiLink fork began from upstream commit `562c8dc`; the first public snapshot
includes the DiAuto modifications developed from that fork.

See [LICENSE](LICENSE) (AGPL-3.0), original copyright notices, in-app credits and
[third-party notices](docs/THIRD_PARTY.md). This repository contains the source for the
published app. Android Auto is a Google trademark. This project is not affiliated with
or endorsed by Google or BYD.

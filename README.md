# DiAuto Pro

**Android Auto on your BYD display. Wireless or USB.**

**Also mirrors the Android Auto picture onto the instrument cluster** — with a configurable
trigger, so you choose whether it mirrors always, only while a map is on screen, or only
while a route is being guided. Targets DiLink 4.0, 5.0 and 5.1.
[Jump to the details](#1-instrument-cluster-mirror).

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

[English](README.md) · [简体中文](README.zh-CN.md) · [العربية](https://zutaozhong-hash.github.io/DiAuto-Pro/ar/) · [Русский](https://zutaozhong-hash.github.io/DiAuto-Pro/ru/) · [Español](https://zutaozhong-hash.github.io/DiAuto-Pro/es/) · [简体中文网站](https://zutaozhong-hash.github.io/DiAuto-Pro/zh-Hans/)

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

## Headline features

Three capabilities set this fork apart from a plain Android Auto receiver. Each is
described below with its actual limits, not just its promise.

### 1. Instrument cluster mirror

Put the Android Auto picture on the **vehicle instrument cluster display**, next to the
speed and gear read-outs, using the cluster area BYD already exposes to third-party apps.

The cluster's projection area is published by `com.byd.containerservice` as a public
presentation display, so no BYD-specific permission is required. The app discovers it by
display name and picks the best layer present, in this order:

| Firmware | Layer published | How the mirror is carried |
| --- | --- | --- |
| DiLink 4.0 / 5.0 | `fission_bg_XDJAScreenProjection` | Own activity launched with `setLaunchDisplayId` |
| DiLink 5.1 | `shared_fission_bg_XDJAScreenProjection_0` (full map) | `Presentation` |
| DiLink 5.1 | `shared_fission_bg_XDJAScreenProjection_1` (side map) | `Presentation` |

Both carriers are kept because `Presentation` is not guaranteed to composite on every
firmware. The controller tries them in order and falls back automatically.

**The mirror adapts its viewport to the firmware**, so it never covers the instruments:

- **DiLink 4.0 / 5.0** — the published layer already excludes the instrument panels, so the
  mirror uses the whole display.
- **DiLink 5.1** — the stock map's own layers are hidden from third parties, and the
  `shared_` siblings still include the top status strip and the bottom gear/range strip.
  The mirror occupies only the map band (top 144 px and the bottom strip are kept clear),
  or the right-hand 600×720 side card on the side-map layer. Covering the speed or gear
  read-out would be a safety problem, not a cosmetic one.

The stock cluster resolution is **1920×720** on every firmware measured so far. On an
unmeasured resolution the mirror falls back to the whole display, because the band offsets
are only known for 1920×720 — a wrong-but-visible picture beats a silently invisible one.

The app draws nothing itself here: it samples the Android Auto picture and places it into
the viewport. The stock instrument panels stay live underneath.

> **Availability:** this is an **experimental** feature and it is **off by default**. It only
> works on a vehicle whose cluster area is already shared with apps. If no cluster display
> is exposed, the setting reports the feature as unavailable rather than failing — there is
> nothing to install or work around.
>
> Enable it under **Settings → Advanced → "Instrument cluster map mirror"**. The timing
> control below it is intentionally **always shown**, even while the mirror is off: a control
> that hides itself is indistinguishable from a feature that was never built.

### 2. Configurable mirror timing

Mirroring the whole time is not always what you want: on a long media-only drive you may
prefer the cluster to keep showing the car's own display. **Settings → Advanced → "Mirror
timing"** offers three tiers. It defaults to **Always**, so an existing setup behaves
exactly as before until you change it.

| Tier | Mirrors while | Use it when |
| --- | --- | --- |
| **Always** | A session is up, whatever is on screen | You want the cluster to behave like a second screen (default) |
| **Cruise + nav** | A map is on screen, whether or not a route is guided | You want the map whenever you are looking at one |
| **Nav only** | A route is actively being guided | You want the cluster untouched except during turn-by-turn |

The three tiers are **strictly nested** — `Nav only` implies `Cruise + nav` implies
`Always` — so the choice is a single ordered preference with no ambiguous combinations.

The decision is driven by explicit signals from Android Auto rather than by guessing:
`INSTRUMENT_CLUSTER_START` / `STOP` is the reliable latch for "a map app owns the cluster
view", and `INSTRUMENT_CLUSTER_NAVIGATION_STATUS` distinguishes a guided route (`ACTIVE` /
`REROUTING`) from a map that is merely on screen (`INACTIVE`). A generous 30-second window
absorbs the quiet stretches of a long straight road, so the mirror does not blink off where
a map simply has nothing to report.

The rules are pure logic with no Android types, so they are **unit-tested on the JVM**
rather than asserted in comments.

### 3. DiLink 4.0 and 5.x firmware support

The cluster mirror targets **DiLink 4.0, 5.0 and 5.1** rather than a single firmware, which
is why the layer table above has three rows instead of one.

Supporting 4.0 is not a cosmetic matter of matching a string. DiLink 4.0 builds **start
their own activity with `setLaunchDisplayId`** instead of using a `Presentation`, so a
presentation-only implementation silently produces nothing on those units. Both carriers
are therefore implemented and selected at runtime, and the geometry follows the firmware:
full display where the layer excludes the instruments, map band where it does not.

Other firmware-specific handling in the same spirit:

- **Wi-Fi Direct band requests** only exist from API 29, and the code takes the supported
  path there while keeping a working fallback below it.
- **Android 10 BYD firmware pins the local hotspot to 2.4 GHz.** The app detects this and
  reports the specific remedy instead of a generic failure.
- **The BSSID read-back is often masked on Android 10 when GPS is off**, which the
  connection flow accounts for.

These paths are implemented against the shared firmware behaviour and are covered by unit
tests, **but only DiLink 5.1 has been exercised on a real car** — see
[Compatibility](#compatibility-and-setup) below for the honest boundary.

## Features

- Native wireless Android Auto, with USB data-cable support.
- **Instrument cluster mirror with configurable timing** — see
  [the section above](#1-instrument-cluster-mirror).
- **DiLink 4.0 / 5.0 / 5.1 layer support** for the cluster mirror, with two carriers and
  per-firmware viewport geometry.
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

**What "tested" means here, precisely:** the DiLink 5.1 wireless and USB paths, and the
5.1 cluster-mirror layer handling, have been exercised on a real car. The **DiLink 4.0 /
5.0 cluster code paths and the Android 10 connection paths are implemented and unit-tested
but have not been verified on hardware yet.** If you run one of those firmwares, expect to
be an early tester, and please report what you see.

A compatible Android phone with functioning Android Auto is required. The app and website
support English, Simplified Chinese, Arabic, Russian and Spanish. Other existing community
translations remain available.

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

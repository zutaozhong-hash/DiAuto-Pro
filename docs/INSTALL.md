# Install DiAuto Pro 0.3.12

Install the APK **on the car's Android head unit**, not on your phone.
Tested: BYD DiLink 5.1, Android 13. Other firmware/head units are not verified.
Your phone must support Android Auto; no separate DiAuto phone app or dongle is needed.

## Download

Use the [multilingual download page](https://zutaozhong-hash.github.io/DiAuto-Pro/) or the
[GitHub release](https://github.com/zutaozhong-hash/DiAuto-Pro/releases/tag/v0.3.12).
Download `DiAuto-Pro-0.3.12.apk`. Checksums are provided alongside the APK.

## Install and connect

1. Park the car. Use the APK installation method permitted by your head unit's firmware.
   If using a browser/file manager, allow that application to install APKs when prompted.
   If the car does not offer APK installation, use ADB only if the firmware permits it.
2. Open DiAuto and complete setup. Grant permissions requested for Bluetooth,
   Wi-Fi/location, microphone, notifications and overlay features as applicable.
3. Pair your phone in the **car's Bluetooth settings**. Enable Bluetooth and Wi-Fi on
   your phone, then choose **Connect phone** in DiAuto.
4. Accept Android Auto's first-connection prompts on the phone.
5. For wired operation, use a USB **data** cable and choose **Connect with USB**.

DiLink 5.1 defaults to **Music through car Bluetooth**. Keep the phone paired to the car
for media audio. Navigation and assistant audio remain available through Android Auto.
Changes to this option require reconnecting.

## Optional ADB installation

Install Android platform-tools on your computer, enable ADB using your head unit's
supported method, and connect the computer and car to the same trusted network.
Replace `CAR_IP` below with the car's current IP address; no fixed address is assumed.

```sh
adb connect CAR_IP:5555
adb -s CAR_IP:5555 install -r DiAuto-Pro-0.3.12.apk
```

Open DiAuto on the car to finish setup. The optional repository helper also grants
supported runtime permissions, enables location, allows the overlay and exempts DiAuto
from idle/background restrictions:

```sh
./scripts/install.sh CAR_IP:5555 /absolute/path/to/DiAuto-Pro-0.3.12.apk
```

## Wireless pairing / Static BSSID

DiAuto Pro 0.3.12 can recover the Wi-Fi Direct address automatically on supported
DiLink firmware, even when Android hides the usual MAC address. **ADB, root and
a phone helper app are not required for this recovery.**

Leave **Static BSSID** set to **Auto**, pair through the car's Bluetooth settings,
and choose **Connect phone**. If you previously entered an address manually, you
can select Auto in **Settings → Display and performance → Static BSSID** (or search
for **Static BSSID**), save, and reconnect. Existing manual overrides remain supported.

Automatic recovery depends on the firmware exposing a MAC-derived IPv6 address
on the active Wi-Fi Direct interface. It cannot fix every wireless connection
failure. If pairing still fails, report the car model/firmware and phone model;
do not copy another car's address.

If the log says the BSSID could not be recovered, you can still read it without
ADB: while DiAuto shows it is waiting for the phone, open any Wi-Fi scanner app
on the phone, find the network named `DIRECT-…` shown by DiAuto, and enter its
BSSID as **Static BSSID**. That is the address the phone checks when joining.

## Updates and private previews

The first public release uses a dedicated production signing key. Future public releases
will use that same key so they can update in place.

Private `0.3.x-preview` APKs were development-signed. Upstream Open Headunit builds may
also share the application ID but use a different key. Android rejects an in-place update
when signatures differ. Export settings from the existing app first, then uninstall that
app and install the public APK. Uninstalling removes local settings and may require pairing
again. Do not uninstall the official BYD phone app; it is unrelated to DiAuto.

## Troubleshooting

- If a 2.4 GHz connection repeatedly starts and disconnects before video appears,
  try lowering resolution and frame rate in Display and performance, then reconnect.
  This release leaves video preferences under your control; it does not force a
  lower-resolution profile. This will not resolve every Bluetooth or Wi-Fi join failure.

- If scrolling stutters, compare a USB connection. Wireless conditions and concurrent
  car Wi-Fi connections can affect performance. DiAuto now prefers a supported matching
  5 GHz channel but cannot guarantee that firmware or the phone will accept it.
- If Bluetooth music cuts out, verify **Music through car Bluetooth** is enabled and
  reconnect. Test assistant prompts and calls too; report the route and circumstances.
- If the picture is wrong, try the other **View Mode** in Display and performance.
- For reports, include head-unit model/firmware, phone model/Android, connection type,
  DiAuto version and reproducible steps. Remove locations, device identifiers and
  account details from public logs/screenshots.

## BYD navigation

See [BYD navigation displays](BYD_NAVIGATION.md) for the firmware scope, map metadata requirements, settings and cleanup behavior. No runtime ADB starter is required.

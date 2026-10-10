# FoldFix

FoldFix gets a first-generation Google Pixel Fold switching between its inner and outer screens again after its hinge sensor has died.

It's a small Android app. On current Pixel Fold software it needs no computer and no root, not even for setup. It's a workaround for a hardware fault, not a repair, and it isn't affiliated with Google.

## Is this for you?

FoldFix is for a **first-gen Pixel Fold** (2023, codename `felix`) with these symptoms:

- [ ] Closing the phone doesn't move what's on screen to the outer screen, and opening it doesn't move it back.
- [ ] After you fold the phone shut, the outer screen stays black, as if the phone were still open.
- [ ] Half-open (tabletop) mode stopped working too.
- [ ] A hardware diagnostic says fold detection works, but Android still doesn't switch screens.

This failure has been widely reported on first-gen Pixel Folds in 2025 and 2026.

### What's going on

A Pixel Fold has two sensors that notice folding:

| | Hinge-angle sensor | Hall-effect (magnet) sensor |
| --- | --- | --- |
| What it measures | The exact angle between the two halves, 0 to 180 degrees | Whether a magnet in the other half is close: closed, or not closed |
| What relies on it | Android's open / half-open / closed detection, tabletop mode, the fold animation | Some hardware diagnostics, and FoldFix |
| On affected phones | **Stops reporting anything** | **Keeps working** |

Android's fold detection on the Pixel Fold uses the hinge angle. When that sensor goes silent, Android never sees the phone close. It keeps using the inner screen as if the phone were always open, and logs `Have not received sensor event` over and over. Hardware diagnostics that read the magnet sensor directly can still show fold detection working.

The hall-effect sensor is a simple magnetic switch that triggers when the two halves come together. It can't tell half-open from fully open, but it reliably tells closed from not closed, and that's all you need to pick a screen. FoldFix listens to it and tells Android which screen to use.

On some phones the same damage also means the outer screen is **only detected when the phone starts up closed**. FoldFix can't change that, but it's easy to live with. See [Limitations](#limitations).

**FoldFix can't help if:**

- the hall sensor doesn't react either (FoldFix's status screen shows this),
- the outer screen stays undetected even after restarting with the phone closed, or
- your phone already switches screens normally.

## Check your phone

### With the FoldFix app

Install the app (step 1 below) and open **FoldFix**. Its status screen has these sections:

| Section | Affected phone | What else you might see |
| --- | --- | --- |
| **Accessibility service** | `On.` plus what FoldFix is doing, once set up | `Off: turn on FoldFix in Accessibility settings`: see [setup](#install-and-set-up) |
| **Hall sensor (closed / open)** | The sensor's name, then `closed` or `open`, changing as you fold | `Not found: this phone is not supported` |
| **Hinge angle sensor** | `No readings: the sensor is likely broken` (after listening for 3 seconds) | An angle such as `178°`: the sensor works, so you may not need FoldFix |
| **Outer screen** | `Detected` | `Not detected`: restart the phone while it's closed |
| **Rear-display access** | `OK, no setup needed` | `Needs a one-time adb command`: see [Troubleshooting](#status-screen-asks-for-an-adb-command) |
| **Device state** | `2 OPENED (rear display off)` even while the phone is folded | |

Everything except the **Accessibility service** section works right after installing.

### With a computer (adb)

```sh
adb shell "dumpsys sensorservice | grep -E 'Hinge Angle|Hall Effect'"
adb shell "logcat -d | grep -m 3 'Have not received sensor event'"
```

It's this failure if the output shows a `DRV5032 Hall Effect Sensor (wake-up): last N events` line but **no** `Hinge Angle (wake-up): last N events` line, and the log shows `Have not received sensor event`.

[docs/diagnosis.md](docs/diagnosis.md) walks through all the checks and shows healthy and broken output.

## Install and set up

You need a Pixel Fold. FoldFix installs on Android 13 or newer and has been tested on Android 17. A computer is optional.

### 1. Install the app

Download `fold-fix.apk` from the [latest release](../../releases/latest) and open it on the phone. Allow your browser or file manager to install apps when asked. Google Play Protect may offer to scan it first, which is normal for apps from outside the Play Store.

Or install it from a computer with [adb](https://developer.android.com/tools/releases/platform-tools):

```sh
adb install fold-fix.apk
```

You can also [build it yourself](#build-from-source).

<a id="allow-restricted-settings"></a>

### 2. Allow restricted settings

On Android 13 and newer, Android can block sideloaded apps from turning on an accessibility service until you allow it. This may not be needed on every setup.

1. In FoldFix, tap **Open accessibility settings**, choose **FoldFix**, and try to turn it on. If Android says it's a restricted setting, tap **OK**.
2. Back in FoldFix, tap **Open app info**.
3. Tap **⋮** (top right) **→ Allow restricted settings**, and confirm. The option usually only appears after step 1.

### 3. Turn on the FoldFix service

In FoldFix, tap **Open accessibility settings**, choose **FoldFix** (under "Downloaded apps"), turn on **Use FoldFix**, and confirm.

Android will warn that the app can view and control your screen. [Privacy and permissions](#privacy-and-permissions) explains what FoldFix actually does with that access.

Or from a computer:

```sh
adb shell settings put secure enabled_accessibility_services app.foldfix/app.foldfix.FoldService
adb shell settings put secure accessibility_enabled 1
```

This **replaces** the list of enabled accessibility services. If you use others, run `adb shell settings get secure enabled_accessibility_services` first, and join the entries with `:`.

### 4. Try it

Close the phone: the outer screen should turn on. Open it: the inner screen takes over again. In FoldFix, the **Accessibility service** section now reads `On. Closed: outer screen on` or `On. Open: inner screen on`.

If the outer screen doesn't turn on and the **Outer screen** section says `Not detected`, power the phone off, fold it shut, and power it on again while it's closed.

That's it. FoldFix starts by itself at every boot, even before you unlock the phone for the first time.

## Daily use

- Nothing to do. Fold and unfold as usual.
- **Restart with the phone closed**, if your phone only detects the outer screen when it starts up closed. You can restart from the outer screen's power menu.
- Other apps that use the outer screen on purpose, like Pixel Camera's rear-camera selfie mode, keep working.
- After an Android update, open FoldFix once and check that everything is still OK.

## Troubleshooting

**The outer screen never turns on.**
The outer screen probably wasn't detected at startup. FoldFix's **Outer screen** section will say `Not detected`. Power off, fold the phone shut, and power on while it's closed. When the outer screen is missing, FoldFix deliberately does nothing, because switching to a screen that isn't there can crash Android.

**Both screens are black.**
Press the power button, or open and close the phone once. Every fold or unfold makes FoldFix try again straight away. On its own, it retries every 2 seconds, slowing to every 30 seconds after 5 tries that didn't help, so it may also recover by itself. If it doesn't, fold the phone shut and force-restart it: hold the power button until the "G" logo appears, which can take up to a minute. Then please [open an issue](../../issues).

**The wrong screen is on.**
Fold and unfold once. Then open FoldFix and check:

- **Accessibility service** says `On`. If it says `Off`, turn it on again ([step 3](#3-turn-on-the-foldfix-service)).
- **Hall sensor** shows the right `closed` / `open` state.
- **Rear-display access** says `OK`.

If it says `Open: another app is using the outer screen`, an app such as Pixel Camera switched screens on purpose. FoldFix leaves that alone; leave the app to switch back.

**It says "Rear-display mode is unavailable right now".**
Android sometimes refuses rear-display mode, possibly in battery saver or when the phone is hot. FoldFix keeps checking and switches as soon as Android allows it.

**You can see a "Switch screens?" dialog.**
FoldFix answers this dialog on the folded inner screen, normally before anyone could see it. If it shows up on the screen you're looking at, choose an option yourself and [open an issue](../../issues), mentioning your Android version. An update may have changed the dialog.

**The FoldFix switch in Accessibility settings is greyed out.**
See [Allow restricted settings](#allow-restricted-settings).

<a id="status-screen-asks-for-an-adb-command"></a>

**The status screen asks for an adb command.**
Only do this if **Rear-display access** says `Needs a one-time adb command from a computer`. FoldFix normally reaches rear-display mode through a system library that needs no setup. If that library is missing or has changed on your phone, FoldFix falls back to a hidden Android API, which needs one setting made from a computer:

1. Turn on USB debugging: **Settings → About phone →** tap **Build number** seven times, then **Settings → System → Developer options → USB debugging**.
2. Connect the phone to a computer with [adb](https://developer.android.com/tools/releases/platform-tools) and accept the "Allow USB debugging?" prompt.
3. Run (FoldFix's **Copy adb command** button copies it):

   ```sh
   adb shell settings put global hidden_api_blacklist_exemptions Landroid/hardware/devicestate/
   ```

4. Restart the phone, with it closed. FoldFix checks for the setting when it starts.

This lets apps use the hidden `android.hardware.devicestate` classes. It's a system-wide setting, so it applies to every app, but only for those classes. It survives reboots. If you already use this setting for something else (rare), check it first with `adb shell settings get global hidden_api_blacklist_exemptions` and add this value with a comma instead of overwriting it.

To undo it:

```sh
adb shell settings delete global hidden_api_blacklist_exemptions
```

**Turning it off or uninstalling.**

- To pause it: **Settings → Accessibility → FoldFix →** off. The phone goes back to Android's own behaviour (inner screen only).
- To uninstall: **Settings → Apps → FoldFix → Uninstall**, or `adb uninstall app.foldfix`.

Do this with the phone **open**, after the inner screen has come on. An active rear-display request stays in place after FoldFix stops, and once the service is off, Android won't let it switch screens any more.

If you turned FoldFix off while the phone was closed and it stays on the outer screen after you unfold it, do one of these:

- With the phone open, open the FoldFix app. It switches back to the inner screen by itself and says so. Its **Accessibility service** section reads `Off, but the outer screen is still held` until then.
- Turn FoldFix back on.
- Restart the phone.

After uninstalling, only a restart helps.

## Limitations

- **Restart with the phone closed.** On some phones, the outer screen is only detected when the phone boots closed. That's decided by the bootloader, before any app runs, and it also points to damage in the hinge cable. Phones with only a dead hinge sensor may not have this problem.
- **No half-open postures.** Tabletop mode and apps that adapt to a half-folded phone need the hinge angle.
- **No fold/unfold animation.** It's driven by the hinge angle too.
- **Unofficial interfaces.** FoldFix relies on private details of the system's window-manager extensions library and on the layout of SystemUI's dialog. An Android update could break it.
- **Tested on one setup.** See [Tested on](#tested-on).
- **It's a workaround.** The real fix is a hardware repair of the hinge flex cable or sensor.

## Tested on

| Device | Android | Build | Result |
| --- | --- | --- | --- |
| Pixel Fold (gen 1, `felix`) | 17 | CP3A.261005.002 (October 2026 patch) | Works, no adb setup needed |

The app installs on Android 13 and newer. Rear-display mode and its "Switch screens?" dialog are expected on recent Android versions, but only Android 17 has been tested.

It might also help **other foldables** with the same failure (dead hinge-angle sensor, working hall sensor), if they offer a rear-display mode. That's untested. If you try, please [report back](#contributing), whether it works or not.

## Privacy and permissions

To enable an accessibility service, Android shows a strong warning that the app can view and control your screen. That's because accessibility services *could* read anything on screen. FoldFix uses the service for three things:

1. starting automatically at boot and staying running,
2. opening its own invisible screen from the background (Android only accepts a screen switch from the app in the foreground),
3. finding and tapping SystemUI's "Switch screens?" dialog.

FoldFix:

- only looks at System UI. Its accessibility configuration limits events to `com.android.systemui`. To find the dialog, it only opens system windows, and only searches those that belong to System UI, by view ID. It never opens other apps' windows, and it doesn't read, log or store any text from the screen or anything you type.
- reads the hall-effect and hinge-angle sensors.
- requests **no Android permissions**, not even internet access. It collects no data and has no analytics or ads.
- stores one setting on the phone: whether its own rear-display request may still be active.
- is open source. You can read the code and [build it yourself](#build-from-source).

## How it works

In short: an accessibility service watches the hall sensor. When the phone closes, it briefly opens an invisible activity that asks Android for rear-display mode (outer screen on, inner off), using the system's own window-manager extensions library. It then taps the confirmation dialog that appears on the folded, unseen inner screen. When the phone opens, it ends the request. A reconcile loop keeps the sensor and the device state in agreement, so fast folding and unfolding can't leave it stuck.

[docs/how-it-works.md](docs/how-it-works.md) covers the details: device states, the two ways of making the request, the dialog, the trampoline activity, why requests can outlive the app, and the guard against a missing outer screen.

## Build from source

Requirements:

- JDK 17 or newer
- The Android SDK with any recent build-tools and an `android-35` (or newer) platform. The build looks in `ANDROID_HOME` / `ANDROID_SDK_ROOT`, or the default SDK location on macOS and Linux.
- No Gradle needed.

```sh
./build.sh
```

The APK ends up in `build/fold-fix.apk`.

The build picks the newest installed build-tools and platform automatically. These environment variables change its behaviour:

| Variable | Default | Purpose |
| --- | --- | --- |
| `KEYSTORE` | `~/.android/debug.keystore` (created if missing) | Signing keystore |
| `KEYSTORE_PASS` | `android` | Keystore password |
| `KEY_ALIAS` | `androiddebugkey` | Key alias |
| `KEY_PASS` | same as `KEYSTORE_PASS` | Key password |
| `BUILD_TOOLS_VERSION` | newest installed | Build-tools version to use, e.g. `35.0.0` |
| `ANDROID_PLATFORM` | newest installed | Platform to compile against, e.g. `android-35` |

Android only installs an update over an existing app if both are signed with the same key. If you switch between your own build and a release, uninstall first; you'll need to turn the accessibility service on again afterwards.

### Releases

GitHub Actions ([`.github/workflows/build.yml`](.github/workflows/build.yml)) has two jobs:

- **Pushes to `main`, pull requests and manual runs from a branch:** build with a throwaway debug key and upload `fold-fix.apk` and its SHA-256 checksum as a build artifact. These builds never see the release key. They won't install over a release or over each other without uninstalling first.
- **Tags `v*`:** build with the release key and publish a GitHub Release with `fold-fix.apk` and its checksum attached. The build log prints the signing certificate's SHA-256. This job fails if:
  - the tag isn't `v` followed by `android:versionName` from `app/AndroidManifest.xml` (for example `v1.0.0`), or
  - the signing secrets are missing.

CI builds with build-tools `35.0.0` and `android-35`, and all actions are pinned to commit SHAs.

To publish a release, bump `android:versionName` and `android:versionCode` in `app/AndroidManifest.xml`, commit, then tag and push:

```sh
git tag v1.0.0
git push origin v1.0.0
```

#### Signing key

Releases must be signed with the same key every time, so updates install over each other. The release job runs in a GitHub Environment named `release` and reads these secrets:

| Secret | Contents |
| --- | --- |
| `KEYSTORE_BASE64` | The keystore file, base64-encoded |
| `KEYSTORE_PASS` | Keystore password |
| `KEY_ALIAS` | Key alias |
| `KEY_PASS` | Key password (optional; defaults to `KEYSTORE_PASS`) |

They can be secrets of the `release` environment or plain repository secrets. Environment secrets are recommended: under **Settings → Environments → release** you can limit them to `v*` tags and require an approval before a release is built. Create the environment there first, then:

```sh
keytool -genkeypair -keystore release.jks -alias foldfix -keyalg RSA -keysize 4096 -validity 10000
base64 < release.jks | tr -d '\n' | gh secret set KEYSTORE_BASE64 --env release
gh secret set KEYSTORE_PASS --env release
gh secret set KEY_ALIAS --env release --body foldfix
gh secret set KEY_PASS --env release
```

Leave out `--env release` to store them as repository secrets instead.

Keep `release.jks` and its passwords somewhere safe and out of the repository. If the key is lost, existing users have to uninstall to get updates.

## Contributing

Reports from other devices and Android versions are the most useful contribution. If you've tried FoldFix on another foldable or Android build, please [open an issue](../../issues) with:

- device model and Android build (`adb shell getprop ro.build.fingerprint`),
- what happened when folding and unfolding,
- what FoldFix's status screen shows,
- the output of the checks in [docs/diagnosis.md](docs/diagnosis.md),
- FoldFix's log while you fold and unfold a few times: `adb logcat -s FoldFix`.

Logs can contain personal information. Trim them to the relevant lines before posting.

Bug fixes and pull requests are welcome. FoldFix should stay small: no network access, no data collection, and no new permissions without discussion first.

## License

[MIT](LICENSE)

## Disclaimer

FoldFix is not affiliated with, endorsed by, or supported by Google. "Pixel" and "Pixel Fold" are trademarks of Google LLC.

FoldFix changes how your phone picks a screen, using unofficial Android interfaces. Use it at your own risk; the software comes with no warranty (see the [license](LICENSE)). If something goes wrong, restarting the phone hands control back to Android.

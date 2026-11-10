# FoldFix

FoldFix gets a first-generation Google Pixel Fold switching between its inner and outer screens again after its hinge sensor has died.

It's a small Android app. On current Pixel Fold software it needs no computer and no root, not even for setup. It's a workaround for a hardware fault, not a repair, and it isn't affiliated with Google.

## Is this for you?

FoldFix is for a **first-gen Pixel Fold** (2023, codename `felix`) with these symptoms:

- [ ] Closing the phone doesn't move what's on screen to the outer screen, and opening it doesn't move it back.
- [ ] After you fold the phone shut, the outer screen stays black, as if the phone were still open.
- [ ] Half-open (tabletop) mode stopped working too.
- [ ] A hardware diagnostic says fold detection works, but Android still doesn't switch screens.

Other owners of first-gen Pixel Folds reported this failure in 2025 and 2026.

### What's going on

A Pixel Fold has two sensors that notice folding:

| | Hinge-angle sensor | Hall-effect (magnet) sensor |
| --- | --- | --- |
| What it measures | The exact angle between the two halves, 0 to 180 degrees | Whether a magnet in the other half is close: closed, or not closed |
| What relies on it | Android's open / half-open / closed detection, tabletop mode, the fold animation | Some hardware diagnostics, and FoldFix |
| On affected phones | **Stops reporting anything** | **Keeps working** |

Android's fold detection on the Pixel Fold uses the hinge angle. When that sensor goes silent, Android never sees the phone close. It keeps using the inner screen as if the phone were always open, and logs `Have not received sensor event` over and over. Hardware diagnostics that read the magnet sensor directly can still show fold detection working.

The hall-effect sensor is a simple magnetic switch that triggers when the two halves come together. It can't tell half-open from fully open, but it reliably tells closed from not closed, and that's all you need to pick a screen. FoldFix listens to it and tells Android which screen to use. It does that with **rear-display mode**: a mode Android has for an open phone turned around, in which the outer screen is on and the inner one is off.

On some phones the same damage also means the outer screen is **only detected when the phone starts up closed**. That is easy to live with: see [Limitations](#limitations).

**FoldFix can't help if:**

- the hall sensor doesn't react either (the FoldFix app shows this on its status screen, the screen you see when you open it),
- the outer screen stays undetected even after restarting with the phone closed, or
- your phone already switches screens normally. FoldFix has nothing to add there. While Android itself has the phone in its closed state, FoldFix asks for nothing, and its status reads `Closed: Android has switched to the outer screen by itself`. That has only been tried with the closed state forced from a computer, on a phone whose hinge sensor is dead: FoldFix hasn't been run on a phone with a working one. What isn't known is the moment of closing or opening, when the two sensors may disagree for an instant. FoldFix may then ask for the outer screen and take that back, which could show as a flicker.

## Privacy and permissions

FoldFix runs as an accessibility service, the kind of background helper that Android offers for assistive tools such as screen readers. To turn one on, Android shows a strong warning that the app can view and control your screen. The warning is there because an accessibility service *could* read anything on screen. This section says what FoldFix does with that access.

In short: FoldFix asks for no permissions and has no internet access. From the screen it reads one system dialog, and no text. It keeps one setting and, in memory only, a short history of its own steps. The rest of this section is the detail behind that.

FoldFix uses the service for four things:

1. starting automatically at boot and staying running,
2. opening its own screens from the background: an invisible one that switches screens (Android only accepts a screen switch from the app in the foreground), and a black one that covers Pixel Camera for a moment after unfolding,
3. finding and tapping the "Switch screens?" dialog, with which Android asks for confirmation each time FoldFix asks for rear-display mode,
4. keeping landscape the right way up on the outer screen, with an invisible, zero-size overlay that only tells Android which orientation to use.

What it reads:

- **From the screen, only that one dialog.** The dialog belongs to System UI, the part of Android that draws the lock screen, the status bar and system dialogs. FoldFix's accessibility configuration limits the events it gets to System UI's (`com.android.systemui`), and FoldFix doesn't look at what an event contains.

  To find the dialog, it asks Android for the list of windows on screen and looks at the type of each. It opens only the ones Android lists as system windows, asks of each which app it belongs to, and searches only System UI's, for the dialog's title and its two buttons. It finds them by their internal names (view IDs), not by their text.

  Android's system windows include the ones that other apps draw on top of the screen, such as a screen filter or an app's floating button. FoldFix opens such a window too, sees that it isn't System UI's, and goes no further. (A window doesn't say whose it is until it is opened.) It never opens an app's ordinary windows, and it doesn't read, log or store any text from the screen or anything you type.
- **Three sensors:** the hall-effect sensor; the hinge-angle sensor, only while FoldFix's status screen is open; and the orientation sensor that auto-rotate uses, only while the phone is closed with the outer screen on.
- **The phone's state:** the fold state that Android reports (the "device state"), how many built-in screens Android found, which way the screen is turned, whether the screen is on, and whether the phone is locked. The last three are for keeping landscape the right way up, as is a fixed Android setting that says in which fold states Android reverses landscape.
- **About itself,** for the status screen: whether its service is turned on (it asks Android for the enabled accessibility services and looks for its own), its version, and the certificate it was signed with.

What it keeps:

- **One setting,** stored on the phone: whether a request for rear-display mode that FoldFix has to end (its own, or one another app left behind) may still be active.
- **A short history of what it did,** in memory only, shown under **Recent activity** on its status screen. It holds FoldFix's own activity lines and nothing else: the last 500 lines such as `hall closed` or `rear display requested (window extensions)`, with their times, device states, and the names and messages of errors. So it shows when the phone was opened and closed. Nothing read from the screen is in it, and no other app is named: FoldFix can tell that the outer screen was asked for, not by which app. It is never written to storage, and it starts again whenever FoldFix restarts.

Where that history can go: FoldFix sends it nowhere. You can copy it (the **Copy** button, or a long press on the lines). Each line also goes to Android's own log, as any app's log lines do. From a computer, Android's adb tool reads that log (`adb logcat`) and prints FoldFix's history (`adb shell dumpsys`). Apps that are part of the phone's system software and hold Android's permissions for reading logs and system reports can read them too, and so can any app that was given one of those permissions over adb. A bug report made with Android's own tools would contain both.

What it doesn't do:

- It requests **no Android permissions**, not even internet access, so it can't go online. It collects no data and has no analytics or ads.
- It never reads the clipboard. It only writes to it when you tap one of its copy buttons, or long-press the Version line, the adb command or the history.
- Its status screen opens two other screens, both Android's own and only when you tap the button: Accessibility settings and FoldFix's app-info page.

Besides the status screen, two parts of FoldFix can be started from outside the app, both for testing: the invisible screen that switches screens, and a switch that makes FoldFix act on a pretended "open" or "closed" instead of its sensor. Ordinary apps can't reach them. They take Android's `DUMP` permission. A computer connected over adb has it, and so do privileged system apps (apps that are part of the phone's system software); any other app only if it was granted over adb. [How it works](docs/how-it-works.md#driving-it-from-adb) has the details.

FoldFix is open source. You can read the code and [build it yourself](#build-from-source).

## Limitations

- **Tested on one phone only,** and since version 1.0.1 only with simulated folding. See [Tested on](#tested-on).
- **Restart with the phone closed.** On some phones, the outer screen is only detected when the phone starts up closed. FoldFix can't change that. So always restart such a phone closed, from the outer screen's power menu. Phones with only a dead hinge sensor may not have this problem.
- **No half-open postures.** Tabletop mode and apps that adapt to a half-folded phone need the hinge angle.
- **No fold/unfold animation.** It's driven by the hinge angle too.
- **Landscape on the outer screen isn't seamless.** When an app first turns sideways (for example a video going fullscreen while you already hold the phone sideways), the picture can show upside down for about half a second. An app that goes back to portrait while you hold the phone sideways stays in landscape until you turn the phone upright.
- **Pixel Camera needs a moment after unfolding.** If Camera is on screen when you open the phone, the inner screen first shows a "Front screen turned on" prompt, then goes black for about 1.5 seconds, and then shows Camera, about 3 seconds after opening. Now and then Camera stays on the outer screen instead. Then close the phone and open it once more. Camera itself also crashed twice during the tests. If it closes, open it again.
- **Pixel Camera's Rear Camera Selfie mode works on the open phone only.** That mode is for taking a picture of yourself with the rear cameras: phone open and turned around, outer screen as the viewfinder. What you can rely on:
  - On the open phone you can choose the mode in Camera, and FoldFix leaves it alone. Rarely the mode ends right after you chose it: when you choose it within 3 seconds of unfolding, or after a switch to the outer screen that FoldFix began and couldn't finish. A short black screen may show as it ends. Choose it again.
  - If you close the phone while the mode is on, FoldFix ends it at the next opening, and Camera comes back on the inner screen.
  - On the closed phone, Camera behaves as if the mode were on: rear cameras, which then point away from you, and the outer screen as the viewfinder. Its selfie button shows as switched on and does nothing useful there. A tap makes the outer screen go dark for a second or two, and it comes back as before. Sometimes the folded inner screen is then lit whenever the outer one is, until you next open the phone. The button never switches to a front camera.
  - To leave the mode on the open phone, see [Troubleshooting](#troubleshooting): Camera's own controls may not do it.
- **The front cameras may be gone, whichever screen is on.** On the test phone, Android lists a single, back-facing camera, whether the phone is open or closed ("open" was simulated: the phone itself stayed shut) and whether FoldFix has switched to the outer screen or not. The fault is older than FoldFix: the front camera stopped working long before FoldFix was installed. The likely cause is the same damage that silenced the hinge sensor. No app can bring back a camera that Android doesn't list. So on such a phone there are no selfies with a front camera, no front camera in video calls, and face unlock, which needs a front camera, can't be expected to work (not tried: no face is set up on that phone). This is one phone. A phone whose only fault is the hinge sensor may still have its front cameras; how they behave with FoldFix hasn't been tested. [Troubleshooting](#troubleshooting) has a check for your phone.
- **Unofficial interfaces.** FoldFix relies on private details of one of Android's system libraries and on the layout of the "Switch screens?" dialog. An Android update could break it.
- **It's a workaround.** The real fix is a hardware repair of the hinge flex cable or sensor.

## Tested on

| Device | Android | Build | Result |
| --- | --- | --- | --- |
| Pixel Fold (gen 1, `felix`) | 17 | CP3A.261005.002 (October 2026 patch) | Works, no adb setup needed |

That is one phone. Everything added since version 1.0.1 was tested on it while it lay shut and locked. Folding and unfolding were simulated from a computer (FoldFix was told "open" or "closed" in place of its sensor), and Pixel Camera was mostly the one that starts from the lock screen. So none of the following has been tried on purpose by folding the phone by hand, and the timings given for them may differ on a phone in use:

- ending an outer-screen request that another app left behind,
- bringing Pixel Camera back to the inner screen after unfolding, and Camera's selfie button on the closed phone,
- standing back while Android itself has the phone in its closed state. The test phone's hinge sensor never reports that state, so it was forced from a computer,
- the **Switch to the inner screen** button and the **Recent activity** history. The status screen can't be opened on a locked phone, so both were tried with a test build that shows it over the lock screen.

The app installs on Android 13 and newer. Rear-display mode and its "Switch screens?" dialog are expected on recent Android versions, but only Android 17 has been tested.

It might also help **other foldables** with the same failure (dead hinge-angle sensor, working hall sensor), if they offer a rear-display mode. That's untested. If you try, please [report back](#contributing), whether it works or not.

## Check your phone

### With the FoldFix app

Install the app ([step 1](#1-install-the-app) below) and open **FoldFix**. Its status screen has these sections:

| Section | Affected phone | What else you might see |
| --- | --- | --- |
| **Accessibility service** | `On.` plus what FoldFix is doing, once set up | `Off: turn on FoldFix in Accessibility settings`: see [setup](#install-and-set-up). A **Switch to the inner screen** button, only while the outer screen is on although the phone is open: see [Troubleshooting](#troubleshooting) |
| **Hall sensor (closed / open)** | The sensor's name, then `closed` or `open`, changing as you fold | `Not found: this phone is not supported` |
| **Hinge angle sensor** | `No readings: the sensor is likely broken` (after listening for 3 seconds) | An angle such as `178°`: the sensor works, so you may not need FoldFix |
| **Outer screen** | `Detected` | `Not detected`: restart the phone while it's closed |
| **Rear-display access** | `OK, no setup needed` | `Needs a one-time adb command`: see [Troubleshooting](#status-screen-asks-for-an-adb-command) |
| **Device state** | `2 OPENED (rear display off)` even while the phone is folded, until FoldFix is set up. After that, `3 REAR_DISPLAY_STATE (rear display on)` while it is folded | |
| **Version** | The installed version and who signed it, such as `1.0.2 (3), official release` | `self-built (signed with another key)` for any build that isn't one of this project's releases, such as your own |
| **Recent activity** | What FoldFix did, one line per step with its time, newest last, such as `11-11 02:38:44.184 I hall open`. The **Copy** button copies the **Version** line and all of it, for a [bug report](#contributing) | `(400 earlier lines not shown)` at the top: the screen shows the last 100 lines, **Copy** takes all of them (up to 500) |

Everything except the **Accessibility service** section works right after installing.

### With a computer (adb)

```sh
adb shell "dumpsys sensorservice | grep -E '(Hinge Angle|Hall Effect).*: last'"
adb shell "logcat -d -s FoldableDeviceStateProvider | grep -m 3 'Have not received sensor event'"
```

It's this failure if the first command prints a `DRV5032 Hall Effect Sensor (wake-up): last N events` line but **no** `Hinge Angle (wake-up): last N events` line, and the second prints lines with `Have not received sensor event`.

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

On Android 13 and newer, Android can block apps that were installed from outside the Play Store from turning on an accessibility service until you allow it. This may not be needed on every setup.

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
- Pixel Camera and landscape (fullscreen video, auto-rotate) work on the outer screen, each with quirks. [Limitations](#limitations) says what to expect.
- After an Android update, open FoldFix once and check that everything is still OK.

## Troubleshooting

**The outer screen never turns on.**
The outer screen probably wasn't detected at startup. FoldFix's **Outer screen** section will say `Not detected`. Power off, fold the phone shut, and power on while it's closed. When the outer screen is missing, FoldFix deliberately does nothing, because switching to a screen that isn't there can crash Android.

**Both screens are black.**
Press the power button, or open and close the phone once. Every fold or unfold makes FoldFix try again straight away. On its own, it tries again about every 2 seconds, and about every half minute after 5 tries that didn't help, so it may also recover by itself. If it doesn't, fold the phone shut and force-restart it: hold the power button until the "G" logo appears, which can take up to a minute. Then please [open an issue](../../issues).

**The wrong screen is on.**
Fold and unfold once. Then open FoldFix and check:

- **Accessibility service** says `On`. If it says `Off`, turn it on again ([step 3](#3-turn-on-the-foldfix-service)).
- **Hall sensor** shows the right `closed` / `open` state.
- **Rear-display access** says `OK`.

If it says `Open: another app is using the outer screen`, an app such as Pixel Camera asked for the outer screen while the phone was open. FoldFix leaves that alone. Switch back in that app, or tap **Switch to the inner screen**, right below that line. The button is shown while the outer screen is on although the phone is open. It ends the outer-screen request, whichever app made it, and FoldFix does nothing more after that: an app that asks for the outer screen again gets it. Closing and opening the phone once also works.

**Pixel Camera stays on the outer screen.**
This can happen after you open the phone with Camera on screen, or when you want to leave Camera's Rear Camera Selfie mode. Close the phone and open it again. Or leave Camera, and then use the "Slide to use inner screen" control on the inner screen. On the test phone, switching the mode off while Camera was still on screen didn't help, neither with that control nor with Camera's own selfie button: Camera asked for the outer screen again within a second.

If Camera stays on the outer screen after you open the phone, although it should follow to the inner one, please [open an issue](../../issues) with FoldFix's recent activity (see [Contributing](#contributing)). The lines containing `another app turned rear display on` say what happened.

**There's no front (selfie) camera.**
Your phone may have lost its front cameras, as the test phone has: see [Limitations](#limitations). Nothing FoldFix does brings a camera back. What it looks like: Pixel Camera shows no button to switch cameras on the closed phone. On the open phone the button is there, and tapping it brings up Camera's own "Something went wrong" message about the camera's hardware or software. To see what your phone has, from a computer:

```sh
adb shell "dumpsys media.camera | grep -E 'Number of camera devices|Facing:|Front(Inner|Outer)'"
```

A phone with this fault prints `Number of camera devices: 1`, a single `Facing: Back`, and a `FrontOuter` line with `status: Not present`. (The `FrontInner` line can say `Present` although Android doesn't offer that camera either.) For a picture of yourself, open the phone, turn it around and use Pixel Camera's Rear Camera Selfie mode: it shows the picture of the rear cameras on the outer screen.

**It says "Rear-display mode is unavailable right now".**
Android sometimes refuses rear-display mode, possibly in battery saver or when the phone is hot. FoldFix checks again every 5 seconds and switches as soon as Android allows it.

**You can see a "Switch screens?" dialog.**
FoldFix answers this dialog on the folded inner screen, normally before anyone could see it. One exception: after a tap on Pixel Camera's selfie button on the closed phone, it can flash up on the outer screen, usually for under a second, before FoldFix confirms it. If it shows up on the screen you're looking at and stays, choose an option yourself and [open an issue](../../issues), mentioning your Android version. An update may have changed the dialog.

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

## How it works

In short: an accessibility service watches the hall sensor. When the phone closes, it briefly opens an invisible screen of its own that asks Android for rear-display mode (outer screen on, inner off), using one of Android's own system libraries. It then taps the confirmation dialog that appears on the folded, unseen inner screen. When the phone opens, it ends the request, and once more if the app on screen (Pixel Camera) asks for the outer screen again straight away. It keeps comparing the sensor with the screen that is on, so a step that was missed during fast folding and unfolding is normally corrected at the next check.

[docs/how-it-works.md](docs/how-it-works.md) covers the details and the measurements: device states, the two ways of making the request, the dialog, the invisible screen (the "trampoline"), why requests can outlive the app, what happens with Pixel Camera and the cameras, the guard against a missing outer screen, and how FoldFix is tested.

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

Android only installs an update over an existing app if both are signed with the same key. If you switch between your own build and a release, uninstall first; you'll need to turn the accessibility service on again afterwards. The status screen's **Version** section says which of the two is installed.

### Tests

```sh
./test.sh
```

This needs only the JDK: no Android SDK, no phone, and no test library. It compiles the tests in `tests/` together with the classes that hold FoldFix's rules, runs them on your computer, prints each case that failed with what was expected and what came instead, and exits with an error if any did.

The tests check what FoldFix decides: when it requests, confirms, cancels, takes over a request or leaves one alone, and when it tries again. They don't check what Android does with those decisions. Sensors, the dialog, the device-state calls and the screens themselves can only be tested on a phone (see [Testing and logs](docs/how-it-works.md#testing-and-logs)). Nothing in `tests/` ends up in the APK.

### Releases

GitHub Actions ([`.github/workflows/build.yml`](.github/workflows/build.yml)) has two jobs. Both run `./test.sh` first and stop if a test fails:

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
- what FoldFix did: on the status screen, scroll down to **Recent activity** and tap **Copy**, then paste. Do it soon after the problem showed, or after folding and unfolding a few times. It copies the **Version** line, which says exactly which build is installed (for example `1.0.2 (3), official release`), and FoldFix's own history, the last 500 lines with their times. No computer is needed,
- the output of the checks in [docs/diagnosis.md](docs/diagnosis.md), if you have a computer.

The history is kept in memory only and starts again when FoldFix restarts (after an update, or a restart of the phone), so copy it before restarting. From a computer, `adb shell dumpsys activity service app.foldfix/.FoldService` prints the same history while the service is on, with a line saying what FoldFix is doing. `adb logcat -s FoldFix` has the same lines too, but Android's log only keeps them for a few minutes at most.

The copied history holds only FoldFix's own lines: device states, timings, and the names and messages of errors. That includes the times at which the phone was opened and closed. Other logs and system reports, such as a full `adb logcat`, can contain personal information. Trim them to the relevant lines before posting.

Bug fixes and pull requests are welcome. FoldFix should stay small: no network access, no data collection, and no new permissions without discussion first.

## License

[MIT](LICENSE)

## Disclaimer

FoldFix is not affiliated with, endorsed by, or supported by Google. "Pixel" and "Pixel Fold" are trademarks of Google LLC.

FoldFix changes how your phone picks a screen, using unofficial Android interfaces. Use it at your own risk; the software comes with no warranty (see the [license](LICENSE)). If something goes wrong, turn FoldFix off (see [Troubleshooting](#troubleshooting)) and restart the phone: that hands control back to Android. A restart alone doesn't, because FoldFix starts again with the phone.

# How FoldFix works

This is the technical background: why the phone gets stuck and what FoldFix does about it. For setup, see the [README](../README.md). For checking a phone, see [Diagnosis](diagnosis.md).

## The short version

1. The hall sensor reports **closed**. FoldFix asks Android for rear-display mode, which turns the outer screen on and the inner screen off.
2. The hall sensor reports **open**. FoldFix ends that request, and Android falls back to OPENED, so the inner screen is used again.

Everything else exists to make those two steps reliable:

- getting the request accepted without special permissions,
- answering the confirmation dialog nobody can see,
- recovering from fast fold/unfold sequences,
- never leaving a request behind, and
- keeping landscape the right way up, which rear-display mode gets wrong on a closed phone.

The code is in `app/src/app/foldfix/`:

| File | Role |
| --- | --- |
| `FoldService.java` | The accessibility service: hall sensor, reconcile loop, dialog tapping |
| `RearDisplay.java` | Starts and ends rear-display mode (two backends), tracks whether the request is FoldFix's |
| `RotationFix.java` | Corrects landscape on the outer screen while the phone is closed |
| `Trampoline.java` | Invisible activity that makes the request or cancel as the top app |
| `Hardware.java` | Finds the hall sensor; checks whether the outer display exists |
| `MainActivity.java` | The status and setup screen |

## Why the phone gets stuck

### Device states

Android's device-state manager (in `system_server`) keeps track of the phone's posture as a numbered **device state**. The display system maps each state to a screen layout: which panels are on, and which one is the default display.

On the Pixel Fold, `adb shell cmd device_state print-states` lists:

| ID | Name | Meaning | Notes |
| --- | --- | --- | --- |
| 0 | `CLOSED` | Folded. Outer screen on. | Normally chosen from the hinge angle. Apps can't request it. |
| 1 | `HALF_OPENED` | Partly open (tabletop / book posture). | Needs the hinge angle. Unavailable on affected phones. |
| 2 | `OPENED` | Unfolded. Inner screen on. | Where affected phones get stuck. |
| 3 | `REAR_DISPLAY_STATE` | Classic rear-display mode: outer screen on, inner screen off. | **FoldFix uses this.** Also used by Pixel Camera's rear-camera selfie mode. |
| 4 | `CONCURRENT_INNER_DEFAULT` | Both screens on. | Dual-screen features. Ends when the requesting app leaves the foreground. |
| 5 | `REAR_DISPLAY_OUTER_DEFAULT` | Newer rear-display mode ("v2"). The outer screen becomes the default, but the inner screen stays on with a "Front screen turned on / Slide to use inner screen" prompt. | Wrong for a closed phone. See [below](#window-extensions-the-primary-path). |

There are two layers:

- The **base state** comes from the hardware, via a device-state provider.
- The **committed state** is the base state, unless an **override** is active. Apps can request an override (see [Making the request](#2-making-the-request)). The shell can also set one with `cmd device_state state <id>`, or replace the base state itself with `cmd device_state base-state <id>`.

`print-states` shows `app_accessible=true` for every state. That only means apps can see the state. Which states an app may *request* is a separate, device-specific allow-list. Anything outside it needs a system-only permission (`CONTROL_DEVICE_STATE`).

### The failing sensor

The base state on the Pixel Fold comes from `FoldableDeviceStateProvider`. It evaluates a condition for each state, and the CLOSED and HALF_OPENED conditions depend on the hinge angle. When the hinge-angle sensor never sends an event, reading the angle throws, and the provider logs:

```text
W FoldableDeviceStateProvider: Unable to check current state = 0
W FoldableDeviceStateProvider: java.lang.IllegalStateException: Have not received sensor event.
I FoldableDeviceStateProvider: Hinge Angle (wake-up) : null
```

It never gets an answer, so the base state stays at OPENED. The inner screen remains the main screen whether the phone is open or closed.

The hall-effect sensor keeps working. On the Pixel Fold it is `DRV5032 Hall Effect Sensor (wake-up)`, vendor type `com.google.sensor.hall_effect`, type number 65547. It's a magnetic switch that fires when the two halves come together. It can't measure angles, but closed vs. not closed is exactly what's needed to pick a screen. That's also why some hardware diagnostics still show fold detection working: they read the switch directly instead of going through Android's posture logic.

### The outer screen and booting closed

On some affected phones, the outer panel is only detected when the phone **boots closed**. When it boots open, the kernel command line from the bootloader names only the inner panel:

```text
exynos_drm.panel_name=dsim0:samsung-ana6707-f10
```

When it boots closed, it also names the outer one:

```text
exynos_drm.panel_name=dsim0:samsung-ana6707-f10
exynos_drm.sec_panel_name=dsim1:ea8182-f10
```

Without the second entry, Android has a single display. The outer screen stays dark until the next reboot, and switching to it isn't possible. The outer screen and the sensors sit in the folding half, so both faults probably come from the same damaged hinge connection. Phones whose only fault is a dead hinge sensor may not have this boot-time problem.

## What FoldFix does

### 1. An accessibility service

FoldFix's service (`app.foldfix.FoldService`) is an accessibility service because that's the only kind of normal app component that has everything needed:

- It starts automatically at boot, including before the first unlock (direct-boot aware), and the system keeps it running.
- It's allowed to start activities from the background, but only while it's bound. Android exempts bound accessibility services from its background-activity-launch restrictions. Once the user turns the service off, the exemption is gone and its launches are blocked (`BAL_BLOCK`). See [Turning FoldFix off](#turning-foldfix-off).
- It can see and tap SystemUI's confirmation dialog.

Its accessibility configuration limits events to `com.android.systemui`. When looking for the dialog, the service only opens windows of type `TYPE_SYSTEM`, and only searches those whose package is `com.android.systemui`, by view ID. It never opens other apps' windows and doesn't read or keep any screen text. The app requests no Android permissions.

The service looks for the hall sensor by its type number (65547), or by a sensor type name containing "hall", and prefers the wake-up variant. Events from it arrive even while the phone is asleep. A first value of at least `0.5` means closed (it reports `1.0` closed and `0.0` open).

### 2. Making the request

Requests go through one of two backends, tried in this order.

#### Window extensions (the primary path)

Jetpack WindowManager's public rear-display API (`WindowAreaController`) is backed by a library on the device, `androidx.window.extensions`. FoldFix declares it as an optional shared library:

```xml
<uses-library android:name="androidx.window.extensions" android:required="false" />
```

Its code is loaded from the system partition, so it runs in the platform's hidden-API domain. It may call the hidden `DeviceStateManager` on FoldFix's behalf, and **no exemption or adb setup is needed**. FoldFix calls it by reflection:

```text
WindowExtensionsProvider.getWindowExtensions().getWindowAreaComponent()
    .startRearDisplaySession(activity, consumer)   // on close
    .endRearDisplaySession()                       // on open
    .addRearDisplayStatusListener(consumer)        // is rear-display mode active / available?
```

Two details matter:

- **State 3, not state 5.** Since Android 16, the library requests `REAR_DISPLAY_OUTER_DEFAULT` (5) by default. That shows no "Switch screens?" dialog. Instead it keeps the inner screen on with a "Slide to use inner screen" prompt, which is wrong for a closed phone. When it loads the library, FoldFix points it back at the classic rear-display state (3). It sets a private field of the library, using the library's own lookup for the classic state's ID. If a future update changes those internals, FoldFix logs a warning and the request still works, just in state 5.
- **The first status callback is wrong.** Right after it's created, the library reports rear-display mode as "available" before it knows the device state. FoldFix registers its status listener slightly later, so the first status it gets is the correct one. If the listener can't be registered at all, FoldFix switches to the fallback below.

Without the hidden-API exemption, Android's log shows FoldFix's own direct calls to the hidden API being denied (`blocked,test-api`), while requests through the library go through. A full switch (request → dialog confirmed → `REAR_DISPLAY_STATE`) takes about 0.25 to 0.5 seconds.

#### The hidden `DeviceStateManager` API (fallback)

If the library is missing or unusable, FoldFix calls `android.hardware.devicestate.DeviceStateManager` directly: `requestState()` with a `DeviceStateRequest` for state 3, and `cancelStateRequest()`. It also registers a device-state callback to track the committed state.

These are hidden (`@hide`) classes. Since Android 9, apps are blocked from calling non-SDK interfaces unless they're exempted, so this path needs a one-time command from a computer:

```sh
adb shell settings put global hidden_api_blacklist_exemptions Landroid/hardware/devicestate/
```

- The value is a list of method-signature prefixes (comma-separated). `Landroid/hardware/devicestate/` covers only the classes in the `android.hardware.devicestate` package.
- It's a global setting, so it applies to **every** app on the phone, but only for that package.
- Writing it requires the `WRITE_SECURE_SETTINGS` permission, which the adb shell has and normal apps don't.
- It persists across reboots. FoldFix checks it once when its process starts. To remove it:

  ```sh
  adb shell settings delete global hidden_api_blacklist_exemptions
  ```

The status screen shows this command (with a **Copy adb command** button) only when neither backend works.

### 3. The trampoline activity

The device-state manager only accepts an app's request from the app that is **on top and in the foreground**. A background service doesn't qualify. Cancels have a similar rule: they're accepted from the top app, or from a high-priority process that owns the request.

So for both requests and cancels, the service starts a tiny invisible activity, `Trampoline`. It's transparent, has no animation, may show over the lock screen, makes the call, and finishes straight away.

Other apps can't start it. It's protected by `android:permission="android.permission.DUMP"`, which only the system and the adb shell hold; FoldFix can always start its own activity.

A launch can be delayed, for example while the screen is off. If the phone has been opened by the time the trampoline runs, it drops the now-stale request instead of making it.

Being on top isn't always enough: a request made right after another switch can be refused because FoldFix isn't counted as a foreground app yet. The reconcile loop simply retries.

### 4. The "Switch screens?" dialog

Every app request for classic rear-display mode makes SystemUI ask the user to confirm ("Switch screens?"). There is no "don't ask again" option.

When the phone is closed, the request arrives while the inner screen is still the active one, so the dialog is drawn on the **inner screen, which is folded shut**. FoldFix finds it in SystemUI's windows and taps the confirm button, usually within about 0.2 seconds. Nobody sees it.

It identifies everything by view ID, not by text, so it works in any language:

| What | View ID |
| --- | --- |
| Dialog (title) | `com.android.systemui:id/rear_display_title_text_view` |
| Confirm button | `android:id/button1` |
| Cancel button | `android:id/button2` |

Ending an active request (on open) shows no dialog.

A request that's still waiting for its dialog can only be resolved by the dialog's buttons; cancelling it through the API does nothing. So if the phone is opened before the dialog was confirmed, FoldFix taps **Cancel** in the dialog. It also cancels any such dialog that appears within 3 seconds of its last request, even if it already considers that request dropped. Confirming it at that point would leave an open phone stuck on the outer screen.

Apart from that, FoldFix only taps a dialog that belongs to its own request.

### 5. The reconcile loop

FoldFix doesn't treat each sensor event as a one-off command. It keeps comparing what the hall sensor says (closed or open) with whether rear-display mode is active.

| Hall sensor | Rear-display mode | What FoldFix does |
| --- | --- | --- |
| Closed | Active (anyone's request) | Nothing |
| Closed | Not active, FoldFix's request waiting for its dialog | Confirm the dialog once it shows |
| Closed | Not active, no request of FoldFix's in flight | Request |
| Open | Active with FoldFix's request | End the request |
| Open | FoldFix's request waiting for its dialog | Tap **Cancel** in the dialog when it shows (the only way to drop it) |
| Open | Another app's request, or none | Nothing (but still cancels a dialog appearing within 3 s of its last request) |

Timing:

- While a launch is recent (within 3 seconds), it checks every **200 ms**. Accessibility events from System UI also wake it up immediately when a dialog appears.
- Requests and cancels share one retry schedule: every **2 seconds**, then every **30 seconds** after 5 tries that didn't help. Each fold or unfold resets it.
- Nothing is resent while FoldFix's own request is still in flight. A request waiting for its dialog is given up to **10 seconds**; after that it's treated as lost, ended and replaced.
- It does nothing until it knows both the hall reading and the device state. Acting before the state is known can put a dialog on the outer screen.
- If neither backend is usable, it stops and the status shows the adb hint. That can only change after a restart.

Reacting to single sensor events isn't enough: a fast close, open, close while a dialog is pending can leave both screens black. With the reconcile loop, any missed or out-of-order step gets corrected on the next check.

**No second request.** If rear-display mode is already active, or FoldFix's own request is still pending, FoldFix doesn't request again. A second request would put a visible "Switch screens?" dialog on the outer screen.

### 6. Whose request is it?

Some facts about requests that shaped the design:

- **A cancel from the top app ends whatever request is active, whoever made it.** The system doesn't check ownership. So "only cancel our own" is enforced in FoldFix's own logic: it tracks whether the active or pending request is its own, and never cancels otherwise. Other apps' rear-display features, such as Pixel Camera's rear-camera selfie mode, keep working.
- **A waiting request can't be cancelled through the API.** Until its dialog is answered, only the dialog's buttons resolve it (see [the dialog](#4-the-switch-screens-dialog)).
- **An active request outlives the process that made it.** The system keeps an active state-3 request until the device reaches CLOSED, which a dead hinge sensor never reports. Only *pending* requests (not yet confirmed) die with their process.

Because of the last point, FoldFix saves "our request may be active" in device-protected storage, which is readable before the first unlock. It clears the flag once it sees rear-display mode turn off. Without this, updating or restarting FoldFix while the phone is closed would leave nobody responsible for the request, and the phone would stay on the outer screen after unfolding. A reboot clears any request.

#### Turning FoldFix off

Turning the service off can't release its request. Once unbound, the service is no longer allowed to start the trampoline (`BAL_BLOCK`), and only the top app can cancel. FoldFix's status screen covers this case. If it's open while the service is off, the phone is open and FoldFix's request is still active, the screen cancels the request itself (it's the top app) and says so. Turning FoldFix back on, or rebooting, also releases it. That's why the README advises turning FoldFix off or uninstalling it with the phone open.

### 7. The outer-screen guard

FoldFix never requests rear-display mode unless Android detected the outer display. Otherwise it does nothing, and the status screen says to restart with the phone closed.

The reason: moving the default display to a panel that doesn't exist can take down `system_server`. Forcing CLOSED in that situation crashed it with:

```text
Can't remove the primary display
```

and the phone soft-rebooted.

How the check works:

- It counts built-in displays, including ones that are currently off. On Android 17 (API 37) and newer it uses the public `DisplayManager.DISPLAY_CATEGORY_BUILT_IN_DISPLAYS` category. On older versions it uses the hidden category string `android.hardware.display.category.ALL_INCLUDING_DISABLED`. Both return 2 displays when the outer panel was detected.
- It runs when the service starts, before every request, and again in the trampoline right before the call, so no caller can trigger a request without it.
- A positive result is kept for the life of the process. The panel can't disappear without a reboot, but the display list is briefly incomplete during every screen switch.

### 8. Landscape on the outer screen

Rear-display mode is meant for an open phone turned around, so that the outer screen faces someone else. In that posture the orientation sensor's landscape directions come out mirrored, so Android turns them around in rear-display states. It lists those states in a framework setting, which is `[3, 5]` on the Pixel Fold:

```text
config_deviceStatesToReverseDefaultDisplayRotationAroundZAxis
```

On a closed phone the outer screen faces the user the usual way, so the reversal is wrong: anything the sensor rotates lands upside down. That covers fullscreen video (`SCREEN_ORIENTATION_SENSOR_LANDSCAPE`), auto-rotate, and the rotate button. Turning the phone around just flips it to the other wrong side. Portrait isn't affected, because the reversal only swaps rotations 90 and 270.

Apps can't request CLOSED (`SecurityException`, it isn't available for app requests), so FoldFix corrects the rotation instead. While the phone is closed in rear-display mode, `RotationFix` keeps an invisible accessibility overlay attached: zero size, not focusable, not touchable. No permission is needed. A window above the apps that sets `screenOrientation` decides the orientation for the whole display. So:

- While the phone is held sideways and the display is already in landscape, the overlay asks for the landscape the phone is really in (`LANDSCAPE` for rotation 90, `REVERSE_LANDSCAPE` for 270). The value comes from the device orientation sensor (type 27), the one Android's auto-rotate uses.
- Otherwise it asks for nothing (`UNSPECIFIED`), and the app decides as usual. It never turns an app that is in portrait, and it steps aside for the lock screen and while the screen is off.

It only runs if the framework setting lists a rear-display state, so it can't introduce a flip on a system that doesn't reverse.

The overlay stays attached and only its orientation changes. That way a correction is a single layout update, which takes effect within a few tens of milliseconds. Adding a window at that moment took about half a second.

What it can't fix, because other apps' orientation requests aren't visible to FoldFix:

- When an app turns to landscape while the phone is already sideways, Android picks the wrong side first. The correction follows once that turn finishes, about half a second later.
- While the overlay holds landscape, an app that goes back to portrait (leaving fullscreen) stays in landscape until the phone is turned upright.

## Testing and logs

FoldFix logs what it does under the tag `FoldFix`:

```sh
adb logcat -s FoldFix
```

The trampoline can be driven from adb for testing. The adb shell holds the `DUMP` permission that protects it; other apps don't.

```sh
adb shell am start -n app.foldfix/.Trampoline --es action request
adb shell am start -n app.foldfix/.Trampoline --es action cancel
```

The outer-screen guard still applies, and the running service undoes any request of its own that doesn't match the fold position.

## What FoldFix can't do

- **Half-open postures** (tabletop mode and apps that adapt to a half-folded phone) need the hinge angle. The hall sensor only knows closed or not closed.
- **The fold/unfold animation** is driven by the hinge angle, so it doesn't play.
- **Detecting the outer screen** happens in the bootloader, before any app runs. If the phone booted open and the outer panel wasn't found, only a reboot with the phone closed brings it back.

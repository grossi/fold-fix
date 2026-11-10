# How FoldFix works

This is the technical background: why the phone gets stuck and what FoldFix does about it. For setup, see the [README](../README.md). For checking a phone, see [Diagnosis](diagnosis.md).

## The short version

1. The hall sensor reports **closed**. FoldFix asks Android for rear-display mode, which turns the outer screen on and the inner screen off.
2. The hall sensor reports **open**. FoldFix ends that request, and Android falls back to OPENED, so the inner screen is used again.

Everything else exists to make those two steps reliable:

- getting the request accepted without special permissions,
- answering the confirmation dialog nobody can see,
- recovering from fast fold/unfold sequences,
- never leaving a request behind,
- getting Pixel Camera back to the inner screen when the phone is opened with Camera on screen, and
- keeping landscape the right way up, which rear-display mode gets wrong on a closed phone.

What it means for the cameras is in [Cameras on the closed phone](#9-cameras-on-the-closed-phone).

The code is in `app/src/app/foldfix/`:

| File | Role |
| --- | --- |
| `FoldService.java` | The accessibility service: hall sensor, reconcile loop, dialog tapping |
| `Reconciler.java` | The rules of the reconcile loop: what to do for a hall reading and a device state, and when to look again |
| `RearDisplay.java` | Starts and ends rear-display mode (two backends), reports whether it is active |
| `Ownership.java` | RearDisplay's bookkeeping: whether the request is FoldFix's to end, and which cancel ended rear-display mode |
| `RotationFix.java` | Corrects landscape on the outer screen while the phone is closed |
| `Trampoline.java` | Invisible activity that makes the request or cancel as the top app |
| `Curtain.java` | Black screen that ends Pixel Camera's repeat request on open and covers Camera for a moment |
| `Stale.java` | The check the trampoline and the curtain make again when they run |
| `Hardware.java` | Finds the hall sensor; checks whether the outer screen was detected |
| `FoldLog.java` | The one way FoldFix logs: to Android's log, and into a short history kept in memory |
| `App.java` | Marks the start of the process in that history; knows which build is installed |
| `HallOverride.java` | Test hook: lets adb simulate folding and unfolding |
| `MainActivity.java` | The status and setup screen |

`Reconciler`, `Ownership` and `Stale` hold decisions and nothing of Android's, so they can be [tested without a phone](#the-tests).

## Why the phone gets stuck

### Device states

Android's device-state manager (in `system_server`) keeps track of the phone's posture as a numbered **device state**. The display system maps each state to a screen layout: which panels are on, and which one is the default display.

On the Pixel Fold, `adb shell cmd device_state print-states` lists:

| ID | Name | Meaning | Notes |
| --- | --- | --- | --- |
| 0 | `CLOSED` | Folded. Outer screen on. | Normally chosen from the hinge angle. Apps can't request it. |
| 1 | `HALF_OPENED` | Partly open (tabletop / book posture). | Needs the hinge angle. Unavailable on affected phones. |
| 2 | `OPENED` | Unfolded. Inner screen on. | Where affected phones get stuck. |
| 3 | `REAR_DISPLAY_STATE` | Classic rear-display mode: outer screen on, inner screen off. | **FoldFix uses this.** An app's request for it has to be confirmed in a "Switch screens?" dialog. |
| 4 | `CONCURRENT_INNER_DEFAULT` | Both screens on. | Dual-screen features. Ends when the requesting app leaves the foreground. |
| 5 | `REAR_DISPLAY_OUTER_DEFAULT` | Newer rear-display mode ("v2"). The outer screen becomes the default, but the inner screen stays on with a "Front screen turned on / Slide to use inner screen" prompt. | Needs no dialog. Pixel Camera's Rear Camera Selfie mode asked for this state every time it was observed. Wrong for a closed phone. See [below](#window-extensions-the-primary-path). |

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
- It can see and tap System UI's confirmation dialog.

Its accessibility configuration limits events to `com.android.systemui`. When looking for the dialog, the service only opens windows of type `TYPE_SYSTEM`, and only searches those whose package is `com.android.systemui`, by view ID. Going by Android's source, `TYPE_SYSTEM` also covers the overlay windows that other apps draw on top of the screen (this wasn't checked on the phone: none was up). The service then opens such a window as well, reads which package it belongs to, and leaves it. It never opens an app's ordinary windows and doesn't read or keep any screen text. The app requests no Android permissions.

The service looks for the hall sensor by its type number (65547), or by a sensor type name containing "hall", and prefers the wake-up variant. Events from it arrive even while the phone is asleep. A first value of at least `0.5` means closed (it reports `1.0` closed and `0.0` open).

#### Drawn in software

FoldFix is one process. Besides the service it holds everything FoldFix puts on screen: the status screen, the [trampoline](#3-the-trampoline-activity), the [curtain](#pixel-camera-on-screen-when-the-phone-is-opened) and the [landscape fix](#8-landscape-on-the-outer-screen)'s overlay. They share state that exists once per process (whose request is active, the last hall reading), so they can't be moved to processes of their own.

All of it is drawn in software: the manifest sets `android:hardwareAccelerated="false"` for the whole app, which also covers the overlay, a window the service adds itself. The process then has no render thread and makes no use of the GPU.

Two things in Android ignore that setting and bring a hardware renderer of their own into the app's process, so the status screen keeps clear of both:

- The magnifier that shows while text is being selected. The status screen has no selectable text; a long press on the Version line, on the adb command or on the recent activity copies it instead.
- Long screenshots ("Capture more"), which Android renders from the app's scrolling view. The status screen's scrolling view is excluded from them, so only a normal screenshot is offered.

The reason is what a GPU fault does to an app. Android's hardware renderer runs inside the app's process. When the GPU driver reports its device as lost, the renderer aborts the process, and while the GPU doesn't answer, the main thread waits for the render thread. On a Pixel Fold on Android 17 that happened three times during tests, each time with the status screen showing over the lock screen (which only test builds do). Two of the three read as follows; the other one, the first of them, named another fault, `CS_INHERIT_FAULT`:

```text
Abort message: 'VK_ERROR_DEVICE_LOST (RenderThread, ...): Exception type 0x10001: GPU fault.'
```

In one of them, the GPU stopped answering when a simulated opening came. The service's main thread waited about 23 seconds for the render thread, until the abort, and the cancel it had just started wasn't carried out in that time. By the crash record it was removing a window, by all signs the landscape overlay: the service takes it away on open. Android restarted the service 2 seconds later. A phone opened by hand would have stayed on the outer screen for all of that.

Why the GPU faulted isn't known, and it couldn't be reproduced on purpose. Older crash records on the same phone show the same abort in two other apps, so it isn't FoldFix's alone. Nothing FoldFix draws needs a GPU (text, a few buttons, a black rectangle, an empty view), so it does without. The cost, all on the status screen apart from the last: buttons have no ripple and no shadow (a pressed button is dimmed instead), scrolling past the end shows no stretch, text can be copied but not selected, there are no long screenshots, and switching to the inner screen took about 10 ms longer in the measurements (8 to 15 ms, depending on the runs compared).

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

Without the hidden-API exemption, Android's log shows FoldFix's own direct calls to the hidden API being denied (`blocked,test-api`), while requests through the library go through. A full switch (request → dialog confirmed → `REAR_DISPLAY_STATE`) takes about 0.2 seconds on a normal close: 0.18 to 0.24 seconds in the runs that were measured.

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

So for both requests and cancels, the service starts a tiny invisible activity, `Trampoline`. (One cancel is made by a second activity, `Curtain`, for the same reason: see [Pixel Camera](#pixel-camera-on-screen-when-the-phone-is-opened).) It's transparent, has no animation, may show over the lock screen, makes the call, and finishes straight away.

Ordinary apps can't start it. It's protected by `android:permission="android.permission.DUMP"`. The adb shell and privileged system apps hold that permission; any other app can only start the trampoline if the permission was granted to it over adb. FoldFix can always start its own activity.

A launch can be delayed, for example while the screen is off, so the trampoline checks again when it runs:

- A **request** is dropped if the phone has been opened by then. Only a definite "open" stops it. With no hall reading at all (the service has stopped, or has only just connected) the request is still made, and if the service has stopped, nobody is left to confirm its dialog. The log says `phone is open, dropping stale request`.
- A **cancel** is dropped if the phone has been closed by then, or if no request is left that is FoldFix's to end (see [Whose request is it?](#6-whose-request-is-it)). The system ends whatever request is active when the cancel arrives. That could be FoldFix's new one on a phone that was closed again, which would turn the outer screen off for a moment, or one another app made in the meantime. The check is the one the service makes before it sends a cancel, so a late cancel does nothing a fresh one wouldn't. One difference: only a definite "closed" stops it. With no hall reading at all (the service has stopped, or has only just connected) it still goes through, because the request is FoldFix's to end either way. The log says `dropping stale cancel`.

Being on top isn't always enough: a request made right after another switch can be refused because FoldFix isn't counted as a foreground app yet. The reconcile loop simply retries.

### 4. The "Switch screens?" dialog

Every app request for classic rear-display mode makes System UI ask the user to confirm ("Switch screens?"). There is no "don't ask again" option.

When the phone is closed, the request arrives while the inner screen is still the active one, so the dialog is drawn on the **inner screen, which is folded shut**. FoldFix finds it in System UI's windows and taps the confirm button, usually within about 0.2 seconds. Nobody sees it.

It identifies everything by view ID, not by text, so it works in any language:

| What | View ID |
| --- | --- |
| Dialog (title) | `com.android.systemui:id/rear_display_title_text_view` |
| Confirm button | `android:id/button1` |
| Cancel button | `android:id/button2` |

Ending an active request (on open) shows no dialog.

A request that's still waiting for its dialog can only be resolved by the dialog's buttons; cancelling it through the API does nothing. So if the phone is opened before the dialog was confirmed, FoldFix taps **Cancel** in the dialog. It also cancels any such dialog that appears within 3 seconds of its last request, even if it already considers that request dropped. Confirming it at that point would leave an open phone stuck on the outer screen.

There is one case where the dialog comes up on the outer screen, in front of the user. Another app's request can become active while FoldFix's own is still waiting for its dialog: Pixel Camera's does, now and then, after its selfie button ended FoldFix's request on the closed phone (see [Cameras on the closed phone](#9-cameras-on-the-closed-phone)). The outer screen is then on through the other request, and FoldFix's dialog shows there. FoldFix confirms it as well, which puts its own request in place of the other one. Left alone, the dialog stayed until someone answered it or the screen turned off.

FoldFix looks for that dialog for the 10 seconds it gives any request of its own (see [the reconcile loop](#5-the-reconcile-loop)), not longer: after that it takes the request for lost, and a dialog still showing up couldn't be told from another app's. No dialog came that late in the tests. It showed within about a second of the other request becoming active each time, and one that went away with the screen turning off didn't come back when the screen was turned on again: its request stayed pending, unseen, until the active one ended. If the phone is opened while FoldFix's request is still waiting like this, both requests go (see [Other apps' leftover requests](#other-apps-leftover-requests)).

FoldFix can't tell whose request a dialog belongs to. So it only looks for the dialog while one of its own may be up: on a closed phone while a request of its own is under way, and on an open phone while a request is FoldFix's to end or its last request is less than 3 seconds old. Another app's "Switch screens?" dialog that came up in one of those moments would get the same answer.

### 5. The reconcile loop

FoldFix doesn't treat each sensor event as a one-off command. It keeps comparing what the hall sensor says (closed or open) with whether rear-display mode is active.

| Hall sensor | Rear-display mode | What FoldFix does |
| --- | --- | --- |
| Closed | Active (anyone's request) | Nothing now. If the request is another app's, FoldFix takes it over, to end it on open (see [Whose request is it?](#6-whose-request-is-it)) |
| Closed | Active through another app's request, FoldFix's own still waiting for its dialog | Confirm the dialog once it shows, so that FoldFix's request replaces the other one |
| Closed | Not active, FoldFix's request waiting for its dialog | Confirm the dialog once it shows |
| Closed | Not active, no request of FoldFix's in flight | Request |
| Closed | Not active, and the outer screen wasn't detected or Android reports rear-display mode as unavailable | Nothing, not even a look for the dialog. Check again in 5 seconds |
| Open | Active with FoldFix's request, or one that was active while the phone was closed | End the request. If a "Switch screens?" dialog is up at that moment, tap **Cancel** in it first and end the request in the next step |
| Open | FoldFix's request waiting for its dialog | Tap **Cancel** in the dialog when it shows (the only way to drop it). A request of another app that was active when the phone was opened is ended as well, and so is one that becomes active later, while FoldFix's is still waiting |
| Open | Another app's request, the first one made within 3 s of FoldFix's cancel | End it too, once per opening, behind a black screen (see [Pixel Camera](#pixel-camera-on-screen-when-the-phone-is-opened)) |
| Open | Any other request another app made while open, or none | Nothing (but still cancels a dialog appearing within 3 s of its last request) |

Timing:

- For 3 seconds after it has started the trampoline, it checks every **200 ms**. Accessibility events from System UI also wake it up immediately when a dialog appears.
- Those 3 seconds count from the last launch since the phone was last closed or opened: every fold or unfold forgets the time of the launch before it. An opening that finds a request active launches the cancel, and so polls. An opening that finds FoldFix's request still waiting for its dialog launches nothing: FoldFix then looks again after 2 seconds, not 200 ms, and relies on System UI's event to find the dialog sooner.
- Requests and cancels share one retry schedule. A launch that didn't help is repeated once **more than 2 seconds** have passed since it. FoldFix is looking every 200 ms at that point, so the launches come **2.2 seconds** apart. After 5 launches that didn't help, the wait is 30 seconds, and the timer for it is only set when the 3 seconds of looking every 200 ms are over: the launches then come **33 seconds** apart. Anything else that wakes the loop (a change of the device state, an event from System UI) can bring a launch sooner, but never less than 2 or 30 seconds after the last one. Each fold or unfold starts the schedule over.
- Nothing is resent while FoldFix's own request is still in flight. A request waiting for its dialog is given up to **10 seconds**; after that, on a closed phone, it's treated as lost, ended and replaced.
- On an open phone, a request of FoldFix's that still waits for its dialog can only be dropped once the dialog shows. From 3 seconds after the request, FoldFix sets no timer for that. The status reads `Open: waiting for the request's dialog to cancel it`, and it stays until something else wakes the loop: the dialog showing, a change of the device state, the trampoline arriving late and dropping its request, or the phone being closed. For as long as it stays, the waiting request counts as FoldFix's to end, and FoldFix can't tell it from the active one. So if the dialog never comes (a request can get lost), a request that another app makes later on the open phone, such as Rear Camera Selfie mode, is ended like one of FoldFix's own, however long after the opening. That cancel also drops FoldFix's claim. What the app requests after it falls under the usual rules for an open phone, the [Pixel Camera](#pixel-camera-on-screen-when-the-phone-is-opened) one included.
- While the outer screen isn't detected, or Android reports rear-display mode as unavailable, a closed phone without rear-display mode is only checked again every **5 seconds**. FoldFix doesn't look for the dialog in that time. A request made just before the mode became unavailable keeps its dialog, on the folded inner screen, until the mode is available again or the phone is opened.
- It does nothing until it knows both the hall reading and the device state. Acting before the state is known can put a dialog on the outer screen.
- If neither backend is usable, it stops and the status shows the adb hint. That can only change after a restart.

Reacting to single sensor events isn't enough: a fast close, open, close while a dialog is pending can leave both screens black. With the reconcile loop, a missed or out-of-order step gets corrected on the next check. (One sequence that used to slip through, involving Pixel Camera, is described [below](#pixel-camera-on-screen-when-the-phone-is-opened).)

**No second request.** If rear-display mode is already active, or FoldFix's own request is still pending, FoldFix doesn't request again. A second request would put a visible "Switch screens?" dialog on the outer screen.

### 6. Whose request is it?

Some facts about requests that shaped the design:

- **A cancel from the top app ends whatever request is active, whoever made it.** The system doesn't check ownership. So which requests get cancelled is decided by FoldFix's own logic: it tracks whether the active or pending request is its own, or one it took over (see [below](#other-apps-leftover-requests)), and never cancels otherwise, with [one exception](#pixel-camera-on-screen-when-the-phone-is-opened). A request another app makes while the phone is open, such as Pixel Camera's Rear Camera Selfie mode, is left alone, unless the user ends it with the [status screen's button](#the-status-screens-button), or a request of FoldFix's own is still waiting for a dialog that never came (see the timing notes under [the reconcile loop](#5-the-reconcile-loop)).
- **A waiting request can't be cancelled through the API.** Until its dialog is answered, only the dialog's buttons resolve it (see [the dialog](#4-the-switch-screens-dialog)).
- **An active request outlives the process that made it.** The system keeps an active rear-display request (state 3 or 5) until the device reaches CLOSED, which a dead hinge sensor never reports. Only *pending* requests (not yet confirmed) die with their process.

Because of the last point, FoldFix saves "a request that FoldFix has to end may be active" in device-protected storage, which is readable before the first unlock. It clears the flag once it sees rear-display mode turn off. Without this, updating or restarting FoldFix while the phone is closed would leave nobody responsible for the request, and the phone would stay on the outer screen after unfolding. A reboot clears any request.

#### Other apps' leftover requests

The last point holds for every app's request, not just FoldFix's. On an affected phone, a request that Pixel Camera made stays active after Camera is left, and even after it is force-stopped. If the phone is closed at that point, FoldFix sees rear-display mode active and has nothing to do. If it only ever ended its own requests, the phone would then stay on the outer screen after every unfolding. With Camera's state-5 request, the inner screen would show System UI's "Front screen turned on / Slide to use inner screen" prompt instead of the user's apps.

So FoldFix follows one rule: **a rear-display request that is active while the phone is closed is FoldFix's to end when the phone opens, whoever made it.** Nobody uses Rear Camera Selfie mode with the phone shut, so a request seen there is either FoldFix's own or a leftover.

- When the hall sensor reads closed and rear-display mode is active through a request that isn't FoldFix's, FoldFix takes the request over ("adopts" it) and logs that. The saved flag covers it, so this survives a FoldFix restart too.
- It waits with that for a moment after a request of its own ended or was cancelled. FoldFix hears of the end just before the system reports rear-display mode as off, and what looks like an active request in between is the one that has just ended. This is what happens at most taps on [Pixel Camera's selfie button](#pixel-cameras-selfie-button) on the closed phone (11 of 12 when counted). The wait is 2 seconds at most. After the end of FoldFix's own request it is over as soon as rear-display mode is next reported, on or off: when a request from the adb shell took the place of FoldFix's own, that report came within milliseconds. After a cancel FoldFix made itself, only a report that the mode is off ends it early.
- On open, it ends an adopted request exactly like its own.
- Rear-display mode that is on at the moment the phone is opened was on while it was closed, so the active request is ended then, also if FoldFix hadn't taken it over yet. That is the case when another app's request became active while FoldFix's own was still waiting for its dialog: FoldFix ends the active one, and taps **Cancel** in its own dialog when that shows. If the dialog is already up when the phone is opened, Cancel is tapped in it first, and the active request is ended in the next step, when no dialog is up. (Before, the other app's request counted as made on an open phone after that tap, and stayed.) The log line is `opened with rear display on before a request of ours was reported active: taking the active one over`. It also appears when the phone is opened in the tenth of a second between FoldFix's own request becoming active and the system saying so; the request is ended either way.
- It ends it once. A new request another app makes while the phone is open is then treated like any request made on an open phone: left alone, apart from the first one made straight away (see [below](#pixel-camera-on-screen-when-the-phone-is-opened)). A request that is left alone is adopted at the next close and ended at the next open.
- A request FoldFix took over, from another app or from an earlier FoldFix process, has no callback of its own. If another request takes its place without rear-display mode going off in between, nothing tells FoldFix, and it ends that one on open as well. On a closed phone that is the rule anyway. On an open phone it would take an app that asks for rear-display mode on top of an active request, in the moment between the unfolding and FoldFix's cancel (normally well under a second; longer if the system refuses the cancel and FoldFix has to retry). None is known: Pixel Camera makes no request while the mode is on, and its selfie button ends the active request, which FoldFix sees. With a request from the adb shell it has been seen. (A request that takes the place of one FoldFix made itself in this process is reported, and is left alone on an open phone.)
- While the phone stays closed, FoldFix doesn't replace an adopted request with its own. Cancelling would blank the screen the user is looking at, and the other app could request again. The one exception needs no cancel: a request of FoldFix's own that was already waiting for its dialog when the other one became active. Confirming that dialog puts FoldFix's request in place of the other (see [the dialog](#4-the-switch-screens-dialog)). See [What FoldFix can't do](#what-foldfix-cant-do) for the cost.

#### Pixel Camera on screen when the phone is opened

Pixel Camera, started on the outer screen of the closed phone, finds rear-display mode active and behaves as if it were in its own Rear Camera Selfie mode. When the phone is opened and FoldFix ends its request, Camera is still on screen and asks for rear-display mode again, about 0.5 to 1.5 seconds later. It asks for state 5, which needs no confirmation. The viewfinder goes back to the outer screen, which now faces away from the user, and the inner screen shows System UI's "Front screen turned on / Slide to use inner screen" prompt.

What was measured on a Pixel Fold on Android 17, with the lock-screen camera and a simulated opening:

- Camera asks again every time rear-display mode ends while it is in the foreground, whoever ended it: FoldFix, System UI's "Slide to use inner screen" control, or Camera's own selfie button. Ending the request again doesn't help.
- It doesn't ask again if it only comes back to the foreground a moment after rear-display mode ended: about 0.4 seconds or more behind an opaque activity, about 1.6 seconds or more behind a translucent one. How long it had been in the background before the mode ended makes no difference.
- Started while the phone is already open, it makes no request and works on the inner screen.

So on open, FoldFix does one more thing. If its cancel has ended its request, and the first request another app makes follows within **3 seconds** of that cancel, the service starts `Curtain`: a plain black, opaque activity. Like the trampoline, it ends the request as the top app. Unlike the trampoline, it then stays on top for **1.5 seconds** after rear-display mode went off, and only then finishes. Camera comes back on the inner screen and normally stays there (see the limits below).

- **Once per opening.** The service starts the curtain at most once between an unfolding and the next fold, and the curtain tries its cancel once. If the other app asks yet again, or the cancel is refused, the request is left alone, as before. So an opening costs at most two successful cancels: FoldFix's own request, and this one.
- **Only straight after FoldFix's own cancel.** The 3 seconds count from the cancel FoldFix made on that opening, and only if rear-display mode went off through it (within 2 seconds of it). A switch-off from anything else, such as System UI's control or the other app, starts nothing. A request made later, or on an opening where FoldFix had nothing to end, is the user's choice (the real selfie mode) and isn't touched by this rule. Someone who opens Camera and picks the selfie mode within 3 seconds of unfolding would see the mode end once; choosing it again would work.
- **Also after the real selfie mode.** If the phone is closed and opened again while Camera's own selfie mode is on, FoldFix takes the request over on close, ends it on open, and ends Camera's repeat request with the curtain. Camera ends up on the inner screen. Before, it went back to the outer one.
- **Checked again when it runs.** The curtain does nothing if the 3 seconds are over by the time it runs, if the phone isn't open, if rear-display mode is off already or went off and on again since the curtain was started, or if the active request is FoldFix's. So the curtain makes no cancel later than 3 seconds after FoldFix's own, in real time: neither a phone that slept in between nor a callback that arrived late stretches that. Once it is up, the curtain is gone after 4 seconds at most. If the phone is closed while it is up, it stays for the rest of its time, so the outer screen can be black for a moment.
- **No look at other apps.** FoldFix doesn't know that the app is Camera. It only sees that rear-display mode came back on, through the same status callback as always.

Every request that follows a cancel FoldFix made on that opening is logged with its timing, whether FoldFix acts on it or not. Lines from two runs:

```text
another app turned rear display on 628 ms after our cancel: ending that once
rear display request cancelled
curtain: up, covering the other app for 1500 ms once rear display is off
another app turned rear display on 11922 ms after our cancel: left alone
```

What it costs, and what isn't known:

- The switch isn't clean. After unfolding, the inner screen comes on, shows System UI's prompt for a moment, goes black for about 1.5 seconds, and then shows Camera. Camera was back on screen 2.2 to 3.7 seconds after the simulated unfolding, and once after 4.6 seconds, when FoldFix's own cancel took 2 seconds to get through.
- This was only measured with a simulated opening and the lock-screen camera. Why Camera asks again isn't known, so the two delays are measured values with a margin, not guarantees: in 1 of 70 simulated openings Camera asked again about a second after the curtain had gone. If Camera asks later than 3 seconds on a phone opened by hand, or asks again after the curtain, the result is the old behaviour: Camera on the outer screen, until the phone is closed and opened again, or until Camera is left and System UI's control is used.
- Closing the phone and opening it again within a second or two, with Camera on screen, used to leave Camera on the outer screen now and then. Camera's repeat request can become active while FoldFix's own new request is still waiting for its dialog; if the phone was opened just as that dialog showed, FoldFix dropped its own request and left Camera's. Now it ends Camera's as well (see [Other apps' leftover requests](#other-apps-leftover-requests)), and Camera's next request is dealt with behind the curtain as on any opening. With a request from the adb shell in Camera's place and a simulated opening within half a second of it, the phone was back on the inner screen in 28 of 28 runs, 5 of them with the dialog answered first; on the build before, 1 run of 22 stayed on the outer screen. With Camera itself, 16 simulated open, close, open sequences (closed 0.2 to 0.6 seconds after the first opening, for 0.1 to 0.6 seconds) all ended on the inner screen; in one of them Camera's request got in ahead of FoldFix's. What is still left alone is a request Camera makes only after the phone is open again, on an opening where FoldFix had nothing active to end. Camera made no such request in those 16 runs.
- Pixel Camera itself crashed twice during the tests, with an uncaught `SecurityException`, when the system refused its repeat request: once in an open, close, open sequence, and once 2.7 seconds after a tap on its selfie button on the closed phone. How often that happens wasn't looked into.
- On the closed phone, Camera's selfie button can't do anything useful. See [Cameras on the closed phone](#9-cameras-on-the-closed-phone).

#### Turning FoldFix off

Turning the service off can't release its request. Once unbound, the service is no longer allowed to start the trampoline (`BAL_BLOCK`), and only the top app can cancel. FoldFix's status screen covers this case. If it's open while the service is off, the phone is open and a request that FoldFix has to end (its own or an adopted one) is still active, the screen cancels the request itself (it's the top app) and says so. Turning FoldFix back on, or rebooting, also releases it. That's why the README advises turning FoldFix off or uninstalling it with the phone open.

#### The status screen's button

The same cancel is also offered as a button, **Switch to the inner screen**, for a phone that is open but on the outer screen: another app's request that FoldFix leaves alone, or something that went wrong. It shows while rear-display mode is active and the status screen has read the hall sensor as open since it came to the front, never on a closed phone, where the outer screen is the one in use and the service would request it again. It is also hidden while a cancel of FoldFix's is under way, and for 2 seconds after a tap. A tap sends one cancel, which ends the active request whoever made it, because the user asked for exactly that.

The service doesn't count this cancel as one of its own. A tap while a cancel of the service's is still under way does nothing: the button is hidden then, and the tap is checked again when it comes. Should the two cancels ever meet all the same, the bookkeeping doesn't credit the switch-off to the service's. If another app requests rear-display mode again afterwards, even within the 3 seconds of the [Pixel Camera](#pixel-camera-on-screen-when-the-phone-is-opened) rule, nothing follows: no curtain, no second cancel. The log line is `rear display request cancelled from the status screen`. In state 5 the status screen may be showing on the outer screen when the button is tapped; it moves to the inner screen with everything else.

### 7. The outer-screen guard

FoldFix never requests rear-display mode unless Android detected the outer screen. Otherwise it does nothing, and the status screen says to restart with the phone closed.

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

### 9. Cameras on the closed phone

FoldFix doesn't touch the cameras, but the state it holds decides what the camera software believes, and Pixel Camera has a button that ends that state.

#### What the camera software is told

The camera software doesn't read the hinge sensor. It learns the fold position from the device state: the system's camera proxy (`dumpsys media.camera.proxy`) turns the committed state into a flag and passes it to the camera service and the camera driver (HAL). On a Pixel Fold on Android 17:

| Committed state | `Device state:` in `dumpsys media.camera.proxy` | Camera HAL log |
| --- | --- | --- |
| 2 `OPENED` | `NORMAL` | `Device state change from 4 to 0` |
| 3 `REAR_DISPLAY_STATE` (FoldFix, phone closed) | `FOLDED` | `Device state change from 0 to 4` |

So while FoldFix holds rear-display mode, the cameras are told that the phone is folded, which is right for a closed phone. On every such change the HAL also swaps, going by its log, which front camera stands behind the front-facing camera ID: the outer screen's when folded, the inner screen's otherwise.

#### No front camera on the test phone

On the test phone, a Pixel Fold on Android 17, that swap leads nowhere, because there is no front-facing camera ID. `adb shell dumpsys media.camera` shows the same in state 2 and in state 3 (an excerpt, with the lines shortened):

```text
Number of camera devices: 1
...
    Device 0 maps to "0"
...
  camera_status_map_:
    RearWide hal id: 3, status: Present
    FrontInner hal id: 6, status: Present
    RearVirtual hal id: 7, status: Present
    Rear hal id: 2, status: Present
    FrontOuter hal id: 5, status: Not present
    RearTele hal id: 4, status: Present
== Camera HAL device device@1.1/internal/0 (v1.4) static information: ==
...
    Facing: Back
```

- The camera service has one camera, ID 0, facing back. It is the rear cameras combined.
- The HAL reports the outer screen's camera (`FrontOuter`) as not present. The inner screen's camera (`FrontInner`) is present, but isn't offered either: on each device-state change the HAL reports a status for the front cameras, and the camera service answers that it doesn't know that device (`says an unknown camera device device@1.1/internal/1-5 is not present. Curious.`).
- The static parts of the two dumps are identical, and state 2 is what the phone is in without FoldFix. So this isn't caused by rear-display mode, and nothing FoldFix does while running changes it. (State 2 was reached by simulating an opening; the phone itself stayed shut.)

The fault is older than FoldFix. On the test phone the front camera stopped working long before FoldFix was installed. Why the outer screen's camera is missing can't be told from the outside. It sits in the same half as the outer screen, behind the hinge, so the damage that silenced the hinge sensor is the likely cause. It isn't known whether it depends on the position the phone booted in, as the [outer screen](#the-outer-screen-and-booting-closed) does: the dumps are from a phone that booted closed.

An app can only use cameras that the camera service lists. So on such a phone no app gets a front camera, open or closed. Pixel Camera logs `Can't find camera ID with facing FRONT` and opens camera 0, also when it is started with a request for the front camera. In state 2 it shows its button for switching cameras, and a tap on it only brings up Camera's own message: "Something went wrong. Since restarting your phone didn't help, the problem could be due to issues with your camera's hardware or software". The same list is what video-call apps get. Face unlock needs a front camera too; no face was set up on the test phone, so that wasn't tried.

This is one phone. A phone whose only fault is the hinge sensor may still have both front cameras. In state 3 the camera software is told "folded", so the outer screen's camera should be the one apps get on the closed phone. That hasn't been tested.

#### Pixel Camera's selfie button

Camera, opened on the outer screen of the closed phone, finds rear-display mode active and shows its Rear Camera Selfie button as switched on. The only thing the button can then do is switch that mode off, which means: use the inner screen. Camera shows no button for switching cameras there.

What a tap does, measured with the lock-screen camera and taps sent over adb:

```text
22:00:42.88  tap
22:00:42.945 rear display session 0                 Camera ended the active request (FoldFix's)
22:00:42.949 rear display status 2, state 2 OPENED  outer screen off, folded inner screen on
22:00:42.951 request rear display (try 1, 2 OPENED (rear display off))
22:00:42.960 CAM: Can't find camera ID with facing FRONT
22:00:43.046 rear display requested (window extensions)
22:00:44.099 clicked android:id/button1: true
22:00:44.170 rear display status 3, state 3 REAR_DISPLAY_STATE
```

Camera ends the active request, whoever made it. FoldFix sees a closed phone without rear-display mode and requests again; it can't tell this from any other way its request could end, and requesting again is what keeps the outer screen on. Camera also wants the mode back (it asks again [whenever the mode ends while it is on screen](#pixel-camera-on-screen-when-the-phone-is-opened)), with a state-5 request that needs no dialog. The two race, and there are three outcomes:

| Outcome | Result | How often |
| --- | --- | --- |
| FoldFix's request is accepted and its dialog confirmed | Back in state 3, FoldFix's request. Outer screen dark for 1.1 to 2.5 s | 40 of 64 taps before the change described below, 19 of 24 after |
| FoldFix's request is refused (FoldFix isn't counted as the top app at that moment) | Camera's own request follows: state 5, which FoldFix takes over. Outer screen dark for about a second. The folded inner screen is then lit whenever the outer one is, until the phone is opened | 22 of 64, then 5 of 24. The change has no bearing on this outcome: together, 27 of 88. In the taps counted for later changes it was rarer: 5 of 97 |
| FoldFix's request is accepted, but Camera's becomes active before the dialog is confirmed | State 5 with FoldFix's "Switch screens?" dialog on the outer screen. FoldFix now confirms it, and the phone is back in state 3. In the test with the adb shell's request described below, that took 0.3 to 0.9 s from the other request becoming active | 2 of 64, where the dialog stayed until it was tapped. Since the change, 1 of 12 taps in a later count: confirmed after 0.98 s |

The third outcome is the only one FoldFix made worse than leaving things alone, and the one that was changed. It didn't turn up in the 24 taps after the change, so it was tested by letting a state-5 request from the adb shell overtake FoldFix's: confirmed in 5 of 5 runs. It has come up once with Camera itself since, and was confirmed there too.

What is left:

- The button can't be made useful on a closed phone. It is Camera's switch for a mode that only makes sense on an open one.
- The second outcome leaves the phone in state 5 until it is next opened. FoldFix doesn't replace that request, for the reasons given under [Other apps' leftover requests](#other-apps-leftover-requests). Another tap usually ends in state 3 again.
- FoldFix requesting again costs little. In all 27 taps of the first counts where its request was refused, Camera's own brought the outer screen back in state 5 about a second after it went dark. FoldFix's request takes about as long when it gets through, and it is what switches the inner screen off again.

## Testing and logs

### The tests

```sh
./test.sh
```

The rules that decide which screen is on are in three classes that use nothing of Android's: `Reconciler` (the [reconcile loop](#5-the-reconcile-loop)), `Ownership` ([whose request it is](#6-whose-request-is-it)) and `Stale` (the second look of the [trampoline](#3-the-trampoline-activity) and the [curtain](#pixel-camera-on-screen-when-the-phone-is-opened)). The Android classes read the sensor, the clock and the device state, hand those to the rules, and carry out what the rules say. So the rules can be run on any computer with a JDK, and `test.sh` does that: `javac`, then `java`, with a small test runner in `tests/`. There is no test library, as there is no other dependency.

What the tests cover:

- **One step of the reconcile loop at a time:** every row of the [table](#5-the-reconcile-loop), the retry schedule, the 3-second windows, and when FoldFix must not act (no hall reading, no backend, device state unknown, outer screen not detected, rear-display mode unavailable). One group runs a step in every combination of what the rules read, 65,536 of them, and checks what must hold in all: never a request without the outer screen, never a cancel of another app's request by the trampoline, a look for the dialog only where a dialog would be answered.
- **The bookkeeping:** FoldFix's own request from launch to end, requests it takes over (while closed, on open, and when not yet), the 10 seconds a request may wait for its dialog, and which cancel counts as the one that ended rear-display mode.
- **Late launches:** when a cancel, a request or the curtain stands down.
- **Whole sequences,** as they were tried by hand on a phone for earlier changes: closing and opening, another app's request made while open or found while closed, or getting in ahead of FoldFix's own, Pixel Camera on screen when the phone is opened, its selfie button on the closed phone, launches that arrive late, FoldFix restarted. These run on a model of the phone (`tests/app/foldfix/SimPhone.java`), in which the test says when a launch arrives, when the dialog shows and what another app does.
- **[The history](#the-history):** which lines are kept, in which order, and how their times read. `FoldLog` also writes to Android's log, the one class of Android's that the tested code uses; a small class in `tests/` stands in for it.

A few cases carry the comment `current behaviour, pinned as found`. They record what the code does in a spot where that is open to question, so that a change there shows up. They don't say that it is right.

What they don't cover: Android. The tests say what FoldFix decides, not what the system does with it. Whether a request is accepted, when the dialog shows, what Pixel Camera does and how long any of it takes is only known from a phone, and the model's idea of it is no better than the measurements in this document. The parts of FoldFix that talk to the system have no tests either: finding the sensor and the dialog, the two backends, the landscape fix and the status screen. A change to those, or to the rules, still needs the phone (below).

The tests are never part of the APK: `build.sh` only compiles `app/src`. CI runs them before every build and before a release is signed.

### The log

FoldFix logs what it does under the tag `FoldFix`:

```sh
adb logcat -s FoldFix
```

### The history

That log is of little use afterwards, because it only reaches back a few minutes at most. On the test phone, the buffer that apps' lines go to held anything from under a minute to about six minutes when it was sampled, depending on how much else was being logged. (The system's own buffer, into which the dead hinge sensor makes Android write about 400 `FoldableDeviceStateProvider` lines per second, held two to three.) So FoldFix also keeps its own lines itself. Every log call goes through one class, `FoldLog`, which writes the line to Android's log as before (same tag, same text) and adds it to a history:

- **The last 500 lines**, each with the wall-clock time to the millisecond and its level (`I`, `W` or `E`). Opening and closing the phone once writes 15 lines, or 23 with [Pixel Camera on screen](#pixel-camera-on-screen-when-the-phone-is-opened) (two more each when [simulated](#driving-it-from-adb)), so that is the last 33 such cycles, or 21 of the long ones. Older lines drop out one by one. Kept in memory, 500 lines take an estimated 50 KB.
- **In memory only.** No file and no setting: the history starts again when FoldFix's process does (an update, a restart of the phone, Android restarting the app). Each process writes its version as its first line, such as `FoldFix 1.0.3 (4) started`, so it is plain where the history begins.
- **Only what FoldFix logs:** device states, timings, the view IDs of the dialog's buttons, and the names and messages of exceptions (Android's log gets their stack traces, the history doesn't). Nothing read from the screen is ever logged, and nothing about other apps: FoldFix only knows that rear-display mode went on or off, not through which app. A line longer than 300 characters is cut.
- **Cheap.** Adding a line takes a lock and stores a time and the text that was logged anyway; times are only formatted when the history is read. It can be called from any thread.

The status screen shows the newest 100 lines under **Recent activity**, and its **Copy** button (or a long press on the lines) copies the **Version** line followed by all 500. Two limits keep that cheap, because the status screen runs on the main thread of the one process, which the service needs too (see [Drawn in software](#drawn-in-software)):

- **100 lines, not 500.** What costs is laying out the text again when it changes. On an idle Pixel Fold on Android 17, where everything runs at its slowest, a refresh that renewed the lines took about 36 ms with 100 lines shown (21 to 52 ms in ten runs) and about 88 ms with all 500 (60 to 118 ms); a refresh without a change took about 7 ms. Drawing the next frame in software added 16 to 46 ms while the lines filled the screen. Scrolling was the same with either, about 5 ms per frame.
- **At most once a second,** and only when the history has changed. The screen refreshes more often than that during a screen switch, on every change of the device state; the lines wait for the next refresh that is a second later. The exception is a status screen that Android creates anew because it moved to the other display: it lays out everything once anyway, the lines included.

From a computer, the same comes from the service, without the status screen:

```sh
adb shell dumpsys activity service app.foldfix/.FoldService
```

```text
SERVICE app.foldfix/.FoldService 72adec4 pid=2749 user=0
  Client:
    FoldFix 1.0.3 (4), self-built (signed with another key)
    Status: Closed: outer screen on
    Recent activity (in memory only, since FoldFix last started; newest last):
    11-11 02:42:29.568 I FoldFix 1.0.3 (4) started
    11-11 02:42:29.603 I window extensions ready, rear state 5 -> 3
    ...
    11-11 02:42:53.827 I rear display status 3, state 3 REAR_DISPLAY_STATE
    11-11 02:42:53.834 I status: Closed: outer screen on
```

It works on a locked phone, needs the service to be on (otherwise: `No services match`), and like every `dumpsys` it is only open to holders of `DUMP`: the adb shell, privileged system apps, and apps it was granted to over adb. `dumpsys` indents the lines, and now and then leaves the indent off one of them. The lines are the ones `adb logcat -s FoldFix` shows; a time can be a millisecond later than there, because the history takes it right after the line was logged.

### Driving it from adb

The trampoline can be driven from adb for testing. The adb shell holds the `DUMP` permission that protects it ([the trampoline](#3-the-trampoline-activity) says who else does).

```sh
adb shell am start -n app.foldfix/.Trampoline --es action request
adb shell am start -n app.foldfix/.Trampoline --es action cancel
adb shell am start -n app.foldfix/.Trampoline --es action cancel --ez force true
```

The outer-screen guard still applies, and so do the trampoline's own checks. A `request` is dropped while the service reads the phone as open, and does nothing while rear-display mode is on or a request of FoldFix's is under way.

A plain `cancel` is checked like the service's own (see [the trampoline](#3-the-trampoline-activity)): it only ends a request that is FoldFix's to end, and not while the service reads the phone as closed. FoldFix can't always tell whose the active request is: one it took over from an earlier FoldFix process, or adopted, still counts as its own after another request replaced it without rear-display mode going off (see [Other apps' leftover requests](#other-apps-leftover-requests)), and a plain `cancel` ends that one too. `--ez force true` skips that check and ends whatever request is active, for example another app's, or FoldFix's own on the closed phone. The service never uses it.

Folding and unfolding can be simulated too, so both directions can be tested on a phone that stays shut on a desk:

```sh
adb shell am broadcast -n app.foldfix/.HallOverride --es hall open
adb shell am broadcast -n app.foldfix/.HallOverride --es hall closed
adb shell am broadcast -n app.foldfix/.HallOverride --es hall real
```

`open` and `closed` make the running service act on that value instead of the hall sensor's. It takes the same path as a real sensor event: the retry schedule is reset, the reconcile loop runs, and the trampoline and the landscape fix see the simulated value. `real` goes back to the sensor and applies its current reading straight away.

- While a value is simulated, the sensor's readings are remembered and logged, but not acted on.
- The command prints what happened (`data="simulating hall open, sensor says closed"`), and every change is logged.
- The status screen shows a `TEST MODE` line under **Accessibility service** for as long as a value is simulated. Its [button](#the-status-screens-button) goes by the simulated value too, not by the sensor.
- Nothing is stored. The override is off by default and ends when the service stops or connects again, or when FoldFix's process restarts. It's ignored while the service isn't running, or before the sensor's first reading.
- There is no timeout: finish a test with `real`, or the phone keeps acting on the simulated value.
- Like the trampoline, the receiver is protected by `DUMP`. The adb shell and privileged system apps hold it; any other app can only send the broadcast if that permission was granted to it over adb.

As with a real fold, the switch can be delayed while the screen is off; `adb shell input keyevent KEYCODE_WAKEUP` wakes it. Don't read the screen with `uiautomator dump` during such a test: it suspends all accessibility services while it runs, which restarts FoldFix's service and so ends the override. `screencap` is safe.

## What FoldFix can't do

- **Half-open postures** (tabletop mode and apps that adapt to a half-folded phone) need the hinge angle. The hall sensor only knows closed or not closed.
- **The fold/unfold animation** is driven by the hinge angle, so it doesn't play.
- **Detecting the outer screen** happens in the bootloader, before any app runs. If the phone booted open and the outer panel wasn't found, only a reboot with the phone closed brings it back.
- **Bringing back a front camera.** If Android lists no front camera, no app can open one, and the state FoldFix holds doesn't change which cameras are listed (see [Cameras on the closed phone](#9-cameras-on-the-closed-phone)).
- **Turning the inner screen off under another app's request.** If another app's state-5 request is active when the phone is closed, FoldFix keeps it until the phone is opened (see [Other apps' leftover requests](#other-apps-leftover-requests)). The exception is a request of FoldFix's own that was still waiting for its dialog when the other one became active (see [the dialog](#4-the-switch-screens-dialog)). State 5 leaves the inner screen on, so until then the folded inner screen is lit whenever the outer one is.

# Diagnosing a Pixel Fold that won't switch screens

These checks tell you whether your phone has the failure FoldFix works around:

- the **hinge-angle sensor** has stopped reporting,
- the **hall-effect (magnet) sensor** still works, and
- Android can see **both screens**.

Checks 1 to 5 only read information (the optional `logcat -G` in check 2 just enlarges the log buffer until the next reboot). Check 6 changes the device state temporarily; read its warning first.

If you'd rather not use a computer, install FoldFix and open it. Its status screen covers the same ground (see the [README](../README.md#check-your-phone)).

## Before you start

1. Install the Android SDK Platform-Tools (`adb`) on a computer.
2. On the phone, turn on USB debugging: **Settings → About phone →** tap **Build number** seven times, then **Settings → System → Developer options → USB debugging**.
3. Connect the phone, accept the "Allow USB debugging?" prompt, and check that it shows up:

   ```sh
   adb devices
   ```

The commands below put the filter inside `adb shell "..."`, so `grep` runs on the phone. That way they work the same on macOS, Linux and Windows.

Exact numbers (sensor handles, line numbers in stack traces, display mode IDs) vary between builds. Look at the overall pattern.

## 1. Sensors: `dumpsys sensorservice`

```sh
adb shell "dumpsys sensorservice | grep -E 'Hinge Angle|Hall Effect'"
```

Fold and unfold the phone a few times, then run it again.

**Affected phone** (both sensors are listed, but only the hall sensor has an event history):

```text
0x01010015) Hinge Angle (wake-up)     | Google          | ver: 1 | type: android.sensor.hinge_angle(36) | perm: n/a | flags: 0x00000003
0x0101001f) DRV5032 Hall Effect Sensor (wake-up) | Texas Instruments | ver: 1 | type: com.google.sensor.hall_effect(65547) | perm: n/a | flags: 0x00000003
DRV5032 Hall Effect Sensor (wake-up): last 10 events
Hinge Angle (wake-up) (handle=0x01010015, connections=3)
DRV5032 Hall Effect Sensor (wake-up) (handle=0x0101001f, connections=1)
```

What to look for:

- `DRV5032 Hall Effect Sensor (wake-up): last N events` is present. The hall sensor works.
- There is **no** `Hinge Angle (wake-up): last N events` line, even though the system is listening to it (`connections=3`). The hinge sensor has never reported since boot.

To see the hall sensor's values:

```sh
adb shell "dumpsys sensorservice | grep -A 4 'Hall Effect Sensor (wake-up): last'"
```

```text
DRV5032 Hall Effect Sensor (wake-up): last 10 events
	 1 (ts=12023.008838280, wall=22:05:58.778) 1.00, 0.00, 0.00, ...
	 2 (ts=12031.720187701, wall=22:06:07.491) 0.00, 0.00, 0.00, ...
	 3 (ts=12034.108693904, wall=22:06:09.884) 1.00, 0.00, 0.00, ...
```

The first value is `1.00` when the phone is closed and `0.00` when it's open. New events should appear each time you fold or unfold.

**Healthy phone:** there is also a `Hinge Angle (wake-up): last N events` block, and its first value is the hinge angle in degrees (around 180 when flat open, near 0 when closed).

**Hall sensor broken too:** no hall event history, or no new events when you fold. FoldFix can't help in that case.

## 2. Logs: `FoldableDeviceStateProvider`

`FoldableDeviceStateProvider` is the part of Android that turns the hinge angle into "open", "half-open" or "closed".

```sh
adb logcat | grep FoldableDeviceStateProvider
```

Press Ctrl+C to stop. To dump what's already in the log and exit instead:

```sh
adb shell "logcat -d | grep -m 10 FoldableDeviceStateProvider"
```

**Affected phone** (repeats many times per second):

```text
W FoldableDeviceStateProvider: Unable to check current state = 0
W FoldableDeviceStateProvider: java.lang.IllegalStateException: Have not received sensor event.
W FoldableDeviceStateProvider: 	at com.android.server.policy.FoldableDeviceStateProvider.getSensorValue(FoldableDeviceStateProvider.java:344)
W FoldableDeviceStateProvider: 	at com.android.server.policy.FoldableDeviceStateProvider.getHingeAngle(FoldableDeviceStateProvider.java:548)
W FoldableDeviceStateProvider: 	at com.android.server.policy.BookStyleClosedStatePredicate.test(BookStyleClosedStatePredicate.java:135)
I FoldableDeviceStateProvider: Sensor values:
I FoldableDeviceStateProvider: Hinge Angle (wake-up) : null
```

Android tries to work out the fold state, asks for the hinge angle, gets nothing (`null`), and gives up. The state stays where it was (OPENED).

**Healthy phone:** none of these warnings.

These messages are so frequent that they push everything else out of the default log buffer within a minute or so. If you need longer log history, enlarge the buffer (resets at reboot):

```sh
adb logcat -G 4M
```

## 3. Device state: `cmd device_state`

The device state is Android's idea of the phone's posture, and it decides which screen is used.

```sh
adb shell cmd device_state print-states
```

```text
Supported states: [
  DeviceState{identifier=0, name='CLOSED', app_accessible=true, cancel_when_requester_not_on_top=false},
  DeviceState{identifier=1, name='HALF_OPENED', app_accessible=true, cancel_when_requester_not_on_top=false},
  DeviceState{identifier=2, name='OPENED', app_accessible=true, cancel_when_requester_not_on_top=false},
  DeviceState{identifier=3, name='REAR_DISPLAY_STATE', app_accessible=true, cancel_when_requester_not_on_top=false},
  DeviceState{identifier=4, name='CONCURRENT_INNER_DEFAULT', app_accessible=true, cancel_when_requester_not_on_top=true},
  DeviceState{identifier=5, name='REAR_DISPLAY_OUTER_DEFAULT', app_accessible=true, cancel_when_requester_not_on_top=false},
]
```

(The list may differ by Android version. [How it works](how-it-works.md#device-states) explains each one.)

Now **fold the phone shut** and run:

```sh
adb shell cmd device_state state
```

**Affected phone, without FoldFix:** the state stays OPENED even though the phone is closed.

```text
Committed state: DeviceState{identifier=2, name='OPENED', ...}
----------------------
Base state: DeviceState{identifier=2, name='OPENED', ...}
```

**Healthy phone:** `CLOSED` (identifier 0) when folded, `OPENED` when unfolded.

**Affected phone, with FoldFix running:** the base state (from the sensors) is still OPENED, but FoldFix's request overrides it with the rear-display state, so the outer screen is used.

```text
Committed state: DeviceState{identifier=3, name='REAR_DISPLAY_STATE', ...}
----------------------
Base state: DeviceState{identifier=2, name='OPENED', ...}
Override state: DeviceState{identifier=3, name='REAR_DISPLAY_STATE', ...}
```

## 4. Displays: `dumpsys display`

```sh
adb shell "dumpsys display | grep -E 'Display Devices|Logical Displays|DisplayDeviceInfo'"
```

**Outer screen detected** (two display devices):

```text
Display Devices: size=2
  DisplayDeviceInfo{"Inner Display": uniqueId="local:<id>", 2208 x 1840, modeId 2, ...
  DisplayDeviceInfo{"Outer Display": uniqueId="local:<id>", 1080 x 2092, modeId 4, ...
  Logical Displays: size=2
```

**Outer screen not detected** (only the inner one):

```text
Display Devices: size=1
  DisplayDeviceInfo{"Inner Display": uniqueId="local:<id>", 2208 x 1840, modeId 2, ...
  Logical Displays: size=1
```

If you only see one display, power the phone off, fold it shut, and power it on again while it's closed. Then check again. On some affected phones the outer screen is only detected when the phone starts up closed.

If it still shows one display after booting closed, the outer screen or its connection has a problem that software can't work around.

## 5. Panels at boot: `/proc/cmdline` (optional)

The bootloader tells the kernel which display panels it found. You can read that list:

```sh
adb shell "cat /proc/cmdline | tr ' ' '\n' | grep panel"
```

**Both panels found:**

```text
panel-samsung-ana6707-f10.load_sequential=1
exynos_drm.panel_name=dsim0:samsung-ana6707-f10
exynos_drm.sec_panel_name=dsim1:ea8182-f10
```

**Only the inner panel found:** there is no `exynos_drm.sec_panel_name=...` line.

The panel model names can differ between phones. What matters is whether a `sec_panel_name` (secondary panel) entry is there. This is decided before Android starts, which is why only a reboot (with the phone closed) changes it.

## 6. Manual test: force the closed state (optional)

> **Warning:** only do this if check 4 shows **two** display devices. Forcing the closed state when the outer screen wasn't detected crashes Android's system server (`Can't remove the primary display`) and the phone restarts.

With the phone folded shut, run:

```sh
adb shell cmd device_state base-state 0
```

The outer screen should turn on and show what the inner screen was showing. This tells you the screens and the rest of Android work fine and only the sensor input is missing.

Undo it (otherwise Android treats the phone as closed even when you open it):

```sh
adb shell cmd device_state base-state reset
```

The override doesn't survive a reboot.

## What the results mean

| Check | Affected phone (FoldFix can help) | Points elsewhere |
| --- | --- | --- |
| Hinge-angle sensor events | None at all | Values change as you fold: the sensor works |
| Hall sensor events | `1.00` / `0.00` flip as you fold | No new events: hall sensor broken too, FoldFix can't help |
| `FoldableDeviceStateProvider` log | `Have not received sensor event` over and over | No warnings |
| Device state when folded | Stays `OPENED` | `CLOSED`: Android already detects folding |
| Display devices | 2 (at least after booting closed) | Still 1 after booting closed: outer screen or cable problem |

## Collecting information for a bug report or repair

These commands save the relevant output to files:

```sh
adb shell getprop ro.build.fingerprint                > build.txt
adb shell dumpsys sensorservice                       > sensors.txt
adb shell cmd device_state print-states               > device_states.txt
adb shell cmd device_state state                     >> device_states.txt
adb shell dumpsys display                             > display.txt
adb shell "cat /proc/cmdline | tr ' ' '\n' | grep panel" > panels.txt
adb logcat -d -s FoldFix                              > foldfix.txt
adb logcat -d                                         > logcat.txt
```

`foldfix.txt` holds only FoldFix's own log (tag `FoldFix`): what it detected and what it did. Fold and unfold the phone a few times just before running it. It's usually the most useful file for a FoldFix bug report.

Full logs and dumps can contain personal information, such as account names, Wi-Fi network names, device identifiers, app names and notification text. Before attaching them to a public issue, trim them to the relevant lines or check them carefully.

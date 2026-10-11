package app.foldfix;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;

/**
 * Keeps landscape the right way up on the outer screen.
 *
 * <p>Android turns the orientation sensor's landscape around in rear-display mode
 * ({@code config_deviceStatesToReverseDefaultDisplayRotationAroundZAxis}): the mode is meant for
 * an open phone turned around so that the outer screen faces someone else. FoldFix uses it on a
 * closed phone, where the outer screen faces the user the usual way, so every turn the sensor
 * drives (fullscreen video, auto-rotate, the rotate button) lands upside down.
 *
 * <p>While the phone is closed in rear-display mode, this keeps an invisible accessibility overlay
 * attached (zero size, not touchable). A window above the apps that asks for an orientation
 * decides it for the whole screen, so while the phone is held sideways and the screen is in
 * landscape, the overlay asks for the landscape the phone is really in. Otherwise it asks for
 * nothing and the app decides. The overlay stays attached so that switching takes a single
 * layout update, fast enough to catch Android's own rotation while it is still being prepared.
 *
 * <p>Other apps' orientation requests can't be read, which leaves two gaps: when an app turns to
 * landscape while the phone is already sideways, Android may show the wrong side for a moment
 * before the overlay corrects it; and an app that goes back to portrait (leaving fullscreen) while
 * the phone is sideways stays in landscape until the phone is turned upright.
 */
final class RotationFix implements SensorEventListener, DisplayManager.DisplayListener {
    static final String TAG = FoldService.TAG;
    /** Sensor.TYPE_DEVICE_ORIENTATION (hidden): the rotation Android's auto-rotate follows. */
    static final int TYPE_DEVICE_ORIENTATION = 27;

    private final AccessibilityService service;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SensorManager sm;
    private final DisplayManager dm;
    private final WindowManager wm;
    private final KeyguardManager km;
    private final PowerManager pm;
    private final Sensor sensor;
    private final boolean needed;
    private final WindowManager.LayoutParams params = new WindowManager.LayoutParams(0, 0,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSPARENT);

    private boolean active;
    /** Rotation that matches how the phone is held, from the sensor; -1 while unknown. */
    private int held = -1;
    /** Rotation the overlay asks for; -1 for none. */
    private int forced = -1;
    private View overlay;
    /** Screen on/off and unlock: display listeners don't hear about those on newer versions. */
    private final BroadcastReceiver screenEvents = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            update();
        }
    };

    RotationFix(AccessibilityService service) {
        this.service = service;
        sm = service.getSystemService(SensorManager.class);
        dm = service.getSystemService(DisplayManager.class);
        wm = service.getSystemService(WindowManager.class);
        km = service.getSystemService(KeyguardManager.class);
        pm = service.getSystemService(PowerManager.class);
        sensor = sm.getDefaultSensor(TYPE_DEVICE_ORIENTATION);
        needed = reversesRearDisplay();
        params.setTitle("FoldFix landscape fix");
        Log.i(TAG, "landscape fix " + (sensor == null ? "unavailable: no device orientation sensor"
                : needed ? "ready" : "not needed: Android doesn't reverse rear-display rotation"));
    }

    /** Whether Android reverses landscape in a rear-display state (3 or 5). */
    static boolean reversesRearDisplay() {
        Resources r = Resources.getSystem();
        int id = r.getIdentifier("config_deviceStatesToReverseDefaultDisplayRotationAroundZAxis", "array", "android");
        if (id == 0) return false;  // the reversal doesn't exist before Android 14
        for (int state : r.getIntArray(id)) {
            if (state == RearDisplay.STATE_REAR_DISPLAY || state == RearDisplay.STATE_REAR_DISPLAY_OUTER_DEFAULT) return true;
        }
        return false;
    }

    /** On while the phone is closed in rear-display mode. */
    void setActive(boolean on) {
        on &= sensor != null && needed;
        if (on == active) return;
        active = on;
        if (on) {
            try {
                params.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
                overlay = new View(service);
                wm.addView(overlay, params);
            } catch (RuntimeException e) {
                Log.w(TAG, "landscape fix: can't add the overlay", e);
                overlay = null;
                active = false;
                return;
            }
            sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, main);
            dm.registerDisplayListener(this, main);
            IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
            f.addAction(Intent.ACTION_SCREEN_ON);
            f.addAction(Intent.ACTION_USER_PRESENT);
            service.registerReceiver(screenEvents, f, null, main, Context.RECEIVER_NOT_EXPORTED);
        } else {
            sm.unregisterListener(this);
            dm.unregisterDisplayListener(this);
            service.unregisterReceiver(screenEvents);
            if (overlay != null) wm.removeView(overlay);
            overlay = null;
            held = -1;
            forced = -1;
        }
    }

    private void update() {
        if (!active) return;
        Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
        if (d == null) return;
        int screen = d.getRotation();
        int target = -1;
        // Only while landscape is wanted anyway (the screen is in it, or the overlay keeps it
        // there): never turn an app that's in portrait. The keyguard picks its own orientation.
        if (landscape(held) && (forced >= 0 || landscape(screen))
                && pm.isInteractive() && !km.isKeyguardLocked()) {
            target = held;
        }
        if (target == forced) return;
        Log.i(TAG, "landscape fix: " + (target < 0 ? "off" : "rotation " + target)
                + " (phone " + held + ", screen " + screen + ")");
        forced = target;
        // The outer screen is portrait by nature: LANDSCAPE is rotation 90, REVERSE_LANDSCAPE 270.
        params.screenOrientation = target < 0 ? ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                : target == Surface.ROTATION_90 ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
        wm.updateViewLayout(overlay, params);
    }

    private static boolean landscape(int rotation) {
        return rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270;
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        held = (int) e.values[0];
        update();
    }

    @Override
    public void onDisplayChanged(int displayId) {
        if (displayId == Display.DEFAULT_DISPLAY) update();
    }

    @Override
    public void onDisplayAdded(int displayId) {}

    @Override
    public void onDisplayRemoved(int displayId) {}

    @Override
    public void onAccuracyChanged(Sensor s, int accuracy) {}
}

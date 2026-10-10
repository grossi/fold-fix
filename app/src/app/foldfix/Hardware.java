package app.foldfix;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.os.Build;

import java.util.Locale;

/** Sensor and display lookups shared by the service and the status screen. */
final class Hardware {
    /** Pixel Fold's hall-effect sensor ("DRV5032 Hall Effect Sensor", com.google.sensor.hall_effect). */
    static final int TYPE_GOOGLE_HALL = 65547;

    /** Public since API 37: built-in displays, including ones that are currently off. */
    static final String CATEGORY_BUILT_IN = "android.hardware.display.category.BUILT_IN_DISPLAYS";
    /** Hidden category with the same effect on older versions (plus any external displays). */
    static final String CATEGORY_ALL_INCLUDING_DISABLED = "android.hardware.display.category.ALL_INCLUDING_DISABLED";

    private static volatile boolean outerSeen;

    private Hardware() {}

    /**
     * The hall (magnet) sensor that says whether the phone is closed. Prefers the wake-up
     * variant so closing the phone is noticed even while the screen is off.
     */
    static Sensor findHallSensor(SensorManager sm) {
        Sensor best = null;
        for (Sensor s : sm.getSensorList(Sensor.TYPE_ALL)) {
            boolean hall = s.getType() == TYPE_GOOGLE_HALL
                    || s.getStringType().toLowerCase(Locale.ROOT).contains("hall");
            if (hall && (best == null || (s.isWakeUpSensor() && !best.isWakeUpSensor()))) best = s;
        }
        return best;
    }

    static boolean isClosed(SensorEvent e) {
        return e.values[0] >= 0.5f;
    }

    /** Number of built-in displays, counting ones that are off (the outer screen while open). */
    static int builtInDisplayCount(Context c) {
        String category = Build.VERSION.SDK_INT >= 37 ? CATEGORY_BUILT_IN : CATEGORY_ALL_INCLUDING_DISABLED;
        return c.getSystemService(DisplayManager.class).getDisplays(category).length;
    }

    /**
     * Whether the outer screen exists as a display. The bootloader only brings up the outer panel
     * if the phone boots closed; booted open there is a single display, and forcing rear-display
     * mode then crashes the system ("Can't remove the primary display").
     *
     * <p>A positive answer is kept for the life of the process: the panel can't disappear without
     * a reboot, while the display list is briefly incomplete during every screen switch.
     */
    static boolean outerDisplayPresent(Context c) {
        if (!outerSeen) outerSeen = builtInDisplayCount(c) >= 2;
        return outerSeen;
    }
}

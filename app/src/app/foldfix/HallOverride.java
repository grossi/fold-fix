package app.foldfix;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Test hook: makes the running service act on a simulated hall reading instead of the sensor's,
 * so that opening and closing can be tested on a phone nobody is touching. The service handles
 * it exactly like a sensor event; the status screen flags it.
 *
 * <p>Only holders of {@code DUMP} may send it, in practice the adb shell (see the manifest):
 * {@code adb shell am broadcast -n app.foldfix/.HallOverride --es hall open|closed|real}
 *
 * <p>Nothing is stored and nothing times out: the override ends with {@code real}, when the
 * service stops or connects again, or with the process.
 */
public class HallOverride extends BroadcastReceiver {
    static final String EXTRA_HALL = "hall", OPEN = "open", CLOSED = "closed", REAL = "real";

    @Override
    public void onReceive(Context c, Intent intent) {
        String hall = intent.getStringExtra(EXTRA_HALL);
        String result;
        if (OPEN.equals(hall) || CLOSED.equals(hall)) {
            result = FoldService.simulateHall(CLOSED.equals(hall));
        } else if (REAL.equals(hall)) {
            result = FoldService.simulateHall(null);
        } else {
            result = "ignored: expected --es " + EXTRA_HALL + " " + OPEN + "|" + CLOSED + "|" + REAL;
            FoldLog.w("hall simulation " + result);
        }
        if (isOrderedBroadcast()) setResultData(result);  // "am broadcast" prints it
    }
}

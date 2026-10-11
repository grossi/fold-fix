package app.foldfix;

import android.accessibilityservice.AccessibilityService;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

/**
 * The fix itself. On a Pixel Fold with a dead hinge-angle sensor Android is stuck in the OPENED
 * state, so closing the phone leaves the (folded-away) inner screen on and the outer one off.
 * This service watches the hall sensor instead and keeps "closed ⇔ rear-display mode" true:
 * closed → request rear-display mode (outer screen on), open → end our request.
 *
 * <p>Each request makes SystemUI ask "Switch screens?" on the inner screen, which is folded shut
 * at that moment, so the service answers the dialog itself. Rather than trusting single events,
 * it reconciles hall vs. device state every {@link #POLL_MS} until they agree.
 *
 * <p>It never fights other apps: Pixel Camera's rear-screen selfie mode uses the same state and
 * dialog, so the service only confirms, cancels or ends requests it made itself.
 *
 * <p>While the phone is closed on the outer screen, {@link RotationFix} keeps landscape the right
 * way up (rear-display mode turns it upside down).
 */
public class FoldService extends AccessibilityService implements SensorEventListener {
    static final String TAG = "FoldFix";
    static final String SYSTEMUI = "com.android.systemui";
    static final String DIALOG_TITLE_ID = SYSTEMUI + ":id/rear_display_title_text_view";
    /** Dialog buttons by ID, not label, so it works in every language. */
    static final String CONFIRM_ID = "android:id/button1", CANCEL_ID = "android:id/button2";

    static final long POLL_MS = 200;
    static final long RETRY_MS = 2_000;
    static final long BACKOFF_MS = 30_000;    // after MAX_FAST_TRIES launches that didn't help
    static final int MAX_FAST_TRIES = 5;
    static final long DIALOG_WAIT_MS = 3_000; // how long a dialog may take to show up
    static final long BLOCKED_RECHECK_MS = 5_000;

    /** What the service is doing, for the status screen; null while it isn't running. */
    static volatile String status;
    /** Last hall reading while the service runs, else null; lets the trampoline drop stale requests. */
    static volatile Boolean hallClosed;

    final Handler h = new Handler(Looper.getMainLooper());
    final Runnable reconcile = this::reconcile;

    SensorManager sm;
    RearDisplay rd;
    RotationFix rotation;
    boolean running;
    Boolean closed;
    long lastLaunch, lastRequest;
    int tries;

    @Override
    protected void onServiceConnected() {
        running = true;
        rd = RearDisplay.get(this);
        rd.addListener(reconcile);
        rotation = new RotationFix(this);
        sm = getSystemService(SensorManager.class);
        sm.unregisterListener(this);  // in case the system connects the same instance twice
        Sensor hall = Hardware.findHallSensor(sm);
        if (hall == null) {
            setStatus("No hall sensor found: this phone is not supported");
        } else {
            boolean ok = sm.registerListener(this, hall, SensorManager.SENSOR_DELAY_NORMAL);
            Log.i(TAG, "service connected, hall sensor \"" + hall.getName() + "\" registered=" + ok
                    + ", backend " + rd.backend());
            setStatus("Waiting for the hall sensor and device state");
        }
        // Checked now, while no screen switch is in progress (the display list is briefly
        // incomplete during one).
        Log.i(TAG, "outer screen present " + Hardware.outerDisplayPresent(this)
                + " (built-in displays: " + Hardware.builtInDisplayCount(this) + "), hidden API "
                + (rd.hasHiddenApi() ? "allowed" : "blocked"));
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        boolean c = Hardware.isClosed(e);
        if (closed != null && closed == c) return;
        Log.i(TAG, c ? "hall closed" : "hall open");
        closed = hallClosed = c;
        lastLaunch = 0;
        tries = 0;
        reconcile();
    }

    /** One step towards "closed ⇔ rear display"; re-arms itself until that holds. */
    void reconcile() {
        h.removeCallbacks(reconcile);
        if (!running || closed == null) return;
        rotation.setActive(closed && rd.isRearActive());
        if (rd.backend() == null) {
            setStatus("No rear-display access: run the one-time adb command");
            return;  // can only change after a restart
        }
        // Wait until the device state is known too: acting earlier can put a dialog on the
        // outer screen.
        if (!rd.isKnown()) return;
        boolean rear = rd.isRearActive();
        boolean ours = rd.hasOwnRequest();
        long now = SystemClock.uptimeMillis();

        if (closed) {
            if (rear) {  // already on the outer screen, whoever asked for it
                tries = 0;
                setStatus("Closed: outer screen on");
                return;
            }
            String blocked = whyNotPossible();
            if (blocked != null) {
                setStatus(blocked);
                h.postDelayed(reconcile, BLOCKED_RECHECK_MS);
                return;
            }
            AccessibilityNodeInfo dialog = ours ? findDialog() : null;
            if (dialog != null) {
                click(dialog, CONFIRM_ID);
            } else if (!rd.isSessionLive() && now - lastLaunch > retryDelay()) {
                // Not while our request is in flight: a confirmed switch can take a moment.
                launch(Trampoline.REQUEST);
            }
            setStatus("Closed: switching to the outer screen");
        } else {
            // Also look for a dialog shortly after our last request: one can still pop up after
            // ownership was dropped, and tapping "Switch" there would strand the phone in
            // rear-display mode while open.
            boolean recent = now - lastRequest < DIALOG_WAIT_MS;
            if (!ours && !recent) {  // nothing of ours to undo; never touch other apps' requests
                tries = 0;
                setStatus(rear ? "Open: another app is using the outer screen" : "Open: inner screen on");
                return;
            }
            AccessibilityNodeInfo dialog = findDialog();
            if (dialog != null) {
                // The only way to drop a request that is still waiting for its dialog.
                click(dialog, CANCEL_ID);
            } else if (ours && rear && now - lastLaunch > retryDelay()) {
                // Like requests, cancels are only accepted from the top app, hence the trampoline.
                launch(Trampoline.CANCEL);
            } else if (!rear && !recent) {
                // Ours but pending, dialog not up (yet): wait for SystemUI events instead of polling.
                setStatus("Open: waiting for the request's dialog to cancel it");
                return;
            }
            setStatus("Open: switching back to the inner screen");
        }
        h.postDelayed(reconcile, now - lastLaunch < DIALOG_WAIT_MS ? POLL_MS : retryDelay());
    }

    long retryDelay() {
        return tries < MAX_FAST_TRIES ? RETRY_MS : BACKOFF_MS;
    }

    /** Reasons not to request rear-display mode right now (checked each time, it's cheap). */
    String whyNotPossible() {
        if (!Hardware.outerDisplayPresent(this)) {
            // Booted open: the outer panel was never brought up, and forcing rear-display mode
            // would crash the system.
            return "Outer screen not detected: restart the phone while it is closed";
        }
        if (!rd.isAvailable()) return "Rear-display mode is unavailable right now (battery saver or heat?)";
        return null;
    }

    void launch(String action) {
        long now = SystemClock.uptimeMillis();
        lastLaunch = now;
        tries++;
        if (Trampoline.REQUEST.equals(action)) {
            lastRequest = now;
            rd.markRequestPending();
        }
        Log.i(TAG, action + " rear display (try " + tries + ", " + rd.describeState() + ")");
        Trampoline.start(this, action);
    }

    /**
     * Root of SystemUI's "Switch screens?" dialog, or null. Only system windows are opened, and
     * only SystemUI's are searched: other apps' windows are never read.
     */
    AccessibilityNodeInfo findDialog() {
        for (AccessibilityWindowInfo w : getWindows()) {
            if (w.getType() != AccessibilityWindowInfo.TYPE_SYSTEM) continue;
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null || !SYSTEMUI.contentEquals(root.getPackageName())) continue;
            if (!root.findAccessibilityNodeInfosByViewId(DIALOG_TITLE_ID).isEmpty()) return root;
        }
        return null;
    }

    void click(AccessibilityNodeInfo dialog, String buttonId) {
        for (AccessibilityNodeInfo n : dialog.findAccessibilityNodeInfosByViewId(buttonId)) {
            AccessibilityNodeInfo t = n;
            while (t != null && !t.isClickable()) t = t.getParent();
            if (t != null) {
                boolean ok = t.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                Log.i(TAG, "clicked " + buttonId + ": " + ok);
                return;
            }
        }
        Log.w(TAG, "dialog up but no " + buttonId + " yet");
    }

    void setStatus(String s) {
        if (!s.equals(status)) Log.i(TAG, "status: " + s);
        status = s;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Fast path for the dialog appearing; skip the window scan when there's nothing to do.
        if (!running || closed == null || !rd.isKnown()) return;
        boolean recent = SystemClock.uptimeMillis() - lastRequest < DIALOG_WAIT_MS;
        if (closed ? !rd.isRearActive() : rd.hasOwnRequest() || recent) h.post(reconcile);
    }

    // Turning the service off can't end our request: once unbound, Android blocks the trampoline
    // ("Background activity launch blocked"). MainActivity releases it instead (see there).

    @Override
    public void onDestroy() {
        stop();
        super.onDestroy();
    }

    void stop() {
        if (!running) return;
        Log.i(TAG, "service stopped");
        running = false;
        h.removeCallbacks(reconcile);
        if (sm != null) sm.unregisterListener(this);
        if (rd != null) rd.removeListener(reconcile);
        if (rotation != null) rotation.setActive(false);
        status = null;
        hallClosed = null;
    }

    @Override
    public void onAccuracyChanged(Sensor s, int accuracy) {}

    @Override
    public void onInterrupt() {}
}

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
 * dialog, so the service only answers the dialog of a request it made itself. The requests it
 * ends are its own, and any it found active while the phone was closed: that one is a leftover
 * which nothing else would end (see {@link RearDisplay#adopt}). A request another app makes while
 * the phone is open is left alone, with one exception: the first one made within
 * {@link #REREQUEST_MS} of the service ending a request on open is ended too, once (see
 * {@link Curtain}). That one is Pixel Camera wanting back the mode it was started in.
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
    /** Another app's request this soon after our cancel on open is a reflex, not the user's choice. */
    static final long REREQUEST_MS = 3_000;

    /** What the service is doing, for the status screen; null while it isn't running. */
    static volatile String status;
    /** Last hall reading while the service runs, else null; lets the trampoline drop stale requests. */
    static volatile Boolean hallClosed;
    /**
     * Test override: the hall reading the service acts on instead of the sensor's, or null (see
     * {@link HallOverride}). Never stored; gone when the service stops or connects again.
     */
    static volatile Boolean simulatedHall;
    /** The running service, for {@link HallOverride}. Main thread only, like the service itself. */
    private static FoldService instance;

    final Handler h = new Handler(Looper.getMainLooper());
    final Runnable reconcile = this::reconcile;

    SensorManager sm;
    RearDisplay rd;
    RotationFix rotation;
    boolean running;
    /** The hall reading being acted on: the sensor's, or the simulated one. */
    Boolean closed;
    /** The sensor's last reading, also kept up to date while a simulated one is in use. */
    Boolean sensorClosed;
    long lastLaunch, lastRequest;
    int tries;
    /** Real time of the first cancel sent since the last fold or unfold, 0 if there was none. */
    long cancelSentAt;
    /** The one follow-up of this opening is used up (see {@link Curtain}). */
    boolean followedUp;
    /** The last of another app's requests that was looked at (see {@link RearDisplay#activations}). */
    int otherSeen;

    @Override
    protected void onServiceConnected() {
        running = true;
        instance = this;
        if (simulatedHall != null) {
            // Left over from an earlier connection that wasn't stopped: each one starts on the sensor.
            Log.i(TAG, "hall simulation off: service connected again");
            simulatedHall = null;
            closed = hallClosed = null;  // the sensor's reading, sent again on registering, applies
        }
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
        boolean changed = sensorClosed == null || sensorClosed != c;
        sensorClosed = c;
        if (simulatedHall == null) {
            onHall(c);
        } else if (changed) {
            Log.i(TAG, "hall sensor says " + name(c) + ", ignored: simulating " + name(simulatedHall));
        }
    }

    /** A hall reading to act on, from the sensor or simulated. */
    void onHall(boolean c) {
        if (closed != null && closed == c) return;
        Log.i(TAG, "hall " + name(c) + (simulatedHall != null ? " (simulated)" : ""));
        closed = hallClosed = c;
        lastLaunch = 0;
        tries = 0;
        cancelSentAt = 0;
        followedUp = false;
        otherSeen = 0;
        reconcile();
    }

    /**
     * Test hook behind {@link HallOverride}: makes the running service act on a simulated hall
     * reading, or (null) on the sensor again, whose current reading then applies at once. Returns
     * what happened, for the caller's output.
     */
    static String simulateHall(Boolean value) {
        FoldService s = instance;
        // Without a sensor reading there would be nothing true to go back to.
        String refused = s == null ? "the service isn't running"
                : s.sensorClosed == null ? "no reading from the hall sensor yet" : null;
        if (refused != null) {
            Log.w(TAG, "hall simulation ignored: " + refused);
            return "ignored: " + refused;
        }
        simulatedHall = value;
        String result = (value != null ? "simulating hall " + name(value) : "hall simulation off")
                + ", sensor says " + name(s.sensorClosed);
        Log.i(TAG, result);
        s.onHall(value != null ? value : s.sensorClosed);
        return result;
    }

    static String name(boolean closed) {
        return closed ? "closed" : "open";
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
                // Another app's request can't be meant for a closed phone, and it never ends by
                // itself: take it over, so that opening the phone ends it like one of ours.
                if (!ours) rd.adopt();
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
            // Rear display back on through another app's request, and the last time it went off was
            // the doing of a cancel sent since the phone was opened.
            long cancelled = rd.endingCancelAt();
            if (rear && !ours && cancelSentAt != 0 && cancelled >= cancelSentAt
                    && otherSeen != rd.activations()) {
                followUp(cancelled);
            }
            // Also look for a dialog shortly after our last request: one can still pop up after
            // ownership was dropped, and tapping "Switch" there would strand the phone in
            // rear-display mode while open.
            boolean recent = now - lastRequest < DIALOG_WAIT_MS;
            // Nothing of ours to undo. A request another app made while the phone is open (the
            // real selfie mode) is left alone, apart from the one followUp() deals with.
            if (!ours && !recent) {
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

    /**
     * Another app got rear-display mode after a cancel of ours, made at real time
     * {@code cancelled}, ended it on open. If that was the first time and within
     * {@link #REREQUEST_MS} of that cancel, it is the app on screen asking again for the mode it
     * was started in (Pixel Camera does): end that request too, once, behind the curtain. Anything
     * later or repeated is the app's business, and is only logged. Real time, not uptime, so that
     * sleeping doesn't stretch the window.
     */
    void followUp(long cancelled) {
        otherSeen = rd.activations();
        long after = SystemClock.elapsedRealtime() - cancelled;
        String what = "another app turned rear display on " + after + " ms after our cancel: ";
        if (followedUp || after >= REREQUEST_MS) {
            Log.i(TAG, what + "left alone");
            return;
        }
        followedUp = true;
        Log.i(TAG, what + "ending that once");
        Curtain.start(this, cancelled + REREQUEST_MS, otherSeen);
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
        } else if (cancelSentAt == 0) {
            cancelSentAt = SystemClock.elapsedRealtime();
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
        if (instance == this) {
            instance = null;
            if (simulatedHall != null) Log.i(TAG, "hall simulation off: service stopped");
            simulatedHall = null;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor s, int accuracy) {}

    @Override
    public void onInterrupt() {}
}

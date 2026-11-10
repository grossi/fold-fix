package app.foldfix;

import android.accessibilityservice.AccessibilityService;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * The fix itself. On a Pixel Fold with a dead hinge-angle sensor Android is stuck in the OPENED
 * state, so closing the phone leaves the (folded-away) inner screen on and the outer one off.
 * This service watches the hall sensor instead and keeps "closed ⇔ rear-display mode" true:
 * closed → request rear-display mode (outer screen on), open → end our request.
 *
 * <p>Each request makes SystemUI ask "Switch screens?" on the inner screen, which is folded shut
 * at that moment, so the service answers the dialog itself. Rather than trusting single events,
 * it reconciles hall vs. device state every {@link Reconciler#POLL_MS} until they agree. What it
 * does at each step is decided by {@link Reconciler}, which holds the rules; this class reads the
 * sensor, finds and taps the dialog, and starts the activities.
 *
 * <p>It never fights other apps: Pixel Camera's rear-screen selfie mode uses the same state and
 * dialog, so the service only answers the dialog of a request it made itself. The requests it
 * ends are its own, and any it found active while the phone was closed: that one is a leftover
 * which nothing else would end (see {@link RearDisplay#adopt}). A request another app makes while
 * the phone is open is left alone, with one exception: the first one made within
 * {@link Reconciler#REREQUEST_MS} of the service ending a request on open is ended too, once (see
 * {@link Curtain}). That one is Pixel Camera wanting back the mode it was started in.
 *
 * <p>While the phone is closed on the outer screen, {@link RotationFix} keeps landscape the right
 * way up (rear-display mode turns it upside down).
 */
public class FoldService extends AccessibilityService implements SensorEventListener, Reconciler.Phone {
    static final String SYSTEMUI = "com.android.systemui";
    static final String DIALOG_TITLE_ID = SYSTEMUI + ":id/rear_display_title_text_view";
    /** Dialog buttons by ID, not label, so it works in every language. */
    static final String CONFIRM_ID = "android:id/button1", CANCEL_ID = "android:id/button2";

    /** What the service is doing, for the status screen; null while it isn't running. */
    static volatile String status;
    /** Last hall reading while the service runs, else null; lets a late trampoline or curtain stand down. */
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
    /**
     * The rules, and what they keep between steps. Made at the first connection and kept if the
     * system connects this instance again.
     */
    Reconciler rules;
    boolean running;
    /** The hall reading being acted on: the sensor's, or the simulated one. */
    Boolean closed;
    /** The sensor's last reading, also kept up to date while a simulated one is in use. */
    Boolean sensorClosed;

    @Override
    protected void onServiceConnected() {
        running = true;
        instance = this;
        if (simulatedHall != null) {
            // Left over from an earlier connection that wasn't stopped: each one starts on the sensor.
            FoldLog.i("hall simulation off: service connected again");
            simulatedHall = null;
        }
        // Nothing is kept from an earlier connection of this instance. The sensor sends its
        // reading again on registering, and that first reading resets the rest (see onHall).
        closed = sensorClosed = hallClosed = null;
        rd = RearDisplay.get(this);
        if (rules == null) rules = new Reconciler(rd, this);
        rd.addListener(reconcile);
        // Likewise: the old helper would keep its overlay, listeners and receiver.
        if (rotation != null) rotation.setActive(false);
        rotation = new RotationFix(this);
        sm = getSystemService(SensorManager.class);
        sm.unregisterListener(this);  // in case the system connects the same instance twice
        Sensor hall = Hardware.findHallSensor(sm);
        if (hall == null) {
            setStatus("No hall sensor found: this phone is not supported");
        } else {
            boolean ok = sm.registerListener(this, hall, SensorManager.SENSOR_DELAY_NORMAL);
            FoldLog.i("service connected, hall sensor \"" + hall.getName() + "\" registered=" + ok
                    + ", backend " + rd.backend());
            setStatus("Waiting for the hall sensor and device state");
        }
        // Checked now, while no screen switch is in progress (the display list is briefly
        // incomplete during one).
        FoldLog.i("outer screen present " + Hardware.outerDisplayPresent(this)
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
            FoldLog.i("hall sensor says " + name(c) + ", ignored: simulating " + name(simulatedHall));
        }
    }

    /** A hall reading to act on, from the sensor or simulated. */
    void onHall(boolean c) {
        if (closed != null && closed == c) return;
        FoldLog.i("hall " + name(c) + (simulatedHall != null ? " (simulated)" : ""));
        closed = hallClosed = c;
        rules.onFold();
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
            FoldLog.w("hall simulation ignored: " + refused);
            return "ignored: " + refused;
        }
        simulatedHall = value;
        String result = (value != null ? "simulating hall " + name(value) : "hall simulation off")
                + ", sensor says " + name(s.sensorClosed);
        FoldLog.i(result);
        s.onHall(value != null ? value : s.sensorClosed);
        return result;
    }

    static String name(boolean closed) {
        return closed ? "closed" : "open";
    }

    /** One step towards "closed ⇔ rear display"; re-arms itself until that holds (see {@link Reconciler}). */
    void reconcile() {
        h.removeCallbacks(reconcile);
        if (running) rules.reconcile(closed);
    }

    // ---- what the rules read and do (Reconciler.Phone) -------------------------------------

    @Override
    public long uptime() {
        return SystemClock.uptimeMillis();
    }

    @Override
    public long realtime() {
        return SystemClock.elapsedRealtime();
    }

    @Override
    public boolean outerDisplayPresent() {
        return Hardware.outerDisplayPresent(this);
    }

    @Override
    public boolean tapDialog(boolean confirm) {
        AccessibilityNodeInfo dialog = findDialog();
        if (dialog != null) click(dialog, confirm ? CONFIRM_ID : CANCEL_ID);
        return dialog != null;
    }

    @Override
    public void launch(String action) {
        if (Trampoline.REQUEST.equals(action)) rd.markRequestPending();
        FoldLog.i(action + " rear display (try " + rules.tries + ", " + rd.describeState() + ")");
        Trampoline.start(this, action);
    }

    @Override
    public void startCurtain(long deadline, int activation) {
        Curtain.start(this, deadline, activation);
    }

    @Override
    public void setLandscapeFix(boolean active) {
        rotation.setActive(active);
    }

    @Override
    public void setStatus(String s) {
        if (!s.equals(status)) FoldLog.i("status: " + s);
        status = s;
    }

    @Override
    public void log(String line) {
        FoldLog.i(line);
    }

    @Override
    public void lookAgainIn(long ms) {
        h.postDelayed(reconcile, ms);
    }

    // ---- the dialog ------------------------------------------------------------------------

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
                FoldLog.i("clicked " + buttonId + ": " + ok);
                return;
            }
        }
        FoldLog.w("dialog up but no " + buttonId + " yet");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Fast path for the dialog appearing; skip the window scan when there's nothing to do.
        if (running && rules.dialogMatters(closed)) h.post(reconcile);
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
        FoldLog.i("service stopped");
        running = false;
        h.removeCallbacks(reconcile);
        if (sm != null) sm.unregisterListener(this);
        if (rd != null) rd.removeListener(reconcile);
        if (rotation != null) rotation.setActive(false);
        // The statics belong to the connected instance: an old one, stopped late, leaves them alone.
        if (instance == this) {
            instance = null;
            status = null;
            hallClosed = null;
            if (simulatedHall != null) FoldLog.i("hall simulation off: service stopped");
            simulatedHall = null;
        }
    }

    /**
     * The history without the status screen, for a phone whose screen can't be looked at:
     * {@code adb shell dumpsys activity service app.foldfix/.FoldService}. Prints which build this
     * is, what the service is doing, and the history. Only while the service is on, and only for
     * holders of {@code DUMP}, in practice the adb shell.
     */
    @Override
    protected void dump(FileDescriptor fd, PrintWriter out, String[] args) {
        String s = status;
        out.println("FoldFix " + App.describeBuild(this));
        out.println("Status: " + (s != null ? s : "service not connected"));
        out.println("Recent activity (in memory only, since FoldFix last started; newest last):");
        out.print(FoldLog.text(FoldLog.CAPACITY));
    }

    @Override
    public void onAccuracyChanged(Sensor s, int accuracy) {}

    @Override
    public void onInterrupt() {}
}

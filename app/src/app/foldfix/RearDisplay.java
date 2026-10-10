package app.foldfix;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Starts/ends rear-display mode (outer screen on, inner screen off) and reports whether it is
 * active. FoldFix uses this mode as "closed", because with a dead hinge sensor Android never
 * leaves the OPENED state on its own.
 *
 * <p>Two backends, tried in this order:
 * <ol>
 * <li>The system's WindowManager extensions library ({@code androidx.window.extensions}, the
 *     implementation behind Jetpack WindowManager's rear-display mode). It is loaded from the
 *     system partition, so its code runs in the platform hidden-API domain and may call
 *     DeviceStateManager for us: no adb setup needed.
 * <li>The hidden {@code DeviceStateManager} API, called directly. Needs the one-time
 *     {@link #ADB_COMMAND}.
 * </ol>
 *
 * <p>How the system treats requests (Android 17, Pixel Fold), which shapes this class:
 * <ul>
 * <li>A new request waits for SystemUI's "Switch screens?" dialog. While it waits, cancelling it
 *     through the API does nothing; only the dialog's buttons resolve it.
 * <li>An active request is cancelled by any caller that is the top app, whoever made it.
 * <li>An active request outlives the process that made it ("sticky") until the device reaches
 *     CLOSED, which a dead hinge sensor never reports.
 * </ul>
 *
 * <p>One instance per process, shared by the service and the trampoline activity: the extension
 * keeps per-process session state. Everything runs on the main thread; listeners are posted.
 */
final class RearDisplay {
    static final String TAG = "FoldFix";
    static final String ADB_COMMAND =
            "adb shell settings put global hidden_api_blacklist_exemptions Landroid/hardware/devicestate/";

    /** Pixel Fold's classic rear-display state: outer screen on, inner screen off. */
    static final int STATE_REAR_DISPLAY = 3;
    /** Rear-display "v2" state: outer screen is the default, inner stays on with a prompt. */
    static final int STATE_REAR_DISPLAY_OUTER_DEFAULT = 5;
    /** A request still waiting for its dialog after this long is considered lost and redone. */
    static final long STALE_SESSION_MS = 10_000;

    // WindowAreaComponent values (stable extensions API).
    static final int STATUS_UNAVAILABLE = 1, STATUS_ACTIVE = 3;
    static final int SESSION_INACTIVE = 0;

    private static RearDisplay instance;

    static synchronized RearDisplay get(Context context) {
        if (instance == null) instance = new RearDisplay(context.getApplicationContext());
        return instance;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final SharedPreferences prefs;

    // Extension backend.
    private Object area;           // androidx.window.extensions.area.WindowAreaComponent
    private Class<?> extConsumer;  // androidx.window.extensions.core.util.function.Consumer
    private volatile int extStatus = -1;

    // Hidden-API backend.
    private final Object dsm;      // android.hardware.devicestate.DeviceStateManager
    private Boolean hiddenApi;     // null = not checked yet
    private boolean hiddenWatched;
    private volatile int hiddenState = -1;

    /** FoldService is about to request, or this process's request is pending or active. */
    private volatile boolean ownRequest;
    /**
     * A request made by an earlier FoldFix process may still be active (requests are sticky,
     * see above), so ownership is persisted.
     */
    private volatile boolean inherited;
    /** Uptime when this process's current request was accepted, 0 if there is none. */
    private volatile long sessionStart;
    /** The current request has been activated (the dialog was confirmed). */
    private volatile boolean sessionActive;
    /** Bumped per request, so a replaced request's "ended" callback can't clear the new one. */
    private volatile int generation;

    private RearDisplay(Context app) {
        dsm = app.getSystemService("device_state");
        prefs = app.createDeviceProtectedStorageContext().getSharedPreferences("state", Context.MODE_PRIVATE);
        inherited = prefs.getBoolean("request_active", false);
        initExtension();
        if (area == null) watchHiddenState();
    }

    // ---- state ----------------------------------------------------------------------------

    /** Backend in use, or null if none works (then {@link #ADB_COMMAND} is needed). */
    String backend() {
        if (area != null) return "window extensions";
        if (hiddenWatched) return "hidden API";
        return null;
    }

    boolean usesExtension() {
        return area != null;
    }

    /** Whether the current device state is known yet (don't act before it is). */
    boolean isKnown() {
        return area != null ? extStatus >= 0 : hiddenState >= 0;
    }

    /** Whether rear-display mode is active, no matter who requested it. */
    boolean isRearActive() {
        if (area != null) return extStatus == STATUS_ACTIVE;
        return hiddenState == STATE_REAR_DISPLAY || hiddenState == STATE_REAR_DISPLAY_OUTER_DEFAULT;
    }

    /** False when the system currently refuses rear-display mode (e.g. battery saver, heat). */
    boolean isAvailable() {
        return area == null || extStatus != STATUS_UNAVAILABLE;
    }

    /** Whether the active or pending rear-display request (if any) is FoldFix's. */
    boolean hasOwnRequest() {
        return ownRequest || inherited;
    }

    /** Our request is active, or still waiting for its dialog and not yet given up on. */
    boolean isSessionLive() {
        long start = sessionStart;
        return start != 0 && (sessionActive || SystemClock.uptimeMillis() - start < STALE_SESSION_MS);
    }

    /** Called by FoldService right before it launches the trampoline to request. */
    void markRequestPending() {
        setOwn(true);
    }

    /** Called when a request intent turns out to be stale (the phone was opened meanwhile). */
    void dropPendingMark() {
        if (sessionStart == 0) {
            ownRequest = false;
            save();
        }
    }

    /** Human-readable device state, best effort (for the status screen and logs). */
    String describeState() {
        if (area != null) {
            String id = extensionDeviceState();
            String mode = extStatus < 0 ? "unknown" : isRearActive() ? "rear display on" : "rear display off";
            return id != null ? id + " (" + mode + ")" : mode;
        }
        return hiddenState < 0 ? "unknown" : String.valueOf(hiddenState);
    }

    void addListener(Runnable r) {
        if (!listeners.contains(r)) listeners.add(r);
    }

    void removeListener(Runnable r) {
        listeners.remove(r);
    }

    // ---- actions (called from the trampoline, which is the top app) ------------------------

    /**
     * Asks for rear-display mode. The system only accepts this from the top app. SystemUI then
     * shows its "Switch screens?" dialog, which FoldService confirms.
     */
    void request(Activity activity) {
        if (!Hardware.outerDisplayPresent(activity)) {
            // Checked here too, so no caller can crash the system.
            Log.w(TAG, "outer screen not detected, not requesting rear display");
            dropPendingMark();
            return;
        }
        if (isRearActive() || isSessionLive()) {
            // Nothing to do. A second request would put another dialog up (on the outer screen if
            // it is already on) or restart a request whose switch is still under way. Ownership
            // stays with whoever has it: us if our request is outstanding or inherited.
            Log.i(TAG, "rear display already on or requested (ours: " + (sessionStart != 0 || inherited)
                    + "), not requesting again");
            dropPendingMark();
            return;
        }
        setOwn(true);
        int gen = ++generation;
        if (area != null) {
            try {
                startExtensionSession(activity, gen);
                sessionStarted();
                Log.i(TAG, "rear display requested (window extensions)");
                return;
            } catch (Throwable t) {
                Log.w(TAG, "window extensions request failed: " + unwrap(t));
                if (unwrap(t) instanceof SecurityException) {  // not top app: same for both backends
                    setOwn(false);
                    return;
                }
            }
        }
        if (hasHiddenApi()) {
            try {
                requestHidden(gen);
                sessionStarted();
                Log.i(TAG, "rear display requested (hidden API)");
                return;
            } catch (Throwable t) {
                Log.w(TAG, "hidden API request failed: " + unwrap(t));
            }
        }
        setOwn(false);
    }

    /**
     * Ends rear-display mode if it is active. Only call this for FoldFix's own request: the
     * system cancels the active request whoever made it. Shows no dialog. Returns false if the
     * system refused (caller not the top app).
     */
    boolean cancel() {
        if (!isRearActive()) {
            // Nothing active. A request still waiting for its dialog can't be cancelled through
            // the API (and the extension would lose track of it), only with the dialog's Cancel
            // button, so ownership is kept while one is outstanding.
            Log.i(TAG, "nothing active to cancel" + (sessionStart != 0 ? ", request still waiting for its dialog" : ""));
            if (sessionStart == 0) setOwn(false);
            return true;
        }
        try {
            if (area != null) {
                area.getClass().getMethod("endRearDisplaySession").invoke(area);
            } else {
                dsm.getClass().getMethod("cancelStateRequest").invoke(dsm);
            }
        } catch (Throwable t) {
            if (!(unwrap(t) instanceof IllegalStateException)) {  // ISE: no request to cancel
                Log.w(TAG, "cancel failed: " + unwrap(t));
                return false;
            }
        }
        Log.i(TAG, "rear display request cancelled");
        generation++;
        setOwn(false);
        return true;
    }

    // ---- window extensions backend ------------------------------------------------------

    private void initExtension() {
        try {
            Class<?> provider = Class.forName("androidx.window.extensions.WindowExtensionsProvider");
            Object ext = provider.getMethod("getWindowExtensions").invoke(null);
            Object a = ext.getClass().getMethod("getWindowAreaComponent").invoke(ext);
            if (a == null) {
                Log.i(TAG, "window extensions: no WindowAreaComponent");
                return;
            }
            extConsumer = Class.forName("androidx.window.extensions.core.util.function.Consumer",
                    false, a.getClass().getClassLoader());
            Method add = a.getClass().getMethod("addRearDisplayStatusListener", extConsumer);
            area = a;
            preferClassicRearState();
            Object listener = consumer(v -> onExtensionStatus((Integer) v));
            // Posted on purpose: the extension's first callbacks (queued on the main thread while it
            // was created) report "available" before it knows the device state. Added after them,
            // the listener gets the correct status right away.
            main.post(() -> {
                try {
                    add.invoke(area, listener);
                } catch (Throwable t) {
                    Log.e(TAG, "could not watch rear display status, falling back", unwrap(t));
                    area = null;
                    watchHiddenState();
                    notifyListeners();
                }
            });
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            Log.i(TAG, "window extensions not available");
        } catch (Throwable t) {
            Log.w(TAG, "window extensions unusable", unwrap(t));
            area = null;
        }
    }

    /**
     * Since Android 16 the extension picks REAR_DISPLAY_OUTER_DEFAULT ("rear display v2"), which
     * keeps the inner screen on with a "Slide to use inner screen" prompt: wrong for a closed
     * phone. Point it at the classic rear-display state (outer on, inner off) instead, using the
     * extension's own lookup. These are private members of a system library, so failure is
     * tolerated: the request then still works, just in v2 mode.
     */
    private void preferClassicRearState() {
        try {
            Class<?> c = area.getClass();
            Field state = c.getDeclaredField("mRearDisplayState");
            state.setAccessible(true);
            int classic = STATE_REAR_DISPLAY;
            try {
                Field supported = c.getDeclaredField("mCurrentSupportedDeviceStates");
                Method v1 = c.getDeclaredMethod("getRdmV1Identifier", List.class);
                supported.setAccessible(true);
                v1.setAccessible(true);
                classic = (Integer) v1.invoke(null, supported.get(area));
            } catch (Throwable ignored) {
                // Other library version: keep the Pixel Fold default.
            }
            int current = state.getInt(area);
            if (classic >= 0 && classic != current) state.setInt(area, classic);
            Log.i(TAG, "window extensions ready, rear state " + current + " -> " + state.getInt(area));
        } catch (Throwable t) {
            Log.w(TAG, "window extensions ready, could not select the classic rear state: " + t);
        }
    }

    private void onExtensionStatus(int s) {
        if (s != extStatus) Log.i(TAG, "rear display status " + s + ", state " + extensionDeviceState());
        extStatus = s;
        onStateChanged();
    }

    private void startExtensionSession(Activity activity, int gen) throws Exception {
        Method start = area.getClass().getMethod("startRearDisplaySession", Activity.class, extConsumer);
        Object session = consumer(v -> {
            Log.i(TAG, "rear display session " + v);
            if ((Integer) v == SESSION_INACTIVE) requestEnded(gen);
            else requestActivated(gen);
        });
        try {
            start.invoke(area, activity, session);
        } catch (InvocationTargetException e) {
            // The extension allows one session per process; a lost one (still waiting for a
            // dialog that never came, see STALE_SESSION_MS) is ended and replaced.
            if (!(e.getCause() instanceof IllegalStateException)) throw e;
            area.getClass().getMethod("endRearDisplaySession").invoke(area);
            start.invoke(area, activity, session);
        }
    }

    /** Wraps a lambda in the extension's own Consumer interface. */
    private Object consumer(Consumer<Object> body) {
        return Proxy.newProxyInstance(extConsumer.getClassLoader(), new Class<?>[]{extConsumer},
                (proxy, m, args) -> {
                    if (m.getName().equals("accept")) {
                        main.post(() -> body.accept(args[0]));
                        return null;
                    }
                    return objectMethod(proxy, m.getName(), args);
                });
    }

    private static final Pattern STATE_ID = Pattern.compile("identifier=(\\d+), name='([^']*)'");

    /** The extension's view of the device state, e.g. "3 REAR_DISPLAY_STATE"; diagnostics only. */
    private String extensionDeviceState() {
        try {
            Field f = area.getClass().getDeclaredField("mCurrentDeviceState");
            f.setAccessible(true);
            Matcher m = STATE_ID.matcher(String.valueOf(f.get(area)));
            return m.find() ? m.group(1) + " " + m.group(2) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- hidden DeviceStateManager backend ----------------------------------------------

    /** True if the hidden-API exemption is set (checked once: it only changes on process start). */
    synchronized boolean hasHiddenApi() {
        if (hiddenApi == null) {
            try {
                dsm.getClass().getMethod("cancelStateRequest");
                hiddenApi = true;
            } catch (Throwable t) {
                hiddenApi = false;
            }
        }
        return hiddenApi;
    }

    private void requestHidden(int gen) throws Exception {
        Class<?> req = Class.forName("android.hardware.devicestate.DeviceStateRequest");
        Object builder = req.getMethod("newBuilder", int.class).invoke(null, STATE_REAR_DISPLAY);
        Object request = builder.getClass().getMethod("build").invoke(builder);
        Class<?> cbType = Class.forName("android.hardware.devicestate.DeviceStateRequest$Callback");
        Object cb = Proxy.newProxyInstance(cbType.getClassLoader(), new Class<?>[]{cbType},
                (proxy, m, args) -> {
                    if (m.getName().equals("onRequestCanceled")) main.post(() -> requestEnded(gen));
                    if (m.getName().equals("onRequestActivated")) main.post(() -> requestActivated(gen));
                    return objectMethod(proxy, m.getName(), args);
                });
        dsm.getClass().getMethod("requestState", req, Executor.class, cbType)
                .invoke(dsm, request, (Executor) main::post, cb);
    }

    /** Tracks the committed device state through the hidden DeviceStateCallback. */
    private void watchHiddenState() {
        if (!hasHiddenApi()) return;
        try {
            Class<?> cbType = Class.forName(
                    "android.hardware.devicestate.DeviceStateManager$DeviceStateCallback");
            Object cb = Proxy.newProxyInstance(cbType.getClassLoader(), new Class<?>[]{cbType},
                    (proxy, m, args) -> {
                        if (m.getName().equals("onDeviceStateChanged") || m.getName().equals("onStateChanged")) {
                            Object s = args[0];
                            int id = s instanceof Integer ? (Integer) s
                                    : (Integer) s.getClass().getMethod("getIdentifier").invoke(s);
                            main.post(() -> {
                                hiddenState = id;
                                Log.i(TAG, "device state " + id);
                                onStateChanged();
                            });
                        }
                        return objectMethod(proxy, m.getName(), args);
                    });
            dsm.getClass().getMethod("registerCallback", Executor.class, cbType)
                    .invoke(dsm, (Executor) main::post, cb);
            hiddenWatched = true;
        } catch (Throwable t) {
            Log.e(TAG, "could not watch device state", unwrap(t));
        }
    }

    // ---- helpers ------------------------------------------------------------------------

    private void onStateChanged() {
        if (inherited && !ownRequest && !isRearActive()) {
            Log.i(TAG, "earlier request is gone");
            setOwn(false);  // rear display is off, so whatever we requested before has ended
        }
        notifyListeners();
    }

    private void sessionStarted() {
        sessionStart = SystemClock.uptimeMillis();
        sessionActive = false;
    }

    private void requestActivated(int gen) {
        if (gen == generation && sessionStart != 0) sessionActive = true;
    }

    private void requestEnded(int gen) {
        if (gen == generation) setOwn(false);
    }

    private void setOwn(boolean own) {
        ownRequest = own;
        if (!own) {
            inherited = false;
            sessionStart = 0;
            sessionActive = false;
        }
        save();
    }

    private void save() {
        boolean active = ownRequest || inherited;
        if (prefs.getBoolean("request_active", false) != active) {
            prefs.edit().putBoolean("request_active", active).apply();
        }
        notifyListeners();
    }

    private void notifyListeners() {
        for (Runnable r : listeners) main.post(r);
    }

    private static Object objectMethod(Object proxy, String name, Object[] args) {
        switch (name) {
            case "hashCode": return System.identityHashCode(proxy);
            case "equals": return proxy == args[0];
            case "toString": return "FoldFixCallback";
            default: return null;
        }
    }

    private static Throwable unwrap(Throwable t) {
        return t instanceof InvocationTargetException && t.getCause() != null ? t.getCause() : t;
    }
}

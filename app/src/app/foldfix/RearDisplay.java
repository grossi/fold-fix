package app.foldfix;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
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
 * <li>In CLOSED the outer screen is on without any request, and a new request gets an "Unfold
 *     phone" dialog that has no Confirm button: FoldFix makes none there ({@link #isStateClosed}).
 * </ul>
 *
 * <p>So an active request only ends when someone cancels it. FoldFix does that for its own
 * requests and for those it {@linkplain #adopt adopted}. The only other one it ends by itself is
 * the one {@link Curtain} is for; the user can end any with a button on the status screen
 * ({@link #cancelForUser}). One more can be replaced without a cancel: a request that became active
 * while FoldFix's own was waiting for its dialog gives way when FoldService confirms that dialog.
 *
 * <p>One instance per process, shared by the service and the trampoline activity: the extension
 * keeps per-process session state. Everything runs on the main thread; listeners are posted.
 */
final class RearDisplay implements Reconciler.Rear {
    static final String ADB_COMMAND =
            "adb shell settings put global hidden_api_blacklist_exemptions Landroid/hardware/devicestate/";

    /** Pixel Fold's classic rear-display state: outer screen on, inner screen off. */
    static final int STATE_REAR_DISPLAY = 3;
    /** Rear-display "v2" state: outer screen is the default, inner stays on with a prompt. */
    static final int STATE_REAR_DISPLAY_OUTER_DEFAULT = 5;
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
    /** The device state's identifier as of the last status callback, -1 if it couldn't be read. */
    private volatile int extState = -1;

    // Hidden-API backend.
    private final Object dsm;      // android.hardware.devicestate.DeviceStateManager
    private Boolean hiddenApi;     // null = not checked yet
    private boolean hiddenWatched;
    private volatile int hiddenState = -1;

    /** The device states Android itself counts as folded: on the Pixel Fold, CLOSED (0) alone. */
    private final int[] foldedStates = readFoldedStates();

    /**
     * Whose request it is, and how far this process's own has got. What it calls ours is saved,
     * because a request that FoldFix has to end outlives this process (requests are sticky, see
     * above).
     */
    private final Ownership own;

    private RearDisplay(Context app) {
        dsm = app.getSystemService("device_state");
        prefs = app.createDeviceProtectedStorageContext().getSharedPreferences("state", Context.MODE_PRIVATE);
        own = new Ownership(prefs.getBoolean("request_active", false), this::save);
        initExtension();
        if (area == null) watchHiddenState();
    }

    // ---- state ----------------------------------------------------------------------------

    /** Backend in use, or null if none works (then {@link #ADB_COMMAND} is needed). */
    @Override
    public String backend() {
        if (area != null) return "window extensions";
        if (hiddenWatched) return "hidden API";
        return null;
    }

    boolean usesExtension() {
        return area != null;
    }

    /** Whether the current device state is known yet (don't act before it is). */
    @Override
    public boolean isKnown() {
        return area != null ? extStatus >= 0 : hiddenState >= 0;
    }

    /** Whether rear-display mode is active, no matter who requested it. */
    @Override
    public boolean isRearActive() {
        if (area != null) return extStatus == STATUS_ACTIVE;
        return hiddenState == STATE_REAR_DISPLAY || hiddenState == STATE_REAR_DISPLAY_OUTER_DEFAULT;
    }

    /**
     * Counts how often rear-display mode has turned on (1 or more while it is on). Unchanged means
     * it didn't go off in between; a request replaced by another without that doesn't show.
     */
    @Override
    public int activations() {
        return own.activations();
    }

    /**
     * If rear-display mode last went off through a {@link #cancel} made in this process: when that
     * cancel was made ({@code SystemClock.elapsedRealtime()}, taken before the call). 0 if
     * something else ended it, the user's {@link #cancelForUser} included, or if it hasn't been
     * seen going off. The time of the cancel, not of the callback, which can be late: so nothing
     * counted from it can run longer than meant.
     */
    @Override
    public long endingCancelAt() {
        return own.endingCancelAt();
    }

    /**
     * A {@link #cancel} made in this process is under way: it was sent less than
     * {@link Ownership#CANCEL_EFFECT_MS} ago, and rear-display mode hasn't been seen going off
     * since. Main thread only.
     */
    @Override
    public boolean isCancelPending() {
        return own.isCancelPending(SystemClock.elapsedRealtime());
    }

    /** False when the system currently refuses rear-display mode (e.g. battery saver, heat). */
    @Override
    public boolean isAvailable() {
        return area == null || extStatus != STATUS_UNAVAILABLE;
    }

    /**
     * Whether Android itself has the phone in a folded state (CLOSED on the Pixel Fold): it has
     * then switched to the outer screen by itself, and FoldFix has nothing to ask for. A phone
     * with a dead hinge sensor never gets there; one that works is there whenever it is shut.
     *
     * <p>Which states are folded is Android's own list ({@code config_foldedDeviceStates}), not a
     * number written here. The state comes from the hidden API's callback, or, with the
     * extension, from a private field of the library (its stable status reads "available" in
     * CLOSED as in OPENED). Whatever can't be read counts as not closed, and FoldFix then behaves
     * as if this check weren't there: a wrong "closed" would leave a phone with a dead sensor
     * without its outer screen.
     */
    @Override
    public boolean isStateClosed() {
        int state = area != null ? extState : hiddenState;
        for (int folded : foldedStates) {
            if (state == folded) return true;
        }
        return false;
    }

    /** That list, for the log. */
    String describeFoldedStates() {
        return Arrays.toString(foldedStates);
    }

    /** {@code config_foldedDeviceStates}, a framework setting; empty if Android doesn't have it. */
    private static int[] readFoldedStates() {
        try {
            Resources r = Resources.getSystem();
            int id = r.getIdentifier("config_foldedDeviceStates", "array", "android");
            return id == 0 ? new int[0] : r.getIntArray(id);
        } catch (RuntimeException e) {
            return new int[0];
        }
    }

    /** Whether the active or pending rear-display request (if any) is FoldFix's to end. */
    @Override
    public boolean hasOwnRequest() {
        return own.hasOwnRequest();
    }

    /** Our request is active, or still waiting for its dialog and not yet given up on. */
    @Override
    public boolean isSessionLive() {
        return own.isSessionLive(SystemClock.uptimeMillis());
    }

    /** Our request was accepted and is still waiting for its dialog, and not yet given up on. */
    @Override
    public boolean isSessionWaiting() {
        return own.isSessionWaiting(SystemClock.uptimeMillis());
    }

    /** Called by FoldService right before it launches the trampoline to request. */
    void markRequestPending() {
        own.markRequestPending();
    }

    /** Called when a request intent turns out to be stale (the phone was opened meanwhile). */
    void dropPendingMark() {
        own.dropPendingMark();
    }

    /**
     * Makes the active request FoldFix's to end, like one of its own, if it isn't already. Called
     * by FoldService when it finds another app's request while the phone is closed: that app has
     * no use for it there, and nothing else would ever end it (requests are sticky, see above).
     * Returns false if that has to wait: a request of ours has just ended or been cancelled, and
     * what looks active is most likely that one (see {@link Ownership#isEnding} for how long).
     */
    @Override
    public boolean adopt() {
        long now = SystemClock.elapsedRealtime();
        if (own.canAdopt(isRearActive(), now)) {
            FoldLog.i("adopting the active rear display request (" + describeState() + ")");
            own.adopt();
        }
        return !own.isEnding(now);
    }

    /**
     * The same when the phone is opened with rear-display mode on: the active request was active
     * while the phone was closed, so it is FoldFix's to end, also if FoldFix's own request is
     * still waiting for its dialog behind another app's (see {@link Ownership#canAdoptOnOpen}).
     */
    @Override
    public void adoptOnOpen() {
        if (!own.canAdoptOnOpen(isRearActive())) return;
        FoldLog.i("opened with rear display on before a request of ours was reported active: "
                + "taking the active one over (" + describeState() + ")");
        own.adopt();
    }

    /** Human-readable device state, best effort (for the status screen and logs). */
    String describeState() {
        if (area != null) {
            String id = describe(extensionState());
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
            FoldLog.w("outer screen not detected, not requesting rear display");
            dropPendingMark();
            return;
        }
        if (isRearActive() || isSessionLive()) {
            // Nothing to do. A second request would put another dialog up (on the outer screen if
            // it is already on) or restart a request whose switch is still under way. Ownership
            // stays with whoever has it: us if our request is outstanding or inherited.
            FoldLog.i("rear display already on or requested (ours: " + (own.hasSession() || own.isInherited())
                    + "), not requesting again");
            dropPendingMark();
            return;
        }
        int gen = own.begin();
        if (area != null) {
            try {
                startExtensionSession(activity, gen);
                own.sessionStarted(SystemClock.uptimeMillis());
                FoldLog.i("rear display requested (window extensions)");
                return;
            } catch (Throwable t) {
                FoldLog.w("window extensions request failed: " + unwrap(t));
                if (unwrap(t) instanceof SecurityException) {  // not top app: same for both backends
                    own.clear();
                    return;
                }
            }
        }
        if (hasHiddenApi()) {
            try {
                requestHidden(gen);
                own.sessionStarted(SystemClock.uptimeMillis());
                FoldLog.i("rear display requested (hidden API)");
                return;
            } catch (Throwable t) {
                FoldLog.w("hidden API request failed: " + unwrap(t));
            }
        }
        own.clear();
    }

    /**
     * Ends rear-display mode if it is active. Only call this for a request that is FoldFix's to
     * end ({@link #hasOwnRequest}), or from {@link Curtain}: the system cancels the active request
     * whoever made it. Shows no dialog. Returns false if the system refused (caller not the top
     * app).
     */
    boolean cancel() {
        return cancel(false);
    }

    /**
     * The same for the status screen's button, where the user ends the active request, whoever
     * made it. Such a cancel isn't one {@link #endingCancelAt} reports: what another app requests
     * after it is the user's business, not something for the service to follow up on.
     */
    boolean cancelForUser() {
        return cancel(true);
    }

    private boolean cancel(boolean byUser) {
        if (!isRearActive()) {
            // Nothing active. A request still waiting for its dialog can't be cancelled through
            // the API (and the extension would lose track of it), only with the dialog's Cancel
            // button, so ownership is kept while one is outstanding.
            FoldLog.i("nothing active to cancel" + (own.hasSession() ? ", request still waiting for its dialog" : ""));
            if (!own.hasSession()) own.clear();
            return true;
        }
        long at = SystemClock.elapsedRealtime();
        try {
            if (area != null) {
                area.getClass().getMethod("endRearDisplaySession").invoke(area);
            } else {
                dsm.getClass().getMethod("cancelStateRequest").invoke(dsm);
            }
        } catch (Throwable t) {
            if (!(unwrap(t) instanceof IllegalStateException)) {  // ISE: no request to cancel
                FoldLog.w("cancel failed: " + unwrap(t));
                return false;
            }
        }
        FoldLog.i("rear display request cancelled" + (byUser ? " from the status screen" : ""));
        own.cancelled(at, byUser);
        return true;
    }

    // ---- window extensions backend ------------------------------------------------------

    private void initExtension() {
        try {
            Class<?> provider = Class.forName("androidx.window.extensions.WindowExtensionsProvider");
            Object ext = provider.getMethod("getWindowExtensions").invoke(null);
            Object a = ext.getClass().getMethod("getWindowAreaComponent").invoke(ext);
            if (a == null) {
                FoldLog.i("window extensions: no WindowAreaComponent");
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
                    FoldLog.e("could not watch rear display status, falling back", unwrap(t));
                    area = null;
                    watchHiddenState();
                    notifyListeners();
                }
            });
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            FoldLog.i("window extensions not available");
        } catch (Throwable t) {
            FoldLog.w("window extensions unusable", unwrap(t));
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
            FoldLog.i("window extensions ready, rear state " + current + " -> " + state.getInt(area));
        } catch (Throwable t) {
            FoldLog.w("window extensions ready, could not select the classic rear state: " + t);
        }
    }

    private void onExtensionStatus(int s) {
        // The library calls this for every change of the device state, also when the status stays
        // the same, as it does between OPENED and CLOSED.
        Matcher state = extensionState();
        boolean wasClosed = isStateClosed();
        extState = state != null ? Integer.parseInt(state.group(1)) : -1;
        if (s != extStatus || wasClosed != isStateClosed()) {
            FoldLog.i("rear display status " + s + ", state " + describe(state));
        }
        extStatus = s;
        onStateChanged();
    }

    private void startExtensionSession(Activity activity, int gen) throws Exception {
        Method start = area.getClass().getMethod("startRearDisplaySession", Activity.class, extConsumer);
        Object session = consumer(v -> {
            FoldLog.i("rear display session " + v);
            if ((Integer) v == SESSION_INACTIVE) own.requestEnded(gen, SystemClock.elapsedRealtime());
            else own.requestActivated(gen);
        });
        try {
            start.invoke(area, activity, session);
        } catch (InvocationTargetException e) {
            // The extension allows one session per process; a lost one (still waiting for a
            // dialog that never came, see Ownership.STALE_SESSION_MS) is ended and replaced.
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

    private static final Pattern STATE_ID = Pattern.compile("identifier=(\\d{1,9}), name='([^']*)'");

    /**
     * The extension's view of the device state, matched as identifier and name, or null. It is a
     * private field of a system library, read through its text: good for diagnostics, and for
     * {@link #isStateClosed}, where a failure only means that CLOSED isn't recognised.
     */
    private Matcher extensionState() {
        try {
            Field f = area.getClass().getDeclaredField("mCurrentDeviceState");
            f.setAccessible(true);
            Matcher m = STATE_ID.matcher(String.valueOf(f.get(area)));
            return m.find() ? m : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** For example "3 REAR_DISPLAY_STATE". */
    private static String describe(Matcher state) {
        return state != null ? state.group(1) + " " + state.group(2) : null;
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
                    if (m.getName().equals("onRequestCanceled")) {
                        main.post(() -> own.requestEnded(gen, SystemClock.elapsedRealtime()));
                    }
                    if (m.getName().equals("onRequestActivated")) main.post(() -> own.requestActivated(gen));
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
                                FoldLog.i("device state " + id);
                                onStateChanged();
                            });
                        }
                        return objectMethod(proxy, m.getName(), args);
                    });
            dsm.getClass().getMethod("registerCallback", Executor.class, cbType)
                    .invoke(dsm, (Executor) main::post, cb);
            hiddenWatched = true;
        } catch (Throwable t) {
            FoldLog.e("could not watch device state", unwrap(t));
        }
    }

    // ---- helpers ------------------------------------------------------------------------

    private void onStateChanged() {
        boolean active = isRearActive();
        own.onRearDisplay(active, SystemClock.elapsedRealtime());
        if (own.isInheritedGone(active)) {
            FoldLog.i("earlier or adopted request is gone");
            own.inheritedGone();  // rear display is off, so the request we had to end has ended
        }
        notifyListeners();
    }

    /** Run by {@link Ownership} after each change of what is ours. */
    private void save() {
        boolean active = own.hasOwnRequest();
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

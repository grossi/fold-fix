package app.foldfix;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.animation.ObjectAnimator;
import android.animation.StateListAnimator;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.RippleDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.View;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;
import java.util.function.Supplier;

/** Status and setup checklist. Everything refreshes on resume; sensors update live. */
public class MainActivity extends Activity implements SensorEventListener {
    static final long HINGE_LISTEN_MS = 3_000;
    static final String OK = "✓ ", BAD = "✗ ", WAIT = "… ";
    /** After a tap that sent a cancel, the switch button is hidden for this long: one cancel, then wait. */
    static final long SWITCH_AGAIN_MS = 2_000;
    /**
     * How many of the history's lines are shown; the Copy button takes them all. On an idle Pixel
     * Fold a refresh that renewed the lines took about 36 ms with 100 of them and 88 ms with all
     * 500, nearly all of it for laying out the text, on the main thread, which the service shares.
     */
    static final int ACTIVITY_LINES = 100;
    /**
     * The shown lines are renewed at most this often. Not with every refresh: several of those
     * come with each screen switch, when the service needs the main thread. (A screen created
     * anew by that switch shows them at once, with the rest of its first layout.)
     */
    static final long ACTIVITY_EVERY_MS = 1_000;

    final Handler h = new Handler(Looper.getMainLooper());
    final Runnable refresh = this::refresh;
    final Runnable hingeTimeout = this::hingeTimedOut;

    LinearLayout list;
    TextView serviceText, hallText, hingeText, outerText, accessText, stateText, activityText;
    View accessCommand, switchBox;
    SensorManager sm;
    Sensor hall, hinge;
    RearDisplay rd;
    boolean hingeSeen, resumed, serviceEnabled = true;
    Boolean hallClosed;
    /** {@link FoldLog#total} when the history was last put on screen, and the uptime; -1 before the first time. */
    long activityShown = -1, activityShownAt;
    /**
     * Uptime of the last tap on the switch button that sent a cancel. Static, because the switch
     * it causes recreates this screen.
     */
    static long switchedAt;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sm = getSystemService(SensorManager.class);
        hall = Hardware.findHallSensor(sm);
        hinge = sm.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE);
        rd = RearDisplay.get(this);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        list.setPadding(pad, pad, pad, pad);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        // Kept out of long screenshots ("Capture more"): Android renders those with the GPU, in
        // the app's process, even for an app that is drawn in software (see the manifest).
        scroll.setScrollCaptureHint(View.SCROLL_CAPTURE_HINT_EXCLUDE | View.SCROLL_CAPTURE_HINT_EXCLUDE_DESCENDANTS);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(i.left, i.top, i.right, i.bottom);
            return WindowInsets.CONSUMED;
        });
        setContentView(scroll);

        TextView title = text(getString(R.string.app_name), 26, true);
        list.addView(title);
        list.addView(text("Uses the hall sensor to switch to the outer screen when the phone is closed, "
                + "for Pixel Fold phones with a broken hinge sensor.", 14, false));

        serviceText = section("Accessibility service");
        // Only shown while it can help, see canSwitchToInner().
        LinearLayout switcher = new LinearLayout(this);
        switcher.setOrientation(LinearLayout.VERTICAL);
        switcher.addView(button("Switch to the inner screen", this::switchToInner));
        switcher.addView(text("The outer screen is on although the phone is open. This switches back "
                + "to the inner screen, whichever app asked for the outer one.", 13, false));
        switchBox = switcher;
        list.addView(switcher);
        list.addView(button("Open accessibility settings",
                () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        list.addView(text("If the switch is greyed out (\"restricted setting\"): open App info, "
                + "tap ⋮ → Allow restricted settings, then try again.", 13, false));
        list.addView(button("Open app info", () -> startActivity(
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", getPackageName(), null)))));

        hallText = section("Hall sensor (closed / open)");
        hingeText = section("Hinge angle sensor");
        outerText = section("Outer screen");
        accessText = section("Rear-display access");
        TextView cmd = text(RearDisplay.ADB_COMMAND, 13, false);
        cmd.setTypeface(Typeface.MONOSPACE);
        copyOnLongPress(cmd, "adb command");
        Button copy = button("Copy adb command", () -> copy("adb command", RearDisplay.ADB_COMMAND));
        LinearLayout command = new LinearLayout(this);
        command.setOrientation(LinearLayout.VERTICAL);
        command.addView(cmd);
        command.addView(copy);
        accessCommand = command;
        list.addView(command);
        stateText = section("Device state");
        // The line to copy into a bug report.
        TextView version = section("Version");
        version.setText(App.describeBuild(this));
        copyOnLongPress(version, "FoldFix version");
        // Last, because it is long. The lines are FoldFix's own log (see FoldLog).
        section("Recent activity").setText("What FoldFix did, newest last. Kept in memory only and "
                + "not sent anywhere; it starts again when FoldFix restarts. Copy takes the Version "
                + "line and all of it, for a bug report.");
        Supplier<CharSequence> report = () -> "FoldFix " + version.getText() + "\n" + FoldLog.text(FoldLog.CAPACITY);
        list.addView(button("Copy", () -> copy("FoldFix activity", report.get())));
        activityText = text("", 11, false);
        activityText.setTypeface(Typeface.MONOSPACE);
        copyOnLongPress(activityText, "FoldFix activity", report);
        list.addView(activityText);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        rd.addListener(refresh);
        // The reading from the last time this screen was shown may be out of date; the sensor
        // sends its current one on registering. Until then nothing is released (see below).
        hallClosed = null;
        if (hall != null) sm.registerListener(this, hall, SensorManager.SENSOR_DELAY_NORMAL);
        hingeSeen = false;
        if (hinge != null) {
            sm.registerListener(this, hinge, SensorManager.SENSOR_DELAY_NORMAL);
            hingeText.setText(WAIT + "Listening for 3 seconds…");
            h.postDelayed(hingeTimeout, HINGE_LISTEN_MS);
        } else {
            hingeText.setText(BAD + "No hinge angle sensor on this phone");
        }
        hallText.setText(hall == null ? BAD + "Not found: this phone is not supported"
                : WAIT + hall.getName() + ": waiting for a reading…");
        refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;  // a refresh already posted by RearDisplay may still arrive
        rd.removeListener(refresh);
        sm.unregisterListener(this);
        h.removeCallbacks(hingeTimeout);
        h.removeCallbacks(refresh);
    }

    void refresh() {
        h.removeCallbacks(refresh);
        if (!resumed) return;
        boolean enabled = false;
        AccessibilityManager am = getSystemService(AccessibilityManager.class);
        for (AccessibilityServiceInfo i : am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            if (getPackageName().equals(i.getResolveInfo().serviceInfo.packageName)) enabled = true;
        }
        serviceEnabled = enabled;
        releaseIfTurnedOff();
        String status = FoldService.status;
        // Said loudly, so a test state isn't mistaken for what the hardware reports.
        Boolean simulated = FoldService.simulatedHall;
        String test = simulated == null ? "" : "\n" + BAD + "TEST MODE: acting on a simulated \""
                + FoldService.name(simulated) + "\" hall reading (set over adb), not on the sensor below.";
        boolean holding = rd.hasOwnRequest() && rd.isRearActive();
        serviceText.setText(enabled ? (status == null ? WAIT + "On, starting…" : OK + "On. " + status) + test
                : holding ? BAD + "Off, but the outer screen is still held. Open the phone while this "
                        + "screen is showing to switch back (or restart the phone)."
                : BAD + "Off: turn on FoldFix in Accessibility settings");
        switchBox.setVisibility(canSwitchToInner() ? View.VISIBLE : View.GONE);

        outerText.setText(Hardware.outerDisplayPresent(this) ? OK + "Detected"
                : BAD + "Not detected. Restart the phone while it is closed: the outer screen "
                + "only starts if the phone boots closed.");

        String backend = rd.backend();
        accessText.setText(rd.usesExtension() ? OK + "OK, no setup needed (" + backend + ")"
                : backend != null ? OK + "OK (" + backend + ", adb setting applied)"
                : BAD + "Needs a one-time adb command from a computer:");
        accessCommand.setVisibility(backend != null ? View.GONE : View.VISIBLE);

        stateText.setText(rd.describeState());
        showActivity();
        h.postDelayed(refresh, 1_000);  // the service status changes without callbacks
    }

    /**
     * Puts the history on screen again, if it has changed since the last time. A change that comes
     * too soon after that is shown by the next refresh, a second later at most.
     */
    void showActivity() {
        long total = FoldLog.total(), now = SystemClock.uptimeMillis();
        if (total == activityShown || (activityShown >= 0 && now - activityShownAt < ACTIVITY_EVERY_MS)) return;
        activityShown = total;
        activityShownAt = now;
        long earlier = Math.min(total, FoldLog.CAPACITY) - ACTIVITY_LINES;
        activityText.setText((earlier > 0 ? "(" + earlier + " earlier lines not shown)\n" : "")
                + FoldLog.text(ACTIVITY_LINES).trim());
    }

    void hingeTimedOut() {
        if (!hingeSeen) {
            hingeText.setText(BAD + "No readings: the sensor is likely broken (this is what FoldFix works around)");
        }
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (e.sensor == hinge) {
            hingeSeen = true;
            hingeText.setText(String.format(Locale.ROOT,
                    "%s%.0f° — the sensor works, so you may not need FoldFix", OK, e.values[0]));
        } else if (e.sensor == hall) {
            hallClosed = Hardware.isClosed(e);
            hallText.setText(OK + hall.getName() + ": " + (hallClosed ? "closed" : "open"));
            refresh();  // releases if turned off, and shows or hides the switch button
        }
    }

    /**
     * After FoldFix is turned off its request stays active (requests outlive their process here),
     * and the service can't end it any more: Android blocks activity starts once it is unbound.
     * This screen is the top app, so it can: do it once the phone is open.
     */
    void releaseIfTurnedOff() {
        if (resumed && !serviceEnabled && Boolean.FALSE.equals(hallClosed) && rd.isKnown()
                && rd.isRearActive() && rd.hasOwnRequest() && rd.cancel()) {
            Toast.makeText(this, "FoldFix is off: switched back to the inner screen", Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Whether to offer the switch button: rear-display mode is on although the phone is open.
     * That is another app's request, which the service leaves alone on purpose, or something went
     * wrong. Never while the phone reads closed, or before this screen has a reading: ending the
     * request there would turn off the only screen in use, and the service would request again.
     * Not while a cancel is under way either, the button's own or one of FoldFix's.
     */
    boolean canSwitchToInner() {
        // In test mode the simulated reading counts, as it does for the service.
        Boolean simulated = FoldService.simulatedHall;
        return resumed && Boolean.FALSE.equals(simulated != null ? simulated : hallClosed)
                && rd.isKnown() && rd.isRearActive() && !rd.isCancelPending()
                && SystemClock.uptimeMillis() - switchedAt >= SWITCH_AGAIN_MS;
    }

    /**
     * The switch button: ends the active request, whoever made it. The user asked for that, and
     * this screen is the top app, so the system accepts it. One cancel per tap and nothing after
     * it: if another app requests again, that is left to the user (see
     * {@link RearDisplay#cancelForUser}).
     */
    void switchToInner() {
        if (canSwitchToInner()) {  // checked again: the button may be a moment out of date
            boolean sent = rd.cancelForUser();
            if (sent) switchedAt = SystemClock.uptimeMillis();  // refused: the button stays, to try again
            Toast.makeText(this, sent ? "Switched to the inner screen"
                    : "Android refused to switch screens. Try again.", Toast.LENGTH_LONG).show();
        }
        refresh();
    }

    @Override
    public void onAccuracyChanged(Sensor s, int accuracy) {}

    void copy(String label, CharSequence text) {
        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText(label, text));
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
    }

    // ---- tiny view helpers ------------------------------------------------------------------

    /**
     * A long press copies the whole text. The text isn't made selectable instead: selecting brings
     * up Android's text magnifier, which is drawn with the GPU even in an app that is drawn in
     * software, and FoldFix keeps the GPU out of its process (see the manifest).
     */
    void copyOnLongPress(TextView t, String label) {
        copyOnLongPress(t, label, t::getText);
    }

    /** The same, for a view that doesn't show all of what is to be copied. */
    void copyOnLongPress(View view, String label, Supplier<CharSequence> text) {
        view.setOnLongClickListener(v -> {
            copy(label, text.get());
            return true;
        });
    }

    TextView section(String title) {
        TextView t = text(title, 17, true);
        t.setPadding(0, dp(20), 0, dp(4));
        list.addView(t);
        TextView body = text("", 15, false);
        list.addView(body);
        return body;
    }

    TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    Button button(String label, Runnable onClick) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(v -> onClick.run());
        // FoldFix is drawn in software (see the manifest), and the theme's ripple can't be: it shows
        // nothing and logs an error for every frame of a press. So the button keeps the ripple's
        // shape as its background, without the ripple, and shows a press by dimming. That takes
        // the place of the theme's animator, which only moves a shadow that isn't drawn either.
        if (b.getBackground() instanceof RippleDrawable) {
            RippleDrawable ripple = (RippleDrawable) b.getBackground();
            if (ripple.getNumberOfLayers() > 0 && ripple.getId(0) != android.R.id.mask) {
                b.setBackground(ripple.getDrawable(0));
            }
        }
        StateListAnimator press = new StateListAnimator();
        press.addState(new int[]{android.R.attr.state_pressed},
                ObjectAnimator.ofFloat(b, View.ALPHA, 0.6f).setDuration(50));
        press.addState(new int[0], ObjectAnimator.ofFloat(b, View.ALPHA, 1f).setDuration(150));
        b.setStateListAnimator(press);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        b.setLayoutParams(lp);
        return b;
    }

    int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}

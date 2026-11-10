package app.foldfix;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.animation.ObjectAnimator;
import android.animation.StateListAnimator;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
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

import java.security.MessageDigest;
import java.util.Locale;

/** Status and setup checklist. Everything refreshes on resume; sensors update live. */
public class MainActivity extends Activity implements SensorEventListener {
    static final long HINGE_LISTEN_MS = 3_000;
    static final String OK = "✓ ", BAD = "✗ ", WAIT = "… ";
    /** After a tap that sent a cancel, the switch button is hidden for this long: one cancel, then wait. */
    static final long SWITCH_AGAIN_MS = 2_000;
    /**
     * SHA-256 of the certificate the releases are signed with. Not a secret: the release job's
     * log prints it, and release notes quote it.
     */
    static final String RELEASE_CERT_SHA256 = "41f87594fad12af529af92f99bd25c8309020fc5d681acdf9d2fc6c53bdb57e3";

    final Handler h = new Handler(Looper.getMainLooper());
    final Runnable refresh = this::refresh;
    final Runnable hingeTimeout = this::hingeTimedOut;

    LinearLayout list;
    TextView serviceText, hallText, hingeText, outerText, accessText, stateText;
    View accessCommand, switchBox;
    SensorManager sm;
    Sensor hall, hinge;
    RearDisplay rd;
    boolean hingeSeen, resumed, serviceEnabled = true;
    Boolean hallClosed;
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
        version.setText(describeBuild());
        copyOnLongPress(version, "FoldFix version");
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
        h.postDelayed(refresh, 1_000);  // the service status changes without callbacks
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

    /**
     * Version and who signed the installed app, for example "1.0.2 (3), official release". Read
     * from the package manager, so it is what is installed, whatever its version is called.
     */
    String describeBuild() {
        PackageManager pm = getPackageManager();
        String version;
        try {
            PackageInfo p = pm.getPackageInfo(getPackageName(), PackageManager.PackageInfoFlags.of(0));
            version = p.versionName + " (" + p.getLongVersionCode() + ")";
        } catch (Exception e) {
            return "Unknown";
        }
        // Looked up separately, so that a failure here still leaves the version.
        try {
            SigningInfo info = pm.getPackageInfo(getPackageName(), PackageManager.PackageInfoFlags.of(
                    PackageManager.GET_SIGNING_CERTIFICATES)).signingInfo;
            // The current signer only, not earlier keys it may have been rotated from.
            Signature[] signers = info == null ? null : info.getApkContentsSigners();
            if (signers == null || signers.length == 0) return version;
            StringBuilder hex = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray())) {
                hex.append(String.format(Locale.ROOT, "%02x", b));
            }
            return version + (signers.length == 1 && RELEASE_CERT_SHA256.contentEquals(hex)
                    ? ", official release" : ", self-built (signed with another key)");
        } catch (Exception e) {
            return version;  // better no claim than a wrong one
        }
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
        t.setOnLongClickListener(v -> {
            copy(label, t.getText());
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

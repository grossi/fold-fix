package app.foldfix;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

/** Status and setup checklist. Everything refreshes on resume; sensors update live. */
public class MainActivity extends Activity implements SensorEventListener {
    static final long HINGE_LISTEN_MS = 3_000;
    static final String OK = "✓ ", BAD = "✗ ", WAIT = "… ";

    final Handler h = new Handler(Looper.getMainLooper());
    final Runnable refresh = this::refresh;
    final Runnable hingeTimeout = this::hingeTimedOut;

    LinearLayout list;
    TextView serviceText, hallText, hingeText, outerText, accessText, stateText;
    View accessCommand;
    SensorManager sm;
    Sensor hall, hinge;
    RearDisplay rd;
    boolean hingeSeen, resumed, serviceEnabled = true;
    Boolean hallClosed;

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
        cmd.setTextIsSelectable(true);
        Button copy = button("Copy adb command", () -> {
            getSystemService(ClipboardManager.class)
                    .setPrimaryClip(ClipData.newPlainText("adb command", RearDisplay.ADB_COMMAND));
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
        });
        LinearLayout command = new LinearLayout(this);
        command.setOrientation(LinearLayout.VERTICAL);
        command.addView(cmd);
        command.addView(copy);
        accessCommand = command;
        list.addView(command);
        stateText = section("Device state");
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        rd.addListener(refresh);
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
        boolean holding = rd.hasOwnRequest() && rd.isRearActive();
        serviceText.setText(enabled ? (status == null ? WAIT + "On, starting…" : OK + "On. " + status)
                : holding ? BAD + "Off, but the outer screen is still held. Open the phone while this "
                        + "screen is showing to switch back (or restart the phone)."
                : BAD + "Off: turn on FoldFix in Accessibility settings");

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
            releaseIfTurnedOff();
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

    @Override
    public void onAccuracyChanged(Sensor s, int accuracy) {}

    // ---- tiny view helpers ------------------------------------------------------------------

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

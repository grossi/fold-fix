package app.foldfix;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Invisible activity that starts or ends rear-display mode, then finishes. It exists because the
 * system only accepts rear-display requests and cancels from the top app, and an accessibility
 * service may start activities from the background.
 *
 * <p>Only FoldFix itself and the adb shell may start it (see the manifest). For testing:
 * {@code adb shell am start -n app.foldfix/.Trampoline --es action request|cancel}
 */
public class Trampoline extends Activity {
    static final String EXTRA_ACTION = "action", REQUEST = "request", CANCEL = "cancel";

    static void start(Context c, String action) {
        c.startActivity(new Intent(c, Trampoline.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(EXTRA_ACTION, action));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override
    @SuppressWarnings("deprecation")  // overridePendingTransition: its replacement needs API 34
    protected void onResume() {
        super.onResume();
        RearDisplay rd = RearDisplay.get(this);
        if (CANCEL.equals(getIntent().getStringExtra(EXTRA_ACTION))) {
            rd.cancel();
        } else if (Boolean.FALSE.equals(FoldService.hallClosed)) {
            // Started while closed but only resumed now (e.g. the screen was off) and the phone
            // has been opened since: a request would just be cancelled again.
            Log.i(RearDisplay.TAG, "phone is open, dropping stale request");
            rd.dropPendingMark();
        } else {
            rd.request(this);
        }
        finish();
        overridePendingTransition(0, 0);
    }
}

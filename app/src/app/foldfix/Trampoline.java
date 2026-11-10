package app.foldfix;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

/**
 * Invisible activity that starts or ends rear-display mode, then finishes. It exists because the
 * system only accepts rear-display requests and cancels from the top app, and an accessibility
 * service may start activities from the background.
 *
 * <p>A launch can arrive late (the screen was off, the system was busy), so both actions check
 * again that they are still wanted before they do anything.
 *
 * <p>Only FoldFix itself and the adb shell may start it (see the manifest). For testing:
 * {@code adb shell am start -n app.foldfix/.Trampoline --es action request|cancel}. With
 * {@code --ez force true}, a cancel skips that check and ends whatever request is active. That
 * is for tests only: {@link #start} never sets it.
 */
public class Trampoline extends Activity {
    static final String EXTRA_ACTION = "action", REQUEST = "request", CANCEL = "cancel";
    static final String EXTRA_FORCE = "force";

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
        Intent intent = getIntent();
        if (CANCEL.equals(intent.getStringExtra(EXTRA_ACTION))) {
            // Started while the phone was open with a request of FoldFix's active, but maybe only
            // resumed now. The system ends whatever request is active by now, whoever made it, so
            // check again what the service checked: then a late cancel does no more than one the
            // service would send at this moment. Only a definite "closed" stops it. Without a
            // reading (service stopped, or just connected) the request is still FoldFix's to end.
            String stale = intent.getBooleanExtra(EXTRA_FORCE, false) ? null
                    : Boolean.TRUE.equals(FoldService.hallClosed) ? "the phone is closed"
                    : !rd.hasOwnRequest() ? "no request of FoldFix's to end" : null;
            if (stale == null) {
                rd.cancel();
            } else {
                FoldLog.i("dropping stale cancel: " + stale + " (" + rd.describeState() + ")");
            }
        } else if (Boolean.FALSE.equals(FoldService.hallClosed)) {
            // Started while closed but only resumed now (e.g. the screen was off) and the phone
            // has been opened since: a request would just be cancelled again.
            FoldLog.i("phone is open, dropping stale request");
            rd.dropPendingMark();
        } else {
            rd.request(this);
        }
        finish();
        overridePendingTransition(0, 0);
    }
}

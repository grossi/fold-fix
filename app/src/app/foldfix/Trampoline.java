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
 * again that they are still wanted before they do anything (see {@link Stale}).
 *
 * <p>Only FoldFix itself and holders of {@code DUMP} may start it (see the manifest). For testing:
 * {@code adb shell am start -n app.foldfix/.Trampoline --es action request|cancel}. With
 * {@code --ez force true}, a cancel skips that check and ends whatever request is active. That
 * is for tests only: {@link #start} never sets it.
 */
public class Trampoline extends Activity {
    static final String EXTRA_ACTION = "action", REQUEST = Reconciler.REQUEST, CANCEL = Reconciler.CANCEL;
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
            // resumed now: checked again (see Stale).
            String stale = Stale.whyCancelStale(intent.getBooleanExtra(EXTRA_FORCE, false),
                    FoldService.hallClosed, rd.hasOwnRequest());
            if (stale == null) {
                rd.cancel();
            } else {
                FoldLog.i("dropping stale cancel: " + stale + " (" + rd.describeState() + ")");
            }
        } else {
            // Started while closed but maybe only resumed now (e.g. the screen was off): checked
            // again that the phone hasn't been opened since, and that Android hasn't switched to
            // the outer screen by itself (see Stale).
            String stale = Stale.whyRequestStale(FoldService.hallClosed, rd.isStateClosed());
            if (stale == null) {
                rd.request(this);
            } else {
                FoldLog.i(stale + ", dropping stale request");
                rd.dropPendingMark();
            }
        }
        finish();
        overridePendingTransition(0, 0);
    }
}

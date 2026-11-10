package app.foldfix;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * Plain black screen for one case: the phone was opened, FoldFix ended its request, and the app
 * on screen asked for rear-display mode again straight away. Pixel Camera does that when it was
 * started on the outer screen of the closed phone, and ends up there again, facing away from the
 * user.
 *
 * <p>Ending that request as well is not enough. Camera asks again each time rear-display mode
 * ends, unless it stays out of the foreground for a moment afterwards (measured on Android 17:
 * about 0.4 s behind an opaque activity, about 1.6 s behind a translucent one). So this activity
 * ends the request as the top app, like the trampoline, but is opaque and stays for
 * {@link #HOLD_MS} after rear-display mode went off. Then it finishes and the app below comes
 * back, now on the inner screen.
 *
 * <p>{@link FoldService} starts it at most once per opening, and only for a request made right
 * after its own ended. Other apps can't start it.
 */
public class Curtain extends Activity {
    static final String EXTRA_DEADLINE = "deadline", EXTRA_ACTIVATION = "activation";

    /** How long the app below is kept in the background once rear-display mode is off. */
    static final long HOLD_MS = 1_500;
    /** Upper limit for the whole thing, also when rear-display mode goes off late or not at all. */
    static final long MAX_MS = 4_000;

    final Handler h = new Handler(Looper.getMainLooper());
    final Runnable check = this::check;
    final Runnable done = this::finish;

    RearDisplay rd;
    boolean started, holding;

    /**
     * {@code deadline} is the real time ({@code SystemClock.elapsedRealtime()}) after which nothing
     * may be ended any more, {@code activation} the {@link RearDisplay#activations} count while
     * the request to end was seen.
     */
    static void start(Context c, long deadline, int activation) {
        c.startActivity(new Intent(c, Curtain.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(EXTRA_DEADLINE, deadline)
                .putExtra(EXTRA_ACTIVATION, activation));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        h.removeCallbacks(done);
        started = holding = false;  // started again while still up: onResume follows
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (started) return;
        started = true;
        rd = RearDisplay.get(this);
        String stale = whyStale();
        if (stale != null) {
            FoldLog.i("curtain: leaving the request alone, " + stale);
            finish();
            return;
        }
        if (!rd.cancel()) {  // refused: once is all that is tried
            finish();
            return;
        }
        FoldLog.i("curtain: up, covering the other app for " + HOLD_MS + " ms once rear display is off");
        h.postDelayed(done, MAX_MS);
        rd.addListener(check);
        check();
    }

    /**
     * Why the request shouldn't be ended after all, or null. Checked when the launch arrives (see
     * {@link Stale#whyCurtainStale}).
     */
    String whyStale() {
        return Stale.whyCurtainStale(SystemClock.elapsedRealtime(), getIntent().getLongExtra(EXTRA_DEADLINE, 0),
                FoldService.hallClosed, rd.isRearActive(),
                rd.activations(), getIntent().getIntExtra(EXTRA_ACTIVATION, 0), rd.hasOwnRequest());
    }

    /** Starts the hold once rear-display mode is seen off. {@link #MAX_MS} keeps running. */
    void check() {
        if (holding || rd.isRearActive()) return;
        holding = true;
        h.postDelayed(done, HOLD_MS);
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacks(done);
        if (rd != null) rd.removeListener(check);
        super.onDestroy();
    }
}

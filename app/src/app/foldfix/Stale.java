package app.foldfix;

/**
 * The second look {@link Trampoline} and {@link Curtain} take when they run. The service starts
 * them for what it saw at that moment, but a launch can arrive late (the screen was off, the
 * system was busy), and by then the phone may have been folded the other way or the request may
 * be someone else's. Nothing here is Android's, so the checks can be tested without a phone
 * ({@code test.sh}).
 *
 * <p>{@code hallClosed} is the service's last hall reading, null while it has none: stopped, or
 * only just connected.
 */
final class Stale {
    private Stale() {}

    /**
     * Why a cancel shouldn't be made after all, or null. The system ends whatever request is
     * active by now, whoever made it, so this checks again what the service checked: then a late
     * cancel does no more than one the service would send at this moment. Only a definite "closed"
     * stops it. Without a reading the request is still FoldFix's to end. {@code force} skips the
     * check (tests only).
     */
    static String whyCancelStale(boolean force, Boolean hallClosed, boolean ownRequest) {
        return force ? null
                : Boolean.TRUE.equals(hallClosed) ? "the phone is closed"
                : !ownRequest ? "no request of FoldFix's to end" : null;
    }

    /**
     * Whether a request shouldn't be made after all: the phone has been opened since, and the
     * request would just be cancelled again.
     */
    static boolean isRequestStale(Boolean hallClosed) {
        return Boolean.FALSE.equals(hallClosed);
    }

    /**
     * Why the curtain shouldn't end the active request after all, or null. {@code now} and
     * {@code deadline} are real time ({@code SystemClock.elapsedRealtime()}), {@code activation}
     * is the {@link RearDisplay#activations} count the curtain was started for. The deadline is
     * what keeps a late launch from ending a request the user chose meanwhile: which request is
     * active can't be told, only that rear-display mode didn't go off in between.
     */
    static String whyCurtainStale(long now, long deadline, Boolean hallClosed, boolean rearActive,
            int activations, int activation, boolean ownRequest) {
        long late = now - deadline;
        if (late > 0) return "too late by " + late + " ms";
        if (!Boolean.FALSE.equals(hallClosed)) return "the phone isn't open, or the service is off";
        if (!rearActive) return "rear display is off already";
        if (activations != activation) return "it went off and on again";
        if (ownRequest) return "the request is FoldFix's";
        return null;
    }
}

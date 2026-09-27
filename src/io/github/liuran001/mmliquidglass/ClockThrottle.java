package io.github.liuran001.mmliquidglass;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * Runs an action at most once per interval, however often it is asked for.
 *
 * <p>Built for upkeep that used to count frames. Once the glass stopped
 * redrawing a still screen, "every N frames" came to mean "never, once things
 * settle" — and settling is exactly when the host has just finished undoing
 * our work. Asking here from the per-frame path gives a run as soon as the
 * interval allows, and one more after the frames stop, but never a run of its
 * own while nothing is asking.
 *
 * <p>Always runs posted, never inline: the actions change layout and must not
 * run inside the pre-draw pass that asks for them. UI thread only.
 */
final class ClockThrottle {

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final long mIntervalMs;
    private final Runnable mAction;
    private final Runnable mRun = this::run;
    private long mLastRunMs;
    private boolean mPending;

    ClockThrottle(long intervalMs, Runnable action) {
        mIntervalMs = intervalMs;
        mAction = action;
    }

    /** Schedules a run: straight away if the interval has passed, else when it does. */
    void request() {
        if (mPending) {
            return;
        }
        mPending = true;
        long wait = mLastRunMs + mIntervalMs - SystemClock.uptimeMillis();
        mHandler.postDelayed(mRun, Math.max(0L, wait));
    }

    /** Drops a pending run; the next request starts afresh. */
    void cancel() {
        mHandler.removeCallbacks(mRun);
        mPending = false;
    }

    private void run() {
        mPending = false;
        mLastRunMs = SystemClock.uptimeMillis();
        mAction.run();
    }
}

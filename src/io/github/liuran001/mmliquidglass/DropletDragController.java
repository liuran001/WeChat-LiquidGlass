package io.github.liuran001.mmliquidglass;

import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

import java.lang.ref.WeakReference;

/**
 * Press/drag behaviour for the droplet, ported from KernelSU's
 * {@code DampedDragAnimation}.
 *
 * <p>Five independent springs drive everything, with KernelSU's exact
 * parameters:
 *
 * <pre>
 * value    spring(1.0, 1000)   position, in tab units
 * velocity spring(0.5,  300)   normalised speed, feeds the stretch
 * press    spring(1.0, 1000)   press progress
 * scaleX   spring(0.6,  250)
 * scaleY   spring(0.7,  250)
 * </pre>
 *
 * <p>Two details matter as much as the numbers. Position is tracked in
 * <em>tab units</em> (0..N-1) rather than pixels, so velocity normalises by the
 * tab count and feels identical on any screen. And release waits: the scale only
 * relaxes once the droplet has nearly arrived, which is what makes a flick read
 * as a single motion instead of a slide plus a separate shrink.
 *
 * <p>Taps still belong to WeChat — the gesture is only claimed once the finger
 * has moved horizontally past the touch slop.
 */
final class DropletDragController implements LiquidGlassHostLayout.DragHandler {

    /** KernelSU: pressedScale = 78dp / 56dp. */
    private static final float PRESSED_SCALE = 78f / 56f;
    /** KernelSU: LocalFloatingBottomBarTabScale = lerp(1f, 1.2f, pressProgress). */
    private static final float FOCUS_SCALE = 1.2f;
    private static final float STRETCH_LIMIT = 0.2f;
    /** KernelSU: release() waits until within 2.5% of the range. */
    private static final float SETTLE_FRACTION = 0.025f;

    /**
     * Bar growth while held.
     *
     * <p>KernelSU uses 16dp, but it grows the glass layer alone on a full-width
     * bar. Here the tabs ride along and the pill hugs its content, so the same
     * 16dp lands as a much larger fraction and reads as a lurch; half of it
     * gives the same gentle breath.
     */
    private static final float PILL_GROWTH_DP = 8f;

    private WeakReference<View> mPillRef = new WeakReference<>(null);
    private WeakReference<View> mHostRef = new WeakReference<>(null);
    private final WeakReference<View> mDropletRef;
    private WeakReference<ViewGroup> mTabRowRef;
    private final int mTouchSlop;
    private final float mDensity;
    private final boolean mNight;
    private final Spring mValue;
    private final Spring mVelocity;
    private final Spring mPress;
    private final Spring mScaleX;
    private final Spring mScaleY;

    private float mDownX;
    private float mDownY;
    private float mDragStartValue;
    private boolean mDragging;
    private boolean mReleasePending;

    private long mLastFrameNs;
    private boolean mFrameScheduled;

    /** Scratch for {@link #originInHost}; every use is on the UI thread. */
    private final int[] mPos = new int[2];

    /** Velocity is tracked over the value (tab units), as KernelSU does. */
    private long mLastSampleMs;
    private float mLastSampleValue;

    void setPill(View pill) {
        mPillRef = new WeakReference<>(pill);
    }

    /**
     * The container the growth is applied to.
     *
     * <p>KernelSU puts its 16dp growth on the glass layer alone, leaving the tab
     * icons at their laid-out size. On a full-width bar that reads as the pill
     * breathing; on WeChat's hugged, much narrower pill the same 16dp is a far
     * larger fraction, and the tabs visibly fail to follow. Growing the host
     * instead keeps glass, tabs and droplet locked together.
     */
    void setHost(View host) {
        mHostRef = new WeakReference<>(host);
    }

    DropletDragController(View droplet, ViewGroup tabRow, float density, boolean night) {
        mDropletRef = new WeakReference<>(droplet);
        mTabRowRef = new WeakReference<>(tabRow);
        mTouchSlop = ViewConfiguration.get(droplet.getContext()).getScaledTouchSlop();
        mDensity = density;
        mNight = night;

        float visibility = 0.001f;
        mValue = new Spring(1f, 1000f, visibility, 0f);
        mVelocity = new Spring(0.5f, 300f, visibility * 10f, 0f);
        mPress = new Spring(1f, 1000f, 0.001f, 0f);
        mScaleX = new Spring(0.6f, 250f, 0.001f, 1f);
        mScaleY = new Spring(0.7f, 250f, 0.001f, 1f);
    }

    /**
     * Rebinds to the row the app currently owns.
     *
     * <p>QQ can add/remove tabs, or replace Material's SlidingTabIndicator,
     * while the Activity stays alive. Cancelling any in-flight gesture keeps a
     * half-finished spring from continuing against the old geometry; the
     * selection watcher snaps to the current tab after the new row lays out.
     */
    void setTabRow(ViewGroup tabRow) {
        mTabRowRef = new WeakReference<>(tabRow);
        mDragging = false;
        mReleasePending = false;
        mLastSampleMs = 0L;
        mLastFrameNs = 0L;
        float max = tabCount(tabRow) - 1f;
        mValue.snapTo(clamp(mValue.value(), 0f, max));
        mVelocity.snapTo(0f);
        mPress.snapTo(0f);
        mScaleX.snapTo(1f);
        mScaleY.snapTo(1f);
        apply();
    }

    /* ---------------- external drive ---------------- */

    /**
     * The selection changed — the only thing allowed to move the droplet other
     * than a finger. Ignored mid-drag so a mid-gesture page switch cannot yank
     * the droplet out from under the finger.
     */
    void animateToIndex(int index, boolean immediate) {
        if (mDragging) {
            return;
        }
        ViewGroup tabRow = mTabRowRef.get();
        float target = clamp(index, 0f, tabCount(tabRow) - 1f);
        // Releasing a drag already aimed the spring here, and the resulting
        // performClick() bounces the selection straight back at us. Without this
        // the droplet pops a second time after it has settled — KernelSU avoids
        // it with a MutatorMutex, which the View world has no equivalent of.
        if (!immediate && Math.abs(mValue.target() - target) < 0.01f) {
            return;
        }
        if (immediate) {
            mValue.snapTo(target);
            mVelocity.snapTo(0f);
            mPress.snapTo(0f);
            mScaleX.snapTo(1f);
            mScaleY.snapTo(1f);
            apply();
            return;
        }
        press();
        mValue.animateTo(target);
        mVelocity.animateTo(0f);
        mReleasePending = true;
        schedule();
    }

    /* ---------------- gestures ---------------- */

    @Override
    public boolean onIntercept(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                mDownX = ev.getX();
                mDownY = ev.getY();
                mDragging = false;
                boolean over = overDroplet(ev);
                if (over) {
                    press();
                    schedule();
                }
                return false;
            }
            case MotionEvent.ACTION_MOVE: {
                if (mDragging) {
                    return true;
                }
                View droplet = mDropletRef.get();
                if (droplet == null || droplet.getVisibility() != View.VISIBLE) {
                    return false;
                }
                float dx = ev.getX() - mDownX;
                float dy = ev.getY() - mDownY;
                if (Math.abs(dx) > mTouchSlop && Math.abs(dx) > Math.abs(dy)) {
                    mDragging = true;
                    mDragStartValue = mValue.target();
                    press();
                    schedule();
                    return true;
                }
                return false;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                release();
                return false;
            default:
                return false;
        }
    }

    @Override
    public boolean onTouch(MotionEvent ev) {
        ViewGroup tabRow = mTabRowRef.get();
        if (!mDragging || tabRow == null) {
            return false;
        }
        int tabCount = tabCount(tabRow);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE: {
                float tabWidth = tabWidth(tabRow);
                if (tabWidth > 0f) {
                    float v = mDragStartValue + (ev.getX() - mDownX) / tabWidth;
                    mValue.animateTo(clamp(v, 0f, tabCount - 1f));
                    schedule();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                // KernelSU's onDragStopped: settle to the nearest index, then let
                // that index flow back through the selection as the single source
                // of truth. We only report it; the watcher drives the spring.
                int index = Math.round(clamp(mValue.target(), 0f, tabCount - 1f));
                mValue.animateTo(index);
                mVelocity.animateTo(0f);
                mDragging = false;
                release();
                View tab = TabBarBridge.tabAt(tabRow, index);
                if (tab != null && !tab.isSelected()) {
                    tab.performClick();
                }
                return true;
            }
            default:
                return true;
        }
    }

    private boolean overDroplet(MotionEvent ev) {
        View droplet = mDropletRef.get();
        if (droplet == null || droplet.getVisibility() != View.VISIBLE) {
            return false;
        }
        // getTranslationX() is an offset from the laid-out position, and that
        // position already carries the host's shadow padding — comparing it
        // directly against a host-relative touch x missed by that padding, so
        // pressing the droplet never registered.
        float x = ev.getX();
        float left = droplet.getLeft() + droplet.getTranslationX();
        ViewGroup.LayoutParams lp = droplet.getLayoutParams();
        float width = lp != null && lp.width > 0 ? lp.width : droplet.getWidth();
        return x >= left && x <= left + width;
    }

    /* ---------------- press / release ---------------- */

    private void press() {
        mLastSampleMs = 0L;
        mPress.animateTo(1f);
        mScaleX.animateTo(PRESSED_SCALE);
        mScaleY.animateTo(PRESSED_SCALE);
    }

    private void release() {
        // KernelSU holds the pressed scale until the droplet has almost arrived.
        mReleasePending = true;
        schedule();
    }

    private void maybeFinishRelease() {
        if (!mReleasePending || mDragging) {
            return;
        }
        float threshold = Math.max((tabCount(mTabRowRef.get()) - 1f)
                * SETTLE_FRACTION, 0.001f);
        if (Math.abs(mValue.value() - mValue.target()) > threshold) {
            return;
        }
        mReleasePending = false;
        mPress.animateTo(0f);
        mScaleX.animateTo(1f);
        mScaleY.animateTo(1f);
    }

    /* ---------------- frame loop ---------------- */

    private void schedule() {
        if (mFrameScheduled) {
            return;
        }
        mFrameScheduled = true;
        mLastFrameNs = 0L;
        Choreographer.getInstance().postFrameCallback(this::onFrame);
    }

    private void onFrame(long frameNs) {
        mFrameScheduled = false;
        float dt = mLastFrameNs == 0L ? 1f / 60f : (frameNs - mLastFrameNs) / 1e9f;
        mLastFrameNs = frameNs;

        boolean running = mValue.update(dt);
        sampleVelocity();
        running |= mVelocity.update(dt);
        running |= mPress.update(dt);
        running |= mScaleX.update(dt);
        running |= mScaleY.update(dt);

        apply();
        maybeFinishRelease();

        // Re-check after the release: it starts the press/scale springs going
        // again, and testing the pre-release `running` would end the loop right
        // then — leaving the droplet stuck at its pressed size.
        running |= mPress.isRunning() || mScaleX.isRunning() || mScaleY.isRunning()
                || mValue.isRunning() || mVelocity.isRunning();

        if (running || mReleasePending || mDragging) {
            mFrameScheduled = true;
            Choreographer.getInstance().postFrameCallback(this::onFrame);
        }
    }

    /**
     * KernelSU samples velocity over the value itself and normalises it by the
     * range, so the stretch is independent of tab width and screen density.
     */
    private void sampleVelocity() {
        long now = android.os.SystemClock.uptimeMillis();
        if (mLastSampleMs == 0L) {
            mLastSampleMs = now;
            mLastSampleValue = mValue.value();
            return;
        }
        float dtMs = now - mLastSampleMs;
        if (dtMs < 8f) {
            return;
        }
        float perSecond = (mValue.value() - mLastSampleValue) * 1000f / dtMs;
        mVelocity.animateTo(perSecond
                / Math.max(1f, tabCount(mTabRowRef.get()) - 1f));
        mLastSampleMs = now;
        mLastSampleValue = mValue.value();
    }

    /* ---------------- apply ---------------- */

    private void apply() {
        View droplet = mDropletRef.get();
        ViewGroup tabRow = mTabRowRef.get();
        if (droplet == null || tabRow == null || TabBarBridge.tabCount(tabRow) == 0) {
            return;
        }
        float tabWidth = tabWidth(tabRow);
        if (tabWidth <= 0f) {
            return;
        }
        // Centre using the laid-out width, not getWidth(): the droplet is sized
        // through LayoutParams and getWidth() still reads 0 until the next layout
        // pass, which parked it half a tab off on the first frame after launch.
        View first = TabBarBridge.tabAt(tabRow, 0);
        if (first == null) {
            return;
        }
        ViewGroup.LayoutParams lp = droplet.getLayoutParams();
        float dropletW = lp != null && lp.width > 0 ? lp.width : droplet.getWidth();
        float originX = originInHost(tabRow, first, dropletW);
        droplet.setTranslationX(originX + mValue.value() * tabWidth);

        // KernelSU:
        //   scaleX /= 1f - (velocity * 0.75f).coerceIn(-0.2f, 0.2f)
        //   scaleY *= 1f - (velocity * 0.25f).coerceIn(-0.2f, 0.2f)
        float v = mVelocity.value() / 10f;
        float along = clamp(v * 0.75f, -STRETCH_LIMIT, STRETCH_LIMIT);
        float across = clamp(v * 0.25f, -STRETCH_LIMIT, STRETCH_LIMIT);
        droplet.setScaleX(mScaleX.value() / (1f - along));
        droplet.setScaleY(mScaleY.value() * (1f - across));

        // The lens, its wash and the inner shadow all ride on press progress.
        float p = mPress.value();
        if (droplet instanceof DropletPanel) {
            DropletPanel panel = (DropletPanel) droplet;
            panel.setProgress(p);
            // The droplet slides via translationX, which does not redraw it —
            // re-capture every frame so the refraction tracks what it passes over.
            panel.refresh();
        }
        // KernelSU grows the whole pill a little while dragging, and rides a
        // highlight that follows the droplet across it.
        View pill = mPillRef.get();
        if (pill != null && pill.getWidth() > 0) {
            float grow = 1f + (PILL_GROWTH_DP * mDensity / pill.getWidth()) * p;
            View host = mHostRef.get();
            View grown = host != null ? host : pill;
            grown.setScaleX(grow);
            grown.setScaleY(grow);
            if (grown != pill && pill.getScaleX() != 1f) {
                pill.setScaleX(1f);
                pill.setScaleY(1f);
            }
            if (pill instanceof LiquidGlassPanel) {
                // Droplet centre in the pill's own coordinates. translationX is
                // an offset from the laid-out left, and that left already carries
                // the host's shadow padding, so both sides need it.
                float centre = droplet.getLeft() + droplet.getTranslationX()
                        + dropletW * 0.5f - pill.getLeft();
                ((LiquidGlassPanel) pill).setInteraction(p, centre);
            }
        }
        applyFocusZoom(tabRow, p);
    }

    /**
     * Keeps each individual tab at its natural size.
     *
     * <p>KernelSU does not scale the tabs one by one — the enlarged icon you see
     * while dragging is the separately drawn 1.2× copy that the droplet refracts
     * (see {@link DropletPanel}). Scaling the real tabs as well would double it.
     * The bar-wide growth is a different thing and rides on the host.
     */
    private void applyFocusZoom(ViewGroup tabRow, float p) {
        for (int i = 0; i < tabRow.getChildCount(); i++) {
            View tab = tabRow.getChildAt(i);
            if (tab.getScaleX() != 1f) {
                tab.setScaleX(1f);
                tab.setScaleY(1f);
            }
        }
    }

    /**
     * Resting x of the first tab column, expressed as a translation for a
     * droplet that is laid out at the host's padding origin.
     *
     * <p>Measured through the tree rather than from the row's own {@code
     * getLeft()}: on QQ the row <em>is</em> the bar view, so that left already
     * includes the host's shadow padding, and adding it to a position that
     * starts at the host's padding counted the padding twice — the droplet rode
     * one padding to the right of its tab, outside the pill.
     */
    private float originInHost(ViewGroup tabRow, View first, float dropletW) {
        float centre = (first.getWidth() - dropletW) * 0.5f;
        View host = mHostRef.get();
        if (host instanceof ViewGroup
                && ViewGeom.positionIn(tabRow, host, mPos)) {
            return mPos[0] + first.getLeft() + centre
                    - ((ViewGroup) host).getPaddingLeft();
        }
        return tabRow.getLeft() + first.getLeft() + centre;
    }

    private static float tabWidth(ViewGroup tabRow) {
        if (tabRow == null || TabBarBridge.tabCount(tabRow) == 0) {
            return 0f;
        }
        View first = TabBarBridge.tabAt(tabRow, 0);
        return first == null ? 0f : first.getWidth();
    }

    private static int tabCount(ViewGroup tabRow) {
        return Math.max(1, TabBarBridge.tabCount(tabRow));
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}

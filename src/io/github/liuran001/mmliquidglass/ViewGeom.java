package io.github.liuran001.mmliquidglass;

import android.view.View;
import android.view.ViewParent;

/**
 * Geometry helpers for views that are drawn while an ancestor is scaled.
 *
 * <p>Pressing the droplet grows the whole bar (KernelSU's {@code layerBlock}),
 * which puts both glass panels inside a scaled graphics layer. Their shaders
 * work in unscaled local coordinates, so anything that samples the screen has to
 * ask for positions and scale factors that ignore that transform — otherwise the
 * backdrop comes out magnified instead of revealing more of what is behind.
 */
final class ViewGeom {

    /** Scratch for the anchor lookup; every caller is on the UI thread. */
    private static final int[] sAnchor = new int[2];

    private ViewGeom() {
    }

    /**
     * Screen position with every view scale factored out: accumulates the plain
     * layout offsets all the way to the root and anchors there.
     */
    static boolean unscaledScreenPos(View v, int[] out) {
        float x = 0f;
        float y = 0f;
        View cur = v;
        // All the way to the root. Stopping at the first unscaled ancestor is
        // not enough: that ancestor's own getLocationOnScreen() still carries
        // any scale applied further up, which is exactly the case once the bar
        // grows as a whole.
        while (cur.getParent() instanceof View) {
            View parent = (View) cur.getParent();
            x += cur.getLeft() + cur.getTranslationX() - parent.getScrollX();
            y += cur.getTop() + cur.getTranslationY() - parent.getScrollY();
            cur = parent;
        }
        cur.getLocationOnScreen(sAnchor);
        out[0] = Math.round(sAnchor[0] + x);
        out[1] = Math.round(sAnchor[1] + y);
        return true;
    }

    /**
     * Top-left of {@code v} in {@code ancestor}'s own coordinates, layout
     * offsets only — no scale, no screen origin.
     *
     * <p>This is the form the bar's own children need. Reading a single
     * {@code getTop()} is only correct while the two views are parent and
     * child; the host's shadow padding lives on the host, so a view one level
     * down (QQ's tab widget, which doubles as its own tab row) reports an
     * offset that already contains it. Summing every step to the host and
     * subtracting the host's padding then gives the position exactly once,
     * whichever shape the app's tree has this release.
     *
     * @return false when {@code v} is not a descendant of {@code ancestor},
     *     so callers can fall back instead of trusting a half-filled array.
     */
    static boolean positionIn(View v, View ancestor, int[] out) {
        if (v == null || ancestor == null) {
            return false;
        }
        float x = 0f;
        float y = 0f;
        View cur = v;
        while (cur != null && cur != ancestor) {
            x += cur.getLeft();
            y += cur.getTop();
            ViewParent p = cur.getParent();
            cur = p instanceof View ? (View) p : null;
        }
        if (cur != ancestor) {
            return false;
        }
        out[0] = Math.round(x);
        out[1] = Math.round(y);
        return true;
    }

    /**
     * Scale this view is actually drawn at, including every ancestor's.
     *
     * <p>A view's own {@code getScaleX()} is not enough once the bar grows as a
     * whole: the droplet carries its own press scale <em>and</em> the host's.
     */
    static float cumulativeScale(View v) {
        float s = 1f;
        View cur = v;
        while (cur != null) {
            s *= Math.abs(cur.getScaleX());
            ViewParent parent = cur.getParent();
            cur = parent instanceof View ? (View) parent : null;
        }
        return s < 0.01f ? 1f : s;
    }
}

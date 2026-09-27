package io.github.liuran001.mmliquidglass;

import android.graphics.Canvas;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Method;

/**
 * Paints the host's pages into a glass capture.
 *
 * <p>Both panels sample the pages under the pill whenever they re-record. They
 * used to do it with {@code page.draw(canvas)}, which runs the page's own
 * drawing code again on top of the pass that has just drawn it, and that code
 * is the host's. WeChat's is the case in point: since 8.0.72 every page is a
 * {@code FrostedContentView}, whose {@code dispatchDraw} records all of its
 * children into an intermediate {@code RenderNode} of its own before drawing
 * that (8.0.78). Drawing the page again re-records that node, and a node with a
 * new display list is damaged over its whole bounds — the whole page, i.e. the
 * whole screen — so every re-capture of a pill-sized strip turned into a
 * full-screen redraw.
 *
 * <p>None of it has to run again. The pill is drawn after the pager, so by the
 * time a panel records, every page already holds this frame's display list, and
 * {@code ViewGroup.drawChild} on a hardware canvas hands that list over instead
 * of drawing the child anew. It is protected, hence the reflection; should it
 * ever be out of reach the pages are redrawn as before.
 */
final class Backdrop {

    private static Method sDrawChild;
    private static boolean sDrawChildResolved;

    /** Scratch; every caller is on the UI thread. */
    private static final int[] sPos = new int[2];
    private static final Rect sVisible = new Rect();
    /** drawChild's arguments, reused so a capture allocates nothing. */
    private static final Object[] sArgs = new Object[3];

    private Backdrop() {
    }

    /**
     * Draws the pages of {@code pager} that are on screen, with the screen
     * point ({@code left}, {@code top}) at the canvas origin and everything
     * beyond {@code width} x {@code height} from there clipped away.
     */
    static void draw(RecordingCanvas c, ViewGroup pager, boolean night,
                     int left, int top, int width, int height) {
        // Lay down the page colour first. Any part of the capture the pages do
        // not cover — which happens as soon as WeChat slides the bar past the
        // bottom of the content — is otherwise never drawn, and transparent
        // black turns into solid black once it goes through the blur.
        c.drawColor(night ? 0xFF111111 : 0xFFF7F7F7);
        Method drawChild = drawChild();
        boolean recorded = false;
        boolean sawPage = false;
        if (drawChild != null) {
            try {
                sawPage = drawRecorded(c, pager, drawChild, left, top, width, height);
                recorded = true;
            } catch (Throwable t) {
                // Not retried: a reflective call that fails once will again.
                sDrawChild = null;
                LiquidGlassModule.logErr("recorded page capture failed, redrawing pages", t);
            }
        }
        if (!recorded) {
            sawPage = drawRedrawn(c, pager, left, top, width, height);
        }
        if (!sawPage) {
            drawPager(c, pager, left, top);
        }
    }

    /**
     * Draws each page from the display list the pager's own pass recorded.
     *
     * @return whether the pager has a visible page at all, drawn or culled
     */
    private static boolean drawRecorded(Canvas c, ViewGroup pager, Method drawChild,
                                        int left, int top, int width, int height)
            throws ReflectiveOperationException {
        // The pages sit in the pager's content space, which its own display
        // list shifts by the scroll before drawing them. That one shift puts
        // every page where it is on screen; drawChild adds each page's own
        // position and transform from its render node.
        ViewGeom.unscaledScreenPos(pager, sPos);
        float ox = sPos[0] - pager.getScrollX() - left;
        float oy = sPos[1] - pager.getScrollY() - top;
        // The capture's bounds, in that same content space.
        float cl = -ox;
        float ct = -oy;
        float cr = cl + width;
        float cb = ct + height;
        Object[] args = sArgs;
        args[0] = c;
        args[2] = pager.getDrawingTime();
        boolean sawPage = false;
        int save = c.save();
        try {
            c.translate(ox, oy);
            c.clipRect(cl, ct, cr, cb);
            for (int i = 0; i < pager.getChildCount(); i++) {
                View page = pager.getChildAt(i);
                if (page.getVisibility() != View.VISIBLE) {
                    continue;
                }
                sawPage = true;
                float pl = page.getLeft() + page.getTranslationX();
                float pt = page.getTop() + page.getTranslationY();
                if (pl >= cr || pt >= cb
                        || pl + page.getWidth() <= cl || pt + page.getHeight() <= ct) {
                    continue; // nowhere near the pill
                }
                if (page.getAnimation() != null) {
                    // drawChild steps a legacy Animation as it draws, and the
                    // pager's own pass has already stepped it for this frame.
                    int s = c.save();
                    c.translate(pl, pt);
                    page.draw(c);
                    c.restoreToCount(s);
                } else {
                    args[1] = page;
                    drawChild.invoke(pager, args);
                }
            }
        } finally {
            c.restoreToCount(save);
            // Held statically; must not keep the canvas or a page alive.
            args[0] = null;
            args[1] = null;
            args[2] = null;
        }
        return sawPage;
    }

    /**
     * Draws each page by running its draw again, placed by its own screen
     * position. Drawing only the "current" page leaves the other half of the
     * bar with nothing to refract mid-swipe — it renders black.
     */
    private static boolean drawRedrawn(Canvas c, ViewGroup pager,
                                       int left, int top, int width, int height) {
        boolean drewAny = false;
        for (int i = 0; i < pager.getChildCount(); i++) {
            View page = pager.getChildAt(i);
            if (page.getVisibility() != View.VISIBLE
                    || !page.getGlobalVisibleRect(sVisible) || sVisible.isEmpty()) {
                continue;
            }
            page.getLocationOnScreen(sPos);
            float dx = sPos[0] - left;
            float dy = sPos[1] - top;
            int save = c.save();
            c.translate(dx, dy);
            // Clip after translating, i.e. in the page's own coordinates, so
            // ViewGroup can reject non-intersecting children early. Clipping
            // before the translate would reject everything.
            c.clipRect(-dx, -dy, -dx + width, -dy + height);
            page.draw(c);
            c.restoreToCount(save);
            drewAny = true;
        }
        return drewAny;
    }

    /** Last resort when no page is on screen: the pager as a whole. */
    private static void drawPager(Canvas c, ViewGroup pager, int left, int top) {
        pager.getLocationOnScreen(sPos);
        int save = c.save();
        c.translate(sPos[0] - left, sPos[1] - top);
        pager.draw(c);
        c.restoreToCount(save);
    }

    private static Method drawChild() {
        if (!sDrawChildResolved) {
            sDrawChildResolved = true;
            try {
                Method m = ViewGroup.class.getDeclaredMethod("drawChild",
                        Canvas.class, View.class, long.class);
                m.setAccessible(true);
                sDrawChild = m;
            } catch (Throwable t) {
                LiquidGlassModule.logErr(
                        "ViewGroup.drawChild out of reach, pages are redrawn", t);
            }
        }
        return sDrawChild;
    }
}

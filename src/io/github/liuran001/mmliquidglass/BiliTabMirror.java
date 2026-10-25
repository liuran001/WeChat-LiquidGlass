package io.github.liuran001.mmliquidglass;

import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RenderNode;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Invisible stand-ins for Bilibili's Compose tabs, and the capsule around them.
 *
 * <p>The droplet only knows how to follow real child views. The bottom bar is
 * one Compose view, so the mirror lays a slot over each tab column the
 * semantics tree reports — bounds and the selected bit — and the pill is then
 * shrunk to that cluster. A drag that settles on a slot taps the compose view
 * there, which is what actually switches the page.
 *
 * <p>The original Compose bar still paints every icon/label/badge and owns
 * taps. The adapter supplies geometry, not a replacement navigation model.
 */
final class BiliTabMirror {

    private static final Object TAG = new Object();
    /** Marks a mirror slot so the droplet can paint that column's real glyphs. */
    private static final Object COLUMN = new Object();
    private static final long SYNC_INTERVAL_MS = 64L;
    private static final String RECT = "androidx.compose.ui.geometry.Rect";

    private static boolean sLogged;
    private static boolean sProbeLogged;
    private static boolean sUnavailable;
    private static String sKeySample = "";

    private static Method sRootMethod;
    private static Method sBoundsMethod;
    private static Method sChildrenMethod;
    private static Method[] sConfigMethods = new Method[0];
    private static Method sGetLeft;
    private static Method sGetTop;
    private static Method sGetRight;
    private static Method sGetBottom;

    /** Layout and scheduling belong to this bar, never to the whole process. */
    private static final class Column extends View {
        boolean navigation;
        Column(android.content.Context context) { super(context); }
    }

    private static final class Mirror extends FrameLayout {
        long lastSyncMs;
        int layoutKey = Integer.MIN_VALUE;
        boolean ready;
        boolean refreshPosted;
        final Runnable refresh = () -> {
            refreshPosted = false;
            lastSyncMs = 0L;
            if (isAttachedToWindow() && getParent() instanceof View) {
                // The following pre-draw may read immediately. Do not sync here
                // then invalidate: that creates an idle polling loop.
                ((View) getParent()).postInvalidateOnAnimation();
            }
        };

        Mirror(android.content.Context context) { super(context); }

        void requestRefresh(long delay) {
            if (!refreshPosted && isAttachedToWindow()) {
                refreshPosted = true;
                postDelayed(refresh, Math.max(1L, delay));
            }
        }

        @Override protected void onDetachedFromWindow() {
            removeCallbacks(refresh);
            refreshPosted = false;
            lastSyncMs = 0L;
            ready = false;
            layoutKey = Integer.MIN_VALUE;
            super.onDetachedFromWindow();
        }
    }

    private BiliTabMirror() { }

    static boolean isMirrorColumn(View view) {
        return view != null && view.getTag() == COLUMN;
    }

    static boolean isReady(LiquidGlassHostLayout host) {
        ViewGroup row = findMirror(host);
        return row instanceof Mirror && ((Mirror) row).ready;
    }

    static void attach(LiquidGlassHostLayout host, View tabView) {
        if (!isTarget(tabView) || host == null) {
            return;
        }
        ViewGroup mirror = findMirror(host);
        if (mirror == null) {
            mirror = new Mirror(host.getContext());
            mirror.setTag(TAG);
            mirror.setClickable(false);
            mirror.setImportantForAccessibility(
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            int index = host.indexOfChild(tabView);
            if (index < 0) {
                index = host.getChildCount();
            }
            host.addView(mirror, index, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }
        ((Mirror) mirror).lastSyncMs = 0L;
        sync(host);
    }

    /** Refreshes the mirror. No-op for the other hosts, and throttled on this one. */
    static void sync(LiquidGlassHostLayout host) {
        if (LiquidGlassModule.app() != HostApp.BILIBILI || host == null) {
            return;
        }
        View tabView = findTab(host);
        ViewGroup mirror = findMirror(host);
        if (tabView == null || mirror == null) {
            return;
        }
        Mirror state = (Mirror) mirror;
        long now = SystemClock.uptimeMillis();
        long elapsed = now - state.lastSyncMs;
        if (state.ready && elapsed < SYNC_INTERVAL_MS) {
            // Guarantee a final read even if the native click stops drawing
            // before the throttle expires. At most one callback per bar.
            state.requestRefresh(SYNC_INTERVAL_MS - elapsed);
            return;
        }
        state.lastSyncMs = now;
        List<Hit> slots = readSlots(tabView);
        if (slots.size() < 3) {
            state.ready = false;
            return;
        }
        int key = layoutKey(slots);
        key = key * 31 + tabView.getWidth();
        key = key * 31 + tabView.getHeight();
        key = key * 31 + tabView.getLeft();
        key = key * 31 + tabView.getTop();
        key = key * 31 + host.getLayoutDirection();
        key = key * 31 + (host.getParent() instanceof View
                ? ((View) host.getParent()).getWidth() : 0);
        if (key != state.layoutKey || !state.ready) {
            state.ready = layOut(host, mirror, tabView, slots);
            if (state.ready) {
                state.layoutKey = key;
            } else {
                state.requestRefresh(SYNC_INTERVAL_MS);
                return;
            }
        } else {
            applySelection(mirror, slots);
        }
        LiquidGlassInstaller.noteSyntheticRow(host, mirror);
    }

    /** Selection can move without the columns moving. Avoids a layout pass. */
    private static void applySelection(ViewGroup mirror, List<Hit> slots) {
        int n = Math.min(mirror.getChildCount(), slots.size());
        for (int i = 0; i < n; i++) {
            View slot = mirror.getChildAt(i);
            ((Column) slot).navigation = slots.get(i).navigation;
            boolean selected = slots.get(i).selected;
            if (slot.isSelected() != selected) {
                slot.setSelected(selected);
            }
        }
    }

    private static int layoutKey(List<Hit> slots) {
        int key = slots.size();
        for (int i = 0; i < slots.size(); i++) {
            Rect b = slots.get(i).bounds;
            // Quantize so a one-pixel semantics wobble does not relayout the bar.
            key = key * 31 + (b.left >> 2);
            key = key * 31 + (b.top >> 2);
            key = key * 31 + (b.width() >> 2);
            key = key * 31 + (b.height() >> 2);
        }
        return key;
    }

    private static boolean isTarget(View v) {
        return LiquidGlassModule.app() == HostApp.BILIBILI && TabBarBridge.isTabView(v);
    }

    /**
     * Paints one mirror column from the compose bar's own display list.
     *
     * <p>WeChat's tabs paint their icon and title when the droplet calls
     * {@link View#draw(Canvas)}. These stand-ins are empty, and calling
     * {@code draw} on the compose view from inside the droplet records nothing:
     * Compose has already put the glyphs in its own render node.
     * Replaying that node, clipped to the slot, is the same column the lens
     * shows for the other apps.
     *
     * @return false when {@code slot} is not a mirror column
     */
    static boolean drawColumn(View slot, Canvas canvas) {
        if (slot == null || slot.getTag() != COLUMN) {
            return false;
        }
        if (slot.getWidth() <= 0 || slot.getHeight() <= 0) {
            return true;
        }
        View compose = composeOf(slot);
        RenderNode node = compose == null ? null : renderNodeOf(compose);
        if (node == null) {
            if (!sReplayMissing) {
                sReplayMissing = true;
                LiquidGlassModule.log(android.util.Log.INFO,
                        "bili lens: compose display list not ready");
            }
            return true;
        }
        if (!sReplayLogged) {
            sReplayLogged = true;
            LiquidGlassModule.log(android.util.Log.INFO,
                    "bili lens replay: node=" + node.getWidth() + "x" + node.getHeight()
                            + " at " + node.getLeft() + "," + node.getTop()
                            + " slot=" + slot.getLeft() + "," + slot.getTop()
                            + " " + slot.getWidth() + "x" + slot.getHeight());
        }
        int save = canvas.save();
        try {
            canvas.clipRect(0, 0, slot.getWidth(), slot.getHeight());
            // Slot position in the mirror, minus where the compose view sits,
            // is the column in compose-local pixels. The node's own left/top
            // is added back when it is drawn, so cancel that too. If the node
            // position is already 0, this still lands the column at the origin.
            int columnX = slot.getLeft();
            int columnY = slot.getTop();
            if (slot.getParent() instanceof View) {
                View mirror = (View) slot.getParent();
                columnX += mirror.getLeft() - compose.getLeft();
                columnY += mirror.getTop() - compose.getTop();
            }
            canvas.translate(-columnX - node.getLeft(), -columnY - node.getTop());
            canvas.drawRenderNode(node);
        } catch (Throwable t) {
            if (!sReplayMissing) {
                sReplayMissing = true;
                LiquidGlassModule.logErr("bili lens replay failed", t);
            }
        } finally {
            canvas.restoreToCount(save);
        }
        return true;
    }

    private static boolean sReplayLogged;
    private static boolean sReplayMissing;
    private static Field sRenderNodeField;

    /** The display list the compose view was last drawn into. */
    private static RenderNode renderNodeOf(View view) {
        try {
            if (sRenderNodeField == null) {
                Field field = View.class.getDeclaredField("mRenderNode");
                field.setAccessible(true);
                sRenderNodeField = field;
            }
            Object node = sRenderNodeField.get(view);
            if (!(node instanceof RenderNode)) {
                return null;
            }
            RenderNode render = (RenderNode) node;
            return render.hasDisplayList() ? render : null;
        } catch (Throwable t) {
            if (!sReplayMissing) {
                sReplayMissing = true;
                LiquidGlassModule.logErr("bili lens node unavailable", t);
            }
            return null;
        }
    }

    private static View composeOf(View slot) {
        View mirror = slot.getParent() instanceof View ? (View) slot.getParent() : null;
        View host = mirror != null && mirror.getParent() instanceof View
                ? (View) mirror.getParent() : null;
        if (!(host instanceof ViewGroup)) {
            return null;
        }
        ViewGroup group = (ViewGroup) host;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (TabBarBridge.isTabView(child)) {
                return child;
            }
        }
        return null;
    }

    private static View findTab(ViewGroup host) {
        for (int i = 0; i < host.getChildCount(); i++) {
            View c = host.getChildAt(i);
            if (TabBarBridge.isTabView(c)) {
                return c;
            }
        }
        return null;
    }

    private static ViewGroup findMirror(ViewGroup host) {
        for (int i = 0; i < host.getChildCount(); i++) {
            View c = host.getChildAt(i);
            if (c.getTag() == TAG && c instanceof ViewGroup) {
                return (ViewGroup) c;
            }
        }
        return null;
    }

    /** @return false while the compose view is still the pre-shrink full-bleed bar */
    private static boolean layOut(LiquidGlassHostLayout host, ViewGroup mirror,
                               View tabView, List<Hit> slots) {
        while (mirror.getChildCount() < slots.size()) {
            View slot = new Column(mirror.getContext());
            slot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            // FrameLayout's default is MATCH_PARENT. A slot measured that way
            // is the whole pill, and the droplet is sized from it before the
            // real column width arrives — so the bubble hangs past both tips.
            slot.setLayoutParams(new FrameLayout.LayoutParams(0, 0));
            mirror.addView(slot);
        }
        while (mirror.getChildCount() > slots.size()) {
            mirror.removeViewAt(mirror.getChildCount() - 1);
        }
        int n = slots.size();
        int targetW = LiquidGlassInstaller.fitCapsule(host, n);
        int composeW = tabView.getWidth();
        int composeH = tabView.getHeight();
        // fitCapsule shrinks the host, but this frame's compose view is still
        // the full-bleed bar. Tiling that width makes a droplet that hangs
        // out of both ends of the capsule. Wait until the shrink has landed.
        if (composeW <= 0 || composeH <= 0 || (targetW > 0 && composeW > targetW + 8)) {
            return false;
        }
        int[] composePos = new int[2];
        if (!ViewGeom.positionIn(tabView, host, composePos)) {
            return false;
        }
        int padL = host.getPaddingLeft();
        int padT = host.getPaddingTop();
        boolean laidOut = true;
        int selected = -1;
        for (int i = 0; i < n; i++) {
            Hit hit = slots.get(i);
            View slot = mirror.getChildAt(i);
            final View column = slot;
            final View bar = tabView;
            slot.setTag(COLUMN);
            ((Column) slot).navigation = hit.navigation;
            slot.setOnClickListener(v -> click(bar, column));
            if (hit.selected) {
                selected = i;
            }
            if (slot.isSelected() != hit.selected) {
                slot.setSelected(hit.selected);
            }
            // The same native bounds now drive the lens, hit target and
            // selection. Never replace them with equally divided screen cells.
            Rect bounds = hit.bounds;
            int width = bounds.width();
            int height = bounds.height();
            int left = composePos[0] + bounds.left - padL;
            int top = composePos[1] + bounds.top - padT;
            laidOut &= slot.getWidth() == width && slot.getHeight() == height
                    && slot.getLeft() == left && slot.getTop() == top;
            FrameLayout.LayoutParams lp = slot.getLayoutParams() instanceof FrameLayout.LayoutParams
                    ? (FrameLayout.LayoutParams) slot.getLayoutParams()
                    : new FrameLayout.LayoutParams(width, height);
            if (lp.width != width || lp.height != height
                    || lp.leftMargin != left || lp.topMargin != top
                    || lp.gravity != (android.view.Gravity.TOP | android.view.Gravity.LEFT)) {
                lp.width = width;
                lp.height = height;
                lp.leftMargin = left;
                lp.topMargin = top;
                lp.gravity = android.view.Gravity.TOP | android.view.Gravity.LEFT;
                slot.setLayoutParams(lp);
            }
        }
        if (!sLogged) {
            sLogged = true;
            LiquidGlassModule.log(android.util.Log.INFO,
                    "bilibili tab mirror: slots=" + n
                            + " selected=" + selected
                            + " nativeBounds=" + slots.get(0).bounds
                            + " compose=" + composeW + "x" + composeH
                            + " keys=" + sKeySample);
        }
        return laidOut;
    }

    /** Commit through the original Compose input path, at the native slot. */
    private static void click(View tabView, View slot) {
        if (!(slot instanceof Column) || !((Column) slot).navigation
                || !(slot.getParent() instanceof Mirror)
                || !((Mirror) slot.getParent()).ready
                || !tabView.isShown() || !tabView.isAttachedToWindow()) {
            return;
        }
        ViewGroup mirror = (ViewGroup) slot.getParent();
        if (mirror.getParent() != tabView.getParent()) {
            return;
        }
        float x = mirror.getLeft() + slot.getLeft() + slot.getWidth() * 0.5f
                - tabView.getLeft();
        float y = mirror.getTop() + slot.getTop() + slot.getHeight() * 0.5f
                - tabView.getTop();
        if (x < 0 || y < 0 || x >= tabView.getWidth() || y >= tabView.getHeight()) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, x, y, 0);
        try {
            if (tabView.dispatchTouchEvent(down)) {
                tabView.dispatchTouchEvent(up);
            }
        } finally {
            down.recycle();
            up.recycle();
            ((Mirror) mirror).requestRefresh(16L);
        }
    }

    private static List<Hit> readSlots(View tabView) {
        if (tabView == null || tabView.getWidth() <= 0 || tabView.getHeight() <= 0) {
            return Collections.emptyList();
        }
        try {
            View compose = findCompose(tabView);
            Object owner = compose.getClass().getMethod("getSemanticsOwner").invoke(compose);
            if (owner == null) {
                return Collections.emptyList();
            }
            Method rootMethod = rootMethod(owner);
            if (rootMethod == null) {
                return Collections.emptyList();
            }
            Object root = rootMethod.invoke(owner);
            if (root == null) {
                return Collections.emptyList();
            }
            Rect view = new Rect(0, 0, tabView.getWidth(), tabView.getHeight());
            Method bounds = boundsMethod(root, view);
            Method children = childrenMethod(root);
            if (bounds == null || children == null) {
                probe(root, view, 0, 0, compose);
                return Collections.emptyList();
            }
            Method[] configs = configMethods(root.getClass());
            Rect rootBounds = toRect(bounds.invoke(root));
            // Compose may still expose the pre-resize tree for one frame.
            // Wait for real geometry; guessing equal columns hides that race.
            if (Math.abs(rootBounds.width() - view.width()) > 4
                    || Math.abs(rootBounds.height() - view.height()) > 4) {
                return Collections.emptyList();
            }
            int dx = -rootBounds.left;
            int dy = -rootBounds.top;
            int minH = Math.max(view.height() * 2 / 3, 1);
            List<Hit> found = new ArrayList<>();
            List<Rect> selected = new ArrayList<>();
            List<Rect> navigation = new ArrayList<>();
            walk(root, bounds, children, configs, dx, dy, view, minH, found, selected, navigation, 0);
            for (Rect mark : navigation) {
                for (Hit hit : found) {
                    if (hit.bounds.contains(mark.centerX(), mark.centerY())) hit.navigation = true;
                }
            }
            for (int i = 0; i < selected.size(); i++) {
                Rect mark = selected.get(i);
                int cx = mark.centerX();
                int cy = mark.centerY();
                for (int j = 0; j < found.size(); j++) {
                    Hit hit = found.get(j);
                    if (hit.bounds.contains(cx, cy)) {
                        hit.selected = true;
                    }
                }
            }
            List<Hit> kept = dedupe(found);
            for (Hit hit : kept) {
                if (!hit.bounds.intersect(view) || hit.bounds.width() <= 0) {
                    return Collections.emptyList();
                }
            }
            if (!sProbeLogged && kept.size() < 3) {
                probe(root, view, childCount(children, root), kept.size(), compose);
            }
            return kept;
        } catch (Throwable t) {
            if (!sUnavailable) {
                sUnavailable = true;
                LiquidGlassModule.logErr("bilibili semantics walk failed", t);
            }
            return Collections.emptyList();
        }
    }

    private static void probe(Object root, Rect view, int kids, int columns, View compose) {
        if (sProbeLogged) {
            return;
        }
        sProbeLogged = true;
        LiquidGlassModule.log(android.util.Log.INFO,
                "bilibili semantics: children=" + kids
                        + " columns=" + columns
                        + " host=" + view.width() + "x" + view.height()
                        + " root=" + root.getClass().getName()
                        + " compose=" + compose.getClass().getName());
    }

    private static int childCount(Method children, Object node) {
        try {
            Object list = children.invoke(node);
            return list instanceof List ? ((List<?>) list).size() : 0;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static View findCompose(View tabView) {
        if (!(tabView instanceof ViewGroup)) {
            return tabView;
        }
        ViewGroup group = (ViewGroup) tabView;
        for (int i = 0; i < group.getChildCount(); i++) {
            View c = group.getChildAt(i);
            if (c.getClass().getName().endsWith("AndroidComposeView")) {
                return c;
            }
        }
        return tabView;
    }

    private static Method rootMethod(Object owner) throws Exception {
        if (sRootMethod == null) {
            if (!owner.getClass().getName().equals("b1.C")) throw new NoSuchMethodException("SemanticsOwner");
            sRootMethod = owner.getClass().getMethod("a");
        }
        return sRootMethod;
    }

    private static Method boundsMethod(Object root, Rect unused) throws Exception {
        if (sBoundsMethod == null) {
            if (!root.getClass().getName().equals("b1.y")) throw new NoSuchMethodException("SemanticsNode");
            sBoundsMethod = root.getClass().getMethod("g"); // boundsInRoot, NOT h/window or n/touch
        }
        return sBoundsMethod;
    }

    private static Method childrenMethod(Object root) throws Exception {
        if (sChildrenMethod == null) sChildrenMethod = root.getClass().getMethod("m");
        return sChildrenMethod;
    }

    private static Method[] configMethods(Class<?> node) throws Exception {
        if (sConfigMethods.length == 0) sConfigMethods = new Method[]{node.getMethod("o")};
        return sConfigMethods;
    }

    private static void walk(Object node, Method bounds, Method children, Method[] configs,
                             int dx, int dy, Rect host, int minH,
                             List<Hit> out, List<Rect> selected, List<Rect> navigation, int depth) throws Exception {
        if (node == null || depth > 14) {
            return;
        }
        Rect b = toRect(bounds.invoke(node));
        b.offset(dx, dy);
        boolean column = b.height() >= minH
                && b.width() > minH / 2
                && b.width() < host.width() / 2
                && b.top >= host.top - 8
                && b.bottom <= host.bottom + 8;
        int selection = selectionState(node, configs);
        if (selection >= 0 && b.width() > 0 && b.height() > 0) {
            navigation.add(b);
            if (selection == 1) selected.add(b);
        }
        Object list = children.invoke(node);
        if (list instanceof List) {
            List<?> kids = (List<?>) list;
            for (int i = 0; i < kids.size(); i++) {
                Object kid = kids.get(i);
                if (kid != null && bounds.getDeclaringClass().isInstance(kid)) {
                    walk(kid, bounds, children, configs, dx, dy, host, minH,
                            out, selected, navigation, depth + 1);
                }
            }
        }
        if (column) {
            out.add(new Hit(b, false));
        }
    }

    private static int selectionState(Object node, Method[] configs) {
        int selected = -1;
        for (int i = 0; i < configs.length; i++) {
            try {
                Object cfg = configs[i].invoke(node);
                if (!(cfg instanceof Iterable)) {
                    continue;
                }
                for (Object entry : (Iterable<?>) cfg) {
                    if (!(entry instanceof java.util.Map.Entry)) {
                        continue;
                    }
                    java.util.Map.Entry<?, ?> pair = (java.util.Map.Entry<?, ?>) entry;
                    String name = keyName(pair.getKey());
                    if (name.length() > 0 && sKeySample.length() < 280
                            && !sKeySample.contains(name + "=")) {
                        sKeySample = sKeySample + name + "=" + pair.getValue() + ";";
                    }
                    if ("Selected".equals(name) && pair.getValue() instanceof Boolean) {
                        selected = Boolean.TRUE.equals(pair.getValue()) ? 1 : 0;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return selected;
    }

    /** Semantics property keys keep their name in a String field. */
    private static String keyName(Object key) {
        if (key == null) {
            return "";
        }
        String found = "";
        try {
            for (java.lang.reflect.Field f : key.getClass().getDeclaredFields()) {
                if (f.getType() != String.class) {
                    continue;
                }
                f.setAccessible(true);
                Object value = f.get(key);
                if (!(value instanceof String)) {
                    continue;
                }
                String name = (String) value;
                if (name.length() == 0 || name.length() > 40) {
                    continue;
                }
                if ("Selected".equals(name)) {
                    return name;
                }
                if (found.length() == 0) {
                    found = name;
                }
            }
        } catch (Throwable ignored) {
        }
        return found;
    }

    private static Rect toRect(Object rect) throws Exception {
        if (sGetLeft == null || !sGetLeft.getDeclaringClass().isInstance(rect)) {
            Class<?> type = rect.getClass();
            sGetLeft = type.getMethod("getLeft");
            sGetTop = type.getMethod("getTop");
            sGetRight = type.getMethod("getRight");
            sGetBottom = type.getMethod("getBottom");
        }
        return new Rect(
                Math.round(((Number) sGetLeft.invoke(rect)).floatValue()),
                Math.round(((Number) sGetTop.invoke(rect)).floatValue()),
                Math.round(((Number) sGetRight.invoke(rect)).floatValue()),
                Math.round(((Number) sGetBottom.invoke(rect)).floatValue()));
    }

    /** Drops a node whose bounds sit inside a larger column, then orders left to right. */
    private static List<Hit> dedupe(List<Hit> found) {
        Collections.sort(found, Comparator.comparingInt(
                (Hit h) -> h.bounds.width() * h.bounds.height()).reversed());
        List<Hit> kept = new ArrayList<>();
        for (int i = 0; i < found.size(); i++) {
            Hit hit = found.get(i);
            boolean inside = false;
            for (int j = 0; j < kept.size(); j++) {
                Rect outer = kept.get(j).bounds;
                Rect inner = hit.bounds;
                int overlapW = Math.min(inner.right, outer.right)
                        - Math.max(inner.left, outer.left);
                int overlapH = Math.min(inner.bottom, outer.bottom)
                        - Math.max(inner.top, outer.top);
                boolean sameColumn = overlapW > inner.width() / 2
                        && overlapH > inner.height() / 2;
                if (sameColumn) {
                    inside = true;
                    if (hit.selected) kept.get(j).selected = true;
                    kept.get(j).navigation |= hit.navigation;
                    break;
                }
            }
            if (!inside) {
                kept.add(hit);
            }
        }
        Collections.sort(kept, Comparator.comparingInt(h -> h.bounds.left));
        return kept;
    }

    private static final class Hit {
        final Rect bounds;
        boolean selected;
        boolean navigation;

        Hit(Rect bounds, boolean selected) {
            this.bounds = bounds;
            this.selected = selected;
        }
    }
}

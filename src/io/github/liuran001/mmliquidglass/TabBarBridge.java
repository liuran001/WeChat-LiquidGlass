package io.github.liuran001.mmliquidglass;

import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import java.lang.reflect.Method;

/**
 * Bridge to the host app's own bottom tab bar.
 *
 * <p>Both targets obfuscate resource ids (AndResGuard turns them into
 * {@code app:id/huj}), so nothing here may look an id up by name. The UI class
 * names survive obfuscation, and with them the one method the app calls on
 * every in-app page switch — exactly the signal the droplet animation needs,
 * and the trigger that installs the pill in the first place.
 *
 * <pre>
 * WeChat: class LauncherUIBottomTabView extends RelativeLayout implements t1
 *         interface t1 { int getCurIdx(); void setTo(int); ... }
 *
 * QQ:     class QQTabWidget extends android.widget.TabWidget
 *         class QQTabLayout extends TabLayout implements FrameFragment$e
 *         interface FrameFragment$e { int getCurrentTab(); void setCurrentTab(int); ... }
 * </pre>
 *
 * <p>The class names themselves live in {@link HostApp}; this class only knows
 * how to work with them.
 */
final class TabBarBridge {

    private static volatile boolean sHooked;

    private TabBarBridge() {
    }

    static void install(HostApp app, ClassLoader cl) {
        if (sHooked) {
            return;
        }
        int hooked = 0;
        for (String className : app.tabViewClasses) {
            Class<?> cls;
            try {
                cls = cl.loadClass(className);
            } catch (Throwable t) {
                // Expected for QQ: only one of its two bars ships enabled, and
                // which one depends on a server switch.
                LiquidGlassModule.log(android.util.Log.INFO,
                        "tab bar class absent in this build: " + className);
                continue;
            }
            for (String methodName : app.tabSwitchMethods) {
                try {
                    // Declared, not inherited: QQ's bar extends the framework's
                    // TabWidget, and hooking that method on the base class would
                    // reach every TabWidget in the process.
                    Method m = cls.getDeclaredMethod(methodName, int.class);
                    LiquidGlassModule.hookAfter(m, chain -> {
                        Object thiz = chain.getThisObject();
                        Object arg0 = chain.getArg(0);
                        if (thiz instanceof View && arg0 instanceof Integer) {
                            LiquidGlassInstaller.onTabChanged((View) thiz, (Integer) arg0);
                        }
                    });
                    hooked++;
                    LiquidGlassModule.log(android.util.Log.INFO,
                            "hooked " + className + "." + methodName + "(int)");
                } catch (Throwable t) {
                    LiquidGlassModule.log(android.util.Log.WARN,
                            "no " + methodName + "(int) on " + className + ": " + t);
                }
            }
        }
        sHooked = hooked > 0;
        if (!sHooked) {
            LiquidGlassModule.log(android.util.Log.WARN,
                    "tab bar bridge unavailable for " + app + " (layout changed?);"
                            + " falling back to polling alone");
        }
    }

    /**
     * Matches by class name rather than {@code isInstance}. Both apps ship
     * Tinker hot-patching, so the loader that resolved our hook target is not
     * necessarily the loader the live view came from — an identity check
     * silently fails there, while the name always holds.
     */
    static boolean isTabView(View v) {
        HostApp app = LiquidGlassModule.app();
        return v != null && app != null && app.isTabViewClass(v.getClass().getName());
    }

    /** Tabs a bottom bar can plausibly have. */
    private static final int MIN_TABS = 3;
    private static final int MAX_TABS = 5;
    /** A tab has to be tall enough to stack an icon over a label. */
    private static final float MIN_TAB_HEIGHT_DP = 32f;

    /**
     * Locates the tab bar, by class name first and by shape only as a fallback.
     *
     * <p>The name is what actually holds today, and it is exact. The structural
     * pass exists for the day the app renames the class: it is deliberately
     * strict rather than best-effort, because the two failure modes are not
     * comparable. Finding nothing leaves the app with its own bar and costs the
     * user a feature; latching onto the wrong row would reparent some unrelated
     * control into a floating pill and break the app.
     */
    static ViewGroup locateTabView(View root) {
        ViewGroup byName = findTabView(root);
        if (byName != null) {
            return byName;
        }
        ViewGroup row = findTabRowByShape(root);
        if (row == null) {
            return null;
        }
        ViewGroup host = tightestWrapper(row);
        LiquidGlassModule.log(android.util.Log.WARN,
                "tab bar class not found; matched by shape instead: "
                        + host.getClass().getName()
                        + " tabs=" + row.getChildCount());
        return host;
    }

    /**
     * Whether this group is laid out the way a bottom tab row is.
     *
     * <p>Every one of these has to hold. The geometry alone would still admit a
     * toolbar or a row of action buttons, so it is the last test that decides:
     * either the children carry their own index as a tag, or exactly one of them
     * is selected. Ordinary button rows do neither.
     */
    private static boolean looksLikeTabRow(View v) {
        if (!(v instanceof ViewGroup) || v.getVisibility() != View.VISIBLE
                || v.getWidth() <= 0 || v.getHeight() <= 0) {
            return false;
        }
        ViewGroup g = (ViewGroup) v;
        View first = null;
        int prevRight = Integer.MIN_VALUE;
        int tabs = 0;
        int selected = 0;
        boolean indexTagged = true;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c.getVisibility() != View.VISIBLE) {
                continue;
            }
            if (first == null) {
                first = c;
            } else if (Math.abs(c.getWidth() - first.getWidth()) > 2) {
                return false; // tabs share one width
            }
            if (c.getLeft() < prevRight) {
                return false; // side by side, in order, not overlapping
            }
            prevRight = c.getRight();
            Object tag = c.getTag();
            if (!(tag instanceof Integer) || (Integer) tag != i) {
                indexTagged = false;
            }
            if (c.isSelected()) {
                selected++;
            }
            tabs++;
        }
        if (first == null || tabs < MIN_TABS || tabs > MAX_TABS) {
            return false;
        }
        View root = v.getRootView();
        if (root == null || root.getWidth() <= 0 || root.getHeight() <= 0) {
            return false;
        }
        if (v.getWidth() < root.getWidth() * 0.6f) {
            return false; // a tab bar spans most of the screen
        }
        float density = v.getResources().getDisplayMetrics().density;
        if (first.getHeight() < MIN_TAB_HEIGHT_DP * density) {
            return false;
        }
        int[] loc = new int[2];
        int[] rootLoc = new int[2];
        v.getLocationOnScreen(loc);
        root.getLocationOnScreen(rootLoc);
        float fromBottom = (rootLoc[1] + root.getHeight()) - (loc[1] + v.getHeight());
        if (fromBottom > root.getHeight() * 0.25f) {
            return false; // and sits at the bottom of it
        }
        return indexTagged || selected == 1;
    }

    /** Lowest group on screen that passes {@link #looksLikeTabRow}. */
    private static ViewGroup findTabRowByShape(View root) {
        if (root == null || root.getVisibility() != View.VISIBLE) {
            return null;
        }
        if (root instanceof LiquidGlassHostLayout) {
            return null; // our own bar, already installed
        }
        ViewGroup best = looksLikeTabRow(root) ? (ViewGroup) root : null;
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                ViewGroup found = findTabRowByShape(g.getChildAt(i));
                if (found != null && (best == null || lowerOnScreen(found, best))) {
                    best = found;
                }
            }
        }
        return best;
    }

    private static boolean lowerOnScreen(View a, View b) {
        int[] la = new int[2];
        int[] lb = new int[2];
        a.getLocationOnScreen(la);
        b.getLocationOnScreen(lb);
        return la[1] + a.getHeight() > lb[1] + b.getHeight();
    }

    /**
     * The smallest container that wraps the row, which is what gets reparented.
     *
     * <p>Stops as soon as an ancestor is taller than the row by any real margin:
     * past that it is a page, not the bar.
     */
    private static ViewGroup tightestWrapper(ViewGroup row) {
        ViewGroup best = row;
        android.view.ViewParent p = row.getParent();
        while (p instanceof ViewGroup && !(p instanceof LiquidGlassHostLayout)) {
            ViewGroup g = (ViewGroup) p;
            if (g.getHeight() > row.getHeight() * 1.6f) {
                break;
            }
            best = g;
            p = g.getParent();
        }
        return best;
    }

    /** Depth-first search for the app's tab bar under {@code root}, by class name. */
    static ViewGroup findTabView(View root) {
        if (isTabView(root)) {
            return root instanceof ViewGroup ? (ViewGroup) root : null;
        }
        if (!(root instanceof ViewGroup)) {
            return null;
        }
        ViewGroup vg = (ViewGroup) root;
        for (int i = 0; i < vg.getChildCount(); i++) {
            ViewGroup found = findTabView(vg.getChildAt(i));
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * The horizontal row holding the tabs.
     *
     * <p>WeChat builds it in code as a plain LinearLayout child of the tab view,
     * so it carries no id at all; QQ's material {@code TabLayout} keeps its tabs
     * in a {@code SlidingTabIndicator}, which is also a horizontal LinearLayout
     * child. QQ's other bar is an {@code android.widget.TabWidget}, which *is*
     * the row — it holds the tabs directly, with no wrapper in between.
     */
    static ViewGroup findTabRow(ViewGroup tabView) {
        if (tabView == null) {
            return null;
        }
        ViewGroup hiddenFallback = null;
        for (int i = 0; i < tabView.getChildCount(); i++) {
            View c = tabView.getChildAt(i);
            if (c instanceof LinearLayout
                    && ((LinearLayout) c).getOrientation() == LinearLayout.HORIZONTAL
                    && ((ViewGroup) c).getChildCount() >= 2) {
                if (c.getVisibility() == View.VISIBLE) {
                    return (ViewGroup) c;
                }
                if (hiddenFallback == null) {
                    hiddenFallback = (ViewGroup) c;
                }
            }
        }
        // No wrapper: the bar lays the tabs out itself. Covers TabWidget, and
        // shape-matched bars that were located as the row to begin with.
        if (tabView instanceof LinearLayout
                && ((LinearLayout) tabView).getOrientation() == LinearLayout.HORIZONTAL
                && tabView.getChildCount() >= 2) {
            return tabView;
        }
        if (looksLikeTabRow(tabView)) {
            return tabView;
        }
        return hiddenFallback;
    }

    /** Number of slots that actually participate in the row's layout. */
    static int tabCount(ViewGroup tabRow) {
        if (tabRow == null) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < tabRow.getChildCount(); i++) {
            if (tabRow.getChildAt(i).getVisibility() != View.GONE) {
                count++;
            }
        }
        return count;
    }

    /** Tab occupying a visible layout slot; GONE placeholders do not count. */
    static View tabAt(ViewGroup tabRow, int slot) {
        if (tabRow == null || slot < 0) {
            return null;
        }
        int current = 0;
        for (int i = 0; i < tabRow.getChildCount(); i++) {
            View c = tabRow.getChildAt(i);
            if (c.getVisibility() == View.GONE) {
                continue;
            }
            if (current == slot) {
                return c;
            }
            current++;
        }
        return null;
    }

    /**
     * Converts an app/logical index to the row's visible layout slot.
     *
     * <p>WeChat tags each tab with its logical index. QQ does not, so its raw
     * child position is used. Either way a GONE feature placeholder is skipped.
     */
    static int slotForIndex(ViewGroup tabRow, int index) {
        if (tabRow == null || index < 0) {
            return -1;
        }
        int slot = 0;
        int rawSlot = -1;
        for (int i = 0; i < tabRow.getChildCount(); i++) {
            View c = tabRow.getChildAt(i);
            if (c.getVisibility() == View.GONE) {
                continue;
            }
            Object tag = c.getTag();
            if (tag instanceof Integer && (Integer) tag == index) {
                return slot;
            }
            if (i == index) {
                rawSlot = slot;
            }
            slot++;
        }
        return rawSlot;
    }

    /** Lists the app-owned view classes under {@code root}, for miss diagnosis. */
    static String describeTree(View root) {
        StringBuilder sb = new StringBuilder();
        HostApp app = LiquidGlassModule.app();
        collectNames(root, sb, 0, app == null ? "com.tencent." : app.uiPrefix);
        return sb.length() == 0 ? "(no host-app views)" : sb.toString();
    }

    private static void collectNames(View v, StringBuilder sb, int depth, String prefix) {
        if (v == null || depth > 30 || sb.length() > 2000) {
            return;
        }
        String n = v.getClass().getName();
        if (n.startsWith(prefix) || n.contains("TabView") || n.contains("TabWidget")) {
            sb.append(depth).append(':').append(n).append(' ');
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                collectNames(vg.getChildAt(i), sb, depth + 1, prefix);
            }
        }
    }

    /**
     * Index of the visually selected tab, read straight off the view state.
     *
     * <p>The switch hooks turn out not to fire on ordinary tab taps in either
     * app, so the selection has to be observed rather than intercepted. Every
     * tab root gets {@code setSelected(true/false)} on each switch, which is
     * both reliable and free to poll.
     */
    static int selectedIndex(ViewGroup tabRow) {
        if (tabRow == null) {
            return -1;
        }
        int slot = 0;
        for (int i = 0; i < tabRow.getChildCount(); i++) {
            View c = tabRow.getChildAt(i);
            if (c.getVisibility() == View.GONE) {
                continue;
            }
            if (c.isSelected()) {
                return slot;
            }
            slot++;
        }
        return -1;
    }

    /**
     * The selected tab, asked of the bar directly and observed if it will not say.
     *
     * <p>The getter is not something every bar has. WeChat's does. Of QQ's two,
     * only {@code QQTabLayout} answers, and by an accident worth writing down:
     * it inherits {@code getCurrentTab()} from the copy of the material
     * {@code TabLayout} QQ ships, which has been patched to carry that method —
     * the stock class has no such thing. {@code QQTabWidget}, the bar almost
     * everyone actually runs, declares nothing of the sort and inherits
     * {@code android.widget.TabWidget}, which has no getter either, so the
     * lookup always throws there.
     *
     * <p>Hence the fallback rather than a second method name: the selection is
     * already on the views themselves, put there by {@code TabWidget}'s own
     * {@code setCurrentTab}, and {@link #selectedIndex} reads it off them. It is
     * the same signal the per-frame watcher runs on, so a bar that answers
     * neither way was never going to work.
     */
    static int currentIndex(View tabView) {
        HostApp app = LiquidGlassModule.app();
        if (app == null) {
            return -1;
        }
        ViewGroup row = tabView instanceof ViewGroup
                ? findTabRow((ViewGroup) tabView) : null;
        int selected = selectedIndex(row);
        if (selected >= 0) {
            return selected;
        }
        try {
            Method m = tabView.getClass().getMethod(app.currentIndexMethod);
            Object v = m.invoke(tabView);
            if (v instanceof Integer) {
                int slot = slotForIndex(row, (Integer) v);
                if (slot >= 0) {
                    return slot;
                }
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private static volatile boolean sHookedPager;

    static void tryHookPager(ViewGroup pager) {
        if (sHookedPager || pager == null) return;
        try {
            Class<?> viewPagerCls = pager.getClass();
            Method setItem = viewPagerCls.getMethod("setCurrentItem", int.class);
            Method setItemSmooth = viewPagerCls.getMethod("setCurrentItem", int.class, boolean.class);
            LiquidGlassModule.hookIntercept(setItem, chain -> {
                Object[] args = chain.getArgs().toArray();
                setItemSmooth.invoke(chain.getThisObject(), (Integer) args[0], true);
                return null;
            });
            sHookedPager = true;
            LiquidGlassModule.log(android.util.Log.INFO, "Hooked ViewPager: " + viewPagerCls.getName());
        } catch (Throwable t) {
            LiquidGlassModule.log(android.util.Log.WARN, "No ViewPager to hook: " + t);
        }
    }
}

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

    /** One hook per process; the instance it applies to is resolved per call. */
    private static volatile boolean sHookedPager;

    /**
     * Slot whose page the backdrop pager is actually showing, or -1 when that
     * cannot be established.
     *
     * <p>Asked reflectively because the two hosts ship different pagers —
     * WeChat's own {@code ViewPager} subclass, QQ's {@code ViewPager2} — and
     * {@code getCurrentItem()} is the one accessor both carry.
     *
     * <p>Only a row in which every child takes part in the layout is trusted. A
     * GONE tab shifts every later page by one, and following the page is not
     * worth putting the droplet on the wrong tab for.
     */
    static int pageSlot(ViewGroup pager, ViewGroup tabRow) {
        if (pager == null || tabRow == null
                || tabCount(tabRow) != tabRow.getChildCount()) {
            return -1;
        }
        int index = currentItem(pager);
        return index >= 0 && tabAt(tabRow, index) != null ? index : -1;
    }

    /** The pager's current page, or -1 when it is not a pager we can ask. */
    static int currentItem(Object pager) {
        if (pager == null) {
            return -1;
        }
        Method getter = pageGetter(pager.getClass());
        if (getter == null) {
            return -1;
        }
        try {
            Object value = getter.invoke(pager);
            if (value instanceof Integer) {
                return (Integer) value;
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    /** Pager class → its page getter; the callers run on every frame. */
    private static final java.util.HashMap<Class<?>, Method> sPageGetters =
            new java.util.HashMap<>();
    private static final java.util.HashSet<Class<?>> sNotAPager = new java.util.HashSet<>();

    private static Method pageGetter(Class<?> pagerClass) {
        Method cached = sPageGetters.get(pagerClass);
        if (cached != null) {
            return cached;
        }
        if (sNotAPager.contains(pagerClass)) {
            return null;
        }
        Method found = null;
        try {
            found = pagerClass.getMethod("getCurrentItem");
        } catch (Throwable ignored) {
            // A backdrop that is not a pager at all: remembered as an answer of
            // its own, so the lookup happens once rather than per frame.
        }
        if (found == null) {
            sNotAPager.add(pagerClass);
        } else {
            sPageGetters.put(pagerClass, found);
        }
        return found;
    }

    /**
     * Restores the page transition both hosts throw away on a tab tap.
     *
     * <p>Neither app animates the swap itself: the bar handler calls
     * {@code setCurrentItem(index, false)} and the pages hard-cut. Rewriting
     * that one flag to true hands the slide back to the pager, which is the
     * motion the droplet was already animating alongside.
     *
     * <p>A one-page move is the shape the hosts' own page-change handling was
     * written for — a finger can only ever drag across one boundary — so that
     * case is simply handed to the pager. Anything longer has to be earned:
     *
     * <p>WeChat hangs the ActionBar's title visibility and the tab/fragment
     * bookkeeping off {@code onPageScrolled}, keyed on the page and offset the
     * pager reports against the tab index the bar holds. A page the user is
     * merely passing over is reported with a non-zero offset, and the host
     * treats those as motion — but when a frame lands <em>exactly</em> on an
     * intermediate boundary the offset arrives as {@code 0.0f}, which the host
     * reads as "this page has settled": it moves its own selection onto that
     * page, resumes that page's fragment and marks it resumed. Two jumps in a
     * row — the reported 微信↔我 double tap, which crosses two pages each way —
     * then leave the title view at {@code View.GONE} and the top bar comes back
     * blank.
     *
     * <p>So a longer jump is only animated when the intermediate settles can be
     * held back from the app's listeners ({@link #holdIntermediateSettles}),
     * which leaves the app seeing exactly the event sequence a hard cut gives
     * it while the pages still slide. If that filter cannot be installed the
     * jump falls back to the app's own hard cut.
     *
     * <p>Hooked once and left in place: the method belongs to the app's class,
     * not to the instance, so re-hooking on every install would stack
     * redundant chains on the same executable.
     */
    static void tryHookPager(ViewGroup pager) {
        if (sHookedPager || pager == null) return;
        try {
            Class<?> cls = pager.getClass();
            Method setItemBool = null;
            while (cls != null && cls != Object.class) {
                try {
                    setItemBool = cls.getDeclaredMethod("setCurrentItem", int.class, boolean.class);
                    break;
                } catch (NoSuchMethodException ignored) {}
                cls = cls.getSuperclass();
            }
            if (setItemBool != null) {
                final Method setItemSmooth = setItemBool;
                LiquidGlassModule.hookIntercept(setItemSmooth, chain -> {
                    // Binding is per class, so every pager in the app lands
                    // here. On QQ the declaring class is AndroidX's own
                    // ViewPager2, shared with the profile card carousel and
                    // every TabLayoutMediator in the app; on WeChat it is the
                    // WxViewPager base of the home pager. Only the backdrop the
                    // glass is refracting may be rewritten — everywhere else an
                    // instant jump is what the app asked for, and turning it
                    // into a scroll animates UI this module has no business
                    // touching.
                    Object self = chain.getThisObject();
                    if (self != LiquidGlassInstaller.currentPager()) {
                        return chain.proceed();
                    }
                    Object[] args = chain.getArgs().toArray();
                    if (args.length >= 2 && args[1] instanceof Boolean
                            && (Boolean) args[1]) {
                        return chain.proceed(); // already a slide
                    }
                    if (args.length < 1 || !(args[0] instanceof Integer)) {
                        return chain.proceed();
                    }
                    int target = (Integer) args[0];
                    int current = currentItem(self);
                    if (current < 0) {
                        return chain.proceed(); // a pager we cannot ask
                    }
                    int distance = Math.abs(target - current);
                    if (distance == 0) {
                        return chain.proceed(); // the pager's own no-op
                    }
                    // This call is the live intent: whatever an earlier jump was
                    // still holding back ends here.
                    sJumpTarget = -1;
                    if (distance > 1) {
                        // Only if the host can be kept from seeing the pages the
                        // slide passes over; otherwise its own hard cut.
                        if (!(self instanceof ViewGroup)
                                || !holdIntermediateSettles((ViewGroup) self)) {
                            if (!sFilterRefused) {
                                sFilterRefused = true;
                                LiquidGlassModule.log(android.util.Log.WARN,
                                        "no page callbacks to filter on "
                                                + self.getClass().getName()
                                                + "; jumps over more than one page"
                                                + " keep the app's hard cut");
                            }
                            return chain.proceed();
                        }
                        sJumpTarget = target;
                        sJumpDeadlineMs = android.os.SystemClock.uptimeMillis()
                                + JUMP_WINDOW_MS;
                        LiquidGlassModule.log(android.util.Log.INFO,
                                "sliding " + current + " -> " + target
                                        + " with the settled pages held back");
                    }
                    // Re-enters this same hook one level down, where smooth is
                    // now true and the branch above proceeds: that is what
                    // stops it recursing.
                    setItemSmooth.invoke(self, args[0], true);
                    return null; // swallow the original hard-cut call
                });
                sHookedPager = true;
                LiquidGlassModule.log(android.util.Log.INFO,
                        "hooked " + setItemBool.getDeclaringClass().getName()
                                + ".setCurrentItem(int, boolean) for the backdrop's"
                                + " page transition");
            } else {
                LiquidGlassModule.log(android.util.Log.WARN,
                        "no setCurrentItem(int, boolean) on " + pager.getClass()
                                + ", pages will hard-cut between tabs");
            }
        } catch (Throwable t) {
            LiquidGlassModule.log(android.util.Log.WARN,
                    "could not hook the backdrop pager: " + t);
        }
    }

    /* ---------------- long jump: hold back the intermediate settles -------- */

    /**
     * Page an in-flight rewritten scroll is heading for, or -1 when none is.
     *
     * <p>Only a jump of more than one page ever sets it. A ±1 move is the shape
     * a drag produces, and the app has always handled that event sequence, so
     * there is nothing to hold back there.
     */
    private static volatile int sJumpTarget = -1;
    /** Uptime millis after which {@link #sJumpTarget} stops being honoured. */
    private static volatile long sJumpDeadlineMs;

    /**
     * How long a rewritten scroll may keep the filter open. The pager caps its
     * own animation at 600ms and the settle that follows is a frame or two
     * behind that; this only has to outlast it, and to be short enough that an
     * interrupted slide cannot leave the filter armed.
     */
    private static final long JUMP_WINDOW_MS = 800L;

    /** Listener classes already carrying the filter. */
    private static final java.util.HashSet<Class<?>> sFilteredListeners =
            new java.util.HashSet<>();
    private static boolean sFilterLogged;
    private static boolean sFilterRefused;

    /**
     * Keeps the app's page-change listeners from being told that a page the
     * slide is merely passing over has settled.
     *
     * <p>Nothing is added to what the app sees; the filter only withholds the
     * {@code positionOffset == 0} callback for pages that are not the
     * destination — the signal the host reads as "this page settled" and acts
     * on by moving its own tab selection and resuming that page. Everything the
     * slide animates on (the non-zero offsets that drive the bar's colour
     * cross-fade) still arrives, and so does the destination's own settle, so
     * the app's bookkeeping runs exactly as it would after a hard cut while the
     * pages still slide.
     *
     * <p>Found by shape rather than by name: the listeners are whatever the
     * pager holds in a list field, or in a field that answers the page callback,
     * so no host class is named here. Anything under {@code android*} /
     * {@code androidx*} is left alone — that is dispatch plumbing shared with
     * the rest of the app, and holding a callback back there would reach UI this
     * module has no business touching.
     *
     * @return whether every listener the pager currently has is filtered, i.e.
     *     whether a long jump can be animated without the app misreading it.
     */
    private static boolean holdIntermediateSettles(ViewGroup pager) {
        java.util.ArrayList<Object> listeners = pageListeners(pager);
        if (listeners.isEmpty()) {
            return false;
        }
        boolean ready = true;
        for (int i = 0; i < listeners.size(); i++) {
            Class<?> cls = listeners.get(i).getClass();
            if (sFilteredListeners.contains(cls)) {
                continue;
            }
            Method scrolled = pageCallback(cls, "onPageScrolled",
                    int.class, float.class, int.class);
            if (scrolled == null) {
                ready = false;
                continue;
            }
            try {
                LiquidGlassModule.hookIntercept(scrolled, chain -> {
                    int target = sJumpTarget;
                    if (target >= 0) {
                        if (android.os.SystemClock.uptimeMillis() > sJumpDeadlineMs) {
                            sJumpTarget = -1;
                        } else {
                            Object[] args = chain.getArgs().toArray();
                            int page = args.length > 0 && args[0] instanceof Integer
                                    ? (Integer) args[0] : Integer.MIN_VALUE;
                            float offset = args.length > 1 && args[1] instanceof Float
                                    ? (Float) args[1] : -1f;
                            if (offset == 0f) {
                                if (page != target) {
                                    LiquidGlassModule.log(android.util.Log.INFO,
                                            "settle of page " + page
                                                    + " held back (sliding to "
                                                    + target + ")");
                                    return null; // passed over, not settled
                                }
                                sJumpTarget = -1; // arrived
                                LiquidGlassModule.log(android.util.Log.INFO,
                                        "settle of page " + page
                                                + " passed through; slide done");
                            }
                        }
                    } else if (chain.getArgs().size() > 1
                            && chain.getArg(1) instanceof Float
                            && (Float) chain.getArg(1) == 0f) {
                        // No jump of ours in flight: whatever the app is doing
                        // with this settle is its own business, but it is the
                        // one event the top bar is rebuilt from, so it is worth
                        // a line when it goes wrong on a device.
                        LiquidGlassModule.log(android.util.Log.INFO,
                                "settle of page " + chain.getArg(0));
                    }
                    return chain.proceed();
                });
                Method stateChanged = pageCallback(cls,
                        "onPageScrollStateChanged", int.class);
                if (stateChanged != null) {
                    LiquidGlassModule.hookIntercept(stateChanged, chain -> {
                        Object[] args = chain.getArgs().toArray();
                        if (args.length > 0 && args[0] instanceof Integer) {
                            int state = (Integer) args[0];
                            LiquidGlassModule.log(android.util.Log.INFO,
                                    "scroll state " + state
                                            + (sJumpTarget >= 0
                                                    ? " (jump to " + sJumpTarget + ")"
                                                    : ""));
                            if (state == 1) {
                                // DRAGGING: a finger took over, so the jump is
                                // over and its destination will never report.
                                sJumpTarget = -1;
                            }
                        }
                        return chain.proceed();
                    });
                }
                sFilteredListeners.add(cls);
                if (!sFilterLogged) {
                    sFilterLogged = true;
                    LiquidGlassModule.log(android.util.Log.INFO,
                            "page callbacks filtered on " + cls.getName()
                                    + "; long tab jumps will slide");
                }
            } catch (Throwable t) {
                LiquidGlassModule.logErr("could not filter page callbacks", t);
                ready = false;
            }
        }
        return ready;
    }

    /** The pager's page-change listeners, found by shape and never by name. */
    private static java.util.ArrayList<Object> pageListeners(ViewGroup pager) {
        java.util.ArrayList<Object> found = new java.util.ArrayList<>(2);
        for (Class<?> k = pager.getClass(); k != null && k != Object.class;
                k = k.getSuperclass()) {
            java.lang.reflect.Field[] fields;
            try {
                fields = k.getDeclaredFields();
            } catch (Throwable t) {
                continue;
            }
            for (java.lang.reflect.Field f : fields) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                Object value;
                try {
                    f.setAccessible(true);
                    value = f.get(pager);
                } catch (Throwable t) {
                    continue;
                }
                if (value instanceof java.util.List) {
                    try {
                        for (Object item : (java.util.List<?>) value) {
                            if (isPageListener(item)) {
                                found.add(item);
                            }
                        }
                    } catch (Throwable ignored) {
                        // A list the app mutates under us is not the one holding
                        // the listeners; another field will answer.
                    }
                } else if (isPageListener(value)) {
                    found.add(value);
                }
            }
        }
        return found;
    }

    private static boolean isPageListener(Object o) {
        return o != null
                && !o.getClass().getName().startsWith("android")
                && pageCallback(o.getClass(), "onPageScrolled",
                        int.class, float.class, int.class) != null;
    }

    /** The callback as declared on {@code cls} or on an app base of it. */
    private static Method pageCallback(Class<?> cls, String name, Class<?>... params) {
        for (Class<?> k = cls; k != null && k != Object.class; k = k.getSuperclass()) {
            if (k.getName().startsWith("android")) {
                return null;
            }
            try {
                return k.getDeclaredMethod(name, params);
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable ignored) {
            }
        }
        return null;
    }
}

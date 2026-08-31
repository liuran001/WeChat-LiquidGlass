package io.github.liuran001.mmliquidglass;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.widget.FrameLayout;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

/**
 * Moves the host app's bottom tab bar into a floating liquid-glass pill.
 *
 * <p>Unlike the HeyBox original this is ported from, no layout surgery on the
 * content area is needed. Both supported apps already lay the content out at
 * full screen height with the tab bar floating over it inside a FrameLayout —
 * WeChat's {@code CustomViewPager}, QQ's {@code tabcontent} — so the backdrop
 * the glass refracts is there from the start. We only reparent the bar and
 * strip the solid colour the app paints behind it.
 *
 * <p>Nothing below names a class: the bar arrives already located (see
 * {@link TabBarBridge}) and everything from there on is geometry.
 */
final class LiquidGlassInstaller {

    /**
     * Retry budget for the first layout pass after the home activity resumes.
     * Both apps build their home screen asynchronously (WeChat's
     * FirstScreenFrameLayout and blink preloading; QQ's SplashActivity, which
     * may sit on a login screen first), so the bar can show up seconds after
     * onResume. This polling is only a safety net — the primary trigger is the
     * tab-switch hook.
     */
    private static final int MAX_ATTEMPTS = 40;
    private static final long RETRY_DELAY_MS = 250L;

    /** Scratch for on-screen positions; every use is on the UI thread. */
    private static final int[] sLoc = new int[2];
    private static boolean sKeepFailed;
    private static WeakReference<Activity> sActivityRef = new WeakReference<>(null);
    private static WeakReference<LiquidGlassHostLayout> sHostRef = new WeakReference<>(null);
    private static WeakReference<View> sDropletRef = new WeakReference<>(null);
    private static WeakReference<ViewGroup> sTabRowRef = new WeakReference<>(null);
    private static WeakReference<ViewGroup> sPagerRef = new WeakReference<>(null);
    private static int sLastIndex = -1;
    private static WeakReference<View> sTabViewRef = new WeakReference<>(null);
    private static WeakReference<View> sGlassRef = new WeakReference<>(null);
    private static DropletDragController sDrag;
    /** Identity/children fingerprint of the row the renderer is currently bound to. */
    private static int sTabStructureSignature;
    private static boolean sTabStructureRefreshPosted;
    /** Content height established during install, before QQ's navigation reserve. */
    private static int sBarHeight;
    /**
     * Last real navigation-bar inset seen before our edge-to-edge listener
     * strips it from the app's view tree. Kept across Activity recreation: QQ
     * rebuilds SplashActivity when its skin follows system day/night, while the
     * DecorView (and therefore the stripping listener) can survive long enough
     * for the replacement tab bar to report an inset of zero.
     */
    private static int sNavigationInset;
    /** The app's own frosted strip and the bar's hairline, held hidden. */
    private static WeakReference<View> sBlurLayerRef = new WeakReference<>(null);
    private static WeakReference<View> sHairlineRef = new WeakReference<>(null);
    private static boolean sBlurRelit;
    /** Droplet's resting Y inside the host, before WeChat's bar offset. */
    private static float sDropletBaseY;

    private LiquidGlassInstaller() {
    }

    /**
     * How long the stock bar stays hidden before it is handed back. Sized to
     * outlast the install polling above, so a slow cold start does not flash
     * the original bar a beat before the pill replaces it.
     */
    private static final long REVEAL_TIMEOUT_MS = 8000L;

    /**
     * Keeps the app's own bar invisible until the pill takes it over.
     *
     * <p>Installing cannot happen before the first layout, and the app has drawn
     * its own bar by then — which reads as a flash of the original on every cold
     * start. Hooking the bar's constructor would be the natural place to catch
     * it, but Tinker means the class our loader resolves is not the one the live
     * view comes from, so the hook never fires. Matching on the class name from
     * a pre-draw listener sidesteps that entirely, and pre-draw is the last
     * point before anything reaches the screen.
     *
     * <p>The timeout hands the bar back if the pill never arrives: a permanently
     * invisible tab bar would be far worse than a flash.
     */
    private static void hideStockBarUntilInstalled(View decor) {
        decor.getViewTreeObserver().addOnPreDrawListener(
                new ViewTreeObserver.OnPreDrawListener() {
                    private final long deadline =
                            android.os.SystemClock.uptimeMillis() + REVEAL_TIMEOUT_MS;
                    private View bar;

                    @Override
                    public boolean onPreDraw() {
                        if (bar == null || bar.getParent() == null) {
                            bar = TabBarBridge.findTabView(decor);
                        }
                        boolean installed = bar != null
                                && bar.getParent() instanceof LiquidGlassHostLayout;
                        boolean expired =
                                android.os.SystemClock.uptimeMillis() > deadline;
                        if (installed || expired) {
                            if (bar != null && !installed) {
                                bar.setAlpha(1f);
                                if (bar.getParent() instanceof ViewGroup) {
                                    showOwnBlurLayers((ViewGroup) bar.getParent());
                                }
                                LiquidGlassModule.log(android.util.Log.WARN,
                                        "pill never took over, stock bar restored");
                            }
                            decor.getViewTreeObserver().removeOnPreDrawListener(this);
                        } else if (bar != null && bar.getAlpha() != 0f) {
                            bar.setAlpha(0f);
                            // QQ's own frosted strip is a separate view and
                            // stays lit with the bar faded out, which reads as
                            // a grey band across the bottom for the whole cold
                            // start. It goes at the same moment the bar does.
                            if (bar.getParent() instanceof ViewGroup) {
                                hideOwnBlurLayers((ViewGroup) bar.getParent(), bar);
                            }
                        }
                        return true;
                    }
                });
    }

    static void scheduleInstall(Activity activity) {
        // Kept for extendUnderNavBar: WeChat's views hand out a context that does
        // not wrap the Activity, so the window is not reachable from them.
        sActivityRef = new WeakReference<>(activity);
        View decor = activity.getWindow().getDecorView();
        if (sHostRef.get() == null) {
            hideStockBarUntilInstalled(decor);
        }
        decor.post(() -> tryInstall(activity, decor, 0));
    }

    private static void tryInstall(Activity activity, View decor, int attempt) {
        try {
            if (activity.isFinishing() || activity.isDestroyed()) {
                return;
            }
            // Only skip if the pill is live in *this* window. WeChat's process
            // outlives a swipe-away from recents, so these statics still point at
            // the destroyed Activity's host — treating that as "already installed"
            // left the relaunched LauncherUI with its stock bar.
            LiquidGlassHostLayout live = sHostRef.get();
            if (live != null && live.isAttachedToWindow()
                    && live.getRootView() == decor.getRootView()) {
                // Nothing to install, but coming back from a chat or any other
                // screen rebuilds the window state: the navigation inset is
                // applied again and the dead strip at the bottom re-opens.
                reassertBottom(activity);
                return;
            }
            if (live != null) {
                resetState();
            }
            ViewGroup tabView = TabBarBridge.locateTabView(decor);
            if (tabView == null) {
                if (attempt < MAX_ATTEMPTS) {
                    decor.postDelayed(
                            () -> tryInstall(activity, decor, attempt + 1),
                            RETRY_DELAY_MS);
                } else {
                    LiquidGlassModule.log(android.util.Log.WARN,
                            "tab bar not found after " + MAX_ATTEMPTS
                                    + " attempts, giving up; tree="
                                    + TabBarBridge.describeTree(decor));
                }
                return;
            }
            install(tabView);
        } catch (Throwable t) {
            LiquidGlassModule.logErr("install failed", t);
        }
    }

    /** Drops references to a previous Activity's views so a relaunch reinstalls. */
    private static void resetState() {
        sHostRef = new WeakReference<>(null);
        sTabViewRef = new WeakReference<>(null);
        sGlassRef = new WeakReference<>(null);
        sDropletRef = new WeakReference<>(null);
        sTabRowRef = new WeakReference<>(null);
        sPagerRef = new WeakReference<>(null);
        sDrag = null;
        sTabStructureSignature = 0;
        sTabStructureRefreshPosted = false;
        sBarHeight = 0;
        sBlurLayerRef = new WeakReference<>(null);
        sHairlineRef = new WeakReference<>(null);
        sNavBgRef = new WeakReference<>(null);
        sBlurRelit = false;
        sLastIndex = -1;
        sDropletBaseY = 0f;
        LiquidGlassModule.log(android.util.Log.INFO,
                "stale host from a previous Activity dropped, reinstalling");
    }

    private static void install(ViewGroup tabView) {
        ViewGroup parent = tabView.getParent() instanceof ViewGroup
                ? (ViewGroup) tabView.getParent() : null;
        if (parent == null) {
            return;
        }
        if (parent instanceof LiquidGlassHostLayout) {
            return; // already installed
        }

        ViewGroup backdrop = findBackdrop(parent, tabView);
        if (backdrop == null) {
            LiquidGlassModule.log(android.util.Log.WARN,
                    "no backdrop sibling found, glass would refract nothing");
            return;
        }

        int index = parent.indexOfChild(tabView);
        if (index < 0) {
            return;
        }
        ViewGroup.LayoutParams originalLp = tabView.getLayoutParams();

        Context ctx = tabView.getContext();
        float density = ctx.getResources().getDisplayMetrics().density;
        int bottomOffset = Math.round(density * GlassConfig.barOffsetDp);

        int navigationInset = rememberNavigationInset(parent);

        LiquidGlassHostLayout host = new LiquidGlassHostLayout(ctx, backdrop, tabView);

        int navReserve = tabView.getPaddingBottom();
        // Read before the drop lands: the bar is still laid out at its old
        // height here, and the reserve is exactly what the pill must not keep.
        ViewGroup tabRow = TabBarBridge.findTabRow(tabView);
        int barHeight = contentBarHeight(tabRow, tabView.getHeight() - navReserve);

        // KernelSU's floating bar is width(IntrinsicSize.Min) — it hugs its tabs
        // and floats centred, rather than stretching edge to edge. The width has
        // to be resolved up front and pinned: leaving the host WRAP_CONTENT makes
        // the MATCH_PARENT glass layer measure to zero and vanish.
        // The shadow is drawn inside the host's own padding — setElevation never
        // renders anything in this view tree — so the host is inflated by that
        // much and pulled back down by the same amount.
        host.setupShadow(density, isNight(ctx));
        int shadowPad = host.shadowPad();

        TabGeometrySnapshot geometry = new TabGeometrySnapshot(tabView, tabRow);
        FrameLayout.LayoutParams hostLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL);
        // The navigation inset only counts once the window has been grown to
        // cover it — before that the pill's parent already stops above the
        // gesture bar, and adding it again floats the pill far too high.
        // Anchored on the parent, not the bar: the bar is about to be detached,
        // and a detached view reports no insets at all. Reading from it later
        // would silently drop the correction and let the bar sink onto the
        // gesture pill.
        hostLp.bottomMargin = bottomOffset - shadowPad;
        FrameLayout.LayoutParams tabLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                barHeight > 0 ? barHeight : ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP | android.view.Gravity.FILL_HORIZONTAL);

        // The native bar must never be left detached. All potentially failing
        // setup above runs while it still belongs to the app; this small
        // structural transaction either installs both levels or puts the bar
        // straight back at its original index.
        try {
            parent.removeView(tabView);
            parent.addView(host, index, hostLp);
            host.addView(tabView, tabLp);
        } catch (Throwable t) {
            restoreAfterFailedReparent(parent, tabView, host, index, originalLp);
            LiquidGlassModule.logErr("could not reparent the stock tab bar", t);
            return;
        }

        try {
            dropNavReserve(tabView);
            int barWidth = hugContentWidth(tabRow, density);
            if (barWidth > 0) {
                hostLp.width = barWidth + shadowPad * 2;
                host.setLayoutParams(hostLp);
            }
        } catch (Throwable t) {
            try {
                geometry.restore(density);
            } catch (Throwable restoreError) {
                LiquidGlassModule.logErr("could not restore stock tab geometry",
                        restoreError);
            }
            restoreAfterFailedReparent(parent, tabView, host, index, originalLp);
            LiquidGlassModule.logErr("could not size the floating tab bar", t);
            return;
        }

        // Safe to show again: the glass goes in below it in this same pass, so
        // the two appear together.
        tabView.setAlpha(1f);

        sHostRef = new WeakReference<>(host);
        sTabViewRef = new WeakReference<>(tabView);
        sTabRowRef = new WeakReference<>(tabRow);
        sTabStructureSignature = tabStructureSignature(tabRow);
        sTabStructureRefreshPosted = false;
        sPagerRef = new WeakReference<>(backdrop);
        sBarHeight = barHeight;
        sLastIndex = -1;

        TabBarBridge.tryHookPager(backdrop);

        // Cosmetic cleanup cannot invalidate the structural install. If an app
        // skin changes one of these details, keep the functional floating bar
        // and report the degraded effect rather than trying to tear it back out.
        try {
            hideOwnBlurLayers(parent, tabView);
            hideBarHairline(parent, tabView);
            stripSolidBackgrounds(tabView);
            disableTabWidgetStrips(tabView);
        } catch (Throwable t) {
            LiquidGlassModule.logErr("could not remove all stock tab chrome", t);
        }

        boolean underNav = extendUnderNavBar(ctx);
        if (underNav) {
            hostLp.bottomMargin = bottomOffset - shadowPad + navigationInset;
            host.setLayoutParams(hostLp);
        }
        if (underNav) {
            // The first real insets dispatch can arrive either side of host
            // creation. Re-read the shared cache on the next UI turn so both
            // orders converge on the same bottom anchor.
            host.post(() -> syncHostBottomInset(host, sNavigationInset));
        }

        // Open up clipping all the way to the content root: the droplet grows
        // past the pill while dragging, and any ancestor still clipping its
        // children would shear that overflow off.
        unclipAncestors(parent);
        attachRenderer(ctx, host, backdrop, density);
        installSelectionWatcher(host);
        watchBottomInset(host, backdrop);

        host.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    private boolean done;

                    @Override
                    public void onGlobalLayout() {
                        if (done) {
                            return;
                        }
                        done = true;
                        host.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        host.attach();
                        syncDropletSize(TabBarBridge.currentIndex(tabView));
                        extendPagesToBottom(backdrop);
                        // The window grows a beat after the flag is set, and the
                        // pages have to be re-stretched into the room that frees.
                        host.postDelayed(() -> extendPagesToBottom(backdrop), 500L);
                        LiquidGlassModule.log(android.util.Log.INFO,
                                "liquid glass installed: hostW=" + host.getWidth()
                                        + " hostH=" + host.getHeight()
                                        + " barH=" + tabView.getHeight()
                                        + " children=" + host.getChildCount());
                    }
                });
    }

    /** Restores the app-owned bar if the two-level reparent cannot complete. */
    private static void restoreAfterFailedReparent(
            ViewGroup parent, View tabView, LiquidGlassHostLayout host,
            int index, ViewGroup.LayoutParams originalLp) {
        try {
            if (tabView.getParent() instanceof ViewGroup
                    && tabView.getParent() != parent) {
                ((ViewGroup) tabView.getParent()).removeView(tabView);
            }
            if (host.getParent() instanceof ViewGroup) {
                ((ViewGroup) host.getParent()).removeView(host);
            }
            if (tabView.getParent() == null) {
                int safeIndex = Math.max(0, Math.min(index, parent.getChildCount()));
                if (originalLp != null) {
                    parent.addView(tabView, safeIndex, originalLp);
                } else {
                    parent.addView(tabView, safeIndex);
                }
            }
            tabView.setAlpha(1f);
        } catch (Throwable restoreError) {
            LiquidGlassModule.logErr("could not restore the stock tab bar", restoreError);
        }
    }

    /** Reversible geometry touched while turning equal-width tabs into a pill. */
    private static final class TabGeometrySnapshot {
        private final View mTabView;
        private final int[] mTabPadding = new int[4];
        private final ViewGroup mRow;
        private final int[] mRowPadding = new int[4];
        private final View[] mTabs;
        private final int[] mWidths;
        private final float[] mWeights;
        private final boolean[] mHadLayoutParams;

        TabGeometrySnapshot(View tabView, ViewGroup row) {
            mTabView = tabView;
            mTabPadding[0] = tabView.getPaddingLeft();
            mTabPadding[1] = tabView.getPaddingTop();
            mTabPadding[2] = tabView.getPaddingRight();
            mTabPadding[3] = tabView.getPaddingBottom();
            mRow = row;
            int count = row == null ? 0 : row.getChildCount();
            mTabs = new View[count];
            mWidths = new int[count];
            mWeights = new float[count];
            mHadLayoutParams = new boolean[count];
            if (row == null) {
                return;
            }
            mRowPadding[0] = row.getPaddingLeft();
            mRowPadding[1] = row.getPaddingTop();
            mRowPadding[2] = row.getPaddingRight();
            mRowPadding[3] = row.getPaddingBottom();
            for (int i = 0; i < count; i++) {
                View tab = row.getChildAt(i);
                mTabs[i] = tab;
                ViewGroup.LayoutParams lp = tab.getLayoutParams();
                if (lp == null) {
                    continue;
                }
                mHadLayoutParams[i] = true;
                mWidths[i] = lp.width;
                mWeights[i] = lp instanceof android.widget.LinearLayout.LayoutParams
                        ? ((android.widget.LinearLayout.LayoutParams) lp).weight : 0f;
            }
        }

        void restore(float density) {
            mTabView.setPadding(mTabPadding[0], mTabPadding[1],
                    mTabPadding[2], mTabPadding[3]);
            if (mRow == null) {
                return;
            }
            applyQqIconOnlyAlignment(mRow, false, density);
            mRow.setPadding(mRowPadding[0], mRowPadding[1],
                    mRowPadding[2], mRowPadding[3]);
            for (int i = 0; i < mTabs.length; i++) {
                if (!mHadLayoutParams[i]) {
                    continue;
                }
                View tab = mTabs[i];
                ViewGroup.LayoutParams lp = tab.getLayoutParams();
                if (lp == null) {
                    continue;
                }
                lp.width = mWidths[i];
                if (lp instanceof android.widget.LinearLayout.LayoutParams) {
                    ((android.widget.LinearLayout.LayoutParams) lp).weight = mWeights[i];
                }
                tab.setLayoutParams(lp);
            }
        }
    }

    private static final int EXTEND_TAG_KEY = 0x7F5A0001;

    /**
     * Lets every page's content run to the bottom of the screen, so the list
     * keeps rendering behind and below the floating pill.
     *
     * <p>WeChat sizes each page to stop where the docked bar used to start. Once
     * the bar floats, that band would otherwise show bare page background. The
     * content is stretched to the full height and the scrolling views get bottom
     * padding, so rows scroll through underneath the pill and the last one can
     * still clear it.
     *
     * <p>WeChat re-applies its own LayoutParams on later layout passes (that is
     * why the Contacts page kept snapping back), so each container also gets a
     * layout listener that re-stretches it whenever that happens.
     */
    private static void extendPagesToBottom(ViewGroup pager) {
        if (pager == null) {
            return;
        }
        // The pager itself first. A page can only be stretched as far as its
        // container reaches, and WeChat sizes the pager to stop where the docked
        // bar used to start. Left short, the band the bar vacated shows the root
        // layout's own background instead of the page — the pale strip that
        // appears under the floating pill. Its parent is the FrameLayout the bar
        // was pulled out of, so this is a plain margin/height fix with no
        // positioning rules to undo.
        ViewGroup pagerParent = pager.getParent() instanceof ViewGroup
                ? (ViewGroup) pager.getParent() : null;
        if (pagerParent != null) {
            stretchToBottom(pager, pagerParent.getHeight());
        }
        int target = pager.getHeight();
        for (int i = 0; i < pager.getChildCount(); i++) {
            View page = pager.getChildAt(i);
            if (page instanceof ViewGroup) {
                // And the page itself, for the same reason: WeChat sizes each
                // page to stop where the docked bar started, and a child can
                // only be stretched as far as the page it sits in. Left as it
                // is, the band the bar vacated shows the DecorView's own
                // background through the pager — the strip under the pill.
                dropBottomFrost(page);
                stretchToBottom(page, target);
                extendOnePage((ViewGroup) page, target);
            }
        }
    }

    /**
     * Switches off the frosted band WeChat paints along the bottom of a page.
     *
     * <p>New in 8.0.72: every page is a {@code FrostedContentView} which blurs a
     * strip of its own content the height of the docked tab bar, so the bar
     * reads as frosted glass over the page behind it. Once the bar floats, that
     * strip has nothing sitting on it any more and shows up on its own — a band
     * across the bottom of the screen, lighter than the page, running the full
     * width under the pill. It is painted in {@code dispatchDraw} rather than by
     * a child view, which is why it never shows up in a view-tree dump.
     *
     * <p>Only the bottom band is dropped. The same view frosts a strip under the
     * status bar, and that one still sits behind a real bar — it is WeChat's own
     * look and none of our business.
     *
     * <p>Re-applied every frame rather than once, because setting it is not
     * final: the setter only writes the field, and WeChat re-applies the whole
     * frosted configuration (its {@code a(boolean, int, float)}) on theme and
     * layout changes, which puts the band straight back. Running on the same
     * pre-draw pass as {@link #keepNavBarClear()} means the value is right for
     * the frame about to be drawn, whenever it was last clobbered.
     *
     * <p>Resolved by method name rather than by type: the class is WeChat's own
     * and absent from QQ and from every WeChat before 8.0.72, which land on the
     * NoSuchMethod path once and are never probed again.
     */
    private static void dropBottomFrostAll() {
        ViewGroup pager = sPagerRef.get();
        if (pager == null) {
            return;
        }
        for (int i = 0; i < pager.getChildCount(); i++) {
            dropBottomFrost(pager.getChildAt(i));
        }
    }

    /** Per-page-class cache for WeChat's optional frosted-band API. */
    private static final java.util.HashMap<Class<?>, Method[]> sFrostMethods =
            new java.util.HashMap<>();
    private static final java.util.HashSet<Class<?>> sNoFrostClasses =
            new java.util.HashSet<>();
    private static boolean sFrostLogged;

    private static void dropBottomFrost(View page) {
        Class<?> pageClass = page.getClass();
        Method[] methods = sFrostMethods.get(pageClass);
        if (methods == null && !sNoFrostClasses.contains(pageClass)) {
            try {
                methods = new Method[]{
                        pageClass.getMethod("getBottomBlurAreaHeight"),
                        pageClass.getMethod("setBottomBlurAreaHeight", int.class),
                };
                sFrostMethods.put(pageClass, methods);
            } catch (NoSuchMethodException e) {
                sNoFrostClasses.add(pageClass);
            } catch (Throwable t) {
                sNoFrostClasses.add(pageClass);
                LiquidGlassModule.logErr("frosted band lookup", t);
            }
        }
        if (methods == null) {
            return;
        }
        try {
            Object h = methods[0].invoke(page);
            if (!(h instanceof Integer) || (Integer) h == 0) {
                return;
            }
            methods[1].invoke(page, 0);
            if (!sFrostLogged) {
                sFrostLogged = true;
                LiquidGlassModule.log(android.util.Log.INFO,
                        "dropping the pages' " + h + "px frosted bottom band");
            }
        } catch (Throwable ignored) {
            // Per-frame path: a broken page must not spam the log.
        }
    }

    /**
     * @param targetHeight height the page is being stretched to. Passed in
     *     rather than read off the page because the stretch above only takes
     *     effect on the next layout pass, so the page still reports the old,
     *     short height on this one.
     */
    private static void extendOnePage(ViewGroup pg, int targetHeight) {
        int pageHeight = Math.max(pg.getHeight(), targetHeight);
        if (pageHeight <= 0) {
            return;
        }
        // Some pages (Contacts) reserve the bar's height as padding on the page
        // itself rather than as a child margin, which caps the content at 2434
        // no matter what its LayoutParams say. Drop it and let the scrollers
        // carry the inset instead.
        if (pg.getPaddingBottom() > 0) {
            pg.setClipToPadding(false);
            pg.setPadding(pg.getPaddingLeft(), pg.getPaddingTop(),
                    pg.getPaddingRight(), 0);
        }
        for (int i = 0; i < pg.getChildCount(); i++) {
            View c = pg.getChildAt(i);
            if (c.getVisibility() != View.VISIBLE
                    || c.getHeight() < pageHeight / 2) {
                continue;
            }
            stretchToBottom(c, pageHeight);
            keepStretchedToBottom(c);
        }
        padScrollersBottom(pg, bottomReserve(pg), 0);
    }

    /** Breathing room between the last row and the pill once it is scrolled clear. */
    private static final float LAST_ROW_GAP_DP = 8f;

    /**
     * Room the floating bar takes up at the bottom of the screen.
     *
     * <p>Measured off the pill rather than assembled from the constants that
     * position it, so it stays right whatever the bar height, the float offset
     * or the navigation inset turn out to be.
     */
    private static int bottomReserve(View anchor) {
        LiquidGlassHostLayout host = sHostRef.get();
        if (host == null || host.getHeight() <= 0) {
            return 0;
        }
        host.getLocationOnScreen(sLoc);
        // Undo the offset followBarOffset applies while the bar slides away.
        float pillTop = sLoc[1] - host.getTranslationY() + host.getPaddingTop();
        View root = host.getRootView();
        if (root == null || root.getHeight() <= 0) {
            return 0;
        }
        root.getLocationOnScreen(sLoc);
        float density = anchor.getResources().getDisplayMetrics().density;
        float reserve = (sLoc[1] + root.getHeight()) - pillTop
                + LAST_ROW_GAP_DP * density;
        return reserve > 0f ? Math.round(reserve) : 0;
    }

    private static void stretchToBottom(View c, int pageHeight) {
        if (pageHeight <= 0) {
            return;
        }
        int gap = pageHeight - c.getBottom();
        if (gap <= 8) {
            return; // already reaches the bottom
        }
        ViewGroup.LayoutParams lp = c.getLayoutParams();
        if (!(lp instanceof ViewGroup.MarginLayoutParams)) {
            return;
        }
        ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
        boolean changed = false;
        if (mlp.bottomMargin != 0) {
            mlp.bottomMargin = 0;
            changed = true;
        }
        if (mlp.height >= 0) {
            mlp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            changed = true;
        }
        if (changed) {
            c.setLayoutParams(mlp);
        }
    }

    /** Re-applies a bottom stretch when the host page restores its docked size. */
    private static void keepStretchedToBottom(View c) {
        if (Boolean.TRUE.equals(c.getTag(EXTEND_TAG_KEY))) {
            return;
        }
        c.setTag(EXTEND_TAG_KEY, Boolean.TRUE);
        c.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or2, ob) -> {
            ViewGroup parent = v.getParent() instanceof ViewGroup
                    ? (ViewGroup) v.getParent() : null;
            if (parent == null) {
                return;
            }
            dropParentBottomReserve(parent, v);
            ViewGroup grand = parent.getParent() instanceof ViewGroup
                    ? (ViewGroup) parent.getParent() : null;
            if (grand != null) {
                stretchToBottom(parent, grand.getHeight());
            }
            stretchToBottom(v, Math.max(parent.getHeight(),
                    grand == null ? 0 : grand.getHeight()));
        });
    }

    /**
     * Removes a docked-bar reserve expressed as padding on the scroller's parent.
     *
     * <p>QQ's Dynamic page uses a full-height {@code QZoneBaseBlockContainer}
     * with 152px bottom padding, so its MATCH_PARENT feed stops at 2628px inside
     * a 2780px page. The feed gets its own scroll padding below, therefore this
     * outer reserve must be dropped just like page-level bottom padding.
     */
    private static void dropParentBottomReserve(ViewGroup parent, View child) {
        int gap = parent.getHeight() - child.getBottom();
        int reserve = parent.getPaddingBottom();
        ViewGroup pager = sPagerRef.get();
        int pageHeight = pager == null ? parent.getHeight() : pager.getHeight();
        if (child.getTop() > 8 || gap <= 8 || reserve <= 0
                || Math.abs(parent.getHeight() - pageHeight) > 8
                || (sBarHeight > 0 && Math.abs(gap - sBarHeight) > 8)
                || Math.abs(reserve - gap) > 8) {
            return;
        }
        int remaining = Math.max(0, reserve - gap);
        parent.setClipToPadding(false);
        parent.setPadding(parent.getPaddingLeft(), parent.getPaddingTop(),
                parent.getPaddingRight(), remaining);
    }

    /**
     * Gives every scrolling view in the subtree room to scroll its last row clear
     * of the floating pill, with {@code clipToPadding=false} so rows still render
     * through the padded band — i.e. behind and below the pill.
     */
    private static void padScrollersBottom(ViewGroup root, int pad, int depth) {
        if (depth > 12) {
            return;
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            View c = root.getChildAt(i);
            // ViewPager2 is backed by a RecyclerView, but that RecyclerView is
            // only the horizontal page host. Treating it as the page scroller
            // stops the walk here and leaves QQ's real BounceScrollView several
            // levels below untouched. Pass through pager RecyclerViews and pad
            // the first actual scrolling container inside each page instead.
            if (isViewPagerRecycler(c)) {
                padScrollersBottom((ViewGroup) c, pad, depth + 1);
            } else if (isScroller(c)) {
                ViewGroup sv = (ViewGroup) c;
                // QQ's Dynamic page nests its real feed RecyclerView several
                // containers below the page root and still measures it to the
                // old docked bar. Its parent owns that reserve as bottom padding,
                // so remove it before stretching the feed itself. Only touch a
                // top-anchored scroller; title-offset lists keep their origin.
                int parentHeight = root.getHeight();
                if (c.getTop() <= 8 && parentHeight - c.getBottom() > 8) {
                    dropParentBottomReserve(root, c);
                    stretchToBottom(c, parentHeight);
                    keepStretchedToBottom(c);
                }
                // clipToPadding is re-asserted even when the amount already
                // matches: WeChat turns it back on, and with it on the padded
                // band goes blank instead of showing rows through the glass.
                if (sv.getClipToPadding()) {
                    sv.setClipToPadding(false);
                }
                if (sv.getPaddingBottom() != pad) {
                    sv.setPadding(sv.getPaddingLeft(), sv.getPaddingTop(),
                            sv.getPaddingRight(), pad);
                }
            } else if (c instanceof ViewGroup) {
                padScrollersBottom((ViewGroup) c, pad, depth + 1);
            }
        }
    }

    /** The RecyclerView used internally by ViewPager2 scrolls between pages. */
    private static boolean isViewPagerRecycler(View v) {
        if (!(v instanceof ViewGroup)) {
            return false;
        }
        for (Class<?> k = v.getClass(); k != null; k = k.getSuperclass()) {
            if ("androidx.viewpager2.widget.ViewPager2$RecyclerViewImpl"
                    .equals(k.getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Recognises scrolling containers without compile-time access to androidx,
     * walking the superclass chain so WeChat's own subclasses match too.
     */
    private static boolean isScroller(View v) {
        if (!(v instanceof ViewGroup)) {
            return false;
        }
        if (v instanceof android.widget.ScrollView
                || v instanceof android.widget.AbsListView) {
            return true;
        }
        for (Class<?> k = v.getClass(); k != null; k = k.getSuperclass()) {
            String n = k.getName();
            if ("androidx.recyclerview.widget.RecyclerView".equals(n)
                    || "androidx.core.widget.NestedScrollView".equals(n)) {
                return true;
            }
        }
        return false;
    }

    /** Lets the droplet's overflow escape every ancestor up to the content view. */
    private static void unclipAncestors(ViewGroup from) {
        ViewGroup v = from;
        for (int i = 0; i < 12 && v != null; i++) {
            v.setClipChildren(false);
            v.setClipToPadding(false);
            if (v.getId() == android.R.id.content) {
                return;
            }
            android.view.ViewParent p = v.getParent();
            v = p instanceof ViewGroup ? (ViewGroup) p : null;
        }
    }

    /** Saved host translation while QQ's title-less layout is active. */
    private static final int ICON_ONLY_TRANSLATION_TAG_KEY = 0x7F5A0003;

    /**
     * Finds QQ's real tab title, excluding unread badges.
     *
     * <p>QAuxiliary's "hide bottom tab title" hook does not clear the text or
     * hide the view. It leaves QQ's {@code QUIBlendTextView} visible and sets
     * both LayoutParams dimensions to zero. QQ also tags that TextView with its
     * original title, which is the strongest discriminator from badge views;
     * the class/background fallback keeps this working if that implementation
     * detail changes in a later build.
     */
    private static android.widget.TextView findTabTitle(View v) {
        if (v instanceof android.widget.TextView) {
            android.widget.TextView text = (android.widget.TextView) v;
            CharSequence value = text.getText();
            String name = v.getClass().getName();
            boolean nonEmpty = value != null && value.toString().trim().length() > 0;
            boolean badge = name.contains("Badge") || name.contains("RedTouch");
            Object tag = v.getTag();
            if (nonEmpty && !badge
                    && (tag instanceof CharSequence
                    || name.contains("BlendTextView")
                    || v.getBackground() == null)) {
                return text;
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) v;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.widget.TextView found = findTabTitle(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** Whether a title still occupies a real slot in its tab. */
    private static boolean hasUsableTabTitle(View tab) {
        android.widget.TextView title = findTabTitle(tab);
        if (title == null || title.getVisibility() != View.VISIBLE) {
            return false;
        }
        ViewGroup.LayoutParams lp = title.getLayoutParams();
        return lp == null || (lp.width != 0 && lp.height != 0);
    }

    /** Finds the app-owned icon view inside one tab. */
    private static View findTabIcon(View v) {
        HostApp app = LiquidGlassModule.app();
        if (app != null && app.isTabIconClass(v.getClass().getName())) {
            return v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) v;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findTabIcon(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * True when QQ still has icon views but none of its tabs has a usable title.
     * Restricting this to QQ avoids treating WeChat's transient construction
     * frames as a deliberate icon-only layout.
     */
    private static boolean isQqIconOnlyRow(ViewGroup tabRow) {
        if (LiquidGlassModule.app() != HostApp.QQ || tabRow == null) {
            return false;
        }
        int icons = 0;
        int titles = 0;
        for (int i = 0; i < tabRow.getChildCount(); i++) {
            View tab = tabRow.getChildAt(i);
            if (tab.getVisibility() == View.GONE || findTabIcon(tab) == null) {
                continue;
            }
            icons++;
            if (hasUsableTabTitle(tab)) {
                titles++;
            }
        }
        return icons > 0 && titles == 0;
    }

    /** Adds or removes one reversible vertical offset from an app-owned view. */
    private static void setIconOnlyTranslation(View v, boolean iconOnly, float offset) {
        Object saved = v.getTag(ICON_ONLY_TRANSLATION_TAG_KEY);
        if (iconOnly) {
            float base;
            if (saved instanceof Float) {
                base = (Float) saved;
            } else {
                base = v.getTranslationY();
                v.setTag(ICON_ONLY_TRANSLATION_TAG_KEY, base);
            }
            float desired = base + offset;
            if (Math.abs(v.getTranslationY() - desired) > 0.5f) {
                v.setTranslationY(desired);
            }
        } else if (saved instanceof Float) {
            v.setTranslationY((Float) saved);
            v.setTag(ICON_ONLY_TRANSLATION_TAG_KEY, null);
        }
    }

    /** Moves QQ's icon and its unread badge together into the vacated title slot. */
    private static void alignQqIconOnlyContent(View v, boolean iconOnly, float offset) {
        String name = v.getClass().getName();
        HostApp app = LiquidGlassModule.app();
        if ((app != null && app.isTabIconClass(name))
                || name.contains("Badge")) {
            setIconOnlyTranslation(v, iconOnly, offset);
        }
        if (v instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) v;
            for (int i = 0; i < group.getChildCount(); i++) {
                alignQqIconOnlyContent(group.getChildAt(i), iconOnly, offset);
            }
        }
    }

    /**
     * QQ draws its 29dp icon below 5dp top padding in a 54dp tab. With the title
     * removed, 7.5dp is the exact shift from that icon centre to the tab centre.
     *
     * <p>Only the actual icon and QUIBadge move. Several tabs wrap their content
     * in a full-size TianshuRedTouch; moving that wrapper as well as its children
     * applies the offset twice and makes those tabs visibly lower than the first.
     */
    private static void applyQqIconOnlyAlignment(
            ViewGroup tabRow, boolean iconOnly, float density) {
        float offset = density * 7.5f;
        for (int i = 0; i < tabRow.getChildCount(); i++) {
            View tab = tabRow.getChildAt(i);
            if (tab.getVisibility() != View.GONE) {
                alignQqIconOnlyContent(tab, iconOnly, offset);
            }
        }
    }

    /**
     * Width of the widest visible leaf inside a tab column.
     *
     * <p>Measuring the column with an UNSPECIFIED spec is not dependable on its
     * own. WeChat's tab is a RelativeLayout wrapping a chain of MATCH_PARENT
     * children, and a MATCH_PARENT child under an unspecified parent resolves to
     * an UNSPECIFIED spec of size zero — so what comes back is whatever that
     * chain collapses to rather than the label's own width, and the pill ends up
     * hugging a value several times too small. The row is already laid out by the
     * time this runs and its leaves — the icon and the label — carry their real
     * widths, so they are read straight off instead.
     *
     * <p>Only visible leaves count: the unread badge and the red dot sit at
     * INVISIBLE rather than GONE, so they are still laid out, and they are wide
     * enough to stretch the column if allowed to.
     */
    private static int leafContentWidth(View v) {
        if (v.getVisibility() != View.VISIBLE) {
            return 0;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            int widest = 0;
            for (int i = 0; i < g.getChildCount(); i++) {
                widest = Math.max(widest, leafContentWidth(g.getChildAt(i)));
            }
            return widest;
        }
        // A MATCH_PARENT leaf is only ever as wide as the column it was handed,
        // so it says nothing about what is drawn inside it. QQ's icon is one —
        // it fills the whole 305px column while the glyph in it is about 55px —
        // and counting it would size every column to the screen quarter it
        // already had, which is the opposite of hugging the content.
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp != null && lp.width == ViewGroup.LayoutParams.MATCH_PARENT) {
            return 0;
        }
        return v.getWidth();
    }

    /**
     * Replaces the app's equal-weight tab columns with fixed, content-sized ones.
     *
     * <p>Both apps give each tab {@code LinearLayout.LayoutParams(0, h, weight=1)}
     * — WeChat in its own layout, QQ by way of {@code TabWidget.addView} — which
     * only makes sense when the bar spans the screen. For a floating pill the
     * width has to come from the content instead, mirroring KernelSU, where the
     * row is {@code IntrinsicSize.Min} and every tab shares the widest column's
     * width.
     */
    private static int hugContentWidth(ViewGroup tabRow, float density) {
        if (tabRow == null || tabRow.getChildCount() == 0) {
            return 0;
        }
        boolean iconOnly = isQqIconOnlyRow(tabRow);
        applyQqIconOnlyAlignment(tabRow, iconOnly, density);
        int childCount = tabRow.getChildCount();
        int count = 0;
        int unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        int measured = 0;
        int leaf = 0;
        int slot = 0;
        for (int i = 0; i < childCount; i++) {
            View tab = tabRow.getChildAt(i);
            if (tab.getVisibility() == View.GONE) {
                continue;
            }
            count++;
            tab.measure(unspecified, unspecified);
            measured = Math.max(measured, tab.getMeasuredWidth());
            leaf = Math.max(leaf, leafContentWidth(tab));
            slot = Math.max(slot, tab.getWidth());
        }
        if (count == 0) {
            return 0;
        }
        // An unbounded measure that comes back wider than the column the tab is
        // already laid out in has not measured content at all — it has fallen
        // over. QQ reads 737px for a 305px column, because measuring a
        // MATCH_PARENT child against UNSPECIFIED is undefined and its
        // RelativeLayout answers with something unrelated. WeChat's 105px for
        // the same 305px column is a real content width and is kept.
        if (slot > 0 && measured > slot) {
            LiquidGlassModule.log(android.util.Log.INFO,
                    "unbounded measure returned " + measured + " for a " + slot
                            + "px column; using the leaf width instead");
            measured = 0;
        }
        // Whichever reads wider. Each can come back short on its own — the
        // measure when the layout will not measure unbounded, the leaves when
        // the widest thing in the tab sizes itself to the column — so the
        // maximum is the one that holds for both apps.
        // In QAuxiliary's icon-only mode the MATCH_PARENT icon is the only real
        // content. Its view width is the old full-screen slot rather than the
        // 29dp glyph it draws, while badges are incidental and must not make the
        // pill grow and shrink with unread counts. Use a stable 24dp content
        // basis, which together with the normal 32dp breathing room gives each
        // icon a 56dp touch column.
        int widest = iconOnly ? Math.round(density * 24f) : Math.max(measured, leaf);
        if (widest <= 0) {
            return 0;
        }
        // WeChat's labels are narrower than KernelSU's, so hugging them exactly
        // gives a cramped 46%-wide bar. Matching KernelSU's proportions means
        // giving each column the same generous breathing room it uses (~32dp per
        // side), then capping so the pill still clears the screen edges.
        int pad = Math.round(density * 4f);
        int tabWidth = widest + Math.round(density * 32f);
        int screen = tabRow.getResources().getDisplayMetrics().widthPixels;
        int maxTotal = screen - Math.round(density * 24f);
        if (tabWidth * count + pad * 2 > maxTotal) {
            tabWidth = (maxTotal - pad * 2) / count;
        }
        for (int i = 0; i < childCount; i++) {
            View tab = tabRow.getChildAt(i);
            if (tab.getVisibility() == View.GONE) {
                continue;
            }
            ViewGroup.LayoutParams lp = tab.getLayoutParams();
            if (lp == null) {
                continue;
            }
            lp.width = tabWidth;
            if (lp instanceof android.widget.LinearLayout.LayoutParams) {
                ((android.widget.LinearLayout.LayoutParams) lp).weight = 0f;
            }
            tab.setLayoutParams(lp);
        }
        // Horizontal inset only: the bar's height is WeChat's own and adding
        // vertical padding pushes the tabs out through the bottom of the pill.
        tabRow.setPadding(pad, 0, pad, 0);
        int total = tabWidth * count + pad * 2;
        LiquidGlassModule.log(android.util.Log.INFO,
                "tab row hugged: content=" + widest
                        + " (measured=" + measured + " leaf=" + leaf + ")"
                        + " mode=" + (iconOnly ? "icon-only" : "title")
                        + " tabWidth=" + tabWidth
                        + " total=" + total + " screen=" + screen);
        return total;
    }

    /**
     * Keeps the glass and shadow travelling with WeChat's own bar.
     *
     * <p>WeChat slides the bar out of the way with {@code translationY} — that is
     * how it gets out of the way for the mini-program panel — and fades it with
     * alpha. Since only the bar itself was reparented into the host, those
     * properties would otherwise move the tabs while the glass and its shadow
     * stayed put. Mirroring them onto our own layers keeps the pill whole, and
     * reading rather than overwriting them leaves WeChat's animator alone.
     */
    /**
     * How much further than WeChat the bar has to travel to leave the screen,
     * as a fraction of WeChat's own travel. Zero once the pill already clears.
     */
    private static float hideShortfall(LiquidGlassHostLayout host, View tabView) {
        float travel = tabView.getHeight();
        if (travel <= 0f) {
            return 0f;
        }
        host.getLocationOnScreen(sLoc);
        // Undo the offset this very method applied, so the reference stays put.
        float pillTop = sLoc[1] - host.getTranslationY() + host.getPaddingTop();
        View root = host.getRootView();
        root.getLocationOnScreen(sLoc);
        float need = sLoc[1] + root.getHeight() - pillTop;
        return Math.max(0f, need / travel - 1f);
    }

    private static void followBarOffset(LiquidGlassHostLayout host) {
        View tabView = sTabViewRef.get();
        if (tabView == null) {
            return;
        }
        // Both apps repaint the bar's own background when the theme changes —
        // QQ through its skin engine, which hands the bar a fresh opaque
        // drawable and would bury the glass under it. Nothing announces that,
        // so it is re-checked here; the common case is a null field read.
        if (tabView.getBackground() != null) {
            stripSolidBackgrounds(tabView);
        }
        float ty = tabView.getTranslationY();
        float alpha = tabView.getAlpha();
        boolean gone = tabView.getVisibility() != View.VISIBLE;

        // WeChat slides its bar down by exactly its own height, which was enough
        // to clear the bottom of the screen when the bar sat flush against it.
        // The pill floats above that, so the same travel leaves a slice of it
        // still showing when WeChat gives up and hides the bar outright — it
        // vanishes mid-slide. The shortfall is added to the host rather than to
        // the glass: the tab icons are carried by WeChat's own translation, and
        // moving the glass alone would slide it out from under them.
        // Only worth computing while the bar is actually moving.
        host.setTranslationY(ty == 0f ? 0f : hideShortfall(host, tabView) * ty);

        View glass = sGlassRef.get();
        if (glass != null && glass.getTranslationY() != ty) {
            glass.setTranslationY(ty);
        }
        View droplet = sDropletRef.get();
        if (droplet != null) {
            droplet.setTranslationY(sDropletBaseY + ty);
            droplet.setAlpha(gone ? 0f : alpha);
        }
        if (glass != null) {
            glass.setAlpha(gone ? 0f : alpha);
        }
        host.setShadowOffsetY(gone ? Float.MAX_VALUE : ty, alpha);
    }

    /** Puts a layer back to GONE if the app re-showed it; reports once. */
    private static void holdHidden(View v, boolean report) {
        if (v == null || v.getVisibility() == View.GONE) {
            return;
        }
        v.setVisibility(View.GONE);
        if (report && !sBlurRelit) {
            sBlurRelit = true;
            LiquidGlassModule.log(android.util.Log.INFO,
                    "the app re-lit its own blur layer; holding it hidden");
        }
    }

    /**
     * Keeps QQ's docked-bar chrome hidden even when a skin refresh replaces it.
     *
     * <p>Day/night switches can build the replacement blur wrapper as GONE and
     * make it visible only after our install pass. Remembering only the layer
     * that happened to be visible during install therefore loses the new
     * wrapper. Re-scan the small sibling list before every draw so both a
     * re-shown instance and a replacement instance are caught before rendering.
     */
    private static void holdOwnBarChromeHidden(LiquidGlassHostLayout host) {
        android.view.ViewParent rawParent = host.getParent();
        HostApp app = LiquidGlassModule.app();
        if (rawParent instanceof ViewGroup
                && app != null && app.hiddenSiblings.length > 0) {
            ViewGroup parent = (ViewGroup) rawParent;
            View tabView = sTabViewRef.get();
            for (int i = 0; i < parent.getChildCount(); i++) {
                View c = parent.getChildAt(i);
                if (c != tabView && app.isHiddenSibling(c.getClass().getName())) {
                    sBlurLayerRef = new WeakReference<>(c);
                    holdHidden(c, true);
                }
            }
        } else {
            holdHidden(sBlurLayerRef.get(), true);
        }
        holdHidden(sHairlineRef.get(), false);
    }

    /**
     * Tracks the selected tab and the visible pager page once per frame.
     *
     * <p>Both have to be polled rather than hooked: {@code setTo(int)} does not
     * fire on tab taps, and the pager scrolls between fixed child offsets instead
     * of restacking them, so nothing notifies us when the page under the glass
     * changes.
     */
    /**
     * Holds the navigation bar transparent.
     *
     * <p>WeChat's chat screen opens inside LauncherUI and paints the navigation
     * bar opaque on its way in, then leaves it that way — which puts a solid
     * strip back over the content the pill floats above. Nothing tells us when
     * that happens (no Activity change, no relayout), so it is simply checked
     * whenever the bar draws; the read is a field access and the write only
     * happens when WeChat has actually changed it.
     */
    private static WeakReference<View> sNavBgRef = new WeakReference<>(null);
    private static int sNavBgId = -1;

    /** The DecorView's navigation-bar backdrop, looked up by its framework id. */
    private static View navBarBackground(View decor) {
        View v = sNavBgRef.get();
        if (v != null && v.getParent() != null
                && v.getRootView() == decor.getRootView()) {
            return v;
        }
        if (sNavBgId == -1) {
            sNavBgId = decor.getResources().getIdentifier(
                    "navigationBarBackground", "id", "android");
        }
        if (sNavBgId == 0) {
            return null;
        }
        v = decor.findViewById(sNavBgId);
        sNavBgRef = new WeakReference<>(v);
        return v;
    }

    /**
     * Keeps the navigation bar from covering the content.
     *
     * <p>WeChat's chat screen opens inside LauncherUI — no Activity change, no
     * window focus change — and paints the navigation bar opaque on its way in,
     * leaving it that way on the way out. That is the strip that reappears at
     * the bottom: the system bar drawn over content that already reaches the
     * edge, not a layout gap.
     *
     * <p>Setting the colour back is no use here. On HyperOS the value reads
     * straight back as WeChat's, so the write never lands. The bar's backdrop is
     * an ordinary View inside the DecorView though, and hiding that is entirely
     * ours to do — and it holds, because the DecorView has to run a draw pass to
     * show it again, and this runs first in every one of them.
     */
    private static void keepNavBarClear() {
        Activity a = sActivityRef.get();
        if (a == null) {
            return;
        }
        View decor = a.getWindow().getDecorView();
        View navBg = navBarBackground(decor);
        if (navBg != null && navBg.getVisibility() != View.GONE) {
            navBg.setVisibility(View.GONE);
        }
        // Checked separately: losing this flag shrinks the window back above the
        // gesture bar, which is a real gap rather than something drawn over.
        int vis = decor.getSystemUiVisibility();
        if ((vis & View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION) == 0) {
            decor.setSystemUiVisibility(
                    vis | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    /**
     * Cheap fingerprint of the row topology and title layout mode.
     *
     * <p>Measured geometry is deliberately excluded because it changes on every
     * layout. LayoutParams identity and the equal-weight bit are retained: QQ's
     * Material bar can reuse the same TabViews while replacing their content or
     * restoring {@code width=0, weight=1}, and either transition must re-hug the
     * existing row.
     */
    private static int tabStructureSignature(ViewGroup tabRow) {
        if (tabRow == null) {
            return 0;
        }
        int signature = System.identityHashCode(tabRow);
        signature = signature * 31 + tabRow.getVisibility();
        signature = signature * 31 + tabRow.getChildCount();
        signature = signature * 31 + (isQqIconOnlyRow(tabRow) ? 1 : 0);
        for (int i = 0; i < tabRow.getChildCount(); i++) {
            View tab = tabRow.getChildAt(i);
            signature = signature * 31 + System.identityHashCode(tab);
            signature = signature * 31 + tab.getVisibility();
            ViewGroup.LayoutParams lp = tab.getLayoutParams();
            signature = signature * 31 + System.identityHashCode(lp);
            boolean equalWeight = lp != null && lp.width == 0;
            if (lp instanceof android.widget.LinearLayout.LayoutParams) {
                equalWeight |= ((android.widget.LinearLayout.LayoutParams) lp).weight != 0f;
            }
            signature = signature * 31 + (equalWeight ? 1 : 0);
            if (tab instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) tab;
                signature = signature * 31 + group.getChildCount();
                for (int j = 0; j < group.getChildCount(); j++) {
                    View child = group.getChildAt(j);
                    signature = signature * 31 + System.identityHashCode(child);
                    signature = signature * 31 + child.getVisibility();
                }
            }
        }
        return signature;
    }

    /**
     * Watches QQ's runtime-configurable tabs without measuring on every frame.
     *
     * <p>The actual repair is posted out of pre-draw: it changes LayoutParams and
     * must be allowed to trigger a clean layout pass. Returning {@code true}
     * tells the selection watcher to wait until the new row is rebound.
     */
    private static boolean scheduleTabStructureRefreshIfNeeded(
            LiquidGlassHostLayout host) {
        View tabView = sTabViewRef.get();
        if (!(tabView instanceof ViewGroup)) {
            return false;
        }
        ViewGroup current = TabBarBridge.findTabRow((ViewGroup) tabView);
        if (current == null || current.getVisibility() != View.VISIBLE
                || TabBarBridge.tabCount(current) == 0) {
            // QQ briefly detaches/empties Material's indicator while applying
            // the setting. Keep the old binding untouched and retry next frame.
            return sTabRowRef.get() != null;
        }
        if (sTabStructureRefreshPosted) {
            return true;
        }
        int signature = tabStructureSignature(current);
        if (current == sTabRowRef.get() && signature == sTabStructureSignature) {
            return false;
        }
        sTabStructureRefreshPosted = true;
        host.post(() -> refreshTabStructure(host));
        return true;
    }

    /** Re-hugs and rebinds a bottom bar after its tab topology changes. */
    private static void refreshTabStructure(LiquidGlassHostLayout host) {
        try {
            if (host != sHostRef.get() || host.getParent() == null) {
                return;
            }
            View tabView = sTabViewRef.get();
            if (!(tabView instanceof ViewGroup)) {
                return;
            }
            ViewGroup tabRow = TabBarBridge.findTabRow((ViewGroup) tabView);
            if (tabRow == null || tabRow.getVisibility() != View.VISIBLE
                    || TabBarBridge.tabCount(tabRow) == 0) {
                return;
            }

            // Newly created Material TabViews bring QQ's own backgrounds and
            // equal-weight widths and docked height back with them. Apply the
            // same treatment as the initial install, then resize the already-live
            // host around the row.
            stripSolidBackgrounds(tabView);
            disableTabWidgetStrips(tabView);
            dropNavReserve(tabView);
            ViewGroup.LayoutParams tabLp = tabView.getLayoutParams();
            if (tabLp != null && sBarHeight > 0 && tabLp.height != sBarHeight) {
                tabLp.height = sBarHeight;
                tabView.setLayoutParams(tabLp);
            }
            float density = host.getResources().getDisplayMetrics().density;
            int barWidth = hugContentWidth(tabRow, density);
            if (barWidth <= 0) {
                return; // not laid out yet; leave the old signature so we retry
            }
            ViewGroup.LayoutParams lp = host.getLayoutParams();
            int desired = barWidth + host.shadowPad() * 2;
            if (lp != null && lp.width != desired) {
                lp.width = desired;
                host.setLayoutParams(lp);
            }

            sTabRowRef = new WeakReference<>(tabRow);
            sTabStructureSignature = tabStructureSignature(tabRow);
            View droplet = sDropletRef.get();
            if (droplet instanceof DropletPanel) {
                ((DropletPanel) droplet).setTabRow(tabRow);
            }
            if (sDrag != null) {
                sDrag.setTabRow(tabRow);
            }

            // The next pre-draw runs after the requested layout and snaps the
            // droplet to the selected tab using the new geometry.
            sLastIndex = -1;
            tabRow.requestLayout();
            tabView.requestLayout();
            host.requestLayout();
            LiquidGlassModule.log(android.util.Log.INFO,
                    "tab structure rebound: row=" + tabRow.getClass().getName()
                            + " children=" + tabRow.getChildCount()
                            + " hostWidth=" + (lp == null ? 0 : lp.width)
                            + " barHeight=" + sBarHeight);
        } catch (Throwable t) {
            LiquidGlassModule.logErr("tab structure refresh failed", t);
        } finally {
            sTabStructureRefreshPosted = false;
        }
    }

    /**
     * QQ's skin refresh restores the docked bar's navigation padding without
     * rebuilding the tab row. Topology fingerprints deliberately ignore
     * geometry, so hold this small invariant separately on the pre-draw path.
     */
    private static boolean restoreBarContentHeight(LiquidGlassHostLayout host) {
        View tabView = sTabViewRef.get();
        if (tabView == null) {
            return false;
        }
        boolean changed = dropNavReserve(tabView) > 0;
        ViewGroup.LayoutParams lp = tabView.getLayoutParams();
        if (lp != null && sBarHeight > 0 && lp.height != sBarHeight) {
            lp.height = sBarHeight;
            tabView.setLayoutParams(lp);
            changed = true;
        }
        if (changed) {
            tabView.requestLayout();
            host.requestLayout();
            LiquidGlassModule.log(android.util.Log.INFO,
                    "bar content height restored after host relayout: "
                            + sBarHeight);
        }
        return changed;
    }

    private static void installSelectionWatcher(LiquidGlassHostLayout host) {
        host.getViewTreeObserver().addOnPreDrawListener(() -> {
            if (host != sHostRef.get() || !host.isAttachedToWindow()) {
                return true;
            }
            // First, and on its own: anything below must not be able to stop the
            // navigation bar being held clear.
            try {
                keepNavBarClear();
                dropBottomFrostAll();
                holdOwnBarChromeHidden(host);
            } catch (Throwable t) {
                // Once only: this sits on a per-frame path.
                if (!sKeepFailed) {
                    sKeepFailed = true;
                    LiquidGlassModule.logErr("keepNavBarClear failed", t);
                }
            }
            try {
                if (restoreBarContentHeight(host)) {
                    return true;
                }
                followBarOffset(host);
                if (scheduleTabStructureRefreshIfNeeded(host)) {
                    return true;
                }
                ViewGroup tabRow = sTabRowRef.get();
                int sel = TabBarBridge.selectedIndex(tabRow);
                if (sel >= 0 && sel != sLastIndex) {
                    boolean first = sLastIndex < 0;
                    sLastIndex = sel;
                    syncDropletSize(sel);
                    if (sDrag != null) {
                        // KernelSU animates programmatic switches the same way as
                        // drags: press, travel, release.
                        sDrag.animateToIndex(sel, first);
                    }
                    ViewGroup pgr = sPagerRef.get();
                    if (pgr != null) {
                        pgr.post(() -> extendPagesToBottom(pgr));
                    }
                }
            } catch (Throwable ignored) {
            }
            return true;
        });
    }

    /**
     * The sibling the glass refracts. WeChat puts the pager and the tab bar under
     * the same parent, so the backdrop is whichever sibling is not the bar itself
     * and actually covers the screen.
     */
    private static ViewGroup findBackdrop(ViewGroup parent, View tabView) {
        ViewGroup best = null;
        int bestArea = 0;
        HostApp app = LiquidGlassModule.app();
        for (int i = 0; i < parent.getChildCount(); i++) {
            View c = parent.getChildAt(i);
            if (c == tabView || !(c instanceof ViewGroup)
                    || c.getVisibility() != View.VISIBLE
                    || (app != null && app.isHiddenSibling(c.getClass().getName()))) {
                continue;
            }
            int area = c.getWidth() * c.getHeight();
            if (area > bestArea) {
                bestArea = area;
                best = (ViewGroup) c;
            }
        }
        return best;
    }

    /**
     * WeChat paints the bar's opaque colour on the inner LinearLayout
     * ({@code E.setBackgroundColor(...)}). Only flat colour fills are removed —
     * anything else (badge shapes, ripples) is left alone.
     */
    private static void stripSolidBackgrounds(View v) {
        // The bar and its row are drawn by the glass now, so clear their
        // backgrounds outright rather than only flat fills: WeChat paints the
        // bar's hairline top divider through a non-ColorDrawable background, and
        // leaving it in draws a stray line across the top of the pill that stops
        // short at the row's padding edge.
        if (v.getBackground() != null) {
            v.setBackground(null);
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            // Down to the tab columns themselves: each carries a top hairline,
            // and the four of them line up into a divider spanning the row's
            // content box (214..1006 px) right across the pill's top edge.
            // Their children keep theirs (badges, icon states).
            for (int i = 0; i < vg.getChildCount(); i++) {
                View c = vg.getChildAt(i);
                if (c.getBackground() != null) {
                    c.setBackground(null);
                }
                if (c instanceof ViewGroup) {
                    ViewGroup row = (ViewGroup) c;
                    for (int j = 0; j < row.getChildCount(); j++) {
                        View tab = row.getChildAt(j);
                        if (tab.getBackground() != null) {
                            tab.setBackground(null);
                        }
                    }
                }
            }
        }
    }

    /**
     * Hides the app's own frosted layer behind the bar.
     *
     * <p>QQ ships one: a 54dp {@code QQBlurViewWrapper} pinned to the bottom of
     * the same FrameLayout, drawn between the page and the tab bar. It is the
     * app's answer to the same problem this module solves, and the two do not
     * compose — left visible it covers the page the glass is meant to refract,
     * so the pill would show a blur of a blur. WeChat has no equivalent and the
     * list is simply empty there.
     */
    private static void hideOwnBlurLayers(ViewGroup parent, View tabView) {
        HostApp app = LiquidGlassModule.app();
        if (app == null || app.hiddenSiblings.length == 0) {
            return;
        }
        for (int i = 0; i < parent.getChildCount(); i++) {
            View c = parent.getChildAt(i);
            if (c != tabView && app.isHiddenSibling(c.getClass().getName())) {
                sBlurLayerRef = new WeakReference<>(c);
                if (c.getVisibility() != View.GONE) {
                    c.setVisibility(View.GONE);
                    LiquidGlassModule.log(android.util.Log.INFO,
                            "hid the app's own blur layer: "
                                    + c.getClass().getName());
                }
            }
        }
    }

    /**
     * Puts the app's blur layer back, for the path where the pill never
     * arrived. Only ever undoes {@link #hideOwnBlurLayers}, which is why the
     * restored state is unconditionally VISIBLE: nothing else is touched.
     */
    private static void showOwnBlurLayers(ViewGroup parent) {
        HostApp app = LiquidGlassModule.app();
        if (app == null || app.hiddenSiblings.length == 0) {
            return;
        }
        for (int i = 0; i < parent.getChildCount(); i++) {
            View c = parent.getChildAt(i);
            if (c.getVisibility() == View.GONE
                    && app.isHiddenSibling(c.getClass().getName())) {
                c.setVisibility(View.VISIBLE);
            }
        }
    }

    /**
     * Hides the hairline the app rules across the top of its docked bar.
     *
     * <p>QQ draws it as a sibling of the bar — a 1px full-width View with a flat
     * colour — so clearing backgrounds inside the bar never reaches it, and once
     * the bar floats away it is left behind as a line straight across the page.
     * Matched on shape, not class: it is a bare {@code android.view.View}, a name
     * far too common to go by. One pixel tall and nearly the full width is not
     * something else in this layout.
     */
    private static void hideBarHairline(ViewGroup parent, View tabView) {
        float density = parent.getResources().getDisplayMetrics().density;
        int maxThickness = Math.max(2, Math.round(density * 1.5f));
        for (int i = 0; i < parent.getChildCount(); i++) {
            View c = parent.getChildAt(i);
            if (c == tabView || c.getVisibility() != View.VISIBLE
                    || c instanceof ViewGroup) {
                continue;
            }
            if (c.getHeight() > 0 && c.getHeight() <= maxThickness
                    && c.getWidth() >= parent.getWidth() * 0.9f
                    && c.getBackground() != null) {
                c.setVisibility(View.GONE);
                sHairlineRef = new WeakReference<>(c);
                LiquidGlassModule.log(android.util.Log.INFO,
                        "hid the bar's " + c.getHeight() + "px hairline");
            }
        }
    }

    /**
     * Turns off the divider strips {@code android.widget.TabWidget} draws itself.
     *
     * <p>QQ's bar is a TabWidget subclass, and the strips are painted in
     * {@code dispatchDraw} rather than through the background — clearing the
     * background does not touch them, and they would run right across the pill.
     */
    private static void disableTabWidgetStrips(View tabView) {
        if (tabView instanceof android.widget.TabWidget) {
            android.widget.TabWidget tw = (android.widget.TabWidget) tabView;
            tw.setStripEnabled(false);
            tw.setDividerDrawable(null);
        }
    }

    /**
     * Drops the room the bar holds at its bottom for the navigation bar.
     *
     * <p>A docked bar has to keep its tabs clear of the gesture pill, and QQ
     * does it with bottom padding — 60px here, exactly the navigation inset.
     * Floating puts the pill above that area to begin with, so the reserve
     * survives only as a band of empty glass under the tabs, as tall as the
     * inset. It has to go before the bar's height is read, since that height
     * is what the pill is built to.
     *
     * <p>WeChat reserves nothing (its bar measures 162px with no padding at
     * all), so this is a no-op there.
     *
     * @return the padding removed, which the caller owes the pill's height
     */
    /**
     * Bar height that leaves the same gap above and below the tabs' content.
     *
     * <p>The bar's own height is not trustworthy under edge-to-edge. On some
     * devices 8.0.72 sizes the bar to cover the navigation inset without putting
     * it anywhere {@link #dropNavReserve} can find it, so the bar measures some
     * 20dp taller than what it actually draws. The pill inherits that as a band
     * of dead space along its bottom edge: the tabs sit against the top with a
     * gap underneath — measured at +72px and +53px on the two devices in
     * issue #1, against −3px on a third whose reserve is a single pixel and
     * which therefore cannot reproduce it at all.
     *
     * <p>So it is measured off the content instead. Whatever gap the bar leaves
     * above its icons is the one it means to have; mirroring that below gives
     * the height the bar would have had without the inset, no matter which route
     * the inset took to get in.
     *
     * <p>Only ever shrinks the bar. If the content cannot be located, or the
     * symmetric height would be taller than what the bar reports, the bar's own
     * height stands.
     */
    private static int contentBarHeight(ViewGroup tabRow, int fallback) {
        if (tabRow == null || fallback <= 0) {
            return fallback;
        }
        int top = Integer.MAX_VALUE;
        int bottom = 0;
        for (int i = 0; i < tabRow.getChildCount(); i++) {
            View tab = tabRow.getChildAt(i);
            if (tab.getVisibility() != View.VISIBLE) {
                continue;
            }
            int[] b = {Integer.MAX_VALUE, 0};
            leafBounds(tab, 0, b);
            if (b[0] < b[1]) {
                int base = tabRow.getTop() + tab.getTop();
                top = Math.min(top, base + b[0]);
                bottom = Math.max(bottom, base + b[1]);
            }
        }
        if (top == Integer.MAX_VALUE || bottom <= top) {
            return fallback;
        }
        int symmetric = bottom + top;
        LiquidGlassModule.log(android.util.Log.INFO,
                "bar height: content " + top + ".." + bottom
                        + " -> " + symmetric + " (bar reports " + fallback + ")");
        return symmetric > 0 && symmetric < fallback ? symmetric : fallback;
    }

    /** Vertical extent of a tab's drawn content, relative to the tab column. */
    private static void leafBounds(View v, int offset, int[] out) {
        if (v.getVisibility() != View.VISIBLE) {
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                leafBounds(c, offset + c.getTop(), out);
            }
            return;
        }
        if (v.getWidth() <= 0 || v.getHeight() <= 0) {
            return;
        }
        out[0] = Math.min(out[0], offset);
        out[1] = Math.max(out[1], offset + v.getHeight());
    }

    private static int dropNavReserve(View tabView) {
        int reserve = tabView.getPaddingBottom();
        if (reserve <= 0) {
            return 0;
        }
        tabView.setPadding(tabView.getPaddingLeft(), tabView.getPaddingTop(),
                tabView.getPaddingRight(), 0);
        LiquidGlassModule.log(android.util.Log.INFO,
                "dropped the bar's " + reserve + "px navigation reserve");
        return reserve;
    }

    private static final int EDGE_TAG_KEY = 0x7F5A0002;

    /**
     * Lets the pages draw under the gesture bar.
     *
     * <p>WeChat's window stops at the navigation bar, so once the tab bar is
     * lifted off the bottom there is a dead strip below it that nothing ever
     * paints. Asking for the hide-navigation layout grows the window to the
     * whole screen; the navigation inset is then stripped on its way down the
     * tree, because otherwise WeChat's own containers just pad the same gap
     * straight back in.
     *
     * @return whether the window was actually grown
     */
    private static boolean extendUnderNavBar(Context ctx) {
        if (Build.VERSION.SDK_INT < 30) {
            return false;
        }
        if (LiquidGlassModule.app() == HostApp.QQ) {
            return false; // QQ NT breaks IME window insets if the root decor is hijacked
        }
        try {
            Activity activity = activityOf(ctx);
            if (activity == null) {
                activity = sActivityRef.get();
            }
            if (activity == null) {
                LiquidGlassModule.log(android.util.Log.WARN,
                        "no Activity for the window, bottom strip stays blank");
                return false;
            }
            android.view.Window w = activity.getWindow();
            View decor = w.getDecorView();
            boolean first = !Boolean.TRUE.equals(decor.getTag(EDGE_TAG_KEY));

            w.setNavigationBarColor(android.graphics.Color.TRANSPARENT);
            w.setNavigationBarContrastEnforced(false);
            android.view.WindowInsetsController ctrl = w.getInsetsController();
            if (ctrl != null) {
                // The gesture pill now sits on WeChat's own content rather than
                // on a system background, so it has to contrast with that.
                int light = android.view.WindowInsetsController
                        .APPEARANCE_LIGHT_NAVIGATION_BARS;
                ctrl.setSystemBarsAppearance(isNight(ctx) ? 0 : light, light);
            }
            decor.setSystemUiVisibility(decor.getSystemUiVisibility()
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            if (first) {
                decor.setTag(EDGE_TAG_KEY, Boolean.TRUE);
                decor.setOnApplyWindowInsetsListener((v, insets) -> {
                    int inset = rememberNavigationInset(insets);
                    LiquidGlassHostLayout host = sHostRef.get();
                    if (host != null
                            && host.getRootView() == v.getRootView()) {
                        syncHostBottomInset(host, inset);
                    }
                    WindowInsets stripped = new WindowInsets.Builder(insets)
                            .setInsets(WindowInsets.Type.navigationBars(),
                                    android.graphics.Insets.NONE)
                            .build();
                    return v.onApplyWindowInsets(stripped);
                });
                LiquidGlassModule.log(android.util.Log.INFO,
                        "window extended under the navigation bar, inset="
                                + navInset(decor));
            }
            decor.requestApplyInsets();
            return true;
        } catch (Throwable t) {
            LiquidGlassModule.logErr("could not extend under the nav bar", t);
            return false;
        }
    }

    /**
     * Keeps the bottom strip filled without guessing at timings.
     *
     * <p>Coming back from a chat re-runs WeChat's own window setup, and it does
     * so on its own schedule — a delayed one-shot after resume sometimes lands
     * before WeChat has finished handing the navigation inset back. Watching the
     * pager's layout and the window's focus catches it whenever it happens.
     */
    private static void watchBottomInset(LiquidGlassHostLayout host, ViewGroup backdrop) {
        backdrop.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or2, ob) -> {
            extendPagesToBottom(backdrop);
            // Catches the transition frame itself rather than waiting for the
            // bar's next draw, so the strip never flashes on the way back.
            keepNavBarClear();
        });
        host.getViewTreeObserver().addOnWindowFocusChangeListener(hasFocus -> {
            if (!hasFocus) {
                return;
            }
            Activity a = sActivityRef.get();
            if (a != null) {
                reassertBottom(a);
            }
        });
    }

    /**
     * Re-applies the edge-to-edge window state and re-stretches the pages.
     *
     * <p>Runs on every resume of an already-installed window. WeChat restores
     * its own system-ui flags when a secondary screen closes, which hands the
     * navigation inset straight back to its containers.
     */
    private static void reassertBottom(Activity activity) {
        if (!extendUnderNavBar(activity)) {
            return;
        }
        ViewGroup pager = sPagerRef.get();
        if (pager == null) {
            return;
        }
        pager.post(() -> extendPagesToBottom(pager));
        // The insets land a frame or two after the window is re-shown, and the
        // pages can only be stretched once the room is actually there.
        pager.postDelayed(() -> extendPagesToBottom(pager), 400L);
    }



    /**
     * Stops WeChat repainting the navigation bar over the content.
     *
     * <p>The chat screen opens inside LauncherUI — no Activity change, no window
     * focus change — and on its way in it paints the navigation bar opaque,
     * leaving it that way on the way out. That is the strip that reappears at
     * the bottom: not a layout gap, the system bar itself drawn over content
     * that already reaches the edge.
     *
     * <p>Correcting it after the fact is not enough. There is nothing to react
     * to, and a per-frame check only runs while something is being drawn — once
     * the list settles, no frames, no correction. So the call itself is
     * swallowed for this one window instead.
     */

    private static Activity activityOf(Context ctx) {
        while (ctx instanceof android.content.ContextWrapper) {
            if (ctx instanceof Activity) {
                return (Activity) ctx;
            }
            ctx = ((android.content.ContextWrapper) ctx).getBaseContext();
        }
        return null;
    }

    private static int navInset(View anchor) {
        try {
            WindowInsets insets = anchor.getRootWindowInsets();
            return insets == null ? 0 : navigationInset(insets);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Returns the last non-zero system inset once our listener has stripped it. */
    private static int rememberNavigationInset(View anchor) {
        int inset = navInset(anchor);
        if (inset > 0) {
            sNavigationInset = inset;
        }
        return inset > 0 ? inset : sNavigationInset;
    }

    private static int rememberNavigationInset(WindowInsets insets) {
        int inset = navigationInset(insets);
        // Theme recreation sends a transient zero-inset dispatch before the
        // real navigation-bar frame. Do not let that one frame pull an already
        // anchored pill down; the following non-zero dispatch refreshes the
        // cache normally. A process that genuinely starts at zero still keeps
        // zero until it sees a real bar.
        if (inset > 0 || sNavigationInset == 0) {
            sNavigationInset = inset;
        }
        return inset > 0 ? inset : sNavigationInset;
    }

    private static int navigationInset(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= 30) {
            return insets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.navigationBars()).bottom;
        }
        return insets.getSystemWindowInsetBottom();
    }

    /** Keeps an already-installed pill anchored when system insets change. */
    private static void syncHostBottomInset(LiquidGlassHostLayout host, int inset) {
        ViewGroup.LayoutParams raw = host.getLayoutParams();
        if (!(raw instanceof FrameLayout.LayoutParams)) {
            return;
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) raw;
        float density = host.getResources().getDisplayMetrics().density;
        int desired = Math.round(density * GlassConfig.barOffsetDp)
                - host.shadowPad() + Math.max(0, inset);
        if (lp.bottomMargin == desired) {
            return;
        }
        lp.bottomMargin = desired;
        host.setLayoutParams(lp);
        LiquidGlassModule.log(android.util.Log.INFO,
                "pill bottom anchor refreshed: inset=" + inset
                        + " margin=" + desired);
    }

    /* ---------------- renderer ---------------- */

    private static boolean isNight(Context ctx) {
        return (ctx.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    private static void attachRenderer(Context ctx, LiquidGlassHostLayout host,
                                       ViewGroup backdrop, float density) {
        if (Build.VERSION.SDK_INT < 33) {
            LiquidGlassModule.log(android.util.Log.INFO,
                    "SDK < 33, staying on the legacy frost path");
            return;
        }
        try {
            boolean night = isNight(ctx);

            // The glass surface is a direct port of KernelSU's effect stack,
            // see LiquidGlassPanel.
            final LiquidGlassPanel glass =
                    new LiquidGlassPanel(ctx, backdrop, density, night);
            host.addView(glass, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            // The droplet goes on top of the tabs, not under them: it refracts a
            // separately drawn, enlarged copy of the tab row, and that refracted
            // copy is what should be visible inside it — same as KernelSU.
            final DropletPanel droplet = new DropletPanel(
                    ctx, backdrop, sTabRowRef.get(), density, night);
            droplet.setVisibility(View.INVISIBLE);
            host.addView(droplet, new FrameLayout.LayoutParams(0, 0,
                    android.view.Gravity.TOP | android.view.Gravity.START));
            droplet.setPill(glass);
            sDropletRef = new WeakReference<>(droplet);
            // The droplet scales past the pill's bounds while held (78/56). Both
            // flags matter: FrameLayout defaults clipToPadding to true, and the
            // host carries 14dp of shadow padding — that alone was shearing off
            // exactly the overflow we wanted to show.
            host.setClipChildren(false);
            host.setClipToPadding(false);
            sGlassRef = new WeakReference<>(glass);

            host.setGlassTuner(new LiquidGlassHostLayout.GlassTuner() {
                @Override
                public void onSize(int w, int h, float cornerRadius) {
                    glass.invalidate();
                }

                @Override
                public void onTheme(boolean dark) {
                    glass.setTheme(dark);
                    droplet.setTheme(dark);
                }
            });

            ViewGroup tabRow = sTabRowRef.get();
            if (tabRow != null) {
                sDrag = new DropletDragController(droplet, tabRow, density, night);
                sDrag.setPill(glass);
                sDrag.setHost(host);
                host.setDragHandler(sDrag);
            }

            // The backdrop is re-captured on each draw, so the glass follows the
            // page behind it.
            host.getViewTreeObserver().addOnPreDrawListener(() -> {
                glass.invalidate();
                return true;
            });

            LiquidGlassModule.log(android.util.Log.INFO,
                    "renderer=KernelSU-style lens (saturation+blur+SDF refraction)"
                            + " supported=" + glass.isSupported()
                            + " drag=" + (tabRow != null));
        } catch (Throwable t) {
            LiquidGlassModule.logErr("glass renderer unavailable", t);
        }
    }


    /* ---------------- droplet ---------------- */

    /** Resolves hook/getter indices to the row's current visible layout slot. */
    private static int resolveTabSlot(int appIndex) {
        ViewGroup tabRow = sTabRowRef.get();
        int selected = TabBarBridge.selectedIndex(tabRow);
        return selected >= 0 ? selected : TabBarBridge.slotForIndex(tabRow, appIndex);
    }

    /**
     * Called from the tab-switch hook on every in-app page switch — WeChat's
     * {@code setTo(int)}, QQ's {@code setCurrentTab(int)}.
     *
     * <p>This doubles as the install trigger: both apps call it once during
     * startup to select the initial tab, and by then the bar is guaranteed to
     * exist — which the decor-view polling cannot guarantee.
     */
    static void onTabChanged(View tabView, int index) {
        LiquidGlassHostLayout host = sHostRef.get();
        if (host != null && !host.isAttachedToWindow()) {
            resetState();
            host = null;
        }
        if (host == null || host.getParent() == null || tabView.getParent() != host) {
            // Not ours (yet). Either the first call of this process, or a fresh
            // LauncherUI instance after the old one was destroyed.
            if (tabView instanceof ViewGroup && tabView.getParent() != null) {
                tabView.post(() -> {
                    try {
                        install((ViewGroup) tabView);
                        syncDropletSize(resolveTabSlot(index));
                    } catch (Throwable t) {
                        LiquidGlassModule.logErr("install from setTo failed", t);
                    }
                });
            }
            return;
        }
        LiquidGlassHostLayout installedHost = host;
        installedHost.post(() -> {
            if (scheduleTabStructureRefreshIfNeeded(installedHost)) {
                return;
            }
            int slot = resolveTabSlot(index);
            if (slot < 0) {
                return;
            }
            syncDropletSize(slot);
            if (sDrag != null) {
                sDrag.animateToIndex(slot, false);
            }
        });
    }

    /**
     * Sizes and vertically places the droplet for the given tab. Horizontal
     * position and all motion belong to {@link DropletDragController}'s springs.
     */
    private static void syncDropletSize(int index) {
        try {
            View droplet = sDropletRef.get();
            ViewGroup tabRow = sTabRowRef.get();
            LiquidGlassHostLayout host = sHostRef.get();
            if (droplet == null || tabRow == null || host == null || index < 0) {
                return;
            }
            View tab = TabBarBridge.tabAt(tabRow, index);
            if (tab == null || tab.getWidth() == 0) {
                return;
            }
            float density = host.getResources().getDisplayMetrics().density;
            // KernelSU sizes the droplet to the full tab column: width = tabWidth,
            // height = bar height - 2 * 4dp padding.
            int inset = Math.round(density * 4f);
            int w = tab.getWidth();
            int h = tab.getHeight() - inset * 2;
            if (w <= 0 || h <= 0) {
                return;
            }
            ViewGroup.LayoutParams lp = droplet.getLayoutParams();
            if (lp.width != w || lp.height != h) {
                lp.width = w;
                lp.height = h;
                droplet.setLayoutParams(lp);
            }
            sDropletBaseY = tab.getTop() + tabRow.getTop() + inset;
            droplet.setTranslationY(sDropletBaseY);
            droplet.setVisibility(View.VISIBLE);
        } catch (Throwable t) {
            LiquidGlassModule.logErr("droplet sizing failed", t);
        }
    }
}

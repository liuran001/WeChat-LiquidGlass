package io.github.liuran001.mmliquidglass;

/**
 * Everything that differs between the apps this module can dress up.
 *
 * <p>The installer, the glass renderer and the droplet are entirely
 * app-agnostic — they work off view geometry. What is not portable is finding
 * the bar in the first place: both WeChat and QQ run their resource ids through
 * AndResGuard, so an id lookup by name is worthless in either, and the only
 * stable handles are the UI class names. Those are collected here so adding a
 * third app is a matter of one more entry rather than a sweep through the code.
 *
 * <p>Structurally the two are near twins, which is why one installer covers
 * both: a full-bleed content view with the tab bar floating over it inside a
 * FrameLayout, an opaque drawable behind the bar, and one method the app calls
 * on every page switch.
 */
final class HostApp {

    /**
     * WeChat, verified against 8.0.72 (3085) and 8.0.77 (3160).
     *
     * <p>Nothing here moved between the two: same launcher activity, same bar
     * class, and {@code setTo(int)} / {@code getCurIdx()} still declared on it.
     * The bar is still built in code — a horizontal LinearLayout of columns, each
     * tagged with its index — so the row lookup and the droplet's index mapping
     * hold as well.
     */
    static final HostApp WECHAT = new HostApp(
            "com.tencent.mm",
            "com.tencent.mm.ui.LauncherUI",
            new String[]{"com.tencent.mm.ui.LauncherUIBottomTabView"},
            new String[]{"setTo"},
            "getCurIdx",
            new String[0],
            new String[]{"TabIconView"},
            false,
            "com.tencent.mm.ui.");

    /**
     * QQ 9.2.85 (13860).
     *
     * <p>Two bars ship in the same build and a server switch
     * ({@code tab_layout_9065_116522266}, default off) picks between them:
     * {@code QQTabWidget} — a plain {@code android.widget.TabWidget} inside
     * {@code QQTabHost} — for everyone today, and {@code QQTabLayout} (a
     * material {@code TabLayout}) behind the flag. Both sit as a bottom-gravity
     * child of the same {@code DragFrameLayout}, over a full-screen content
     * view, and both take {@code setCurrentTab(int)} on every switch.
     *
     * <p>Rechecked against 9.3.55, 9.3.60 and 9.3.70: same classes, same
     * switch. The flag only applies to phones — {@code AppSetting.s()} keeps
     * tablets and unfolded foldables on {@code QQTabWidget} whatever the server
     * says. The Google Play build 8.2.11 ships {@code QQTabWidget} alone, and
     * without its own {@code setCurrentTab} (see
     * {@code TabBarBridge.switchMethod}).
     *
     * <p>{@code QQBlurViewWrapper} is QQ's own 54dp frosted strip behind the
     * bar. Left visible it would sit between the glass and the page it is
     * supposed to refract, so it is hidden on install.
     *
     * <p>{@code getCurrentTab()} only answers on one of the two. QQ's copy of
     * the material {@code TabLayout} has been patched to carry it, so
     * {@code QQTabLayout} inherits one; {@code QQTabWidget} declares no getter
     * and {@code android.widget.TabWidget} has none either. It is named here
     * for the bar that does answer — the other falls through to reading the
     * selection off the tabs, which is where it lives anyway.
     */
    static final HostApp QQ = new HostApp(
            "com.tencent.mobileqq",
            "com.tencent.mobileqq.activity.SplashActivity",
            new String[]{
                    "com.tencent.mobileqq.widget.QQTabWidget",
                    "com.tencent.mobileqq.widget.QQTabLayout",
            },
            new String[]{"setCurrentTab"},
            "getCurrentTab",
            new String[]{"com.tencent.qui.quiblurview.QQBlurViewWrapper"},
            new String[]{"TabDragAnimationView"},
            true,
            "com.tencent.mobileqq.");

    private static final HostApp[] ALL = {WECHAT, QQ};

    /** Package name, which is also the name of the process the home screen lives in. */
    final String pkg;
    /** Activity hosting the tab bar; the only one worth reacting to. */
    final String launcherActivity;
    /** Tab bar view classes, most likely first. */
    final String[] tabViewClasses;
    /** Methods called on the bar for every page switch, hooked as install triggers. */
    final String[] tabSwitchMethods;
    /**
     * Zero-arg method on the bar returning the selected index, if it has one.
     * A bar that does not is read through its tabs' selected state instead, so
     * this is an optimisation rather than a requirement.
     */
    final String currentIndexMethod;
    /** Sibling views to hide, so nothing is left between the glass and the page. */
    final String[] hiddenSiblings;
    /**
     * Class-name suffixes of the tab icon views.
     *
     * <p>Matched by suffix because neither app's icon class survives
     * obfuscation with its package intact. The droplet needs to know them:
     * the icon takes the accent tint along with the label, while everything
     * else in a tab (unread bubbles, red dots) keeps the colour it painted
     * itself. WeChat's icon is a custom view whose name ends in TabIconView;
     * QQ's is TabDragAnimationView, which is a bare {@code View} subclass and
     * would otherwise be mistaken for a badge.
     */
    final String[] iconClassSuffixes;
    /**
     * Whether the tab labels' own colour beats the activity uiMode when
     * deciding light or dark.
     *
     * <p>WeChat resolves day/night through standard {@code values-night}
     * qualifiers, so its uiMode is authoritative and the label probe is only
     * logged as a cross-check. QQ ships a skin engine with a night mode of its
     * own that the uiMode does not track — a user can run QQ dark on a light
     * system — so there the labels are what the glass has to match.
     */
    final boolean preferTextColorProbe;
    /** Package prefix used to summarise the view tree when the bar is not found. */
    final String uiPrefix;

    private HostApp(String pkg, String launcherActivity, String[] tabViewClasses,
                    String[] tabSwitchMethods, String currentIndexMethod,
                    String[] hiddenSiblings, String[] iconClassSuffixes,
                    boolean preferTextColorProbe, String uiPrefix) {
        this.pkg = pkg;
        this.launcherActivity = launcherActivity;
        this.tabViewClasses = tabViewClasses;
        this.tabSwitchMethods = tabSwitchMethods;
        this.currentIndexMethod = currentIndexMethod;
        this.hiddenSiblings = hiddenSiblings;
        this.iconClassSuffixes = iconClassSuffixes;
        this.preferTextColorProbe = preferTextColorProbe;
        this.uiPrefix = uiPrefix;
    }

    /**
     * The app this process belongs to, or null for one we do not dress up.
     *
     * <p>Matched on the exact process name rather than the package: both apps
     * are heavily multi-process ({@code :push}, {@code :tools}, {@code :MSF},
     * {@code :appbrandX}), and the home screen only ever lives in the main
     * process, which is named after the package.
     */
    static HostApp forProcess(String processName) {
        for (HostApp app : ALL) {
            if (app.pkg.equals(processName)) {
                return app;
            }
        }
        return null;
    }

    /** The app owning {@code packageName}, or null. */
    static HostApp forPackage(String packageName) {
        for (HostApp app : ALL) {
            if (app.pkg.equals(packageName)) {
                return app;
            }
        }
        return null;
    }

    boolean isTabViewClass(String className) {
        for (String c : tabViewClasses) {
            if (c.equals(className)) {
                return true;
            }
        }
        return false;
    }

    boolean isHiddenSibling(String className) {
        for (String c : hiddenSiblings) {
            if (c.equals(className)) {
                return true;
            }
        }
        return false;
    }

    boolean isTabIconClass(String className) {
        for (String suffix : iconClassSuffixes) {
            if (className.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return pkg;
    }
}

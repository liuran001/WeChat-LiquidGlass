package io.github.liuran001.mmliquidglass;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;

final class LiquidGlassHostLayout extends FrameLayout {

    static final Object GLASS_TAG = new Object();

    /** Software frost, for when no GPU glass is attached (below Android 12). */
    private static final float SAMPLE_SCALE_LEGACY = 0.4f;
    private static final int BLUR_RADIUS_LEGACY = 3;
    private static final float SATURATION_BOOST = 1.08f;

    private final ViewGroup mSampleRoot;
    private final float mDensity;
    private boolean mDarkMode;

    /**
     * Re-reads dark/light so theme switches follow the app live. Asked on every
     * frame, run at most every 300ms and once more after the frames stop: a
     * theme switch redraws the screen for only a handful of frames.
     */
    private final ClockThrottle mThemeProbe = new ClockThrottle(300L, this::probeTheme);
    private boolean mThemeProbed;

    /** Hooks for the GPU glass (LiquidGlassPanel). Null = the software frost here. */
    interface GlassTuner {
        void onSize(int w, int h, float cornerRadius);
        void onTheme(boolean dark);
    }

    private GlassTuner mTuner;

    /**
     * Self-drawn drop shadow.
     *
     * <p>{@code setElevation} draws nothing here — whatever WeChat's view tree
     * does to this subtree, the platform shadow never appears even at absurd
     * values. Drawing it ourselves also keeps it matched to the capsule.
     *
     * <p>The host reserves {@link #mShadowPad} of padding on every side; children
     * (MATCH_PARENT) shrink into the inner box, so the opaque round-rect drawn
     * here is fully covered by the glass and only its shadow shows.
     */
    private int mShadowPad;
    private final Paint mShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mShadowClip = new Path();
    private float mShadowOffsetY;
    private boolean mShadowHidden;
    private int mShadowAlpha = 255;

    /** Slides/fades the shadow along with WeChat's bar. */
    void setShadowOffsetY(float ty, float alpha) {
        boolean hidden = ty == Float.MAX_VALUE;
        int a = Math.round(255 * Math.max(0f, Math.min(1f, alpha)));
        if (mShadowHidden == hidden && mShadowOffsetY == ty && mShadowAlpha == a) {
            return;
        }
        mShadowHidden = hidden;
        mShadowOffsetY = hidden ? 0f : ty;
        mShadowAlpha = a;
        invalidate();
    }

    /** KernelSU: dropShadow(radius = 10.dp, alpha = dark ? 0.2f : 0.1f). */
    void setupShadow(float density, boolean night) {
        mShadowPad = Math.round(density * 14f);
        setPadding(mShadowPad, mShadowPad, mShadowPad, mShadowPad);
        mShadowPaint.setColor(0xFF000000);
        mShadowPaint.setShadowLayer(density * 10f, 0f, density * 2f,
                night ? 0x33000000 : 0x1A000000);
        // No setLayerType here: promoting the host to its own layer renders the
        // whole padded box as one texture, which shows up as a rectangular patch
        // clipping the list separators behind it. drawRoundRect's shadow layer is
        // hardware-accelerated on its own since API 28.
        invalidate();
    }

    int shadowPad() {
        return mShadowPad;
    }

    private void drawPillShadow(Canvas canvas) {
        if (mShadowPad <= 0 || mShadowHidden || mShadowAlpha == 0) {
            return;
        }
        float l = mShadowPad;
        float t = mShadowPad;
        float r = getWidth() - mShadowPad;
        float b = getHeight() - mShadowPad;
        if (r <= l || b <= t) {
            return;
        }
        float radius = (b - t) * 0.5f;
        int save = canvas.save();
        canvas.translate(0f, mShadowOffsetY);
        // Punch the pill out of the shadow instead of relying on the glass to
        // cover it. The paint's fill is opaque black — it only ever existed to
        // cast the shadow — so a single frame where the glass lags behind (as
        // happens while WeChat slides the bar away) would otherwise flash a solid
        // black band across the bottom of the pill.
        if (mShadowClipW != getWidth() || mShadowClipH != getHeight()) {
            mShadowClip.reset();
            mShadowClip.addRoundRect(l, t, r, b, radius, radius, Path.Direction.CW);
            mShadowClipW = getWidth();
            mShadowClipH = getHeight();
        }
        canvas.clipOutPath(mShadowClip);
        int prev = mShadowPaint.getAlpha();
        mShadowPaint.setAlpha(mShadowAlpha);
        canvas.drawRoundRect(l, t, r, b, radius, radius, mShadowPaint);
        mShadowPaint.setAlpha(prev);
        canvas.restoreToCount(save);
    }

    /**
     * Optional gesture owner. It gets first refusal on every touch, but is
     * expected to claim only drags so WeChat's own tab views keep their taps.
     */
    interface DragHandler {
        boolean onIntercept(android.view.MotionEvent ev);
        boolean onTouch(android.view.MotionEvent ev);
    }

    private DragHandler mDragHandler;
    private final int mTouchSlop;
    private float mGestureDownX;
    private float mGestureDownY;
    private boolean mAncestorsBlocked;

    void setDragHandler(DragHandler handler) {
        mDragHandler = handler;
    }

    /**
     * Keeps an outer drawer or pager from claiming a drag that began on the
     * floating bar.
     *
     * <p>Call the parent directly instead of {@link #requestDisallowInterceptTouchEvent}:
     * setting the flag on this ViewGroup as well would prevent our own
     * {@link #onInterceptTouchEvent} from seeing the MOVE that starts a droplet
     * drag. Taps still go to the app's tab children. Horizontal movement stays
     * protected for the droplet, while a clearly vertical gesture is released
     * after QQ's own tab widget has had enough travel to fire its native
     * swipe-up callback.
     */
    private void protectGestureFromAncestors(android.view.MotionEvent ev) {
        int action = ev.getActionMasked();
        android.view.ViewParent parent = getParent();
        if (parent == null) {
            return;
        }
        if (action == android.view.MotionEvent.ACTION_DOWN) {
            mGestureDownX = ev.getX();
            mGestureDownY = ev.getY();
            mAncestorsBlocked = true;
            parent.requestDisallowInterceptTouchEvent(true);
            return;
        }
        if (action == android.view.MotionEvent.ACTION_MOVE && mAncestorsBlocked) {
            float dx = ev.getX() - mGestureDownX;
            float dy = ev.getY() - mGestureDownY;
            // QQTabWidget's native upward action fires after 50 px. Releasing
            // before that would let the outer drawer cancel the child first.
            float verticalRelease = Math.max(mTouchSlop, 50f);
            if (Math.abs(dy) > verticalRelease && Math.abs(dy) > Math.abs(dx)) {
                mAncestorsBlocked = false;
                parent.requestDisallowInterceptTouchEvent(false);
            }
            return;
        }
        if ((action == android.view.MotionEvent.ACTION_UP
                || action == android.view.MotionEvent.ACTION_CANCEL)
                && mAncestorsBlocked) {
            mAncestorsBlocked = false;
            parent.requestDisallowInterceptTouchEvent(false);
        }
    }

    @Override
    public boolean onInterceptTouchEvent(android.view.MotionEvent ev) {
        protectGestureFromAncestors(ev);
        try {
            if (mDragHandler != null && mDragHandler.onIntercept(ev)) {
                return true;
            }
        } catch (Throwable t) {
            LiquidGlassModule.logErr("drag intercept failed", t);
        }
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent ev) {
        protectGestureFromAncestors(ev);
        try {
            if (mDragHandler != null && mDragHandler.onTouch(ev)) {
                return true;
            }
        } catch (Throwable t) {
            LiquidGlassModule.logErr("drag touch failed", t);
        }
        return super.onTouchEvent(ev);
    }

    private final Paint mBackdropPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGlossPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mBounds = new RectF();

    private float mCornerRadius;
    private Bitmap mRegionBuf;
    private boolean mCapturing;
    /** Legacy frost: where and at what size the last capture was taken. */
    private final int[] mRootLoc = new int[2];
    private final int[] mSelfLoc = new int[2];
    private int mCapturedDx;
    private int mCapturedDy;
    private int mCapturedW;
    private int mCapturedH;
    private boolean mCapturedLastFrame;

    private ViewTreeObserver.OnPreDrawListener mPreDrawListener;
    private int mShadowClipW = -1;
    private int mShadowClipH = -1;

    /** The app's own bar, kept so the theme can be re-read off its labels. */
    private ViewGroup mBar;

    LiquidGlassHostLayout(Context context, ViewGroup sampleRoot, ViewGroup bar) {
        super(context);
        mSampleRoot = sampleRoot;
        mBar = bar;
        mTouchSlop = android.view.ViewConfiguration.get(context).getScaledTouchSlop();
        mDensity = context.getResources().getDisplayMetrics().density;
        Boolean detected = detectDarkFromText(bar);
        mDarkMode = resolveDark(context, detected);
        setTag(GLASS_TAG);
        setWillNotDraw(false);
        setupPaints();
        LiquidGlassModule.log(android.util.Log.INFO,
                "host created: sdk=" + Build.VERSION.SDK_INT
                        + " dark=" + mDarkMode + " source=" + darkSource(detected)
                        + " uiMode=" + isSystemNight(context)
                        + " textProbe=" + detected);
    }

    /**
     * Light or dark, from whichever signal the host app actually honours.
     *
     * <p>WeChat's uiMode is the whole story — it resolves day/night through
     * standard {@code values-night} qualifiers — so the label probe there is
     * logged and nothing more. QQ's skin engine has a night mode independent of
     * the system's, and the labels are the only thing that reflects it, so the
     * probe leads and uiMode is the fallback for when it finds no labels.
     */
    private static boolean resolveDark(Context context, Boolean textProbe) {
        HostApp app = LiquidGlassModule.app();
        if (app != null && app.preferTextColorProbe && textProbe != null) {
            return textProbe;
        }
        return isSystemNight(context);
    }

    private static String darkSource(Boolean textProbe) {
        HostApp app = LiquidGlassModule.app();
        return app != null && app.preferTextColorProbe && textProbe != null
                ? "text-color" : "uiMode";
    }

    /** Hands drawing to the GPU glass; the software frost stops for good. */
    void setGlassTuner(GlassTuner tuner) {
        mTuner = tuner;
        if (tuner != null) {
            // QQ can use a skin whose light/dark state differs from uiMode.
            // The host has already resolved that from the live tab labels, so
            // initialise the renderer from the same source immediately rather
            // than waiting for a future theme transition that may never occur.
            tuner.onTheme(mDarkMode);
        }
    }

    @SuppressWarnings("unused")
    private static boolean isSystemNight(Context context) {
        int mode = context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * Follows the app's actual rendering: bright nav text means a dark bar.
     *
     * <p>WeChat nests each tab label three levels deep, and the same subtree also
     * holds the unread-count badge (white on red) which would poison a
     * first-match probe. Taking the most common colour across all labels sidesteps
     * that: three of the four tabs always carry the unselected colour, so the
     * badge and the single selected label can never win the vote.
     */
    static Boolean detectDarkFromText(ViewGroup bar) {
        if (bar == null) {
            return null;
        }
        try {
            java.util.HashMap<Integer, Integer> votes = new java.util.HashMap<>();
            collectTextColors(bar, votes);
            int best = 0;
            int bestCount = 0;
            for (java.util.Map.Entry<Integer, Integer> e : votes.entrySet()) {
                if (e.getValue() > bestCount) {
                    bestCount = e.getValue();
                    best = e.getKey();
                }
            }
            if (bestCount == 0) {
                return null;
            }
            float lum = (0.299f * Color.red(best)
                    + 0.587f * Color.green(best)
                    + 0.114f * Color.blue(best)) / 255f;
            return lum > 0.5f;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void collectTextColors(View v, java.util.Map<Integer, Integer> votes) {
        if (v.getVisibility() != VISIBLE) {
            return;
        }
        if (v instanceof android.widget.TextView) {
            android.widget.TextView tv = (android.widget.TextView) v;
            // Badges carry a background drawable; plain labels do not.
            if (tv.getBackground() == null && tv.getText() != null
                    && tv.getText().length() > 0) {
                android.content.res.ColorStateList csl = tv.getTextColors();
                if (csl != null) {
                    int col = csl.getDefaultColor() | 0xFF000000;
                    Integer prev = votes.get(col);
                    votes.put(col, prev == null ? 1 : prev + 1);
                }
            }
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                collectTextColors(vg.getChildAt(i), votes);
            }
        }
    }

    private void setupPaints() {
        if (mDarkMode) {
            mTintPaint.setColor(0x33000000);
            mBorderPaint.setColor(0x1FFFFFFF);
            mBackdropPaint.setColor(0x40000000);
        } else {
            mTintPaint.setColor(0x4DFFFFFF);
            mBorderPaint.setColor(0x2EFFFFFF);
            mBackdropPaint.setColor(0x8CFFFFFF);
        }
        mBorderPaint.setStyle(Paint.Style.STROKE);
        mBorderPaint.setStrokeWidth(Math.max(mDensity * 0.8f, 0.75f));
        if (getWidth() > 0 && getHeight() > 0) {
            mGlossPaint.setShader(new LinearGradient(
                    0f, 0f, 0f, getHeight() * 0.45f,
                    mDarkMode ? 0x14FFFFFF : 0x30FFFFFF,
                    0x00FFFFFF, Shader.TileMode.CLAMP));
        }
    }

    void attach() {
        detach();
        mPreDrawListener = () -> {
            if (!mCapturing && isAttachedToWindow()
                    && getVisibility() == VISIBLE
                    && getWidth() > 0 && getHeight() > 0) {
                capture();
            }
            return true;
        };
        mSampleRoot.getViewTreeObserver().addOnPreDrawListener(mPreDrawListener);
        invalidate();
        playRevealAnimation();
    }

    void detach() {
        if (mPreDrawListener != null) {
            mSampleRoot.getViewTreeObserver().removeOnPreDrawListener(mPreDrawListener);
            mPreDrawListener = null;
        }
        mThemeProbe.cancel();
    }

    @Override
    protected void onDetachedFromWindow() {
        detach();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mBounds.set(0f, 0f, w, h);
        mCornerRadius = Math.min(h * 0.46f, 30f * mDensity);
        if (mTuner != null) {
            // The tuner sizes the glass, which lives inside the shadow padding.
            mTuner.onSize(w - mShadowPad * 2, h - mShadowPad * 2, mCornerRadius);
            return;
        }
        mGlossPaint.setShader(new LinearGradient(
                0f, 0f, 0f, h * 0.45f,
                mDarkMode ? 0x1FFFFFFF : 0x40FFFFFF,
                0x00FFFFFF, Shader.TileMode.CLAMP));
    }

    private void capture() {
        try {
            mCapturing = true;
            mThemeProbe.request();
            if (mTuner != null) {
                // LiquidGlassPanel records the pages itself; no bitmaps needed.
                return;
            }
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0 || mSampleRoot.getWidth() <= 0) {
                return;
            }
            if (!mSampleRoot.isShown()) {
                // Undrawn, so its dirty flag never clears; forced afresh instead
                // once it is back.
                mCapturedLastFrame = false;
                return;
            }
            mSampleRoot.getLocationOnScreen(mRootLoc);
            getLocationOnScreen(mSelfLoc);
            int dx = mSelfLoc[0] - mRootLoc[0];
            int dy = mSelfLoc[1] - mRootLoc[1];
            // The rule LiquidGlassPanel.backdropStale spells out, and it matters
            // more here: this is a software pass over the pages, and the
            // invalidate at the end used to ask for the next one straight away,
            // on every frame for as long as the bar was up.
            boolean stale = mRegionBuf == null || !mCapturedLastFrame
                    || mSampleRoot.isDirty()
                    || dx != mCapturedDx || dy != mCapturedDy
                    || w != mCapturedW || h != mCapturedH;
            mCapturedLastFrame = stale;
            if (!stale) {
                return;
            }
            mCapturedDx = dx;
            mCapturedDy = dy;
            mCapturedW = w;
            mCapturedH = h;
            ensureRegionBuffer(w, h);

            Canvas c = new Canvas(mRegionBuf);
            c.save();
            c.clipRect(0f, 0f, w, h);
            c.scale(SAMPLE_SCALE_LEGACY, SAMPLE_SCALE_LEGACY);
            c.translate(-dx, -dy);
            int vis = getVisibility();
            setVisibility(INVISIBLE);
            try {
                mSampleRoot.draw(c);
            } finally {
                setVisibility(vis);
                c.restore();
            }

            applySaturationBoost(mRegionBuf);
            StackBlur.blur(mRegionBuf, BLUR_RADIUS_LEGACY);
            invalidate();
        } catch (Throwable t) {
            LiquidGlassModule.logErr("capture failed", t);
        } finally {
            mCapturing = false;
        }
    }

    private void ensureRegionBuffer(int w, int h) {
        int bw = Math.max(Math.round(w * SAMPLE_SCALE_LEGACY), 1);
        int bh = Math.max(Math.round(h * SAMPLE_SCALE_LEGACY), 1);
        if (mRegionBuf == null
                || mRegionBuf.isRecycled()
                || mRegionBuf.getWidth() != bw
                || mRegionBuf.getHeight() != bh) {
            Bitmap old = mRegionBuf;
            mRegionBuf = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            if (old != null && !old.isRecycled()) {
                old.recycle();
            }
        } else {
            mRegionBuf.eraseColor(android.graphics.Color.TRANSPARENT);
        }
    }

    /** Reads dark/light through the same signal {@link #resolveDark} picked at install. */
    private void probeTheme() {
        // Only walk the labels for the apps that are decided by them; for the
        // rest this stays the config read it always was.
        HostApp app = LiquidGlassModule.app();
        Boolean probe = app != null && app.preferTextColorProbe
                ? detectDarkFromText(mBar) : null;
        boolean detected = resolveDark(getContext(), probe);
        if (!mThemeProbed) {
            mThemeProbed = true;
            LiquidGlassModule.log(android.util.Log.INFO,
                    "theme probe first sample: dark=" + detected
                            + " current=" + mDarkMode);
        }
        if (detected != mDarkMode) {
            mDarkMode = detected;
            if (mTuner != null) {
                mTuner.onTheme(mDarkMode);
            }
            setupPaints();
            invalidate();
            LiquidGlassModule.log(android.util.Log.INFO,
                    "theme switched: dark=" + mDarkMode);
        }
    }

    private void applySaturationBoost(Bitmap bmp) {
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(SATURATION_BOOST);
        Paint p = new Paint();
        p.setColorFilter(new ColorMatrixColorFilter(cm));
        new Canvas(bmp).drawBitmap(bmp, 0f, 0f, p);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        drawPillShadow(canvas);
        if (mTuner != null) {
            // LiquidGlassPanel draws as child view index 0 beneath us.
            return;
        }
        if (getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        drawLegacyFrost(canvas);
    }

    private void drawLegacyFrost(Canvas canvas) {
        float r = mCornerRadius;

        if (mRegionBuf != null && !mRegionBuf.isRecycled()) {
            BitmapShader shader = new BitmapShader(
                    mRegionBuf, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix m = new Matrix();
            m.setScale(
                    getWidth() / (float) mRegionBuf.getWidth(),
                    getHeight() / (float) mRegionBuf.getHeight());
            shader.setLocalMatrix(m);
            mBackdropPaint.setShader(shader);
        } else {
            mBackdropPaint.setShader(null);
            mBackdropPaint.setColor(mDarkMode ? 0x50000000 : 0x8CFFFFFF);
        }
        canvas.drawRoundRect(mBounds, r, r, mBackdropPaint);

        canvas.drawRoundRect(mBounds, r, r, mTintPaint);
        canvas.drawRoundRect(mBounds, r, r, mGlossPaint);

        float half = mBorderPaint.getStrokeWidth() * 0.5f;
        RectF border = new RectF(half, half,
                getWidth() - half, getHeight() - half);
        canvas.drawRoundRect(border, r - half, r - half, mBorderPaint);
    }

    /* ---------------- liquid motion ---------------- */

    private void playRevealAnimation() {
        try {
            setPivotX(getWidth() * 0.5f);
            setPivotY(getHeight());
            setScaleY(0.86f);
            setAlpha(0f);
            animate().alpha(1f).scaleY(1f)
                    .setDuration(380L)
                    .setInterpolator(new OvershootInterpolator(1.1f))
                    .start();
        } catch (Throwable ignored) {
        }
    }

}

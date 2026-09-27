package io.github.liuran001.mmliquidglass;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import java.lang.ref.WeakReference;

/**
 * The liquid-glass surface, reproducing KernelSU's floating bar effect stack.
 *
 * <p>KernelSU composes it through miuix-blur as:
 *
 * <pre>
 * vibrancy()                       // colorControls(saturation = 1.5)
 * blur(4.dp, 4.dp)
 * lens(refractionHeight = 24.dp, refractionAmount = 24.dp)
 * highlight = baseHighlight.copy(alpha = 0.75f)
 * onDrawSurface = { drawRect(surfaceContainer.copy(0.4f)) }
 * </pre>
 *
 * <p>The same three effects map directly onto {@link RenderEffect}'s chaining
 * API, so the backdrop is captured into a {@link RenderNode} and run through
 * saturation → blur → refraction, then the surface wash and edge highlight are
 * painted over it.
 *
 * <p>The refraction shader is Kyant0's rounded-rect SDF lens (Apache-2.0), the
 * same one KernelSU vendors. Its defining property is the early-out: anything
 * further than {@code refractionHeight} from the edge is passed through
 * untouched, so only a band around the rim bends — the middle stays a plain
 * blurred, saturated view of what is behind it.
 *
 * <p>The capture, the saturation and the blur only need Android 12; the lens
 * and the drag highlight are AGSL and need 13. Android 12 therefore gets the
 * same frosted surface with a straight rim — rather than a software frost
 * redrawn on the CPU every frame the pages move.
 */
final class LiquidGlassPanel extends View {

    /** RenderNode capture and RenderEffect blur; below this there is no glass panel. */
    static final int MIN_SDK = 31;
    /** AGSL, for the refracting rim and the drag highlight. */
    static final int LENS_MIN_SDK = 33;

    /** KernelSU: lens(refractionHeight = 24.dp, refractionAmount = 24.dp). */
    private static final float REFRACTION_DP = 24f;
    /** KernelSU: blur(4.dp, 4.dp). */
    private static final float BLUR_DP = 4f;
    /** KernelSU: vibrancy() -> colorControls(saturation = 1.5f). */
    private static final float SATURATION = 1.5f;

    static final String SDF_SOURCE = ""
            + "float radiusAt(float2 coord, float4 radii) {\n"
            + "    if (coord.x >= 0.0) {\n"
            + "        if (coord.y <= 0.0) return radii.y; else return radii.z;\n"
            + "    } else {\n"
            + "        if (coord.y <= 0.0) return radii.x; else return radii.w;\n"
            + "    }\n"
            + "}\n"
            + "float sdRoundedRect(float2 coord, float2 halfSize, float radius) {\n"
            + "    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));\n"
            + "    float outside = length(max(cornerCoord, 0.0)) - radius;\n"
            + "    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);\n"
            + "    return outside + inside;\n"
            + "}\n"
            + "float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {\n"
            + "    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));\n"
            + "    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {\n"
            + "        return sign(coord) * normalize(max(cornerCoord, 0.0));\n"
            + "    } else {\n"
            + "        float gradX = step(cornerCoord.y, cornerCoord.x);\n"
            + "        return sign(coord) * float2(gradX, 1.0 - gradX);\n"
            + "    }\n"
            + "}\n";

    private static final String LENS_SHADER = ""
            + "uniform shader content;\n"
            + "uniform float2 size;\n"
            + "uniform float2 offset;\n"
            + "uniform float4 cornerRadii;\n"
            + "uniform float refractionHeight;\n"
            + "uniform float refractionAmount;\n"
            + "uniform float depthEffect;\n"
            + SDF_SOURCE
            + "float circleMap(float x) { return 1.0 - sqrt(1.0 - x * x); }\n"
            + "half4 main(float2 coord) {\n"
            + "    float2 halfSize = size * 0.5;\n"
            + "    float2 centeredCoord = (coord + offset) - halfSize;\n"
            + "    float radius = radiusAt(coord, cornerRadii);\n"
            + "    float sd = sdRoundedRect(centeredCoord, halfSize, radius);\n"
            + "    if (-sd >= refractionHeight) { return content.eval(coord); }\n"
            + "    sd = min(sd, 0.0);\n"
            + "    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;\n"
            + "    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));\n"
            + "    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize,"
            + "            gradRadius) + depthEffect * normalize(centeredCoord));\n"
            + "    float2 refractedCoord = coord + d * grad;\n"
            + "    return content.eval(refractedCoord);\n"
            + "}\n";

    private final WeakReference<ViewGroup> mBackdropRef;
    private final float mDensity;
    private final int mPad;

    private final RenderNode mNode = new RenderNode("wxLiquidGlass");
    /** Separate display list used when the droplet reuses this blurred surface. */
    private final RenderNode mEmbeddedNode = new RenderNode("wxLiquidGlassEmbedded");
    // Reused every frame: onDraw runs on each traversal, and allocating here
    // would churn the heap for nothing.
    private final int[] mSelf = new int[2];
    private final int[] mProbe = new int[2];
    /** Where and at what scale the last capture was taken; see {@link #backdropStale}. */
    private final int[] mCapturedAt = new int[2];
    private float mCapturedScale;
    private boolean mCaptured;
    private boolean mRecapturedLastFrame;
    private RuntimeShader mLens;
    private RenderEffect mChain;
    private int mChainW;
    private int mChainH;
    private final RenderEffect mSaturate;

    /**
     * KernelSU's InteractiveHighlight: a white wash plus a radial bloom that
     * tracks the droplet, both in Plus blend, fading in with press progress.
     *
     * <p>KernelSU writes this as {@code return color * intensity} over a
     * {@code layout(color)} uniform. That uniform arrives <em>un</em>premultiplied
     * — {@code (1, 1, 1, 0.12)} for white at 12% — while an AGSL shader has to
     * return a premultiplied colour, so the peak really adds rgb 1.0: solid
     * white. KernelSU gets away with it because its droplet covers the blown-out
     * core; WeChat's pill is shorter and narrower, so the core spills across the
     * whole bar and the highlight reads as a blowout. Carrying the alpha as a
     * plain float and returning premultiplied white gives the intended 12%.
     */
    private static final String HIGHLIGHT_SHADER = ""
            + "uniform float2 size;\n"
            + "uniform float alpha;\n"
            + "uniform float radius;\n"
            + "uniform float2 position;\n"
            + "half4 main(float2 coord) {\n"
            + "    float dist = distance(coord, position);\n"
            + "    float intensity = smoothstep(radius, radius * 0.5, dist);\n"
            + "    half a = half(alpha * intensity);\n"
            + "    return half4(a, a, a, a);\n"
            + "}\n";

    private RuntimeShader mHighlightShader;
    private final Paint mBloom = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mWashPlus = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float mInteraction;
    private float mInteractionX;

    /** Press progress and the droplet's centre, in this view's coordinates. */
    void setInteraction(float progress, float centreX) {
        if (mInteraction != progress || mInteractionX != centreX) {
            mInteraction = progress;
            mInteractionX = centreX;
            invalidate();
        }
    }

    private boolean mNight;
    private final Paint mSurfacePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mHighlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mClip = new Path();

    /** Cleared if drawing the glass ever throws; the flat wash stays. */
    private boolean mSupported = true;

    /**
     * Re-captures whenever the capture has gone stale, and only then, so a
     * screen where nothing moves stops drawing. Asked from the draw listener
     * rather than pre-draw: that runs after every pre-draw listener, the
     * selection watcher's pill moves included, and still ahead of the display
     * lists being rebuilt, so the invalidate lands in this very frame. It also
     * books one more traversal, which finds nothing stale and ends there.
     */
    private final ViewTreeObserver.OnDrawListener mStaleCheck = () -> {
        if (backdropStale()) {
            invalidate();
        }
    };

    LiquidGlassPanel(Context ctx, ViewGroup backdrop, float density, boolean night) {
        super(ctx);
        mBackdropRef = new WeakReference<>(backdrop);
        mDensity = density;
        // The lens samples outside its own bounds, so the captured backdrop is
        // grown by the refraction amount on every side.
        mPad = Math.round(REFRACTION_DP * density);

        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(SATURATION);
        mSaturate = RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(cm));

        if (Build.VERSION.SDK_INT >= LENS_MIN_SDK) {
            try {
                mLens = new RuntimeShader(LENS_SHADER);
                mHighlightShader = new RuntimeShader(HIGHLIGHT_SHADER);
            } catch (Throwable t) {
                // Still frosted, just without the bent rim.
                mLens = null;
                LiquidGlassModule.logErr("lens shader rejected", t);
            }
        }
        setTheme(night);
        setWillNotDraw(false);
    }

    /** KernelSU: containerColor = surfaceContainer.copy(0.4f). */
    void setTheme(boolean night) {
        mNight = night;
        mSurfacePaint.setColor(night ? 0x662C2C2E : 0x66F2F2F7);
        // iosIndicatorSpecular: BloomStroke(white @ 0.12), width 1.dp, alpha 0.75.
        mHighlightPaint.setStyle(Paint.Style.STROKE);
        mHighlightPaint.setStrokeWidth(mDensity);
        mHighlightPaint.setColor(night ? 0x1FFFFFFF : 0x2EFFFFFF);
        invalidate();
    }

    boolean hasLens() {
        return mLens != null;
    }

    /**
     * Contributes nothing to a WRAP_CONTENT parent.
     *
     * <p>The host sizes itself to the tab bar. A plain View measured MATCH_PARENT
     * against an AT_MOST spec would report the full parent height and stretch the
     * host to the whole screen; FrameLayout re-measures MATCH_PARENT children
     * with an EXACTLY spec afterwards, which is when the real size arrives.
     */
    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(
                MeasureSpec.getMode(widthSpec) == MeasureSpec.EXACTLY
                        ? MeasureSpec.getSize(widthSpec) : 0,
                MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY
                        ? MeasureSpec.getSize(heightSpec) : 0);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        mClip.reset();
        float r = h * 0.5f;
        mClip.addRoundRect(0, 0, w, h, r, r, Path.Direction.CW);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        // Noted whether or not the capture below succeeds: a capture that
        // cannot be taken must not read as permanently stale.
        mCaptured = true;
        ViewGeom.unscaledScreenPos(this, mCapturedAt);
        mCapturedScale = ViewGeom.cumulativeScale(this);
        drawPanel(canvas, w, h, h * 0.5f, mNode, mCapturedAt, mCapturedScale);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnDrawListener(mStaleCheck);
    }

    @Override
    protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnDrawListener(mStaleCheck);
        super.onDetachedFromWindow();
    }

    /**
     * Whether the frame about to be drawn needs a fresh capture.
     *
     * <p>A capture is a recording of the pages as they stood, and it only goes
     * out of date when they change or when the pill moves over them.
     * Re-recording on every frame regardless — which is what this used to do —
     * means asking for the next frame from inside this one, so the app never
     * stopped drawing: a full frame rate on a screen where nothing moves.
     *
     * <p>A change is read off the pager's dirty flag, which every invalidation
     * underneath it raises and its own draw clears. That flag is not ours
     * though — anything that draws the tree by hand clears it too — so a frame
     * that arrives without this having asked for it counts as a change as
     * well. Should something keep redrawing where the flag cannot see it, that
     * halves the capture rate; it never leaves the glass behind.
     *
     * <p>A pager that is not on screen is not drawn, so nothing ever clears its
     * flag either; it is left alone until it is back rather than read as
     * changing on every frame.
     */
    private boolean backdropStale() {
        ViewGroup pager = mBackdropRef.get();
        if (!mSupported || pager == null || getWidth() <= 0 || getHeight() <= 0
                || !isShown() || getAlpha() == 0f || !pager.isShown()) {
            // Nothing to show it on: taken afresh once there is again.
            mCaptured = false;
            mRecapturedLastFrame = false;
            return false;
        }
        boolean stale = !mCaptured || !mRecapturedLastFrame || pager.isDirty();
        if (!stale) {
            ViewGeom.unscaledScreenPos(this, mProbe);
            stale = mProbe[0] != mCapturedAt[0] || mProbe[1] != mCapturedAt[1]
                    || ViewGeom.cumulativeScale(this) != mCapturedScale;
        }
        mRecapturedLastFrame = stale;
        return stale;
    }

    /** Draws the resting pill material into the droplet's combined backdrop. */
    void drawEmbedded(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        ViewGeom.unscaledScreenPos(this, mSelf);
        drawPanel(canvas, w, h, h * 0.5f, mEmbeddedNode, mSelf, 1f);
    }

    /**
     * @param self this view's unscaled screen position. Scale-free because the
     *     whole bar grows while dragged (KernelSU's layerBlock), and
     *     getLocationOnScreen would report where this view lands after that
     *     transform, not where its layout puts it.
     */
    private void drawPanel(Canvas canvas, int w, int h, float radius,
                           RenderNode node, int[] self, float captureScale) {
        if (mSupported && canvas.isHardwareAccelerated()) {
            try {
                drawGlass(canvas, w, h, radius, node, self, captureScale);
            } catch (Throwable t) {
                mSupported = false;
                LiquidGlassModule.logErr("glass draw failed, flat fallback", t);
            }
        }

        // Surface wash and rim highlight sit on top of the refracted backdrop —
        // this is what carries legibility, not a heavy blur.
        canvas.drawRoundRect(0, 0, w, h, radius, radius, mSurfacePaint);
        drawInteractiveHighlight(canvas, w, h, radius);
        float half = mHighlightPaint.getStrokeWidth() * 0.5f;
        canvas.drawRoundRect(half, half, w - half, h - half,
                radius - half, radius - half, mHighlightPaint);
    }

    /** KernelSU's InteractiveHighlight, drawn over the pill while dragging. */
    private void drawInteractiveHighlight(Canvas canvas, int w, int h, float radius) {
        float p = mInteraction;
        if (p <= 0.01f) {
            return;
        }
        int save = canvas.save();
        canvas.clipPath(mClip);
        // drawRect(White.copy(0.06f * progress), blendMode = Plus). Paint colours
        // are premultiplied by Skia, so this one already lands at the 6% KernelSU
        // asks for and needs no correction.
        mWashPlus.setColor(0xFFFFFFFF);
        mWashPlus.setAlpha(Math.round(0x0F * p));
        mWashPlus.setBlendMode(android.graphics.BlendMode.PLUS);
        canvas.drawRect(0, 0, w, h, mWashPlus);

        // KernelSU: White.copy(0.12f * progress), radius = size.minDimension * 1.2
        // The bloom is AGSL; without it the wash above still carries the press.
        if (mHighlightShader != null) {
            mHighlightShader.setFloatUniform("size", w, h);
            mHighlightShader.setFloatUniform("alpha", 0.12f * p);
            mHighlightShader.setFloatUniform("radius", Math.min(w, h) * 1.2f);
            mHighlightShader.setFloatUniform("position",
                    Math.max(0f, Math.min(mInteractionX, w)), h * 0.5f);
            mBloom.setShader(mHighlightShader);
            mBloom.setBlendMode(android.graphics.BlendMode.PLUS);
            canvas.drawRect(0, 0, w, h, mBloom);
        }
        canvas.restoreToCount(save);
    }

    private void drawGlass(Canvas canvas, int w, int h, float radius,
                           RenderNode node, int[] self, float captureScale) {
        ViewGroup pager = mBackdropRef.get();
        if (pager == null || pager.getWidth() <= 0) {
            return;
        }

        int nw = w + mPad * 2;
        int nh = h + mPad * 2;
        node.setPosition(0, 0, nw, nh);

        RecordingCanvas rc = node.beginRecording(nw, nh);
        try {
            // Undo the scale this view is drawn at — its own and every ancestor's
            // — or the sampled backdrop comes out stretched instead of revealing
            // more of what sits behind.
            if (Math.abs(captureScale - 1f) > 0.001f) {
                rc.scale(1f / captureScale, 1f / captureScale,
                        nw * 0.5f, nh * 0.5f);
            }
            Backdrop.draw(rc, pager, mNight, self[0] - mPad, self[1] - mPad, nw, nh);
        } finally {
            node.endRecording();
        }

        // Every uniform here is a function of the pill's size, so the whole
        // chain only has to be rebuilt when that changes. Building it per frame
        // meant three native effect objects churned on every single frame.
        if (mChain == null || mChainW != w || mChainH != h) {
            float blur = BLUR_DP * mDensity;
            RenderEffect frost = RenderEffect.createBlurEffect(blur, blur, mSaturate,
                    Shader.TileMode.CLAMP);
            if (mLens != null) {
                mLens.setFloatUniform("size", w, h);
                mLens.setFloatUniform("offset", -mPad, -mPad);
                mLens.setFloatUniform("cornerRadii", radius, radius, radius, radius);
                mLens.setFloatUniform("refractionHeight", REFRACTION_DP * mDensity);
                // KernelSU passes the amount negated.
                mLens.setFloatUniform("refractionAmount", -REFRACTION_DP * mDensity);
                mLens.setFloatUniform("depthEffect", 0f);
                mChain = RenderEffect.createChainEffect(
                        RenderEffect.createRuntimeShaderEffect(mLens, "content"), frost);
            } else {
                mChain = frost;
            }
            mChainW = w;
            mChainH = h;
        }
        node.setRenderEffect(mChain);

        canvas.save();
        canvas.clipPath(mClip);
        canvas.translate(-mPad, -mPad);
        canvas.drawRenderNode(node);
        canvas.restore();
    }
}

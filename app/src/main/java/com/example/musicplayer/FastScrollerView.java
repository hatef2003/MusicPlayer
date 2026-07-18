package com.example.musicplayer;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Terminal-styled fast scroller for RecyclerView.
 * Thin green track on the left with a draggable thumb and section-letter bubble.
 */
public class FastScrollerView extends View {

    /* ── Paints ────────────────────────────────────────────── */
    private final Paint trackPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubbleBg     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubbleBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubbleText   = new Paint(Paint.ANTI_ALIAS_FLAG);

    /* ── RecyclerView binding ──────────────────────────────── */
    private RecyclerView recyclerView;
    private LinearLayoutManager lm;
    private int itemCount;

    /* ── Scroller state ────────────────────────────────────── */
    private boolean dragging;
    private float   progress;          // 0‥1
    private String  section = "";
    private float   bubbleAlpha;
    private float   thumbAlpha = 1f;   // 0‥1 — fades thumb + track when idle
    private float   glowAlpha  = 0f;   // 0‥1 — green glow when dragging

    /* ── Dimensions (px) ───────────────────────────────────── */
    private final int trackW;
    private final int thumbW;
    private final int thumbH;
    private final int bubbleR;
    private final int cornerR;
    private final int trackX;      // fixed horizontal center of track

    /* ── Animation ─────────────────────────────────────────── */
    private ValueAnimator bubbleAnim;
    private ValueAnimator thumbFadeAnim;
    private ValueAnimator glowAnim;
    private final Runnable hideThumbRunnable = this::fadeOutThumb;
    private static final long HIDE_DELAY_MS = 2000;
    private static final int GLOW_COLOR  = 0xFF39FF6A;   // terminal green
    private static final int GLOW_RADIUS_DP = 12;

    /* ── Section provider ──────────────────────────────────── */
    public interface SectionProvider {
        /** Return a display string for the item at {@code pos}. */
        String getSection(int pos);
    }

    private SectionProvider sectionProvider;

    /* ═══════════════════════════════════════════════════════════
       Constructors
       ═══════════════════════════════════════════════════════════ */

    public FastScrollerView(@NonNull Context ctx)                          { this(ctx, null); }
    public FastScrollerView(@NonNull Context ctx, @Nullable AttributeSet a) { this(ctx, a, 0); }

    public FastScrollerView(@NonNull Context ctx, @Nullable AttributeSet a, int def) {
        super(ctx, a, def);
        setLayerType(LAYER_TYPE_SOFTWARE, null);   // required for setShadowLayer glow

        trackW  = dp(1.5f);
        thumbW  = dp(3);
        thumbH  = dp(44);
        bubbleR = dp(18);
        cornerR = dp(4);
        trackX  = dp(6);           // fixed 6dp from left edge — pushed left to avoid content collision

        // Track – dim green line
        trackPaint.setColor(0x3339FF6A);
        trackPaint.setStrokeWidth(trackW);
        trackPaint.setStrokeCap(Paint.Cap.ROUND);

        // Thumb – solid green
        thumbPaint.setColor(0xFF39FF6A);

        // Bubble – dark bg with subtle green border
        bubbleBg.setColor(0xE6101010);
        bubbleBorder.setColor(0x4039FF6A);
        bubbleBorder.setStyle(Paint.Style.STROKE);
        bubbleBorder.setStrokeWidth(dp(1));

        // Bubble text – green monospace
        bubbleText.setColor(0xFF39FF6A);
        bubbleText.setTextSize(dp(15));
        bubbleText.setTypeface(Typeface.MONOSPACE);
        bubbleText.setTextAlign(Paint.Align.CENTER);
    }

    /* ═══════════════════════════════════════════════════════════
       Public API
       ═══════════════════════════════════════════════════════════ */

    public void setSectionProvider(SectionProvider sp) { sectionProvider = sp; }

    /**
     * Bind to a RecyclerView.  Must be called after the adapter is set.
     * Registers scroll + data observers automatically.
     */
    public void attachRecyclerView(@NonNull RecyclerView rv) {
        recyclerView = rv;
        lm = (LinearLayoutManager) rv.getLayoutManager();
        itemCount = rv.getAdapter() != null ? rv.getAdapter().getItemCount() : 0;

        // Scroll listener – sync thumb
        rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView r, int dx, int dy) {
                if (!dragging) syncFromScroll();
            }
        });

        // Data observer – keep item count in sync
        if (rv.getAdapter() != null) {
            rv.getAdapter().registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
                @Override public void onChanged()                      { refresh(); }
                @Override public void onItemRangeInserted(int p, int c){ refresh(); }
                @Override public void onItemRangeRemoved(int p, int c) { refresh(); }
            });
        }

        post(this::syncFromScroll);
    }

    /** Manually refresh after external data changes. */
    public void refresh() {
        if (recyclerView != null && recyclerView.getAdapter() != null)
            itemCount = recyclerView.getAdapter().getItemCount();
        post(this::syncFromScroll);
    }

    /* ═══════════════════════════════════════════════════════════
       Drawing
       ═══════════════════════════════════════════════════════════ */

    @Override
    protected void onDraw(@NonNull Canvas c) {
        int   w   = getWidth();
        int   h   = getHeight();
        float cx  = trackX;        // fixed track position, not view center
        float pad = thumbH / 2f;

        float top = pad;
        float bot = h - pad;
        float len = bot - top;

        // ── Track line (fades with thumb) ──
        trackPaint.setAlpha((int) (0x33 * thumbAlpha));
        c.drawLine(cx, top, cx, bot, trackPaint);

        // ── Thumb (rounded rect, with glow when dragging) ──
        float ty = top + progress * len;
        RectF tr = new RectF(cx - thumbW / 2f, ty - thumbH / 2f,
                              cx + thumbW / 2f, ty + thumbH / 2f);

        // Glow layer beneath thumb
        if (glowAlpha > 0.01f) {
            int glowA = (int) (0x66 * glowAlpha);     // peak alpha ~40%
            thumbPaint.setShadowLayer(dp(GLOW_RADIUS_DP), 0, 0,
                    (glowA << 24) | (GLOW_COLOR & 0x00FFFFFF));
        } else {
            thumbPaint.setShadowLayer(0, 0, 0, 0);
        }
        thumbPaint.setAlpha((int) (255 * thumbAlpha));
        c.drawRoundRect(tr, cornerR, cornerR, thumbPaint);
        // Reset shadow so it doesn't affect other draws
        thumbPaint.setShadowLayer(0, 0, 0, 0);

        // ── Section bubble (drawn to the right of the thumb) ──
        if (dragging && bubbleAlpha > 0.01f && !section.isEmpty()) {
            float bx = cx + thumbW / 2f + dp(6) + bubbleR;
            float by = clamp(ty, top + bubbleR, bot - bubbleR);

            int layer = c.saveLayerAlpha(0, 0, w, h,
                    (int) (bubbleAlpha * 255), Canvas.ALL_SAVE_FLAG);

            RectF br = new RectF(bx - bubbleR, by - bubbleR,
                                  bx + bubbleR, by + bubbleR);
            c.drawRoundRect(br, dp(6), dp(6), bubbleBg);
            c.drawRoundRect(br, dp(6), dp(6), bubbleBorder);

            float textY = by - (bubbleText.descent() + bubbleText.ascent()) / 2f;
            c.drawText(section, bx, textY, bubbleText);

            c.restoreToCount(layer);
        }
    }

    /* ═══════════════════════════════════════════════════════════
       Touch handling
       ═══════════════════════════════════════════════════════════ */

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (itemCount <= 0) return false;

        float pad = thumbH / 2f;
        float top = pad;
        float bot = getHeight() - pad;

        // Only handle touches in the left track area (first 24dp)
        float trackTouchWidth = dp(24);

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (e.getX() > trackTouchWidth) return false;
                dragging = true;
                showThumb();
                animateGlow(true);
                animateBubble(true);
                seekTo(e.getY(), top, bot);
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                return true;

            case MotionEvent.ACTION_MOVE:
                if (dragging) {
                    seekTo(e.getY(), top, bot);
                    return true;
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    dragging = false;
                    animateGlow(false);
                    animateBubble(false);
                    scheduleHideThumb();
                    return true;
                }
                break;
        }
        return super.onTouchEvent(e);
    }

    /* ═══════════════════════════════════════════════════════════
       Internal helpers
       ═══════════════════════════════════════════════════════════ */

    private void syncFromScroll() {
        if (lm == null || itemCount <= 0) { invalidate(); return; }
        int first = lm.findFirstVisibleItemPosition();
        if (first == RecyclerView.NO_POSITION) first = 0;
        progress = itemCount > 1 ? (float) first / (itemCount - 1) : 0f;
        updateSection(first);
        scheduleHideThumb();   // reset the hide timer on every scroll
        invalidate();
    }

    private void seekTo(float y, float top, float bot) {
        float p = clamp((y - top) / (bot - top), 0f, 1f);
        progress = p;

        int target = Math.round(p * (itemCount - 1));
        target = Math.max(0, Math.min(itemCount - 1, target));

        if (lm != null) lm.scrollToPositionWithOffset(target, 0);

        updateSection(target);
        invalidate();
    }

    private void updateSection(int pos) {
        if (sectionProvider != null && pos >= 0 && pos < itemCount) {
            String s = sectionProvider.getSection(pos);
            section = (s != null && !s.isEmpty())
                    ? s.substring(0, 1).toUpperCase()
                    : "";
        }
    }

    /* ── Thumb show / hide ──────────────────────────────────── */

    /** Show the thumb immediately and reset the idle timer. */
    private void showThumb() {
        removeCallbacks(hideThumbRunnable);
        if (thumbAlpha >= 1f) return;   // already fully visible, skip redraw
        if (thumbFadeAnim != null) thumbFadeAnim.cancel();
        if (thumbAlpha < 1f) {
            thumbFadeAnim = ValueAnimator.ofFloat(thumbAlpha, 1f);
            thumbFadeAnim.setDuration(150);
            thumbFadeAnim.addUpdateListener(a -> {
                thumbAlpha = (float) a.getAnimatedValue();
                invalidate();
            });
            thumbFadeAnim.start();
        } else {
            thumbAlpha = 1f;
            invalidate();
        }
    }

    /** Schedule a fade-out after {@link #HIDE_DELAY_MS} of inactivity. */
    private void scheduleHideThumb() {
        removeCallbacks(hideThumbRunnable);
        showThumb();
        postDelayed(hideThumbRunnable, HIDE_DELAY_MS);
    }

    /** Fade the thumb out to 20% opacity. */
    private void fadeOutThumb() {
        if (dragging) return;
        if (thumbFadeAnim != null) thumbFadeAnim.cancel();
        thumbFadeAnim = ValueAnimator.ofFloat(thumbAlpha, 0.2f);
        thumbFadeAnim.setDuration(400);
        thumbFadeAnim.addUpdateListener(a -> {
            thumbAlpha = (float) a.getAnimatedValue();
            invalidate();
        });
        thumbFadeAnim.start();
    }

    private void animateGlow(boolean on) {
        if (glowAnim != null) glowAnim.cancel();
        float from = glowAlpha;
        float to   = on ? 1f : 0f;
        glowAnim = ValueAnimator.ofFloat(from, to);
        glowAnim.setDuration(on ? 150 : 300);
        glowAnim.addUpdateListener(a -> { glowAlpha = (float) a.getAnimatedValue(); invalidate(); });
        glowAnim.start();
    }

    private void animateBubble(boolean show) {
        if (bubbleAnim != null) bubbleAnim.cancel();
        float from = bubbleAlpha;
        float to   = show ? 1f : 0f;
        bubbleAnim = ValueAnimator.ofFloat(from, to);
        bubbleAnim.setDuration(show ? 120 : 200);
        bubbleAnim.addUpdateListener(a -> { bubbleAlpha = (float) a.getAnimatedValue(); invalidate(); });
        bubbleAnim.start();
    }

    /* ── Utilities ─────────────────────────────────────────── */

    private int dp(float v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(hideThumbRunnable);
        if (thumbFadeAnim != null) thumbFadeAnim.cancel();
        if (bubbleAnim != null) bubbleAnim.cancel();
        if (glowAnim != null) glowAnim.cancel();
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}

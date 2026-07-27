package com.example.musicplayer;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.example.musicplayer.model.Song;

/**
 * A reusable, futuristic "Tech UI" context menu that replaces the standard
 * PopupMenu / AlertDialog.  Shows a floating glassmorphism card with grouped
 * menu items, optional song header with album art, and smooth animations.
 *
 * <p>Usage:
 * <pre>
 *   new TechContextMenu.Builder(context)
 *       .setSong(song)                       // optional header
 *       .addItem(R.drawable.ic_x, "Label", () -> { ... })
 *       .addSpacer()
 *       .addItem(R.drawable.ic_y, "Label 2", () -> { ... })
 *       .show(anchorView);
 * </pre>
 */
public class TechContextMenu {

    // ── Listener interface ────────────────────────────────────────────────
    public interface OnDismissListener { void onDismiss(); }

    // ── Builder ───────────────────────────────────────────────────────────
    public static class Builder {
        private final Context context;
        private final java.util.List<Object> items = new java.util.ArrayList<>();
        private Song song;
        private String headerSubtitle;      // e.g. "Artist • Album"
        private OnDismissListener dismissListener;

        public Builder(Context context) {
            this.context = context;
        }

        /** Show a song header with album art at the top of the menu. */
        public Builder setSong(Song song) {
            this.song = song;
            return this;
        }

        /** Override the subtitle shown below the song title in the header. */
        public Builder setHeaderSubtitle(String subtitle) {
            this.headerSubtitle = subtitle;
            return this;
        }

        /** Add a clickable menu item. */
        public Builder addItem(int iconRes, String label, Runnable action) {
            items.add(new MenuItem(iconRes, label, action, false));
            return this;
        }

        /** Add a destructive (red-tinted) menu item. */
        public Builder addDestructiveItem(int iconRes, String label, Runnable action) {
            items.add(new MenuItem(iconRes, label, action, true));
            return this;
        }

        /** Add a visual spacer between groups. */
        public Builder addSpacer() {
            items.add(SPACER);
            return this;
        }

        public Builder setOnDismissListener(OnDismissListener listener) {
            this.dismissListener = listener;
            return this;
        }

        /** Build and show the menu, anchored near the given view. */
        public void show(View anchor) {
            new TechContextMenu(this).showInternal(anchor);
        }
    }

    // ── Internal data ─────────────────────────────────────────────────────
    private static final Object SPACER = new Object();

    private static class MenuItem {
        final int iconRes;
        final String label;
        final Runnable action;
        final boolean destructive;

        MenuItem(int iconRes, String label, Runnable action, boolean destructive) {
            this.iconRes = iconRes;
            this.label = label;
            this.action = action;
            this.destructive = destructive;
        }
    }

    /** Tracks the currently visible menu to prevent double-open stacking. */
    private static TechContextMenu sActiveMenu;

    // ── Instance fields ───────────────────────────────────────────────────
    private final Builder builder;
    private ViewGroup overlayView;
    private View cardView;
    private AnimatorSet currentAnimator;
    private OnBackPressedCallback backCallback;

    private TechContextMenu(Builder builder) {
        this.builder = builder;
    }

    // ── Show logic ────────────────────────────────────────────────────────
    private void showInternal(View anchor) {
        // Dismiss any previously-open menu to prevent stacking
        if (sActiveMenu != null) {
            sActiveMenu.dismiss();
            sActiveMenu = null;
        }

        Context ctx = anchor.getContext();
        if (!(ctx instanceof Activity)) return;
        Activity activity = (Activity) ctx;
        if (activity.isFinishing()) return;

        // Inflate the overlay
        LayoutInflater inflater = LayoutInflater.from(ctx);
        overlayView = (ViewGroup) inflater.inflate(R.layout.tech_menu_container, null);
        cardView = overlayView.findViewById(R.id.techMenuCard);

        // ── Populate header ──
        if (builder.song != null) {
            setupHeader(ctx);
        }

        // ── Populate menu items ──
        LinearLayout itemsContainer = overlayView.findViewById(R.id.techMenuItems);
        populateItems(ctx, itemsContainer);

        // ── Dismiss on background tap ──
        overlayView.findViewById(R.id.techMenuRoot).setOnClickListener(v -> dismiss());

        // ── Intercept back button: dismiss menu instead of finishing activity ──
        if (activity instanceof AppCompatActivity) {
            AppCompatActivity appActivity = (AppCompatActivity) activity;
            backCallback = new OnBackPressedCallback(true) {
                @Override
                public void handleOnBackPressed() {
                    dismiss();
                }
            };
            appActivity.getOnBackPressedDispatcher()
                    .addCallback(appActivity, backCallback);
        }

        // ── Add to window ──
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        activity.addContentView(overlayView, lp);

        // ── Entrance animation ──
        sActiveMenu = this;
        playEnterAnimation();
    }

    private void setupHeader(Context ctx) {
        View header = overlayView.findViewById(R.id.techMenuHeader);
        View divider = overlayView.findViewById(R.id.techMenuHeaderDivider);
        header.setVisibility(View.VISIBLE);
        divider.setVisibility(View.VISIBLE);

        ImageView albumArt = overlayView.findViewById(R.id.techMenuAlbumArt);
        TextView titleView = overlayView.findViewById(R.id.techMenuSongTitle);
        TextView artistView = overlayView.findViewById(R.id.techMenuSongArtist);

        Song song = builder.song;
        titleView.setText(song.getTitle());

        String subtitle = builder.headerSubtitle;
        if (subtitle == null || subtitle.isEmpty()) {
            subtitle = song.getArtist();
            if (song.getAlbum() != null && !song.getAlbum().isEmpty()) {
                subtitle += " • " + song.getAlbum();
            }
        }
        artistView.setText(subtitle);

        // Load album art
        Uri artUri = MusicRepository.getAlbumArtUri(song.getAlbumId());
        if (artUri != null) {
            int radiusPx = dp(ctx, 8);
            Glide.with(albumArt)
                    .asBitmap()
                    .load(artUri)
                    .transform(new RoundedCorners(radiusPx))
                    .placeholder(R.drawable.ic_album)
                    .error(R.drawable.ic_album)
                    .into(new CustomTarget<Bitmap>() {
                        @Override
                        public void onResourceReady(Bitmap resource, Transition<? super Bitmap> t) {
                            albumArt.setImageBitmap(resource);
                        }
                        @Override
                        public void onLoadCleared(Drawable placeholder) {
                            albumArt.setImageResource(R.drawable.ic_album);
                        }
                    });
        }

        // Tap header → open NowPlayingActivity
        header.setOnClickListener(v -> {
            dismiss();
            android.content.Intent intent = new android.content.Intent(ctx, NowPlayingActivity.class);
            intent.setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
            ctx.startActivity(intent);
        });
    }

    private void populateItems(Context ctx, LinearLayout container) {
        boolean isFirst = true;
        for (Object obj : builder.items) {
            if (obj == SPACER) {
                // Add vertical spacing between groups (no visible divider)
                View spacer = new View(ctx);
                spacer.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 8)));
                container.addView(spacer);
                isFirst = true;
                continue;
            }

            MenuItem mi = (MenuItem) obj;

            // Inflate item
            View itemView = LayoutInflater.from(ctx).inflate(R.layout.tech_menu_item, container, false);
            ImageView icon = itemView.findViewById(R.id.menuItemIcon);
            TextView label = itemView.findViewById(R.id.menuItemLabel);

            icon.setImageResource(mi.iconRes);
            label.setText(mi.label);

            if (mi.destructive) {
                label.setTextColor(0xFFFF4444);
                icon.setColorFilter(0xFFFF4444);
            }

            // Add top margin for first item after spacer (extra breathing room)
            if (!isFirst) {
                // Normal spacing
            }
            isFirst = false;

            // Click handler with press animation
            itemView.setOnClickListener(v -> {
                playPressAnimation(v, () -> {
                    dismiss();
                    if (mi.action != null) {
                        new Handler(Looper.getMainLooper()).post(mi.action);
                    }
                });
            });

            container.addView(itemView);
        }
    }

    // ── Dismiss ────────────────────────────────────────────────────────────
    public void dismiss() {
        if (overlayView == null) return;
        if (sActiveMenu == this) sActiveMenu = null;
        // Remove back-press interceptor immediately so subsequent back presses
        // are handled by the activity (e.g. finish())
        if (backCallback != null) {
            backCallback.remove();
            backCallback = null;
        }
        playExitAnimation(() -> {
            if (overlayView != null && overlayView.getParent() instanceof ViewGroup) {
                ((ViewGroup) overlayView.getParent()).removeView(overlayView);
            }
            overlayView = null;
            if (builder.dismissListener != null) {
                builder.dismissListener.onDismiss();
            }
        });
    }

    // ── Animations ─────────────────────────────────────────────────────────

    /** Fade in overlay + scale up the card from 95% → 100%. */
    private void playEnterAnimation() {
        if (cardView == null) return;

        // Overlay fade in
        ObjectAnimator overlayAlpha = ObjectAnimator.ofFloat(overlayView, "alpha", 0f, 1f);
        overlayAlpha.setDuration(180);

        // Card scale from 95% → 100% with spring overshoot
        cardView.setScaleX(0.95f);
        cardView.setScaleY(0.95f);
        cardView.setAlpha(0f);

        ObjectAnimator cardScaleX = ObjectAnimator.ofFloat(cardView, "scaleX", 0.95f, 1f);
        ObjectAnimator cardScaleY = ObjectAnimator.ofFloat(cardView, "scaleY", 0.95f, 1f);
        ObjectAnimator cardAlpha = ObjectAnimator.ofFloat(cardView, "alpha", 0f, 1f);

        cardScaleX.setDuration(280);
        cardScaleY.setDuration(280);
        cardAlpha.setDuration(180);

        OvershootInterpolator spring = new OvershootInterpolator(1.2f);
        cardScaleX.setInterpolator(spring);
        cardScaleY.setInterpolator(spring);

        if (currentAnimator != null) currentAnimator.cancel();
        currentAnimator = new AnimatorSet();
        currentAnimator.playTogether(overlayAlpha, cardScaleX, cardScaleY, cardAlpha);
        currentAnimator.start();
    }

    /** Fade out overlay + scale down the card to 96%. */
    private void playExitAnimation(Runnable onEnd) {
        if (cardView == null) {
            if (onEnd != null) onEnd.run();
            return;
        }

        ObjectAnimator overlayAlpha = ObjectAnimator.ofFloat(overlayView, "alpha", 1f, 0f);
        overlayAlpha.setDuration(150);

        ObjectAnimator cardScaleX = ObjectAnimator.ofFloat(cardView, "scaleX", 1f, 0.96f);
        ObjectAnimator cardScaleY = ObjectAnimator.ofFloat(cardView, "scaleY", 1f, 0.96f);
        ObjectAnimator cardAlpha = ObjectAnimator.ofFloat(cardView, "alpha", 1f, 0f);

        cardScaleX.setDuration(150);
        cardScaleY.setDuration(150);
        cardAlpha.setDuration(150);

        if (currentAnimator != null) currentAnimator.cancel();
        currentAnimator = new AnimatorSet();
        currentAnimator.playTogether(overlayAlpha, cardScaleX, cardScaleY, cardAlpha);
        currentAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (onEnd != null) onEnd.run();
            }
        });
        currentAnimator.start();
    }

    /** Micro press animation: scale 1.0 → 0.98 → 1.0 on tap. */
    private void playPressAnimation(View v, Runnable onEnd) {
        ObjectAnimator down = ObjectAnimator.ofFloat(v, "scaleX", 1f, 0.96f);
        ObjectAnimator downY = ObjectAnimator.ofFloat(v, "scaleY", 1f, 0.96f);
        down.setDuration(60);
        downY.setDuration(60);

        AnimatorSet pressDown = new AnimatorSet();
        pressDown.playTogether(down, downY);
        pressDown.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                ObjectAnimator up = ObjectAnimator.ofFloat(v, "scaleX", 0.96f, 1f);
                ObjectAnimator upY = ObjectAnimator.ofFloat(v, "scaleY", 0.96f, 1f);
                up.setDuration(100);
                upY.setDuration(100);

                AnimatorSet pressUp = new AnimatorSet();
                pressUp.playTogether(up, upY);
                pressUp.setInterpolator(new OvershootInterpolator(2f));
                pressUp.addListener(new android.animation.AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(android.animation.Animator animation) {
                        if (onEnd != null) onEnd.run();
                    }
                });
                pressUp.start();
            }
        });
        pressDown.start();
    }

    // ── Helpers ────────────────────────────────────────────────────────────
    private static int dp(Context ctx, int dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, ctx.getResources().getDisplayMetrics());
    }
}

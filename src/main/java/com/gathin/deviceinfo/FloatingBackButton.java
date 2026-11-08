package com.gathin.deviceinfo;

import android.accessibilityservice.AccessibilityService;
import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

@TargetApi(22)
final class FloatingBackButton {
    private final WindowManager windows;
    private final View button;
    private final WindowManager.LayoutParams layout;
    private final Point displaySize = new Point();
    private final int touchSlop;
    private final int edgeMargin;
    private final Runnable dimButton = this::dim;
    private float downX;
    private float downY;
    private int startX;
    private int startY;
    private boolean dragging;
    private boolean homeReady;
    private TextView homeHint;
    private final Runnable armHome = this::showHomeReady;
    private final Runnable dismissHomeHint = this::hideHomeHint;

    FloatingBackButton(AccessibilityService service) {
        windows = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
        touchSlop = ViewConfiguration.get(service).getScaledTouchSlop();
        float density = service.getResources().getDisplayMetrics().density;
        int size = Math.round(48 * density);
        edgeMargin = Math.round(8 * density);
        LayerDrawable face = new LayerDrawable(new Drawable[] {
                circle(0xFF242424), circle(0xFF757575), circle(0xFFF5F5F5)
        });
        int ringInset = Math.round(6 * density);
        int centerInset = Math.round(10 * density);
        face.setLayerInset(1, ringInset, ringInset, ringInset, ringInset);
        face.setLayerInset(2, centerInset, centerInset, centerInset, centerInset);

        button = new View(service);
        button.setBackground(face);
        button.setAlpha(0.55f);
        button.setContentDescription(service.getString(R.string.floating_back_action));
        button.setOnClickListener(view -> {
            if (!service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) {
                Toast.makeText(service, R.string.floating_back_action_failed, Toast.LENGTH_SHORT).show();
            }
        });
        button.setOnLongClickListener(view -> {
            boolean opened = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
            if (!opened) showTemporaryHomeHint(R.string.floating_home_failed);
            return true;
        });
        layout = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        layout.gravity = Gravity.LEFT | Gravity.TOP;
        windows.getDefaultDisplay().getSize(displaySize);
        layout.x = Math.max(0, displaySize.x - size - edgeMargin);
        layout.y = Math.max(0, (displaySize.y - size) / 2);
        button.setOnTouchListener(this::handleTouch);
        windows.addView(button, layout);
    }

    private boolean handleTouch(View view, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                view.removeCallbacks(dimButton);
                view.animate().cancel();
                view.setAlpha(1f);
                downX = event.getRawX();
                downY = event.getRawY();
                startX = layout.x;
                startY = layout.y;
                dragging = false;
                view.setPressed(true);
                hideHomeHint();
                view.postDelayed(armHome, ViewConfiguration.getLongPressTimeout());
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - downX;
                float dy = event.getRawY() - downY;
                if (!dragging && !movedPastTouchSlop(event)) return true;
                cancelHome();
                dragging = true;
                view.setPressed(false);
                windows.getDefaultDisplay().getSize(displaySize);
                layout.x = clamp(startX + Math.round(dx), displaySize.x - layout.width);
                layout.y = clamp(startY + Math.round(dy), displaySize.y - layout.height);
                windows.updateViewLayout(view, layout);
                return true;
            case MotionEvent.ACTION_UP:
                view.setPressed(false);
                view.postDelayed(dimButton, 1500);
                view.removeCallbacks(armHome);
                if (dragging || movedPastTouchSlop(event)) {
                    cancelHome();
                    return true;
                }
                if (homeReady) {
                    homeReady = false;
                    hideHomeHint();
                    view.performLongClick();
                } else {
                    view.performClick();
                }
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                cancelHome();
                dragging = true;
                view.setPressed(false);
                return true;
            case MotionEvent.ACTION_CANCEL:
                cancelHome();
                view.postDelayed(dimButton, 1500);
                view.setPressed(false);
                return true;
            default:
                return true;
        }
    }

    private void showHomeReady() {
        homeReady = true;
        showHomeHint(R.string.floating_home_ready);
    }

    private void cancelHome() {
        button.removeCallbacks(armHome);
        if (homeReady) {
            homeReady = false;
            showTemporaryHomeHint(R.string.floating_home_cancelled);
        }
    }

    private void showTemporaryHomeHint(int message) {
        showHomeHint(message);
        button.postDelayed(dismissHomeHint, 1200);
    }

    private void showHomeHint(int message) {
        button.removeCallbacks(dismissHomeHint);
        if (homeHint != null) {
            homeHint.setText(message);
            return;
        }
        float density = button.getResources().getDisplayMetrics().density;
        TextView hint = new TextView(button.getContext());
        hint.setText(message);
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(16);
        int horizontalPadding = Math.round(16 * density);
        int verticalPadding = Math.round(8 * density);
        hint.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xEE242424);
        background.setCornerRadius(verticalPadding);
        hint.setBackground(background);
        WindowManager.LayoutParams hintLayout = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        hintLayout.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        hintLayout.y = Math.round(16 * density);
        windows.addView(hint, hintLayout);
        homeHint = hint;
    }

    private void hideHomeHint() {
        button.removeCallbacks(dismissHomeHint);
        if (homeHint != null) {
            windows.removeViewImmediate(homeHint);
            homeHint = null;
        }
    }

    private boolean movedPastTouchSlop(MotionEvent event) {
        return Math.hypot(event.getRawX() - downX, event.getRawY() - downY) > touchSlop;
    }

    private static GradientDrawable circle(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(color);
        return shape;
    }

    private void dim() {
        button.animate().alpha(0.55f).setDuration(200).start();
    }

    private static int clamp(int position, int maximum) {
        return Math.max(0, Math.min(position, maximum));
    }

    void remove() {
        button.removeCallbacks(armHome);
        hideHomeHint();
        button.removeCallbacks(dimButton);
        button.animate().cancel();
        windows.removeViewImmediate(button);
    }
}

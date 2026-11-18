package com.limelight.quickconfig;

import android.view.MotionEvent;
import android.view.View;

/**
 * Two-finger pinch zoom + pan for a single target view.
 *
 * This intentionally lives outside of {@code Game} so that the upstream file only
 * needs a couple of one-line hooks. It works purely with raw (screen) coordinates
 * for the gesture math, so it is unaffected by the view transformation it applies.
 */
public class PinchZoomHelper {
    private static final float MIN_ZOOM = 1.0f;
    private static final float MAX_ZOOM = 3.0f;

    // A two-finger gesture must change its span by this much before we treat it as a
    // pinch. Below that threshold we leave the event alone so scrolling still works.
    private static final float TRIGGER_RATIO = 0.15f;

    private final View target;

    private float scale = 1.0f;
    private float panX = 0.0f;
    private float panY = 0.0f;

    private boolean active;
    private float startSpan;
    private float startScale = 1.0f;
    private float startMidX;
    private float startMidY;
    private float startPanX;
    private float startPanY;

    public PinchZoomHelper(View target) {
        this.target = target;
    }

    public boolean isZoomed() {
        return scale != 1.0f;
    }

    public void reset() {
        scale = 1.0f;
        panX = 0.0f;
        panY = 0.0f;
        active = false;
        apply();
    }

    /**
     * Handle a motion event.
     *
     * @return true if the event belongs to an in-progress pinch gesture and must not be
     *         forwarded to the host as normal touch input.
     */
    public boolean handleEvent(MotionEvent event) {
        final int action = event.getActionMasked();
        final int pointerCount = event.getPointerCount();

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            boolean wasActive = active;
            active = false;
            return wasActive;
        }

        // Once a pinch is under way, keep consuming the rest of the gesture (even after
        // one finger lifts) so a stray single-finger event doesn't reach the host.
        if (pointerCount < 2) {
            return active;
        }

        float span = distance(event, 0, 1);
        float midX = (event.getRawX(0) + event.getRawX(1)) / 2.0f;
        float midY = (event.getRawY(0) + event.getRawY(1)) / 2.0f;

        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            beginGesture(span, midX, midY);
            return active;
        }

        if (action == MotionEvent.ACTION_POINTER_UP) {
            return active;
        }

        if (action != MotionEvent.ACTION_MOVE) {
            return active;
        }

        if (startSpan <= 0.0f) {
            beginGesture(span, midX, midY);
        }

        float ratio = span / startSpan;

        if (!active) {
            if (Math.abs(ratio - 1.0f) < TRIGGER_RATIO) {
                // Not (yet) a pinch, so allow the normal two-finger scroll to happen.
                startMidX = midX;
                startMidY = midY;
                return false;
            }
            active = true;
        }

        scale = clamp(startScale * ratio, MIN_ZOOM, MAX_ZOOM);
        panX = startPanX + (midX - startMidX);
        panY = startPanY + (midY - startMidY);
        clampPan();
        apply();
        return true;
    }

    /** Maps a parent-space X coordinate back to the target's untransformed local space. */
    public float mapToLocalX(float parentX) {
        if (scale == 1.0f) {
            return parentX - target.getX();
        }
        float pivot = target.getWidth() / 2.0f;
        return pivot + (parentX - target.getX() - pivot) / scale;
    }

    /** Maps a parent-space Y coordinate back to the target's untransformed local space. */
    public float mapToLocalY(float parentY) {
        if (scale == 1.0f) {
            return parentY - target.getY();
        }
        float pivot = target.getHeight() / 2.0f;
        return pivot + (parentY - target.getY() - pivot) / scale;
    }

    private void beginGesture(float span, float midX, float midY) {
        startSpan = span;
        startScale = scale;
        startMidX = midX;
        startMidY = midY;
        startPanX = panX;
        startPanY = panY;
    }

    private void apply() {
        target.setPivotX(target.getWidth() / 2.0f);
        target.setPivotY(target.getHeight() / 2.0f);
        target.setScaleX(scale);
        target.setScaleY(scale);
        target.setTranslationX(panX);
        target.setTranslationY(panY);
    }

    private void clampPan() {
        // Never let the scaled content be dragged fully off screen.
        float maxX = target.getWidth() * (scale - 1.0f) / 2.0f;
        float maxY = target.getHeight() * (scale - 1.0f) / 2.0f;
        panX = Math.max(-maxX, Math.min(maxX, panX));
        panY = Math.max(-maxY, Math.min(maxY, panY));
    }

    private static float distance(MotionEvent event, int a, int b) {
        float dx = event.getRawX(a) - event.getRawX(b);
        float dy = event.getRawY(a) - event.getRawY(b);
        return (float) Math.hypot(dx, dy);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
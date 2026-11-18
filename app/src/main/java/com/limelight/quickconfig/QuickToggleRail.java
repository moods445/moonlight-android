package com.limelight.quickconfig;

import android.app.Activity;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.limelight.R;

/**
 * Always-visible column of one-tap toggles pinned to the left edge of the stream.
 *
 * <p>Everything reachable from the quick settings panel is duplicated here as an icon that never
 * has to be opened first: the panel toggle, the virtual gamepad, the touch mode (direct touch vs
 * trackpad) and the keyboard. The rail only renders state and reports intent through
 * {@link Listener}; the actual behaviour lives in {@link QuickConfigPanel}.
 */
public class QuickToggleRail extends LinearLayout {

    /** Keyboard display modes the rail can cycle through. */
    public static final int KEYBOARD_OFF = 0;
    public static final int KEYBOARD_BUILT_IN = 1;
    public static final int KEYBOARD_ANDROID = 2;

    /** Tap intents raised by the rail buttons. */
    public interface Listener {
        void onPanelToggled();

        void onVirtualControllerToggled(boolean visible);

        void onTouchModeToggled(boolean trackpad);

        void onKeyboardModeCycled();
    }

    private static final float ACTIVE_ALPHA = 1.0f;
    private static final float IDLE_ALPHA = 0.55f;

    private static final int IDLE_BACKGROUND = 0x59000000;
    private static final int ACTIVE_BACKGROUND = 0xA6FFB300;
    private static final int ANDROID_IME_BACKGROUND = 0xA61E88E5;

    private final Listener listener;

    private final ImageButton panelButton;
    private final ImageButton controllerButton;
    private final ImageButton touchButton;
    private final ImageButton keyboardButton;

    private boolean controllerOn;
    private boolean touchTrackpad;
    private int keyboardMode = KEYBOARD_OFF;

    public QuickToggleRail(Context context, int buttonSize, int topMargin, Listener listener) {
        super(context);
        this.listener = listener;

        setOrientation(VERTICAL);
        setBackgroundColor(0x33000000);
        setClickable(false);
        setFocusable(false);
        setFocusableInTouchMode(false);

        panelButton = addButton(buttonSize, R.drawable.ic_settings, R.string.qc_button_desc,
                new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        QuickToggleRail.this.listener.onPanelToggled();
                    }
                });

        controllerButton = addButton(buttonSize, R.drawable.qc_ic_controller,
                R.string.qc_rail_controller_desc, new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        QuickToggleRail.this.listener.onVirtualControllerToggled(!controllerOn);
                    }
                });

        touchButton = addButton(buttonSize, R.drawable.qc_ic_touch, R.string.qc_touch_trackpad,
                new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        QuickToggleRail.this.listener.onTouchModeToggled(!touchTrackpad);
                    }
                });

        keyboardButton = addButton(buttonSize, R.drawable.qc_ic_keyboard,
                R.string.qc_rail_keyboard_desc, new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        QuickToggleRail.this.listener.onKeyboardModeCycled();
                    }
                });
    }

    public ImageButton getPanelButton() {
        return panelButton;
    }

    public void setVirtualControllerOn(boolean on) {
        controllerOn = on;
        applyState(controllerButton, on, on ? R.string.qc_rail_controller_on : R.string.qc_rail_controller_off);
    }

    public void setTouchTrackpad(boolean trackpad) {
        touchTrackpad = trackpad;
        touchButton.setImageResource(trackpad ? R.drawable.qc_ic_mouse : R.drawable.qc_ic_touch);
        applyState(touchButton, true, trackpad ? R.string.qc_rail_touch_trackpad : R.string.qc_rail_touch_direct);
    }

    public void setKeyboardMode(int mode) {
        keyboardMode = mode;

        int background = IDLE_BACKGROUND;
        int description;
        if (mode == KEYBOARD_BUILT_IN) {
            background = ACTIVE_BACKGROUND;
            description = R.string.qc_rail_keyboard_builtin;
        }
        else if (mode == KEYBOARD_ANDROID) {
            background = ANDROID_IME_BACKGROUND;
            description = R.string.qc_rail_keyboard_android;
        }
        else {
            description = R.string.qc_rail_keyboard_off;
        }

        keyboardButton.setBackgroundColor(background);
        keyboardButton.setAlpha(mode == KEYBOARD_OFF ? IDLE_ALPHA : ACTIVE_ALPHA);
        keyboardButton.setContentDescription(getResources().getString(description));
    }

    /** Hides/shows the whole rail (used when entering or leaving PiP). */
    public void setRailVisible(boolean visible) {
        setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    public int getKeyboardMode() {
        return keyboardMode;
    }

    private ImageButton addButton(int size, int iconRes, int descRes, OnClickListener listener) {
        ImageButton button = new ImageButton(getContext());
        button.setImageResource(iconRes);
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setPadding(dp(5), dp(5), dp(5), dp(5));
        button.setBackgroundColor(IDLE_BACKGROUND);
        button.setAlpha(IDLE_ALPHA);
        button.setFocusable(false);
        button.setFocusableInTouchMode(false);
        button.setContentDescription(getResources().getString(descRes));
        button.setOnClickListener(listener);

        LayoutParams params = new LayoutParams(size, size);
        addView(button, params);
        return button;
    }

    /** Marks a mode button as active (bright) and refreshes its description. */
    private void applyState(ImageButton button, boolean active, int descRes) {
        button.setBackgroundColor(active ? ACTIVE_BACKGROUND : IDLE_BACKGROUND);
        button.setAlpha(active ? ACTIVE_ALPHA : IDLE_ALPHA);
        button.setContentDescription(getResources().getString(descRes));
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** Convenience for building the rail's frame layout params. */
    public static FrameLayout.LayoutParams layoutParams(int topMargin, int leftMargin) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.leftMargin = leftMargin;
        params.topMargin = topMargin;
        return params;
    }
}
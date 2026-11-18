package com.limelight.quickconfig;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.limelight.R;
import com.limelight.preferences.PreferenceConfiguration;

/**
 * In-stream quick configuration overlay: a left-edge rail of one-tap toggles plus the
 * collapsible settings panel they belong to.
 *
 * The whole UI is built in code (no layout XML) and all of its state is session-only:
 * nothing written here is persisted to SharedPreferences. Communication with the host
 * activity happens through the {@link Host} callback interface, which keeps the number
 * of hooks required in {@code Game} to a minimum.
 *
 * <p>The rail ({@link QuickToggleRail}) always shows the four things that used to require
 * opening the panel first: the panel itself, the virtual gamepad, the touch mode and the
 * keyboard. Each rail button is a plain toggle with no hidden state, and mirrors whatever
 * the matching panel switch shows.
 */
public class QuickConfigPanel extends FrameLayout
        implements View.OnClickListener, CompoundButton.OnCheckedChangeListener,
        QuickToggleRail.Listener {

    /** Callbacks implemented by the host activity. */
    public interface Host {
        void setVirtualControllerVisible(boolean visible);
        void setTouchscreenTrackpad(boolean enabled);
        void setReadOnly(boolean readOnly);
        void sendUtf8Text(String text);
        void sendKeyEvent(int keyCode, boolean down);
    }

    private final Activity activity;
    private final PreferenceConfiguration prefConfig;
    private final Host host;
    private final PinchZoomHelper zoomHelper;

    private final QuickToggleRail rail;
    private final ImageButton toggleButton;
    private final LinearLayout panel;
    private final RemoteKeyboardOverlay keyboard;
    private final AndroidImeInput androidIme;

    private final Switch switchVirtualController;
    private final Switch switchTouchTrackpad;
    private final Switch switchReadOnly;
    private final Switch switchPhoneInput;
    private final Switch switchAndroidInput;
    private final Switch switchClipboardSync;
    private final Button zoomResetButton;

    private boolean readOnly;
    private boolean overlaysVisible = true;
    private boolean clipboardSyncEnabled;

    /** One of {@link QuickToggleRail#KEYBOARD_*} shared by the rail and the panel switches. */
    private int keyboardMode = QuickToggleRail.KEYBOARD_OFF;

    /** Guards against programmatic switch updates re-entering the host callbacks. */
    private boolean updatingUi;

    private ClipboardManager clipboardManager;
    private ClipboardManager.OnPrimaryClipChangedListener clipboardListener;
    private String lastClipboardText;

    public QuickConfigPanel(Activity activity, FrameLayout root,
                            PreferenceConfiguration prefConfig, PinchZoomHelper zoomHelper,
                            Host host) {
        super(activity);

        this.activity = activity;
        this.prefConfig = prefConfig;
        this.zoomHelper = zoomHelper;
        this.host = host;

        // A non-clickable container lets touches outside of the widgets fall through
        // to the background touch view / stream view beneath us.
        setClickable(false);
        setFocusable(false);
        setFocusableInTouchMode(false);

        int buttonSize = (int) (getResources().getDisplayMetrics().heightPixels * 0.06f);
        int margin = dp(10);

        // The rail is pinned to the left edge and always visible; the panel opens beside it.
        rail = new QuickToggleRail(activity, buttonSize, margin, this);
        FrameLayout.LayoutParams railParams = QuickToggleRail.layoutParams(margin, margin);
        addView(rail, railParams);

        toggleButton = rail.getPanelButton();
        rail.setVirtualControllerOn(prefConfig.onscreenController);
        rail.setTouchTrackpad(prefConfig.touchscreenTrackpad);

        panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xCC000000);
        panel.setPadding(dp(12), dp(10), dp(12), dp(10));
        panel.setFocusable(false);
        panel.setVisibility(View.GONE);

        TextView title = new TextView(activity);
        title.setText(R.string.qc_title);
        title.setTextSize(15);
        title.setTextColor(0xFFFFFFFF);
        title.setPadding(0, 0, 0, dp(6));
        panel.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        switchVirtualController = addSwitchRow(R.string.qc_virtual_controller,
                R.string.qc_virtual_controller_summary, prefConfig.onscreenController);
        switchTouchTrackpad = addSwitchRow(R.string.qc_touch_trackpad,
                R.string.qc_touch_trackpad_summary, prefConfig.touchscreenTrackpad);
        switchReadOnly = addSwitchRow(R.string.qc_read_only,
                R.string.qc_read_only_summary, false);
        switchAndroidInput = addSwitchRow(R.string.qc_android_input,
                R.string.qc_android_input_summary, false);
        switchPhoneInput = addSwitchRow(R.string.qc_phone_input,
                R.string.qc_phone_input_summary, false);
        switchClipboardSync = addSwitchRow(R.string.qc_clipboard_sync,
                R.string.qc_clipboard_sync_summary, false);

        LinearLayout buttonRow = new LinearLayout(activity);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setPadding(0, dp(6), 0, 0);

        zoomResetButton = new Button(activity);
        zoomResetButton.setText(R.string.qc_zoom_reset);
        zoomResetButton.setFocusable(false);
        zoomResetButton.setOnClickListener(this);
        buttonRow.addView(zoomResetButton, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button macro = new Button(activity);
        macro.setText(R.string.qc_macro);
        macro.setFocusable(false);
        macro.setOnClickListener(this);
        buttonRow.addView(macro, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        panel.addView(buttonRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                Math.min(getResources().getDisplayMetrics().widthPixels / 2, dp(400)),
                FrameLayout.LayoutParams.WRAP_CONTENT);
        panelParams.gravity = Gravity.TOP | Gravity.START;
        panelParams.leftMargin = margin * 2 + buttonSize;
        panelParams.topMargin = margin;
        addView(panel, panelParams);

        // The phone input keyboard. It is a direct child of this view (not of the collapsible
        // panel) so it can be shown on its own, pinned to the bottom edge of the screen.
        keyboard = new RemoteKeyboardOverlay(activity, new RemoteKeyboardOverlay.KeySender() {
            @Override
            public void sendKeyEvent(int keyCode, boolean down) {
                host.sendKeyEvent(keyCode, down);
            }
        });
        keyboard.setVisibility(View.GONE);

        FrameLayout.LayoutParams keyboardParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        keyboardParams.gravity = Gravity.BOTTOM;
        addView(keyboard, keyboardParams);

        // The Android IME bridge, used instead of the drawn keyboard when the user only wants to
        // type plain text.
        androidIme = new AndroidImeInput(activity, new AndroidImeInput.Sink() {
            @Override
            public void sendUtf8Text(String text) {
                if (!readOnly) {
                    host.sendUtf8Text(text);
                }
            }

            @Override
            public void sendKeyEvent(int keyCode, boolean down) {
                host.sendKeyEvent(keyCode, down);
            }
        });

        // Listeners are registered last so that the initial setChecked() calls above
        // don't fire any host callbacks.
        switchVirtualController.setOnCheckedChangeListener(this);
        switchTouchTrackpad.setOnCheckedChangeListener(this);
        switchReadOnly.setOnCheckedChangeListener(this);
        switchAndroidInput.setOnCheckedChangeListener(this);
        switchPhoneInput.setOnCheckedChangeListener(this);
        switchClipboardSync.setOnCheckedChangeListener(this);

        root.addView(this, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    /**
     * Called from the activity's key handlers.
     *
     * @return true if the event was consumed locally and must not be sent to the host.
     */
    public boolean handleKeyEvent(KeyEvent event, boolean down) {
        // Read-only mode blocks all local input from reaching the host. The built-in keyboard
        // is not affected here: it dispatches through the activity's own key handlers, which
        // is exactly where this read-only check is applied. The Android IME bridge filters
        // text on the same flag before forwarding it.
        return readOnly;
    }

    /** Hides/shows the overlay (used when entering or leaving PiP). */
    public void setOverlaysVisible(boolean visible) {
        overlaysVisible = visible;
        rail.setRailVisible(visible);
        if (!visible) {
            panel.setVisibility(View.GONE);
        }
        androidIme.setPanelVisible(visible);
        updateKeyboardVisibility();
    }

    /**
     * The keyboard and the settings panel both want the same corner of the screen, and the
     * keyboard would otherwise cover the very switch that turns it off. So they are shown one
     * at a time: the keyboard appears only while the panel is closed.
     */
    private void updateKeyboardVisibility() {
        boolean show = overlaysVisible && keyboardMode == QuickToggleRail.KEYBOARD_BUILT_IN &&
                panel.getVisibility() != View.VISIBLE;
        keyboard.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) {
            keyboard.resetShift();
        }
    }

    /** Ensures the panel stays above the on-screen controller, which is added lazily. */
    public void bringToFrontAfterOsc() {
        bringToFront();
    }

    public void destroy() {
        setClipboardSyncEnabled(false);
        setKeyboardMode(QuickToggleRail.KEYBOARD_OFF);
        androidIme.release();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);

        // Recomputed here rather than in the constructor: the stream is created before the
        // landscape lock takes effect, so widthPixels would still be the portrait value.
        int panelWidth = Math.min(width / 2, dp(400));
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) panel.getLayoutParams();
        if (params != null && params.width != panelWidth) {
            params.width = panelWidth;
            panel.setLayoutParams(params);
        }
    }

    @Override
    public void onClick(View view) {
        if (view == toggleButton) {
            onPanelToggled();
        }
        else if (view == zoomResetButton) {
            zoomHelper.reset();
        }
        else {
            Toast.makeText(activity, R.string.qc_macro_unimplemented, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onPanelToggled() {
        panel.setVisibility(panel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        updateKeyboardVisibility();
    }

    @Override
    public void onVirtualControllerToggled(boolean visible) {
        updateUiState(switchVirtualController, visible);
        rail.setVirtualControllerOn(visible);
        host.setVirtualControllerVisible(visible);
        Toast.makeText(activity,
                visible ? R.string.qc_toast_controller_on : R.string.qc_toast_controller_off,
                Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onTouchModeToggled(boolean trackpad) {
        updateUiState(switchTouchTrackpad, trackpad);
        rail.setTouchTrackpad(trackpad);
        host.setTouchscreenTrackpad(trackpad);
        Toast.makeText(activity,
                trackpad ? R.string.qc_toast_touch_trackpad : R.string.qc_toast_touch_direct,
                Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onKeyboardModeCycled() {
        // Android IME first, because plain text entry (a notepad, a search box) is what the
        // keyboard button is mostly needed for; the drawn keyboard stays one tap away.
        int next;
        switch (keyboardMode) {
            case QuickToggleRail.KEYBOARD_OFF:
                next = QuickToggleRail.KEYBOARD_ANDROID;
                break;
            case QuickToggleRail.KEYBOARD_ANDROID:
                next = QuickToggleRail.KEYBOARD_BUILT_IN;
                break;
            default:
                next = QuickToggleRail.KEYBOARD_OFF;
                break;
        }
        setKeyboardMode(next);
    }

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        if (updatingUi) {
            return;
        }

        if (buttonView == switchVirtualController) {
            onVirtualControllerToggled(isChecked);
        }
        else if (buttonView == switchTouchTrackpad) {
            onTouchModeToggled(isChecked);
        }
        else if (buttonView == switchReadOnly) {
            readOnly = isChecked;
            host.setReadOnly(isChecked);
        }
        else if (buttonView == switchAndroidInput) {
            setKeyboardMode(isChecked ? QuickToggleRail.KEYBOARD_ANDROID : QuickToggleRail.KEYBOARD_OFF);
        }
        else if (buttonView == switchPhoneInput) {
            setKeyboardMode(isChecked ? QuickToggleRail.KEYBOARD_BUILT_IN : QuickToggleRail.KEYBOARD_OFF);
        }
        else if (buttonView == switchClipboardSync) {
            setClipboardSyncEnabled(isChecked);
        }
    }

    /**
     * Single entry point for the three keyboard states: off, the Android system IME, or the
     * drawn in-app keyboard. The two keyboards are mutually exclusive by construction.
     *
     * <p>The Android IME path sends the committed text as UTF-8, which supports every Android
     * keyboard including Chinese ones. The drawn keyboard sends real host key events instead,
     * so it remains necessary for places that only accept genuine key presses (PIN unlock).
     */
    private void setKeyboardMode(int mode) {
        if (keyboardMode == mode) {
            return;
        }
        keyboardMode = mode;

        if (mode != QuickToggleRail.KEYBOARD_OFF) {
            // Dismiss the panel so the keyboard is visible immediately, rather than only after
            // the user closes the panel by hand.
            panel.setVisibility(View.GONE);
        }

        updatingUi = true;
        switchAndroidInput.setChecked(mode == QuickToggleRail.KEYBOARD_ANDROID);
        switchPhoneInput.setChecked(mode == QuickToggleRail.KEYBOARD_BUILT_IN);
        updatingUi = false;

        rail.setKeyboardMode(mode);
        androidIme.setEnabled(mode == QuickToggleRail.KEYBOARD_ANDROID);
        updateKeyboardVisibility();

        if (mode == QuickToggleRail.KEYBOARD_ANDROID) {
            Toast.makeText(activity, R.string.qc_toast_keyboard_android, Toast.LENGTH_SHORT).show();
        }
        else if (mode == QuickToggleRail.KEYBOARD_BUILT_IN) {
            Toast.makeText(activity, R.string.qc_toast_keyboard_builtin, Toast.LENGTH_SHORT).show();
        }
    }

    private void updateUiState(Switch switchView, boolean checked) {
        if (switchView.isChecked() != checked) {
            updatingUi = true;
            switchView.setChecked(checked);
            updatingUi = false;
        }
    }

    private void setClipboardSyncEnabled(boolean enabled) {
        clipboardSyncEnabled = enabled;

        if (clipboardManager == null) {
            clipboardManager = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        }
        if (clipboardManager == null) {
            return;
        }

        if (enabled) {
            if (clipboardListener == null) {
                clipboardListener = new ClipboardManager.OnPrimaryClipChangedListener() {
                    @Override
                    public void onPrimaryClipChanged() {
                        sendClipboardText();
                    }
                };
            }
            clipboardManager.addPrimaryClipChangedListener(clipboardListener);
        }
        else if (clipboardListener != null) {
            clipboardManager.removePrimaryClipChangedListener(clipboardListener);
        }
    }

    private void sendClipboardText() {
        if (!clipboardSyncEnabled || readOnly || clipboardManager == null) {
            return;
        }

        ClipData clip = clipboardManager.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            return;
        }

        CharSequence text = clip.getItemAt(0).coerceToText(activity);
        if (text == null) {
            return;
        }

        String value = text.toString();
        if (value.isEmpty() || value.equals(lastClipboardText)) {
            return;
        }

        lastClipboardText = value;
        host.sendUtf8Text(value);
    }

    private Switch addSwitchRow(int labelRes, int summaryRes, boolean checked) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));

        Switch switchView = new Switch(activity);
        switchView.setText(labelRes);
        switchView.setTextSize(14);
        switchView.setChecked(checked);
        switchView.setFocusable(false);
        switchView.setFocusableInTouchMode(false);
        row.addView(switchView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView summary = new TextView(activity);
        summary.setText(summaryRes);
        summary.setTextSize(10);
        summary.setTextColor(0xFFB0B0B0);
        summary.setMaxLines(2);
        summary.setPadding(dp(8), 0, 0, 0);
        row.addView(summary, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        panel.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        return switchView;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
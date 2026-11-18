package com.limelight.quickconfig;

import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.Spanned;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;

import com.limelight.R;

/**
 * Bridges the Android system IME (软键盘/输入法) into the stream.
 *
 * <p>An invisible 1dp {@link EditText} is the IME's input target: everything the IME commits is
 * forwarded to the host as UTF-8 text, so plain text entry (a notepad, a search box, a URL field)
 * works with any Android keyboard including Chinese ones. This complements
 * {@link RemoteKeyboardOverlay}, which sends real host key events instead and therefore stays
 * necessary for PIN entry and other hosts that only accept genuine key presses.
 *
 * <p>The field itself is kept as small as possible and pinned to a corner so it never swallows a
 * touch meant for the stream or the virtual gamepad; the keyboard is shown programmatically.
 */
public class AndroidImeInput extends FrameLayout {

    /** Where the forwarded text and keys go. */
    public interface Sink {
        void sendUtf8Text(String text);

        void sendKeyEvent(int keyCode, boolean down);
    }

    /** The invisible field is emptied once it grows past this, to bound memory use. */
    private static final int CLEAR_AFTER_CHARS = 400;

    private final EditText editText;
    private final Sink sink;

    /** Last text we have already forwarded, used to diff insertions from deletions. */
    private String lastText = "";
    /** Snapshot taken when an IME composition starts, so pinyin isn't forwarded. */
    private String composingBase = "";
    private boolean composing;
    private boolean suppressWatcher;
    private boolean enabled;

    public AndroidImeInput(Context context, Sink sink) {
        super(context);
        this.sink = sink;

        editText = new EditText(context);
        editText.setBackgroundColor(0x00000000);
        editText.setTextColor(0x00000000);
        editText.setTextSize(1f);
        editText.setCursorVisible(false);
        editText.setSingleLine(false);
        editText.setInputType(InputType.TYPE_CLASS_TEXT);
        // Without this the IME may go fullscreen in landscape and cover everything.
        editText.setImeOptions(EditorInfo.IME_FLAG_NO_FULLSCREEN);
        editText.setContentDescription(getResources().getString(R.string.qc_android_input_desc));
        editText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                forwardTextChange(s);
            }
        });
        editText.setOnKeyListener(new OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_DOWN) {
                    return false;
                }
                // Backspace with an already empty field and Enter never reach the host otherwise.
                if (keyCode == KeyEvent.KEYCODE_DEL && editText.getText().length() == 0) {
                    sendKeyOnce(KeyEvent.KEYCODE_DEL);
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_ENTER) {
                    sendKeyOnce(KeyEvent.KEYCODE_ENTER);
                    return true;
                }
                return false;
            }
        });

        LayoutParams params = new LayoutParams(1, 1);
        params.gravity = Gravity.TOP | Gravity.END;
        addView(editText, params);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Turns the Android IME on or off. */
    public void setEnabled(boolean enable) {
        if (enabled == enable) {
            if (enable) {
                showIme();
            }
            return;
        }

        enabled = enable;

        if (enable) {
            showIme();
        }
        else {
            hideIme();
            // Release the focus so hardware/Bluetooth keys go back to the stream view.
            editText.clearFocus();
            clearField();
        }
    }

    /** PiP hides the overlays; the IME must follow so it doesn't float over the small window. */
    public void setPanelVisible(boolean visible) {
        if (!visible) {
            hideIme();
        }
        else if (enabled) {
            showIme();
        }
    }

    public void release() {
        hideIme();
        editText.clearFocus();
        clearField();
        enabled = false;
    }

    private void showIme() {
        editText.requestFocus();

        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void hideIme() {
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(editText.getWindowToken(), 0);
        }
    }

    private void sendKeyOnce(int keyCode) {
        sink.sendKeyEvent(keyCode, true);
        sink.sendKeyEvent(keyCode, false);
    }

    /**
     * Forwards the difference between the previously forwarded text and the new one: inserted
     * characters go out as UTF-8 text, removed ones as real Backspace key presses.
     */
    private void forwardTextChange(Editable editable) {
        if (suppressWatcher || !enabled) {
            return;
        }

        String now = editable.toString();

        // While an IME is composing (pinyin, kana, ...) the field holds candidates, not the final
        // text. Wait until the composition ends, otherwise the raw pinyin would be typed out.
        if (editable.getSpanStart(Spanned.SPAN_COMPOSING) >= 0) {
            if (!composing) {
                composingBase = lastText;
                composing = true;
            }
            lastText = now;
            return;
        }

        String base = composing ? composingBase : lastText;
        composing = false;
        composingBase = "";
        lastText = now;

        if (base.equals(now)) {
            return;
        }

        int max = Math.min(base.length(), now.length());
        int prefix = 0;
        while (prefix < max && base.charAt(prefix) == now.charAt(prefix)) {
            prefix++;
        }

        int suffix = 0;
        while (suffix < max - prefix &&
                base.charAt(base.length() - 1 - suffix) == now.charAt(now.length() - 1 - suffix)) {
            suffix++;
        }

        String removed = base.substring(prefix, base.length() - suffix);
        String inserted = now.substring(prefix, now.length() - suffix);

        // Count code points so a surrogate pair (emoji) costs one Backspace, as on a real keyboard.
        int deletions = removed.codePointCount(0, removed.length());
        for (int i = 0; i < deletions; i++) {
            sendKeyOnce(KeyEvent.KEYCODE_DEL);
        }

        if (!inserted.isEmpty()) {
            sink.sendUtf8Text(inserted);
        }

        if (now.length() >= CLEAR_AFTER_CHARS) {
            clearField();
        }
    }

    private void clearField() {
        suppressWatcher = true;
        editText.setText("");
        suppressWatcher = false;

        composing = false;
        composingBase = "";
        lastText = "";
    }
}
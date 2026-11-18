package com.limelight.quickconfig;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * A semi-transparent, in-app keyboard that turns taps into real host key events.
 *
 * <p>Why not the system IME: a soft keyboard resizes/shifts the stream view, so the remote
 * desktop is pushed out of sight, and it commits text through an InputConnection instead of
 * key events. Some host interactions (most notably typing a PIN on the Windows lock screen)
 * only react to genuine key events. This overlay never resizes the stream — it is drawn on
 * top of it — and every key goes through the normal Moonlight key pipeline as a real
 * {@code KEY_DOWN}/{@code KEY_UP} pair, so modifiers (Shift) behave exactly like a hardware
 * keyboard.
 */
public class RemoteKeyboardOverlay extends LinearLayout {

    /** Sends a key press to the host. */
    public interface KeySender {
        void sendKeyEvent(int keyCode, boolean down);
    }

    private static final int ROW_HEIGHT_DP = 46;
    private static final int KEY_TEXT_SIZE = 16;

    // Deliberately translucent so the remote picture stays visible underneath.
    private static final int CONTAINER_COLOR = 0x66000000;
    private static final int KEY_COLOR = 0x40FFFFFF;
    private static final int KEY_ACTIVE_COLOR = 0x99FFC107;

    /** A single key: its label(s), the key code it sends and whether Shift affects it. */
    private static final class Key {
        final TextView view;
        final String normalLabel;
        final String shiftedLabel;
        final int keyCode;
        final boolean shiftable;

        Key(TextView view, String normalLabel, String shiftedLabel, int keyCode, boolean shiftable) {
            this.view = view;
            this.normalLabel = normalLabel;
            this.shiftedLabel = shiftedLabel;
            this.keyCode = keyCode;
            this.shiftable = shiftable;
        }
    }

    private final KeySender sender;
    private final List<Key> keys = new ArrayList<>();
    private TextView shiftKeyView;

    private boolean shiftActive;

    public RemoteKeyboardOverlay(Context context, KeySender sender) {
        super(context);
        this.sender = sender;

        setOrientation(VERTICAL);
        setBackgroundColor(CONTAINER_COLOR);
        setPadding(dp(3), dp(3), dp(3), dp(3));
        // Consume taps that land between keys so they don't fall through to the stream.
        setClickable(true);
        setFocusable(false);

        addRow(buildDigitRow());
        addRow(buildLetterRow("qwertyuiop"));

        LinearLayout row3 = newRow();
        addLetters(row3, "asdfghjkl");
        addKey(row3, "-", "_", KeyEvent.KEYCODE_MINUS, true, 1);
        addRow(row3);

        LinearLayout row4 = newRow();
        addShiftKey(row4);
        addLetters(row4, "zxcvbnm");
        addKey(row4, ".", ">", KeyEvent.KEYCODE_PERIOD, true, 1);
        addKey(row4, "/", "?", KeyEvent.KEYCODE_SLASH, true, 1);
        addKey(row4, "\u232B", null, KeyEvent.KEYCODE_DEL, false, 1);
        addRow(row4);

        LinearLayout row5 = newRow();
        addKey(row5, "Esc", null, KeyEvent.KEYCODE_ESCAPE, false, 1);
        addKey(row5, "Tab", null, KeyEvent.KEYCODE_TAB, false, 1);
        addKey(row5, "Space", null, KeyEvent.KEYCODE_SPACE, false, 3);
        addKey(row5, "Enter", null, KeyEvent.KEYCODE_ENTER, false, 1);
        addRow(row5);
    }

    /** Clears the latch on Shift (used when the overlay is hidden). */
    public void resetShift() {
        setShift(false);
    }

    private LinearLayout buildDigitRow() {
        LinearLayout row = newRow();
        String digits = "1234567890";
        for (int i = 0; i < digits.length(); i++) {
            int digit = digits.charAt(i) - '0';
            addKey(row, String.valueOf(digit), null, KeyEvent.KEYCODE_0 + digit, false, 1);
        }
        return row;
    }

    private LinearLayout buildLetterRow(String letters) {
        LinearLayout row = newRow();
        addLetters(row, letters);
        return row;
    }

    private void addLetters(LinearLayout row, String letters) {
        for (int i = 0; i < letters.length(); i++) {
            char c = letters.charAt(i);
            String lower = String.valueOf(c);
            addKey(row, lower, lower.toUpperCase(), KeyEvent.KEYCODE_A + (c - 'a'), true, 1);
        }
    }

    private void addShiftKey(LinearLayout row) {
        TextView view = createKey("Shift", 2);
        view.setBackgroundColor(KEY_COLOR);
        shiftKeyView = view;
        view.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                setShift(!shiftActive);
            }
        });
        row.addView(view);
    }

    private void addKey(LinearLayout row, String label, String shiftedLabel, final int keyCode,
                        boolean shiftable, int weight) {
        TextView view = createKey(label, weight);
        final Key key = new Key(view, label, shiftable ? shiftedLabel : label, keyCode, shiftable);
        keys.add(key);
        view.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean shifted = shiftActive && key.shiftable;
                if (shifted) {
                    sender.sendKeyEvent(KeyEvent.KEYCODE_SHIFT_LEFT, true);
                }
                sender.sendKeyEvent(key.keyCode, true);
                sender.sendKeyEvent(key.keyCode, false);
                if (shifted) {
                    sender.sendKeyEvent(KeyEvent.KEYCODE_SHIFT_LEFT, false);
                    // A single Shift press only affects one key, like a real keyboard would
                    // after a one-off capital letter.
                    setShift(false);
                }
            }
        });
        row.addView(view);
    }

    private void setShift(boolean active) {
        shiftActive = active;
        if (shiftKeyView != null) {
            shiftKeyView.setBackgroundColor(active ? KEY_ACTIVE_COLOR : KEY_COLOR);
        }
        for (Key key : keys) {
            if (key.shiftable) {
                key.view.setText(active ? key.shiftedLabel : key.normalLabel);
            }
        }
    }

    private LinearLayout newRow() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        return row;
    }

    private void addRow(LinearLayout row) {
        addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, dp(ROW_HEIGHT_DP)));
    }

    private TextView createKey(String label, int weight) {
        TextView view = new TextView(getContext());
        view.setText(label);
        view.setTextColor(Color.WHITE);
        view.setTextSize(KEY_TEXT_SIZE);
        view.setGravity(Gravity.CENTER);
        view.setBackgroundColor(KEY_COLOR);
        view.setClickable(true);
        view.setFocusable(false);

        LayoutParams params = new LayoutParams(0, LayoutParams.MATCH_PARENT, weight);
        params.setMargins(dp(2), dp(2), dp(2), dp(2));
        view.setLayoutParams(params);
        return view;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
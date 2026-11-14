package org.fe57.atomspectra;

import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

import androidx.annotation.NonNull;

public class KeyboardHelper {
    /**
     * Makes Enter/Done in every single-line EditText under {@code root} just hide the keyboard.
     * Without an editor-action listener, TextView.onKeyUp runs focusSearch(FOCUS_DOWN) on Enter and throws
     * IllegalStateException if the found view (CheckBox, Spinner, Button) can't take focus in touch mode.
     * Replaces any listener already set on those fields.
     */
    public static void consumeEnterIn(@NonNull View root) {
        if (root instanceof EditText) {
            EditText edit = (EditText) root;
            if ((edit.getInputType() & InputType.TYPE_TEXT_FLAG_MULTI_LINE) == 0) {
                edit.setOnEditorActionListener((v, actionId, event) -> {
                    hide(v);
                    return true;
                });
            }
        } else if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                consumeEnterIn(group.getChildAt(i));
            }
        }
    }

    private static void hide(@NonNull View view) {
        InputMethodManager imm = (InputMethodManager) view.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }
}

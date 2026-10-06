package org.fe57.atomspectra;

import android.app.AlertDialog;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class DialogHelper {
    public static void showActionConfirmationDialog(@NonNull Context context, String message, Runnable action) {
        showActionConfirmationDialog(context, message,
                context.getString(R.string.dialog_continue_button),
                context.getString(R.string.dialog_cancel_button),
                action);
    }

    public static void showActionConfirmationDialog(@NonNull Context context, String message,
                                                    String positiveLabel, String negativeLabel, Runnable action) {
        final AlertDialog.Builder alert = new AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.dialog_confirm_title))
                .setMessage(message)
                .setPositiveButton(positiveLabel, (dialog, whichButton) -> {
                    action.run();
                })
                .setNegativeButton(negativeLabel, (dialog, whichButton) -> {});
        alert.show();
    }

    public static class StackedAction {
        final String label;
        @Nullable
        final Runnable onClick;

        public StackedAction(@NonNull String label, @Nullable Runnable onClick) {
            this.label = label;
            this.onClick = onClick;
        }
    }

    public static AlertDialog showStackedActions(@NonNull Context context, @NonNull String title,
                                                 @NonNull String message, boolean cancelable,
                                                 @NonNull StackedAction... actions) {
        LinearLayout column = (LinearLayout) LayoutInflater.from(context)
                .inflate(R.layout.dialog_stacked_actions, null);
        final AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(title)
                .setMessage(message)
                .setView(column)
                .setCancelable(cancelable)
                .create();
        int minHeight = Math.round(48f * context.getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        for (StackedAction action : actions) {
            Button button = new Button(context, null, android.R.attr.borderlessButtonStyle);
            button.setText(action.label);
            button.setMinHeight(minHeight);
            button.setOnClickListener(v -> {
                dialog.dismiss();
                if (action.onClick != null) action.onClick.run();
            });
            column.addView(button, params);
        }
        dialog.show();
        return dialog;
    }
}

package org.fe57.atomspectra;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

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
                .setNegativeButton(negativeLabel, (dialog, whichButton) -> {
                });
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
        Context dialogContext = dialog.getContext();
        float density = dialogContext.getResources().getDisplayMetrics().density;
        int minHeight = Math.round(48f * density);
        TypedArray colors = dialogContext.obtainStyledAttributes(new int[]{
                android.R.attr.textColorPrimary, android.R.attr.colorControlHighlight
        });
        int foreground = colors.getColor(0, Color.WHITE);
        int highlight = colors.getColor(1, ColorUtils.setAlphaComponent(foreground, 31));
        colors.recycle();
        for (StackedAction action : actions) {
            Button button = new Button(dialogContext, null, android.R.attr.borderlessButtonStyle);
            button.setText(action.label);
            button.setMinHeight(minHeight);
            GradientDrawable background = new GradientDrawable();
            background.setCornerRadius(6f * density);
            background.setColor(ColorUtils.setAlphaComponent(foreground, 15));
            background.setStroke(Math.max(1, Math.round(density)),
                    ColorUtils.setAlphaComponent(foreground, 61));
            GradientDrawable mask = new GradientDrawable();
            mask.setCornerRadius(6f * density);
            mask.setColor(Color.WHITE);
            button.setBackground(new RippleDrawable(ColorStateList.valueOf(highlight), background, mask));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (column.getChildCount() > 0) params.topMargin = Math.round(8f * density);
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

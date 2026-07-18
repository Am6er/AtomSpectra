package org.fe57.atomspectra;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.NonNull;

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
}

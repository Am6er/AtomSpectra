package org.fe57.atomspectra;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.NonNull;

public class DialogHelper {
    public static void showActionConfirmationDialog(@NonNull Context context, String message, Runnable action) {
        final AlertDialog.Builder alert = new AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.dialog_confirm_title))
                .setMessage(message)
                .setPositiveButton(R.string.dialog_continue_button, (dialog, whichButton) -> {
                    action.run();
                })
                .setNegativeButton(R.string.dialog_cancel_button, (dialog, whichButton) -> {});
        alert.show();
    }
}

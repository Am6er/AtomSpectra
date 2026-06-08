package org.fe57.atomspectra;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.NonNull;

public class ToastHelper {
    public static void showToast(@NonNull Context context, int text) {
        showToast(context, context.getString(text));
    }

    public static void showToast(@NonNull Context context, String text) {
        showToastInMainLooper(context.getApplicationContext(), text);
    }

    private static void showToastInMainLooper(@NonNull Context appContext, String text) {
        AtomSpectraLog.addMessage(appContext, text);
        new Handler(Looper.getMainLooper()).post(() -> {
            Toast.makeText(appContext, text, Toast.LENGTH_SHORT).show();
        });
    }
}

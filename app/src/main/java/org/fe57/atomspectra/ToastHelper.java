package org.fe57.atomspectra;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.NonNull;

public class ToastHelper {
    public static void showActionAndLog(@NonNull Context context, String text) {
        AtomSpectraLog.action(context, text);
        showToast(context, text);
    }

    public static void showErrorAndLog(@NonNull Context context, String text) {
        AtomSpectraLog.error(context, text);
        showToast(context, text);
    }

    public static void showErrorAndLog(@NonNull Context context, int resource) {
        showErrorAndLog(context, context.getString(resource));
    }

    public static void showWarningAndLog(@NonNull Context context, String text) {
        AtomSpectraLog.warning(context, text);
        showToast(context, text);
    }

    public static void showWarningAndLog(@NonNull Context context, int resource) {
        showWarningAndLog(context, context.getString(resource));
    }

    public static void showToast(@NonNull Context context, String text) {
        showToast(context, text, Toast.LENGTH_SHORT);
    }

    public static void showToast(@NonNull Context context, String text, int duration) {
        Context appContext = context.getApplicationContext();
        new Handler(Looper.getMainLooper()).post(() -> {
            Toast.makeText(appContext, text, duration).show();
        });
    }
}

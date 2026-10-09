package org.fe57.atomspectra;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.NonNull;

public class ToastHelper {
    public static void showActionAndLog(@NonNull Context context, String tag, String text) {
        AtomSpectraLog.action(context, tag, text);
        show(context, text);
    }

    public static void showErrorAndLog(@NonNull Context context, String tag, String text) {
        AtomSpectraLog.error(context, tag, text);
        show(context, text);
    }

    public static void showErrorAndLog(@NonNull Context context, String tag, int resource) {
        showErrorAndLog(context, tag, context.getString(resource));
    }

    public static void showWarningAndLog(@NonNull Context context, String tag, String text) {
        AtomSpectraLog.warning(context, tag, text);
        show(context, text);
    }

    public static void showWarningAndLog(@NonNull Context context, String tag, int resource) {
        showWarningAndLog(context, tag, context.getString(resource));
    }

    public static void showToastAndLog(@NonNull Context context, String tag, String text) {
        AtomSpectraLog.event(context, tag, text);
        show(context, text);
    }

    public static void showToastOnly(@NonNull Context context, String text) {
        show(context, text);
    }

    private static void show(@NonNull Context context, String text) {
        Context appContext = context.getApplicationContext();
        new Handler(Looper.getMainLooper()).post(() -> {
            Toast.makeText(appContext, text, Toast.LENGTH_SHORT).show();
        });
    }
}

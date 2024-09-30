package org.fe57.atomspectra.data;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.net.Uri;
import android.os.Environment;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;

/**
 * Created by S. Epiphanov.
 */
public class TopExceptionHandler implements Thread.UncaughtExceptionHandler {
    private final Thread.UncaughtExceptionHandler defaultUEH;
    private final Activity app;

    public TopExceptionHandler(Activity app) {
        defaultUEH = Thread.getDefaultUncaughtExceptionHandler();
        this.app = app;
    }

    @SuppressLint("SetWorldReadable")
    public void uncaughtException(Thread t, Throwable e) {
        StackTraceElement[] arr = e.getStackTrace();
        StringBuilder report = new StringBuilder(e + "\n\n");
        report.append("--------- Stack trace ---------\n\n");
        for (StackTraceElement stackTraceElement : arr) {
            report.append("    ").append(stackTraceElement.toString()).append("\n");
        }
        report.append("-------------------------------\n\n");

        // If the exception was thrown in a background thread inside
        // AsyncTask, then the actual exception can be found with getCause

        report.append("--------- Cause ---------\n\n");
        Throwable cause = e.getCause();
        if (cause != null) {
            report.append(cause).append("\n\n");
            arr = cause.getStackTrace();
            for (StackTraceElement stackTraceElement : arr) {
                report.append("    ").append(stackTraceElement.toString()).append("\n");
            }
        }
        report.append("-------------------------------\n\n");

        OutputStream docStream = null;
        try {
            File f = new File(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "stack.trace");
            docStream = app.getContentResolver().openOutputStream(Uri.fromFile(f), "w");
            f.setReadable(true, false);
            f.setWritable(true, true);
        } catch (Exception ignored) {
            //
        }

        if (docStream != null) {
            try {
                BufferedWriter trace = new BufferedWriter(new OutputStreamWriter(docStream));
                trace.append(report.toString());
                trace.close();
            } catch (IOException ioe) {
                // ...
            }
        }

        defaultUEH.uncaughtException(t, e);
    }
}

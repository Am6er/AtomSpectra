package org.fe57.atomspectra;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.telephony.AccessNetworkConstants;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

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

        Date now = new Date();
        File downloads = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (downloads != null) {
            trySaveFile(report.toString(), downloads.toString(), "stacktrace-" + formatDate(now) + ".txt");
            trySaveFile(AtomSpectraLog.getText(), downloads.toString(), "log-" + formatDate(now) + ".txt");
        }

        defaultUEH.uncaughtException(t, e);
    }

    private void trySaveFile(String report, String dir, String filename) {
        OutputStream docStream = null;
        try {
            File f = new File(dir, filename);
            docStream = app.getContentResolver().openOutputStream(Uri.fromFile(f), "w");
            f.setReadable(true, false);
            f.setWritable(true, true);
        } catch (Exception ignored) {
            //
        }

        if (docStream != null) {
            try {
                BufferedWriter trace = new BufferedWriter(new OutputStreamWriter(docStream));
                trace.append(report);
                trace.close();
            } catch (IOException ioe) {
                // ...
            }
        }
    }

    private static String formatDate(Date date) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.getDefault());
        sdf.setTimeZone(TimeZone.getDefault());

        return sdf.format(date);
    }
}

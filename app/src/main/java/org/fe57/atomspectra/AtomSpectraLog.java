package org.fe57.atomspectra;

import android.annotation.SuppressLint;
import android.app.ActionBar;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.WindowManager;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;


import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class AtomSpectraLog extends Activity {
    private static final int REQUEST_SAVE_LOG = 1;
    private static final String LOG_FILE_NAME = "AtomSpectra-log.txt";
    private static final Object logSync = new Object();
    private static final LinkedList<Entry> log = new LinkedList<>();
    private static final LinkedList<Entry> details = new LinkedList<>();
    private static final int MAX_MESSAGES = 1000;
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int MAX_ENTRY_BYTES = 16 * 1024;
    private static int logBytes;
    private static int detailBytes;
    private static long sequence;
    private static long logEvicted;
    private static long detailsEvicted;
    private boolean active = false;
    private static long lastNotifyTime = 0;
    private static final long NOTIFY_INTERVAL_MS = 250;
    private static final Handler notifyHandler = new Handler(Looper.getMainLooper());
    private static boolean notifyPending = false;

    public enum Type { ACTION, EVENT, DETAIL }
    public enum Severity { INFO, WARNING, ERROR }

    private static final class Entry {
        final long sequence;
        final long timestamp;
        final Type type;
        final Severity severity;
        final String text;
        final int bytes;

        Entry(long sequence, Type type, Severity severity, String text) {
            this.sequence = sequence;
            this.timestamp = System.currentTimeMillis();
            this.type = type;
            this.severity = severity;
            this.text = text;
            this.bytes = text.getBytes(StandardCharsets.UTF_8).length;
        }
    }

    private static volatile boolean diagnosticsLoaded;
    private static volatile boolean diagnosticsEnabled;

    public static boolean isDiagnosticsEnabled(Context context) {
        if (!diagnosticsLoaded) {
            if (context == null) return false;
            diagnosticsEnabled = PrefHelper.isLogDiagnosticsEnabled(context);
            diagnosticsLoaded = true;
        }
        return diagnosticsEnabled;
    }

    public static void setDiagnosticsEnabled(Context context, boolean enabled) {
        PrefHelper.setLogDiagnosticsEnabled(context, enabled);
        diagnosticsEnabled = enabled;
        diagnosticsLoaded = true;
    }

    public static void action(Context context, String message) {
        add(context, Type.ACTION, Severity.INFO, null, message);
    }

    public static void event(Context context, String message) {
        add(context, Type.EVENT, Severity.INFO, null, message);
    }

    public static void warning(Context context, String message) {
        add(context, Type.EVENT, Severity.WARNING, null, message);
    }

    public static void warning(Context context, String tag, String message) {
        add(context, Type.EVENT, Severity.WARNING, tag, message);
    }

    public static void error(Context context, String message) {
        add(context, Type.EVENT, Severity.ERROR, null, message);
    }

    public static void error(Context context, String tag, String message) {
        add(context, Type.EVENT, Severity.ERROR, tag, message);
    }

    public static void error(Context context, String operation, Throwable error) {
        error(context, operation + ": " + error.getClass().getSimpleName()
                + (error.getMessage() == null ? "" : " - " + error.getMessage())
                + "\n" + android.util.Log.getStackTraceString(error).trim());
    }

    /**
     * A file name that is safe to log: the display name only, never the directory.
     */
    public static String fileName(Context context, android.net.Uri uri) {
        try (android.database.Cursor cursor = context.getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0);
        } catch (Exception ignored) {
        }
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) return new java.io.File(uri.getPath()).getName();
        return "selected file";
    }

    public static void detail(Context context, String tag, String message) {
        add(context, Type.DETAIL, Severity.INFO, tag, message);
    }

    public static void add(Context context, Type type, Severity severity, String tag, String message) {
        if (type == Type.DETAIL && !isDiagnosticsEnabled(context)) return;
        String text = boundedText(tag, message);
        synchronized (logSync) {
            Entry entry = new Entry(sequence++, type, severity, text);
            boolean diagnostic = type == Type.DETAIL;
            LinkedList<Entry> target = diagnostic ? details : log;
            int bytes = (diagnostic ? detailBytes : logBytes) + entry.bytes;
            target.add(entry);
            while (target.size() > MAX_MESSAGES || bytes > MAX_BYTES) {
                bytes -= target.removeFirst().bytes;
                if (diagnostic) detailsEvicted++;
                else logEvicted++;
            }
            if (diagnostic) detailBytes = bytes;
            else logBytes = bytes;
        }
        notifyLogUpdated(context);
    }

    private static String boundedText(String tag, String message) {
        String marker = "\n[truncated]";
        StringBuilder result = new StringBuilder();
        int bytes = 0;
        String[] parts = tag == null || tag.isEmpty()
                ? new String[]{message == null ? "" : message}
                : new String[]{tag, ": ", message == null ? "" : message};
        for (String part : parts) {
            for (int offset = 0; offset < part.length();) {
                int codePoint = part.codePointAt(offset);
                int width = codePoint <= 0x7f ? 1 : codePoint <= 0x7ff ? 2 : codePoint <= 0xffff ? 3 : 4;
                if (bytes + width > MAX_ENTRY_BYTES - marker.length()) {
                    return result.append(marker).toString();
                }
                result.appendCodePoint(codePoint);
                bytes += width;
                offset += Character.charCount(codePoint);
            }
        }
        return result.toString();
    }

    public static void clear(Context context) {
        synchronized (logSync) {
            log.clear();
            details.clear();
            logBytes = 0;
            detailBytes = 0;
            logEvicted = 0;
            detailsEvicted = 0;
        }
        synchronized (AtomSpectraLog.class) {
            lastNotifyTime = 0; // force immediate notification after clear
        }
        notifyLogUpdated(context);
    }

    public static String getText() {
        return getText(true);
    }

    private static String getText(boolean includeDetails) {
        List<Entry> snapshot;
        long normalDropped;
        long diagnosticDropped;
        synchronized (logSync) {
            snapshot = new ArrayList<>(log);
            if (includeDetails) snapshot.addAll(details);
            normalDropped = logEvicted;
            diagnosticDropped = detailsEvicted;
        }
        Collections.sort(snapshot, (first, second) -> Long.compare(first.sequence, second.sequence));
        StringBuilder stringBuilder = new StringBuilder();
        if (normalDropped > 0 || (includeDetails && diagnosticDropped > 0)) {
            stringBuilder.append("[Older entries evicted: actions/events=").append(normalDropped);
            if (includeDetails) stringBuilder.append(", details=").append(diagnosticDropped);
            stringBuilder.append("]\n\n");
        }
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
        dateFormat.setTimeZone(TimeZone.getDefault());
        for (Entry entry : snapshot) {
            stringBuilder.append(dateFormat.format(new Date(entry.timestamp)))
                    .append(" [").append(entry.type).append('/').append(entry.severity)
                    .append("] ").append("\n").append(entry.text).append("\n\n");
        }

        return stringBuilder.toString();
    }

    private static synchronized void notifyLogUpdated(Context context) {
        if (context == null) return;
        Context appContext = context.getApplicationContext();
        long now = android.os.SystemClock.uptimeMillis();
        long elapsed = now - lastNotifyTime;
        if (elapsed >= NOTIFY_INTERVAL_MS) {
            notifyHandler.removeCallbacksAndMessages(null);
            notifyPending = false;
            lastNotifyTime = now;
            appContext.sendBroadcast(new Intent(Constants.ACTION.ACTION_LOG_UPDATED).setPackage(Constants.PACKAGE_NAME));
        } else if (!notifyPending) {
            notifyPending = true;
            notifyHandler.postDelayed(() -> {
                synchronized (AtomSpectraLog.class) {
                    notifyPending = false;
                    lastNotifyTime = android.os.SystemClock.uptimeMillis();
                }
                appContext.sendBroadcast(new Intent(Constants.ACTION.ACTION_LOG_UPDATED).setPackage(Constants.PACKAGE_NAME));
            }, NOTIFY_INTERVAL_MS - elapsed);
        }
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        String lang = PrefHelper.getLocale(newBase);
        super.attachBaseContext(LocaleContextWrapper.wrap(newBase, lang));
    }

    @SuppressLint("SetTextI18n")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_atom_spectra_log);
        ActionBar bar = getActionBar();
        if (bar != null) {
            bar.setDisplayShowHomeEnabled(true);
            bar.setDisplayHomeAsUpEnabled(true);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Constants.ACTION.ACTION_CLOSE_LOG);
        intentFilter.addAction(Constants.ACTION.ACTION_LOG_UPDATED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(mDataUpdateReceiver, intentFilter, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(mDataUpdateReceiver, intentFilter);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.atom_spectra_log, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.findItem(R.id.action_log_capture).setChecked(isDiagnosticsEnabled(this));
        menu.findItem(R.id.action_log_details).setChecked(PrefHelper.isLogDetailsVisible(this));
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        if (item.getItemId() == R.id.action_log_capture) {
            boolean enabled = !isDiagnosticsEnabled(this);
            setDiagnosticsEnabled(this, enabled);
            item.setChecked(enabled);
            action(this, getString(enabled ? R.string.log_capture_enabled : R.string.log_capture_disabled));
            return true;
        }
        if (item.getItemId() == R.id.action_log_details) {
            boolean enabled = !item.isChecked();
            PrefHelper.setLogDetailsVisible(this, enabled);
            item.setChecked(enabled);
            renderLog();
            return true;
        }
        if (item.getItemId() == R.id.action_log_save) {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TITLE, LOG_FILE_NAME);
            try {
                startActivityForResult(intent, REQUEST_SAVE_LOG);
            } catch (Exception exception) {
                error(this, "Cannot open log destination picker", exception);
                Toast.makeText(this, getString(R.string.hist_save_error, LOG_FILE_NAME), Toast.LENGTH_SHORT).show();
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void startActivityForResult(Intent intent, int requestCode, Bundle options) {
        if (requestCode >= 0) AtomSpectraApplication.externalUiStarted(this);
        try {
            super.startActivityForResult(intent, requestCode, options);
        } catch (RuntimeException error) {
            AtomSpectraApplication.externalUiFinished(this);
            throw error;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        AtomSpectraApplication.externalUiFinished(this);
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_SAVE_LOG || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        String snapshot = getText();
        try (OutputStream output = getContentResolver().openOutputStream(data.getData(), "wt")) {
            if (output == null) {
                throw new IOException("Unable to open log destination");
            }
            try (OutputStreamWriter writer = new OutputStreamWriter(output, StandardCharsets.UTF_8)) {
                writer.write(snapshot);
            }
            Toast.makeText(this, getString(R.string.hist_save_success, LOG_FILE_NAME), Toast.LENGTH_SHORT).show();
        } catch (Exception exception) {
            error(this, "Cannot save log", exception);
            Toast.makeText(this, getString(R.string.hist_save_error, LOG_FILE_NAME), Toast.LENGTH_SHORT).show();
        }
    }

    private final BroadcastReceiver mDataUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (Constants.ACTION.ACTION_CLOSE_LOG.equals(action)) {
                finish();
            }
            if (Constants.ACTION.ACTION_LOG_UPDATED.equals(action)) {
                renderLog();
            }

        }
    };

    @Override
    protected void onStart() {
        super.onStart();
        active = true;
        renderLog();
    }

    @Override
    protected void onStop() {
        super.onStop();
        active = false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(mDataUpdateReceiver);
    }

    private void renderLog() {
        if (active) {
            TextView logView = findViewById(R.id.logText);
            if (logView != null) {
                String logText = getText(PrefHelper.isLogDetailsVisible(this));
                if (logText.isEmpty()) {
                    logText = getString(R.string.log_no_records);

                }

                logView.setText(logText);
            }
            ScrollView scrollView = findViewById(R.id.logTextScroll);
            if (scrollView != null) {
                scrollView.post(new Runnable() {
                    @Override
                    public void run() {
                        scrollView.fullScroll(ScrollView.FOCUS_DOWN);
                    }
                });
            }
        }
    }

}

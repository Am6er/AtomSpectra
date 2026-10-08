package org.fe57.atomspectra;

import android.annotation.SuppressLint;
import android.app.ActionBar;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Annotation;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannedString;
import android.text.style.ForegroundColorSpan;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.Locale;
import java.util.TimeZone;

public class AtomSpectraLog extends Activity {
    private static final int REQUEST_SAVE_LOG = 1;
    private static final String LOG_FILE_NAME = "AtomSpectra-log.txt";
    private static final Object logSync = new Object();
    private static final LinkedList<String> log = new LinkedList<>();
    private static final int MAX_MESSAGES = 1000;
    private static boolean active = false;
    private static long lastNotifyTime = 0;
    private static final long NOTIFY_INTERVAL_MS = 250;
    private static final Handler notifyHandler = new Handler(Looper.getMainLooper());
    private static boolean notifyPending = false;

    public static void addMessage(Context context, String message) {
        addMessage(context, null, message);
    }

    /**
     * Prefixes the message with a source tag, e.g. the device/input source it originated from.
     */
    public static void addMessage(Context context, String tag, String message) {
        String formatted = (tag == null || tag.isEmpty()) ? message : (tag + ": " + message);
        synchronized (logSync) {
            if (log.size() >= MAX_MESSAGES) {
                log.remove();
            }

            Date now = new Date();
            log.add(String.format("%s: %s", formatDate(now), formatted));
        }

        notifyLogUpdated(context);
    }

    public static void clear(Context context) {
        synchronized (logSync) {
            log.clear();
        }
        synchronized (AtomSpectraLog.class) {
            lastNotifyTime = 0; // force immediate notification after clear
        }
        notifyLogUpdated(context);
    }

    public static String getText() {
        StringBuilder stringBuilder = new StringBuilder();
        synchronized (logSync) {
            for (String message : log) {
                stringBuilder.append(message);
                stringBuilder.append("\n\n");
            }
        }

        return stringBuilder.toString();
    }

    private static synchronized void notifyLogUpdated(Context context) {
        if (context == null) return;
        long now = System.currentTimeMillis();
        long elapsed = now - lastNotifyTime;
        if (elapsed >= NOTIFY_INTERVAL_MS) {
            notifyPending = false;
            lastNotifyTime = now;
            context.sendBroadcast(new Intent(Constants.ACTION.ACTION_LOG_UPDATED).setPackage(Constants.PACKAGE_NAME));
        } else if (!notifyPending) {
            notifyPending = true;
            notifyHandler.postDelayed(() -> {
                notifyPending = false;
                lastNotifyTime = System.currentTimeMillis();
                context.sendBroadcast(new Intent(Constants.ACTION.ACTION_LOG_UPDATED).setPackage(Constants.PACKAGE_NAME));
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
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
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
                Toast.makeText(this, getString(R.string.hist_save_error, LOG_FILE_NAME), Toast.LENGTH_SHORT).show();
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_SAVE_LOG || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        try (OutputStream output = getContentResolver().openOutputStream(data.getData(), "wt")) {
            if (output == null) {
                throw new IOException("Unable to open log destination");
            }
            try (OutputStreamWriter writer = new OutputStreamWriter(output, StandardCharsets.UTF_8)) {
                writer.write(getText());
            }
            Toast.makeText(this, getString(R.string.hist_save_success, LOG_FILE_NAME), Toast.LENGTH_SHORT).show();
        } catch (Exception exception) {
            AtomSpectraLog.addMessage(this, android.util.Log.getStackTraceString(exception));
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
                String logText = getText();
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

    private static String formatDate(Date date) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
        sdf.setTimeZone(TimeZone.getDefault());

        return sdf.format(date);
    }
}

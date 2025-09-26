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
import android.text.Annotation;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannedString;
import android.text.style.ForegroundColorSpan;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.Locale;
import java.util.TimeZone;

public class AtomSpectraLog extends Activity {
    private static final Integer logSync = 1;
    private static final LinkedList<String> log = new LinkedList<>();
    private static final int MAX_MESSAGES = 1000;
    private static boolean active = false;

    public static void addMessage(Context context, String message) {
        synchronized(logSync) {
            if (log.size() >= MAX_MESSAGES) {
                log.remove();
            }

            Date now = new Date();
            log.add(String.format("%s: %s", formatDate(now), message));
        }

        notifyLogUpdated(context);
    }

    public static void clear(Context context) {
        synchronized (logSync) {
            log.clear();
        }

        notifyLogUpdated(context);
    }

    private static void notifyLogUpdated(Context context) {
      if (context != null) {
        context.sendBroadcast(new Intent(Constants.ACTION.ACTION_LOG_UPDATED).setPackage(Constants.PACKAGE_NAME));
      }
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        SharedPreferences sharedPreferences = newBase.getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        int r = sharedPreferences.getInt(Constants.CONFIG.CONF_LOCALE_ID, 0);
        r = r < Constants.LOCALES_ID.length ? r : (Constants.LOCALES_ID.length - 1);
        String lang = Locale.getDefault().getLanguage();
        if (r > 0) {
            lang = Constants.LOCALES_ID[r];
        }
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
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
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
                StringBuilder stringBuilder = new StringBuilder();
                synchronized (logSync) {
                    for (String message : log) {
                        stringBuilder.append(message);
                        stringBuilder.append("\n");
                    }
                }

                String logText = stringBuilder.toString();
                if (logText.isEmpty()) {
                    logText = "No records yet";

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

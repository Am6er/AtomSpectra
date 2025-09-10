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
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import java.util.Locale;

public class AtomSpectraHelp extends Activity {

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
        setContentView(R.layout.activity_atom_spectra_help);
        ActionBar bar = getActionBar();
        if (bar != null) {
            bar.setDisplayShowHomeEnabled(true);
            bar.setDisplayHomeAsUpEnabled(true);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // different smartphones throw various errors while rendering help
        // next code is very basic attempt to simplify "remote debugging"
        renderSection(R.id.helpTextOverview, R.string.help_text_overview, 0);
        renderSection(R.id.helpTextPart1, R.string.help_text_part1, 1);
        renderSection(R.id.helpTextPart2, R.string.help_text_part2, 2);
        renderSection(R.id.helpTextPart3, R.string.help_text_part3, 3);
        renderSection(R.id.helpTextPart4, R.string.help_text_part4, 4);
        renderSection(R.id.helpTextPart5, R.string.help_text_part5, 5);
        renderSection(R.id.helpTextPart6, R.string.help_text_part6, 6);
        renderSection(R.id.helpTextPart7, R.string.help_text_part7, 7);
        renderSection(R.id.helpTextPart8, R.string.help_text_part8, 8);

        try {
            VersionInfo versionInfo = getVersionInfo(this);
            ((TextView)findViewById(R.id.helpTextVersion)).setText(getString(R.string.help_version, versionInfo.version, versionInfo.verCode));
        } catch (Exception e) {
            Toast.makeText(this, "Help rendering error for version section: " + e.getMessage(), Toast.LENGTH_LONG).show();
            // throw e;
        }
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Constants.ACTION.ACTION_CLOSE_HELP);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(mDataUpdateReceiver, intentFilter, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(mDataUpdateReceiver, intentFilter);
        }
    }

    public void renderSection(int viewId, int textId, int sectionIndex) {
        try {
            ((TextView) findViewById(viewId)).setText(prepareString(textId));
        } catch (Exception e) {
            Toast.makeText(this, "Help rendering error for section " + sectionIndex + ": " + e.getMessage(), Toast.LENGTH_LONG).show();
            // throw e;
        }
    }

    public static @NonNull VersionInfo getVersionInfo(Activity activity) {
        try {
            PackageInfo pInfo = activity.getApplicationContext().getPackageManager().getPackageInfo(activity.getPackageName(), 0);
            String version = pInfo.versionName;
            int verCode;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                verCode = (int) (pInfo.getLongVersionCode());
            } else {
                verCode = pInfo.versionCode;
            }
            return new VersionInfo(version, verCode);
        } catch (Exception e) {
            return new VersionInfo("", 0);
        }

    }

    public static class VersionInfo {
        public final String version;
        public final int verCode;

        public VersionInfo(String version, int verCode) {
            this.version = version;
            this.verCode = verCode;
        }
    }

    private SpannableString prepareString(@StringRes int id) {
        SpannedString text = (SpannedString) getText(id);
        Annotation[] annotations = text.getSpans(0, text.length(), Annotation.class);

// create a copy of the title text as a SpannableString.
// the constructor copies both the text and the spans. so we can add and remove spans
        SpannableString spannableString = new SpannableString(text);

// iterate through all the annotation spans
        for (Annotation annotation: annotations) {
            // look for the span with the key foreground
            if (annotation.getKey().equals("foreground")) {
                String fontColor = annotation.getValue();
                // check the value associated to the annotation key
                switch (fontColor) {
                    case "caption":
                        // set the span at the same indices as the annotation
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            spannableString.setSpan(new ForegroundColorSpan(getColor(R.color.colorCaption)),
                                    text.getSpanStart(annotation),
                                    text.getSpanEnd(annotation),
                                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                        } else {
                            spannableString.setSpan(new ForegroundColorSpan(0xFFFFFFFF),
                                    text.getSpanStart(annotation),
                                    text.getSpanEnd(annotation),
                                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                        }
                        break;
                    case "parameter":
                        // set the span at the same indices as the annotation
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            spannableString.setSpan(new ForegroundColorSpan(getColor(R.color.colorParameter)),
                                    text.getSpanStart(annotation),
                                    text.getSpanEnd(annotation),
                                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                        } else {
                            spannableString.setSpan(new ForegroundColorSpan(0xFFFFFFFF),
                                    text.getSpanStart(annotation),
                                    text.getSpanEnd(annotation),
                                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                        }
                        break;
                    case "highlight":
                        // set the span at the same indices as the annotation
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            spannableString.setSpan(new ForegroundColorSpan(getColor(R.color.colorHighlight)),
                                    text.getSpanStart(annotation),
                                    text.getSpanEnd(annotation),
                                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                        } else {
                            spannableString.setSpan(new ForegroundColorSpan(0xFFFFFFFF),
                                    text.getSpanStart(annotation),
                                    text.getSpanEnd(annotation),
                                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                        }
                        break;
                }
            }
        }

        return spannableString;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if(item.getItemId() == android.R.id.home){
//            onBackPressed();
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private final BroadcastReceiver mDataUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (Constants.ACTION.ACTION_CLOSE_HELP.equals(action)) {
                finish();
            }

        }

    };

    @Override
    protected void onStart() {
        super.onStart();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(mDataUpdateReceiver);
    }
}

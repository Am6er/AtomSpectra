package org.fe57.atomspectra;

import android.annotation.SuppressLint;
import android.app.ActionBar;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Bundle;
import android.text.Annotation;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.SpannedString;
import android.text.style.ForegroundColorSpan;
import android.view.MenuItem;
import android.view.WindowManager;
import android.widget.TextView;

import org.fe57.atomspectra.data.Constants;

/**
 * Created by S. Epiphanov.
 */
public class AtomSpectraHelp extends Activity {

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
        try {
            PackageInfo pInfo = getApplicationContext().getPackageManager().getPackageInfo(getPackageName(), 0);
            String version = pInfo.versionName;
            int verCode = pInfo.versionCode;
            SpannedString text = (SpannedString) getText(R.string.help_text);
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
            SpannableStringBuilder builder = new SpannableStringBuilder();
            builder.append(spannableString)
                    .append("\n\n\n")
                    .append(getString(R.string.help_version, version, verCode));
            ((TextView)findViewById(R.id.helpText)).setText(builder);
        } catch (Exception e) {
            //
        }
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Constants.ACTION.ACTION_CLOSE_HELP);
        registerReceiver(mDataUpdateReceiver, intentFilter);
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

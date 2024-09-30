package org.fe57.atomspectra;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.res.ResourcesCompat;

import java.io.File;
import java.util.ArrayList;
import java.util.Locale;

public class AtomSpectraSelect extends Activity implements OnClickListener {
    private String fileDir = null;
    public static final String CAPTION_INTENT = "Selection";
    public static final String DIRECTORY_INTENT = "Directory";
    public static final String FILTER_START = "Start with";
    public static final String FILTER_END = "End with";
    private int filesNum = 0;
    private int lastChosen = -1;
    private String start = "";
    private String end = "";
    ArrayList<String> files = null;

    @Override
    protected void attachBaseContext(Context newBase) {
        SharedPreferences sharedPreferences = newBase.getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        int r = sharedPreferences.getInt(Constants.CONFIG.CONF_LOCALE_ID, 0);
        r = r < Constants.LOCALES_ID.length ? r : (Constants.LOCALES_ID.length - 1);
        String lang = Locale.getDefault().getLanguage();
        if (r > 0) {
            lang = Constants.LOCALES_ID[r];
        }
        super.attachBaseContext(MyContextWrapper.wrap(newBase, lang));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_atom_spectra_select);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Intent intent = getIntent();
        String caption;
        if (intent != null) {
            caption = intent.getStringExtra(CAPTION_INTENT);
            if (caption == null) {
                caption = "File selection";
            }
            fileDir = intent.getStringExtra(DIRECTORY_INTENT);
            if (fileDir == null) {
                fileDir = Environment.getExternalStorageDirectory() + "/AtomSpectra";
            }
            start = intent.getStringExtra(FILTER_START);
            if (start == null) {
                start = "";
            }
            end = intent.getStringExtra(FILTER_END);
            if (end == null) {
                end = "";
            }
        } else {
            caption = "File selection";
            fileDir = Environment.getExternalStorageDirectory() + "/AtomSpectra";
        }
        ((TextView) findViewById(R.id.textCaption)).setText(caption);
        LinearLayout layout = findViewById(R.id.fileSelection);
        File folder = new File(fileDir);
        File[] folderList = folder.listFiles();
        filesNum = 0;
        if (folderList != null) {
            files = new ArrayList<>();
            int pos;
            String name;
            for (File next : folderList) {
                pos = files.size();
                name = next.getName();
                if (!name.startsWith(start) || !name.endsWith(end)) {
                    continue;
                }
                for (int i = 0; i < files.size(); i++) {
                    if (files.get(i).compareTo(name) < 0) {
                        pos = i;
                        break;
                    }
                }
                files.add(pos, name);
            }
            for (String next : files) {
                TextView v = new TextView(this);
                v.setText(next);
                v.setTextSize(20);
                v.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
                v.setOnClickListener(this);
                v.setPadding(0, 10, 0, 10);
                v.setId(Constants.GROUPS.FILE_SELECTION_GROUP + filesNum);
                v.setCompoundDrawablesRelativeWithIntrinsicBounds(ResourcesCompat.getDrawable(getResources(), R.drawable.menu_open, null), null, null, null);
                v.setGravity(Gravity.CENTER_VERTICAL);
                filesNum++;
                v.setTextColor(0xFFFFFFFF);
                v.setEnabled(true);
                v.setClickable(true);
                layout.addView(v);
            }
        }
    }

    @Override
    public void onClick(View v) {
        int num = v.getId();
        if (num >= Constants.GROUPS.FILE_SELECTION_GROUP && num < (Constants.GROUPS.FILE_SELECTION_GROUP + filesNum)) {
            if (lastChosen != -1) {
                ((TextView) findViewById(lastChosen + Constants.GROUPS.FILE_SELECTION_GROUP)).setTextColor(Color.WHITE);
            }
            ((TextView) v).setTextColor(0xFFFF9090);
            lastChosen = num - Constants.GROUPS.FILE_SELECTION_GROUP;
            findViewById(R.id.okFileButton).setEnabled(true);
        }
        if (num == R.id.okFileButton) {
            Intent out = new Intent();
            File file = new File(fileDir + "/" + files.get(lastChosen));
            out.setData(Uri.fromFile(file));
            setResult(RESULT_OK, out);
            finish();
        }
        if (num == R.id.cancelFileButton) {
            setResult(RESULT_CANCELED);
            finish();
        }
    }
}

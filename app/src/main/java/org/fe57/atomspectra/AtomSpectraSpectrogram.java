package org.fe57.atomspectra;

import android.annotation.SuppressLint;
import android.app.ActionBar;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;

import java.util.Locale;

public class AtomSpectraSpectrogram extends Activity {

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
        setContentView(R.layout.activity_atom_spectra_spectrogram);
        ActionBar bar = getActionBar();
        if (bar != null) {
            bar.setDisplayShowHomeEnabled(true);
            bar.setDisplayHomeAsUpEnabled(true);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);


        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Constants.ACTION.ACTION_CLOSE_SPECTROGRAM);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(mDataUpdateReceiver, intentFilter, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(mDataUpdateReceiver, intentFilter);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if(item.getItemId() == android.R.id.home){
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private final BroadcastReceiver mDataUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        if (Constants.ACTION.ACTION_CLOSE_SPECTROGRAM.equals(action)) {
            finish();
        }
        }

    };

    @Override
    protected void onStart() {
        super.onStart();

        AtomSpectraSpectrogramView spgView = findViewById(R.id.viewSpectrogram);
        spgView.renderSpectrogram(AtomSpectraSpectrogramData.instance.getSpectrogram());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(mDataUpdateReceiver);
    }
}

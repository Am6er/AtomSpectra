package org.fe57.atomspectra;

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
import android.widget.CheckBox;
import android.widget.TextView;

/**
 * Spectrogram recording configuration screen: daily file rollover and the delta duration.
 */
public class AtomSpectraSpectrogramSettings extends Activity {

    private SharedPreferences sp;
    private boolean active = false;
    private final SharedPreferences.OnSharedPreferenceChangeListener logPreferenceChange = (preferences, key) -> {
        if (!active || key == null) return;
        Object value = preferences.getAll().get(key);
        // strings may hold device names/identities, URLs or paths: log their key only
        boolean plain = value instanceof Boolean || value instanceof Number;
        AtomSpectraLog.action(this, LogTag.SETTINGS_ACT, "Preference changed: " + key + (plain ? " = " + value : ""));
    };

    @Override
    protected void attachBaseContext(Context newBase) {
        String lang = PrefHelper.getLocale(newBase);
        super.attachBaseContext(LocaleContextWrapper.wrap(newBase, lang));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_atom_spectra_spectrogram_settings);
        ActionBar bar = getActionBar();
        if (bar != null) {
            bar.setDisplayShowHomeEnabled(true);
            bar.setDisplayHomeAsUpEnabled(true);
        }

        sp = PrefHelper.getASSharedPreferences(this);

        setupSpgMidnightResetCheckbox();
        updateSpgDeltaDurationText();

        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Constants.ACTION.ACTION_CLOSE_SPECTROGRAM);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(mCloseReceiver, intentFilter, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(mCloseReceiver, intentFilter);
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

    @Override
    protected void onStart() {
        super.onStart();
        sp.registerOnSharedPreferenceChangeListener(logPreferenceChange);
    }

    @Override
    protected void onResume() {
        super.onResume();
        active = true;
    }

    @Override
    protected void onPause() {
        super.onPause();
        active = false;
    }

    @Override
    protected void onStop() {
        super.onStop();
        sp.unregisterOnSharedPreferenceChangeListener(logPreferenceChange);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(mCloseReceiver);
    }

    private final BroadcastReceiver mCloseReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Constants.ACTION.ACTION_CLOSE_SPECTROGRAM.equals(intent.getAction())) {
                finish();
            }
        }
    };

    // midnight reset
    private void setupSpgMidnightResetCheckbox() {
        CheckBox checkSpgMidnightReset = findViewById(R.id.spgMidnightReset);
        checkSpgMidnightReset.setChecked(sp.getBoolean(Constants.CONFIG.CONF_SPG_MIDNIGHT_RESET, Constants.SPG_MIDNIGHT_RESET_DEFAULT));
        checkSpgMidnightReset.setOnClickListener(v -> {
            saveBooleanPref(checkSpgMidnightReset.isChecked(), Constants.CONFIG.CONF_SPG_MIDNIGHT_RESET);
        });
    }

    // save delta every n sec
    private void updateSpgDeltaDurationText() {
        TextView spgDeltaDurationText = findViewById(R.id.spgDeltaDurationText);
        int value = sp.getInt(Constants.CONFIG.CONF_SPG_DELTA_DURATION, 0);
        if (value > 0) {
            spgDeltaDurationText.setText(getString(R.string.settings_spg_delta_duration, value));
        } else {
            spgDeltaDurationText.setText(getString(R.string.settings_spg_save_delta_off));
        }
    }

    public void onClick_spgDeltaDuration_minus(View v) {
        int val = sp.getInt(Constants.CONFIG.CONF_SPG_DELTA_DURATION, 0);
        if (val > Constants.SPG_DELTA_DURATION_MIN) {
            val -= Constants.SPG_DELTA_DURATION_MIN;
        } else {
            val = 0;
        }

        saveSpgDeltaDuration(val);
    }

    public void onClick_spgDeltaDuration_plus(View v) {
        int val = sp.getInt(Constants.CONFIG.CONF_SPG_DELTA_DURATION, 0);
        if (val <= (Constants.SPG_DELTA_DURATION_MAX - Constants.SPG_DELTA_DURATION_MIN)) {
            val += Constants.SPG_DELTA_DURATION_MIN;
        } else {
            val = Constants.SPG_DELTA_DURATION_MAX;
        }

        saveSpgDeltaDuration(val);
    }

    private void saveSpgDeltaDuration(int val) {
        saveIntPref(val, Constants.CONFIG.CONF_SPG_DELTA_DURATION);
        updateSpgDeltaDurationText();
    }

    private void saveBooleanPref(boolean value, String setting) {
        SharedPreferences.Editor prefEditor = sp.edit();
        prefEditor.putBoolean(setting, value);
        prefEditor.apply();
    }

    private void saveIntPref(int value, String setting) {
        SharedPreferences.Editor prefEditor = sp.edit();
        prefEditor.putInt(setting, value);
        prefEditor.apply();
    }
}

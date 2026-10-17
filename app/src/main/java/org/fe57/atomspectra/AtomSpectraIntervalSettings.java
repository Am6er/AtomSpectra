package org.fe57.atomspectra;

import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioDeviceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.text.method.NumberKeyListener;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.util.Locale;

/**
 * Interval search configuration screen: the energy range to watch and the sound alarm
 * (enable, output device, volume, detection level).
 * <p>
 * TODO: describe these preferences with a SettingsSchema (SOFTWARE scope) and render them with the rows of AtomSpectraDeviceSettings
 */
public class AtomSpectraIntervalSettings extends Activity {

    private SharedPreferences sp;
    private boolean active = false;
    private final SharedPreferences.OnSharedPreferenceChangeListener logPreferenceChange = (preferences, key) -> {
        if (!active || key == null) return;
        Object value = preferences.getAll().get(key);
        // strings may hold device names/identities, URLs or paths: log their key only
        boolean plain = value instanceof Boolean || value instanceof Number;
        AtomSpectraLog.action(this, LogTag.SETTINGS_ACT, "Preference changed: " + key + (plain ? " = " + value : ""));
    };
    private TextView outputDeviceText;
    private int outputDeviceID = -1;
    private String outputDeviceName = "(none)";

    @Override
    protected void attachBaseContext(Context newBase) {
        String lang = PrefHelper.getLocale(newBase);
        super.attachBaseContext(LocaleContextWrapper.wrap(newBase, lang));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_atom_spectra_interval_settings);
        ActionBar bar = getActionBar();
        if (bar != null) {
            bar.setDisplayShowHomeEnabled(true);
            bar.setDisplayHomeAsUpEnabled(true);
        }

        sp = PrefHelper.getASSharedPreferences(this);

        setupEnableSearchAlarmCheckbox();
        setupSearchAlarmOutputDeviceText();
        updateSearchEnergyRangeText();
        updateSearchAlarmVolumeText();
        updateSearchAlarmDetectionLevelText();

        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Constants.ACTION.ACTION_CLOSE_INTERVAL_SETTINGS);
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
            if (Constants.ACTION.ACTION_CLOSE_INTERVAL_SETTINGS.equals(intent.getAction())) {
                finish();
            }
        }
    };

    // alarm enable
    private void setupEnableSearchAlarmCheckbox() {
        CheckBox alarmEnabledCheckBox = findViewById(R.id.intervalSearchSoundEnabled);
        alarmEnabledCheckBox.setChecked(sp.getBoolean(Constants.CONFIG.CONF_OUTPUT_SOUND, false));
        alarmEnabledCheckBox.setEnabled(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M);
        alarmEnabledCheckBox.setOnClickListener(v -> {
            saveBooleanPref(alarmEnabledCheckBox.isChecked(), Constants.CONFIG.CONF_OUTPUT_SOUND);
            sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_MENU).setPackage(Constants.PACKAGE_NAME));
        });
    }

    // alarm output device
    private void setupSearchAlarmOutputDeviceText() {
        outputDeviceID = sp.getInt(Constants.CONFIG.CONF_OUTPUT_SOUND_DEVICE_ID, -1);
        outputDeviceName = sp.getString(Constants.CONFIG.CONF_OUTPUT_SOUND_DEVICE_NAME, "(none)");
        outputDeviceText = findViewById(R.id.intervalSearchSoundOutputDevice);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioDeviceInfo deviceOut;
            if (outputDeviceID == -1) {
                deviceOut = AtomSpectraService.getDeviceOutput(getApplicationContext(), -1, null, false);
            } else {
                deviceOut = AtomSpectraService.getDeviceOutput(getApplicationContext(), outputDeviceID, outputDeviceName, true);
            }
            int outputDeviceType;
            if (deviceOut == null) {
                outputDeviceType = AudioDeviceInfo.TYPE_UNKNOWN;
            } else {
                outputDeviceType = deviceOut.getType();
            }
            outputDeviceText.setText(String.format(Locale.US, "%s: %s", AtomSpectraService.audioDeviceNames[Constants.MinMax(outputDeviceType, 0, AtomSpectraService.audioDeviceNames.length - 1)], outputDeviceName));
        } else {
            outputDeviceText.setEnabled(false);
        }
    }

    public void onSelectSearchAlarmOutputDevice(View v) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioDeviceInfo deviceOut = AtomSpectraService.getDeviceOutput(this, outputDeviceID, outputDeviceName, false);
            int outputDeviceType;
            if (deviceOut != null) {
                outputDeviceID = deviceOut.getId();
                outputDeviceName = deviceOut.getProductName().toString();
                outputDeviceType = deviceOut.getType();
                SharedPreferences.Editor editor = sp.edit();
                editor.putInt(Constants.CONFIG.CONF_OUTPUT_SOUND_DEVICE_ID, outputDeviceID);
                editor.putString(Constants.CONFIG.CONF_OUTPUT_SOUND_DEVICE_NAME, outputDeviceName);
                editor.apply();
            } else {
                outputDeviceID = -1;
                outputDeviceName = "(none)";
                outputDeviceType = 0;
            }
            outputDeviceText.setText(String.format(Locale.US, "%s: %s", AtomSpectraService.audioDeviceNames[Constants.MinMax(outputDeviceType, 0, AtomSpectraService.audioDeviceNames.length - 1)], outputDeviceName));
        } else {
            outputDeviceText.setEnabled(false);
        }
    }

    // energy range
    private void updateSearchEnergyRangeText() {
        TextView energyRangeText = findViewById(R.id.intervalSearchEnergyRange);
        EnergyIntervalData.Snapshot interval = EnergyIntervalData.instance.get();
        if (!interval.full) {
            Calibration calibration = SpectrumData.instance.foreground.getSpectrumCalibration();
            energyRangeText.setText(getString(R.string.interval_search_energy_range_format, calibration.toEnergy(interval.leftChannel), calibration.toEnergy(interval.rightChannel)));
        } else
            energyRangeText.setText(getString(R.string.interval_search_energy_range_full));
    }

    public void onSelectIntervalClick(View energyRangeText) {
        final AlertDialog.Builder alert = new AlertDialog.Builder(this);

        alert.setTitle(getString(R.string.ask_select_interval_title));
        alert.setMessage(getString(R.string.ask_select_interval_text));

        final EditText input = new EditText(this);
        input.setKeyListener(new NumberKeyListener() {
            @NonNull
            @Override
            protected char[] getAcceptedChars() {
                return new char[]{'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '-', '.'};
            }

            @Override
            public int getInputType() {
                return InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_VARIATION_NORMAL;
            }
        });
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        alert.setView(input);

        alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
            String value = input.getText().toString();
            if ("-1".equals(value)) {
                EnergyIntervalData.instance.reset();
                AtomSpectraService.getIntervalSearchAlarmBaseline().reset();
                updateSearchEnergyRangeText();
            } else {
                if (value.contains("-")) {
                    String num1 = value.substring(0, value.indexOf("-"));
                    String num2 = value.substring(value.indexOf("-") + 1);
                    try {
                        double val1 = Double.parseDouble(num1);
                        int number1 = SpectrumData.instance.foreground.getSpectrumCalibration().toChannel(val1);
                        double val2 = Double.parseDouble(num2);
                        int number2 = SpectrumData.instance.foreground.getSpectrumCalibration().toChannel(val2);
                        if (number1 < 0 || number1 >= number2 || number2 >= SpectrumData.instance.getChannelCount() - 1)
                            return;
                        EnergyIntervalData.instance.setEnergy(val1, val2);
                        AtomSpectraService.getIntervalSearchAlarmBaseline().reset();
                        updateSearchEnergyRangeText();
                    } catch (Exception ignored) {

                    }
                }
            }
        });
        alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
        });
        closeKeyboardOnAlertDismiss(alert);
        alert.show();
    }

    // sound volume
    private void updateSearchAlarmVolumeText() {
        TextView alarmVolumeText = findViewById(R.id.intervalSearchAlarmVolumeText);
        alarmVolumeText.setText(getString(R.string.interval_search_sound_volume, sp.getInt(Constants.CONFIG.CONF_SEARCH_ALARM_VOLUME, Constants.ALARM_VOLUME_DEFAULT)));
    }

    public void onClick_intervalAlarmVolume_minus(View v) {
        int r = sp.getInt(Constants.CONFIG.CONF_SEARCH_ALARM_VOLUME, Constants.ALARM_VOLUME_DEFAULT);

        if (r < 10) {
            r = 10;
        }
        if (r > 100) {
            r = 100;
        }
        if (r > 10) {
            r -= 10;
        }

        saveSearchAlarmVolume(r);
    }

    public void onClick_intervalAlarmVolume_plus(View v) {
        int r = sp.getInt(Constants.CONFIG.CONF_SEARCH_ALARM_VOLUME, Constants.ALARM_VOLUME_DEFAULT);

        if (r < 10) {
            r = 10;
        }
        if (r > 100) {
            r = 100;
        }
        if (r < 100) {
            r += 10;
        }

        saveSearchAlarmVolume(r);
    }

    private void saveSearchAlarmVolume(int val) {
        saveIntPref(val, Constants.CONFIG.CONF_SEARCH_ALARM_VOLUME);
        updateSearchAlarmVolumeText();
    }

    // detection level
    private void updateSearchAlarmDetectionLevelText() {
        TextView detectionLevelText = findViewById(R.id.intervalSearchDetectionLevelText);
        detectionLevelText.setText(getString(R.string.interval_search_detection_level, sp.getInt(Constants.CONFIG.CONF_SEARCH_DETECTION_LEVEL, Constants.ALARM_DETECTION_LEVEL_DEFAULT)));
    }

    public void onClick_intervalDetectionLevel_minus(View v) {
        int r = sp.getInt(Constants.CONFIG.CONF_SEARCH_DETECTION_LEVEL, Constants.ALARM_DETECTION_LEVEL_DEFAULT);

        if (r < 3) {
            r = 3;
        }
        if (r > 9) {
            r = 9;
        }
        if (r > 3) {
            r -= 1;
        }

        saveSearchDetectionLevelVolume(r);
    }

    public void onClick_intervalDetectionLevel_plus(View v) {
        int r = sp.getInt(Constants.CONFIG.CONF_SEARCH_DETECTION_LEVEL, Constants.ALARM_DETECTION_LEVEL_DEFAULT);

        if (r < 3) {
            r = 3;
        }
        if (r > 9) {
            r = 9;
        }
        if (r < 9) {
            r += 1;
        }

        saveSearchDetectionLevelVolume(r);
    }

    private void saveSearchDetectionLevelVolume(int val) {
        saveIntPref(val, Constants.CONFIG.CONF_SEARCH_DETECTION_LEVEL);
        updateSearchAlarmDetectionLevelText();
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

    private void closeKeyboard() {
        new Handler().postDelayed(() -> {
            View view = getCurrentFocus();
            if (view == null) {
                view = new View(this.getApplicationContext());
            }

            InputMethodManager imm = (InputMethodManager) view.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }, 100);
    }

    private void closeKeyboardOnAlertDismiss(AlertDialog.Builder alertDialog) {
        alertDialog.setOnDismissListener(dialog -> closeKeyboard());
    }
}

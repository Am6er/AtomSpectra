package org.fe57.atomspectra;


import android.Manifest;
import android.annotation.SuppressLint;
import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.gesture.GestureOverlayView;
import android.gesture.GestureOverlayView.OnGestureListener;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.text.method.NumberKeyListener;
import android.util.Log;
import android.view.GestureDetector;
import android.view.GestureDetector.SimpleOnGestureListener;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.app.ActivityCompat.OnRequestPermissionsResultCallback;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.text.NumberFormat;
import java.util.Locale;

public class AtomSpectraSettings extends Activity  implements OnGestureListener, OnRequestPermissionsResultCallback {

    private final static String TAG = AtomSpectraSettings.class.getSimpleName();
    private final static int REQUEST_FINE_GPS = 501;

    public static boolean active = false;

    private AtomSpectraShapeView mAtomSpectraSignalView;

    private GestureDetector gestureDetector;
    private TextView countsTextField;
    private EditText SensG, SensGCompensated, BackgroundCount, slowSens, mediumSens, fastSens;
    private SharedPreferences sp;
    private TextView doseRateFreqLabel;
    private TextView workingDirText;
    private TextView outputDeviceText;
    private int outputDeviceID = -1;
    private String outputDeviceName = "(none)";
    private TextView inputDeviceText;
    private int inputDeviceID = -1;
    private String inputDeviceName = "(none)";
    private final int SELECT_DIR_CODE_SETTINGS = 100;

    private float zoom_factor = 1;
    private int usb_noise_value = 25;
    private boolean retrySend = true;
    private boolean from_search_view = false;

    private static final String SETTINGS_GET_INF_ID = "Inf get";
    private static final String SETTINGS_SET_NOISE_ID = "Noise set";

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_atom_spectra_settings);
        ActionBar bar = getActionBar();
        if (bar != null) {
            bar.setDisplayShowHomeEnabled(true);
            bar.setDisplayHomeAsUpEnabled(true);
        }

        sp = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);

        // --- audio processing
        mAtomSpectraSignalView = findViewById(R.id.signal_area);
        mAtomSpectraSignalView.setClickable(false);
        countsTextField = findViewById(R.id.countView);
        setupPileUpCheckbox();
        setupRawAudioSourceCheckbox();
        setupInvertAudioCheckbox();
        setupInputSoundCheckbox();
        updateMinFrontPointsText();
        updateMaxFrontPointsText();
        updateAdcMaxRoundingText();
        // --- end audio processing

        // --- spectrum
        updateReduceToText();
        updateNoiseDiscriminatorTextFromPrefs();
        updateCalibrationFactorText();
        updateSmoothText();
        updateCompressGraphText();
        // --- end spectrum

        // --- spectrum change
        updateDiffTimeText();
        // --- end spectrum change

        // --- spectrogram
        setupSpgMidnightResetCheckbox();
        updateSpgDeltaDurationText();
        // --- end spectrogram

        // --- dose rate
        setupDoseRateSection();
        // --- end dose rate

        // --- interval search section
        setupEnableSearchAlarmCheckbox();
        setupSearchAlarmOutputDeviceText();
        updateSearchEnergyRangeText();
        updateSearchAlarmVolumeText();
        updateSearchAlarmDetectionLevelText();
        // --- end interval search section

        // --- AtomSwift integration section
        setupAtomSwiftIntegrationCheckbox();
        updateAtomSwiftDRText();
        // --- end AtomSwift integration section

        // --- search isotopes section
        setupAutoSearchIsotopesCheckbox();
        // --- end search isotopes

        // --- files section
        updateWorkingDirText();
        setupEnableGPSCheckbox();
        updateSpectrumSaveChannelsText();
        updateSpectrumLoadChannelsText();
        updateSpectrumChannelCompressionText();
        updateFilenamePatternText();
        // --- end files section

        // --- misc section
        updateLocaleText();
        // --- end misc section

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mDataUpdateReceiver, makeAtomSpectraUpdateIntentFilter(), RECEIVER_NOT_EXPORTED);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(mDataUpdateReceiver, makeAtomSpectraUpdateIntentFilter(), 0);
        } else {
            registerReceiver(mDataUpdateReceiver, makeAtomSpectraUpdateIntentFilter());
        }

        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        initializeGestures();

        if (AtomSpectraService.getScaleFactor() <= Constants.SCALE_MAX)
            AtomSpectraService.saveScaleFactor();
        else if (AtomSpectraService.getScaleFactor() == Constants.SCALE_DOSE_MODE)
            from_search_view = true;
        AtomSpectraService.setScaleFactor(Constants.SCALE_OSCILLOSCOPE_MODE);

        sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_GRAPH).setPackage(Constants.PACKAGE_NAME));
        sendUsbInfoRequest();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        switch (requestCode) {
            case REQUEST_FINE_GPS:
                CheckBox v = findViewById(R.id.enableGPSCheckbox);
                if (grantResults.length > 1 && (grantResults[0] == PackageManager.PERMISSION_GRANTED || grantResults[1] == PackageManager.PERMISSION_GRANTED)) {
                    saveBooleanPref(true, Constants.CONFIG.CONF_ADD_GPS_TO_FILES);
                    v.setChecked(true);
                    sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_GPS).setPackage(Constants.PACKAGE_NAME));
                } else {
                    saveBooleanPref(false, Constants.CONFIG.CONF_ADD_GPS_TO_FILES);
                    Toast.makeText(this, getString(R.string.perm_no_gps), Toast.LENGTH_LONG).show();
                    v.setChecked(false);
                }
                sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_SETTINGS).setPackage(Constants.PACKAGE_NAME));
                break;
            default:
                super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            returnFromSettings();
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void sendUsbInfoRequest() {
        sendBroadcast(new Intent(Constants.ACTION.ACTION_SEND_USB_COMMAND) // Command will not be executed if USB-serial does not exist
                .putExtra(Constants.ACTION_PARAMETERS.USB_COMMAND_ID, SETTINGS_GET_INF_ID)
                .putExtra(Constants.ACTION_PARAMETERS.USB_COMMAND_DATA, "-inf")
                .setPackage(Constants.PACKAGE_NAME));
    }

    // --- audio processing section
    // pile up
    private void setupPileUpCheckbox() {
        CheckBox pileUpCheckBox = findViewById(R.id.CheckPileUp);
        pileUpCheckBox.setChecked(sp.getBoolean(Constants.CONFIG.CONF_PILE_UP, Constants.PILE_UP_DEFAULT));
        pileUpCheckBox.setOnClickListener(v -> {
            saveBooleanPref(pileUpCheckBox.isChecked(), Constants.CONFIG.CONF_PILE_UP);
            stopRecording();
        });
    }

    // raw audio
    private void setupRawAudioSourceCheckbox() {
        CheckBox rawAudio = findViewById(R.id.CheckRawAudio);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            AudioManager manager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (manager != null && manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == null) {
                rawAudio.setEnabled(false);
                rawAudio.setChecked(false);
                rawAudio.setVisibility(CheckBox.INVISIBLE);
                saveIntPref(AtomSpectraService.SET_AUDIO_VOICE, Constants.CONFIG.CONF_AUDIO_SOURCE);
            } else {
                rawAudio.setEnabled(true);
                rawAudio.setVisibility(CheckBox.VISIBLE);
                rawAudio.setChecked(sp.getInt(Constants.CONFIG.CONF_AUDIO_SOURCE, AtomSpectraService.SET_AUDIO_VOICE) == AtomSpectraService.SET_AUDIO_RAW);
            }
        } else {
            rawAudio.setEnabled(false);
            rawAudio.setChecked(false);
            rawAudio.setVisibility(CheckBox.INVISIBLE);
            saveIntPref(AtomSpectraService.SET_AUDIO_VOICE, Constants.CONFIG.CONF_AUDIO_SOURCE);
        }
    }

    public void onSelectRawAudioCheckboxClick(View v) {
        SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        AtomSpectraService.SetAudioSource = ((CheckBox) v).isChecked() ? AtomSpectraService.SET_AUDIO_RAW : AtomSpectraService.SET_AUDIO_VOICE;
        int newValue = ((CheckBox) v).isChecked() ? AtomSpectraService.SET_AUDIO_RAW : AtomSpectraService.SET_AUDIO_VOICE;
        saveIntPref(newValue, Constants.CONFIG.CONF_AUDIO_SOURCE);
    }

    // invert input
    private void setupInvertAudioCheckbox() {
        CheckBox checkInvert = findViewById(R.id.CheckInvert);
        checkInvert.setChecked(sp.getBoolean(Constants.CONFIG.CONF_INVERSION, Constants.INVERSE_DEFAULT));
        checkInvert.setOnClickListener(v -> {
            saveBooleanPref(checkInvert.isChecked(), Constants.CONFIG.CONF_INVERSION);
            stopRecording();
        });
    }

    // input sound
    private void setupInputSoundCheckbox() {
        inputDeviceText = findViewById(R.id.inputSoundText);
        inputDeviceID = sp.getInt(Constants.CONFIG.CONF_INPUT_SOUND_DEVICE_ID, -1);
        inputDeviceName = sp.getString(Constants.CONFIG.CONF_INPUT_SOUND_DEVICE_NAME, "(none)");
        updateInputDeviceText();
        CheckBox inputSoundCheckBox = findViewById(R.id.inputSoundCheckbox);
        inputSoundCheckBox.setChecked(sp.getBoolean(Constants.CONFIG.CONF_INPUT_SOUND, false) && !inputDeviceName.equals("(none)"));
        inputSoundCheckBox.setEnabled(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M);
        inputSoundCheckBox.setOnClickListener(v -> {
            SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
            boolean inputSound = ((CheckBox) v).isChecked();
            String inputName = settings.getString(Constants.CONFIG.CONF_INPUT_SOUND_DEVICE_NAME, "(none)");
            if (inputName == null)
                inputName = "(none)";
            if (inputName.equals("(none)")) {
                inputSound = false;
                ((CheckBox) v).setChecked(false);
            }

            saveBooleanPref(inputSound, Constants.CONFIG.CONF_INPUT_SOUND);
        });
    }

    private void updateInputDeviceText() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioDeviceInfo deviceIn;
            if (inputDeviceID == -1) {
                deviceIn = AtomSpectraService.getDeviceInput(this, -1, null, false);
            } else {
                deviceIn = AtomSpectraService.getDeviceInput(this, inputDeviceID, inputDeviceName, true);
            }
            int inputDeviceType;
            if (deviceIn == null) {
                inputDeviceType = AudioDeviceInfo.TYPE_UNKNOWN;
            } else {
                inputDeviceType = deviceIn.getType();
            }
            inputDeviceText.setText(String.format(Locale.US, "%s: %s", AtomSpectraService.audioDeviceNames[Constants.MinMax(inputDeviceType, 0, AtomSpectraService.audioDeviceNames.length - 1)], inputDeviceName));
        } else {
            inputDeviceText.setEnabled(false);
        }
    }

    public void onSelectInputSoundClick(View v) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioDeviceInfo deviceIn = AtomSpectraService.getDeviceInput(this, inputDeviceID, inputDeviceName, false);
            int inputDeviceType;
            if (deviceIn != null) {
                inputDeviceID = deviceIn.getId();
                inputDeviceName = deviceIn.getProductName().toString();
                inputDeviceType = deviceIn.getType();
                SharedPreferences.Editor editor = sp.edit();
                editor.putInt(Constants.CONFIG.CONF_INPUT_SOUND_DEVICE_ID, inputDeviceID);
                editor.putString(Constants.CONFIG.CONF_INPUT_SOUND_DEVICE_NAME, inputDeviceName);
                editor.apply();
            } else {
                inputDeviceID = -1;
                inputDeviceName = "(none)";
                inputDeviceType = 0;
            }
            inputDeviceText.setText(String.format(Locale.US, "%s: %s", AtomSpectraService.audioDeviceNames[Constants.MinMax(inputDeviceType, 0, AtomSpectraService.audioDeviceNames.length - 1)], inputDeviceName));
        } else {
            inputDeviceText.setEnabled(false);
        }
    }

    // min front points
    private void updateMinFrontPointsText() {
        TextView minFrontPointsTextField = findViewById(R.id.minFrontText);
        minFrontPointsTextField.setText(getString(R.string.min_front_points_format, sp.getInt(Constants.CONFIG.CONF_MIN_POINTS, Constants.MIN_FRONT_POINTS_DEFAULT)));
    }

    public void onClick_minFront_plus(View v) {
        SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        int r = settings.getInt(Constants.CONFIG.CONF_MIN_POINTS, Constants.MIN_FRONT_POINTS_DEFAULT);
        int rr = settings.getInt(Constants.CONFIG.CONF_MAX_POINTS, Constants.MAX_FRONT_POINTS_DEFAULT);
        if (r < rr - 1) {
            r++;
            if (!AtomSpectraService.getFreeze()) {
                sendBroadcast(new Intent(Constants.ACTION.ACTION_CLEAR_IMPULSE).setPackage(Constants.PACKAGE_NAME));
            }
        }

        saveMinFrontPoints(r);
    }

    public void onClick_minFront_minus(View v) {
        SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        int r = settings.getInt(Constants.CONFIG.CONF_MIN_POINTS, Constants.MIN_FRONT_POINTS_DEFAULT);
        if (r > 1) {
            r--;
            if (!AtomSpectraService.getFreeze()) {
                sendBroadcast(new Intent(Constants.ACTION.ACTION_CLEAR_IMPULSE).setPackage(Constants.PACKAGE_NAME));
            }
        }

        saveMinFrontPoints(r);
    }

    private void saveMinFrontPoints(int val) {
        AtomSpectraService.setScaleFactor(Constants.SCALE_AUDIO_REFERENCE_PULSE_MODE);
        saveIntPref(val, Constants.CONFIG.CONF_MIN_POINTS);
        updateMinFrontPointsText();
    }

    // max front points
    private void updateMaxFrontPointsText() {
        TextView maxFrontPointsTextField = findViewById(R.id.maxFrontText);
        maxFrontPointsTextField.setText(getString(R.string.max_front_points_format, sp.getInt(Constants.CONFIG.CONF_MAX_POINTS, Constants.MAX_FRONT_POINTS_DEFAULT)));
    }

    public void onClick_maxFront_plus(View v) {
        SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        int r = settings.getInt(Constants.CONFIG.CONF_MAX_POINTS, Constants.MAX_FRONT_POINTS_DEFAULT);
        if (r < 4096) {
            r++;
            if (!AtomSpectraService.getFreeze()) {
                sendBroadcast(new Intent(Constants.ACTION.ACTION_CLEAR_IMPULSE).setPackage(Constants.PACKAGE_NAME));
            }
        }

        saveMaxFrontPoints(r);
    }

    public void onClick_maxFront_minus(View v) {
        SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        int r = settings.getInt(Constants.CONFIG.CONF_MAX_POINTS, Constants.MAX_FRONT_POINTS_DEFAULT);
        int rr = settings.getInt(Constants.CONFIG.CONF_MIN_POINTS, Constants.MIN_FRONT_POINTS_DEFAULT);
        if (r > rr + 1) {
            r--;
            if (!AtomSpectraService.getFreeze()) {
                sendBroadcast(new Intent(Constants.ACTION.ACTION_CLEAR_IMPULSE).setPackage(Constants.PACKAGE_NAME));
            }
        }

        saveMaxFrontPoints(r);
    }

    private void saveMaxFrontPoints(int val) {
        AtomSpectraService.setScaleFactor(Constants.SCALE_AUDIO_REFERENCE_PULSE_MODE);
        saveIntPref(val, Constants.CONFIG.CONF_MAX_POINTS);
        updateMaxFrontPointsText();
    }

    // max adc rounding
    private void updateAdcMaxRoundingText() {
        TextView adcText = findViewById(R.id.ADCText);
        adcText.setText(getString(R.string.adc_rounded_format, Constants.MinMax(sp.getInt(Constants.CONFIG.CONF_ROUNDED, Constants.ADC_DEFAULT), Constants.ADC_MIN, Constants.ADC_MAX)));
    }

    public void onClick_adcRoundedTo_minus(View v) {
        SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        int r = Constants.MinMax(settings.getInt(Constants.CONFIG.CONF_ROUNDED, Constants.ADC_DEFAULT), Constants.ADC_MIN, Constants.ADC_MAX);

        if (r > Constants.ADC_MIN) {
            r--;
            if (!AtomSpectraService.getFreeze())
                stopRecording();
        }

        saveAdcRoundedTo(r);
    }

    public void onClick_adcRoundedTo_plus(View v) {
        SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        int r = Constants.MinMax(settings.getInt(Constants.CONFIG.CONF_ROUNDED, Constants.ADC_DEFAULT), Constants.ADC_MIN, Constants.ADC_MAX);

        if (r < Constants.ADC_MAX) {
            r++;
            if (!AtomSpectraService.getFreeze())
                stopRecording();
        }

        saveAdcRoundedTo(r);
    }

    private void saveAdcRoundedTo(int val) {
        saveIntPref(val, Constants.CONFIG.CONF_ROUNDED);
        updateAdcMaxRoundingText();
    }
    // --- end audio processing section

    // --- spectrum section
    // reduce display to (number of points to render on the screen)
    private void updateReduceToText() {
        TextView reduceToText = findViewById(R.id.reduceToChannelsText);
        reduceToText.setText(getString(R.string.reduced_to_format, sp.getInt(Constants.CONFIG.CONF_REDUCED_TO, Constants.VIEW_CHANNELS_DEFAULT)));
    }

    public void onClick_reducedTo_plus(View v) {
        SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        int reducedTo = settings.getInt(Constants.CONFIG.CONF_REDUCED_TO, Constants.VIEW_CHANNELS_DEFAULT);
        if (reducedTo < 1024) reducedTo *= 2;

        saveReduceTo(reducedTo);
    }

    public void onClick_reducedTo_minus(View v) {
        SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        int reducedTo = settings.getInt(Constants.CONFIG.CONF_REDUCED_TO, Constants.VIEW_CHANNELS_DEFAULT);
        if (reducedTo > 128) reducedTo /= 2;

        saveReduceTo(reducedTo);
    }

    private void saveReduceTo(int val) {
        saveIntPref(val, Constants.CONFIG.CONF_REDUCED_TO);
        updateReduceToText();
    }

    // noise discriminator
    private void updateNoiseDiscriminatorTextFromPrefs() {
        TextView noiseText = findViewById(R.id.noiseText);
        noiseText.setText(getString(R.string.noise_discriminator_format, sp.getInt(Constants.CONFIG.CONF_NOISE, Constants.NOISE_DISCRIMINATOR_DEFAULT)));
    }

    private void updateNoiseDiscriminatorTextFromUSB() {
        TextView noiseText = findViewById(R.id.noiseText);
        noiseText.setText(getString(R.string.noise_discriminator_format, usb_noise_value));
    }

    public void onClick_noise_minus(View v) {
        if (AtomSpectraService.inputType != AtomSpectraService.INPUT_SERIAL) {
            SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
            int r = settings.getInt(Constants.CONFIG.CONF_NOISE, Constants.NOISE_DISCRIMINATOR_DEFAULT);

            if (r > 0) {
                r--;
                stopRecording();
            }

            saveNoiseDiscriminatorToPrefs(r);
        } else {
            if (usb_noise_value > 0) {
                usb_noise_value--;
                stopRecording();
            }
            saveNoiseDiscriminatorToUSB(usb_noise_value);
        }
    }

    public void onClick_noise_plus(View v) {
        if (AtomSpectraService.inputType != AtomSpectraService.INPUT_SERIAL) {
            SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
            int r = settings.getInt(Constants.CONFIG.CONF_NOISE, Constants.NOISE_DISCRIMINATOR_DEFAULT);

            if (r < (Constants.NUM_HIST_POINTS / 4)) {
                r++;
                stopRecording();
            }

            saveNoiseDiscriminatorToPrefs(r);
        } else {
            if (usb_noise_value < (Constants.NUM_HIST_POINTS / 4)) {
                usb_noise_value++;
                stopRecording();
            }
            saveNoiseDiscriminatorToUSB(usb_noise_value);
        }
    }

    private void saveNoiseDiscriminatorToPrefs(int val) {
        saveIntPref(val, Constants.CONFIG.CONF_NOISE);
        updateNoiseDiscriminatorTextFromPrefs();
    }

    private void saveNoiseDiscriminatorToUSB(int val) {
        sendBroadcast(new Intent(Constants.ACTION.ACTION_SEND_USB_COMMAND)
                .putExtra(Constants.ACTION_PARAMETERS.USB_COMMAND_ID, SETTINGS_SET_NOISE_ID)
                .putExtra(Constants.ACTION_PARAMETERS.USB_COMMAND_DATA, "-nos " + val)
                .setPackage(Constants.PACKAGE_NAME));
    }

    public void onClick_noise(View v) {
        final AlertDialog.Builder alert = new AlertDialog.Builder(this);

        alert.setTitle(getString(R.string.ask_noise_title));
        alert.setMessage(getString(R.string.ask_noise_text));

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        alert.setView(input);

        alert.setPositiveButton("Ok", (dialog, whichButton) -> {
            if (AtomSpectraService.inputType == AtomSpectraService.INPUT_AUDIO) {
                SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
                int r = settings.getInt(Constants.CONFIG.CONF_NOISE, Constants.NOISE_DISCRIMINATOR_DEFAULT);
                String value = input.getText().toString();
                int intValue = r;
                try {
                    intValue = Integer.parseInt(value);
                } catch (NumberFormatException nfe) {
                    System.out.println("Could not parse " + nfe);
                }
                intValue = StrictMath.max(0, StrictMath.min(Constants.NUM_HIST_POINTS / 4, intValue));
                saveNoiseDiscriminatorToPrefs(intValue);
            }
            if (AtomSpectraService.inputType == AtomSpectraService.INPUT_SERIAL) {
                String value = input.getText().toString();
                int intValue = usb_noise_value;
                try {
                    intValue = Integer.parseInt(value);
                } catch (NumberFormatException nfe) {
                    System.out.println("Could not parse " + nfe);
                }
                usb_noise_value = StrictMath.max(0, StrictMath.min(Constants.NUM_HIST_POINTS / 4, intValue));
                saveNoiseDiscriminatorToUSB(usb_noise_value);
            }
            stopRecording();
        });
        alert.setNegativeButton("Cancel", (dialog, whichButton) -> {
        });
        closeKeyboardOnAlertDismiss(alert);
        alert.show();
    }

    // calibration factor
    private void updateCalibrationFactorText() {
        TextView calibrationFactorText = findViewById(R.id.calibrationFactorText);
        calibrationFactorText.setText(getString(R.string.max_factor_format, sp.getInt(Constants.CONFIG.CONF_MAX_POLI_FACTOR, Constants.DEFAULT_POLI_FACTOR)));
    }

    public void onClick_Factor_minus(View v) {
        SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        int r = settings.getInt(Constants.CONFIG.CONF_MAX_POLI_FACTOR, Constants.DEFAULT_POLI_FACTOR);

        if (r > 1) {
            r = r - 1;
        } else {
            r = 1;
        }

        saveFactor(r);
    }

    public void onClick_Factor_plus(View v) {
        SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        int r = settings.getInt(Constants.CONFIG.CONF_MAX_POLI_FACTOR, Constants.DEFAULT_POLI_FACTOR);

        if (r < 4) {
            r = r + 1;
        } else {
            r = 4;
        }

        saveFactor(r);
    }

    private void saveFactor(int val) {
        saveIntPref(val, Constants.CONFIG.CONF_MAX_POLI_FACTOR);
        updateCalibrationFactorText();
        if (AtomSpectraService.newCalibration.getLines() > 1)
            AtomSpectraService.newCalibration.Calculate(val);
    }

    // smooth window size
    private void updateSmoothText() {
        TextView smoothText = findViewById(R.id.smoothText);
        smoothText.setText(getString(R.string.smoothness_format, sp.getInt(Constants.CONFIG.CONF_GOLAY_WINDOW, Constants.DEFAULT_GOLAY_WINDOW)));
    }

    public void onClick_Smoothness_minus(View v) {
        SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        int r = settings.getInt(Constants.CONFIG.CONF_GOLAY_WINDOW, Constants.DEFAULT_GOLAY_WINDOW);

        if (r > 1) {
            r = r - 1;
        } else {
            r = 1;
        }

        saveSmooth(r);
    }

    public void onClick_Smoothness_plus(View v) {
        SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        int r = settings.getInt(Constants.CONFIG.CONF_GOLAY_WINDOW, Constants.DEFAULT_GOLAY_WINDOW);

        if (r < 5) {
            r = r + 1;
        } else {
            r = 5;
        }

        saveSmooth(r);
    }

    private void saveSmooth(int val) {
        saveIntPref(val, Constants.CONFIG.CONF_GOLAY_WINDOW);
        updateSmoothText();
    }

    // compress graph type
    private void updateCompressGraphText() {
        TextView compressGraphText = findViewById(R.id.compressGraphTypeText);
        CharSequence[] data = getResources().getTextArray(R.array.compress_graph_array);
        int r;
        try {
            r = sp.getInt(Constants.CONFIG.CONF_COMPRESS_GRAPH, Constants.COMPRESS_GRAPH_SUM);
        } catch (Exception e) {
            r = Constants.COMPRESS_GRAPH_SUM;
        }
        r = Constants.MinMax(r, 0, data.length - 1);
        compressGraphText.setText(getString(R.string.compress_graph_format, data[r]));
    }

    public void onClick_CompressGraph_plus(View v) {
        SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        int r = settings.getInt(Constants.CONFIG.CONF_COMPRESS_GRAPH, Constants.COMPRESS_GRAPH_SUM);
        CharSequence[] data = getResources().getTextArray(R.array.compress_graph_array);
        r = r < (data.length - 1) ? r + 1 : data.length - 1;

        saveCompressGraph(r);
    }

    public void onClick_CompressGraph_minus(View v) {
        SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        int r = settings.getInt(Constants.CONFIG.CONF_COMPRESS_GRAPH, Constants.COMPRESS_GRAPH_SUM);
        r = r > 0 ? r - 1 : 0;

        saveCompressGraph(r);
    }

    private void saveCompressGraph(int val) {
        saveIntPref(val, Constants.CONFIG.CONF_COMPRESS_GRAPH);
        updateCompressGraphText();
    }
    // --- end spectrum section

    // --- spectrum change section
    // diff time
    private void updateDiffTimeText() {
        TextView diffTimeText = findViewById(R.id.spectrumChangeDiffTimeText);
        diffTimeText.setText(getString(R.string.spectrum_change_delta_duration_format, sp.getInt(Constants.CONFIG.CONF_SPECTRUM_CHANGE_DIFF_TIME, Constants.DEFAULT_DELTA_TIME)));
    }

    public void onClick_Diff_Time_minus(View v) {
        SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        int r = settings.getInt(Constants.CONFIG.CONF_SPECTRUM_CHANGE_DIFF_TIME, Constants.DEFAULT_DELTA_TIME);

        if (r > 60) {
            r = r - 30;
        } else if (r > 10) {
            r = r - 5;
        } else if (r > 1) {
            r = r - 1;
        } else {
            r = 1;
        }

        saveDiffTime(r);
    }

    public void onClick_Diff_Time_plus(View v) {
        SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        int r = settings.getInt(Constants.CONFIG.CONF_SPECTRUM_CHANGE_DIFF_TIME, Constants.DEFAULT_DELTA_TIME);

        if (r < 10) {
            r = r + 1;
        } else if (r < 60) {
            r = r + 5;
        } else if (r < 300) {
            r = r + 30;
        } else {
            r = 300;
        }

        saveDiffTime(r);
    }

    private void saveDiffTime(int val) {
        saveIntPref(val, Constants.CONFIG.CONF_SPECTRUM_CHANGE_DIFF_TIME);
        updateDiffTimeText();
    }
    // --- end spectrum change section

    // --- spectrogram section
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
        spgDeltaDurationText.setText(getString(R.string.settings_spg_delta_duration, sp.getInt(Constants.CONFIG.CONF_SPG_DELTA_DURATION, 0)));
    }

    public void onClick_spgDeltaDuration_minus(View v) {
        SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        int val = settings.getInt(Constants.CONFIG.CONF_SPG_DELTA_DURATION, 0);
        if (val > Constants.SPG_DELTA_DURATION_MIN) {
            val -= Constants.SPG_DELTA_DURATION_MIN;
        } else {
            val = 0;
        }

        saveSpgDeltaDuration(val);
    }

    public void onClick_spgDeltaDuration_plus(View v) {
        SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        int val = settings.getInt(Constants.CONFIG.CONF_SPG_DELTA_DURATION, 0);
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
    // --- end spectrogram section

    // --- dose rate section
    private void setupDoseRateSection() {
        doseRateFreqLabel = findViewById(R.id.updateFreqLabel);
        SensG = findViewById(R.id.SensG);
        SensGCompensated = findViewById(R.id.SensGCompensated);
        BackgroundCount = findViewById(R.id.BckgCnt);
        slowSens = findViewById(R.id.SlowSens);
        mediumSens = findViewById(R.id.MediumSens);
        fastSens = findViewById(R.id.FastSens);
        slowSens.setOnEditorActionListener(doseRateEditorActionListener);
        mediumSens.setOnEditorActionListener(doseRateEditorActionListener);
        fastSens.setOnEditorActionListener(doseRateEditorActionListener);
        SensG.setOnEditorActionListener(doseRateEditorActionListener);
        SensGCompensated.setOnEditorActionListener(doseRateEditorActionListener);
        BackgroundCount.setOnEditorActionListener(doseRateEditorActionListener);
        slowSens.setText(String.format(Locale.US, "%d", sp.getInt(Constants.CONFIG.CONF_SEARCH_SLOW, Constants.SEARCH_SLOW_DEFAULT)));
        fastSens.setText(String.format(Locale.US, "%d", sp.getInt(Constants.CONFIG.CONF_SEARCH_FAST, Constants.SEARCH_FAST_DEFAULT)));
        mediumSens.setText(String.format(Locale.US, "%d", sp.getInt(Constants.CONFIG.CONF_SEARCH_MEDIUM, Constants.SEARCH_MEDIUM_DEFAULT)));
        SensG.setText(String.format(Locale.getDefault(), "%d", sp.getInt(Constants.CONFIG.CONF_SENSG, Constants.SENSG_DEFAULT)));
        SensGCompensated.setText(String.format(Locale.getDefault(), "%d", sp.getInt(Constants.CONFIG.CONF_SENSG_COMPENSATED, Constants.SENSG_COMPENSATED_DEFAULT)));
        BackgroundCount.setText(String.format(Locale.getDefault(), "%d", sp.getInt(Constants.CONFIG.CONF_BACKGROUND, Constants.BACKGND_CPS_DEFAULT)));
        doseRateFreqLabel.setText(String.format(Locale.US, "%d", sp.getInt(Constants.CONFIG.CONF_DOSE_UPDATE, 1)));
    }

    private final EditText.OnEditorActionListener doseRateEditorActionListener = new TextView.OnEditorActionListener() {
        @Override
        public boolean onEditorAction(TextView textView, int i, KeyEvent keyEvent) {
            if (i == EditorInfo.IME_ACTION_DONE) {
                try {
                    Number sensNumber = NumberFormat.getNumberInstance().parse(SensG.getText().toString());
                    Number sensCompensatedNumber = NumberFormat.getNumberInstance().parse(SensGCompensated.getText().toString());
                    Number backgroundNumber = NumberFormat.getNumberInstance().parse(BackgroundCount.getText().toString());
                    if (sensNumber != null && sensCompensatedNumber != null && backgroundNumber != null) {
                        SharedPreferences.Editor editor = sp.edit();
                        editor.putInt(Constants.CONFIG.CONF_SENSG, sensNumber.intValue());
                        editor.putInt(Constants.CONFIG.CONF_SENSG_COMPENSATED, sensCompensatedNumber.intValue());
                        editor.putInt(Constants.CONFIG.CONF_BACKGROUND, backgroundNumber.intValue());
                        editor.putInt(Constants.CONFIG.CONF_SEARCH_SLOW, Integer.parseInt(slowSens.getText().toString()));
                        editor.putInt(Constants.CONFIG.CONF_SEARCH_FAST, Integer.parseInt(fastSens.getText().toString()));
                        editor.putInt(Constants.CONFIG.CONF_SEARCH_MEDIUM, Integer.parseInt(mediumSens.getText().toString()));
                        editor.apply();
                    }
                } catch (Exception e) {
                    //
                }
            }
            return false;
        }
    };

    public void onClick_increaseFreq(View v) {
        int freq = sp.getInt(Constants.CONFIG.CONF_DOSE_UPDATE, Constants.UPDATE_DOSE_DEFAULT);
        switch (freq) {
            case 1:
                freq = 2;
                break;
            case 2:
            case 4:
                freq = 5;
                break;
            case 5:
            case 10:
                freq = 10;
                break;
            default:
                freq = 1;
                break;
        }
        saveIntPref(freq, Constants.CONFIG.CONF_DOSE_UPDATE);
        doseRateFreqLabel.setText(String.format(Locale.US, "%d", freq));
    }

    public void onClick_decreaseFreq(View v) {
        int freq = sp.getInt(Constants.CONFIG.CONF_DOSE_UPDATE, Constants.UPDATE_DOSE_DEFAULT);
        switch (freq) {
            case 4:
            case 5:
                freq = 2;
                break;
            case 10:
                freq = 5;
                break;
            default:
                freq = 1;
                break;
        }
        saveIntPref(freq, Constants.CONFIG.CONF_DOSE_UPDATE);
        doseRateFreqLabel.setText(String.format(Locale.US, "%d", freq));
    }
    // --- end dose rate section

    // --- interval search section
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
        if (AtomSpectraService.leftChannelInterval != 0 || AtomSpectraService.rightChannelInterval != Constants.NUM_HIST_POINTS - 1)
            energyRangeText.setText(getString(R.string.interval_search_energy_range_format, AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().toEnergy(AtomSpectraService.leftChannelInterval), AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().toEnergy(AtomSpectraService.rightChannelInterval)));
        else
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
                AtomSpectraService.resetInterval();
                updateSearchEnergyRangeText();
            } else {
                if (value.contains("-")) {
                    String num1 = value.substring(0, value.indexOf("-"));
                    String num2 = value.substring(value.indexOf("-") + 1);
                    try {
                        double val1 = Double.parseDouble(num1);
                        int number1 = AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().toChannel(val1);
                        double val2 = Double.parseDouble(num2);
                        int number2 = AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().toChannel(val2);
                        if (number1 < 0 || number1 >= number2 || number2 >= Constants.NUM_HIST_POINTS - 1)
                            return;
                        AtomSpectraService.setEnergyInterval(val1, val2);
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
        SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        int r = settings.getInt(Constants.CONFIG.CONF_SEARCH_ALARM_VOLUME, Constants.ALARM_VOLUME_DEFAULT);

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
        SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        int r = settings.getInt(Constants.CONFIG.CONF_SEARCH_ALARM_VOLUME, Constants.ALARM_VOLUME_DEFAULT);

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
        SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
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
        SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
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
    // --- end interval search section

    // --- AtomSwift integration section
    // send data
    private void setupAtomSwiftIntegrationCheckbox() {
        CheckBox checkSendDataToAtomSwift = findViewById(R.id.sendDataToAtomSwiftCheckbox);
        checkSendDataToAtomSwift.setChecked(sp.getBoolean(Constants.CONFIG.CONF_SEND_DATA_TO_ATOMSWIFT, Constants.SEND_DATA_TO_ATOMSWIFT_DEFAULT));
        checkSendDataToAtomSwift.setOnClickListener(v -> {
            saveBooleanPref(checkSendDataToAtomSwift.isChecked(), Constants.CONFIG.CONF_SEND_DATA_TO_ATOMSWIFT);
        });
    }

    // dose rate type
    private void updateAtomSwiftDRText() {
        String atomSwiftDR = sp.getString(Constants.CONFIG.CONF_ATOMSWIFT_DOSE_RATE, Constants.ATOMSWIFT_DR_DEFAULT);
        if (indexOfStringArray(Constants.ATOMSWIFT_DOSE_RATES, atomSwiftDR) == -1) {
            atomSwiftDR = Constants.ATOMSWIFT_DR_DEFAULT;
            saveAtomSwiftDR(atomSwiftDR);
        }

        TextView drText = findViewById(R.id.atomSwiftDRText);
        switch (atomSwiftDR) {
            case Constants.ATOMSWIFT_DR_COMPENSATED:
                drText.setText(R.string.atom_swift_app_dr_compensated);
                break;
            case Constants.ATOMSWIFT_DR_NON_COMPENSATED:
                drText.setText(R.string.atom_swift_app_dr_non_compensated);
                break;
            case Constants.ATOMSWIFT_DR_INTERVAL:
                drText.setText(R.string.atom_swift_app_dr_interval);
                break;
        }
    }

    public void onClick_AtomSwiftDR_plus(View v) {
        String dr = sp.getString(Constants.CONFIG.CONF_ATOMSWIFT_DOSE_RATE, Constants.ATOMSWIFT_DR_DEFAULT);
        int selected_index = indexOfStringArray(Constants.ATOMSWIFT_DOSE_RATES, dr);
        if (selected_index == -1) {
            saveAtomSwiftDR(Constants.ATOMSWIFT_DR_DEFAULT);
        }
        if (selected_index + 1 < Constants.ATOMSWIFT_DOSE_RATES.length) {
            String new_dr = Constants.ATOMSWIFT_DOSE_RATES[selected_index + 1];
            saveAtomSwiftDR(new_dr);
        }
    }

    public void onClick_AtomSwiftDR_minus(View v) {
        String dr = sp.getString(Constants.CONFIG.CONF_ATOMSWIFT_DOSE_RATE, Constants.ATOMSWIFT_DR_DEFAULT);
        int selected_index = indexOfStringArray(Constants.ATOMSWIFT_DOSE_RATES, dr);
        if (selected_index == -1) {
            saveAtomSwiftDR(Constants.ATOMSWIFT_DR_DEFAULT);
        }
        if (selected_index > 0) {
            String new_dr = Constants.ATOMSWIFT_DOSE_RATES[selected_index - 1];
            saveAtomSwiftDR(new_dr);
        }
    }

    private void saveAtomSwiftDR(String dr) {
        saveStringPref(dr, Constants.CONFIG.CONF_ATOMSWIFT_DOSE_RATE);
        updateAtomSwiftDRText();
    }
    // --- end AtomSwift integration section

    // --- search isotopes section
    // auto search
    private void setupAutoSearchIsotopesCheckbox() {
        CheckBox checkUpdate = findViewById(R.id.autoSearchIsotopesEnabledCheckbox);
        checkUpdate.setChecked(sp.getBoolean(Constants.CONFIG.CONF_AUTO_UPDATE_ISOTOPES, false));
        checkUpdate.setOnClickListener(v -> {
            saveBooleanPref(checkUpdate.isChecked(), Constants.CONFIG.CONF_AUTO_UPDATE_ISOTOPES);
        });
    }
    // --- end search isotopes

    // --- files section
    // working dir
    private void updateWorkingDirText() {
        workingDirText = findViewById(R.id.directoryText);
        workingDirText.setText((Uri.parse(sp.getString(Constants.CONFIG.CONF_DIRECTORY_SELECTED, "..."))).getPath());
    }

    public void onSelectWorkingDirectoryClick(View v) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
            String dirName = settings.getString(Constants.CONFIG.CONF_DIRECTORY_SELECTED, null);
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);

            // Provide read access to files and sub-directories in the user-selected
            // directory.
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);

            if (dirName != null) {
                Uri uriName = Uri.parse(dirName);
                // Optionally, specify a URI for the directory that should be opened in
                // the system file picker when it loads.
                intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, uriName);
            } else {
                intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, getApplicationContext().getFilesDir());
            }

            startActivityForResult(intent, SELECT_DIR_CODE_SETTINGS);
        } else {
            SharedPreferences.Editor prefEditor = sp.edit();
            boolean directoryFound = false;
            try {
                String state = Environment.getExternalStorageState();
                if (Environment.MEDIA_MOUNTED.equals(state)) {
                    File folder = new File(Environment.getExternalStorageDirectory() + "/AtomSpectra");
                    if (!folder.exists()) {
                        if (folder.mkdir()) {
                            prefEditor.putString(Constants.CONFIG.CONF_DIRECTORY_SELECTED, folder.toString());
                            directoryFound = true;
                        } else {
                            folder = getApplicationContext().getExternalFilesDir(null);
                            if (folder != null && folder.exists()) {
                                prefEditor.putString(Constants.CONFIG.CONF_DIRECTORY_SELECTED, folder.toString());
                                directoryFound = true;
                            }
                        }
                    } else {
                        if (folder.isDirectory() && folder.canRead() && folder.canWrite()) {
                            prefEditor.putString(Constants.CONFIG.CONF_DIRECTORY_SELECTED, folder.toString());
                            directoryFound = true;
                        }
                    }
                }
                if (!directoryFound) {
                    File folder = getApplicationContext().getFilesDir();
                    prefEditor.putString(Constants.CONFIG.CONF_DIRECTORY_SELECTED, folder.toString());
                }
            } catch (Exception e) {
                prefEditor.remove(Constants.CONFIG.CONF_DIRECTORY_SELECTED);
                Toast.makeText(this, getString(R.string.storage_required), Toast.LENGTH_LONG).show();
            }
            prefEditor.apply();
        }
    }

    // add gps
    private void setupEnableGPSCheckbox() {
        // drop the parameter if the user drop the permission
        if (sp.getBoolean(Constants.CONFIG.CONF_ADD_GPS_TO_FILES, false)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    saveBooleanPref(false, Constants.CONFIG.CONF_ADD_GPS_TO_FILES);
                }
            }
        }

        final Activity id = this;
        CheckBox enableGPSCheckbox = findViewById(R.id.enableGPSCheckbox);
        enableGPSCheckbox.setChecked(sp.getBoolean(Constants.CONFIG.CONF_ADD_GPS_TO_FILES, false));
        enableGPSCheckbox.setOnClickListener(v -> {
            SharedPreferences.Editor prefEditor = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE).edit();
            boolean res = ((CheckBox) v).isChecked();
            if (res) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                        // Permission is not granted
                        //When permission is not granted by user, show them message why this permission is needed.
                        final AlertDialog.Builder alert = new AlertDialog.Builder(id)
                                .setTitle(getString(R.string.perm_ask_fine_gps_title))
                                .setMessage(getString(R.string.perm_ask_fine_gps_text))
                                .setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                                    ActivityCompat.requestPermissions(id, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_FINE_GPS);
                                });
                        alert.show();
                    } else {
                        prefEditor.putBoolean(Constants.CONFIG.CONF_ADD_GPS_TO_FILES, true);
                        sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_GPS).setPackage(Constants.PACKAGE_NAME));
                    }
                } else {
                    prefEditor.putBoolean(Constants.CONFIG.CONF_ADD_GPS_TO_FILES, true);
                    sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_GPS).setPackage(Constants.PACKAGE_NAME));
                }
            } else {
                prefEditor.putBoolean(Constants.CONFIG.CONF_ADD_GPS_TO_FILES, false);
            }
            prefEditor.apply();
            sendBroadcast(new Intent(Constants.ACTION.ACTION_CHECK_GPS_AVAILABILITY).setPackage(Constants.PACKAGE_NAME));
        });
    }

    // save channels
    private void updateSpectrumSaveChannelsText() {
        TextView saveChannelsText = findViewById(R.id.saveChannelsText);
        saveChannelsText.setText(getString(R.string.save_channels_format, Constants.MinMax(sp.getInt(Constants.CONFIG.CONF_SAVE_CHANNELS, Constants.EXPORT_CHANNELS_DEFAULT), 1024, Constants.NUM_HIST_POINTS)));
    }

    public void onClick_save_channels_minus(View v) {
        SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        int r = Constants.MinMax(settings.getInt(Constants.CONFIG.CONF_SAVE_CHANNELS, Constants.EXPORT_CHANNELS_DEFAULT), 1024, Constants.NUM_HIST_POINTS);

        if (r > 2048) {
            r = r / 2;
        } else {
            r = 1024;
        }

        saveSpectrumSaveChannels(r);
    }

    public void onClick_save_channels_plus(View v) {
        SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        int r = Constants.MinMax(settings.getInt(Constants.CONFIG.CONF_SAVE_CHANNELS, Constants.EXPORT_CHANNELS_DEFAULT), 1024, Constants.NUM_HIST_POINTS);

        if (r < (Constants.NUM_HIST_POINTS / 2)) {
            r = r * 2;
        } else {
            r = Constants.NUM_HIST_POINTS;
        }

        saveSpectrumSaveChannels(r);
    }

    public void onClick_save_channels(View v) {
        final AlertDialog.Builder alert = new AlertDialog.Builder(this);

        alert.setTitle("Channels to save to file");
        alert.setMessage("Enter new value:");

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        alert.setView(input);

        alert.setPositiveButton("Ok", (dialog, whichButton) -> {
            SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
            int r = Constants.MinMax(settings.getInt(Constants.CONFIG.CONF_SAVE_CHANNELS, Constants.EXPORT_CHANNELS_DEFAULT), 1024, Constants.NUM_HIST_POINTS);
            alert.setTitle("Channels to save to file " + r);
            String value = input.getText().toString();
            int intValue = r;
            try {
                intValue = Integer.parseInt(value);
            } catch (NumberFormatException nfe) {
                System.out.println("Could not parse " + nfe);
            }
            intValue = StrictMath.max(1024, StrictMath.min(Constants.NUM_HIST_POINTS, intValue));
            saveSpectrumSaveChannels(intValue);
        });
        alert.setNegativeButton("Cancel", (dialog, whichButton) -> {
        });
        closeKeyboardOnAlertDismiss(alert);
        alert.show();
    }

    private void saveSpectrumSaveChannels(int val) {
        AtomSpectra.saveChannels = val;
        saveIntPref(val, Constants.CONFIG.CONF_SAVE_CHANNELS);
        updateSpectrumSaveChannelsText();
    }

    // load channels
    private void updateSpectrumLoadChannelsText() {
        TextView loadChannelsText = findViewById(R.id.loadChannelsText);
        loadChannelsText.setText(getString(R.string.load_channels_format, Constants.MinMax(sp.getInt(Constants.CONFIG.CONF_LOAD_CHANNELS, 65536), 1024, 65536)));
    }

    public void onClick_load_channels_minus(View v) {
        SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        int r = settings.getInt(Constants.CONFIG.CONF_LOAD_CHANNELS, 65536);

        if (r > 2048) {
            r = r / 2;
        } else {
            r = 1024;
        }

        saveSpectrumLoadChannels(r);
    }

    public void onClick_load_channels_plus(View v) {
        SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        int r = settings.getInt(Constants.CONFIG.CONF_LOAD_CHANNELS, 65536);

        if (r < 32768) {
            r = r * 2;
        } else {
            r = 65536;
        }

        saveSpectrumLoadChannels(r);
    }

    public void onClick_load_channels(View v) {
        final AlertDialog.Builder alert = new AlertDialog.Builder(this);

        alert.setTitle("Channels to load from old format file");
        alert.setMessage("Enter new value:");

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        alert.setView(input);

        alert.setPositiveButton("Ok", (dialog, whichButton) -> {
            SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
            int r = settings.getInt(Constants.CONFIG.CONF_LOAD_CHANNELS, 65536);
            alert.setTitle("Channels to load from file " + r);
            String value = input.getText().toString();
            int intValue = r;
            try {
                intValue = Integer.parseInt(value);
            } catch (NumberFormatException nfe) {
                System.out.println("Could not parse " + nfe);
            }
            intValue = StrictMath.max(1024, StrictMath.min(65536, intValue));
            saveSpectrumLoadChannels(intValue);
        });
        alert.setNegativeButton("Cancel", (dialog, whichButton) -> {
        });
        closeKeyboardOnAlertDismiss(alert);
        alert.show();
    }

    private void saveSpectrumLoadChannels(int val) {
        AtomSpectra.loadChannels = val;
        saveIntPref(val, Constants.CONFIG.CONF_LOAD_CHANNELS);
        updateSpectrumLoadChannelsText();
    }

    // export channel compression
    private void updateSpectrumChannelCompressionText() {
        TextView compressChannelsText = findViewById(R.id.compressChannelsText);
        compressChannelsText.setText(getString(R.string.channel_compression_format, sp.getInt(Constants.CONFIG.CONF_COMPRESSION, Constants.EXPORT_COMPRESSION_DEFAULT)));
    }

    public void onClick_compression_minus(View v) {
        SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        int r = settings.getInt(Constants.CONFIG.CONF_COMPRESSION, Constants.EXPORT_COMPRESSION_DEFAULT);

        if (r > 2) {
            r = r / 2;
        } else {
            r = 1;
        }

        saveSpectrumChannelCompression(r);
    }

    public void onClick_compression_plus(View v) {
        SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        int r = settings.getInt(Constants.CONFIG.CONF_COMPRESSION, Constants.EXPORT_COMPRESSION_DEFAULT);

        if (r < 32) {
            r = r * 2;
        } else {
            r = 64;
        }

        saveSpectrumChannelCompression(r);
    }

    public void onClick_compression_channels(View v) {
        final AlertDialog.Builder alert = new AlertDialog.Builder(this);

        alert.setTitle("Channel compression");
        alert.setMessage("Enter new value:");

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        alert.setView(input);

        alert.setPositiveButton("Ok", (dialog, whichButton) -> {
            SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
            int r = settings.getInt(Constants.CONFIG.CONF_COMPRESSION, Constants.EXPORT_COMPRESSION_DEFAULT);
            alert.setTitle("Channel compression " + r);
            String value = input.getText().toString();
            int intValue = r;
            try {
                intValue = Integer.parseInt(value);
            } catch (NumberFormatException nfe) {
                System.out.println("Could not parse " + nfe);
            }
            intValue = StrictMath.max(1, StrictMath.min(64, intValue));
            saveSpectrumChannelCompression(intValue);
        });
        alert.setNegativeButton("Cancel", (dialog, whichButton) -> {
        });
        closeKeyboardOnAlertDismiss(alert);
        alert.show();
    }

    private void saveSpectrumChannelCompression(int val) {
        AtomSpectra.channelCompression = val;
        saveIntPref(val, Constants.CONFIG.CONF_COMPRESSION);
        updateSpectrumChannelCompressionText();
    }

    // filename pattern
    private void updateFilenamePatternText() {
        TextView filenamePatternText = findViewById(R.id.outputFileNamePatternText);
        filenamePatternText.setText(getResources().getTextArray(R.array.file_name_array)[
                (sp.getBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_PREFIX, Constants.OUTPUT_FILE_NAME_PREFIX_DEFAULT) ? 4 : 0) +
                        (sp.getBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_TIME, Constants.OUTPUT_FILE_NAME_TIME_DEFAULT) ? 2 : 0) +
                        (sp.getBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_DATE, Constants.OUTPUT_FILE_NAME_DATE_DEFAULT) ? 1 : 0)
                ]);
    }

    public void onClick_outputFilenameTemplate_minus(View v) {
        SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        boolean prefix = settings.getBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_PREFIX, Constants.OUTPUT_FILE_NAME_PREFIX_DEFAULT);
        boolean date = settings.getBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_DATE, Constants.OUTPUT_FILE_NAME_DATE_DEFAULT);
        boolean time = settings.getBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_TIME, Constants.OUTPUT_FILE_NAME_TIME_DEFAULT);
        int val = (prefix ? 4 : 0) + (time ? 2 : 0) + (date ? 1 : 0);

        if (val > 0) {
            val -= 1;
        }

        saveFilenamePattern(val);
    }

    public void onClick_outputFilenameTemplate_plus(View v) {
        SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        boolean prefix = settings.getBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_PREFIX, Constants.OUTPUT_FILE_NAME_PREFIX_DEFAULT);
        boolean date = settings.getBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_DATE, Constants.OUTPUT_FILE_NAME_DATE_DEFAULT);
        boolean time = settings.getBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_TIME, Constants.OUTPUT_FILE_NAME_TIME_DEFAULT);
        int val = (prefix ? 4 : 0) + (time ? 2 : 0) + (date ? 1 : 0);

        if (val < 7) {
            val += 1;
        }

        saveFilenamePattern(val);
    }

    private void saveFilenamePattern(int val) {
        SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        SharedPreferences.Editor prefEditor = settings.edit();
        prefEditor.putBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_PREFIX, (val & 4) != 0);
        prefEditor.putBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_TIME, (val & 2) != 0);
        prefEditor.putBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_DATE, (val & 1) != 0);
        prefEditor.apply();
        updateFilenamePatternText();
    }
    // --- end files section

    // --- misc section
    // locale
    private void updateLocaleText() {
        TextView localeText = findViewById(R.id.localeText);
        int r = sp.getInt(Constants.CONFIG.CONF_LOCALE_ID, 0);
        r = r < Constants.LOCALES_ID.length ? r : (Constants.LOCALES_ID.length - 1);
        localeText.setText(String.format(Locale.US, "Language: %s", Constants.LOCALES[r]));
    }

    public void onClick_Locale_plus(View v) {
        SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        int r = sp.getInt(Constants.CONFIG.CONF_LOCALE_ID, 0);
        r = (r + 1) < Constants.LOCALES_ID.length ? r + 1 : (Constants.LOCALES_ID.length - 1);

        saveLocale(r);
    }

    public void onClick_Locale_minus(View v) {
        SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        int r = sp.getInt(Constants.CONFIG.CONF_LOCALE_ID, 0);
        r = (r > 1) ? (r - 1) : 0;

        saveLocale(r);
    }

    private void saveLocale(int val) {
        saveIntPref(val, Constants.CONFIG.CONF_LOCALE_ID);
        updateLocaleText();
    }
    // --- end misc section

    private void stopRecording() {
        sendBroadcast(new Intent(Constants.ACTION.ACTION_FREEZE_DATA).putExtra(AtomSpectraSerial.EXTRA_DATA_TYPE, true).setPackage(Constants.PACKAGE_NAME));
    }

    private void saveStringPref(String value, String setting) {
        SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        SharedPreferences.Editor prefEditor = settings.edit();
        prefEditor.putString(setting, value);
        prefEditor.apply();
    }

    private void saveBooleanPref(boolean value, String setting) {
        SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        SharedPreferences.Editor prefEditor = settings.edit();
        prefEditor.putBoolean(setting, value);
        prefEditor.apply();
    }

    private void saveIntPref(int value, String setting) {
        SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        SharedPreferences.Editor prefEditor = settings.edit();
        prefEditor.putInt(setting, value);
        prefEditor.apply();
    }

    private static int indexOfStringArray(String[] array, String value) {
        int returnValue = -1;
        for (int i = 0; i < array.length; ++i) {
            if (value.equals(array[i])) {
                returnValue = i;
                break;
            }
        }
        return returnValue;
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

    private void returnFromSettings() {
        if (from_search_view) {
            AtomSpectraService.setScaleFactor(Constants.SCALE_DOSE_MODE);
        } else {
            AtomSpectraService.restoreScaleFactor();
        }

        sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_GRAPH).setPackage(Constants.PACKAGE_NAME));
    }

    @SuppressLint("WrongConstant")
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == SELECT_DIR_CODE_SETTINGS) {
            if (resultCode == RESULT_OK && (data != null)) {
                SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
                SharedPreferences.Editor editor = settings.edit();
                Uri uri = data.getData();
                if (uri != null) {
                    editor.putString(Constants.CONFIG.CONF_DIRECTORY_SELECTED, uri.toString());
                    final int takeFlags = data.getFlags()
                            & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    getContentResolver().takePersistableUriPermission(uri, takeFlags);
                    workingDirText.setText(uri.getPath());
                }
                editor.apply();
            }
        } else
            super.onActivityResult(requestCode, resultCode, data);
    }

    private static IntentFilter makeAtomSpectraUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(AtomSpectraService.ACTION_DATA_AVAILABLE);
        intentFilter.addAction(Constants.ACTION.ACTION_AUDIO_CHANGED);
        intentFilter.addAction(Constants.ACTION.ACTION_CLOSE_SETTINGS);
        intentFilter.addAction(Constants.ACTION.ACTION_UPDATE_SETTINGS);
        intentFilter.addAction(Constants.ACTION.ACTION_USB_HAS_ANSWER);
        return intentFilter;
    }

    private final BroadcastReceiver mDataUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (AtomSpectraService.ACTION_DATA_AVAILABLE.equals(action) && active) {
                Bundle mBundle = intent.getExtras();
                if (mBundle != null) {
                    int cps = mBundle.getInt(AtomSpectraService.EXTRA_DATA_INT_CP1S);
                    int cps_interval = mBundle.getInt(AtomSpectraService.EXTRA_DATA_INT_CP1S_INTERVAL);
                    countsTextField.setText(getString(R.string.cps_show, cps, cps_interval));
                    SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
                    if (AtomSpectraService.getScaleFactor() == Constants.SCALE_OSCILLOSCOPE_MODE) {
                        double[] realtime_audio_data = mBundle.getDoubleArray(AtomSpectraService.EXTRA_DATA_ARRAY_DOUBLE_REALTIME_AUDIO_DATA);
                        if (realtime_audio_data == null) {
                            realtime_audio_data = new double[1024];
                        }
                        mAtomSpectraSignalView.showOscilloscope(
                                realtime_audio_data,
                                zoom_factor
                        );
                    } else {
                        double[] reference_pulse_data = mBundle.getDoubleArray(AtomSpectraService.EXTRA_DATA_ARRAY_DOUBLE_REFERENCE_PULSE_DATA);
                        if (reference_pulse_data == null) {
                            reference_pulse_data = new double[1024];
                        }
                        mAtomSpectraSignalView.showReferencePulse(
                                reference_pulse_data,
                                settings.getInt(Constants.CONFIG.CONF_MIN_POINTS, Constants.MIN_FRONT_POINTS_DEFAULT),
                                settings.getInt(Constants.CONFIG.CONF_MAX_POINTS, Constants.MAX_FRONT_POINTS_DEFAULT)
                        );
                    }
                    //	Log.d(TAG, "data received from service  "+ String.valueOf(array_length));
                }
            }
            if (Constants.ACTION.ACTION_CLOSE_SETTINGS.equals(action)) {
                returnFromSettings();
                finish();
            }
            if (Constants.ACTION.ACTION_USB_HAS_ANSWER.equals(action)) {
                String id = intent.getStringExtra(AtomSpectraSerial.EXTRA_ID);
                String data = intent.getStringExtra(AtomSpectraSerial.EXTRA_RESULT);
                if (SETTINGS_GET_INF_ID.equals(id)) {
                    if (data != null) {
                        String val = AtomSpectraSerial.getParameter(data, "NOISE");
                        if (val != null) {
                            try {
                                usb_noise_value = Integer.parseInt(val);
                                updateNoiseDiscriminatorTextFromUSB();
                                retrySend = true;
                            } catch (NumberFormatException nfe) {
                                //nothing
                            }
                        } else {
                            if (retrySend) {
                                retrySend = false;
                                //Retry a command to ensure it is not a device failure
                                sendUsbInfoRequest();
                            } else {
                                //Skip command resending
                                retrySend = true;
                            }
                        }
                    }
                } else if (SETTINGS_SET_NOISE_ID.equals(id)) {
                    if (AtomSpectraSerial.COMMAND_RESULT_OK.equals(data)) {
                        updateNoiseDiscriminatorTextFromUSB();
                    }
                }
            }
            if (Constants.ACTION.ACTION_AUDIO_CHANGED.equals(action)) {
                outputDeviceID = sp.getInt(Constants.CONFIG.CONF_OUTPUT_SOUND_DEVICE_ID, -1);
                outputDeviceName = sp.getString(Constants.CONFIG.CONF_OUTPUT_SOUND_DEVICE_NAME, "(none)");
                setupSearchAlarmOutputDeviceText();
                CheckBox searchAlarmEnabledCheckBox = findViewById(R.id.intervalSearchSoundEnabled);
                if (!sp.getBoolean(Constants.CONFIG.CONF_OUTPUT_SOUND, false))
                    searchAlarmEnabledCheckBox.setChecked(false);

                inputDeviceID = sp.getInt(Constants.CONFIG.CONF_INPUT_SOUND_DEVICE_ID, -1);
                inputDeviceName = sp.getString(Constants.CONFIG.CONF_INPUT_SOUND_DEVICE_NAME, "(none)");
                updateInputDeviceText();
                CheckBox inputSoundCheckbox = findViewById(R.id.inputSoundCheckbox);
                if (!sp.getBoolean(Constants.CONFIG.CONF_INPUT_SOUND, false))
                    inputSoundCheckbox.setChecked(false);
            }
            if (Constants.ACTION.ACTION_UPDATE_SETTINGS.equals(action)) {
                SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
                CheckBox box = findViewById(R.id.enableGPSCheckbox);
                box.setChecked(settings.getBoolean(Constants.CONFIG.CONF_ADD_GPS_TO_FILES, false));
                if (AtomSpectraService.inputType != AtomSpectraService.INPUT_SERIAL) {
                    updateNoiseDiscriminatorTextFromPrefs();
                } else {
                    sendUsbInfoRequest();
                }
            }
        }

    };

    @Override
    public void onStart() {
        super.onStart();
        Log.d(TAG, "-XxX-  onStart");
    }

    @Override
    protected void onResume() {
        super.onResume();
        active = true;
        AtomSpectraService.setScaleFactor(Constants.SCALE_OSCILLOSCOPE_MODE);
        Log.d(TAG, "registerReceiver");

    }

    @Override
    protected void onPause() {
        super.onPause();
        active = false;
        Log.d(TAG, "-XxX-  pause");
        returnFromSettings();
    }

    @Override
    protected void onStop() {
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(mDataUpdateReceiver);
    }

    @Override
    public void onGesture(GestureOverlayView overlay, MotionEvent event) {

    }

    @Override
    public void onGestureCancelled(GestureOverlayView overlay, MotionEvent event) {

    }

    @Override
    public void onGestureEnded(GestureOverlayView overlay, MotionEvent event) {

    }

    @Override
    public void onGestureStarted(GestureOverlayView overlay, MotionEvent event) {

    }

    @SuppressLint("ClickableViewAccessibility")
    private void initializeGestures() {
        gestureDetector = initGestureDetector();

        View view = findViewById(R.id.signal_area);

        view.setOnClickListener(arg0 -> {
        });

        view.setOnTouchListener((v, event) -> gestureDetector.onTouchEvent(event));
    }

    private GestureDetector initGestureDetector() {
        return new GestureDetector(getBaseContext(), new SimpleOnGestureListener() {

            private final SwipeDetector detector = new SwipeDetector();

            public boolean onFling(MotionEvent e1, @NonNull MotionEvent e2, float velocityX,
                                   float velocityY) {
                Log.d(TAG, "FLING!  ");
                try {
                    if (detector.isSwipeLeft(e1, e2, velocityX)) {
                        //showToast("Left Swipe");
                        if (AtomSpectraService.getScaleFactor() > Constants.SCALE_OSCILLOSCOPE_MODE)
                            AtomSpectraService.setScaleFactor(Constants.SCALE_OSCILLOSCOPE_MODE);
                        else {
                            AtomSpectraService.setScaleFactor(Constants.SCALE_AUDIO_REFERENCE_PULSE_MODE);
                            sendBroadcast(new Intent(Constants.ACTION.ACTION_CLEAR_IMPULSE).setPackage(Constants.PACKAGE_NAME));
                        }
                        sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_GRAPH).setPackage(Constants.PACKAGE_NAME));

                    } else if (detector.isSwipeRight(e1, e2, velocityX)) {
                        //showToast("Right Swipe");
                        if (AtomSpectraService.getScaleFactor() < Constants.SCALE_AUDIO_REFERENCE_PULSE_MODE)
                            AtomSpectraService.setScaleFactor(Constants.SCALE_AUDIO_REFERENCE_PULSE_MODE);
                        else {
                            AtomSpectraService.setScaleFactor(Constants.SCALE_OSCILLOSCOPE_MODE);
                            sendBroadcast(new Intent(Constants.ACTION.ACTION_CLEAR_IMPULSE).setPackage(Constants.PACKAGE_NAME));
                        }

                        sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_GRAPH).setPackage(Constants.PACKAGE_NAME));
                    } else if (detector.isSwipeDown(e1, e2, velocityY)) {
                        if (zoom_factor > 1.1) zoom_factor /= 2;
                        else showToast(getString(R.string.graph_min_zoom));
                        sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_GRAPH).setPackage(Constants.PACKAGE_NAME));
                    } else if (detector.isSwipeUp(e1, e2, velocityY)) {
                        if (zoom_factor < 40) zoom_factor *= 2;
                        else showToast(getString(R.string.graph_max_zoom));
                        sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_GRAPH).setPackage(Constants.PACKAGE_NAME));
                    }

                } catch (Exception ignored) {
                } //for now, ignore
                return false;

            }

            private void showToast(String phrase) {
                Toast.makeText(getApplicationContext(), phrase, Toast.LENGTH_SHORT).show();
            }
        });
    }
}

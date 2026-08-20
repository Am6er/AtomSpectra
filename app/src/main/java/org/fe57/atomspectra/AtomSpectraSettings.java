package org.fe57.atomspectra;


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

import java.io.File;
import java.util.Locale;

public class AtomSpectraSettings extends Activity implements OnGestureListener {

    private final static String TAG = AtomSpectraSettings.class.getSimpleName();

    public static boolean active = false;

    private AtomSpectraShapeView mAtomSpectraSignalView;

    private GestureDetector gestureDetector;
    private TextView countsTextField;
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
    private boolean showPulseShape = false;

    private float zoom_factor = 1;
    private int usb_noise_value = 25;
    private boolean retrySend = true;

    private static final String SETTINGS_GET_INF_ID = "Inf get";
    private static final String SETTINGS_SET_NOISE_ID = "Noise set";

    @Override
    protected void attachBaseContext(Context newBase) {
        String lang = PrefHelper.getLocale(newBase);
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

        sp = PrefHelper.getASSharedPreferences(this);

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
        setupDoseUpdateFrequency();
        setupAudioSectionAvailability();
        // --- end audio processing

        // --- usb processing
        setupAllowPartialHistogramCheckbox();
        // --- end usb processing

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
        updateExportChannelCompressionText();
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

        sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_GRAPH).setPackage(Constants.PACKAGE_NAME));
        sendUsbInfoRequest();
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

    private void setupAllowPartialHistogramCheckbox() {
        CheckBox checkAllowPartialHistogram = findViewById(R.id.CheckAllowPartialHistogram);
        checkAllowPartialHistogram.setChecked(sp.getBoolean(Constants.CONFIG.CONF_USB_ALLOW_PARTIAL_HISTOGRAM, Constants.USB_ALLOW_PARTIAL_HISTOGRAM_DEFAULT));
        checkAllowPartialHistogram.setOnClickListener(v -> {
            saveBooleanPref(checkAllowPartialHistogram.isChecked(), Constants.CONFIG.CONF_USB_ALLOW_PARTIAL_HISTOGRAM);
        });
    }

    // raw audio
    private void setupRawAudioSourceCheckbox() {
        CheckBox rawAudio = findViewById(R.id.CheckRawAudio);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            AudioManager manager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (manager == null || manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == null) {
                rawAudio.setEnabled(false);
                rawAudio.setChecked(false);
                rawAudio.setVisibility(CheckBox.INVISIBLE);
                saveIntPref(Constants.AUDIO_SOURCE_VOICE, Constants.CONFIG.CONF_AUDIO_SOURCE);
            } else {
                rawAudio.setEnabled(true);
                rawAudio.setVisibility(CheckBox.VISIBLE);
                int currentValue = sp.getInt(Constants.CONFIG.CONF_AUDIO_SOURCE, Constants.AUDIO_SOURCE_DEFAULT);
                rawAudio.setChecked(currentValue == Constants.AUDIO_SOURCE_RAW);
            }
        } else {
            rawAudio.setEnabled(false);
            rawAudio.setChecked(false);
            rawAudio.setVisibility(CheckBox.INVISIBLE);
            saveIntPref(Constants.AUDIO_SOURCE_VOICE, Constants.CONFIG.CONF_AUDIO_SOURCE);
        }

        rawAudio.setOnClickListener(v -> {
            int newValue = rawAudio.isChecked()
                    ? Constants.AUDIO_SOURCE_RAW
                    : Constants.AUDIO_SOURCE_VOICE;
            saveIntPref(newValue, Constants.CONFIG.CONF_AUDIO_SOURCE);
            stopRecording();
        });
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
            SharedPreferences settings = PrefHelper.getASSharedPreferences(this);
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
        SharedPreferences settings = PrefHelper.getASSharedPreferences(this);
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
        SharedPreferences settings = PrefHelper.getASSharedPreferences(this);
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
        saveIntPref(val, Constants.CONFIG.CONF_MIN_POINTS);
        updateMinFrontPointsText();
        showPulseShape = true;
    }

    // max front points
    private void updateMaxFrontPointsText() {
        TextView maxFrontPointsTextField = findViewById(R.id.maxFrontText);
        maxFrontPointsTextField.setText(getString(R.string.max_front_points_format, sp.getInt(Constants.CONFIG.CONF_MAX_POINTS, Constants.MAX_FRONT_POINTS_DEFAULT)));
        showPulseShape = true;
    }

    public void onClick_maxFront_plus(View v) {
        SharedPreferences settings = PrefHelper.getASSharedPreferences(this);
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
        SharedPreferences settings = PrefHelper.getASSharedPreferences(this);
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
        saveIntPref(val, Constants.CONFIG.CONF_MAX_POINTS);
        updateMaxFrontPointsText();
    }
    // --- end audio processing section

    // --- spectrum section
    // reduce display to (number of points to render on the screen)
    private void updateReduceToText() {
        TextView reduceToText = findViewById(R.id.reduceToChannelsText);
        reduceToText.setText(getString(R.string.reduced_to_format, sp.getInt(Constants.CONFIG.CONF_REDUCED_TO, Constants.VIEW_CHANNELS_DEFAULT)));
    }

    public void onClick_reducedTo_plus(View v) {
        SharedPreferences settings = PrefHelper.getASSharedPreferences(this);
        int reducedTo = settings.getInt(Constants.CONFIG.CONF_REDUCED_TO, Constants.VIEW_CHANNELS_DEFAULT);
        if (reducedTo < 1024) reducedTo *= 2;

        saveReduceTo(reducedTo);
    }

    public void onClick_reducedTo_minus(View v) {
        SharedPreferences settings = PrefHelper.getASSharedPreferences(this);
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
            SharedPreferences settings = PrefHelper.getASSharedPreferences(this);
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
            SharedPreferences settings = PrefHelper.getASSharedPreferences(this);
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
                SharedPreferences settings = PrefHelper.getASSharedPreferences(this);
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
        SharedPreferences settings = PrefHelper.getASSharedPreferences(this);
        int r = settings.getInt(Constants.CONFIG.CONF_MAX_POLI_FACTOR, Constants.DEFAULT_POLI_FACTOR);

        if (r > 1) {
            r = r - 1;
        } else {
            r = 1;
        }

        saveFactor(r);
    }

    public void onClick_Factor_plus(View v) {
        SharedPreferences settings = PrefHelper.getASSharedPreferences(this);
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
        if (AtomSpectraService.newCalibration.getPointsCount() > 1)
            AtomSpectraService.newCalibration.Calculate(val);
    }

    // smooth window size
    private void updateSmoothText() {
        TextView smoothText = findViewById(R.id.smoothText);
        smoothText.setText(getString(R.string.smoothness_format, sp.getInt(Constants.CONFIG.CONF_GOLAY_WINDOW, Constants.DEFAULT_GOLAY_WINDOW)));
    }

    public void onClick_Smoothness_minus(View v) {
        SharedPreferences settings = PrefHelper.getASSharedPreferences(this);
        int r = settings.getInt(Constants.CONFIG.CONF_GOLAY_WINDOW, Constants.DEFAULT_GOLAY_WINDOW);

        if (r > 1) {
            r = r - 1;
        } else {
            r = 1;
        }

        saveSmooth(r);
    }

    public void onClick_Smoothness_plus(View v) {
        SharedPreferences settings = PrefHelper.getASSharedPreferences(this);
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
        SharedPreferences settings = PrefHelper.getASSharedPreferences(this);
        int r = settings.getInt(Constants.CONFIG.CONF_COMPRESS_GRAPH, Constants.COMPRESS_GRAPH_SUM);
        CharSequence[] data = getResources().getTextArray(R.array.compress_graph_array);
        r = r < (data.length - 1) ? r + 1 : data.length - 1;

        saveCompressGraph(r);
    }

    public void onClick_CompressGraph_minus(View v) {
        SharedPreferences settings = PrefHelper.getASSharedPreferences(this);
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
        SharedPreferences settings = PrefHelper.getASSharedPreferences(this);
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
        SharedPreferences settings = PrefHelper.getASSharedPreferences(this);
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
        int value = sp.getInt(Constants.CONFIG.CONF_SPG_DELTA_DURATION, 0);
        if (value > 0) {
            spgDeltaDurationText.setText(getString(R.string.settings_spg_delta_duration, value));
        } else {
            spgDeltaDurationText.setText(getString(R.string.settings_spg_save_delta_off));
        }
    }

    public void onClick_spgDeltaDuration_minus(View v) {
        SharedPreferences settings = PrefHelper.getASSharedPreferences(this);
        int val = settings.getInt(Constants.CONFIG.CONF_SPG_DELTA_DURATION, 0);
        if (val > Constants.SPG_DELTA_DURATION_MIN) {
            val -= Constants.SPG_DELTA_DURATION_MIN;
        } else {
            val = 0;
        }

        saveSpgDeltaDuration(val);
    }

    public void onClick_spgDeltaDuration_plus(View v) {
        SharedPreferences settings = PrefHelper.getASSharedPreferences(this);
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

    // --- dose update frequency (audio processing) ---
    private void setupDoseUpdateFrequency() {
        doseRateFreqLabel = findViewById(R.id.updateFreqLabel);
        doseRateFreqLabel.setText(String.format(Locale.US, "%d", sp.getInt(Constants.CONFIG.CONF_DOSE_UPDATE, Constants.UPDATE_DOSE_DEFAULT)));
    }

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
                AtomSpectraService.getIntervalSearchAlarmBaseline().reset();
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
        SharedPreferences settings = PrefHelper.getASSharedPreferences(this);
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
        SharedPreferences settings = PrefHelper.getASSharedPreferences(this);
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
        SharedPreferences settings = PrefHelper.getASSharedPreferences(this);
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
        SharedPreferences settings = PrefHelper.getASSharedPreferences(this);
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
            SharedPreferences settings = PrefHelper.getASSharedPreferences(this);
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
        boolean hasFeature = getPackageManager().hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)
                || getPackageManager().hasSystemFeature(PackageManager.FEATURE_LOCATION_NETWORK);
        boolean granted = AppPermissions.isLocationGranted(this);
        boolean available = hasFeature && granted;

        // Location is requested at app startup. Here the toggle is only usable once the permission
        // is held; without it the toggle is disabled and off, and the hint tells the user to grant
        // it in the system settings and fully restart the app.
        CheckBox enableGPSCheckbox = findViewById(R.id.enableGPSCheckbox);
        enableGPSCheckbox.setEnabled(available);
        enableGPSCheckbox.setChecked(available && sp.getBoolean(Constants.CONFIG.CONF_ADD_GPS_TO_FILES, Constants.ADD_GPS_TO_FILES_DEFAULT));
        findViewById(R.id.gpsPermissionHint).setVisibility(hasFeature && !granted ? View.VISIBLE : View.GONE);
        enableGPSCheckbox.setOnClickListener(v -> {
            saveBooleanPref(((CheckBox) v).isChecked(), Constants.CONFIG.CONF_ADD_GPS_TO_FILES);
            sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_GPS).setPackage(Constants.PACKAGE_NAME));
            sendBroadcast(new Intent(Constants.ACTION.ACTION_CHECK_GPS_AVAILABILITY).setPackage(Constants.PACKAGE_NAME));
        });
    }

    // Audio-processing settings are only useful with a microphone-interface spectrometer; without
    // the mic permission the section is disabled and a hint tells the user to grant it and restart.
    private void setupAudioSectionAvailability() {
        boolean micGranted = AppPermissions.isMicGranted(this);
        int[] audioControls = {
                R.id.CheckPileUp, R.id.CheckRawAudio, R.id.CheckInvert,
                R.id.inputSoundCheckbox, R.id.inputSoundText,
                R.id.decMinFrontButton, R.id.incMinFrontButton,
                R.id.decMaxFrontButton, R.id.incMaxFrontButton,
                R.id.increaseFreq, R.id.decreaseFreq
        };
        for (int id : audioControls) {
            View view = findViewById(id);
            if (view != null) {
                view.setEnabled(micGranted);
                view.setAlpha(micGranted ? 1f : 0.4f);
            }
        }
        findViewById(R.id.audioPermissionHint).setVisibility(micGranted ? View.GONE : View.VISIBLE);
    }

    // export channel compression
    private void updateExportChannelCompressionText() {
        TextView compressChannelsText = findViewById(R.id.compressChannelsText);
        compressChannelsText.setText(getString(R.string.channel_compression_format, sp.getInt(Constants.CONFIG.CONF_EXPORT_COMPRESSION, Constants.EXPORT_COMPRESSION_DEFAULT)));
    }

    public void onClick_compression_minus(View v) {
        SharedPreferences settings = PrefHelper.getASSharedPreferences(this);
        int r = settings.getInt(Constants.CONFIG.CONF_EXPORT_COMPRESSION, Constants.EXPORT_COMPRESSION_DEFAULT);

        if (r > 2) {
            r = r / 2;
        } else {
            r = 1;
        }

        saveExportChannelCompression(r);
    }

    public void onClick_compression_plus(View v) {
        SharedPreferences settings = PrefHelper.getASSharedPreferences(this);
        int r = settings.getInt(Constants.CONFIG.CONF_EXPORT_COMPRESSION, Constants.EXPORT_COMPRESSION_DEFAULT);

        if (r < 32) {
            r = r * 2;
        } else {
            r = 64;
        }

        saveExportChannelCompression(r);
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
            SharedPreferences settings = PrefHelper.getASSharedPreferences(this);
            int r = settings.getInt(Constants.CONFIG.CONF_EXPORT_COMPRESSION, Constants.EXPORT_COMPRESSION_DEFAULT);
            alert.setTitle("Channel compression " + r);
            String value = input.getText().toString();
            int intValue = r;
            try {
                intValue = Integer.parseInt(value);
            } catch (NumberFormatException nfe) {
                System.out.println("Could not parse " + nfe);
            }
            intValue = StrictMath.max(1, StrictMath.min(64, intValue));
            saveExportChannelCompression(intValue);
        });
        alert.setNegativeButton("Cancel", (dialog, whichButton) -> {
        });
        closeKeyboardOnAlertDismiss(alert);
        alert.show();
    }

    private void saveExportChannelCompression(int val) {
        AtomSpectra.exportChannelCompression = val;
        saveIntPref(val, Constants.CONFIG.CONF_EXPORT_COMPRESSION);
        updateExportChannelCompressionText();
    }

    // filename pattern
    private void updateFilenamePatternText() {
        TextView filenamePatternText = findViewById(R.id.outputFileNamePatternText);
        filenamePatternText.setText(getResources().getTextArray(R.array.file_name_array)[
                (sp.getBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_ADD_PREFIX, Constants.OUTPUT_FILE_NAME_USE_PREFIX_DEFAULT) ? 4 : 0) +
                        (sp.getBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_ADD_TIME, Constants.OUTPUT_FILE_NAME_ADD_TIME_DEFAULT) ? 2 : 0) +
                        (sp.getBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_ADD_DATE, Constants.OUTPUT_FILE_NAME_ADD_DATE_DEFAULT) ? 1 : 0)
                ]);
    }

    public void onClick_outputFilenameTemplate_minus(View v) {
        SharedPreferences settings = PrefHelper.getASSharedPreferences(this);
        boolean prefix = settings.getBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_ADD_PREFIX, Constants.OUTPUT_FILE_NAME_USE_PREFIX_DEFAULT);
        boolean date = settings.getBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_ADD_DATE, Constants.OUTPUT_FILE_NAME_ADD_DATE_DEFAULT);
        boolean time = settings.getBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_ADD_TIME, Constants.OUTPUT_FILE_NAME_ADD_TIME_DEFAULT);
        int val = (prefix ? 4 : 0) + (time ? 2 : 0) + (date ? 1 : 0);

        if (val > 0) {
            val -= 1;
        }

        saveFilenamePattern(val);
    }

    public void onClick_outputFilenameTemplate_plus(View v) {
        SharedPreferences settings = PrefHelper.getASSharedPreferences(this);
        boolean prefix = settings.getBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_ADD_PREFIX, Constants.OUTPUT_FILE_NAME_USE_PREFIX_DEFAULT);
        boolean date = settings.getBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_ADD_DATE, Constants.OUTPUT_FILE_NAME_ADD_DATE_DEFAULT);
        boolean time = settings.getBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_ADD_TIME, Constants.OUTPUT_FILE_NAME_ADD_TIME_DEFAULT);
        int val = (prefix ? 4 : 0) + (time ? 2 : 0) + (date ? 1 : 0);

        if (val < 7) {
            val += 1;
        }

        saveFilenamePattern(val);
    }

    private void saveFilenamePattern(int val) {
        SharedPreferences settings = PrefHelper.getASSharedPreferences(this);
        SharedPreferences.Editor prefEditor = settings.edit();
        prefEditor.putBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_ADD_PREFIX, (val & 4) != 0);
        prefEditor.putBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_ADD_TIME, (val & 2) != 0);
        prefEditor.putBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_ADD_DATE, (val & 1) != 0);
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
        SharedPreferences settings = PrefHelper.getASSharedPreferences(this);
        int r = sp.getInt(Constants.CONFIG.CONF_LOCALE_ID, 0);
        r = (r + 1) < Constants.LOCALES_ID.length ? r + 1 : (Constants.LOCALES_ID.length - 1);

        saveLocale(r);
    }

    public void onClick_Locale_minus(View v) {
        SharedPreferences settings = PrefHelper.getASSharedPreferences(this);
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
        SharedPreferences settings = PrefHelper.getASSharedPreferences(this);
        SharedPreferences.Editor prefEditor = settings.edit();
        prefEditor.putString(setting, value);
        prefEditor.apply();
    }

    private void saveBooleanPref(boolean value, String setting) {
        SharedPreferences settings = PrefHelper.getASSharedPreferences(this);
        SharedPreferences.Editor prefEditor = settings.edit();
        prefEditor.putBoolean(setting, value);
        prefEditor.apply();
    }

    private void saveIntPref(int value, String setting) {
        SharedPreferences settings = PrefHelper.getASSharedPreferences(this);
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
        sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_GRAPH).setPackage(Constants.PACKAGE_NAME));
    }

    @SuppressLint("WrongConstant")
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == SELECT_DIR_CODE_SETTINGS) {
            if (resultCode == RESULT_OK && (data != null)) {
                SharedPreferences settings = PrefHelper.getASSharedPreferences(this);
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
                    SharedPreferences settings = PrefHelper.getASSharedPreferences(getApplicationContext());
                    if (showPulseShape) {
                        double[] reference_pulse_data = mBundle.getDoubleArray(AtomSpectraService.EXTRA_DATA_ARRAY_DOUBLE_REFERENCE_PULSE_DATA);
                        if (reference_pulse_data == null) {
                            reference_pulse_data = new double[1024];
                        }
                        mAtomSpectraSignalView.showReferencePulse(
                                reference_pulse_data,
                                settings.getInt(Constants.CONFIG.CONF_MIN_POINTS, Constants.MIN_FRONT_POINTS_DEFAULT),
                                settings.getInt(Constants.CONFIG.CONF_MAX_POINTS, Constants.MAX_FRONT_POINTS_DEFAULT)
                        );
                    } else {
                        double[] realtime_audio_data = mBundle.getDoubleArray(AtomSpectraService.EXTRA_DATA_ARRAY_DOUBLE_REALTIME_AUDIO_DATA);
                        if (realtime_audio_data == null) {
                            realtime_audio_data = new double[1024];
                        }
                        mAtomSpectraSignalView.showOscilloscope(
                                realtime_audio_data,
                                zoom_factor
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
                SharedPreferences settings = PrefHelper.getASSharedPreferences(getApplicationContext());
                CheckBox box = findViewById(R.id.enableGPSCheckbox);
                box.setChecked(settings.getBoolean(Constants.CONFIG.CONF_ADD_GPS_TO_FILES, Constants.ADD_GPS_TO_FILES_DEFAULT));
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
        showPulseShape = false;
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
                        showPulseShape =! showPulseShape;
                        sendBroadcast(new Intent(Constants.ACTION.ACTION_CLEAR_IMPULSE).setPackage(Constants.PACKAGE_NAME));
                        sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_GRAPH).setPackage(Constants.PACKAGE_NAME));

                    } else if (detector.isSwipeRight(e1, e2, velocityX)) {
                        //showToast("Right Swipe");
                        showPulseShape =! showPulseShape;
                        sendBroadcast(new Intent(Constants.ACTION.ACTION_CLEAR_IMPULSE).setPackage(Constants.PACKAGE_NAME));
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

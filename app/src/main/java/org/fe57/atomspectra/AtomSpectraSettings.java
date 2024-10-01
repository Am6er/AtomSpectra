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
    //public static final String ATOMSPECTRA_PREFERENCES = "AtomSpectra Preferences";

    private final static int REQUEST_FINE_GPS = 501;

    public static boolean active = false;

    private AtomSpectraShapeView mAtomSpectraSignalView;

    private GestureDetector gestureDetector;
    private TextView mTextField;
    private EditText SensG, BackgroundCount, slowSens, mediumSens, fastSens;
    private SharedPreferences sp;
    private TextView doseRateFreqLabel;
    private TextView folderToStore;
    private TextView outputDevice;
    private int outputDeviceID = -1;
    private String outputDeviceName = "(none)";
    private TextView inputDevice;
    private int inputDeviceID = -1;
    private String inputDeviceName = "(none)";
    private final int SELECT_DIR_CODE_SETTINGS = 100;

    private float zoom_factor = 1;
    private int usb_noise_value = 25;
    private boolean retrySend = true;

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
        super.attachBaseContext(MyContextWrapper.wrap(newBase, lang));
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

        doseRateFreqLabel = findViewById(R.id.updateFreqLabel);

        mAtomSpectraSignalView = findViewById(R.id.signal_area);
        mAtomSpectraSignalView.setOnClickListener((View view) -> {
//      	if (AtomSpectraService.scale_factor<6) AtomSpectraService.scale_factor++;
//      									  else AtomSpectraService.scale_factor=1;

        });
        mAtomSpectraSignalView.setClickable(false);
        mTextField = findViewById(R.id.countView);
        SensG = findViewById(R.id.SensG);
        BackgroundCount = findViewById(R.id.BckgCnt);

        slowSens = findViewById(R.id.SlowSens);
        mediumSens = findViewById(R.id.MediumSens);
        fastSens = findViewById(R.id.FastSens);

        folderToStore = findViewById(R.id.directoryText);
        outputDevice = findViewById(R.id.outputSoundText);
        inputDevice = findViewById(R.id.inputSoundText);

        slowSens.setOnEditorActionListener(editorActionListener);
        mediumSens.setOnEditorActionListener(editorActionListener);
        fastSens.setOnEditorActionListener(editorActionListener);
        SensG.setOnEditorActionListener(editorActionListener);
        BackgroundCount.setOnEditorActionListener(editorActionListener);

        slowSens.setText(String.format(Locale.US, "%d", sp.getInt(Constants.CONFIG.CONF_SEARCH_SLOW, Constants.SEARCH_SLOW_DEFAULT)));
        fastSens.setText(String.format(Locale.US, "%d", sp.getInt(Constants.CONFIG.CONF_SEARCH_FAST, Constants.SEARCH_FAST_DEFAULT)));
        mediumSens.setText(String.format(Locale.US, "%d", sp.getInt(Constants.CONFIG.CONF_SEARCH_MEDIUM, Constants.SEARCH_MEDIUM_DEFAULT)));
        SensG.setText(String.format(Locale.getDefault(), "%d", sp.getInt(Constants.CONFIG.CONF_SENSG, Constants.SENSG_DEFAULT)));
        BackgroundCount.setText(String.format(Locale.getDefault(), "%d", sp.getInt(Constants.CONFIG.CONF_BACKGROUND, Constants.BACKGND_CNT_DEFAULT)));

        int freq = sp.getInt(Constants.CONFIG.CONF_DOSE_UPDATE, 1);
        doseRateFreqLabel.setText(String.format(Locale.US, "%d", freq));

        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

//        Intent audioServiceIntent = new Intent(this, AtomSpectraService.class).putExtra(Constants.FREEZE_STATE, AtomSpectraService.getFreeze());
//        startService(audioServiceIntent);
//        bindService(audioServiceIntent, mServiceConnection, BIND_AUTO_CREATE);

        //if (!AtomSpectraService.isRecording) AtomSpectraService.Start(getApplicationContext());

        initializeGestures();

        //	tmp_scale_factor = AtomSpectraService.scale_factor;
        if (AtomSpectraService.getScaleFactor() <= Constants.SCALE_MAX)
            AtomSpectraService.saveScaleFactor();
        AtomSpectraService.setScaleFactor(Constants.SCALE_COUNT_MODE);
        AtomSpectraService.requestUpdateGraph();
        //gestureDetector = initGestureDetector();

        // getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        //drop the parameter if the user drop the permission
        if (sp.getBoolean(Constants.CONFIG.CONF_ADD_GPS_TO_FILES, false)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    SharedPreferences.Editor editor = sp.edit();
                    editor.putBoolean(Constants.CONFIG.CONF_ADD_GPS_TO_FILES, false);
                    editor.apply();
                }
            }
        }

        TextView mTextField;
        mTextField = findViewById(R.id.CheckSoundText);
        if (AtomSpectraService.leftChannelInterval != 0 || AtomSpectraService.rightChannelInterval != Constants.NUM_HIST_POINTS - 1)
            mTextField.setText(getString(R.string.output_sound_format, AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().toEnergy(AtomSpectraService.leftChannelInterval), AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().toEnergy(AtomSpectraService.rightChannelInterval)));
        else
            mTextField.setText(getString(R.string.output_sound_full));
        mTextField = findViewById(R.id.channelText);
        mTextField.setText(getString(R.string.reduced_to_format, sp.getInt(Constants.CONFIG.CONF_REDUCED_TO, Constants.VIEW_CHANNELS_DEFAULT)));
        mTextField = findViewById(R.id.minFrontText);
        mTextField.setText(getString(R.string.min_front_points_format, sp.getInt(Constants.CONFIG.CONF_MIN_POINTS, Constants.MIN_FRONT_POINTS_DEFAULT)));
        mTextField = findViewById(R.id.maxFrontText);
        mTextField.setText(getString(R.string.max_front_points_format, sp.getInt(Constants.CONFIG.CONF_MAX_POINTS, Constants.MAX_FRONT_POINTS_DEFAULT)));
        mTextField = findViewById(R.id.noiseText);
        mTextField.setText(getString(R.string.noise_discriminator_format, sp.getInt(Constants.CONFIG.CONF_NOISE, Constants.NOISE_DISCRIMINATOR_DEFAULT)));
        mTextField = findViewById(R.id.ADCText);
        mTextField.setText(getString(R.string.adc_rounded_format, Constants.MinMax(sp.getInt(Constants.CONFIG.CONF_ROUNDED, Constants.ADC_DEFAULT), Constants.ADC_MIN, Constants.ADC_MAX)));
        mTextField = findViewById(R.id.saveText);
        mTextField.setText(getString(R.string.save_channels_format, Constants.MinMax(sp.getInt(Constants.CONFIG.CONF_SAVE_CHANNELS, Constants.EXPORT_CHANNELS_DEFAULT), 1024, Constants.NUM_HIST_POINTS)));
        mTextField = findViewById(R.id.loadText);
        mTextField.setText(getString(R.string.load_channels_format, Constants.MinMax(sp.getInt(Constants.CONFIG.CONF_LOAD_CHANNELS, 65536), 1024, 65536)));
        mTextField = findViewById(R.id.factorText);
        mTextField.setText(getString(R.string.max_factor_format, sp.getInt(Constants.CONFIG.CONF_MAX_POLI_FACTOR, Constants.DEFAULT_POLI_FACTOR)));
        mTextField = findViewById(R.id.smoothText);
        mTextField.setText(getString(R.string.smoothness_format, sp.getInt(Constants.CONFIG.CONF_GOLAY_WINDOW, Constants.DEFAULT_GOLAY_WINDOW)));
        mTextField = findViewById(R.id.deltaText);
        mTextField.setText(getString(R.string.delta_spectrum_format, sp.getInt(Constants.CONFIG.CONF_DELTA_TIME, Constants.DEFAULT_DELTA_TIME)));
        mTextField = findViewById(R.id.compressText);
        mTextField.setText(getString(R.string.channel_compression_format, sp.getInt(Constants.CONFIG.CONF_COMPRESSION, Constants.EXPORT_COMPRESSION_DEFAULT)));
        mTextField = findViewById(R.id.localeText);
        TextView mDataField = findViewById(R.id.autosaveNameText);
        mDataField.setText(getString(R.string.autosave_timeout, sp.getInt(Constants.CONFIG.CONF_AUTOSAVE, Constants.AUTOSAVE_DEFAULT)));
        int r = sp.getInt(Constants.CONFIG.CONF_LOCALE_ID, 0);
        r = r < Constants.LOCALES_ID.length ? r : (Constants.LOCALES_ID.length - 1);
        mTextField.setText(String.format(Locale.US,"Language: %s", Constants.LOCALES[r]));

        mTextField = findViewById(R.id.outputNameText);
        mTextField.setText(getResources().getTextArray(R.array.file_name_array)[
                        (sp.getBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_PREFIX, Constants.OUTPUT_FILE_NAME_PREFIX_DEFAULT) ? 4 : 0) +
                        (sp.getBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_TIME,   Constants.OUTPUT_FILE_NAME_TIME_DEFAULT)   ? 2 : 0) +
                        (sp.getBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_DATE,   Constants.OUTPUT_FILE_NAME_DATE_DEFAULT)   ? 1 : 0)
                ]);

        mTextField = findViewById(R.id.graphTypeText);
        CharSequence[] data = getResources().getTextArray(R.array.compress_graph_array);
        try {
            r = sp.getInt(Constants.CONFIG.CONF_COMPRESS_GRAPH, Constants.COMPRESS_GRAPH_SUM);
        } catch (Exception e) {
            r = Constants.COMPRESS_GRAPH_SUM;
        }
        r = Constants.MinMax(r, 0, data.length - 1);
        mTextField.setText(getString(R.string.compress_graph_format, data[r]));
        folderToStore.setText((Uri.parse(sp.getString(Constants.CONFIG.CONF_DIRECTORY_SELECTED, "..."))).getPath());
        //folderToStore.setEnabled(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O);

        outputDeviceID = sp.getInt(Constants.CONFIG.CONF_OUTPUT_SOUND_DEVICE_ID, -1);
        outputDeviceName = sp.getString(Constants.CONFIG.CONF_OUTPUT_SOUND_DEVICE_NAME, "(none)");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioDeviceInfo deviceOut;
            if (outputDeviceID == -1) {
                deviceOut = AtomSpectraService.getDeviceInput(getApplicationContext(), -1, null, false);
            } else {
                deviceOut = AtomSpectraService.getDeviceInput(getApplicationContext(), outputDeviceID, outputDeviceName, true);
            }
            int outputDeviceType;
            if (deviceOut == null) {
                    outputDeviceType = AudioDeviceInfo.TYPE_UNKNOWN;
            } else {
                outputDeviceType = deviceOut.getType();
            }
            outputDevice.setText(String.format(Locale.US, "%s: %s", AtomSpectraService.audioDeviceNames[Constants.MinMax(outputDeviceType, 0, AtomSpectraService.audioDeviceNames.length - 1)], outputDeviceName));
        } else {
            outputDevice.setEnabled(false);
        }

        CheckBox mCheckBox;

        mCheckBox = findViewById(R.id.CheckInvert);
        mCheckBox.setChecked(sp.getBoolean(Constants.CONFIG.CONF_INVERSION, Constants.INVERSE_DEFAULT));

        mCheckBox = findViewById(R.id.CheckPileUp);
        mCheckBox.setChecked(sp.getBoolean(Constants.CONFIG.CONF_PILE_UP, Constants.PILE_UP_DEFAULT));

        mCheckBox = findViewById(R.id.CheckGPS);
        mCheckBox.setChecked(sp.getBoolean(Constants.CONFIG.CONF_ADD_GPS_TO_FILES, false));

        mCheckBox = findViewById(R.id.UpdateIsotopes);
        mCheckBox.setChecked(sp.getBoolean(Constants.CONFIG.CONF_AUTO_UPDATE_ISOTOPES, false));

        inputDeviceID = sp.getInt(Constants.CONFIG.CONF_INPUT_SOUND_DEVICE_ID, -1);
        inputDeviceName = sp.getString(Constants.CONFIG.CONF_INPUT_SOUND_DEVICE_NAME, "(none)");
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
            inputDevice.setText(String.format(Locale.US, "%s: %s", AtomSpectraService.audioDeviceNames[Constants.MinMax(inputDeviceType, 0, AtomSpectraService.audioDeviceNames.length - 1)], inputDeviceName));
        } else {
            inputDevice.setEnabled(false);
        }
        mCheckBox = findViewById(R.id.CheckInputSound);
        mCheckBox.setChecked(sp.getBoolean(Constants.CONFIG.CONF_INPUT_SOUND, false) && !inputDeviceName.equals("(none)"));
        mCheckBox.setEnabled(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M);

        mCheckBox = findViewById(R.id.CheckSound);
        mCheckBox.setChecked(sp.getBoolean(Constants.CONFIG.CONF_OUTPUT_SOUND, false));
        mCheckBox.setEnabled(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M);

        CheckBox rawAudio = findViewById(R.id.CheckRawAudio);

        addListenerOnCheckInvert();
        addListenerOnCheckPileUp();
        addListenerOnCheckGPS();
        addListenerOnCheckAutoUpdate();
        addListenerOnSound();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            AudioManager manager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (manager != null && manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == null) {
                rawAudio.setEnabled(false);
                rawAudio.setChecked(false);
                rawAudio.setVisibility(CheckBox.INVISIBLE);
                SharedPreferences.Editor editor = sp.edit();
                editor.putInt(Constants.CONFIG.CONF_AUDIO_SOURCE, AtomSpectraService.SET_AUDIO_VOICE);
                editor.apply();
            } else {
                rawAudio.setEnabled(true);
                rawAudio.setVisibility(CheckBox.VISIBLE);
                rawAudio.setChecked(sp.getInt(Constants.CONFIG.CONF_AUDIO_SOURCE, AtomSpectraService.SET_AUDIO_VOICE) == AtomSpectraService.SET_AUDIO_RAW);
            }
        } else {
            rawAudio.setEnabled(false);
            rawAudio.setChecked(false);
            rawAudio.setVisibility(CheckBox.INVISIBLE);
            SharedPreferences.Editor editor = sp.edit();
            editor.putInt(Constants.CONFIG.CONF_AUDIO_SOURCE, AtomSpectraService.SET_AUDIO_VOICE);
            editor.apply();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mDataUpdateReceiver, makeAtomSpectraUpdateIntentFilter(), RECEIVER_NOT_EXPORTED);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(mDataUpdateReceiver, makeAtomSpectraUpdateIntentFilter(), 0);
        } else {
            registerReceiver(mDataUpdateReceiver, makeAtomSpectraUpdateIntentFilter());
        }
        //Command will not be executed if USB-serial does not exist
        sendBroadcast(new Intent(Constants.ACTION.ACTION_SEND_USB_COMMAND).
                putExtra(Constants.ACTION_PARAMETERS.USB_COMMAND_ID, SETTINGS_GET_INF_ID).
                putExtra(Constants.ACTION_PARAMETERS.USB_COMMAND_DATA, "-inf").setPackage(Constants.PACKAGE_NAME));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        switch (requestCode) {
            case REQUEST_FINE_GPS:
                SharedPreferences.Editor editor = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE).edit();
                CheckBox v = findViewById(R.id.CheckGPS);
                if (grantResults.length > 1 && (grantResults[0] == PackageManager.PERMISSION_GRANTED || grantResults[1] == PackageManager.PERMISSION_GRANTED)) {
                    editor.putBoolean(Constants.CONFIG.CONF_ADD_GPS_TO_FILES, true);
                    v.setChecked(true);
                    sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_GPS).setPackage(Constants.PACKAGE_NAME));
                } else {
                    editor.putBoolean(Constants.CONFIG.CONF_ADD_GPS_TO_FILES, false);
                    Toast.makeText(this, getString(R.string.perm_no_gps), Toast.LENGTH_LONG).show();
                    v.setChecked(false);
                }
                editor.apply();
                sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_SETTINGS).setPackage(Constants.PACKAGE_NAME));
                break;
            default:
                super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    public void onClick_increaseFreq(View v) {
        int freq = sp.getInt(Constants.CONFIG.CONF_DOSE_UPDATE, Constants.UPDATE_DOSE_DEFAULT);
        switch (freq) {
            case 1:
                freq = 2;
                break;
            case 2:
                freq = 4;
                break;
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
        sp.edit().putInt(Constants.CONFIG.CONF_DOSE_UPDATE, freq).apply();
        doseRateFreqLabel.setText(String.format(Locale.US, "%d", freq));
    }

    public void onClick_decreaseFreq(View v) {
        int freq = sp.getInt(Constants.CONFIG.CONF_DOSE_UPDATE, Constants.UPDATE_DOSE_DEFAULT);
        switch (freq) {
            case 4:
                freq = 2;
                break;
            case 5:
                freq = 4;
                break;
            case 10:
                freq = 5;
                break;
            default:
                freq = 1;
                break;
        }
        sp.edit().putInt(Constants.CONFIG.CONF_DOSE_UPDATE, freq).apply();
        doseRateFreqLabel.setText(String.format(Locale.US, "%d", freq));
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            AtomSpectraService.restoreScaleFactor();
            AtomSpectraService.requestUpdateGraph();
            finish();
//            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private final EditText.OnEditorActionListener editorActionListener = new TextView.OnEditorActionListener() {
        @Override
        public boolean onEditorAction(TextView textView, int i, KeyEvent keyEvent) {
            if (i == EditorInfo.IME_ACTION_DONE) {
                try {
                    Number sensNumber = NumberFormat.getNumberInstance().parse(SensG.getText().toString());
                    Number backgroundNumber = NumberFormat.getNumberInstance().parse(BackgroundCount.getText().toString());
                    if (sensNumber != null && backgroundNumber != null) {
                        SharedPreferences.Editor editor = sp.edit();
                        editor.putInt(Constants.CONFIG.CONF_SENSG, sensNumber.intValue());
                        editor.putInt(Constants.CONFIG.CONF_BACKGROUND, backgroundNumber.intValue());
                        editor.putInt(Constants.CONFIG.CONF_SEARCH_SLOW, Integer.parseInt(slowSens.getText().toString()));
                        editor.putInt(Constants.CONFIG.CONF_SEARCH_FAST, Integer.parseInt(fastSens.getText().toString()));
                        editor.putInt(Constants.CONFIG.CONF_SEARCH_MEDIUM, Integer.parseInt(mediumSens.getText().toString()));
                        editor.apply();
                    }
//                    sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_DATA));
                } catch (Exception e) {
                    //
                }
            }
            return false;
        }
    };

    public void addListenerOnCheckInvert() {
        CheckBox checkInvert = findViewById(R.id.CheckInvert);
        checkInvert.setOnClickListener(v -> {
            SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
            sendBroadcast(new Intent(Constants.ACTION.ACTION_CLEAR_SPECTRUM).setPackage(Constants.PACKAGE_NAME));
            boolean set_inversion = ((CheckBox) v).isChecked();
            SharedPreferences.Editor prefEditor = settings.edit();
            prefEditor.putBoolean(Constants.CONFIG.CONF_INVERSION, set_inversion);
            prefEditor.apply();
        });
    }

    public void addListenerOnCheckAutoUpdate() {
        CheckBox checkUpdate = findViewById(R.id.UpdateIsotopes);
        checkUpdate.setOnClickListener(v -> {
            SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
            AtomSpectraIsotopes.autoUpdateIsotopes = ((CheckBox) v).isChecked();
            SharedPreferences.Editor prefEditor = settings.edit();
            prefEditor.putBoolean(Constants.CONFIG.CONF_AUTO_UPDATE_ISOTOPES, AtomSpectraIsotopes.autoUpdateIsotopes);
            prefEditor.apply();
        });
    }

    public void addListenerOnCheckGPS() {
        CheckBox checkGPS = findViewById(R.id.CheckGPS);
        final Activity id = this;
        checkGPS.setOnClickListener(v -> {
            //Toast.makeText(AtomSpectraSettings.this,"inversion checked", Toast.LENGTH_LONG).show();
            SharedPreferences.Editor prefEditor = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE).edit();
            boolean res = ((CheckBox) v).isChecked();
            if (res) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                        // Permission is not granted
                        //When permission is not granted by user, show them message why this permission is needed.
                        //if (ActivityCompat.shouldShowRequestPermissionRationale(id, Manifest.permission.ACCESS_FINE_LOCATION) ) {
                            final AlertDialog.Builder alert = new AlertDialog.Builder(id)
                                    .setTitle(getString(R.string.perm_ask_fine_gps_title))
                                    .setMessage(getString(R.string.perm_ask_fine_gps_text))
                                    .setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                                        //Give user option to still opt-in the permissions
//                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
//                                            ActivityCompat.requestPermissions(id, new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION}, REQUEST_FINE_GPS);
//                                        else
                                        ActivityCompat.requestPermissions(id, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_FINE_GPS);
                                    });
                            alert.show();
                        //} else {
//                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
//                                ActivityCompat.requestPermissions(id, new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION}, REQUEST_FINE_GPS);
//                            else
                        //    ActivityCompat.requestPermissions(id, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_FINE_GPS);
                        //}
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

    public void addListenerOnCheckPileUp() {
        CheckBox checkInvert = findViewById(R.id.CheckPileUp);
        checkInvert.setOnClickListener(v -> {
            SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
            sendBroadcast(new Intent(Constants.ACTION.ACTION_CLEAR_SPECTRUM).setPackage(Constants.PACKAGE_NAME));
            boolean set_pile = ((CheckBox) v).isChecked();
            SharedPreferences.Editor prefEditor = settings.edit();
            prefEditor.putBoolean(Constants.CONFIG.CONF_PILE_UP, set_pile);
            prefEditor.apply();
        });
    }

    public void addListenerOnSound() {
        CheckBox checkOutput = findViewById(R.id.CheckSound);
        checkOutput.setOnClickListener(v -> {
            SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
            boolean outputSound = ((CheckBox) v).isChecked();
            SharedPreferences.Editor prefEditor = settings.edit();
            prefEditor.putBoolean(Constants.CONFIG.CONF_OUTPUT_SOUND, outputSound);
            prefEditor.apply();
            sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_MENU).setPackage(Constants.PACKAGE_NAME));
        });
        CheckBox checkInput = findViewById(R.id.CheckInputSound);
        checkInput.setOnClickListener(v -> {
            SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
            boolean inputSound = ((CheckBox) v).isChecked();
            String inputName = settings.getString(Constants.CONFIG.CONF_INPUT_SOUND_DEVICE_NAME, "(none)");
            if (inputName == null)
                inputName = "(none)";
            if (inputName.equals("(none)")) {
                inputSound = false;
                ((CheckBox) v).setChecked(false);
            }
            SharedPreferences.Editor prefEditor = settings.edit();
            prefEditor.putBoolean(Constants.CONFIG.CONF_INPUT_SOUND, inputSound);
            prefEditor.apply();
        });
    }

    public void onSelectAudioClick(View v) {
        SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        sendBroadcast(new Intent(Constants.ACTION.ACTION_CLEAR_SPECTRUM).setPackage(Constants.PACKAGE_NAME));
        AtomSpectraService.SetAudioSource = ((CheckBox) v).isChecked() ? AtomSpectraService.SET_AUDIO_RAW : AtomSpectraService.SET_AUDIO_VOICE;
        SharedPreferences.Editor prefEditor = settings.edit();
        prefEditor.putInt(Constants.CONFIG.CONF_AUDIO_SOURCE, ((CheckBox) v).isChecked() ? AtomSpectraService.SET_AUDIO_RAW : AtomSpectraService.SET_AUDIO_VOICE);
        prefEditor.apply();
    }

    public void onSelectDirectoryClick(View v) {
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
// get path
                    File folder = new File(Environment.getExternalStorageDirectory() + "/AtomSpectra");
// create folder if not exist
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

    public void onSelectAudioOutputClick(View v) {
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
            outputDevice.setText(String.format(Locale.US, "%s: %s", AtomSpectraService.audioDeviceNames[Constants.MinMax(outputDeviceType, 0, AtomSpectraService.audioDeviceNames.length - 1)], outputDeviceName));
        } else {
            outputDevice.setEnabled(false);
        }
    }

    public void onSelectAudioInputClick(View v) {
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
            inputDevice.setText(String.format(Locale.US, "%s: %s", AtomSpectraService.audioDeviceNames[Constants.MinMax(inputDeviceType, 0, AtomSpectraService.audioDeviceNames.length - 1)], inputDeviceName));
        } else {
            inputDevice.setEnabled(false);
        }
    }

    public void onSelectIntervalClick(View v) {
        final AlertDialog.Builder alert = new AlertDialog.Builder(this);

        alert.setTitle(getString(R.string.ask_select_interval_title));
        alert.setMessage(getString(R.string.ask_select_interval_text));

        final EditText input = new EditText(this);
        input.setKeyListener(new NumberKeyListener() {
            @NonNull
            @Override
            protected char[] getAcceptedChars() {
                return new char[]{'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '.', ',', '-'};
            }

            @Override
            public int getInputType() {
                return InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_VARIATION_NORMAL;
            }
        });
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        alert.setView(input);

        alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
            String value = input.getText().toString();
            if ("-1".equals(value)) {
                AtomSpectraService.resetInterval();
                TextView e = (TextView)v;
                e.setText(getString(R.string.output_sound_full));
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
                        TextView e = (TextView) v;
                        if (AtomSpectraService.leftChannelInterval != 0 || AtomSpectraService.rightChannelInterval != Constants.NUM_HIST_POINTS - 1)
                            e.setText(getString(R.string.output_sound_format, AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().toEnergy(AtomSpectraService.leftChannelInterval), AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().toEnergy(AtomSpectraService.rightChannelInterval)));
                        else
                            e.setText(getString(R.string.output_sound_full));
                    }
                    catch (Exception ignored) {

                    }
                }
            }
        });
        alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
            // nothing.
        });
        alert.show();
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
// Check for the freshest data.
                    getContentResolver().takePersistableUriPermission(uri, takeFlags);
                    folderToStore.setText(uri.getPath());
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
        intentFilter.addAction(Constants.ACTION.ACTION_HAS_ANSWER);
        return intentFilter;
    }

    private final BroadcastReceiver mDataUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (AtomSpectraService.ACTION_DATA_AVAILABLE.equals(action) && active) {
                Bundle mBundle = intent.getExtras();
                if (mBundle != null) {
                    double[] histogram = mBundle.getDoubleArray(AtomSpectraService.EXTRA_DATA_ARRAY_LONG_COUNTS);
                    double[] hist_back = mBundle.getDoubleArray(AtomSpectraService.EXTRA_DATA_ARRAY_BACK_COUNTS);
                    int cps = mBundle.getInt(AtomSpectraService.EXTRA_DATA_INT_CPS);
                    int cps_interval = mBundle.getInt(AtomSpectraService.EXTRA_DATA_INT_CPS_INTERVAL);
                    mTextField.setText(getString(R.string.cps_show, cps, cps_interval));
                    SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
                    if (AtomSpectraService.getScaleFactor() == Constants.SCALE_COUNT_MODE)
                        mAtomSpectraSignalView.showShape(
                                histogram,
                                hist_back,
                                false,
                                false,
                                false,
                                1024,
                                128,
                                false,
                                false,
                                0,
                                1024,
                                getString(R.string.graph_show_points),
                                zoom_factor,
                                AtomSpectraService.getScaleFactor(),
                                true,
                                -1,
                                settings.getInt(Constants.CONFIG.CONF_MIN_POINTS, Constants.MIN_FRONT_POINTS_DEFAULT),
                                settings.getInt(Constants.CONFIG.CONF_MAX_POINTS, Constants.MAX_FRONT_POINTS_DEFAULT));
                    else
                        mAtomSpectraSignalView.showShape(
                                histogram,
                                hist_back,
                                false,
                                false,
                                false,
                                1024,
                                256,
                                false,
                                false,
                                0,
                                256,
                                getString(R.string.graph_show_points),
                                zoom_factor,
                                AtomSpectraService.getScaleFactor(),
                                true,
                                -1,
                                settings.getInt(Constants.CONFIG.CONF_MIN_POINTS, Constants.MIN_FRONT_POINTS_DEFAULT),
                                settings.getInt(Constants.CONFIG.CONF_MAX_POINTS, Constants.MAX_FRONT_POINTS_DEFAULT));


                    //	Log.d(TAG, "data received from service  "+ String.valueOf(array_length));
                }
            }
            if (Constants.ACTION.ACTION_CLOSE_SETTINGS.equals(action)) {
                AtomSpectraService.restoreScaleFactor();
                AtomSpectraService.requestUpdateGraph();
                finish();
            }
            if (Constants.ACTION.ACTION_HAS_ANSWER.equals(action)) {
                String id = intent.getStringExtra(AtomSpectraSerial.EXTRA_ID);
                String data = intent.getStringExtra(AtomSpectraSerial.EXTRA_RESULT);
                if (SETTINGS_GET_INF_ID.equals(id)) {
                    if (data != null) {
                        String val = AtomSpectraSerial.getParameter(data, "NOISE");
                        if (val != null) {
                            try {
                                TextView mText = findViewById(R.id.noiseText);
                                mText.setText(getString(R.string.noise_discriminator_format, Integer.parseInt(val)));
                                retrySend = true;
                                usb_noise_value = Integer.parseInt(val);
                            }
                            catch (NumberFormatException nfe) {
                                //nothing
                            }
                        } else {
                            if (retrySend) {
                                retrySend = false;
                                //Retry a command to ensure it is not a device failure
                                sendBroadcast(new Intent(Constants.ACTION.ACTION_SEND_USB_COMMAND).
                                        putExtra(Constants.ACTION_PARAMETERS.USB_COMMAND_ID, SETTINGS_GET_INF_ID).
                                        putExtra(Constants.ACTION_PARAMETERS.USB_COMMAND_DATA, "-inf").setPackage(Constants.PACKAGE_NAME));
                            } else {
                                //Skip command resending
                                retrySend = true;
                            }
                        }
                    }
                } else if (SETTINGS_SET_NOISE_ID.equals(id)) {
                    if (AtomSpectraSerial.COMMAND_RESULT_OK.equals(data)) {
                        TextView mText = findViewById(R.id.noiseText);
                        mText.setText(getString(R.string.noise_discriminator_format, usb_noise_value));
                    }
                }
            }
            if (Constants.ACTION.ACTION_AUDIO_CHANGED.equals(action)) {
                CheckBox mCheckBox;
                outputDeviceID = sp.getInt(Constants.CONFIG.CONF_OUTPUT_SOUND_DEVICE_ID, -1);
                outputDeviceName = sp.getString(Constants.CONFIG.CONF_OUTPUT_SOUND_DEVICE_NAME, "(none)");

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
                    outputDevice.setText(String.format(Locale.US, "%s: %s", AtomSpectraService.audioDeviceNames[Constants.MinMax(outputDeviceType, 0, AtomSpectraService.audioDeviceNames.length - 1)], outputDeviceName));
                } else {
                    outputDevice.setEnabled(false);
                }
                mCheckBox = findViewById(R.id.CheckSound);
                if (!sp.getBoolean(Constants.CONFIG.CONF_OUTPUT_SOUND, false))
                    mCheckBox.setChecked(false);
                inputDeviceID = sp.getInt(Constants.CONFIG.CONF_INPUT_SOUND_DEVICE_ID, -1);
                inputDeviceName = sp.getString(Constants.CONFIG.CONF_INPUT_SOUND_DEVICE_NAME, "(none)");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    AudioDeviceInfo deviceIn;
                    if (inputDeviceID == -1) {
                        deviceIn = AtomSpectraService.getDeviceInput(getApplicationContext(), -1, null, false);
                    } else {
                        deviceIn = AtomSpectraService.getDeviceInput(getApplicationContext(), inputDeviceID, inputDeviceName, true);
                    }
                    int inputDeviceType;
                    if (deviceIn == null) {
                            inputDeviceType = AudioDeviceInfo.TYPE_UNKNOWN;
                    } else {
                        inputDeviceType = deviceIn.getType();
                    }
                    inputDevice.setText(String.format(Locale.US, "%s: %s", AtomSpectraService.audioDeviceNames[Constants.MinMax(inputDeviceType, 0, AtomSpectraService.audioDeviceNames.length - 1)], inputDeviceName));
                } else {
                    inputDevice.setEnabled(false);
                }
                mCheckBox = findViewById(R.id.CheckInputSound);
                if (!sp.getBoolean(Constants.CONFIG.CONF_INPUT_SOUND, false))
                    mCheckBox.setChecked(false);
            }
            if (Constants.ACTION.ACTION_UPDATE_SETTINGS.equals(action)) {
                SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
                CheckBox box = findViewById(R.id.CheckGPS);
                box.setChecked(settings.getBoolean(Constants.CONFIG.CONF_ADD_GPS_TO_FILES, false));
                if (AtomSpectraService.inputType != AtomSpectraService.INPUT_SERIAL) {
                    TextView mText = findViewById(R.id.noiseText);
                    mText.setText(getString(R.string.noise_discriminator_format, sp.getInt(Constants.CONFIG.CONF_NOISE, Constants.NOISE_DISCRIMINATOR_DEFAULT)));
                } else {
                    sendBroadcast(new Intent(Constants.ACTION.ACTION_SEND_USB_COMMAND).
                            putExtra(Constants.ACTION_PARAMETERS.USB_COMMAND_ID, SETTINGS_GET_INF_ID).
                            putExtra(Constants.ACTION_PARAMETERS.USB_COMMAND_DATA, "-inf").setPackage(Constants.PACKAGE_NAME));
                }
            }
        }

    };

    @Override
    public void onStart() {
        super.onStart();
        //	if (!AtomSpectraService.isRecording) AtomSpectraService.Start(getApplicationContext());
        Log.d(TAG, "-XxX-  onStart");
    }

    @Override
    protected void onResume() {
        super.onResume();
        active = true;
        AtomSpectraService.setScaleFactor(Constants.SCALE_COUNT_MODE);
        //ContextCompat.registerReceiver(mDataUpdateReceiver, makeAtomSpectraUpdateIntentFilter());
        //  if (!AtomSpectraService.isRecording) AtomSpectraService.Start(getApplicationContext());
        Log.d(TAG, "registerReceiver");

    }

    @Override
    protected void onPause() {
        super.onPause();
        active = false;
        Log.d(TAG, "-XxX-  pause");
        //AtomSpectraService.scale_factor = tmp_scale_factor;
        AtomSpectraService.restoreScaleFactor();
        AtomSpectraService.requestUpdateGraph();
    }

    @Override
    protected void onStop() {
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
//        unbindService(mServiceConnection);
        unregisterReceiver(mDataUpdateReceiver);
    }

    // Code to manage Service lifecycle.
//    private final ServiceConnection mServiceConnection = new ServiceConnection() {
//
//        @Override
//        public void onServiceConnected(ComponentName componentName, IBinder service) {
//            //mAtomSpectraService = ((AtomSpectraService.LocalBinder) service).getService();
//            //if (!AtomSpectraService.initialize()) {
//            //    Log.e(TAG, "Unable to initialize Bluetooth");
//            //    finish();
//            //}
//            // Automatically connects to the device upon successful start-up initialization.
//            //if (mAtomSpectraService.getConnectionState()==0)
//            //if (!mConnected) mBluetoothLeService.connect(mDeviceAddress);
//            //AtomSpectraService.Start(getApplicationContext());
//        }
//
//        @Override
//        public void onServiceDisconnected(ComponentName componentName) {
//            //mAtomSpectraService = null;
//        }
//    };

    public void onClick_reducedTo_plus(View v) {
        SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        int reducedTo = settings.getInt(Constants.CONFIG.CONF_REDUCED_TO, Constants.VIEW_CHANNELS_DEFAULT);
        if (reducedTo < 1024) reducedTo *= 2;
        SharedPreferences.Editor prefEditor = settings.edit();
        prefEditor.putInt(Constants.CONFIG.CONF_REDUCED_TO, reducedTo);
        prefEditor.apply();

        TextView mDataField = findViewById(R.id.channelText);
        mDataField.setText(getString(R.string.reduced_to_format, reducedTo));
    }

    public void onClick_reducedTo_minus(View v) {
        SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        int reducedTo = settings.getInt(Constants.CONFIG.CONF_REDUCED_TO, Constants.VIEW_CHANNELS_DEFAULT);
        if (reducedTo > 128) reducedTo /= 2;
        SharedPreferences.Editor prefEditor = settings.edit();
        prefEditor.putInt(Constants.CONFIG.CONF_REDUCED_TO, reducedTo);
        prefEditor.apply();

        TextView mDataField = findViewById(R.id.channelText);
        mDataField.setText(getString(R.string.reduced_to_format, reducedTo));
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

//        AtomSpectraService.frontCountsMin = r;
        AtomSpectraService.setScaleFactor(Constants.SCALE_IMPULSE_MODE);

        SharedPreferences.Editor prefEditor = settings.edit();
        prefEditor.putInt(Constants.CONFIG.CONF_MIN_POINTS, r);
        prefEditor.apply();

        TextView mDataField = findViewById(R.id.minFrontText);
        mDataField.setText(getString(R.string.min_front_points_format, r));
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

//        AtomSpectraService.frontCountsMin = r;
        AtomSpectraService.setScaleFactor(Constants.SCALE_IMPULSE_MODE);

        SharedPreferences.Editor prefEditor = settings.edit();
        prefEditor.putInt(Constants.CONFIG.CONF_MIN_POINTS, r);
        prefEditor.apply();

        TextView mDataField = findViewById(R.id.minFrontText);
        mDataField.setText(getString(R.string.min_front_points_format, r));
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

//        AtomSpectraService.frontCountsMax = r;
        AtomSpectraService.setScaleFactor(Constants.SCALE_IMPULSE_MODE);

        SharedPreferences.Editor prefEditor = settings.edit();
        prefEditor.putInt(Constants.CONFIG.CONF_MAX_POINTS, r);
        prefEditor.apply();

        TextView mDataField = findViewById(R.id.maxFrontText);
        mDataField.setText(getString(R.string.max_front_points_format, r));
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

//        AtomSpectraService.frontCountsMax = r;
        AtomSpectraService.setScaleFactor(Constants.SCALE_IMPULSE_MODE);

        SharedPreferences.Editor prefEditor = settings.edit();
        prefEditor.putInt(Constants.CONFIG.CONF_MAX_POINTS, r);
        prefEditor.apply();

        TextView mDataField = findViewById(R.id.maxFrontText);
        mDataField.setText(getString(R.string.max_front_points_format, r));
    }

    public void onClick_noise_minus(View v) {
        if (AtomSpectraService.inputType != AtomSpectraService.INPUT_SERIAL) {
            SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
            int r = settings.getInt(Constants.CONFIG.CONF_NOISE, Constants.NOISE_DISCRIMINATOR_DEFAULT);

            if (r > 0) {
                r--;
                sendBroadcast(new Intent(Constants.ACTION.ACTION_CLEAR_SPECTRUM).setPackage(Constants.PACKAGE_NAME));
            }

//            AtomSpectraService.histogramMinChannel = r;

            SharedPreferences.Editor prefEditor = settings.edit();
            prefEditor.putInt(Constants.CONFIG.CONF_NOISE, r);
            prefEditor.apply();
            TextView mDataField = findViewById(R.id.noiseText);
            mDataField.setText(getString(R.string.noise_discriminator_format, r));
        } else {
            if (usb_noise_value > 0) {
                usb_noise_value--;
                sendBroadcast(new Intent(Constants.ACTION.ACTION_CLEAR_SPECTRUM).setPackage(Constants.PACKAGE_NAME));
            }
            sendBroadcast(new Intent(Constants.ACTION.ACTION_SEND_USB_COMMAND).
                    putExtra(Constants.ACTION_PARAMETERS.USB_COMMAND_ID, SETTINGS_SET_NOISE_ID).
                    putExtra(Constants.ACTION_PARAMETERS.USB_COMMAND_DATA, "-nos " + usb_noise_value).setPackage(Constants.PACKAGE_NAME));
        }
    }

    public void onClick_noise_plus(View v) {
        if (AtomSpectraService.inputType != AtomSpectraService.INPUT_SERIAL) {
            SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
            int r = settings.getInt(Constants.CONFIG.CONF_NOISE, Constants.NOISE_DISCRIMINATOR_DEFAULT);

            if (r < (Constants.NUM_HIST_POINTS / 4)) {
                r++;
                sendBroadcast(new Intent(Constants.ACTION.ACTION_CLEAR_SPECTRUM).setPackage(Constants.PACKAGE_NAME));
            }

//            AtomSpectraService.histogramMinChannel = r;

            SharedPreferences.Editor prefEditor = settings.edit();
            prefEditor.putInt(Constants.CONFIG.CONF_NOISE, r);
            prefEditor.apply();
            TextView mDataField = findViewById(R.id.noiseText);
            mDataField.setText(getString(R.string.noise_discriminator_format, r));
        } else {
            if (usb_noise_value < (Constants.NUM_HIST_POINTS / 4)) {
                usb_noise_value++;
                sendBroadcast(new Intent(Constants.ACTION.ACTION_CLEAR_SPECTRUM).setPackage(Constants.PACKAGE_NAME));
            }
            sendBroadcast(new Intent(Constants.ACTION.ACTION_SEND_USB_COMMAND).
                    putExtra(Constants.ACTION_PARAMETERS.USB_COMMAND_ID, SETTINGS_SET_NOISE_ID).
                    putExtra(Constants.ACTION_PARAMETERS.USB_COMMAND_DATA, "-nos " + usb_noise_value).setPackage(Constants.PACKAGE_NAME));
        }
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
            if (AtomSpectraService.inputType != AtomSpectraService.INPUT_SERIAL) {
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
                TextView mDataField = findViewById(R.id.noiseText);
                mDataField.setText(getString(R.string.noise_discriminator_format, intValue));
                SharedPreferences.Editor prefEditor = settings.edit();
                prefEditor.putInt(Constants.CONFIG.CONF_NOISE, intValue);
                prefEditor.apply();
            } else {
                String value = input.getText().toString();
                int intValue = usb_noise_value;
                try {
                    intValue = Integer.parseInt(value);
                } catch (NumberFormatException nfe) {
                    System.out.println("Could not parse " + nfe);
                }
                usb_noise_value = StrictMath.max(0, StrictMath.min(Constants.NUM_HIST_POINTS / 4, intValue));
                sendBroadcast(new Intent(Constants.ACTION.ACTION_SEND_USB_COMMAND).
                        putExtra(Constants.ACTION_PARAMETERS.USB_COMMAND_ID, SETTINGS_SET_NOISE_ID).
                        putExtra(Constants.ACTION_PARAMETERS.USB_COMMAND_DATA, "-nos " + intValue).setPackage(Constants.PACKAGE_NAME));
            }
            sendBroadcast(new Intent(Constants.ACTION.ACTION_CLEAR_SPECTRUM).setPackage(Constants.PACKAGE_NAME));
        });
        alert.setNegativeButton("Cancel", (dialog, whichButton) -> {
            // nothing.
        });
        alert.show();
    }

    public void onClick_roundedTo_minus(View v) {
        SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        int r = Constants.MinMax(settings.getInt(Constants.CONFIG.CONF_ROUNDED, Constants.ADC_DEFAULT), Constants.ADC_MIN, Constants.ADC_MAX);

        if (r > Constants.ADC_MIN) {
            r--;
            if (!AtomSpectraService.getFreeze())
                sendBroadcast(new Intent(Constants.ACTION.ACTION_CLEAR_SPECTRUM).setPackage(Constants.PACKAGE_NAME));
        }

//        AtomSpectraService.adc_effective_bits = r;

        SharedPreferences.Editor prefEditor = settings.edit();
        prefEditor.putInt(Constants.CONFIG.CONF_ROUNDED, r);
        prefEditor.apply();

        TextView mDataField = findViewById(R.id.ADCText);
        mDataField.setText(getString(R.string.adc_rounded_format, r));
    }

    public void onClick_roundedTo_plus(View v) {
        SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        int r = Constants.MinMax(settings.getInt(Constants.CONFIG.CONF_ROUNDED, Constants.ADC_DEFAULT), Constants.ADC_MIN, Constants.ADC_MAX);

        if (r < Constants.ADC_MAX) {
            r++;
            if (!AtomSpectraService.getFreeze())
                sendBroadcast(new Intent(Constants.ACTION.ACTION_CLEAR_SPECTRUM).setPackage(Constants.PACKAGE_NAME));
        }

//        AtomSpectraService.adc_effective_bits = r;

        SharedPreferences.Editor prefEditor = settings.edit();
        prefEditor.putInt(Constants.CONFIG.CONF_ROUNDED, r);
        prefEditor.apply();

        TextView mDataField = findViewById(R.id.ADCText);
        mDataField.setText(getString(R.string.adc_rounded_format, r));
    }

    public void onClick_save_channels_minus(View v) {
        SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        int r = Constants.MinMax(settings.getInt(Constants.CONFIG.CONF_SAVE_CHANNELS, Constants.EXPORT_CHANNELS_DEFAULT), 1024, Constants.NUM_HIST_POINTS);

        if (r > 2048) {
            r = r / 2;
        } else {
            r = 1024;
        }

        SharedPreferences.Editor prefEditor = settings.edit();
        prefEditor.putInt(Constants.CONFIG.CONF_SAVE_CHANNELS, r);
        prefEditor.apply();
        AtomSpectra.saveChannels = r;

        TextView mDataField = findViewById(R.id.saveText);
        mDataField.setText(getString(R.string.save_channels_format, r));
    }

    public void onClick_save_channels_plus(View v) {
        SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        int r = Constants.MinMax(settings.getInt(Constants.CONFIG.CONF_SAVE_CHANNELS, Constants.EXPORT_CHANNELS_DEFAULT), 1024, Constants.NUM_HIST_POINTS);

        if (r < (Constants.NUM_HIST_POINTS / 2)) {
            r = r * 2;
        } else {
            r = Constants.NUM_HIST_POINTS;
        }

        SharedPreferences.Editor prefEditor = settings.edit();
        prefEditor.putInt(Constants.CONFIG.CONF_SAVE_CHANNELS, r);
        prefEditor.apply();
        AtomSpectra.saveChannels = r;

        TextView mDataField = findViewById(R.id.saveText);
        mDataField.setText(getString(R.string.save_channels_format, r));
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
            TextView mDataField = findViewById(R.id.saveText);
            mDataField.setText(getString(R.string.save_channels_format, intValue));
            AtomSpectra.saveChannels = intValue;

            //SharedPreferences settings = getSharedPreferences(ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
            SharedPreferences.Editor prefEditor = settings.edit();
            prefEditor.putInt(Constants.CONFIG.CONF_SAVE_CHANNELS, intValue);
            prefEditor.apply();


        });
        alert.setNegativeButton("Cancel", (dialog, whichButton) -> {
            // comment
        });
        alert.show();
    }

    public void onClick_load_channels_minus(View v) {
        SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        int r = settings.getInt(Constants.CONFIG.CONF_LOAD_CHANNELS, 65536);

        if (r > 2048) {
            r = r / 2;
        } else {
            r = 1024;
        }

        SharedPreferences.Editor prefEditor = settings.edit();
        prefEditor.putInt(Constants.CONFIG.CONF_LOAD_CHANNELS, r);
        prefEditor.apply();
        AtomSpectra.loadChannels = r;

        TextView mDataField = findViewById(R.id.loadText);
        mDataField.setText(getString(R.string.load_channels_format, r));
    }

    public void onClick_load_channels_plus(View v) {
        SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        int r = settings.getInt(Constants.CONFIG.CONF_LOAD_CHANNELS, 65536);

        if (r < 32768) {
            r = r * 2;
        } else {
            r = 65536;
        }

        SharedPreferences.Editor prefEditor = settings.edit();
        prefEditor.putInt(Constants.CONFIG.CONF_LOAD_CHANNELS, r);
        prefEditor.apply();
        AtomSpectra.loadChannels = r;

        TextView mDataField = findViewById(R.id.loadText);
        mDataField.setText(getString(R.string.load_channels_format, r));
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
            TextView mDataField = findViewById(R.id.loadText);
            mDataField.setText(getString(R.string.load_channels_format, intValue));
            AtomSpectra.loadChannels = intValue;

            //SharedPreferences settings = getSharedPreferences(ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
            SharedPreferences.Editor prefEditor = settings.edit();
            prefEditor.putInt(Constants.CONFIG.CONF_LOAD_CHANNELS, intValue);
            prefEditor.apply();


        });
        alert.setNegativeButton("Cancel", (dialog, whichButton) -> {
            // comment
        });
        alert.show();
    }

    public void onClick_Factor_minus(View v) {
        SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        int r = settings.getInt(Constants.CONFIG.CONF_MAX_POLI_FACTOR, Constants.DEFAULT_POLI_FACTOR);

        if (r > 1) {
            r = r - 1;
        } else {
            r = 1;
        }

        SharedPreferences.Editor prefEditor = settings.edit();
        prefEditor.putInt(Constants.CONFIG.CONF_MAX_POLI_FACTOR, r);
        prefEditor.apply();
        if (AtomSpectraService.newCalibration.getLines() > 1)
            AtomSpectraService.newCalibration.Calculate(r);
//        AtomSpectra.channelCompression = r;

        TextView mDataField = findViewById(R.id.factorText);
        mDataField.setText(getString(R.string.max_factor_format, r));
    }

    public void onClick_Factor_plus(View v) {
        SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        int r = settings.getInt(Constants.CONFIG.CONF_MAX_POLI_FACTOR, Constants.DEFAULT_POLI_FACTOR);

        if (r < 4) {
            r = r + 1;
        } else {
            r = 4;
        }

        SharedPreferences.Editor prefEditor = settings.edit();
        prefEditor.putInt(Constants.CONFIG.CONF_MAX_POLI_FACTOR, r);
        prefEditor.apply();
        if (AtomSpectraService.newCalibration.getLines() > 1)
            AtomSpectraService.newCalibration.Calculate(r);
//        AtomSpectra.channelCompression = r;

        TextView mDataField = findViewById(R.id.factorText);
        mDataField.setText(getString(R.string.max_factor_format, r));
    }

    public void onClick_Smoothness_minus(View v) {
        SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        int r = settings.getInt(Constants.CONFIG.CONF_GOLAY_WINDOW, Constants.DEFAULT_GOLAY_WINDOW);

        if (r > 1) {
            r = r - 1;
        } else {
            r = 1;
        }

        SharedPreferences.Editor prefEditor = settings.edit();
        prefEditor.putInt(Constants.CONFIG.CONF_GOLAY_WINDOW, r);
        prefEditor.apply();
//        AtomSpectra.channelCompression = r;

        TextView mDataField = findViewById(R.id.smoothText);
        mDataField.setText(getString(R.string.smoothness_format, r));
    }

    public void onClick_Smoothness_plus(View v) {
        SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        int r = settings.getInt(Constants.CONFIG.CONF_GOLAY_WINDOW, Constants.DEFAULT_GOLAY_WINDOW);

        if (r < 5) {
            r = r + 1;
        } else {
            r = 5;
        }

        SharedPreferences.Editor prefEditor = settings.edit();
        prefEditor.putInt(Constants.CONFIG.CONF_GOLAY_WINDOW, r);
        prefEditor.apply();
//        AtomSpectra.channelCompression = r;

        TextView mDataField = findViewById(R.id.smoothText);
        mDataField.setText(getString(R.string.smoothness_format, r));
    }

    public void onClick_Delta_Time_minus(View v) {
        SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        int r = settings.getInt(Constants.CONFIG.CONF_DELTA_TIME, Constants.DEFAULT_DELTA_TIME);

        if (r > 60) {
            r = r - 30;
        } else if (r > 10) {
            r = r - 5;
        } else if (r > 1) {
            r = r - 1;
        } else {
            r = 1;
        }

        SharedPreferences.Editor prefEditor = settings.edit();
        prefEditor.putInt(Constants.CONFIG.CONF_DELTA_TIME, r);
        prefEditor.apply();
//        AtomSpectra.channelCompression = r;

        TextView mDataField = findViewById(R.id.deltaText);
        mDataField.setText(getString(R.string.delta_spectrum_format, r));
    }

    public void onClick_Delta_Time_plus(View v) {
        SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        int r = settings.getInt(Constants.CONFIG.CONF_DELTA_TIME, Constants.DEFAULT_DELTA_TIME);

        if (r < 10) {
            r = r + 1;
        } else if (r < 60) {
            r = r + 5;
        } else if (r < 300) {
            r = r + 30;
        } else {
            r = 300;
        }

        SharedPreferences.Editor prefEditor = settings.edit();
        prefEditor.putInt(Constants.CONFIG.CONF_DELTA_TIME, r);
        prefEditor.apply();
//        AtomSpectra.channelCompression = r;

        TextView mDataField = findViewById(R.id.deltaText);
        mDataField.setText(getString(R.string.delta_spectrum_format, r));
    }

    public void onClick_outputName_minus(View v) {
        SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        boolean prefix = settings.getBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_PREFIX, Constants.OUTPUT_FILE_NAME_PREFIX_DEFAULT);
        boolean date = settings.getBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_DATE, Constants.OUTPUT_FILE_NAME_DATE_DEFAULT);
        boolean time = settings.getBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_TIME, Constants.OUTPUT_FILE_NAME_TIME_DEFAULT);
        int val = (prefix ? 4 : 0) + (time ? 2 : 0) + (date ? 1 : 0);

        if (val > 0) {
            val -= 1;
        }

        SharedPreferences.Editor prefEditor = settings.edit();
        prefEditor.putBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_PREFIX, (val & 4) != 0);
        prefEditor.putBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_TIME, (val & 2) != 0);
        prefEditor.putBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_DATE, (val & 1) != 0);
        prefEditor.apply();

        TextView mDataField = findViewById(R.id.outputNameText);
        mDataField.setText(getResources().getTextArray(R.array.file_name_array)[val]);
    }

    public void onClick_outputName_plus(View v) {
        SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        boolean prefix = settings.getBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_PREFIX, Constants.OUTPUT_FILE_NAME_PREFIX_DEFAULT);
        boolean date = settings.getBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_DATE, Constants.OUTPUT_FILE_NAME_DATE_DEFAULT);
        boolean time = settings.getBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_TIME, Constants.OUTPUT_FILE_NAME_TIME_DEFAULT);
        int val = (prefix ? 4 : 0) + (time ? 2 : 0) + (date ? 1 : 0);

        if (val < 7) {
            val += 1;
        }

        SharedPreferences.Editor prefEditor = settings.edit();
        prefEditor.putBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_PREFIX, (val & 4) != 0);
        prefEditor.putBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_TIME, (val & 2) != 0);
        prefEditor.putBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_DATE, (val & 1) != 0);
        prefEditor.apply();

        TextView mDataField = findViewById(R.id.outputNameText);
        mDataField.setText(getResources().getTextArray(R.array.file_name_array)[val]);
    }

    public void onClick_autosaveName_minus(View v) {
        SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        int val = settings.getInt(Constants.CONFIG.CONF_AUTOSAVE, 0);
        if (val > Constants.AUTOSAVE_DELTA) {
            val -= Constants.AUTOSAVE_DELTA;
        } else {
            val = 0;
        }

        SharedPreferences.Editor prefEditor = settings.edit();
        prefEditor.putInt(Constants.CONFIG.CONF_AUTOSAVE, val);
        prefEditor.apply();

        TextView mDataField = findViewById(R.id.autosaveNameText);
        mDataField.setText(getString(R.string.autosave_timeout, val));
    }

    public void onClick_autosaveName_plus(View v) {
        SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        int val = settings.getInt(Constants.CONFIG.CONF_AUTOSAVE, 0);
        if (val <= (Constants.AUTOSAVE_MAX_DELTA - Constants.AUTOSAVE_DELTA)) {
            val += Constants.AUTOSAVE_DELTA;
        } else {
            val = Constants.AUTOSAVE_MAX_DELTA;
        }

        SharedPreferences.Editor prefEditor = settings.edit();
        prefEditor.putInt(Constants.CONFIG.CONF_AUTOSAVE, val);
        prefEditor.apply();

        TextView mDataField = findViewById(R.id.autosaveNameText);
        mDataField.setText(getString(R.string.autosave_timeout, val));
    }

    public void onClick_compression_minus(View v) {
        SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        int r = settings.getInt(Constants.CONFIG.CONF_COMPRESSION, Constants.EXPORT_COMPRESSION_DEFAULT);

        if (r > 2) {
            r = r / 2;
        } else {
            r = 1;
        }

        SharedPreferences.Editor prefEditor = settings.edit();
        prefEditor.putInt(Constants.CONFIG.CONF_COMPRESSION, r);
        prefEditor.apply();
        AtomSpectra.channelCompression = r;

        TextView mDataField = findViewById(R.id.compressText);
        mDataField.setText(getString(R.string.channel_compression_format, r));
    }

    public void onClick_compression_plus(View v) {
        SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        int r = settings.getInt(Constants.CONFIG.CONF_COMPRESSION, Constants.EXPORT_COMPRESSION_DEFAULT);

        if (r < 32) {
            r = r * 2;
        } else {
            r = 64;
        }

        SharedPreferences.Editor prefEditor = settings.edit();
        prefEditor.putInt(Constants.CONFIG.CONF_COMPRESSION, r);
        prefEditor.apply();
        AtomSpectra.channelCompression = r;

        TextView mDataField = findViewById(R.id.compressText);
        mDataField.setText(getString(R.string.channel_compression_format, r));
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
            TextView mDataField = findViewById(R.id.compressText);
            mDataField.setText(getString(R.string.channel_compression_format, intValue));
            AtomSpectra.channelCompression = intValue;

            //SharedPreferences settings = getSharedPreferences(ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
            SharedPreferences.Editor prefEditor = settings.edit();
            prefEditor.putInt(Constants.CONFIG.CONF_COMPRESSION, intValue);
            prefEditor.apply();
        });
        alert.setNegativeButton("Cancel", (dialog, whichButton) -> {
            // comment
        });
        alert.show();
    }

    public void onClick_Graph_plus(View v) {
        SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        int r;
        try {
            r = sp.getInt(Constants.CONFIG.CONF_COMPRESS_GRAPH, Constants.COMPRESS_GRAPH_SUM);
        } catch (Exception e) {
            r = Constants.COMPRESS_GRAPH_SUM;
        }
        CharSequence[] data = getResources().getTextArray(R.array.compress_graph_array);
        r = r < (data.length - 1) ? r + 1 : data.length - 1;
        SharedPreferences.Editor prefEditor = settings.edit();
        prefEditor.putInt(Constants.CONFIG.CONF_COMPRESS_GRAPH, r);
        prefEditor.apply();

        TextView mDataField = findViewById(R.id.graphTypeText);
        mDataField.setText(getString(R.string.compress_graph_format, data[r]));
    }

    public void onClick_Graph_minus(View v) {
        SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        int r = settings.getInt(Constants.CONFIG.CONF_COMPRESS_GRAPH, Constants.COMPRESS_GRAPH_SUM);
        r = r > 0 ? r - 1 : 0;
        SharedPreferences.Editor prefEditor = settings.edit();
        prefEditor.putInt(Constants.CONFIG.CONF_COMPRESS_GRAPH, r);
        prefEditor.apply();

        TextView mDataField = findViewById(R.id.graphTypeText);
        mDataField.setText(getString(R.string.compress_graph_format, getResources().getTextArray(R.array.compress_graph_array)[r]));
    }


    public void onClick_Locale_plus(View v) {
        SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        int r = sp.getInt(Constants.CONFIG.CONF_LOCALE_ID, 0);
        r = (r + 1) < Constants.LOCALES_ID.length ? r + 1 : (Constants.LOCALES_ID.length - 1);
        SharedPreferences.Editor prefEditor = settings.edit();
        prefEditor.putInt(Constants.CONFIG.CONF_LOCALE_ID, r);
        prefEditor.apply();

        TextView mDataField = findViewById(R.id.localeText);
        mDataField.setText(String.format(Locale.US,"Language: %s", Constants.LOCALES[r]));
    }

    public void onClick_Locale_minus(View v) {
        SharedPreferences settings = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        int r = sp.getInt(Constants.CONFIG.CONF_LOCALE_ID, 0);
        r = (r > 1) ? (r - 1) : 0;
        SharedPreferences.Editor prefEditor = settings.edit();
        prefEditor.putInt(Constants.CONFIG.CONF_LOCALE_ID, r);
        prefEditor.apply();

        TextView mDataField = findViewById(R.id.localeText);
        mDataField.setText(String.format(Locale.US,"Language: %s", Constants.LOCALES[r]));
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
                        if (AtomSpectraService.getScaleFactor() > Constants.SCALE_COUNT_MODE)
                            AtomSpectraService.setScaleFactor(Constants.SCALE_COUNT_MODE);
                        else {
                            AtomSpectraService.setScaleFactor(Constants.SCALE_IMPULSE_MODE);
                            sendBroadcast(new Intent(Constants.ACTION.ACTION_CLEAR_IMPULSE).setPackage(Constants.PACKAGE_NAME));
                        }
                        AtomSpectraService.requestUpdateGraph();

                    } else if (detector.isSwipeRight(e1, e2, velocityX)) {
                        //showToast("Right Swipe");
                        if (AtomSpectraService.getScaleFactor() < Constants.SCALE_IMPULSE_MODE)
                            AtomSpectraService.setScaleFactor(Constants.SCALE_IMPULSE_MODE);
                        else {
                            AtomSpectraService.setScaleFactor(Constants.SCALE_COUNT_MODE);
                            sendBroadcast(new Intent(Constants.ACTION.ACTION_CLEAR_IMPULSE).setPackage(Constants.PACKAGE_NAME));
                        }
                        AtomSpectraService.requestUpdateGraph();

                    } else if (detector.isSwipeDown(e1, e2, velocityY)) {
                        if (zoom_factor > 1.1) zoom_factor /= 2;
                        else showToast(getString(R.string.graph_min_zoom));
                        AtomSpectraService.requestUpdateGraph();
                    } else if (detector.isSwipeUp(e1, e2, velocityY)) {
                        if (zoom_factor < 40) zoom_factor *= 2;
                        else showToast(getString(R.string.graph_max_zoom));
                        AtomSpectraService.requestUpdateGraph();
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

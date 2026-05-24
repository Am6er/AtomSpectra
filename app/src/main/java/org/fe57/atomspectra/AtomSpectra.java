package org.fe57.atomspectra;


import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.gesture.GestureOverlayView;
import android.gesture.GestureOverlayView.OnGestureListener;
import android.graphics.Color;
import android.graphics.Paint;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.text.method.NumberKeyListener;
import android.util.Log;
import android.view.GestureDetector;
import android.view.GestureDetector.SimpleOnGestureListener;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.ScaleGestureDetector.SimpleOnScaleGestureListener;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.window.OnBackInvokedDispatcher;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.app.ActivityCompat.OnRequestPermissionsResultCallback;
import androidx.core.content.PermissionChecker;
import androidx.core.util.Pair;
import androidx.documentfile.provider.DocumentFile;

import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeMap;

public class AtomSpectra extends Activity implements OnGestureListener, OnRequestPermissionsResultCallback {

    private final static String TAG = AtomSpectra.class.getSimpleName();
//	private static String PACKAGE_NAME;

    public static final int REQUEST_AUDIO = 0;
    public static final int REQUEST_READ_HIST = 1;
    public static final int REQUEST_READ_BACK = 2;
    public static final int REQUEST_READ_CAL = 3;
    public static final int REQUEST_WRITE_HIST = 4;
    public static final int REQUEST_WRITE_BACK = 5;
    public static final int REQUEST_READ_BACK_FROM = 6;
    public static final int REQUEST_EXPORT = 7;
    public static final int REQUEST_EXPORT_E = 8;
    public static final int REQUEST_SHARE = 9;
    public static final int REQUEST_EXPORT_BQMONI = 10;
    public static final int REQUEST_EXPORT_SPE = 11;
    public static final int REQUEST_EXPORT_N42 = 12;
    public static final int REQUEST_FINE_GPS = 13;
    public static final int REQUEST_READ_DEVICE = 14;
    public static final int REQUEST_WRITE_DEVICE = 15;
    public static final int REQUEST_ADD_HIST = 16;
    public static final int REQUEST_READ_SPG = 17;

    private static final String ATOM_STATE_LOG = "Atom Log";
    private static final String ATOM_STATE_BAR = "Atom Bar";
    private static final String ATOM_STATE_SCALE = "Atom scale";
    private static final String ATOM_STATE_BACKGROUND_SUBTRACT = "Atom subtract";

    private static final String ATOM_STATE_CURSOR_X = "Atom cursor";
    private static final String ATOM_STATE_CURSOR_BUTTONS = "Atom buttons";

    private static final String SEND_CALIBRATION = "Send Calibration";
    private static final String GET_CALIBRATION = "Get calibration";
    private final String[] calibrationAnswers = new String[11];
    private int gotAnswers = 0;

    private AtomSpectraService mAtomSpectraService = null;
    private static Context AppContext;

    public static boolean active = false;
    private AtomSpectraShapeView mAtomSpectraShapeView = null;
    private SeekBar seekChannel = null;

    private GestureDetector gestureDetector = null;
    private ScaleGestureDetector gestureScaleDetector = null;

    @SuppressLint("StaticFieldLeak")
    private TextView mTextView = null;
    private LinearLayout mLayoutView = null;

    private boolean logScale = Constants.LOG_SCALE_DEFAULT;
    private boolean barMode = false;

    //for main spectrum
    private float zoom_factor = 1;
    private int cursor_x = -1;
    public static boolean XCalibrated;
    public static String DisplayDose;

    //for background
    public static boolean background_subtract = false; // Subtract background from main histogram
    private static Menu app_menu = null;
    private final int LOAD_HIST_CODE = 301;
    private final int LOAD_CALIBRATION_CODE = 302;
    private final int SHARE_FILE_CODE = 303;
    private final int LOAD_BACK_CODE = 304;
    private final int SELECT_SAVE_HIST_DIR_CODE = 305;
    private final int SELECT_SAVE_BACK_DIR_CODE = 306;
    private final int SELECT_SAVE_EXPORT_DIR_CODE = 307;
    private final int SELECT_SAVE_EXPORT_E_DIR_CODE = 308;
    private final int SELECT_SAVE_EXPORT_BQ_DIR_CODE = 309;
    private final int SELECT_SAVE_EXPORT_SPE_DIR_CODE = 310;
    private final int SELECT_SAVE_EXPORT_N42_DIR_CODE = 311;
    private final int SELECT_LOAD_BACK_DIR_CODE = 312;
    private final int SELECT_LOAD_DEVICE_CODE = 313;
    private final int SELECT_SAVE_DEVICE_DIR_CODE = 314;
    private final int ADD_HIST_CODE = 315;
    private final int LOAD_SPG_CODE = 316;
    private boolean isPinchMode = false;
    private boolean isPinchModeFinished = false;
    private boolean hasFeatureGPS = false; // GPS coordinates
    private boolean hasFeatureNetwork = false; // Network coordinates
    private boolean addGPS = false;
    private Intent inputServiceIntent = null;
    private SharedPreferences sharedPreferences = null;

    public static int reducedTo;
    public static int saveChannels;
    public static int loadChannels;
    public static int channelCompression;

    private TextView statusLineTopText, statusLineMiddleText, statusLineBottomText;
    private Button fmsButton;
    private final int[] searchFMSNextMode = {1, 2, 0};
    private final String[] searchFMSNames = {"F", "M", "S"};
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH-mm-ss", Locale.US);
    //	private final SimpleDateFormat dateTimeFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US);
    private final SimpleDateFormat dateZoneFormat = new SimpleDateFormat("yyyy.MM.dd HH:mm:ss Z", Locale.US);
    private final int mutabilityFlag = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ? PendingIntent.FLAG_IMMUTABLE : 0;

    //template function to check permissions and ask for them if needed
    protected boolean checkPermissions(final String[] permission, final String title, final String message, final int request) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            final Activity id = this;
            if (PermissionChecker.checkSelfPermission(getApplicationContext(), permission[0]) != PermissionChecker.PERMISSION_GRANTED) {
                // Permission is not granted
                //When permission is not granted by user, show them message why this permission is needed.
                if (ActivityCompat.shouldShowRequestPermissionRationale(this, permission[0])) {
                    final AlertDialog.Builder alert = new AlertDialog.Builder(this)
                            .setTitle(title)
                            .setMessage(message)
                            .setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                                //Give user option to still opt-in the permissions
                                ActivityCompat.requestPermissions(id, permission, request);
                            });
                    alert.show();
                } else {
                    ActivityCompat.requestPermissions(id, permission, request);
                }
                return false;
            } else {
                return true;
            }
        } else
            return true;
    }

    public static Context getContext() {
        return AppContext;
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        String lang = PrefHelper.getLocale(newBase);
        super.attachBaseContext(LocaleContextWrapper.wrap(newBase, lang));
//		super.attachBaseContext(MyContextWrapper.wrap(newBase, "en"));
    }

    @SuppressLint({"ApplySharedPref", "UnspecifiedRegisterReceiverFlag"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // ToastHelper.showToast(this, "On create");
        sharedPreferences = PrefHelper.getASSharedPreferences(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, () -> {
            });
        }
        super.onCreate(savedInstanceState);
        AppContext = getApplicationContext();
        Thread.setDefaultUncaughtExceptionHandler(new TopExceptionHandler(this));
        setContentView(R.layout.activity_atom_spectra);
        SharedPreferences.Editor prefEditor = sharedPreferences.edit();
        mTextView = findViewById(R.id.cursorView);
        mTextView.setVisibility(TextView.INVISIBLE);
        mLayoutView = findViewById(R.id.suffixLayout);
        mLayoutView.setVisibility(LinearLayout.INVISIBLE);
        seekChannel = findViewById(R.id.seekChannel);
        ((TextView) findViewById(R.id.backgroundSuffixView)).setText(AtomSpectraService.BackgroundSpectrum.getSuffix());
        ((TextView) findViewById(R.id.suffixView)).setText(AtomSpectraService.ForegroundSpectrum.getSuffix());
        AtomSpectraIsotopes.autoUpdateIsotopes = sharedPreferences.getBoolean(Constants.CONFIG.CONF_AUTO_UPDATE_ISOTOPES, false);

        if (savedInstanceState != null) {
            logScale = savedInstanceState.getBoolean(ATOM_STATE_LOG, Constants.LOG_SCALE_DEFAULT);
            barMode = savedInstanceState.getBoolean(ATOM_STATE_BAR, false);
            zoom_factor = savedInstanceState.getFloat(ATOM_STATE_SCALE, 1);
            background_subtract = savedInstanceState.getBoolean(ATOM_STATE_BACKGROUND_SUBTRACT, false) && AtomSpectraService.background_show;
            AtomSpectraService.setScaleFactor(savedInstanceState.getInt(Constants.SCALE_FACTOR, Constants.SCALE_DEFAULT));
            cursor_x = savedInstanceState.getInt(ATOM_STATE_CURSOR_X, -1);
            showPlusMinusButtons = savedInstanceState.getBoolean(ATOM_STATE_CURSOR_BUTTONS, false);
            if (showPlusMinusButtons) {
                seekChannel.setVisibility(SeekBar.VISIBLE);
                dateChannelChanged = System.currentTimeMillis();
            }
        } else {
            if (!AtomSpectraService.isStarted || !AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().isCorrect())
                getCalibrationSettingsFromMemory();
            AtomSpectraService.setScaleFactor(sharedPreferences.getInt(Constants.CONFIG.CONF_SCALE_FACTOR, Constants.SCALE_DEFAULT));
            logScale = sharedPreferences.getBoolean(Constants.CONFIG.CONF_LOG_SCALE, Constants.LOG_SCALE_DEFAULT);
        }

        fmsButton = findViewById(R.id.fmsButton);
        statusLineTopText = findViewById(R.id.statusLineTopText);
        statusLineMiddleText = findViewById(R.id.statusLineMiddleText);
        statusLineBottomText = findViewById(R.id.statusLineBottomText);

        mAtomSpectraShapeView = findViewById(R.id.shape_area);
        int coeff = StrictMath.max(seekChannel.getWidth() / 200, 1);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            seekChannel.setProgress(0);
            seekChannel.setMin(-50 * coeff);
            seekChannel.setMax(50 + coeff);
            seekChannel.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                int init_cursor;

                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser) {
                        int shift_cursor = init_cursor + (1 << StrictMath.max(0, (Constants.SCALE_MAX - AtomSpectraService.getScaleFactor() - 1))) * progress;
                        cursor_x = Constants.MinMax(shift_cursor, 0, Constants.NUM_HIST_POINTS - 1);
                        showCursorInfo(true);
                        dateChannelChanged = System.currentTimeMillis();
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                    init_cursor = cursor_x;
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    seekBar.setProgress(0);
                }
            });
        } else {
            seekChannel.setProgress(50 * coeff);
            seekChannel.setMax(100 * coeff);
            seekChannel.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                int init_cursor;
                final int scale = coeff;

                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser) {
                        int shift_cursor = init_cursor + (1 << StrictMath.max(0, (Constants.SCALE_MAX - AtomSpectraService.getScaleFactor() - 1))) * (progress - 50 * scale);
                        cursor_x = Constants.MinMax(shift_cursor, 0, Constants.NUM_HIST_POINTS - 1);
                        showCursorInfo(true);
                        dateChannelChanged = new Date().getTime();
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                    init_cursor = cursor_x;
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    seekBar.setProgress(0);
                }
            });
        }


        Button searchBaselineButton = findViewById(R.id.searchBaselineButton);
        searchBaselineButton.setEnabled((Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) && sharedPreferences.getBoolean(Constants.CONFIG.CONF_OUTPUT_SOUND, false));
        initializeGestures();
        buttonsTimer.schedule(buttonsTask, 0, 1000);

        hasFeatureGPS = getPackageManager().hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS);
        hasFeatureNetwork = getPackageManager().hasSystemFeature(PackageManager.FEATURE_LOCATION_NETWORK);
        addGPS = sharedPreferences.getBoolean(Constants.CONFIG.CONF_ADD_GPS_TO_FILES, false);
        boolean isAndroid14orHigher = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE;
        if ((hasFeatureGPS || hasFeatureNetwork) && addGPS && !isAndroid14orHigher) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                final Activity id = this;
                if (PermissionChecker.checkSelfPermission(id, Manifest.permission.ACCESS_FINE_LOCATION) != PermissionChecker.PERMISSION_GRANTED) {
                    // Permission is not granted
                    //When permission is not granted by user, show them message why this permission is needed.
                    addGPS = false;
                    SharedPreferences.Editor edit = sharedPreferences.edit();
                    edit.putBoolean(Constants.CONFIG.CONF_ADD_GPS_TO_FILES, false);
                    edit.apply();
                    final AlertDialog.Builder alert = new AlertDialog.Builder(id)
                            .setTitle(getString(R.string.perm_ask_fine_gps_title))
                            .setMessage(getString(R.string.perm_ask_fine_gps_text))
                            .setPositiveButton(android.R.string.ok, (dialog, whichButton) -> ActivityCompat.requestPermissions(id, new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST_FINE_GPS));
                    alert.show();
                }
            }
        }

        // temporary: android 14+ requires location access to be able to start background service with location access
        // proper fix should be to check permissions and start background without location + restart when location needed/granted
        if ((hasFeatureGPS || hasFeatureNetwork) && isAndroid14orHigher) {
            final Activity id = this;
            if (PermissionChecker.checkSelfPermission(id, Manifest.permission.ACCESS_FINE_LOCATION) != PermissionChecker.PERMISSION_GRANTED) {
                final AlertDialog.Builder alert = new AlertDialog.Builder(id)
                        .setTitle(getString(R.string.perm_ask_fine_gps_title))
                        .setMessage(getString(R.string.perm_ask_fine_gps_text_android_14))
                        .setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                        });
                alert.show();
                return;
            }
        }

        //prepare directory to work on Android under 7.0
        //on Android 8.0 and above system picker will be used
        updateDestinationDirectory();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        reducedTo = sharedPreferences.getInt(Constants.CONFIG.CONF_REDUCED_TO, Constants.VIEW_CHANNELS_DEFAULT);
        saveChannels = Constants.MinMax(sharedPreferences.getInt(Constants.CONFIG.CONF_SAVE_CHANNELS, Constants.EXPORT_CHANNELS_DEFAULT), 1024, Constants.NUM_HIST_POINTS);
        loadChannels = Constants.MinMax(sharedPreferences.getInt(Constants.CONFIG.CONF_LOAD_CHANNELS, Constants.LOAD_CHANNELS_DEFAULT), 1024, Constants.NUM_HIST_POINTS);
        channelCompression = sharedPreferences.getInt(Constants.CONFIG.CONF_COMPRESSION, Constants.EXPORT_COMPRESSION_DEFAULT);
        searchFMSNames[0] = getString(R.string.mode_fast_button);
        searchFMSNames[1] = getString(R.string.mode_medium_button);
        searchFMSNames[2] = getString(R.string.mode_slow_button);
        fmsButton.setText(searchFMSNames[sharedPreferences.getInt(Constants.CONFIG.CONF_SEARCH_MODE, 0)]);

        AtomSpectraService.setFirstChannel(sharedPreferences.getInt(Constants.CONFIG.CONF_FIRST_CHANNEL, 0));
        boolean doPowerCheck = sharedPreferences.getBoolean(Constants.CONFIG.CONF_CHECK_POWER, true);

        if (doPowerCheck) {
            final AlertDialog.Builder alert = new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.perm_ask_power_title))
                    .setMessage(getString(R.string.perm_ask_power_text))
                    .setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                    });
            alert.show();
        }

        prefEditor.putBoolean(Constants.CONFIG.CONF_CHECK_POWER, false);
        prefEditor.commit();

        barMode = sharedPreferences.getBoolean(Constants.CONFIG.CONF_BAR_MODE, true);

        setXCalibrated(sharedPreferences.getBoolean(Constants.CONFIG.CONF_CALIBRATED, true));
        setDisplayDose(sharedPreferences.getString(Constants.CONFIG.CONF_DISPLAY_DOSE, Constants.DISPLAY_DOSE_DEFAULT));
        updateDisplayModeViews();

        showCursorInfo(false);
        Intent intent = getIntent();
        inputServiceIntent = new Intent(this, AtomSpectraService.class);
        if (intent != null) {
            UsbDevice device;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                device = getIntent().getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
            } else {
                device = getIntent().getParcelableExtra(UsbManager.EXTRA_DEVICE);
            }
            if (device != null) {
                inputServiceIntent.putExtra(Constants.USB_DEVICE, device);
            } else {
                UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
                UsbDevice dev = AtomSpectraSerial.scanForSpectraProDevice(manager);
                if (dev != null) {
                    if (manager.hasPermission(dev)) {
                        inputServiceIntent.putExtra(Constants.USB_DEVICE, dev);
                    } else {
                        PendingIntent pi = PendingIntent.getBroadcast(this, 0, new Intent(Constants.ACTION.ACTION_GET_USB_PERMISSION), mutabilityFlag);
                        manager.requestPermission(dev, pi);
                    }
                }
            }
        }
        if (intent != null) {
            if (Intent.ACTION_SEND.equals(intent.getAction()) && (intent.getType() != null)) {
                if (intent.getType().equals("text/plain")) {
                    Uri file;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        file = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
                    } else {
                        file = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                    }
                    if (file != null) {
                        loadSpectrum(file, savedInstanceState == null);
                        AtomSpectraService.isStarted = true;
                        setIntent(new Intent());
                    }
                }
            } else if (Intent.ACTION_VIEW.equals(intent.getAction()) && (intent.getType() != null)) {
                if (intent.getType().equals("text/plain")) {
                    Uri file = intent.getData();
                    if (file != null) {
                        loadSpectrum(file, savedInstanceState == null);
                        AtomSpectraService.isStarted = true;
                        setIntent(new Intent());
                    }
                }
            }
        }

        inputServiceIntent.setAction(Constants.ACTION.ACTION_START_FOREGROUND);

        //check permissions
        if (checkPermissions(new String[]{Manifest.permission.RECORD_AUDIO},
                getString(R.string.perm_ask_audio_title),
                getString(R.string.perm_ask_audio_text),
                REQUEST_AUDIO)) {
            AtomSpectraService.canOpenAudio = true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getApplicationContext().startForegroundService(inputServiceIntent);
            } else {
                getApplicationContext().startService(inputServiceIntent);
            }
            getApplicationContext().bindService(inputServiceIntent, mServiceConnection, BIND_IMPORTANT);
            inputServiceIntent = null;
        } else {
            if (sharedPreferences.getBoolean(Constants.CONFIG.CONF_CHECK_AUDIO, true)) {
                ToastHelper.showToast(this, getText(R.string.perm_ask_audio_text).toString());
                prefEditor.putBoolean(Constants.CONFIG.CONF_CHECK_AUDIO, false);
                prefEditor.commit();
            }
        }

        updateSelectedInputIndicator();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mDataUpdateReceiver, makeAtomSpectraUpdateIntentFilter(), Context.RECEIVER_NOT_EXPORTED);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(mDataUpdateReceiver, makeAtomSpectraUpdateIntentFilter(), 0);
        } else {
            registerReceiver(mDataUpdateReceiver, makeAtomSpectraUpdateIntentFilter());
        }

        PrefHelper.getWorkingDir(this, true);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        if (intent != null) {
            setIntent(intent);
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
                UsbDevice device = null;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
                } else {
                    device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                }

                Context context = getContext();
                UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
                if ((device != null)) {
                    if (AtomSpectraSerial.isSpectraPro(device)) {
                        if (!usbManager.hasPermission(device)) {
                            PendingIntent pi = PendingIntent.getBroadcast(context, 0, new Intent(Constants.ACTION.ACTION_GET_USB_PERMISSION), mutabilityFlag);
                            usbManager.requestPermission(device, pi);
                        } else {
                            Intent intentAttached = new Intent(Constants.ACTION.ACTION_USB_ATTACHED).setPackage(Constants.PACKAGE_NAME);
                            intentAttached.putExtra(Constants.USB_DEVICE, device);
                            context.sendBroadcast(intentAttached);
                            context.sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_SETTINGS).setPackage(Constants.PACKAGE_NAME));
                        }
                    }
                }
            } else if (Intent.ACTION_SEND.equals(intent.getAction()) && (intent.getType() != null)) {
                if (intent.getType().equals("text/plain")) {
                    Uri file;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        file = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
                    } else {
                        file = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                    }
                    if (file != null) {
                        loadSpectrum(file, true);
                        AtomSpectraService.isStarted = true;
                        setIntent(new Intent());
                    }
                }
            } else if (Intent.ACTION_VIEW.equals(intent.getAction()) && (intent.getType() != null)) {
                if (intent.getType().equals("text/plain")) {
                    Uri file = intent.getData();
                    if (file != null) {
                        loadSpectrum(file, true);
                        AtomSpectraService.isStarted = true;
                        setIntent(new Intent());
                    }
                }
            }
        }
        super.onNewIntent(intent);
    }

    @SuppressLint("ApplySharedPref")
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        switch (requestCode) {
            case REQUEST_AUDIO:
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    AtomSpectraService.canOpenAudio = true;
                    if (inputServiceIntent != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            getApplicationContext().startForegroundService(inputServiceIntent);
                        } else {
                            getApplicationContext().startService(inputServiceIntent);
                        }
                        getApplicationContext().bindService(inputServiceIntent, mServiceConnection, BIND_IMPORTANT);
                        inputServiceIntent = null;
                    }
                } else {
                    ToastHelper.showToast(this, getString(R.string.perm_no_audio));
                    if (AtomSpectraService.isStarted) {
                        sendBroadcast(new Intent(Constants.ACTION.ACTION_STOP_FOREGROUND).setComponent(getComponentName()).setPackage(Constants.PACKAGE_NAME));
                    }
                    inputServiceIntent = null;
                }
                break;
            case REQUEST_READ_HIST:
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Intent loadIntent = new Intent()
                            .setType("*/*")
                            .setAction(Intent.ACTION_GET_CONTENT);
                    startActivityForResult(Intent.createChooser(loadIntent, getString(R.string.ask_select_histogram)), LOAD_HIST_CODE);
                } else
                    ToastHelper.showToast(this, getString(R.string.perm_no_read_histogram));
                break;
            case REQUEST_READ_SPG:
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Intent loadIntent = new Intent()
                            .setType("*/*")
                            .setAction(Intent.ACTION_GET_CONTENT);
                    startActivityForResult(Intent.createChooser(loadIntent, getString(R.string.ask_select_spectrogram)), LOAD_SPG_CODE);
                } else
                    ToastHelper.showToast(this, getString(R.string.perm_no_read_spectrogram));
                break;
            case REQUEST_ADD_HIST:
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Intent loadIntent = new Intent()
                            .setType("*/*")
                            .setAction(Intent.ACTION_GET_CONTENT);
                    startActivityForResult(Intent.createChooser(loadIntent, getString(R.string.ask_select_histogram)), ADD_HIST_CODE);
                } else
                    ToastHelper.showToast(this, getString(R.string.perm_no_read_histogram));
                break;
            case REQUEST_READ_BACK:
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    loadBackgroundOrDefault(null);
                } else
                    ToastHelper.showToast(this, getString(R.string.perm_no_read_background));
                break;
            case REQUEST_READ_BACK_FROM:
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Intent loadIntent = new Intent()
                            .setType("*/*")
                            .setAction(Intent.ACTION_GET_CONTENT);
                    startActivityForResult(Intent.createChooser(loadIntent, getString(R.string.ask_select_background)), LOAD_BACK_CODE);
                } else
                    ToastHelper.showToast(this, getString(R.string.perm_no_read_background));
                break;
            case REQUEST_READ_CAL:
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Intent calibrationIntent = new Intent()
                            .setType("*/*")
                            .setAction(Intent.ACTION_GET_CONTENT);
                    startActivityForResult(Intent.createChooser(calibrationIntent, getString(R.string.ask_select_calibration)), LOAD_CALIBRATION_CODE);
                } else
                    ToastHelper.showToast(this, getString(R.string.perm_no_read_calibration));
                break;
            case REQUEST_WRITE_HIST:
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    try {
                        final AlertDialog.Builder alert = new AlertDialog.Builder(this);
                        alert.setTitle(getString(R.string.ask_spectrum_suffix));
                        alert.setMessage(getString(R.string.ask_suffix_text));

                        final EditText input = new EditText(this);
                        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
                        input.setText(AtomSpectraService.ForegroundSpectrum.getSuffix(), TextView.BufferType.EDITABLE);
                        alert.setView(input);
                        alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                            AtomSpectraService.ForegroundSpectrum.setSuffix(input.getText().toString());
                            TextView text = findViewById(R.id.suffixView);
                            text.setText(AtomSpectraService.ForegroundSpectrum.getSuffix());
                            saveSpectrumAS(AtomSpectraService.ForegroundSpectrum.getSuffix());
                        });
                        alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
                        });
                        alert.show();
                    } catch (Exception e) {
                        Log.d(TAG, "saving spectrum file FAIL");
                    }
                } else
                    ToastHelper.showToast(this, getString(R.string.perm_no_write_histogram));
                break;
            case REQUEST_WRITE_BACK:
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    saveDefaultBackground();
                } else
                    ToastHelper.showToast(this, getString(R.string.perm_no_write_background));
                break;
            case REQUEST_EXPORT:
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    try {
                        final AlertDialog.Builder alert = new AlertDialog.Builder(this);
                        alert.setTitle(getString(R.string.ask_export_suffix));
                        alert.setMessage(getString(R.string.ask_suffix_text));

                        final EditText input = new EditText(this);
                        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
                        input.setText(AtomSpectraService.ForegroundSpectrum.getSuffix(), TextView.BufferType.EDITABLE);
                        alert.setView(input);
                        alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                            AtomSpectraService.ForegroundSpectrum.setSuffix(input.getText().toString());
                            TextView text = findViewById(R.id.suffixView);
                            text.setText(AtomSpectraService.ForegroundSpectrum.getSuffix());
                            saveCSV(AtomSpectraService.ForegroundSpectrum.getSuffix(), false);
                        });
                        alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
                        });
                        alert.show();
                    } catch (Exception e) {
                        Log.d(TAG, "exporting file FAIL");
                    }
                } else
                    ToastHelper.showToast(this, getString(R.string.perm_no_write_export));
                break;
            case REQUEST_EXPORT_E:
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    try {
                        final AlertDialog.Builder alert = new AlertDialog.Builder(this);
                        alert.setTitle(getString(R.string.ask_export_suffix));
                        alert.setMessage(getString(R.string.ask_suffix_text));

                        final EditText input = new EditText(this);
                        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
                        input.setText(AtomSpectraService.ForegroundSpectrum.getSuffix(), TextView.BufferType.EDITABLE);
                        alert.setView(input);
                        alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                            AtomSpectraService.ForegroundSpectrum.setSuffix(input.getText().toString());
                            TextView text = findViewById(R.id.suffixView);
                            text.setText(AtomSpectraService.ForegroundSpectrum.getSuffix());
                            saveCSV(AtomSpectraService.ForegroundSpectrum.getSuffix(), true);
                        });
                        alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
                        });
                        alert.show();
                    } catch (Exception e) {
                        Log.d(TAG, "exporting with energy file FAIL");
                    }
                } else
                    ToastHelper.showToast(this, getString(R.string.perm_no_write_export_energy));
                break;
            case REQUEST_EXPORT_BQMONI:
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    try {
                        final AlertDialog.Builder alert = new AlertDialog.Builder(this);
                        alert.setTitle(getString(R.string.ask_export_bqmoni));
                        alert.setMessage(getString(R.string.ask_suffix_text));

                        final EditText input = new EditText(this);
                        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
                        input.setText(AtomSpectraService.ForegroundSpectrum.getSuffix(), TextView.BufferType.EDITABLE);
                        alert.setView(input);
                        alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                            AtomSpectraService.ForegroundSpectrum.setSuffix(input.getText().toString());
                            TextView text = findViewById(R.id.suffixView);
                            text.setText(AtomSpectraService.ForegroundSpectrum.getSuffix());
                            saveBqMoni(AtomSpectraService.ForegroundSpectrum.getSuffix());
                        });
                        alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
                        });
                        alert.show();
                    } catch (Exception e) {
                        Log.d(TAG, "exporting file FAIL");
                    }
                } else
                    ToastHelper.showToast(this, getString(R.string.perm_no_write_bqmoni));
                break;
            case REQUEST_EXPORT_SPE:
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    try {
                        final AlertDialog.Builder alert = new AlertDialog.Builder(this);
                        alert.setTitle(getString(R.string.ask_export_spe));
                        alert.setMessage(getString(R.string.ask_suffix_text));

                        final EditText input = new EditText(this);
                        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
                        input.setText(AtomSpectraService.ForegroundSpectrum.getSuffix(), TextView.BufferType.EDITABLE);
                        alert.setView(input);
                        alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                            AtomSpectraService.ForegroundSpectrum.setSuffix(input.getText().toString());
                            TextView text = findViewById(R.id.suffixView);
                            text.setText(AtomSpectraService.ForegroundSpectrum.getSuffix());
                            saveSPE(AtomSpectraService.ForegroundSpectrum.getSuffix());
                        });
                        alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
                        });
                        alert.show();
                    } catch (Exception e) {
                        Log.d(TAG, "exporting file FAIL");
                    }
                } else
                    ToastHelper.showToast(this, getString(R.string.perm_no_write_spe));
                break;
            case REQUEST_EXPORT_N42:
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    try {
                        final AlertDialog.Builder alert = new AlertDialog.Builder(this);
                        alert.setTitle(getString(R.string.ask_export_N42));
                        alert.setMessage(getString(R.string.ask_suffix_text));

                        final EditText input = new EditText(this);
                        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
                        input.setText(AtomSpectraService.ForegroundSpectrum.getSuffix(), TextView.BufferType.EDITABLE);
                        alert.setView(input);
                        alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                            AtomSpectraService.ForegroundSpectrum.setSuffix(input.getText().toString());
                            TextView text = findViewById(R.id.suffixView);
                            text.setText(AtomSpectraService.ForegroundSpectrum.getSuffix());
                            saveN42(AtomSpectraService.ForegroundSpectrum.getSuffix());
                        });
                        alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
                        });
                        alert.show();
                    } catch (Exception e) {
                        Log.d(TAG, "exporting file FAIL");
                    }
                } else
                    ToastHelper.showToast(this, getString(R.string.perm_no_write_N42));
                break;
            case REQUEST_SHARE:
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Intent loadIntent = new Intent()
                            .setType("*/*")
                            .setAction(Intent.ACTION_GET_CONTENT);
                    startActivityForResult(Intent.createChooser(loadIntent, getString(R.string.ask_share)), SHARE_FILE_CODE);
                } else
                    ToastHelper.showToast(this, getString(R.string.perm_no_share));
                break;
            case REQUEST_FINE_GPS:
                if (grantResults.length > 1 && grantResults[0] != PackageManager.PERMISSION_GRANTED && grantResults[1] != PackageManager.PERMISSION_GRANTED) {
                    SharedPreferences.Editor editor = sharedPreferences.edit();
                    editor.putBoolean(Constants.CONFIG.CONF_ADD_GPS_TO_FILES, false);
                    editor.commit();
                    sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_SETTINGS).setPackage(Constants.PACKAGE_NAME));
                    ToastHelper.showToast(this, getString(R.string.perm_no_gps));
                }
                sendBroadcast(new Intent(Constants.ACTION.ACTION_CHECK_GPS_AVAILABILITY).setPackage(Constants.PACKAGE_NAME));
                break;
            case REQUEST_READ_DEVICE:
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Intent loadIntent = new Intent()
                            .setType("*/*")
                            .setAction(Intent.ACTION_GET_CONTENT);
                    startActivityForResult(Intent.createChooser(loadIntent, getString(R.string.ask_select_histogram)), SELECT_LOAD_DEVICE_CODE);
                } else
                    ToastHelper.showToast(this, getString(R.string.perm_no_read_device));
                break;
            case REQUEST_WRITE_DEVICE:
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    try {
                        final AlertDialog.Builder alert = new AlertDialog.Builder(this);
                        alert.setTitle(getString(R.string.ask_device_suffix));
                        alert.setMessage(getString(R.string.ask_suffix_text));

                        final EditText input = new EditText(this);
                        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
                        alert.setView(input);
                        alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                            String value = input.getText().toString();
                            saveDevice(value);
                        });
                        alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
                        });
                        alert.show();
                    } catch (Exception e) {
                        Log.d(TAG, "saving spectrum file FAIL");
                    }
                } else
                    ToastHelper.showToast(this, getString(R.string.perm_no_write_device));
                break;
            default:
                super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        Log.d(TAG, "-XxX-  onStart");
        // ToastHelper.showToast(this, "On start");
        active = true;
    }

    //Update destination directory on Android 7.0
    private void updateDestinationDirectory() {
        //prepare directory to work on Android under 7.0
        //on Android 8.0 and above system picker will be used
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            SharedPreferences.Editor prefEditor = sharedPreferences.edit();
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
                            folder = getApplicationContext().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
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
                ToastHelper.showToast(this, getString(R.string.storage_required));
            }
            prefEditor.apply();
        }
    }

    private void updateCalibrationMenu() {
        if (app_menu != null) {
            double[] coeffs = AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().getCoeffArray(5);
            app_menu.findItem(R.id.action_cal_point_1).setTitle(String.format(Locale.getDefault(), "c0: %.12g", coeffs[0]));
            app_menu.findItem(R.id.action_cal_point_2).setTitle(String.format(Locale.getDefault(), "c1: %.12g", coeffs[1]));
            app_menu.findItem(R.id.action_cal_point_3).setTitle(String.format(Locale.getDefault(), "c2: %.12g", coeffs[2]));
            app_menu.findItem(R.id.action_cal_point_4).setTitle(String.format(Locale.getDefault(), "c3: %.12g", coeffs[3]));
            app_menu.findItem(R.id.action_cal_point_5).setTitle(String.format(Locale.getDefault(), "c4: %.12g", coeffs[4]));

            Button button = findViewById(R.id.addCalibrationPointButton);
            if (AtomSpectraService.newCalibration.getPointsCount() >= Constants.MAX_CALIBRATION_POINTS) {
                button.setText("-");
                button.setEnabled(false);
                app_menu.findItem(R.id.action_cal_add_point).setEnabled(false);
            } else {
                button.setText(String.format(Locale.US, "%d", AtomSpectraService.newCalibration.getPointsCount() + 1));
                app_menu.findItem(R.id.action_cal_add_point).setEnabled(true);
            }
            app_menu.findItem(R.id.action_cal_calc).setEnabled(AtomSpectraService.newCalibration.getPointsCount() > 1);
            findViewById(R.id.calibrateButton).setEnabled(AtomSpectraService.newCalibration.getPointsCount() > 1);
            if (AtomSpectraService.newCalibration.getPointsCount() < 2) {
                app_menu.findItem(R.id.action_cal_draw_function).setChecked(false).setEnabled(false);
                AtomSpectraService.showCalibrationFunction = false;
            }
            app_menu.findItem(R.id.action_cal_new_point1).
                    setTitle(getString(R.string.cal_show_line, AtomSpectraService.newCalibration.getPointChannel(0), AtomSpectraService.newCalibration.getPointEnergy(0))).
                    setVisible(AtomSpectraService.newCalibration.getPointsCount() > 0);
            app_menu.findItem(R.id.action_cal_new_point2).
                    setTitle(getString(R.string.cal_show_line, AtomSpectraService.newCalibration.getPointChannel(1), AtomSpectraService.newCalibration.getPointEnergy(1))).
                    setVisible(AtomSpectraService.newCalibration.getPointsCount() > 1);
            app_menu.findItem(R.id.action_cal_new_point3).
                    setTitle(getString(R.string.cal_show_line, AtomSpectraService.newCalibration.getPointChannel(2), AtomSpectraService.newCalibration.getPointEnergy(2))).
                    setVisible(AtomSpectraService.newCalibration.getPointsCount() > 2);
            app_menu.findItem(R.id.action_cal_new_point4).
                    setTitle(getString(R.string.cal_show_line, AtomSpectraService.newCalibration.getPointChannel(3), AtomSpectraService.newCalibration.getPointEnergy(3))).
                    setVisible(AtomSpectraService.newCalibration.getPointsCount() > 3);
            app_menu.findItem(R.id.action_cal_new_point5).
                    setTitle(getString(R.string.cal_show_line, AtomSpectraService.newCalibration.getPointChannel(4), AtomSpectraService.newCalibration.getPointEnergy(4))).
                    setVisible(AtomSpectraService.newCalibration.getPointsCount() > 4);
            app_menu.findItem(R.id.action_cal_new_point6).
                    setTitle(getString(R.string.cal_show_line, AtomSpectraService.newCalibration.getPointChannel(5), AtomSpectraService.newCalibration.getPointEnergy(5))).
                    setVisible(AtomSpectraService.newCalibration.getPointsCount() > 5);
            app_menu.findItem(R.id.action_cal_new_point7).
                    setTitle(getString(R.string.cal_show_line, AtomSpectraService.newCalibration.getPointChannel(6), AtomSpectraService.newCalibration.getPointEnergy(6))).
                    setVisible(AtomSpectraService.newCalibration.getPointsCount() > 6);
            app_menu.findItem(R.id.action_cal_new_point8).
                    setTitle(getString(R.string.cal_show_line, AtomSpectraService.newCalibration.getPointChannel(7), AtomSpectraService.newCalibration.getPointEnergy(7))).
                    setVisible(AtomSpectraService.newCalibration.getPointsCount() > 7);
            app_menu.findItem(R.id.action_cal_new_point9).
                    setTitle(getString(R.string.cal_show_line, AtomSpectraService.newCalibration.getPointChannel(8), AtomSpectraService.newCalibration.getPointEnergy(8))).
                    setVisible(AtomSpectraService.newCalibration.getPointsCount() > 8);
            app_menu.findItem(R.id.action_cal_new_point10).
                    setTitle(getString(R.string.cal_show_line, AtomSpectraService.newCalibration.getPointChannel(9), AtomSpectraService.newCalibration.getPointEnergy(9))).
                    setVisible(AtomSpectraService.newCalibration.getPointsCount() > 9);

            boolean isUSB = AtomSpectraService.inputType == AtomSpectraService.INPUT_SERIAL;
            app_menu.findItem(R.id.action_cal_store_device)
                    .setEnabled(isUSB)
                    .setVisible(isUSB);
            app_menu.findItem(R.id.action_cal_retrieve_device)
                    .setEnabled(isUSB)
                    .setVisible(isUSB);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.atom_spectra, menu);
        app_menu = menu;
        boolean enable_back = !AtomSpectraService.BackgroundSpectrum.isEmpty();
        menu.findItem(R.id.action_cal_channel).setTitle(getString(R.string.calibration_channel_format, AtomSpectraService.lastCalibrationChannel));

        menu.findItem(R.id.action_background_show).setChecked(AtomSpectraService.background_show);
        menu.findItem(R.id.action_background_show).setEnabled(enable_back);
        menu.findItem(R.id.action_background_suffix).setEnabled(enable_back);
        menu.findItem(R.id.action_background_subtract).setEnabled(AtomSpectraService.background_show);
        menu.findItem(R.id.action_background_subtract).setChecked(background_subtract);
        menu.findItem(R.id.action_background_clear).setEnabled(enable_back);
        menu.findItem(R.id.action_background_save).setEnabled(enable_back);
        menu.findItem(R.id.action_cal_draw_function).setChecked(AtomSpectraService.showCalibrationFunction);
        menu.findItem(R.id.action_cal_draw_function).setEnabled(AtomSpectraService.newCalibration != null && AtomSpectraService.newCalibration.getPointsCount() > 1);

        menu.findItem(R.id.action_hist_smooth).setTitle(AtomSpectraService.setSmooth ? getString(R.string.hist_unsmooth) : getString(R.string.hist_smooth));
        updateCalibrationMenu();
        TextView view = findViewById(R.id.backgroundSuffixView);
        view.setVisibility(enable_back ? TextView.VISIBLE : TextView.INVISIBLE);

        updateRecordStatusMenu();
        updateVersionInMenu();
        updateSpectrogramMenu();

        return true;
    }


    private static IntentFilter makeAtomSpectraUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(AtomSpectraService.ACTION_DATA_AVAILABLE);
        intentFilter.addAction(AtomSpectraService.ACTION_RECORDING_SUSPENDED);
        intentFilter.addAction(AtomSpectraService.ACTION_RECORDING_RESUMED);
        intentFilter.addAction(Constants.ACTION.ACTION_UPDATE_GPS);
        intentFilter.addAction(Constants.ACTION.ACTION_CLOSE_APP);
        intentFilter.addAction(Constants.ACTION.ACTION_AUDIO_CHANGED);
        intentFilter.addAction(Constants.ACTION.ACTION_UPDATE_MENU);
        intentFilter.addAction(Constants.ACTION.ACTION_GET_USB_PERMISSION);
        intentFilter.addAction(Constants.ACTION.ACTION_USB_HAS_ANSWER);
        intentFilter.addAction(Constants.ACTION.ACTION_UPDATE_CALIBRATION);
        return intentFilter;
    }

    private final void showCalibrationChecksumAlert(String[] dataArray) {
        final AlertDialog.Builder alert = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.cal_wrong_checksum))
                .setMessage(getString(R.string.cal_load_anyway))
                .setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                    final double[] coeffs = new double[5];
                    StringBuilder combine = new StringBuilder();
                    for (int i = 0; i < 5; i++) {
                        coeffs[i] = Double.longBitsToDouble(Long.parseUnsignedLong(dataArray[2 * i] + dataArray[2 * i + 1], 16));
                        combine.append(dataArray[2 * i]).append(dataArray[2 * i + 1]);
                    }
                    Calibration newCalibration = new Calibration();
                    newCalibration.Calculate(coeffs);
                    if (newCalibration.isCorrect()) {
                        AtomSpectraService.ForegroundSpectrum.setSpectrumCalibration(newCalibration);
                        ToastHelper.showToast(this, getString(R.string.cal_apply_usb));
                        updateCalibrationMenu();
                        sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_GRAPH).setPackage(Constants.PACKAGE_NAME));
                    } else {
                        ToastHelper.showToast(this, getString(R.string.cal_wrong_usb));
                    }
                })
                .setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
                    ToastHelper.showToast(this, getString(R.string.cal_wrong_checksum));
                });
        alert.show();
    }

    private final BroadcastReceiver mDataUpdateReceiver = new BroadcastReceiver() {
        @SuppressLint("ApplySharedPref")
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (AtomSpectraService.ACTION_RECORDING_SUSPENDED.equals(action)) {
                // ToastHelper.showToast(context,"suspended intent");
                checkRecordingSuspended();
            }

            if (AtomSpectraService.ACTION_RECORDING_RESUMED.equals(action)) {
                // ToastHelper.showToast(context,"resumed intent");
                dismissRecordingSuspendedDialog();
            }

            if (AtomSpectraService.ACTION_DATA_AVAILABLE.equals(action)) {
                Bundle mBundle = intent.getExtras();
                if (mBundle != null) {
                    int display_mode = AtomSpectraService.getDisplayMode();
                    int cp1s = mBundle.getInt(AtomSpectraService.EXTRA_DATA_INT_CP1S);
                    int cp1s_interval = mBundle.getInt(AtomSpectraService.EXTRA_DATA_INT_CP1S_INTERVAL);

                    switch (display_mode) {
                        case Constants.DISPLAY_MODE_SPECTRUM:
                            double total_time = mBundle.getDouble(AtomSpectraService.EXTRA_DATA_INT_FG_TOTAL_TIME);
                            long total_counts = mBundle.getLong(AtomSpectraService.EXTRA_DATA_LONG_TOTAL_FG_COUNTS);

                            statusLineTopText.setText(getString(R.string.cps_show, cp1s, cp1s_interval));
                            statusLineMiddleText.setText(getString(R.string.cps_average_show, total_time > 1 ? total_counts / total_time : 0));
                            statusLineBottomText.setText(getString(R.string.total_time_format, total_time));
                            statusLineTopText.setTextColor(Color.WHITE);
                            statusLineMiddleText.setTextColor(Color.WHITE);
                            statusLineBottomText.setTextColor(Color.WHITE);
                            break;
                        case Constants.DISPLAY_MODE_SPECTRUM_CHANGE:
                            long delta_counts = mBundle.getLong(AtomSpectraService.EXTRA_DATA_LONG_SP_CHNG_FG_TOTAL_COUNTS);
                            long delta_back_counts = mBundle.getLong(AtomSpectraService.EXTRA_DATA_LONG_SP_CHNG_BG_TOTAL_COUNTS);
                            int delta_time = mBundle.getInt(AtomSpectraService.EXTRA_DATA_INT_SP_CHNG_FG_TOTAL_TIME);
                            int delta_back_time = mBundle.getInt(AtomSpectraService.EXTRA_DATA_INT_SP_CHNG_BG_TOTAL_TIME);

                            double delta_cps = delta_time > 0 ? delta_counts / (double) delta_time : 0.0;
                            double delta_back_cps = delta_back_time > 0 ? delta_back_counts / (double) delta_back_time : 0.0;

                            statusLineTopText.setText(getString(R.string.cps_show, cp1s, cp1s_interval));
                            statusLineMiddleText.setText(getString(R.string.cps_delta_show, delta_cps, delta_back_cps));
                            statusLineBottomText.setText(getString(R.string.delta_time_format, delta_time, delta_back_time));
                            statusLineTopText.setTextColor(Color.WHITE);
                            statusLineMiddleText.setTextColor(Color.WHITE);
                            statusLineBottomText.setTextColor(Color.WHITE);
                            break;
                        case Constants.DISPLAY_MODE_SEARCH:
                            statusLineTopText.setText(getString(R.string.cps_show, cp1s, cp1s_interval));
                            statusLineTopText.setTextColor(Color.WHITE);

                            long error95Percent = 0;
                            boolean isAlarmMode = AtomSpectraService.intervalSearchAlarmEnabled;
                            AtomSpectraService.AlarmBaseline baseline = AtomSpectraService.getIntervalSearchAlarmBaseline();
                            boolean isIntervalMode = Constants.DISPLAY_DOSE_INTERVAL.equals(DisplayDose);
                            if (isAlarmMode && isIntervalMode) {
                                error95Percent = Math.round(2 * baseline.getBaselineError());
                                if (baseline.isStable()) {
                                    statusLineBottomText.setText(getString(R.string.cps_alarm_levels, baseline.getAlarmLevelHigh(), baseline.getAlarmLevelLow()));
                                    statusLineBottomText.setTextColor(AtomSpectraShapeView.COLOR_ALARM_CPS);
                                } else {
                                    statusLineBottomText.setText(getString(R.string.cps_alarm_baseline_timer, baseline.getRemainingTime(), baseline.getBaseline(), error95Percent));
                                    statusLineBottomText.setTextColor(AtomSpectraShapeView.COLOR_BASELINE_CPS);
                                }
                            }

                            if (isIntervalMode) {
                                double search_int_cps = mBundle.getDouble(AtomSpectraService.EXTRA_DATA_DOUBLE_SEARCH_INT_CPS);
                                double search_int_cps_error = mBundle.getDouble(AtomSpectraService.EXTRA_DATA_DOUBLE_SEARCH_INT_CPS_ERROR);
                                error95Percent = Math.round(search_int_cps_error * 2);

                                statusLineMiddleText.setText(getString(R.string.dose_rate_interval_prefix, formatCpsWithError(search_int_cps, error95Percent)));
                                statusLineMiddleText.setTextColor(AtomSpectraShapeView.COLOR_INTERVAL_CPS);
                                if (!isAlarmMode) {
                                    statusLineBottomText.setText(R.string.interval_search_sound_disabled_label);
                                    statusLineBottomText.setTextColor(AtomSpectraShapeView.COLOR_ALARM_CPS);
                                }
                            } else {
                                double dose_rate_c = mBundle.getDouble(AtomSpectraService.EXTRA_DATA_DOUBLE_SEARCH_DR_C);
                                double dose_rate_c_error = mBundle.getDouble(AtomSpectraService.EXTRA_DATA_DOUBLE_SEARCH_DR_C_ERROR);
                                long error95PercentC = Math.round(dose_rate_c_error * 2);
                                double dose_rate_n = mBundle.getDouble(AtomSpectraService.EXTRA_DATA_DOUBLE_SEARCH_DR_N);
                                double dose_rate_n_error = mBundle.getDouble(AtomSpectraService.EXTRA_DATA_DOUBLE_SEARCH_DR_N_ERROR);
                                long error95PercentN = Math.round(dose_rate_n_error * 2);

                                statusLineMiddleText.setText(getString(R.string.dose_rate_noncompensated_prefix, formatDoseRateWithError(dose_rate_n, error95PercentN)));
                                statusLineMiddleText.setTextColor(AtomSpectraShapeView.COLOR_NON_COMPENSATED_DOSE);
                                statusLineBottomText.setText(getString(R.string.dose_rate_compensated_prefix, formatDoseRateWithError(dose_rate_c, error95PercentC)));
                                statusLineBottomText.setTextColor(AtomSpectraShapeView.COLOR_COMPENSATED_DOSE);
                            }
                            break;
                        case Constants.DISPLAY_MODE_SPECTROGRAM:
                        default:
                            // nothing to do so far
                            break;
                    }

                    showCursorInfo(false);

                    // main data
                    int num_first_channel = AtomSpectraService.getFirstChannel();
                    int num_scale_factor = AtomSpectraService.getScaleFactor();
                    switch (display_mode) {
                        case Constants.DISPLAY_MODE_SPECTRUM:
                            if (AtomSpectraService.showCalibrationFunction) {
                                // calibration function view
                                // ToastHelper.showToast(context, "show calibration in spectrum mode");
                                double[] calibration_data = mBundle.getDoubleArray(AtomSpectraService.EXTRA_DATA_ARRAY_DOUBLE_CALIBRATION_FUNCTION);
                                if (calibration_data == null) {
                                    calibration_data = new double[1024];
                                }
                                mAtomSpectraShapeView.showCalibration(
                                        calibration_data,
                                        reducedTo,
                                        num_first_channel,
                                        num_first_channel + (Constants.WINDOW_OUTPUT_SIZE << (Constants.SCALE_MAX - num_scale_factor)),
                                        zoom_factor,
                                        num_scale_factor);
                            } else {
                                // spectrum view
                                double[] histogram = mBundle.getDoubleArray(AtomSpectraService.EXTRA_DATA_ARRAY_DOUBLE_FG);
                                if (histogram == null) {
                                    histogram = new double[1024];
                                }
                                double[] hist_back = mBundle.getDoubleArray(AtomSpectraService.EXTRA_DATA_ARRAY_DOUBLE_BG);
                                if (hist_back == null) {
                                    hist_back = new double[1024];
                                }
                                boolean show_back = mBundle.getBoolean(AtomSpectraService.EXTRA_DATA_BOOL_SHOW_BG, false);

                                if (XCalibrated) {
                                    // ToastHelper.showToast(context, "spectrum calibrated");
                                    mAtomSpectraShapeView.showSpectrum(
                                            histogram,
                                            hist_back,
                                            show_back,
                                            background_subtract,
                                            true,
                                            reducedTo,
                                            logScale,
                                            barMode,
                                            (float) AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().getEnergyFromEnergyChannel(num_first_channel, AtomSpectraService.lastCalibrationChannel),
                                            (float) AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().getEnergyFromEnergyChannel(num_first_channel + (Constants.WINDOW_OUTPUT_SIZE << (Constants.SCALE_MAX - num_scale_factor)), AtomSpectraService.lastCalibrationChannel),
                                            getString(R.string.graph_show_kev),
                                            zoom_factor,
                                            num_scale_factor,
                                            (float) AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().toEnergy(cursor_x),
                                            false);
                                } else {
                                    // ToastHelper.showToast(context, "spectrum uncalibrated");
                                    mAtomSpectraShapeView.showSpectrum(
                                            histogram,
                                            hist_back,
                                            show_back,
                                            background_subtract,
                                            false,
                                            reducedTo,
                                            logScale,
                                            barMode,
                                            num_first_channel,
                                            num_first_channel + (Constants.WINDOW_OUTPUT_SIZE << (Constants.SCALE_MAX - num_scale_factor)),
                                            getString(R.string.graph_show_channel),
                                            zoom_factor,
                                            num_scale_factor,
                                            (float) cursor_x,
                                            false);
                                }
                            }
                            break;
                        case Constants.DISPLAY_MODE_SPECTRUM_CHANGE:
                            double[] sp_change = mBundle.getDoubleArray(AtomSpectraService.EXTRA_DATA_ARRAY_DOUBLE_SP_CHNG_FG);
                            if (sp_change == null) {
                                sp_change = new double[1024];
                            }
                            double[] sp_change_back = mBundle.getDoubleArray(AtomSpectraService.EXTRA_DATA_ARRAY_DOUBLE_SP_CHNG_BG);
                            if (sp_change_back == null) {
                                sp_change_back = new double[1024];
                            }

                            if (XCalibrated) {
                                // ToastHelper.showToast(context, "spectrum change calibrated");
                                mAtomSpectraShapeView.showSpectrum(
                                        sp_change,
                                        sp_change_back,
                                        true,
                                        false,
                                        true,
                                        reducedTo,
                                        logScale,
                                        barMode,
                                        (float) AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().getEnergyFromEnergyChannel(num_first_channel, AtomSpectraService.lastCalibrationChannel),
                                        (float) AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().getEnergyFromEnergyChannel(num_first_channel + (Constants.WINDOW_OUTPUT_SIZE << (Constants.SCALE_MAX - num_scale_factor)), AtomSpectraService.lastCalibrationChannel),
                                        getString(R.string.graph_show_kev),
                                        zoom_factor,
                                        num_scale_factor,
                                        (float) AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().toEnergy(cursor_x),
                                        true);
                            } else {
                                // ToastHelper.showToast(context, "spectrum change uncalibrated");
                                mAtomSpectraShapeView.showSpectrum(
                                        sp_change,
                                        sp_change_back,
                                        true,
                                        false,
                                        false,
                                        reducedTo,
                                        logScale,
                                        barMode,
                                        num_first_channel,
                                        num_first_channel + (Constants.WINDOW_OUTPUT_SIZE << (Constants.SCALE_MAX - num_scale_factor)),
                                        getString(R.string.graph_show_channel),
                                        zoom_factor,
                                        num_scale_factor,
                                        (float) cursor_x,
                                        true);
                            }
                            break;
                        case Constants.DISPLAY_MODE_SEARCH:
                            // ToastHelper.ShowToast(context, "search mode");
                            switch (DisplayDose) {
                                case Constants.DISPLAY_DOSE_INTERVAL:
                                    double[] interval_search_data = mBundle.getDoubleArray(AtomSpectraService.EXTRA_DATA_ARRAY_DOUBLE_SEARCH_INT_CPS_HISTORY);
                                    double[] alarmLevelHigh = mBundle.getDoubleArray(AtomSpectraService.EXTRA_DATA_ARRAY_DOUBLE_SEARCH_INT_CPS_HIGH_ALARM_HISTORY);
                                    double[] alarmLevelLow = mBundle.getDoubleArray(AtomSpectraService.EXTRA_DATA_ARRAY_DOUBLE_SEARCH_INT_CPS_LOW_ALARM_HISTORY);
                                    double[] alarmBaseline = mBundle.getDoubleArray(AtomSpectraService.EXTRA_DATA_ARRAY_DOUBLE_SEARCH_INT_CPS_BASELINE_HISTORY);

                                    if (interval_search_data == null) {
                                        interval_search_data = new double[AtomSpectraService.SEARCH_WINDOW_SIZE];
                                    }
                                    if (alarmLevelHigh == null) {
                                        alarmLevelHigh = new double[AtomSpectraService.SEARCH_WINDOW_SIZE];
                                    }
                                    if (alarmLevelLow == null) {
                                        alarmLevelLow = new double[AtomSpectraService.SEARCH_WINDOW_SIZE];
                                    }
                                    if (alarmBaseline == null) {
                                        alarmBaseline = new double[AtomSpectraService.SEARCH_WINDOW_SIZE];
                                    }

                                    mAtomSpectraShapeView.showIntervalSearch(
                                            interval_search_data,
                                            alarmLevelHigh,
                                            alarmLevelLow,
                                            alarmBaseline,
                                            sharedPreferences.getBoolean(Constants.CONFIG.CONF_OUTPUT_SOUND, false),
                                            zoom_factor
                                    );
                                    break;
                                case Constants.DISPLAY_DOSE_COMPENSATED:
                                case Constants.DISPLAY_DOSE_NON_COMPENSATED:
                                    double[] compensated_data = mBundle.getDoubleArray(AtomSpectraService.EXTRA_DATA_ARRAY_DOUBLE_SEARCH_DR_C_HISTORY);
                                    double[] non_compensated_data = mBundle.getDoubleArray(AtomSpectraService.EXTRA_DATA_ARRAY_DOUBLE_SEARCH_DR_N_HISTORY);

                                    if (compensated_data == null) {
                                        compensated_data = new double[AtomSpectraService.SEARCH_WINDOW_SIZE];
                                    }
                                    if (non_compensated_data == null) {
                                        non_compensated_data = new double[AtomSpectraService.SEARCH_WINDOW_SIZE];
                                    }

                                    mAtomSpectraShapeView.showDoseSearch(
                                            non_compensated_data,
                                            compensated_data,
                                            zoom_factor
                                    );
                                    break;
                            }

                            break;
                        case Constants.DISPLAY_MODE_SPECTROGRAM:
                        default:
                            // nothing to do so far
                            break;
                    }
                }
            }
            if (Constants.ACTION.ACTION_CLOSE_APP.equals(action)) {
                finishAndRemoveTask();
            }
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                UsbDevice device;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
                } else {
                    device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                }
                if ((device != null)) {
                    if (AtomSpectraSerial.isSpectraPro(device)) {
                        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
                        if (!usbManager.hasPermission(device)) {
                            PendingIntent pi = PendingIntent.getBroadcast(context, 0, new Intent(Constants.ACTION.ACTION_GET_USB_PERMISSION), mutabilityFlag);
                            usbManager.requestPermission(device, pi);
                        } else {
                            Intent intentAttached = new Intent(Constants.ACTION.ACTION_USB_ATTACHED).setPackage(Constants.PACKAGE_NAME);
                            intentAttached.putExtra(Constants.USB_DEVICE, device);
                            context.sendBroadcast(intentAttached);
                        }
                    }
                }
            }
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                Intent intentDetached = new Intent(Constants.ACTION.ACTION_USB_DETACHED).setPackage(Constants.PACKAGE_NAME);
                context.sendBroadcast(intentDetached);
            }
            if (Constants.ACTION.ACTION_GET_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        UsbDevice device;
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
                        } else {
                            device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                        }
                        Intent intentAttached = new Intent(Constants.ACTION.ACTION_USB_ATTACHED).setPackage(Constants.PACKAGE_NAME);
                        intentAttached.putExtra(Constants.USB_DEVICE, device);
                        context.sendBroadcast(intentAttached);
                    }
                }
            }
            if (Constants.ACTION.ACTION_USB_HAS_ANSWER.equals(action)) {
                String id = intent.getStringExtra(AtomSpectraSerial.EXTRA_ID);
                String data = intent.getStringExtra(AtomSpectraSerial.EXTRA_RESULT);
                if (data != null) {
                    if (GET_CALIBRATION.equals(id)) {
                        if (AtomSpectraSerial.COMMAND_RESULT_TIMEOUT.equals(data)) {
                            ToastHelper.showToast(context, getString(R.string.cal_timeout));
                            return;
                        }

                        if (AtomSpectraSerial.COMMAND_RESULT_ERR.equals(data)) {
                            // TODO: localize
                            ToastHelper.showToast(context, "Error from USB during calibration read");
                            return;
                        }

                        final String[] dataArray = data.split("\\s+");
                        StringBuilder combine = new StringBuilder();
                        final double[] coeffs = new double[5];
                        for (int i = 0; i < 5; i++) {
                            coeffs[i] = Double.longBitsToDouble(Long.parseUnsignedLong(dataArray[2 * i] + dataArray[2 * i + 1], 16));
                            combine.append(dataArray[2 * i]).append(dataArray[2 * i + 1]);
                        }
                        long crc = AtomSpectraSerial.crc32(combine.toString().getBytes());
                        if (crc == Long.parseUnsignedLong(dataArray[10], 16)) {
                            Calibration newCalibration = new Calibration();
                            newCalibration.Calculate(coeffs);
                            if (newCalibration.isCorrect()) {
                                AtomSpectraService.ForegroundSpectrum.setSpectrumCalibration(newCalibration);
                                ToastHelper.showToast(context, getString(R.string.cal_apply_usb));
                                updateCalibrationMenu();
                                sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_GRAPH).setPackage(Constants.PACKAGE_NAME));
                            } else {
                                ToastHelper.showToast(context, getString(R.string.cal_wrong_usb));
                            }
                        } else {
                            showCalibrationChecksumAlert(dataArray);
                        }
                    } else if (SEND_CALIBRATION.equals(id)) {
                        String command = intent.getStringExtra(AtomSpectraSerial.EXTRA_COMMAND);
                        gotAnswers = gotAnswers > 0 ? gotAnswers - 1 : 0;
                        boolean onlyZeros = true;
                        StringBuilder res_all = new StringBuilder();
                        if (AtomSpectraSerial.COMMAND_RESULT_OK.equals(data)) {
                            for (int i = 0; i < 11; i++) {
                                if (calibrationAnswers[i].equals(command))
                                    calibrationAnswers[i] = "";
                                if (gotAnswers == 0 && !("".equals(calibrationAnswers[i])))
                                    onlyZeros = false;
                                res_all.append(" : ").append(calibrationAnswers[i]);
                            }
                        } else {
                            onlyZeros = false;
                        }
                        if (gotAnswers == 0) {
                            if (onlyZeros)
                                ToastHelper.showToast(context, getString(R.string.cal_store_usb));
                            else {
                                ToastHelper.showToast(context, getString(R.string.cal_wrong_store_usb));
                            }
                        }
                    }
                }
            }
            if (Constants.ACTION.ACTION_UPDATE_MENU.equals(action)) {
                updateRecordStatusMenu();
                updateSelectedInputIndicator();
                updateCalibrationMenu();
                updateSpectrogramMenu();

                Button searchBaselineButton = findViewById(R.id.searchBaselineButton);
                searchBaselineButton.setEnabled((Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) && sharedPreferences.getBoolean(Constants.CONFIG.CONF_OUTPUT_SOUND, false));
                TextView view;
                view = findViewById(R.id.suffixView);
                if (view != null) {
                    view.setText(AtomSpectraService.ForegroundSpectrum.getSuffix());
                }
                view = findViewById(R.id.backgroundSuffixView);
                if (view != null) {
                    view.setText(AtomSpectraService.BackgroundSpectrum.getSuffix());
                }
            }
            if (Constants.ACTION.ACTION_UPDATE_CALIBRATION.equals(action)) {
                if (intent.getBooleanExtra(Constants.ACTION_PARAMETERS.UPDATE_USB_CALIBRATION, false) || AtomSpectraService.inputType == AtomSpectraService.INPUT_SERIAL) {
                    getCalibrationSettingsFromDevice();
                } else {
                    getCalibrationSettingsFromMemory();
                }
            }
        }

    };

    private String formatDoseRateWithError(double dose_rate, long error95Percent) {
        if (dose_rate < 10) {
            return getString(R.string.dose_rate_1uSv_format, dose_rate, error95Percent);
        } else if (dose_rate < 100) {
            return getString(R.string.dose_rate_10uSv_format, dose_rate, error95Percent);
        } else if (dose_rate < 1000) {
            return getString(R.string.dose_rate_100uSv_format, dose_rate, error95Percent);
        } else if (dose_rate < 10000) {
            return getString(R.string.dose_rate_1mSv_format, dose_rate / 1000.0, error95Percent);
        } else if (dose_rate < 100000) {
            return getString(R.string.dose_rate_10mSv_format, dose_rate / 1000.0, error95Percent);
        } else { // > 100 mSv/h
            return getString(R.string.dose_rate_100mSv_format, dose_rate / 1000.0, error95Percent);
        }
    }

    private String formatCpsWithError(double search_int_cps, long error95Percent) {
        if (search_int_cps < 10) {
            return getString(R.string.dose_rate_1cps_format, search_int_cps, error95Percent);
        } else if (search_int_cps < 100) {
            return getString(R.string.dose_rate_10cps_format, search_int_cps, error95Percent);
        } else if (search_int_cps < 1000) {
            return getString(R.string.dose_rate_100cps_format, search_int_cps, error95Percent);
        } else if (search_int_cps < 10000) {
            return getString(R.string.dose_rate_1kcps_format, search_int_cps / 1000.0, error95Percent);
        } else if (search_int_cps < 100000) {
            return getString(R.string.dose_rate_10kcps_format, search_int_cps / 1000.0, error95Percent);
        } else { // > 100k cps
            return getString(R.string.dose_rate_100kcps_format, search_int_cps / 1000.0, error95Percent);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // ToastHelper.showToast(this, "On resume");

        reducedTo = sharedPreferences.getInt(Constants.CONFIG.CONF_REDUCED_TO, Constants.VIEW_CHANNELS_DEFAULT);
        updateDisplayModeViews();
        checkSpectrogramIsLoading();
        checkRecordingSuspended();

        sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_GRAPH).setPackage(Constants.PACKAGE_NAME));
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "-XxX-  pause");
        // ToastHelper.showToast(this, "On pause");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        buttonsTimer.cancel();
        unregisterReceiver(mDataUpdateReceiver);

        getApplicationContext().unbindService(mServiceConnection);
        background_subtract = false;
        if (app_menu != null) {
            app_menu.findItem(R.id.action_background_show).setChecked(false);
            app_menu.findItem(R.id.action_background_subtract).setChecked(false);
            app_menu.findItem(R.id.action_background_save).setEnabled(false);
            app_menu.findItem(R.id.action_background_suffix).setEnabled(false);
        }
        cursor_x = -1;
        mTextView.setVisibility(View.INVISIBLE);
        mLayoutView.setVisibility(LinearLayout.INVISIBLE);
        mTextView = null;
        mLayoutView = null;
        dismissRecordingSuspendedDialog();
        dismissSpectrogramLoadingDialog();
        Log.d(TAG, "-XxX-");
    }

    @Override
    protected void onStop() {
        super.onStop();
        // ToastHelper.showToast(this, "On stop");
        active = false;
        Log.d(TAG, "-XxX-  stop");
    }

    // Code to manage Service lifecycle.
    private final ServiceConnection mServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            mAtomSpectraService = ((AtomSpectraService.LocalBinder) service).getService();
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mAtomSpectraService = null;
        }
    };

    public void onClickShape(View v) {
    }

    public void onClickDeleteSpc(View v) {
        final AlertDialog.Builder alert = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.hist_ask_delete_title))
                .setMessage(getString(R.string.hist_ask_delete_text))
                .setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                    AtomSpectraIsotopes.showFoundIsotopes = false;
                    AtomSpectraIsotopes.foundList.clear();
                    sendBroadcast(new Intent(Constants.ACTION.ACTION_CLEAR_SPECTRUM).setPackage(Constants.PACKAGE_NAME));
                    ((TextView) findViewById(R.id.suffixView)).setText(getString(R.string.hist_suffix));
                })
                .setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
                });
        alert.show();
    }

    public void onClick_renderModeSpectrum(View v) {
        setDisplayMode(Constants.DISPLAY_MODE_SPECTRUM);
    }

    public void onClick_renderModeSpectrumChange(View v) {
        setDisplayMode(Constants.DISPLAY_MODE_SPECTRUM_CHANGE);
    }

    public void onClick_renderModeSearch(View v) {
        setDisplayMode(Constants.DISPLAY_MODE_SEARCH);
        hideSeekChannel();
    }

    public void onClick_renderModeSpectrogram(View v) {
        showSpectrogramView();
    }

    @SuppressLint("ApplySharedPref")
    public void onClick_xAxisScale(View v) {
        setXCalibrated(!XCalibrated);
        SharedPreferences.Editor prefEditor = sharedPreferences.edit();
        prefEditor.putBoolean(Constants.CONFIG.CONF_CALIBRATED, XCalibrated);
        prefEditor.commit();

        sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_GRAPH).setPackage(Constants.PACKAGE_NAME));
    }

    @SuppressLint("ApplySharedPref")
    public void onClick_fms(View v) {
        int nextMode = searchFMSNextMode[sharedPreferences.getInt(Constants.CONFIG.CONF_SEARCH_MODE, 0)];
        SharedPreferences.Editor prefEditor = sharedPreferences.edit();
        prefEditor.putInt(Constants.CONFIG.CONF_SEARCH_MODE, nextMode);
        prefEditor.commit();
        fmsButton.setText(searchFMSNames[nextMode]);
        sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_GRAPH).setPackage(Constants.PACKAGE_NAME));
    }

    @SuppressLint("ApplySharedPref")
    public void onClick_Dose(View v) {
        String newDisplayDose;
        switch (DisplayDose) {
            case Constants.DISPLAY_DOSE_COMPENSATED:
            case Constants.DISPLAY_DOSE_NON_COMPENSATED:
                newDisplayDose = Constants.DISPLAY_DOSE_INTERVAL;
                break;
            case Constants.DISPLAY_DOSE_INTERVAL:
                newDisplayDose = Constants.DISPLAY_DOSE_COMPENSATED;
                break;
            default:
                newDisplayDose = Constants.DISPLAY_DOSE_DEFAULT;
                break;
        }
        setDisplayDose(newDisplayDose);
        SharedPreferences.Editor prefEditor = sharedPreferences.edit();
        prefEditor.putString(Constants.CONFIG.CONF_DISPLAY_DOSE, newDisplayDose);
        prefEditor.commit();

        // TODO: update button text
        sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_GRAPH).setPackage(Constants.PACKAGE_NAME));
    }

    public void onClick_Sound(View v) {
        AtomSpectraService.AlarmBaseline baseline = AtomSpectraService.getIntervalSearchAlarmBaseline();
        baseline.reset();
    }

    public void onClick_Channel(View v) {
        final AlertDialog.Builder alert = new AlertDialog.Builder(this);

        alert.setTitle(getString(R.string.ask_channel_title));
        alert.setMessage(getString(R.string.ask_channel_text, cursor_x));

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        alert.setView(input);

        alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
            String value = input.getText().toString();
            int intValue = 0;
            try {
                intValue = Integer.parseInt(value);
            } catch (NumberFormatException nfe) {
                //System.out.println("Could not parse " + nfe);
            }
            intValue = StrictMath.max(0, StrictMath.min(Constants.NUM_HIST_POINTS - 1, intValue));
            cursor_x = intValue;
            showCursorInfo(true);
        });
        alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
            // nothing.
        });
        alert.show();
    }

    @Override
    public void onGesture(GestureOverlayView overlay, MotionEvent event) {
        // TODO Auto-generated method stub

    }

    @Override
    public void onGestureCancelled(GestureOverlayView overlay, MotionEvent event) {
        // TODO Auto-generated method stub

    }

    @Override
    public void onGestureEnded(GestureOverlayView overlay, MotionEvent event) {
        // TODO Auto-generated method stub

    }

    @Override
    public void onGestureStarted(GestureOverlayView overlay, MotionEvent event) {
        // TODO Auto-generated method stub

    }

    private void getCalibrationSettingsFromDevice() {
        if (AtomSpectraService.inputType != AtomSpectraService.INPUT_SERIAL) {
            ToastHelper.showToast(getApplicationContext(), "USB device is not available");
            return;
        }

        sendBroadcast(new Intent(Constants.ACTION.ACTION_SEND_USB_COMMAND)
                .putExtra(Constants.ACTION_PARAMETERS.USB_COMMAND_ID, GET_CALIBRATION)
                .putExtra(Constants.ACTION_PARAMETERS.USB_COMMAND_DATA, "-cal").setPackage(Constants.PACKAGE_NAME));
    }

    private void getCalibrationSettingsFromMemory() {
        int poliSize = sharedPreferences.getInt(Constants.CONFIG.CONF_CAL_POLI_SIZE, -1);
        Calibration newHistCalibration = new Calibration();
        if (poliSize == -1) {
            //old calibration is found or nothing, use old style loading
            int RightCal = Constants.NUM_HIST_POINTS - 1;
            double RightCalE = 3000.0;
            int LeftCal = 0;
            double LeftCalE = (float) 0.0;
            newHistCalibration.addPoint(LeftCal, LeftCalE);
            newHistCalibration.addPoint(RightCal, RightCalE);
            newHistCalibration.Calculate();
        } else {
            double x = sharedPreferences.getFloat(PrefHelper.configCalibrationCoefficient(0), -1000);
            int Cal = sharedPreferences.getInt(PrefHelper.configCalibrationChannel(1), -1);
            if (x != -1000 || Cal == -1) {
                double[] coeffs = new double[poliSize + 1];
                for (int i = 0; i <= poliSize; i++)
                    coeffs[i] = sharedPreferences.getFloat(PrefHelper.configCalibrationCoefficient(i), 1);
                newHistCalibration.Calculate(coeffs);
            } else {
                double CalE;
                for (int i = 1; i <= poliSize + 1; i++) {
                    Cal = sharedPreferences.getInt(PrefHelper.configCalibrationChannel(i), (Constants.NUM_HIST_POINTS - 1) * (i - 1) / poliSize);
                    CalE = sharedPreferences.getFloat(PrefHelper.configCalibrationEnergy(i), (float) 3000.0 * (i - 1) / poliSize);
                    newHistCalibration.addPoint(Cal, CalE);
                }
                newHistCalibration.Calculate();
                double[] coeffs = newHistCalibration.getCoeffArray();
                SharedPreferences.Editor editor = sharedPreferences.edit();
                for (int i = 0; i <= newHistCalibration.getFactor(); i++) {
                    editor.putFloat(PrefHelper.configCalibrationCoefficient(i), (float) coeffs[i]);
                    editor.remove(PrefHelper.configCalibrationChannel(i + 1));
                    editor.remove(PrefHelper.configCalibrationEnergy(i));
                }
                editor.apply();
            }
        }
        AtomSpectraService.ForegroundSpectrum.setSpectrumCalibration(newHistCalibration);
        AtomSpectraService.recalculateInterval();
        AtomSpectraService.lastCalibrationChannel = sharedPreferences.getInt(Constants.CONFIG.CONF_LAST_CHANNEL, Constants.NUM_HIST_POINTS);
        updateCalibrationMenu();

        sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_GRAPH).setPackage(Constants.PACKAGE_NAME));
    }

    private void setCalibrationSettingsToDevice() {
        if (AtomSpectraService.inputType != AtomSpectraService.INPUT_SERIAL) {
            ToastHelper.showToast(getApplicationContext(), "USB device is not available");
            return;
        }

        ToastHelper.showToast(getApplicationContext(), getString(R.string.cal_store_usb_wait));
        double[] coeffs = AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().getCoeffArray(5);
        StringBuilder val;
        StringBuilder allCalibration = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            val = new StringBuilder(Long.toHexString(Double.doubleToRawLongBits(coeffs[i])));
            while (val.length() < 16) {
                val.insert(0, "0");
            }
            allCalibration.append(val.substring(0, 8).toUpperCase(Locale.US)).append(val.substring(8, 16).toUpperCase(Locale.US));
            calibrationAnswers[2 * i] = String.format(Locale.US, "-cal %d %s", 2 * i, val.substring(0, 8));
            calibrationAnswers[2 * i + 1] = String.format(Locale.US, "-cal %d %s", 2 * i + 1, val.substring(8, 16));
        }
        //ToastHelper.showToast(getApplicationContext(), allCalibration);
        long crc = AtomSpectraSerial.crc32(allCalibration.toString().getBytes());
        val = new StringBuilder(Long.toHexString(crc));
        while (val.length() < 8) {
            val.insert(0, "0");
        }
        calibrationAnswers[10] = String.format(Locale.US, "-cal 10 %s", val);
        gotAnswers = 11;
        //send data after all preparations
        for (int i = 0; i < 11; i++) {
            sendBroadcast(new Intent(Constants.ACTION.ACTION_SEND_USB_COMMAND).
                    putExtra(Constants.ACTION_PARAMETERS.USB_COMMAND_ID, SEND_CALIBRATION).
                    putExtra(Constants.ACTION_PARAMETERS.USB_COMMAND_DATA, calibrationAnswers[i]).setPackage(Constants.PACKAGE_NAME));
        }
    }

    @SuppressLint("ApplySharedPref")
    private void setCalibrationSettingsToMemory() {
        SharedPreferences.Editor prefEditor = sharedPreferences.edit();
        int poliSize = AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().getFactor();
        prefEditor.putInt(Constants.CONFIG.CONF_CAL_POLI_SIZE, poliSize);
        double[] coeffs = AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().getCoeffArray(5);
        for (int i = 0; i <= poliSize; i++)
            prefEditor.putFloat(PrefHelper.configCalibrationCoefficient(i), (float) coeffs[i]);
        prefEditor.putInt(Constants.CONFIG.CONF_LAST_CHANNEL, AtomSpectraService.lastCalibrationChannel);
        prefEditor.commit();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void initializeGestures() {
        gestureDetector = initGestureDetector();
        gestureScaleDetector = initScaleGestureDetector();

        View view = findViewById(R.id.shape_area);

        view.setOnClickListener(arg0 -> {
        });

        view.setOnTouchListener((v, event) -> {
            boolean retVal;
            retVal = gestureScaleDetector.onTouchEvent(event);
            retVal = gestureDetector.onTouchEvent(event) || retVal;
            retVal = retVal || AtomSpectra.super.onTouchEvent(event);
            return retVal;
        });
    }


    private GestureDetector initGestureDetector() {
        return new GestureDetector(getBaseContext(), new SimpleOnGestureListener() {

            private final SwipeDetector detector = new SwipeDetector();

            @SuppressLint("ApplySharedPref")
            @Override
            public boolean onFling(MotionEvent e1, @NotNull MotionEvent e2, float velocityX, float velocityY) {
                if (isPinchMode) {
                    return true;
                }

                int displayMode = AtomSpectraService.getDisplayMode();
                boolean isSpectrumMode = displayMode == Constants.DISPLAY_MODE_SPECTRUM || displayMode == Constants.DISPLAY_MODE_SPECTRUM_CHANGE;
                if (!isSpectrumMode) {
                    return true;
                }

                if (isPinchModeFinished) {
                    isPinchModeFinished = false;
                    return true;
                }
                int new_channel;

                Log.d(TAG, "FLING!  ");
                try {
                    if (detector.isSwipeLeft(e1, e2, velocityX)) {
                        new_channel = AtomSpectraService.getFirstChannel() + Constants.WINDOW_OUTPUT_SIZE / 4 * (1 << (Constants.SCALE_MAX - AtomSpectraService.getScaleFactor()));

                        if (new_channel + Constants.WINDOW_OUTPUT_SIZE * (1 << (Constants.SCALE_MAX - AtomSpectraService.getScaleFactor())) > Constants.NUM_HIST_POINTS) {
                            new_channel = Constants.NUM_HIST_POINTS - Constants.WINDOW_OUTPUT_SIZE * (1 << (Constants.SCALE_MAX - AtomSpectraService.getScaleFactor()));
                        }

                        AtomSpectraService.setFirstChannel(new_channel);
                        SharedPreferences.Editor prefEditor = sharedPreferences.edit();
                        prefEditor.putInt(Constants.CONFIG.CONF_FIRST_CHANNEL, AtomSpectraService.getFirstChannel());
                        prefEditor.commit();
                        sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_GRAPH).setPackage(Constants.PACKAGE_NAME));
                        findViewById(R.id.shape_area).performClick();
                    } else if (detector.isSwipeRight(e1, e2, velocityX)) {
                        new_channel = AtomSpectraService.getFirstChannel() - Constants.WINDOW_OUTPUT_SIZE / 4 * (1 << (Constants.SCALE_MAX - AtomSpectraService.getScaleFactor()));

                        if (new_channel < 0) {
                            new_channel = 0;
                        }

                        AtomSpectraService.setFirstChannel(new_channel);
                        SharedPreferences.Editor prefEditor = sharedPreferences.edit();
                        prefEditor.putInt(Constants.CONFIG.CONF_FIRST_CHANNEL, AtomSpectraService.getFirstChannel());
                        prefEditor.commit();
                        sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_GRAPH).setPackage(Constants.PACKAGE_NAME));
                        findViewById(R.id.shape_area).performClick();
                    } else if (detector.isSwipeDown(e1, e2, velocityY)) {
                        if (AtomSpectraShapeView.isotopeFound >= 0 && cursor_x > 0) {
                            CheckBox isotopeData = findViewById(Constants.GROUPS.BUTTON_ID_ALIGN + AtomSpectraShapeView.isotopeFound);
                            AtomSpectraIsotopes.checkedIsotopeLine[AtomSpectraShapeView.isotopeFound] = !AtomSpectraIsotopes.checkedIsotopeLine[AtomSpectraShapeView.isotopeFound];
                            isotopeData.toggle();
                        }
                        sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_GRAPH).setPackage(Constants.PACKAGE_NAME));
                        findViewById(R.id.shape_area).performClick();
                    }
                } catch (Exception ignored) {
                } //for now, ignore
                return false;

            }

            @Override
            public boolean onDoubleTap(@NotNull MotionEvent e1) {
                if (!isSpectrumDisplayMode()) {
                    return true;
                }

                logScale = !logScale;
                sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_GRAPH).setPackage(Constants.PACKAGE_NAME));
                SharedPreferences.Editor prefEditor = sharedPreferences.edit();
                prefEditor.putBoolean(Constants.CONFIG.CONF_LOG_SCALE, logScale);
                prefEditor.commit();
                if (logScale) showToast(getString(R.string.graph_log_info));
                else showToast(getString(R.string.graph_linear_info));
                findViewById(R.id.shape_area).performClick();
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(@NotNull MotionEvent e1) {
                if (AtomSpectraService.showCalibrationFunction) {
                    showCursorInfo(true);
                    hideSeekChannel();
                    return true;
                }

                if (!isSpectrumDisplayMode()) {
                    return true;
                }

                boolean getInside = false;
                if (AtomSpectraShapeView.isOutOfFrame(e1.getX())) {
                    hideSeekChannel();
                } else {
                    if ((AtomSpectraService.newCalibration.getPointsCount() < Constants.MAX_CALIBRATION_POINTS)) {
                        for (Isotope i : AtomSpectraIsotopes.foundList) {
                            if (i.getCoord().contains(e1.getX(), e1.getY())) {
                                getInside = true;
                                final Context id = AtomSpectra.this;
                                final int channel_x = AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().toChannel(i.getEnergy(0));
                                final AlertDialog.Builder alert = new AlertDialog.Builder(id)
                                        .setTitle(getString(R.string.calibration_add_nuclid_title))
                                        .setMessage(getString(R.string.calibration_add_nuclid2_text, channel_x, i.getName(), i.getEnergy(0)));
                                final EditText input = new EditText(AtomSpectra.this);
                                input.setKeyListener(new NumberKeyListener() {
                                    @NonNull
                                    @Override
                                    protected char[] getAcceptedChars() {
                                        return new char[]{'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '.', ','};
                                    }

                                    @Override
                                    public int getInputType() {
                                        return InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_VARIATION_NORMAL;
                                    }
                                });
                                input.setImeOptions(EditorInfo.IME_ACTION_DONE);
                                alert.setView(input);
                                alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                                            float fValue;// = value.valueOf(value);
                                            try {
                                                fValue = Float.parseFloat(input.getText().toString().replaceAll(",", "."));
                                            } catch (Exception nfe) {
                                                ToastHelper.showToast(AtomSpectra.this, getString(R.string.cal_error_number));
                                                return;
                                            }
                                            AtomSpectraService.newCalibration.addPoint(channel_x, fValue);
                                            if (AtomSpectraService.newCalibration.getPointsCount() > 1) {
                                                app_menu.findItem(R.id.action_cal_draw_function).setEnabled(true);
                                                AtomSpectraService.newCalibration.Calculate(sharedPreferences.getInt(Constants.CONFIG.CONF_MAX_POLI_FACTOR, Constants.DEFAULT_POLI_FACTOR));
                                                if (!AtomSpectraService.newCalibration.isCorrect()) {
                                                    ToastHelper.showToast(AtomSpectra.this, getString(R.string.cal_maybe_wrong, channel_x, fValue));
                                                }
                                            }
                                            updateCalibrationMenu();
                                            showCursorInfo(true);
                                        })
                                        .setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
                                        });
                                alert.show();
                            }
                        }
                    }
                    if ((AtomSpectraService.newCalibration.getPointsCount() < Constants.MAX_CALIBRATION_POINTS) && (cursor_x != -1)) {
                        for (Isotope i : AtomSpectraIsotopes.isotopeLineArray) {
                            if (i.getCoord().contains(e1.getX(), e1.getY())) {
                                getInside = true;
                                final Context id = AtomSpectra.this;
                                final AlertDialog.Builder alert = new AlertDialog.Builder(id)
                                        .setTitle(getString(R.string.calibration_add_nuclid_title))
                                        .setMessage(getString(R.string.calibration_add_nuclid_text, cursor_x, i.getName(), i.getEnergy(0)))
                                        .setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                                            AtomSpectraService.newCalibration.addPoint(cursor_x, i.getEnergy(0));
                                            if (AtomSpectraService.newCalibration.getPointsCount() > 1) {
                                                AtomSpectraService.newCalibration.Calculate(sharedPreferences.getInt(Constants.CONFIG.CONF_MAX_POLI_FACTOR, Constants.DEFAULT_POLI_FACTOR));
                                                app_menu.findItem(R.id.action_cal_draw_function).setEnabled(true);
                                                if (!AtomSpectraService.newCalibration.isCorrect()) {
                                                    ToastHelper.showToast(AtomSpectra.this, getString(R.string.cal_maybe_wrong, cursor_x, i.getEnergy(0)));
                                                }
                                            }
                                            updateCalibrationMenu();
                                            showCursorInfo(true);
                                        })
                                        .setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
                                        });
                                alert.show();
                            }
                        }
                    }
                    if (!getInside) {
                        dateChannelChanged = System.currentTimeMillis();
                        seekChannel.setVisibility(SeekBar.VISIBLE);
                        if (showPlusMinusButtons || (cursor_x < AtomSpectraService.getFirstChannel())) {
                            if (XCalibrated) {
                                cursor_x = AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().toChannel(AtomSpectraShapeView.X2scale(e1.getX()));
                            } else {
                                cursor_x = (int) StrictMath.rint(AtomSpectraShapeView.X2scale(e1.getX()));
                            }
                        }
                        showPlusMinusButtons = true;
                    }
                }
                showCursorInfo(true);
                findViewById(R.id.shape_area).performClick();
                return true;
            }

            @SuppressLint("ApplySharedPref")
            @Override
            public void onLongPress(@NotNull MotionEvent e1) {
                barMode = !barMode;
                SharedPreferences.Editor prefEditor = sharedPreferences.edit();
                prefEditor.putBoolean(Constants.CONFIG.CONF_BAR_MODE, barMode);
                prefEditor.commit();
                sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_GRAPH).setPackage(Constants.PACKAGE_NAME));
                findViewById(R.id.shape_area).performClick();
            }

            private void showToast(String phrase) {
                ToastHelper.showToast(getApplicationContext(), phrase);
            }
        });
    }

    private void hideSeekChannel() {
        cursor_x = -1;
        dateChannelChanged = 0;
        seekChannel.setVisibility(SeekBar.INVISIBLE);
        showPlusMinusButtons = false;
    }


    private ScaleGestureDetector initScaleGestureDetector() {
        return new ScaleGestureDetector(getBaseContext(), new SimpleOnScaleGestureListener() {
            private float SpanXInit = 1;
            private float SpanYInit = 1;

            @Override
            public boolean onScaleBegin(@NotNull ScaleGestureDetector scaleGestureDetector) {
                SpanXInit = scaleGestureDetector.getCurrentSpanX();
                SpanYInit = scaleGestureDetector.getCurrentSpanY();
                isPinchMode = true;
                return true;
            }

            @Override
            public boolean onScale(@NotNull ScaleGestureDetector scaleGestureDetector) {
                return true;
            }

            @SuppressLint("ApplySharedPref")
            @Override
            public void onScaleEnd(@NotNull ScaleGestureDetector scaleGestureDetector) {
                float mScaleFactor;
                float mSpanX, mSpanY;
                int new_channel;
                isPinchMode = false;
                isPinchModeFinished = true;
                mSpanX = scaleGestureDetector.getCurrentSpanX() - SpanXInit;
                mSpanY = scaleGestureDetector.getCurrentSpanY() - SpanYInit;
                mScaleFactor = ((mSpanX) * (mSpanX) + (mSpanY) * (mSpanY));

                if (mScaleFactor > 22500) {
                    boolean spanXPrefer = StrictMath.abs(mSpanX * 1.5) > StrictMath.abs(mSpanY);
                    boolean spanYPrefer = StrictMath.abs(mSpanX) < StrictMath.abs(mSpanY * 1.5);
                    if ((mSpanX > 100) && spanXPrefer && (AtomSpectraService.getScaleFactor() <= Constants.SCALE_MAX)) {
                        if (AtomSpectraService.getScaleFactor() < Constants.SCALE_MAX) {
                            AtomSpectraService.setScaleFactor(AtomSpectraService.getScaleFactor() + 1);
                            sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_GRAPH).setPackage(Constants.PACKAGE_NAME));
                        } else
                            showToast(getString(R.string.graph_max_gain));
                        SharedPreferences.Editor prefEditor = sharedPreferences.edit();
                        prefEditor.putInt(Constants.CONFIG.CONF_SCALE_FACTOR, AtomSpectraService.getScaleFactor());
                        prefEditor.commit();
                    }

                    if ((mSpanY > 100) && spanYPrefer) {
                        //showToast("Zoom out Y");
                        if (zoom_factor < 40) {
                            if (zoom_factor >= 1)
                                zoom_factor *= 2;
                            else
                                zoom_factor += 0.25f;
                            sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_GRAPH).setPackage(Constants.PACKAGE_NAME));
                        } else {
                            zoom_factor = 64;
                            showToast(getString(R.string.graph_max_zoom));
                        }
                    }

                    if ((mSpanX < -100) && spanXPrefer && (AtomSpectraService.getScaleFactor() <= Constants.SCALE_MAX)) {
                        //showToast("Zoom in X");
                        if (AtomSpectraService.getScaleFactor() > Constants.SCALE_MIN) {

                            if (AtomSpectraService.getFirstChannel() + Constants.WINDOW_OUTPUT_SIZE / 2 * (1 << (1 + Constants.SCALE_MAX - AtomSpectraService.getScaleFactor())) > Constants.NUM_HIST_POINTS) {
                                new_channel = Constants.NUM_HIST_POINTS - Constants.WINDOW_OUTPUT_SIZE / 2 * (1 << (1 + Constants.SCALE_MAX - AtomSpectraService.getScaleFactor()));

                                if (new_channel < 0) {
                                    new_channel = 0;
                                }

                                AtomSpectraService.setFirstChannel(new_channel);
                                SharedPreferences.Editor prefEditor = sharedPreferences.edit();
                                prefEditor.putInt(Constants.CONFIG.CONF_FIRST_CHANNEL, AtomSpectraService.getFirstChannel());
                                prefEditor.commit();
                            }

                            AtomSpectraService.setScaleFactor(AtomSpectraService.getScaleFactor() - 1);
                            sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_GRAPH).setPackage(Constants.PACKAGE_NAME));
                        } else
                            showToast(getString(R.string.graph_min_gain));
                        SharedPreferences.Editor prefEditor = sharedPreferences.edit();
                        prefEditor.putInt(Constants.CONFIG.CONF_SCALE_FACTOR, AtomSpectraService.getScaleFactor());
                        prefEditor.commit();
                    }

                    if ((mSpanY < -100) && spanYPrefer) {
                        if (zoom_factor > 0.4) {
                            if (zoom_factor > 1)
                                zoom_factor /= 2;
                            else
                                zoom_factor -= 0.25f;
                            sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_GRAPH).setPackage(Constants.PACKAGE_NAME));
                        } else {
                            zoom_factor = 0.25f;
                            showToast(getString(R.string.graph_min_zoom));
                        }
                    }

                }

                findViewById(R.id.shape_area).performClick();
            }

            private void showToast(String phrase) {
                ToastHelper.showToast(getApplicationContext(), phrase);
            }
        });
    }

    private final Timer buttonsTimer = new Timer();
    private boolean showPlusMinusButtons = false;
    private long dateChannelChanged = 0;
    private final TimerTask buttonsTask = new TimerTask() {
        @Override
        public void run() {
            if (showPlusMinusButtons && ((System.currentTimeMillis() - dateChannelChanged) > Constants.CURSOR_TIMEOUT)) {
                showPlusMinusButtons = false;
                final SeekBar seekChannel = findViewById(R.id.seekChannel);
                seekChannel.post(() -> seekChannel.setVisibility(SeekBar.INVISIBLE));
            }
        }
    };

    private void showCursorInfo(boolean requestUpdateGraph) {
        if (cursor_x >= 0 && cursor_x >= AtomSpectraService.getFirstChannel() / Constants.NUM_HIST_POINTS * AtomSpectraService.lastCalibrationChannel && !AtomSpectraService.showCalibrationFunction) {
            mTextView.setText(getString(R.string.cursor_format, cursor_x, AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().toEnergy(cursor_x), AtomSpectraService.ForegroundSpectrum.getDataArray()[cursor_x]));
            mTextView.setVisibility(TextView.VISIBLE);
            mLayoutView.setVisibility(LinearLayout.VISIBLE);
        } else {
            mTextView.setVisibility(TextView.INVISIBLE);
            mLayoutView.setVisibility(LinearLayout.INVISIBLE);
        }

        if (requestUpdateGraph) {
            sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_GRAPH).setPackage(Constants.PACKAGE_NAME));
        }

        Log.d(TAG, "showCursorInfo: " + cursor_x);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        boolean isFreeze = AtomSpectraService.getFreeze();

        if (item.getItemId() == R.id.action_background_save) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                String dirName = PrefHelper.getWorkingDir(this, false);
                if (dirName == null) {
                    requestDirectory(SELECT_SAVE_BACK_DIR_CODE);
                } else {
                    final DocumentFile dir = DocumentFile.fromTreeUri(this, Uri.parse(dirName));
                    if ((dir != null) && dir.isDirectory()) {
                        saveDefaultBackground();
                    } else {
                        requestDirectory(SELECT_SAVE_BACK_DIR_CODE);
                    }
                }
            } else {
                if (checkPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, getString(R.string.perm_ask_write_title), getString(R.string.perm_ask_write_text), REQUEST_WRITE_BACK)) {
                    saveDefaultBackground();
                }
            }
            return true;
        } else if (item.getItemId() == R.id.action_background_load) {
            Log.d(TAG, "loading background file");
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                if (checkPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, getString(R.string.perm_ask_read_title), getString(R.string.perm_ask_read_text), REQUEST_READ_HIST)) {
                    Intent loadIntent = new Intent()
                            .setType("*/*")
                            .setAction(Intent.ACTION_GET_CONTENT);
                    startActivityForResult(Intent.createChooser(loadIntent, getString(R.string.ask_select_histogram)), LOAD_BACK_CODE);
                }
            } else {
                Intent loadIntent = new Intent()
                        .setType("*/*")
                        .setAction(Intent.ACTION_GET_CONTENT);
                startActivityForResult(Intent.createChooser(loadIntent, getString(R.string.ask_select_histogram)), LOAD_BACK_CODE);
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                String dirName = PrefHelper.getWorkingDir(this, false);
                if (dirName == null) {
                    requestDirectory(SELECT_LOAD_BACK_DIR_CODE);
                } else {
                    final DocumentFile dir = DocumentFile.fromTreeUri(this, Uri.parse(dirName));
                    if ((dir != null) && dir.isDirectory()) {
                        loadBackgroundOrDefault(null);
                    } else {
                        requestDirectory(SELECT_LOAD_BACK_DIR_CODE);
                    }
                }
            } else {
                if (checkPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, getString(R.string.perm_ask_read_title), getString(R.string.perm_ask_read_text), REQUEST_READ_BACK)) {
                    loadBackgroundOrDefault(null);
                }
            }

            sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_GRAPH).setPackage(Constants.PACKAGE_NAME));
            return true;
        } else if (item.getItemId() == R.id.action_background_load_from) {
            Log.d(TAG, "loading background file from...");
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                if (checkPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, getString(R.string.perm_ask_read_title), getString(R.string.perm_ask_read_text), REQUEST_READ_BACK_FROM)) {
                    Intent loadIntent = new Intent()
                            .setType("*/*")
                            .setAction(Intent.ACTION_GET_CONTENT);
                    startActivityForResult(Intent.createChooser(loadIntent, getString(R.string.ask_select_histogram)), LOAD_BACK_CODE);
                }
            } else {
                Intent loadIntent = new Intent()
                        .setType("*/*")
                        .setAction(Intent.ACTION_GET_CONTENT);
                startActivityForResult(Intent.createChooser(loadIntent, getString(R.string.ask_select_histogram)), LOAD_BACK_CODE);
            }

            sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_GRAPH).setPackage(Constants.PACKAGE_NAME));
            return true;
        } else if (item.getItemId() == R.id.action_background_copy) {
            moveToBackground();
            sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_GRAPH).setPackage(Constants.PACKAGE_NAME));
            return true;
        } else if (item.getItemId() == R.id.action_hist_suffix) {
            final AlertDialog.Builder alert = new AlertDialog.Builder(this);
            alert.setTitle(getString(R.string.ask_spectrum_suffix));
            alert.setMessage(getString(R.string.ask_suffix_text));

            final EditText input = new EditText(this);
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            input.setImeOptions(EditorInfo.IME_ACTION_DONE);
            input.setText(AtomSpectraService.ForegroundSpectrum.getSuffix(), TextView.BufferType.EDITABLE);
            alert.setView(input);
            alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                AtomSpectraService.ForegroundSpectrum.setSuffix(input.getText().toString());
                TextView text = findViewById(R.id.suffixView);
                text.setText(AtomSpectraService.ForegroundSpectrum.getSuffix());
            });
            alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
            });
            alert.show();
            return true;
        } else if (item.getItemId() == R.id.action_background_suffix) {
            final AlertDialog.Builder alert = new AlertDialog.Builder(this);
            alert.setTitle(getString(R.string.ask_spectrum_suffix));
            alert.setMessage(getString(R.string.ask_suffix_text));

            final EditText input = new EditText(this);
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            input.setImeOptions(EditorInfo.IME_ACTION_DONE);
            input.setText(AtomSpectraService.BackgroundSpectrum.getSuffix(), TextView.BufferType.EDITABLE);
            alert.setView(input);
            alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                AtomSpectraService.BackgroundSpectrum.setSuffix(input.getText().toString());
                TextView text = findViewById(R.id.backgroundSuffixView);
                text.setText(AtomSpectraService.BackgroundSpectrum.getSuffix());
            });
            alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
            });
            alert.show();
            return true;
        } else if (item.getItemId() == R.id.action_background_clear) {
            AtomSpectraService.background_show = false;
            background_subtract = false;
            app_menu.findItem(R.id.action_background_show).setChecked(false);
            app_menu.findItem(R.id.action_background_show).setEnabled(false);
            app_menu.findItem(R.id.action_background_subtract).setChecked(false);
            app_menu.findItem(R.id.action_background_subtract).setEnabled(false);
            app_menu.findItem(R.id.action_background_save).setEnabled(false);
            app_menu.findItem(R.id.action_background_clear).setEnabled(false);
            app_menu.findItem(R.id.action_background_suffix).setEnabled(false);
            app_menu.findItem(R.id.action_background_suffix).setEnabled(false);
            AtomSpectraService.BackgroundSpectrum
                    .initSpectrumData(Constants.NUM_HIST_POINTS, Calibration.defaultCalibration(Constants.NUM_HIST_POINTS))
                    .setSuffix(getString(R.string.background_suffix));
            TextView view = findViewById(R.id.backgroundSuffixView);
            view.setText(getResources().getText(R.string.background));
            view.setVisibility(TextView.INVISIBLE);
            sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_GRAPH).setPackage(Constants.PACKAGE_NAME));
            return true;
        } else if (item.getItemId() == R.id.action_background_subtract) {
            if (item.isChecked()) {
                item.setChecked(false);
                background_subtract = false;
            } else {
                if (!AtomSpectraService.BackgroundSpectrum.isEmpty()) {
                    background_subtract = true;
                    item.setChecked(true);
                }
            }

            sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_GRAPH).setPackage(Constants.PACKAGE_NAME));
            return true;
        } else if (item.getItemId() == R.id.action_background_show) {
            if (item.isChecked()) {
                item.setChecked(false);
                AtomSpectraService.background_show = false;
                background_subtract = false;
                app_menu.findItem(R.id.action_background_subtract).setChecked(false);
                app_menu.findItem(R.id.action_background_subtract).setEnabled(false);
            } else {
                if (!AtomSpectraService.BackgroundSpectrum.isEmpty()) {
                    AtomSpectraService.background_show = true;
                    item.setChecked(true);
                    app_menu.findItem(R.id.action_background_subtract).setEnabled(true);
                }
            }

            sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_GRAPH).setPackage(Constants.PACKAGE_NAME));
            return true;
        } else if (item.getItemId() == R.id.action_isotopes) {
            Intent intent_isotopes = new Intent(this, AtomSpectraIsotopes.class);
            startActivity(intent_isotopes);
            return true;
        } else if (item.getItemId() == R.id.action_help) {
            Intent intent_help = new Intent(this, AtomSpectraHelp.class);
            startActivity(intent_help);
            return true;
        } else if (item.getItemId() == R.id.action_app_version) {
            Intent intent_log = new Intent(this, AtomSpectraLog.class);
            startActivity(intent_log);
            return true;
        } else if (item.getItemId() == R.id.action_spectrogram_load) {
            Log.d(TAG, "loading spectrogram file");
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                if (checkPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, getString(R.string.perm_ask_read_title), getString(R.string.perm_ask_read_text), REQUEST_READ_SPG)) {
                    Intent loadIntent = new Intent()
                            .setType("*/*")
                            .setAction(Intent.ACTION_GET_CONTENT);
                    startActivityForResult(Intent.createChooser(loadIntent, getString(R.string.ask_select_spectrogram)), LOAD_SPG_CODE);
                }
            } else {
                Intent loadIntent = new Intent()
                        .setType("*/*")
                        .setAction(Intent.ACTION_GET_CONTENT);
                startActivityForResult(Intent.createChooser(loadIntent, getString(R.string.ask_select_spectrogram)), LOAD_SPG_CODE);
            }
            return true;
        } else if (item.getItemId() == R.id.action_spectrogram_view) {
            showSpectrogramView();
            return true;
        } else if (item.getItemId() == R.id.action_hist_to_file) {
            Log.d(TAG, "saving file");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                String dirName = PrefHelper.getWorkingDir(this, false);
                if (dirName == null) {
                    requestDirectory(SELECT_SAVE_HIST_DIR_CODE);
                } else {
                    final DocumentFile dir = DocumentFile.fromTreeUri(this, Uri.parse(dirName));
                    if ((dir != null) && dir.isDirectory()) {
                        try {
                            final AlertDialog.Builder alert = new AlertDialog.Builder(this);
                            alert.setTitle(getString(R.string.ask_spectrum_suffix));
                            alert.setMessage(getString(R.string.ask_suffix_text));

                            final EditText input = new EditText(this);
                            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                            input.setImeOptions(EditorInfo.IME_ACTION_DONE);
                            input.setText(AtomSpectraService.ForegroundSpectrum.getSuffix(), TextView.BufferType.EDITABLE);
                            alert.setView(input);
                            alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                                AtomSpectraService.ForegroundSpectrum.setSuffix(input.getText().toString());
                                TextView text = findViewById(R.id.suffixView);
                                text.setText(AtomSpectraService.ForegroundSpectrum.getSuffix());
                                saveSpectrumAS(AtomSpectraService.ForegroundSpectrum.getSuffix());
                            });
                            alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
                            });
                            alert.show();
                        } catch (Exception e) {
                            Log.d(TAG, "saving file FAIL");
                        }
                    } else {
                        requestDirectory(SELECT_SAVE_HIST_DIR_CODE);
                    }
                }
            } else {
                if (checkPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, getString(R.string.perm_ask_write_title), getString(R.string.perm_ask_write_text), REQUEST_WRITE_HIST)) {
                    try {
                        final AlertDialog.Builder alert = new AlertDialog.Builder(this);
                        alert.setTitle(getString(R.string.ask_spectrum_suffix));
                        alert.setMessage(getString(R.string.ask_suffix_text));

                        final EditText input = new EditText(this);
                        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
                        input.setText(AtomSpectraService.ForegroundSpectrum.getSuffix(), TextView.BufferType.EDITABLE);
                        alert.setView(input);
                        alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                            AtomSpectraService.ForegroundSpectrum.setSuffix(input.getText().toString());
                            TextView text = findViewById(R.id.suffixView);
                            text.setText(AtomSpectraService.ForegroundSpectrum.getSuffix());
                            saveSpectrumAS(AtomSpectraService.ForegroundSpectrum.getSuffix());
                        });
                        alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
                        });
                        alert.show();
                    } catch (Exception e) {
                        Log.d(TAG, "saving file FAIL");
                    }
                }
            }
            return true;
        } else if (item.getItemId() == R.id.action_hist_from_file) {
            Log.d(TAG, "loading hist file");
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                if (checkPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, getString(R.string.perm_ask_read_title), getString(R.string.perm_ask_read_text), REQUEST_READ_HIST)) {
                    Intent loadIntent = new Intent()
                            .setType("*/*")
                            .setAction(Intent.ACTION_GET_CONTENT);
                    startActivityForResult(Intent.createChooser(loadIntent, getString(R.string.ask_select_histogram)), LOAD_HIST_CODE);
                }
            } else {
                Intent loadIntent = new Intent()
                        .setType("*/*")
                        .setAction(Intent.ACTION_GET_CONTENT);
                startActivityForResult(Intent.createChooser(loadIntent, getString(R.string.ask_select_histogram)), LOAD_HIST_CODE);
            }
            return true;
        } else if (item.getItemId() == R.id.action_hist_add_from_file) {
            Log.d(TAG, "adding hist file");
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                if (checkPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, getString(R.string.perm_ask_read_title), getString(R.string.perm_ask_read_text), REQUEST_READ_HIST)) {
                    Intent loadIntent = new Intent()
                            .setType("*/*")
                            .setAction(Intent.ACTION_GET_CONTENT);
                    startActivityForResult(Intent.createChooser(loadIntent, getString(R.string.ask_select_histogram)), ADD_HIST_CODE);
                }
            } else {
                Intent loadIntent = new Intent()
                        .setType("*/*")
                        .setAction(Intent.ACTION_GET_CONTENT);
                startActivityForResult(Intent.createChooser(loadIntent, getString(R.string.ask_select_histogram)), ADD_HIST_CODE);
            }
            return true;
        } else if (item.getItemId() == R.id.action_cal_load) {
            Log.d(TAG, "loading calibration from file");
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                if (checkPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, getString(R.string.perm_ask_read_title), getString(R.string.perm_ask_read_text), REQUEST_READ_CAL)) {
                    Intent calibIntent = new Intent()
                            .setType("*/*")
                            .setAction(Intent.ACTION_GET_CONTENT);
                    startActivityForResult(Intent.createChooser(calibIntent, getString(R.string.ask_select_histogram)), LOAD_CALIBRATION_CODE);
                }
            } else {
                Intent calibIntent = new Intent()
                        .setType("*/*")
                        .setAction(Intent.ACTION_GET_CONTENT);
                startActivityForResult(Intent.createChooser(calibIntent, getString(R.string.ask_select_calibration)), LOAD_CALIBRATION_CODE);
            }
            return true;
        } else if (item.getItemId() == R.id.action_cal_store_device) {
            Log.d(TAG, "storing calibration to device");
            if (isFreeze) {
                setCalibrationSettingsToDevice();
            } else {
                DialogHelper.showActionConfirmationDialog(this, getString(R.string.calibration_stop_before_save_device_text), () -> {
                    sendBroadcast(new Intent(Constants.ACTION.ACTION_FREEZE_DATA).putExtra(AtomSpectraSerial.EXTRA_DATA_TYPE, true).setPackage(Constants.PACKAGE_NAME));
                    setCalibrationSettingsToDevice();
                });
            }

            return true;
        } else if (item.getItemId() == R.id.action_cal_store_memory) {
            Log.d(TAG, "storing calibration to program");
            setCalibrationSettingsToMemory();
            return true;
        } else if (item.getItemId() == R.id.action_cal_retrieve_device) {
            Log.d(TAG, "retrieving calibration from device");
            if (isFreeze) {
                getCalibrationSettingsFromDevice();
            } else {
                DialogHelper.showActionConfirmationDialog(this, getString(R.string.calibration_stop_before_load_device_text), () -> {
                    sendBroadcast(new Intent(Constants.ACTION.ACTION_FREEZE_DATA).putExtra(AtomSpectraSerial.EXTRA_DATA_TYPE, true).setPackage(Constants.PACKAGE_NAME));
                    getCalibrationSettingsFromDevice();
                });
            }

//			updateCalibrationMenu();
            return true;
        } else if (item.getItemId() == R.id.action_cal_retrieve_memory) {
            Log.d(TAG, "retrieving calibration from program");
            getCalibrationSettingsFromMemory();
//			updateCalibrationMenu();
            return true;
        } else if (item.getItemId() == R.id.action_export) {
            Log.d(TAG, "exporting file");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                String dirName = PrefHelper.getWorkingDir(this, false);
                if (dirName == null) {
                    requestDirectory(SELECT_SAVE_EXPORT_DIR_CODE);
                } else {
                    final DocumentFile dir = DocumentFile.fromTreeUri(this, Uri.parse(dirName));
                    if ((dir != null) && dir.isDirectory()) {
                        try {
                            final AlertDialog.Builder alert = new AlertDialog.Builder(this);
                            alert.setTitle(getString(R.string.ask_export_suffix));
                            alert.setMessage(getString(R.string.ask_suffix_text));

                            final EditText input = new EditText(this);
                            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                            input.setImeOptions(EditorInfo.IME_ACTION_DONE);
                            input.setText(AtomSpectraService.ForegroundSpectrum.getSuffix(), TextView.BufferType.EDITABLE);
                            alert.setView(input);
                            alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                                AtomSpectraService.ForegroundSpectrum.setSuffix(input.getText().toString());
                                TextView text = findViewById(R.id.suffixView);
                                text.setText(AtomSpectraService.ForegroundSpectrum.getSuffix());
                                saveCSV(AtomSpectraService.ForegroundSpectrum.getSuffix(), false);
                            });
                            alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
                            });
                            alert.show();
                        } catch (Exception e) {
                            Log.d(TAG, "saving file FAIL");
                        }
                    } else {
                        requestDirectory(SELECT_SAVE_EXPORT_DIR_CODE);
                    }
                }
            } else {
                if (checkPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, getString(R.string.perm_ask_write_title), getString(R.string.perm_ask_write_text), REQUEST_EXPORT)) {
                    try {
                        final AlertDialog.Builder alert = new AlertDialog.Builder(this);
                        alert.setTitle(getString(R.string.ask_export_suffix));
                        alert.setMessage(getString(R.string.ask_suffix_text));
                        final EditText input = new EditText(this);
                        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
                        input.setText(AtomSpectraService.ForegroundSpectrum.getSuffix(), TextView.BufferType.EDITABLE);
                        alert.setView(input);
                        alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                            AtomSpectraService.ForegroundSpectrum.setSuffix(input.getText().toString());
                            TextView text = findViewById(R.id.suffixView);
                            text.setText(AtomSpectraService.ForegroundSpectrum.getSuffix());
                            saveCSV(AtomSpectraService.ForegroundSpectrum.getSuffix(), false);
                        });
                        alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
                        });
                        alert.show();
                    } catch (Exception e) {
                        Log.d(TAG, "saving file FAIL");
                    }
                }
            }
            return true;
        } else if (item.getItemId() == R.id.action_export_with_energy) {
            Log.d(TAG, "exporting file with energy");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                String dirName = PrefHelper.getWorkingDir(this, false);
                if (dirName == null) {
                    requestDirectory(SELECT_SAVE_EXPORT_E_DIR_CODE);
                } else {
                    final DocumentFile dir = DocumentFile.fromTreeUri(this, Uri.parse(dirName));
                    if ((dir != null) && dir.isDirectory()) {
                        try {
                            final AlertDialog.Builder alert = new AlertDialog.Builder(this);
                            alert.setTitle(getString(R.string.ask_export_suffix));
                            alert.setMessage(getString(R.string.ask_suffix_text));

                            final EditText input = new EditText(this);
                            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                            input.setImeOptions(EditorInfo.IME_ACTION_DONE);
                            input.setText(AtomSpectraService.ForegroundSpectrum.getSuffix(), TextView.BufferType.EDITABLE);
                            alert.setView(input);
                            alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                                AtomSpectraService.ForegroundSpectrum.setSuffix(input.getText().toString());
                                TextView text = findViewById(R.id.suffixView);
                                text.setText(AtomSpectraService.ForegroundSpectrum.getSuffix());
                                saveCSV(AtomSpectraService.ForegroundSpectrum.getSuffix(), true);
                            });
                            alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
                            });
                            alert.show();
                        } catch (Exception e) {
                            Log.d(TAG, "saving file FAIL");
                        }
                    } else {
                        requestDirectory(SELECT_SAVE_EXPORT_E_DIR_CODE);
                    }
                }
            } else {
                if (checkPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, getString(R.string.perm_ask_write_title), getString(R.string.perm_ask_write_text), REQUEST_EXPORT_E)) {
                    try {
                        final AlertDialog.Builder alert = new AlertDialog.Builder(this);
                        alert.setTitle(getString(R.string.ask_export_suffix));
                        alert.setMessage(getString(R.string.ask_suffix_text));
                        final EditText input = new EditText(this);
                        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
                        input.setText(AtomSpectraService.ForegroundSpectrum.getSuffix(), TextView.BufferType.EDITABLE);
                        alert.setView(input);
                        alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                            AtomSpectraService.ForegroundSpectrum.setSuffix(input.getText().toString());
                            TextView text = findViewById(R.id.suffixView);
                            text.setText(AtomSpectraService.ForegroundSpectrum.getSuffix());
                            saveCSV(AtomSpectraService.ForegroundSpectrum.getSuffix(), true);
                        });
                        alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
                        });
                        alert.show();
                    } catch (Exception e) {
                        Log.d(TAG, "saving file FAIL");
                    }
                }
            }
            return true;
        } else if (item.getItemId() == R.id.action_export_to_BqMoni) {
            Log.d(TAG, "exporting file to Becquerel Monitor");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                String dirName = PrefHelper.getWorkingDir(this, false);
                if (dirName == null) {
                    requestDirectory(SELECT_SAVE_EXPORT_BQ_DIR_CODE);
                } else {
                    final DocumentFile dir = DocumentFile.fromTreeUri(this, Uri.parse(dirName));
                    if ((dir != null) && dir.isDirectory()) {
                        try {
                            final AlertDialog.Builder alert = new AlertDialog.Builder(this);
                            alert.setTitle(getString(R.string.ask_export_bqmoni));
                            alert.setMessage(getString(R.string.ask_suffix_text));

                            final EditText input = new EditText(this);
                            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                            input.setImeOptions(EditorInfo.IME_ACTION_DONE);
                            input.setText(AtomSpectraService.ForegroundSpectrum.getSuffix(), TextView.BufferType.EDITABLE);
                            alert.setView(input);
                            alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                                AtomSpectraService.ForegroundSpectrum.setSuffix(input.getText().toString());
                                TextView text = findViewById(R.id.suffixView);
                                text.setText(AtomSpectraService.ForegroundSpectrum.getSuffix());
                                saveBqMoni(AtomSpectraService.ForegroundSpectrum.getSuffix());
                            });
                            alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
                            });
                            alert.show();
                        } catch (Exception e) {
                            Log.d(TAG, "saving file FAIL");
                        }
                    } else {
                        requestDirectory(SELECT_SAVE_EXPORT_BQ_DIR_CODE);
                    }
                }
            } else {
                if (checkPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, getString(R.string.perm_ask_write_title), getString(R.string.perm_ask_write_text), REQUEST_EXPORT_BQMONI)) {
                    try {
                        final AlertDialog.Builder alert = new AlertDialog.Builder(this);
                        alert.setTitle(getString(R.string.ask_export_bqmoni));
                        alert.setMessage(getString(R.string.ask_suffix_text));
                        final EditText input = new EditText(this);
                        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
                        input.setText(AtomSpectraService.ForegroundSpectrum.getSuffix(), TextView.BufferType.EDITABLE);
                        alert.setView(input);
                        alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                            AtomSpectraService.ForegroundSpectrum.setSuffix(input.getText().toString());
                            TextView text = findViewById(R.id.suffixView);
                            text.setText(AtomSpectraService.ForegroundSpectrum.getSuffix());
                            saveBqMoni(AtomSpectraService.ForegroundSpectrum.getSuffix());
                        });
                        alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
                        });
                        alert.show();
                    } catch (Exception e) {
                        Log.d(TAG, "saving file FAIL");
                    }
                }
            }
            return true;
        } else if (item.getItemId() == R.id.action_export_to_SPE) {
            Log.d(TAG, "exporting file to SPE");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                String dirName = PrefHelper.getWorkingDir(this, false);
                if (dirName == null) {
                    requestDirectory(SELECT_SAVE_EXPORT_SPE_DIR_CODE);
                } else {
                    final DocumentFile dir = DocumentFile.fromTreeUri(this, Uri.parse(dirName));
                    if ((dir != null) && dir.isDirectory()) {
                        try {
                            final AlertDialog.Builder alert = new AlertDialog.Builder(this);
                            alert.setTitle(getString(R.string.ask_export_spe));
                            alert.setMessage(getString(R.string.ask_suffix_text));

                            final EditText input = new EditText(this);
                            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                            input.setImeOptions(EditorInfo.IME_ACTION_DONE);
                            input.setText(AtomSpectraService.ForegroundSpectrum.getSuffix(), TextView.BufferType.EDITABLE);
                            alert.setView(input);
                            alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                                AtomSpectraService.ForegroundSpectrum.setSuffix(input.getText().toString());
                                TextView text = findViewById(R.id.suffixView);
                                text.setText(AtomSpectraService.ForegroundSpectrum.getSuffix());
                                saveSPE(AtomSpectraService.ForegroundSpectrum.getSuffix());
                            });
                            alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
                            });
                            alert.show();
                        } catch (Exception e) {
                            Log.d(TAG, "saving file FAIL");
                        }
                    } else {
                        requestDirectory(SELECT_SAVE_EXPORT_SPE_DIR_CODE);
                    }
                }
            } else {
                if (checkPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, getString(R.string.perm_ask_write_title), getString(R.string.perm_ask_write_text), REQUEST_EXPORT_SPE)) {
                    try {
                        final AlertDialog.Builder alert = new AlertDialog.Builder(this);
                        alert.setTitle(getString(R.string.ask_export_spe));
                        alert.setMessage(getString(R.string.ask_suffix_text));
                        final EditText input = new EditText(this);
                        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
                        input.setText(AtomSpectraService.ForegroundSpectrum.getSuffix(), TextView.BufferType.EDITABLE);
                        alert.setView(input);
                        alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                            AtomSpectraService.ForegroundSpectrum.setSuffix(input.getText().toString());
                            TextView text = findViewById(R.id.suffixView);
                            text.setText(AtomSpectraService.ForegroundSpectrum.getSuffix());
                            saveSPE(AtomSpectraService.ForegroundSpectrum.getSuffix());
                        });
                        alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
                        });
                        alert.show();
                    } catch (Exception e) {
                        Log.d(TAG, "saving file FAIL");
                    }
                }
            }
            return true;
        } else if (item.getItemId() == R.id.action_export_to_N42) {
            Log.d(TAG, "exporting file to SPE");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                String dirName = PrefHelper.getWorkingDir(this, false);
                if (dirName == null) {
                    requestDirectory(SELECT_SAVE_EXPORT_N42_DIR_CODE);
                } else {
                    final DocumentFile dir = DocumentFile.fromTreeUri(this, Uri.parse(dirName));
                    if ((dir != null) && dir.isDirectory()) {
                        try {
                            final AlertDialog.Builder alert = new AlertDialog.Builder(this);
                            alert.setTitle(getString(R.string.ask_export_N42));
                            alert.setMessage(getString(R.string.ask_suffix_text));

                            final EditText input = new EditText(this);
                            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                            input.setImeOptions(EditorInfo.IME_ACTION_DONE);
                            input.setText(AtomSpectraService.ForegroundSpectrum.getSuffix(), TextView.BufferType.EDITABLE);
                            alert.setView(input);
                            alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                                AtomSpectraService.ForegroundSpectrum.setSuffix(input.getText().toString());
                                TextView text = findViewById(R.id.suffixView);
                                text.setText(AtomSpectraService.ForegroundSpectrum.getSuffix());
                                saveN42(AtomSpectraService.ForegroundSpectrum.getSuffix());
                            });
                            alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
                            });
                            alert.show();
                        } catch (Exception e) {
                            Log.d(TAG, "saving file FAIL");
                        }
                    } else {
                        requestDirectory(SELECT_SAVE_EXPORT_N42_DIR_CODE);
                    }
                }
            } else {
                if (checkPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, getString(R.string.perm_ask_write_title), getString(R.string.perm_ask_write_text), REQUEST_EXPORT_N42)) {
                    try {
                        final AlertDialog.Builder alert = new AlertDialog.Builder(this);
                        alert.setTitle(getString(R.string.ask_export_N42));
                        alert.setMessage(getString(R.string.ask_suffix_text));
                        final EditText input = new EditText(this);
                        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
                        input.setText(AtomSpectraService.ForegroundSpectrum.getSuffix(), TextView.BufferType.EDITABLE);
                        alert.setView(input);
                        alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                            AtomSpectraService.ForegroundSpectrum.setSuffix(input.getText().toString());
                            TextView text = findViewById(R.id.suffixView);
                            text.setText(AtomSpectraService.ForegroundSpectrum.getSuffix());
                            saveN42(AtomSpectraService.ForegroundSpectrum.getSuffix());
                        });
                        alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
                        });
                        alert.show();
                    } catch (Exception e) {
                        Log.d(TAG, "saving file FAIL");
                    }
                }
            }
            return true;
        } else if (item.getItemId() == R.id.action_save_device) {
            Log.d(TAG, "saving device file");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                String dirName = PrefHelper.getWorkingDir(this, false);
                if (dirName == null) {
                    requestDirectory(SELECT_SAVE_DEVICE_DIR_CODE);
                } else {
                    final DocumentFile dir = DocumentFile.fromTreeUri(this, Uri.parse(dirName));
                    if ((dir != null) && dir.isDirectory()) {
                        try {
                            final AlertDialog.Builder alert = new AlertDialog.Builder(this);
                            alert.setTitle(getString(R.string.ask_device_suffix));
                            alert.setMessage(getString(R.string.ask_suffix_text));

                            final EditText input = new EditText(this);
                            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                            input.setImeOptions(EditorInfo.IME_ACTION_DONE);
                            alert.setView(input);
                            alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                                String value = input.getText().toString();
                                saveDevice(value);
                            });
                            alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
                            });
                            alert.show();
                        } catch (Exception e) {
                            Log.d(TAG, "saving file FAIL");
                        }
                    } else {
                        requestDirectory(SELECT_SAVE_DEVICE_DIR_CODE);
                    }
                }
            } else {
                if (checkPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, getString(R.string.perm_ask_write_title), getString(R.string.perm_ask_write_text), REQUEST_WRITE_HIST)) {
                    try {
                        final AlertDialog.Builder alert = new AlertDialog.Builder(this);
                        alert.setTitle(getString(R.string.ask_device_suffix));
                        alert.setMessage(getString(R.string.ask_suffix_text));

                        final EditText input = new EditText(this);
                        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
                        alert.setView(input);
                        alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                            String value = input.getText().toString();
                            saveDevice(value);
                        });
                        alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
                        });
                        alert.show();
                    } catch (Exception e) {
                        Log.d(TAG, "saving file FAIL");
                    }
                }
            }
            return true;
        } else if (item.getItemId() == R.id.action_load_device) {
            Log.d(TAG, "loading device file");
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                if (checkPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, getString(R.string.perm_ask_read_title), getString(R.string.perm_ask_read_text), REQUEST_READ_HIST)) {
                    Intent loadIntent = new Intent()
                            .setType("*/*")
                            .setAction(Intent.ACTION_GET_CONTENT);
                    startActivityForResult(Intent.createChooser(loadIntent, getString(R.string.ask_select_device_file)), SELECT_LOAD_DEVICE_CODE);
                }
            } else {
                Intent loadIntent = new Intent()
                        .setType("*/*")
                        .setAction(Intent.ACTION_GET_CONTENT);
                startActivityForResult(Intent.createChooser(loadIntent, getString(R.string.ask_select_device_file)), SELECT_LOAD_DEVICE_CODE);
            }
            return true;
        } else if (item.getItemId() == R.id.action_clear_spectrum) {
            onClickDeleteSpc(findViewById(R.id.clearSpectrumButton));
            return true;
        } else if (item.getItemId() == R.id.action_hist_smooth) {
            AtomSpectraService.setSmooth = !AtomSpectraService.setSmooth;
            item.setTitle(AtomSpectraService.setSmooth ? getString(R.string.hist_unsmooth) : getString(R.string.hist_smooth));
            sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_GRAPH).setPackage(Constants.PACKAGE_NAME));
            return true;
        } else if (item.getItemId() == R.id.action_hist_freeze) {
            if (AtomSpectraService.getFreeze()) {
                item.setIcon(R.drawable.menu_block);
                item.setTitle(R.string.hist_freeze_update);
                sendBroadcast(new Intent(Constants.ACTION.ACTION_FREEZE_DATA).putExtra(AtomSpectraSerial.EXTRA_DATA_TYPE, false).setPackage(Constants.PACKAGE_NAME));
                ((TextView) findViewById(R.id.suffixView)).setText(AtomSpectraService.ForegroundSpectrum.getSuffix());
            } else {
                item.setIcon(R.drawable.record);
                item.setTitle(R.string.hist_continue_update);
                sendBroadcast(new Intent(Constants.ACTION.ACTION_FREEZE_DATA).putExtra(AtomSpectraSerial.EXTRA_DATA_TYPE, true).setPackage(Constants.PACKAGE_NAME));
            }
            return true;
        } else if (item.getItemId() == R.id.action_share_export) {
            Log.d(TAG, "sharing file");
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                if (checkPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, getString(R.string.perm_ask_read_title), getString(R.string.perm_ask_share_text), REQUEST_SHARE)) {
                    try {
                        //do not delete, may be good
                        Intent loadIntent = new Intent()
                                .setType("*/*")
                                .setAction(Intent.ACTION_GET_CONTENT);
                        startActivityForResult(Intent.createChooser(loadIntent, getString(R.string.ask_share)), SHARE_FILE_CODE);
                    } catch (Exception e) {
                        Log.d(TAG, "Sharing a file FAIL");
                    }
                }
            } else {
                Intent loadIntent = new Intent()
                        .setType("*/*")
                        .setAction(Intent.ACTION_GET_CONTENT);
                startActivityForResult(Intent.createChooser(loadIntent, getString(R.string.ask_select_device_file)), SHARE_FILE_CODE);
            }


            return true;
        } else if (item.getItemId() == R.id.action_find_isotopes) {
            Intent intent_find_isotopes = new Intent(this, AtomSpectraFindIsotope.class);
            startActivity(intent_find_isotopes);
            return true;
        } else if (item.getItemId() == R.id.action_edit_sensivity) {
            Intent intent_sensitivity = new Intent(this, AtomSpectraSensitivity.class);
            startActivity(intent_sensitivity);
            return true;
        } else if (item.getItemId() == R.id.action_cal_add_point) {
            onAddCalibrationPoint(findViewById(R.id.addCalibrationPointButton));
            return true;
        } else if (item.getItemId() == R.id.action_cal_clear_point) {
            onClearCalibrationButton(findViewById(R.id.removeCalibrationButton));
            return true;
        } else if (item.getItemId() == R.id.action_cal_calc) {
            onCalibrateButton(findViewById(R.id.calibrateButton));
            return true;
        } else if (item.getItemId() == R.id.action_cal_point_1 ||
                item.getItemId() == R.id.action_cal_point_2 ||
                item.getItemId() == R.id.action_cal_point_3 ||
                item.getItemId() == R.id.action_cal_point_4 ||
                item.getItemId() == R.id.action_cal_point_5) {
            int number = 0;
            if (item.getItemId() == R.id.action_cal_point_2)
                number = 1;
            if (item.getItemId() == R.id.action_cal_point_3)
                number = 2;
            if (item.getItemId() == R.id.action_cal_point_4)
                number = 3;
            if (item.getItemId() == R.id.action_cal_point_5)
                number = 4;
            //TODO:
            final MenuItem itemMenu = item;
            final AlertDialog.Builder alert = new AlertDialog.Builder(this);
            alert.setTitle(getString(R.string.cal_coefficient_title, number));
            alert.setMessage(getString(R.string.cal_coefficient_text));

            final EditText input = new EditText(this);
            input.setText(String.format(Locale.getDefault(), "%.12g", AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().getCoeffArray(5)[number]));
            input.setKeyListener(new NumberKeyListener() {
                @NonNull
                @Override
                protected char[] getAcceptedChars() {
                    return new char[]{'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '.', ',', 'e', 'E', '+', '-'};
                }

                @Override
                public int getInputType() {
                    return InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED | InputType.TYPE_NUMBER_VARIATION_NORMAL;
                }
            });
            input.setImeOptions(EditorInfo.IME_ACTION_DONE);
            alert.setView(input);
            final Context context = this;
            int finalNumber = number;
            alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                float fValue;
                try {
                    fValue = Float.parseFloat(input.getText().toString().replaceAll(",", "."));
                } catch (Exception nfe) {
                    ToastHelper.showToast(context, getString(R.string.cal_error_number));
                    return;
                }
                Calibration new_cal = new Calibration();
                double[] coeffs = AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().getCoeffArray(5);
                coeffs[finalNumber] = fValue;
                new_cal.Calculate(coeffs);
                if (!new_cal.isCorrect()) {
                    ToastHelper.showToast(context, getString(R.string.cal_error_number));
                    return;
                }
                itemMenu.setTitle(String.format(Locale.getDefault(), "c%d: %.12g", finalNumber, fValue));
                AtomSpectraService.ForegroundSpectrum.setSpectrumCalibration(new_cal);
                sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_GRAPH).setPackage(Constants.PACKAGE_NAME));
            });
            alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
            });
            alert.show();
            return true;
        } else if (item.getItemId() == R.id.action_cal_new_point1 ||
                item.getItemId() == R.id.action_cal_new_point2 ||
                item.getItemId() == R.id.action_cal_new_point3 ||
                item.getItemId() == R.id.action_cal_new_point4 ||
                item.getItemId() == R.id.action_cal_new_point5 ||
                item.getItemId() == R.id.action_cal_new_point6 ||
                item.getItemId() == R.id.action_cal_new_point7 ||
                item.getItemId() == R.id.action_cal_new_point8 ||
                item.getItemId() == R.id.action_cal_new_point9 ||
                item.getItemId() == R.id.action_cal_new_point10) {
            int number = 0;
            if (item.getItemId() == R.id.action_cal_new_point2)
                number = 1;
            if (item.getItemId() == R.id.action_cal_new_point3)
                number = 2;
            if (item.getItemId() == R.id.action_cal_new_point4)
                number = 3;
            if (item.getItemId() == R.id.action_cal_new_point5)
                number = 4;
            if (item.getItemId() == R.id.action_cal_new_point6)
                number = 5;
            if (item.getItemId() == R.id.action_cal_new_point7)
                number = 6;
            if (item.getItemId() == R.id.action_cal_new_point8)
                number = 7;
            if (item.getItemId() == R.id.action_cal_new_point9)
                number = 8;
            if (item.getItemId() == R.id.action_cal_new_point10)
                number = 9;
            int finalNumber = number;
            final AlertDialog.Builder alert = new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.ask_delete_calibration_line_title))
                    .setMessage(getString(R.string.ask_delete_calibration_line_text, AtomSpectraService.newCalibration.getPointChannel(finalNumber), AtomSpectraService.newCalibration.getPointEnergy(finalNumber)))
                    .setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                        int channel_x = AtomSpectraService.newCalibration.getPointChannel(finalNumber);
                        double fValue = AtomSpectraService.newCalibration.getPointEnergy(finalNumber);
                        AtomSpectraService.newCalibration.removePoint(finalNumber);
                        if (AtomSpectraService.newCalibration.getPointsCount() > 1) {
                            AtomSpectraService.newCalibration.Calculate(sharedPreferences.getInt(Constants.CONFIG.CONF_MAX_POLI_FACTOR, Constants.DEFAULT_POLI_FACTOR));
                            if (!AtomSpectraService.newCalibration.isCorrect()) {
                                ToastHelper.showToast(AtomSpectra.this, getString(R.string.cal_maybe_wrong, channel_x, fValue));
                            }
                        } else {
                            AtomSpectraService.showCalibrationFunction = false;
                            app_menu.findItem(R.id.action_cal_draw_function).setChecked(false);
                            app_menu.findItem(R.id.action_cal_draw_function).setEnabled(false);
                        }
                        updateCalibrationMenu();
                        showCursorInfo(true);
                    })
                    .setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
                    });
            alert.show();
            return true;
        } else if (item.getItemId() == R.id.action_cal_function) {
            try {
                final AlertDialog.Builder alert = new AlertDialog.Builder(this);
                alert.setTitle(getString(R.string.cal_function_title));
                alert.setMessage(getString(R.string.cal_function_message));

                final TextView output = new TextView(this);
                output.setText(AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().getFunction());
                output.setTextSize(18);
                alert.setView(output);
                alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                });
                alert.show();
                return true;
            } catch (Exception e) {
                //..
            }
        } else if (item.getItemId() == R.id.action_cal_draw_function) {
            if (AtomSpectraService.newCalibration.getPointsCount() > 1) {
                item.setChecked(!AtomSpectraService.showCalibrationFunction);
                AtomSpectraService.showCalibrationFunction = !AtomSpectraService.showCalibrationFunction;
            } else {
                AtomSpectraService.showCalibrationFunction = false;
                item.setChecked(false);
            }
            showCursorInfo(true);
        } else if (item.getItemId() == R.id.action_cal_channel) {
            final MenuItem menuVal = item;
            final AlertDialog.Builder alert = new AlertDialog.Builder(this);
            alert.setTitle(getString(R.string.ask_last_channel_title));
            alert.setMessage(getString(R.string.ask_last_channel_text, Constants.NUM_HIST_POINTS));

            final EditText input = new EditText(this);
            input.setKeyListener(new NumberKeyListener() {
                @NonNull
                @Override
                protected char[] getAcceptedChars() {
                    return new char[]{'0', '1', '2', '3', '4', '5', '6', '7', '8', '9'};
                }

                @Override
                public int getInputType() {
                    return InputType.TYPE_CLASS_NUMBER;
                }
            });
            input.setImeOptions(EditorInfo.IME_ACTION_DONE);
            alert.setView(input);
            final Context context = this;
            alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                int iValue;
                try {
                    iValue = Integer.parseInt(input.getText().toString().replaceAll(",", "."));
                } catch (Exception nfe) {
                    ToastHelper.showToast(context, getString(R.string.cal_error_number));
                    return;
                }
                if (iValue < 1024)
                    iValue = 1024;
                if (iValue > Constants.NUM_HIST_POINTS)
                    iValue = Constants.NUM_HIST_POINTS;
                AtomSpectraService.lastCalibrationChannel = iValue;
                sharedPreferences.edit().putInt(Constants.CONFIG.CONF_LAST_CHANNEL, AtomSpectraService.lastCalibrationChannel).apply();
                menuVal.setTitle(getString(R.string.calibration_channel_format, iValue));
                sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_GRAPH).setPackage(Constants.PACKAGE_NAME));
            });
            alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {

            });
            alert.show();
        } else if (item.getItemId() == R.id.action_settings) {
            Intent intent = new Intent(this, AtomSpectraSettings.class);
            startActivity(intent);
            return true;
        } else if (item.getItemId() == R.id.action_buy) {
            Intent buyIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://kbradar.org"));
            if (buyIntent.resolveActivity(getApplicationContext().getPackageManager()) != null)
                startActivity(buyIntent);
            return true;
        } else if (item.getItemId() == R.id.action_exit) {
            if (!AtomSpectraService.getFreeze()) {
                // spectrum recording is in progress, confirm action
                DialogHelper.showActionConfirmationDialog(this, getString(R.string.dialog_confirm_exit_while_recording_message), () -> {
                    exitProgramCompletely();
                });
            } else {
                exitProgramCompletely();
            }

            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void exitProgramCompletely() {
        AtomSpectraIsotopes.foundList.clear();
        AtomSpectraIsotopes.showFoundIsotopes = false;
        sendBroadcast(new Intent(Constants.ACTION.ACTION_STOP_FOREGROUND).setPackage(Constants.PACKAGE_NAME));
    }

    @SuppressLint({"ApplySharedPref", "WrongConstant"})
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        Uri selectedFile;
        if (requestCode == LOAD_HIST_CODE && resultCode == RESULT_OK) {
            selectedFile = data.getData(); //The uri with the location of the file
            if (selectedFile != null)
                loadSpectrum(selectedFile, true);
        } else if (requestCode == ADD_HIST_CODE && resultCode == RESULT_OK) {
            selectedFile = data.getData(); //The uri with the location of the file
            if (selectedFile != null)
                loadSpectrum(selectedFile, true);
        } else if (requestCode == LOAD_BACK_CODE && resultCode == RESULT_OK) {

            selectedFile = data.getData(); //The uri with the location of the file
            if (selectedFile != null)
                loadBackgroundOrDefault(selectedFile);
        } else if (requestCode == LOAD_CALIBRATION_CODE && resultCode == RESULT_OK) {
            selectedFile = data.getData(); //The uri with the location of the file
            if (selectedFile != null)
                loadCalibrationFromSpectrum(selectedFile);
        } else if (requestCode == SELECT_LOAD_DEVICE_CODE && resultCode == RESULT_OK) {
            selectedFile = data.getData(); //The uri with the location of the file
            if (selectedFile != null)
                loadDevice(selectedFile);
        } else if (requestCode == SHARE_FILE_CODE && resultCode == RESULT_OK) {
            selectedFile = data.getData();
            if (selectedFile != null)
                shareFile(selectedFile);
        } else if (requestCode == LOAD_SPG_CODE && resultCode == RESULT_OK) {
            selectedFile = data.getData(); //The uri with the location of the file
            if (selectedFile != null)
                loadAndViewSpectrogram(selectedFile);
        } else if (requestCode == SELECT_SAVE_HIST_DIR_CODE && resultCode == RESULT_OK && (data != null)) {
            try {
                final Uri dirUri = data.getData();
                if (dirUri != null) {
                    final DocumentFile dir = DocumentFile.fromTreeUri(this, dirUri);
                    if ((dir != null) && dir.isDirectory()) {
                        SharedPreferences.Editor editor = sharedPreferences.edit();
                        Uri uri = data.getData();
                        if (uri != null) {
                            editor.putString(Constants.CONFIG.CONF_DIRECTORY_SELECTED, dirUri.toString());
                            final int takeFlags = data.getFlags()
                                    & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
// Check for the freshest data.
                            getContentResolver().takePersistableUriPermission(uri, takeFlags);
                        }
                        editor.commit();
                        final AlertDialog.Builder alert = new AlertDialog.Builder(this);
                        alert.setTitle(getString(R.string.ask_spectrum_suffix));
                        alert.setMessage(getString(R.string.ask_suffix_text));

                        final EditText input = new EditText(this);
                        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
                        input.setText(AtomSpectraService.ForegroundSpectrum.getSuffix(), TextView.BufferType.EDITABLE);
                        alert.setView(input);
                        alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                            AtomSpectraService.ForegroundSpectrum.setSuffix(input.getText().toString());
                            TextView text = findViewById(R.id.suffixView);
                            text.setText(AtomSpectraService.ForegroundSpectrum.getSuffix());
                            saveSpectrumAS(AtomSpectraService.ForegroundSpectrum.getSuffix());
                        });
                        alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
                        });
                        alert.show();
                    } else {
                        ToastHelper.showToast(this, getString(R.string.perm_no_write_histogram));
                    }
                } else {
                    ToastHelper.showToast(this, getString(R.string.perm_no_write_histogram));
                }
            } catch (Exception e) {
                Log.d(TAG, "saving file FAIL");
            }
        } else if (requestCode == SELECT_SAVE_BACK_DIR_CODE && resultCode == RESULT_OK && (data != null)) {
            try {
                final Uri dirUri = data.getData();
                if (dirUri != null) {
                    final DocumentFile dir = DocumentFile.fromTreeUri(this, dirUri);
                    if ((dir != null) && dir.isDirectory()) {
                        SharedPreferences.Editor editor = sharedPreferences.edit();
                        Uri uri = data.getData();
                        if (uri != null) {
                            editor.putString(Constants.CONFIG.CONF_DIRECTORY_SELECTED, dirUri.toString());
                            final int takeFlags = data.getFlags()
                                    & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
// Check for the freshest data.
                            getContentResolver().takePersistableUriPermission(uri, takeFlags);
                        }
                        editor.commit();
                        saveDefaultBackground();
                    } else {
                        ToastHelper.showToast(this, getString(R.string.perm_no_write_background));
                    }
                } else {
                    ToastHelper.showToast(this, getString(R.string.perm_no_write_background));
                }
            } catch (Exception e) {
                Log.d(TAG, "saving file FAIL");
            }
        } else if (requestCode == SELECT_LOAD_BACK_DIR_CODE && resultCode == RESULT_OK && (data != null)) {
            try {
                final Uri dirUri = data.getData();
                if (dirUri != null) {
                    final DocumentFile dir = DocumentFile.fromTreeUri(this, dirUri);
                    if ((dir != null) && dir.isDirectory()) {
                        SharedPreferences.Editor editor = sharedPreferences.edit();
                        Uri uri = data.getData();
                        if (uri != null) {
                            editor.putString(Constants.CONFIG.CONF_DIRECTORY_SELECTED, dirUri.toString());
                            final int takeFlags = data.getFlags()
                                    & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
// Check for the freshest data.
                            getContentResolver().takePersistableUriPermission(uri, takeFlags);
                        }
                        editor.commit();
                        loadBackgroundOrDefault(null);
                    } else {
                        ToastHelper.showToast(this, getString(R.string.perm_no_read_background));
                    }
                } else {
                    ToastHelper.showToast(this, getString(R.string.perm_no_read_background));
                }
            } catch (Exception e) {
                Log.d(TAG, "saving file FAIL");
            }
        } else if (requestCode == SELECT_SAVE_EXPORT_DIR_CODE && resultCode == RESULT_OK && (data != null)) {
            try {
                final Uri dirUri = data.getData();
                if (dirUri != null) {
                    final DocumentFile dir = DocumentFile.fromTreeUri(this, dirUri);
                    if ((dir != null) && dir.isDirectory()) {
                        SharedPreferences.Editor editor = sharedPreferences.edit();
                        Uri uri = data.getData();
                        if (uri != null) {
                            editor.putString(Constants.CONFIG.CONF_DIRECTORY_SELECTED, dirUri.toString());
                            final int takeFlags = data.getFlags()
                                    & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
// Check for the freshest data.
                            getContentResolver().takePersistableUriPermission(uri, takeFlags);
                        }
                        editor.commit();
                        final AlertDialog.Builder alert = new AlertDialog.Builder(this);
                        alert.setTitle(getString(R.string.ask_spectrum_suffix));
                        alert.setMessage(getString(R.string.ask_suffix_text));

                        final EditText input = new EditText(this);
                        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
                        input.setText(AtomSpectraService.ForegroundSpectrum.getSuffix(), TextView.BufferType.EDITABLE);
                        alert.setView(input);
                        alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                            AtomSpectraService.ForegroundSpectrum.setSuffix(input.getText().toString());
                            TextView text = findViewById(R.id.suffixView);
                            text.setText(AtomSpectraService.ForegroundSpectrum.getSuffix());
                            saveCSV(AtomSpectraService.ForegroundSpectrum.getSuffix(), false);
                        });
                        alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
                        });
                        alert.show();
                    } else {
                        ToastHelper.showToast(this, getString(R.string.perm_no_write_export));
                    }
                } else {
                    ToastHelper.showToast(this, getString(R.string.perm_no_write_export));
                }
            } catch (Exception e) {
                Log.d(TAG, "saving file FAIL");
            }
        } else if (requestCode == SELECT_SAVE_EXPORT_E_DIR_CODE && resultCode == RESULT_OK && (data != null)) {
            try {
                final Uri dirUri = data.getData();
                if (dirUri != null) {
                    final DocumentFile dir = DocumentFile.fromTreeUri(this, dirUri);
                    if ((dir != null) && dir.isDirectory()) {
                        SharedPreferences.Editor editor = sharedPreferences.edit();
                        Uri uri = data.getData();
                        if (uri != null) {
                            editor.putString(Constants.CONFIG.CONF_DIRECTORY_SELECTED, dirUri.toString());
                            final int takeFlags = data.getFlags()
                                    & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
// Check for the freshest data.
                            getContentResolver().takePersistableUriPermission(uri, takeFlags);
                        }
                        editor.commit();
                        final AlertDialog.Builder alert = new AlertDialog.Builder(this);
                        alert.setTitle(getString(R.string.ask_spectrum_suffix));
                        alert.setMessage(getString(R.string.ask_suffix_text));

                        final EditText input = new EditText(this);
                        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
                        input.setText(AtomSpectraService.ForegroundSpectrum.getSuffix(), TextView.BufferType.EDITABLE);
                        alert.setView(input);
                        alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                            AtomSpectraService.ForegroundSpectrum.setSuffix(input.getText().toString());
                            TextView text = findViewById(R.id.suffixView);
                            text.setText(AtomSpectraService.ForegroundSpectrum.getSuffix());
                            saveCSV(AtomSpectraService.ForegroundSpectrum.getSuffix(), true);
                        });
                        alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
                        });
                        alert.show();
                    } else {
                        ToastHelper.showToast(this, getString(R.string.perm_no_write_export_energy));
                    }
                } else {
                    ToastHelper.showToast(this, getString(R.string.perm_no_write_export_energy));
                }
            } catch (Exception e) {
                Log.d(TAG, "saving file FAIL");
            }
        } else if (requestCode == SELECT_SAVE_EXPORT_BQ_DIR_CODE && resultCode == RESULT_OK && (data != null)) {
            try {
                final Uri dirUri = data.getData();
                if (dirUri != null) {
                    final DocumentFile dir = DocumentFile.fromTreeUri(this, dirUri);
                    if ((dir != null) && dir.isDirectory()) {
                        SharedPreferences.Editor editor = sharedPreferences.edit();
                        Uri uri = data.getData();
                        if (uri != null) {
                            editor.putString(Constants.CONFIG.CONF_DIRECTORY_SELECTED, dirUri.toString());
                            final int takeFlags = data.getFlags()
                                    & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
// Check for the freshest data.
                            getContentResolver().takePersistableUriPermission(uri, takeFlags);
                        }
                        editor.commit();
                        final AlertDialog.Builder alert = new AlertDialog.Builder(this);
                        alert.setTitle(getString(R.string.ask_spectrum_suffix));
                        alert.setMessage(getString(R.string.ask_suffix_text));

                        final EditText input = new EditText(this);
                        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
                        input.setText(AtomSpectraService.ForegroundSpectrum.getSuffix(), TextView.BufferType.EDITABLE);
                        alert.setView(input);
                        alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                            AtomSpectraService.ForegroundSpectrum.setSuffix(input.getText().toString());
                            TextView text = findViewById(R.id.suffixView);
                            text.setText(AtomSpectraService.ForegroundSpectrum.getSuffix());
                            saveBqMoni(AtomSpectraService.ForegroundSpectrum.getSuffix());
                        });
                        alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
                        });
                        alert.show();
                    } else {
                        ToastHelper.showToast(this, getString(R.string.perm_no_write_bqmoni));
                    }
                } else {
                    ToastHelper.showToast(this, getString(R.string.perm_no_write_bqmoni));
                }
            } catch (Exception e) {
                Log.d(TAG, "saving file FAIL");
            }
        } else if (requestCode == SELECT_SAVE_EXPORT_SPE_DIR_CODE && resultCode == RESULT_OK && (data != null)) {
            try {
                final Uri dirUri = data.getData();
                if (dirUri != null) {
                    final DocumentFile dir = DocumentFile.fromTreeUri(this, dirUri);
                    if ((dir != null) && dir.isDirectory()) {
                        SharedPreferences.Editor editor = sharedPreferences.edit();
                        Uri uri = data.getData();
                        if (uri != null) {
                            editor.putString(Constants.CONFIG.CONF_DIRECTORY_SELECTED, dirUri.toString());
                            final int takeFlags = data.getFlags()
                                    & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
// Check for the freshest data.
                            getContentResolver().takePersistableUriPermission(uri, takeFlags);
                        }
                        editor.commit();
                        final AlertDialog.Builder alert = new AlertDialog.Builder(this);
                        alert.setTitle(getString(R.string.ask_export_spe));
                        alert.setMessage(getString(R.string.ask_suffix_text));

                        final EditText input = new EditText(this);
                        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                        input.setText(AtomSpectraService.ForegroundSpectrum.getSuffix(), TextView.BufferType.EDITABLE);
                        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
                        alert.setView(input);
                        alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                            AtomSpectraService.ForegroundSpectrum.setSuffix(input.getText().toString());
                            TextView text = findViewById(R.id.suffixView);
                            text.setText(AtomSpectraService.ForegroundSpectrum.getSuffix());
                            saveSPE(AtomSpectraService.ForegroundSpectrum.getSuffix());
                        });
                        alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
                        });
                        alert.show();
                    } else {
                        ToastHelper.showToast(this, getString(R.string.perm_no_write_spe));
                    }
                } else {
                    ToastHelper.showToast(this, getString(R.string.perm_no_write_spe));
                }
            } catch (Exception e) {
                Log.d(TAG, "saving file FAIL");
            }
        } else if (requestCode == SELECT_SAVE_EXPORT_N42_DIR_CODE && resultCode == RESULT_OK && (data != null)) {
            try {
                final Uri dirUri = data.getData();
                if (dirUri != null) {
                    final DocumentFile dir = DocumentFile.fromTreeUri(this, dirUri);
                    if ((dir != null) && dir.isDirectory()) {
                        SharedPreferences.Editor editor = sharedPreferences.edit();
                        Uri uri = data.getData();
                        if (uri != null) {
                            editor.putString(Constants.CONFIG.CONF_DIRECTORY_SELECTED, dirUri.toString());
                            final int takeFlags = data.getFlags()
                                    & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
// Check for the freshest data.
                            getContentResolver().takePersistableUriPermission(uri, takeFlags);
                        }
                        editor.commit();
                        final AlertDialog.Builder alert = new AlertDialog.Builder(this);
                        alert.setTitle(getString(R.string.ask_export_N42));
                        alert.setMessage(getString(R.string.ask_suffix_text));

                        final EditText input = new EditText(this);
                        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
                        input.setText(AtomSpectraService.ForegroundSpectrum.getSuffix(), TextView.BufferType.EDITABLE);
                        alert.setView(input);
                        alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                            AtomSpectraService.ForegroundSpectrum.setSuffix(input.getText().toString());
                            TextView text = findViewById(R.id.suffixView);
                            text.setText(AtomSpectraService.ForegroundSpectrum.getSuffix());
                            saveN42(AtomSpectraService.ForegroundSpectrum.getSuffix());
                        });
                        alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
                        });
                        alert.show();
                    } else {
                        ToastHelper.showToast(this, getString(R.string.perm_no_write_N42));
                    }
                } else {
                    ToastHelper.showToast(this, getString(R.string.perm_no_write_N42));
                }
            } catch (Exception e) {
                Log.d(TAG, "saving file FAIL");
            }
        } else if (requestCode == SELECT_SAVE_DEVICE_DIR_CODE && resultCode == RESULT_OK && (data != null)) {
            try {
                final Uri dirUri = data.getData();
                if (dirUri != null) {
                    final DocumentFile dir = DocumentFile.fromTreeUri(this, dirUri);
                    if ((dir != null) && dir.isDirectory()) {
                        SharedPreferences.Editor editor = sharedPreferences.edit();
                        Uri uri = data.getData();
                        if (uri != null) {
                            editor.putString(Constants.CONFIG.CONF_DIRECTORY_SELECTED, dirUri.toString());
                            final int takeFlags = data.getFlags()
                                    & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
// Check for the freshest data.
                            getContentResolver().takePersistableUriPermission(uri, takeFlags);
                        }
                        editor.commit();
                        final AlertDialog.Builder alert = new AlertDialog.Builder(this);
                        alert.setTitle(getString(R.string.ask_device_suffix));
                        alert.setMessage(getString(R.string.ask_suffix_text));

                        final EditText input = new EditText(this);
                        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
                        alert.setView(input);
                        alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                            String value = input.getText().toString();
                            saveDevice(value);
                        });
                        alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
                        });
                        alert.show();
                    } else {
                        ToastHelper.showToast(this, getString(R.string.perm_no_write_device));
                    }
                } else {
                    ToastHelper.showToast(this, getString(R.string.perm_no_write_device));
                }
            } catch (Exception e) {
                Log.d(TAG, "saving file FAIL");
            }
        } else
            super.onActivityResult(requestCode, resultCode, data);
    }

    private void loadSpectrum(Uri histFile, boolean showMessage) {
        final String filename = histFile.getPath();
        if (filename == null) {
            Log.d(TAG, "Null filename");
            ToastHelper.showToast(this, getString(R.string.strange_file_name));
            return;
        }
        Log.d(TAG, filename);

        SpectrumFileAS spectrumFile = new SpectrumFileAS();
        spectrumFile.setChannels(Constants.NUM_HIST_POINTS);
        try {
            spectrumFile.loadSpectrum(histFile, this);
            Spectrum spectrum = spectrumFile.getSpectrum(0);
            if (spectrum == null) {
                throw new NullPointerException("Unexpected: spectrum is null after successful load");
            }

            AtomSpectraService.freeze(true);
            sendBroadcast(new Intent(Constants.ACTION.ACTION_FREEZE_DATA).putExtra(AtomSpectraSerial.EXTRA_DATA_TYPE, true).setPackage(Constants.PACKAGE_NAME));

            AtomSpectraService.ForegroundSpectrum.ReinitializeFrom(spectrum);
            ((TextView) findViewById(R.id.suffixView)).setText(spectrum.getSuffix());
            AtomSpectraService.recalculateInterval();
            updateCalibrationMenu();
            AtomSpectraService.total_counts = spectrum.getTotalCounts();
            if (app_menu != null) {
                app_menu.findItem(R.id.action_hist_freeze).setIcon(R.drawable.record);
                app_menu.findItem(R.id.action_hist_freeze).setTitle(R.string.hist_continue_update);
            }
            AtomSpectraIsotopes.showFoundIsotopes = false;
            AtomSpectraIsotopes.foundList.clear();
            sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_GRAPH).setPackage(Constants.PACKAGE_NAME));
            Log.d(TAG, "Histogram is loaded successfully");
            if (showMessage) {
                ToastHelper.showToast(this, getString(R.string.hist_load_success));
            }
        } catch (Exception e) {
            AtomSpectraLog.addMessage(this, Log.getStackTraceString(e));
            if (showMessage) {
                ToastHelper.showToast(this, getString(R.string.hist_load_error));
            }
        }
    }

    private void loadCalibrationFromSpectrum(Uri histFile) {
        final String filename = histFile.getPath();
        if (filename == null) {
            Log.d(TAG, "Null filename");
            ToastHelper.showToast(this, getString(R.string.strange_file_name));
            return;
        }
        Log.d(TAG, filename);

        SpectrumFileAS spectrumFile = new SpectrumFileAS();
        spectrumFile.setChannels(Constants.NUM_HIST_POINTS);
        try {
            spectrumFile.loadSpectrum(histFile, this);
            Spectrum spectrum = spectrumFile.getSpectrum(0);
            if (spectrum == null) {
                throw new NullPointerException("Unexpected: spectrum is null after successful load");
            }

            AtomSpectraService.ForegroundSpectrum.setSpectrumCalibration(spectrum.getSpectrumCalibration());
            AtomSpectraService.recalculateInterval();
            updateCalibrationMenu();

            Log.d(TAG, "Calibration is loaded successfully");
            ToastHelper.showToast(this, getString(R.string.cal_load_success));
        } catch (Exception e) {
            AtomSpectraLog.addMessage(this, Log.getStackTraceString(e));
            ToastHelper.showToast(this, getString(R.string.cal_load_error));
        }
    }


    private static boolean isLoadingSpectrogram = false;
    private static CancellationToken loadingSpectrogramCancellationToken = null;
    private static AlertDialog spectrogramLoadingDialog = null;

    private void showSpectrogramLoadingDialog() {
        final AlertDialog.Builder alert = new AlertDialog.Builder(this)
                .setTitle(R.string.spectrogram_loading_dialog_title)
                .setMessage(getString(R.string.spectrogram_loading_dialog_message, AtomSpectraSpectrogramData.instance.rowCount()))
                .setPositiveButton(getString(R.string.spectrogram_loading_dialog_stop), (dialog, whichButton) -> {
                    if (loadingSpectrogramCancellationToken != null) {
                        loadingSpectrogramCancellationToken.cancel();
                    }
                })
                .setCancelable(false);

        spectrogramLoadingDialog = alert.show();
    }

    private void dismissSpectrogramLoadingDialog() {
        if (spectrogramLoadingDialog != null) {
            spectrogramLoadingDialog.dismiss();
            spectrogramLoadingDialog = null;
        }
    }

    private void checkSpectrogramIsLoading() {
        if (active && isLoadingSpectrogram && spectrogramLoadingDialog == null) {
            showSpectrogramLoadingDialog();
        }
    }

    private void loadAndViewSpectrogram(Uri histFile) {
        if (isLoadingSpectrogram) {
            ToastHelper.showToast(this, "ERROR: loadAndViewSpectrogram called while spectrogram is loading.");
            return;
        }

        final String filename = histFile.getPath();
        if (filename == null) {
            Log.d(TAG, "Null filename");
            ToastHelper.showToast(this, getString(R.string.strange_file_name));
            return;
        }
        Log.d(TAG, filename);

        SpectrumFileAS spectrumFile = new SpectrumFileAS();
        spectrumFile.setChannels(Constants.NUM_HIST_POINTS);
        AtomSpectraSpectrogramData.instance.clear();

        loadingSpectrogramCancellationToken = new CancellationToken();
        showSpectrogramLoadingDialog();

        Handler mainHandler = new Handler(Looper.getMainLooper());
        Context context = this;
        new Thread(() -> {
            isLoadingSpectrogram = true;
            mainHandler.post(() -> updateSpectrogramMenu());

            try {
                spectrumFile.loadSpectrogram(histFile, context, AtomSpectraSpectrogramData.instance,
                        rowCount -> {
                                    mainHandler.post(() -> {
                                        if (active && spectrogramLoadingDialog != null) {
                                            spectrogramLoadingDialog.setMessage(getString(R.string.spectrogram_loading_dialog_message, AtomSpectraSpectrogramData.instance.rowCount()));
                                        }
                                    });
                                }, loadingSpectrogramCancellationToken);
                mainHandler.post(() -> showSpectrogramView());
                ToastHelper.showToast(getContext(), getString(R.string.spectrogram_load_success));
            } catch (Exception e) {
                AtomSpectraLog.addMessage(getContext(), Log.getStackTraceString(e));
                ToastHelper.showToast(getContext(), getString(R.string.spectrogram_load_error, e.getMessage()));
            } finally {
                isLoadingSpectrogram = false;
                mainHandler.post(() -> {
                    dismissSpectrogramLoadingDialog();
                    updateSpectrogramMenu();
                });
            }
        }).start();
    }

    private void saveSpectrumAS(String suffix) {
        Spectrum spectrum = new Spectrum(AtomSpectraService.ForegroundSpectrum);
        if (!sharedPreferences.getBoolean(Constants.CONFIG.CONF_ADD_GPS_TO_FILES, false)) {
            spectrum.setLocation(null).updateComments();
        }

        try {
            Pair<OutputStreamWriter, Uri> streamInfo = SpectrumFile.prepareOutputFileStream(this, getString(R.string.file_atomspectra_spectrum_prefix), spectrum.getSpectrumDate(), suffix, ".txt", "text/plain", false);
            OutputStreamWriter docStream = streamInfo.first;
            String spectrumFileName = streamInfo.second.getPath();

            SpectrumFileAS saveFile = new SpectrumFileAS();
            saveFile.addSpectrum(spectrum)
                    .setChannels(spectrum.getDataArray().length)
                    .setChannelCompression(1);
            saveFile.saveSpectrumAndCloseStream(docStream, this);
            AtomSpectraService.ForegroundSpectrum.setChanged(false);
            ToastHelper.showToast(this, getString(R.string.hist_save_success, spectrumFileName));
        } catch (Exception e) {
            AtomSpectraLog.addMessage(this, Log.getStackTraceString(e));
            ToastHelper.showToast(this, getString(R.string.hist_save_error, suffix));
        }
    }

    private void moveToBackground() {
        AtomSpectraService.BackgroundSpectrum.ReinitializeFrom(AtomSpectraService.ForegroundSpectrum);
        boolean show_back = !AtomSpectraService.BackgroundSpectrum.isEmpty();
        AtomSpectraService.background_show = show_back;
        app_menu.findItem(R.id.action_background_show).setChecked(show_back);
        app_menu.findItem(R.id.action_background_save).setEnabled(show_back);
        app_menu.findItem(R.id.action_background_show).setEnabled(show_back);
        app_menu.findItem(R.id.action_background_subtract).setEnabled(show_back);
        app_menu.findItem(R.id.action_background_clear).setEnabled(show_back);
        app_menu.findItem(R.id.action_background_suffix).setEnabled(show_back);
        TextView view = findViewById(R.id.backgroundSuffixView);
        view.setVisibility(show_back ? TextView.VISIBLE : TextView.INVISIBLE);
        view.setText(AtomSpectraService.BackgroundSpectrum.getSuffix());
    }

    private void loadBackgroundOrDefault(Uri backgroundFilePath) {
        if (backgroundFilePath == null) {
            // use default background
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                String workingDir = PrefHelper.getWorkingDir(this, true);
                if (workingDir == null) {
                    return;
                }
                // TODO: duplicated working dir validation code
                Uri dirUri = Uri.parse(workingDir);
                if (dirUri == null) {
                    AtomSpectraLog.addMessage(this, String.format("Unexpected: unable to parse working dir path '%s'", workingDir));
                    ToastHelper.showToast(this, getString(R.string.error_working_dir_not_valid, workingDir));
                    return;
                }
                DocumentFile dirFile = DocumentFile.fromTreeUri(this, dirUri);
                if ((dirFile == null) || !dirFile.isDirectory()) {
                    AtomSpectraLog.addMessage(this, String.format("Unexpected: working dir path is not a directory '%s'", workingDir));
                    ToastHelper.showToast(this, getString(R.string.error_working_dir_not_valid, workingDir));
                    return;
                }
                String path = workingDir + "/document/" + Uri.encode(DocumentsContract.getTreeDocumentId(dirUri) + "/Background");
                DocumentFile backgroundFile = DocumentFile.fromSingleUri(this, Uri.parse(path));
                if ((backgroundFile == null) || !backgroundFile.isFile()) {
                    AtomSpectraLog.addMessage(this, String.format("Unexpected: background file '%s' is not a file", path));
                    ToastHelper.showToast(this, getString(R.string.background_load_error));
                    return;
                }
                backgroundFilePath = backgroundFile.getUri();
            } else {
                String workingDir = PrefHelper.getWorkingDir(this, true);
                if (workingDir == null) {
                    return;
                }
                String path = workingDir + "/Background";
                backgroundFilePath = Uri.fromFile(new File(path));
                if (backgroundFilePath == null) {
                    AtomSpectraLog.addMessage(this, String.format("Unexpected: unable to construct default background Uri from path '%s'", path));
                    ToastHelper.showToast(this, getString(R.string.background_load_error));
                    return;
                }
            }
        }

        SpectrumFileAS spectrumFile = new SpectrumFileAS();
        try {
            spectrumFile.loadSpectrum(backgroundFilePath, this);
            Spectrum spectrum = spectrumFile.getSpectrum(0);
            if (spectrum == null) {
                throw new NullPointerException("Unexpected: spectrum is null after load");
            }
            AtomSpectraService.BackgroundSpectrum.ReinitializeFrom(spectrum);
            background_subtract = false;
            boolean show_back = !AtomSpectraService.BackgroundSpectrum.isEmpty();
            AtomSpectraService.background_show = show_back;
            app_menu.findItem(R.id.action_background_save).setEnabled(show_back);
            app_menu.findItem(R.id.action_background_show).setChecked(show_back);
            app_menu.findItem(R.id.action_background_show).setEnabled(show_back);
            app_menu.findItem(R.id.action_background_subtract).setEnabled(show_back);
            app_menu.findItem(R.id.action_background_subtract).setChecked(false);
            app_menu.findItem(R.id.action_background_clear).setEnabled(show_back);
            app_menu.findItem(R.id.action_background_suffix).setEnabled(show_back);
            TextView view = findViewById(R.id.backgroundSuffixView);
            view.setText(AtomSpectraService.BackgroundSpectrum.getSuffix());
            view.setVisibility(show_back ? TextView.VISIBLE : TextView.INVISIBLE);
            sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_GRAPH).setPackage(Constants.PACKAGE_NAME));
            Log.d(TAG, "Background is loaded successfully");
            ToastHelper.showToast(this, getString(R.string.background_load_success));
        } catch (Exception e) {
            AtomSpectraLog.addMessage(this, Log.getStackTraceString(e));
            ToastHelper.showToast(this, getString(R.string.background_load_error));
        }
    }

    private void saveDefaultBackground() {
        if (AtomSpectraService.BackgroundSpectrum.isEmpty()) {
            ToastHelper.showToast(this, getString(R.string.background_no_data));
            return;
        }

        Spectrum spectrum = new Spectrum(AtomSpectraService.BackgroundSpectrum);

        if (!sharedPreferences.getBoolean(Constants.CONFIG.CONF_ADD_GPS_TO_FILES, false)) {
            spectrum.setLocation(null).updateComments();
        }

        try {
            Pair<OutputStreamWriter, Uri> streamInfo = SpectrumFile.prepareOutputFileStream(this, "Background", spectrum.getSpectrumDate(), "", "", "application/octet-stream", true,false, false, true);
            OutputStreamWriter docStream = streamInfo.first;
            SpectrumFileAS saveFile = new SpectrumFileAS();
            saveFile.
                    addSpectrum(spectrum).
                    setChannels(spectrum.getDataArray().length).
                    setChannelCompression(1);
            saveFile.saveSpectrumAndCloseStream(docStream, this);
            ToastHelper.showToast(this, getString(R.string.background_save_success));
        } catch (Exception e) {
            AtomSpectraLog.addMessage(this, Log.getStackTraceString(e));
            ToastHelper.showToast(this, getString(R.string.background_save_error));
        }
    }

    private void saveCSV(String suffix, boolean with_energy) {
        Spectrum spectrum = new Spectrum(AtomSpectraService.ForegroundSpectrum);

        if (!sharedPreferences.getBoolean(Constants.CONFIG.CONF_ADD_GPS_TO_FILES, false)) {
            spectrum.setLocation(null).updateComments();
        }

        try {
            Pair<OutputStreamWriter, Uri> streamInfo = SpectrumFile.prepareOutputFileStream(this, "Export", spectrum.getSpectrumDate(), suffix, ".csv", "text/csv", false);
            OutputStreamWriter docStream = streamInfo.first;
            String spectrumFileName = streamInfo.second.getPath();

            SpectrumFileCSV saveFile = new SpectrumFileCSV();
            saveFile.
                    addSpectrum(spectrum).
                    setChannels(saveChannels).
                    setChannelCompression(channelCompression);
            saveFile.setAddEnergy(with_energy);
            saveFile.saveSpectrumAndCloseStream(docStream, this);
            ToastHelper.showToast(this, getString(R.string.export_save_success, spectrumFileName));
        } catch (Exception e) {
            AtomSpectraLog.addMessage(this, Log.getStackTraceString(e));
            ToastHelper.showToast(this, getString(R.string.export_save_error, suffix));
        }
    }

    private void saveBqMoni(String suffix) {
        Spectrum spectrum = new Spectrum(AtomSpectraService.ForegroundSpectrum);
        Spectrum backSpectrum = new Spectrum(AtomSpectraService.BackgroundSpectrum);

        if (!sharedPreferences.getBoolean(Constants.CONFIG.CONF_ADD_GPS_TO_FILES, false)) {
            spectrum.setLocation(null).updateComments();
            backSpectrum.setLocation(null).updateComments();
        }

        try {
            Pair<OutputStreamWriter, Uri> returnPair = SpectrumFile.prepareOutputFileStream(this, "Bq", spectrum.getSpectrumDate(), suffix, ".xml", "text/xml", false);
            OutputStreamWriter docStream = returnPair.first;
            String spectrumFileName = returnPair.second.getPath();

            SpectrumFileBqMoni saveFile = new SpectrumFileBqMoni();
            saveFile.addSpectrum(spectrum)
                    .setBackgroundSpectrum(backSpectrum)
                    .setChannels(saveChannels)
                    .setChannelCompression(channelCompression);
            saveFile.saveSpectrumAndCloseStream(docStream, this);
            ToastHelper.showToast(this, getString(R.string.export_save_success, spectrumFileName));
        } catch (Exception e) {
            AtomSpectraLog.addMessage(this, Log.getStackTraceString(e));
            ToastHelper.showToast(this, getString(R.string.export_save_error, suffix));
        }
    }

    private void saveSPE(String suffix) {
        Spectrum spectrum = new Spectrum(AtomSpectraService.ForegroundSpectrum);
        if (!sharedPreferences.getBoolean(Constants.CONFIG.CONF_ADD_GPS_TO_FILES, false)) {
            spectrum.setLocation(null).updateComments();
        }

        try {
            Pair<OutputStreamWriter, Uri> streamInfo = SpectrumFile.prepareOutputFileStream(this, "MCA", spectrum.getSpectrumDate(), suffix, ".spe", "application/octet-stream", false);
            OutputStreamWriter docStream = streamInfo.first;
            String spectrumFileName = streamInfo.second.getPath();

            SpectrumFileSPE saveFile = new SpectrumFileSPE();
            saveFile.addSpectrum(spectrum)
                    .setChannels(saveChannels)
                    .setChannelCompression(channelCompression);
            saveFile.saveSpectrumAndCloseStream(docStream, this);
            ToastHelper.showToast(this, getString(R.string.export_save_success, spectrumFileName));
        } catch (Exception e) {
            AtomSpectraLog.addMessage(this, Log.getStackTraceString(e));
            ToastHelper.showToast(this, getString(R.string.export_save_error, suffix));
        }
    }

    private void saveN42(String suffix) {
        Spectrum spectrum = new Spectrum(AtomSpectraService.ForegroundSpectrum);
        Spectrum backSpectrum = new Spectrum(AtomSpectraService.BackgroundSpectrum);

        if (!sharedPreferences.getBoolean(Constants.CONFIG.CONF_ADD_GPS_TO_FILES, false)) {
            spectrum.setLocation(null).updateComments();
            backSpectrum.setLocation(null).updateComments();
        }

        try {
            Pair<OutputStreamWriter, Uri> streamInfo = SpectrumFile.prepareOutputFileStream(this, "MCA", spectrum.getSpectrumDate(), suffix, ".N42", "application/octet-stream", false);
            OutputStreamWriter docStream = streamInfo.first;
            String spectrumFileName = streamInfo.second.getPath();

            SpectrumFileN42 saveFile = new SpectrumFileN42();
            saveFile.addSpectrum(spectrum)
                    .setBackgroundSpectrum(backSpectrum)
                    .setChannels(saveChannels)
                    .setChannelCompression(channelCompression);
            saveFile.saveSpectrumAndCloseStream(docStream, this);
            ToastHelper.showToast(this, getString(R.string.export_save_success, spectrumFileName));
        } catch (Exception e) {
            AtomSpectraLog.addMessage(this, Log.getStackTraceString(e));
            ToastHelper.showToast(this, getString(R.string.export_save_error, suffix));
        }
    }

    public void shareFile(Uri file) {
//        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US);
        final String filename = file.getPath();
        String onlyName, onlyLoName;
        if (filename == null) {
            ToastHelper.showToast(this, getString(R.string.strange_file_name));
            return;
        }
        if (filename.lastIndexOf('/') != -1) {
            onlyName = filename.substring(filename.lastIndexOf('/') + 1);
        } else {
            onlyName = filename;
        }
        Log.d(TAG, filename);
        Intent shareIntent = new Intent();
        shareIntent.setAction(Intent.ACTION_SEND);
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, onlyName);
        shareIntent.putExtra(Intent.EXTRA_TEXT, getString(R.string.ask_share_text, onlyName));
        try {
            shareIntent.putExtra(Intent.EXTRA_STREAM, file);
        } catch (Exception e) {
            ToastHelper.showToast(this, getString(R.string.perm_no_outside));
            return;
        }
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        onlyLoName = onlyName.toLowerCase(Locale.ROOT);

        if (!onlyLoName.equals(".csv") && onlyLoName.endsWith(".csv")) {
            shareIntent.setType("text/csv");
        } else if (!onlyLoName.equals(".txt") && onlyLoName.endsWith(".txt")) {
            shareIntent.setType("text/plain");
        } else if (!onlyLoName.equals(".n42") && onlyLoName.endsWith(".n42")) {
            shareIntent.setType("text/plain");
        } else if (!onlyLoName.equals(".spe") && onlyLoName.endsWith(".spe")) {
            shareIntent.setType("text/plain");
        } else if (onlyName.equals("Background")) {
            shareIntent.setType("text/plain");
        } else
            shareIntent.setType("*/*");

        try {
            if (shareIntent.resolveActivity(getPackageManager()) != null)
                startActivity(Intent.createChooser(shareIntent, null));
        } catch (Exception e) {
            ToastHelper.showToast(this, getString(R.string.something_wrong));
        }
    }

    //Save device data
    private void saveDevice(String suffix) {
        try {
            Pair<OutputStreamWriter, Uri> streamInfo = SpectrumFile.prepareOutputFileStream(this,  "Device", 0, suffix, ".txt", "text/plain", false);
            OutputStreamWriter docStream = streamInfo.first;
            String deviceFileName = streamInfo.second.getPath();

            OutputStreamWriter fw = docStream;
            fw.append("DEVFORMAT: 2\n");      //Type of file
            fw.append(String.format(Locale.US, "%d\n", sharedPreferences.getInt(Constants.CONFIG.CONF_SENSG, Constants.SENSG_DEFAULT)));                               //Sensitivity
            fw.append(String.format(Locale.US, "%d\n", sharedPreferences.getInt(Constants.CONFIG.CONF_SENSG_COMPENSATED, Constants.SENSG_COMPENSATED_DEFAULT)));       //Sensitivity for compensated dose rate
            fw.append(String.format(Locale.US, "%d\n", sharedPreferences.getInt(Constants.CONFIG.CONF_BACKGROUND, Constants.BACKGND_CPS_DEFAULT)));                    //Background
            fw.append(String.format(Locale.US, "%d\n", sharedPreferences.getInt(Constants.CONFIG.CONF_SEARCH_FAST, Constants.SEARCH_FAST_DEFAULT)));                   //fast counts
            fw.append(String.format(Locale.US, "%d\n", sharedPreferences.getInt(Constants.CONFIG.CONF_SEARCH_MEDIUM, Constants.SEARCH_MEDIUM_DEFAULT)));               //medium counts
            fw.append(String.format(Locale.US, "%d\n", sharedPreferences.getInt(Constants.CONFIG.CONF_SEARCH_SLOW, Constants.SEARCH_SLOW_DEFAULT)));                   //slow counts
            fw.append(String.format(Locale.US, "%d\n", sharedPreferences.getInt(Constants.CONFIG.CONF_MIN_POINTS, Constants.MIN_FRONT_POINTS_DEFAULT)));               //minimum front points
            fw.append(String.format(Locale.US, "%d\n", sharedPreferences.getInt(Constants.CONFIG.CONF_MAX_POINTS, Constants.MAX_FRONT_POINTS_DEFAULT)));               //maximum front points
            fw.append(String.format(Locale.US, "%d\n", sharedPreferences.getInt(Constants.CONFIG.CONF_NOISE, Constants.NOISE_DISCRIMINATOR_DEFAULT)));                 //noise discriminator
            fw.append(String.format(Locale.US, "%d\n", sharedPreferences.getInt(Constants.CONFIG.CONF_ROUNDED, Constants.ADC_DEFAULT)));                               //ADC bits
            fw.append(String.format(Locale.US, "%d\n", sharedPreferences.getInt(Constants.CONFIG.CONF_SAVE_CHANNELS, Constants.EXPORT_CHANNELS_DEFAULT)));             //save channels
            fw.append(String.format(Locale.US, "%d\n", sharedPreferences.getInt(Constants.CONFIG.CONF_LOAD_CHANNELS, Constants.LOAD_CHANNELS_DEFAULT)));               //load channels
            fw.append(String.format(Locale.US, "%d\n", sharedPreferences.getInt(Constants.CONFIG.CONF_COMPRESSION, Constants.EXPORT_COMPRESSION_DEFAULT)));            //channel compression level
            fw.append(String.format(Locale.US, "%d\n", sharedPreferences.getInt(Constants.SEARCH.PREF_WINDOW_SIZE, Constants.WINDOW_SEARCH_DEFAULT)));                 //windows search size
            fw.append(String.format(Locale.US, "%f\n", sharedPreferences.getFloat(Constants.SEARCH.PREF_THRESHOLD, Constants.THRESHOLD_DEFAULT)));                     //threshold
            fw.append(String.format(Locale.US, "%f\n", sharedPreferences.getFloat(Constants.SEARCH.PREF_TOLERANCE, Constants.TOLERANCE_DEFAULT)));                     //tolerance
            fw.append(String.format(Locale.US, "%d\n", sharedPreferences.getInt(Constants.SEARCH.PREF_ORDER, Constants.ORDER_DEFAULT)));                               //order size

            TreeMap<Float, Double> sensitivityTable = PrefHelper.getSensitivityTableOrDefault(this);
            fw.append(String.format(Locale.US, "%d\n", sensitivityTable.size()));
            ArrayList<Float> sortedEnergyList = new ArrayList<>(sensitivityTable.keySet());
            for (int i = 0; i < sortedEnergyList.size(); i++) {
                float energy = sortedEnergyList.get(i);
                fw.append(String.format(Locale.US, "%f\n", energy));
            }
            for (int i = 0; i < sortedEnergyList.size(); i++) {
                float energy = sortedEnergyList.get(i);
                double sens = sensitivityTable.get(energy);
                fw.append(String.format(Locale.US, "%.6f\n", sens));
            }
            fw.close();
            Log.d(TAG, deviceFileName + " saved successfully");
            ToastHelper.showToast(this, getString(R.string.device_save_success, deviceFileName));
        } catch (Exception e) {
            AtomSpectraLog.addMessage(this, Log.getStackTraceString(e));
            ToastHelper.showToast(this, getString(R.string.device_save_error, suffix));
        }
    }

    //Load device data
    @SuppressLint("ApplySharedPref")
    private void loadDevice(Uri devFile) {
        final String filename = devFile.getPath();
        if (filename == null) {
            Log.d(TAG, "Null filename");
            ToastHelper.showToast(this, getString(R.string.strange_file_name));
            return;
        }
        Log.d(TAG, filename);

        try {
            InputStream inputFile = getContentResolver().openInputStream(devFile);
            if (inputFile == null) {
                throw new Exception();
            }
            BufferedReader fr = new BufferedReader(new InputStreamReader(inputFile));
            Log.d(TAG, filename + " loading started...");
            String ident = fr.readLine();
            boolean isV1 = ident.matches("^DEVFORMAT: 1$");
            boolean isV2 = ident.matches("^DEVFORMAT: 2$");
            if (!isV1 && !isV2) {
                ToastHelper.showToast(this, getString(R.string.device_load_error));
                return;
            }
            int tempSensG = Constants.MinMax(Integer.parseInt(fr.readLine()), 0, 1000000);
            int tempSensGCompensated = 0;
            if (isV2) {
                tempSensGCompensated = Constants.MinMax(Integer.parseInt(fr.readLine()), 0, 1000000);
            }
            int tempBack = Constants.MinMax(Integer.parseInt(fr.readLine()), 0, 100000);
            int tempFast = Constants.MinMax(Integer.parseInt(fr.readLine()), 10, 100000);
            int tempMedium = Constants.MinMax(Integer.parseInt(fr.readLine()), 10, 100000);
            int tempSlow = Constants.MinMax(Integer.parseInt(fr.readLine()), 10, 100000);
            int tempMin = Constants.MinMax(Integer.parseInt(fr.readLine()), 2, 50);
            int tempMax = Constants.MinMax(Integer.parseInt(fr.readLine()), 2, 50);
            int tempNoise = Constants.MinMax(Integer.parseInt(fr.readLine()), 0, Constants.NUM_HIST_POINTS - 1);
            int tempBits = Constants.MinMax(Integer.parseInt(fr.readLine()), Constants.ADC_MIN, Constants.ADC_MAX);
            int tempSave = Constants.MinMax(Integer.parseInt(fr.readLine()), 1024, Constants.NUM_HIST_POINTS);
            int tempLoad = Constants.MinMax(Integer.parseInt(fr.readLine()), 0, 65536);
            int tempCompression = Constants.MinMax(Integer.parseInt(fr.readLine()), 1, 64);
            int tempWindow = Constants.MinMax(Integer.parseInt(fr.readLine()), 1, 200);                 //windows search size
            float tempThreshold = Float.parseFloat(fr.readLine());
            tempThreshold = (float) Math.rint(Constants.MinMax(tempThreshold * 100, 0, 1000000)) / 100.0f;
            float tempTolerance = Float.parseFloat(fr.readLine());
            tempTolerance = (float) Math.rint(Constants.MinMax(tempTolerance * 100, 1, 5000)) / 100.0f;                     //tolerance
            int tempOrder = Constants.MinMax(Integer.parseInt(fr.readLine()), 2, Constants.ORDER_MAX);
            int tempSensTableSize = Integer.parseInt(fr.readLine());
            float[] tempEArray = new float[tempSensTableSize];
            for (int i = 0; i < tempSensTableSize; i++) {
                tempEArray[i] = Float.parseFloat(fr.readLine());
                if (tempEArray[i] < 0) {
                    ToastHelper.showToast(this, "Negative energy in sensitivity table");
                    return;
                }
            }
            TreeMap<Float, Double> sensitivityTable = new TreeMap<>();
            for (int i = 0; i < tempSensTableSize; i++) {
                double sens = Double.parseDouble(fr.readLine());
                sensitivityTable.put(tempEArray[i], sens);
            }
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putInt(Constants.CONFIG.CONF_SENSG, tempSensG);                        //Sensitivity
            if (isV2) {                                                                      //Sensitivity for compensated DR
                editor.putInt(Constants.CONFIG.CONF_SENSG_COMPENSATED, tempSensGCompensated);
            }
            editor.putInt(Constants.CONFIG.CONF_BACKGROUND, tempBack);                    //Background
            editor.putInt(Constants.CONFIG.CONF_SEARCH_FAST, tempFast);                   //fast counts
            editor.putInt(Constants.CONFIG.CONF_SEARCH_MEDIUM, tempMedium);               //medium counts
            editor.putInt(Constants.CONFIG.CONF_SEARCH_SLOW, tempSlow);                   //slow counts
            editor.putInt(Constants.CONFIG.CONF_MIN_POINTS, tempMin);                     //minimum front points
            editor.putInt(Constants.CONFIG.CONF_MAX_POINTS, tempMax);                     //maximum front points
            editor.putInt(Constants.CONFIG.CONF_NOISE, tempNoise);                        //noise discriminator
            editor.putInt(Constants.CONFIG.CONF_ROUNDED, tempBits);                       //ADC bits
            editor.putInt(Constants.CONFIG.CONF_SAVE_CHANNELS, tempSave);                 //save channels
            editor.putInt(Constants.CONFIG.CONF_LOAD_CHANNELS, tempLoad);                 //load channels
            editor.putInt(Constants.CONFIG.CONF_COMPRESSION, tempCompression);            //channel compression level
            editor.putInt(Constants.SEARCH.PREF_WINDOW_SIZE, tempWindow);                 //windows search size
            editor.putFloat(Constants.SEARCH.PREF_THRESHOLD, tempThreshold);              //threshold
            editor.putFloat(Constants.SEARCH.PREF_TOLERANCE, tempTolerance);              //tolerance
            editor.putInt(Constants.SEARCH.PREF_ORDER, tempOrder);                        //order size
            editor.commit();
            PrefHelper.setSensitivityTable(this, sensitivityTable);
            sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_GRAPH).setPackage(Constants.PACKAGE_NAME));
            ToastHelper.showToast(this, getString(R.string.device_load_success));
        } catch (Exception e) {
            ToastHelper.showToast(this, getString(R.string.device_load_error));
        }
    }

    public void onAddCalibrationPoint(View view) {
//		final Button button = (Button) view;
        if (AtomSpectraService.newCalibration.getPointsCount() >= Constants.MAX_CALIBRATION_POINTS) {
            ToastHelper.showToast(this, getString(R.string.cal_no_more));
            return;
        }
        if (AtomSpectraService.newCalibration.containsPointChannel(cursor_x)) {
            ToastHelper.showToast(this, getString(R.string.cal_have_channel));
            return;
        }
        if ((cursor_x >= 0) && (cursor_x < Constants.NUM_HIST_POINTS)) {
            final AlertDialog.Builder alert = new AlertDialog.Builder(this);
            alert.setTitle(getString(R.string.cal_point_title, cursor_x));
            alert.setMessage(getString(R.string.ask_point_in_kev));

            final EditText input = new EditText(this);
            input.setKeyListener(new NumberKeyListener() {
                @NonNull
                @Override
                protected char[] getAcceptedChars() {
                    return new char[]{'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '.', ','};
                }

                @Override
                public int getInputType() {
                    return InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL;
                }
            });
            input.setImeOptions(EditorInfo.IME_ACTION_DONE);
            alert.setView(input);
            final Context context = this;
            alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                float fValue;// = value.valueOf(value);
                try {
                    fValue = Float.parseFloat(input.getText().toString().replaceAll(",", "."));
                } catch (Exception nfe) {
                    ToastHelper.showToast(context, getString(R.string.cal_error_number));
                    return;
                }
                AtomSpectraService.newCalibration.addPoint(cursor_x, fValue);
                if (AtomSpectraService.newCalibration.getPointsCount() > 1) {
                    AtomSpectraService.newCalibration.Calculate(sharedPreferences.getInt(Constants.CONFIG.CONF_MAX_POLI_FACTOR, Constants.DEFAULT_POLI_FACTOR));
                    app_menu.findItem(R.id.action_cal_draw_function).setEnabled(true);
                    if (!AtomSpectraService.newCalibration.isCorrect()) {
                        ToastHelper.showToast(this, getString(R.string.cal_maybe_wrong, cursor_x, fValue));
                    }
                }
                updateCalibrationMenu();
                showCursorInfo(true);
            });
            alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
            });
            alert.show();
        } else
            ToastHelper.showToast(this, getString(R.string.put_cursor_first));
    }

    public void onCalibrateButton(View view) {
        if (AtomSpectraService.newCalibration.getPointsCount() < 2) {
            ToastHelper.showToast(this, getString(R.string.cal_no_enough_data));
            return;
        }
        AtomSpectraService.newCalibration.Calculate(sharedPreferences.getInt(Constants.CONFIG.CONF_MAX_POLI_FACTOR, Constants.DEFAULT_POLI_FACTOR));
        if (AtomSpectraService.newCalibration.isCorrect()) {
            AtomSpectraService.showCalibrationFunction = false;
            app_menu.findItem(R.id.action_cal_draw_function).setChecked(false);
            app_menu.findItem(R.id.action_cal_draw_function).setEnabled(false);
            AtomSpectraService.ForegroundSpectrum.setSpectrumCalibration(AtomSpectraService.newCalibration);
            AtomSpectraService.newCalibration = new Calibration();
            updateCalibrationMenu();
            Button button = findViewById(R.id.addCalibrationPointButton);
            button.setText("1");
            button.setEnabled(true);
            ToastHelper.showToast(this, getString(R.string.cal_applied));
            sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_GRAPH).setPackage(Constants.PACKAGE_NAME));
        } else {
            ToastHelper.showToast(this, getString(R.string.cal_load_error));
        }
    }

    public void onClearCalibrationButton(View view) {
        AtomSpectraService.showCalibrationFunction = false;
        app_menu.findItem(R.id.action_cal_draw_function).setChecked(false);
        app_menu.findItem(R.id.action_cal_draw_function).setEnabled(false);
        AtomSpectraService.newCalibration.clear();
        updateCalibrationMenu();
    }

    @Override
    public void onBackPressed() {
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(ATOM_STATE_LOG, logScale);
        outState.putBoolean(ATOM_STATE_BAR, barMode);
        outState.putInt(Constants.SCALE_FACTOR, AtomSpectraService.getScaleFactor());
        outState.putFloat(ATOM_STATE_SCALE, zoom_factor);
        outState.putBoolean(ATOM_STATE_BACKGROUND_SUBTRACT, background_subtract);
        outState.putInt(ATOM_STATE_CURSOR_X, cursor_x);
        outState.putBoolean(ATOM_STATE_CURSOR_BUTTONS, showPlusMinusButtons);
    }

    private void requestDirectory(int dir_code) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, dir_code);
    }

    private void updateSelectedInputIndicator() {
        Button inputType = findViewById(R.id.inputTypeButton);
        if (AtomSpectraService.inputType == AtomSpectraService.INPUT_NONE) {
            inputType.setBackgroundResource(R.drawable.input_none);
        }
        if (AtomSpectraService.inputType == AtomSpectraService.INPUT_SERIAL) {
            inputType.setBackgroundResource(R.drawable.input_usb);
        }
        if (AtomSpectraService.inputType == AtomSpectraService.INPUT_AUDIO) {
            inputType.setBackgroundResource(R.drawable.input_mic);
        }
    }

    private void showSpectrogramView() {
        Intent intent_spectrogram = new Intent(this, AtomSpectraSpectrogram.class);
        startActivity(intent_spectrogram);
    }

    private void updateSpectrogramMenu() {
        if (app_menu != null) {
            app_menu.findItem(R.id.action_spectrogram_load).setEnabled(AtomSpectraService.getFreeze() && !isLoadingSpectrogram);
        }
    }

    private void updateVersionInMenu() {
        if (app_menu != null) {
            String testSuffix = "_USBDBG";
            AtomSpectraHelp.VersionInfo versionInfo = AtomSpectraHelp.getVersionInfo(this);
            app_menu.findItem(R.id.action_app_version).setTitle("Ver. " + versionInfo.version + "." + versionInfo.verCode + testSuffix);
        }
    }

    private void updateRecordStatusMenu() {
        if (app_menu != null) {
            if (AtomSpectraService.getFreeze()) {
                app_menu.findItem(R.id.action_hist_freeze).setIcon(R.drawable.record);
                app_menu.findItem(R.id.action_hist_freeze).setTitle(R.string.hist_continue_update);
            } else {
                app_menu.findItem(R.id.action_hist_freeze).setIcon(R.drawable.menu_block);
                app_menu.findItem(R.id.action_hist_freeze).setTitle(R.string.hist_freeze_update);
            }
        }
    }

    private void setXCalibrated(Boolean newXCalibrated) {
        XCalibrated = newXCalibrated;
        Button keVOrChannelButton = findViewById(R.id.keVOrChannelButton);
        CharSequence text = XCalibrated
                ? getText(R.string.mode_axis_kev_button)
                : getText(R.string.mode_axis_ch_button);
        keVOrChannelButton.setText(text);
    }

    private void setDisplayDose(String mode) {
        DisplayDose = mode;
        Button doseButton = (Button) findViewById(R.id.doseButton);
        switch (mode) {
            case Constants.DISPLAY_DOSE_NON_COMPENSATED:
            case Constants.DISPLAY_DOSE_COMPENSATED:
                doseButton.setText(getText(R.string.mode_combined_dose_button));
                break;
            case Constants.DISPLAY_DOSE_INTERVAL:
                doseButton.setText(getText(R.string.mode_interval_button));
                break;
            default:
                setDisplayDose(Constants.DISPLAY_DOSE_DEFAULT);
                break;
        }
    }

    private AlertDialog recordingSuspendedAlert = null;

    private void showRecordingSuspendedDialog() {
        String message;
        switch (AtomSpectraService.recordingSuspendReason) {
            case AtomSpectraService.RECORDING_SUSPEND_REASON_AUDIO_ADDED:
                message = getString(R.string.recording_suspended_dialog_audio_added);
                break;
            case AtomSpectraService.RECORDING_SUSPEND_REASON_AUDIO_REMOVED:
                message = getString(R.string.recording_suspended_dialog_audio_removed);
                break;
            case AtomSpectraService.RECORDING_SUSPEND_REASON_USB_DISCONNECT:
                message = getString(R.string.recording_suspended_dialog_usb_disconnected);
                break;
            default:
                message = "Unknown reason.";
                break;
        }
        message += "\n" + AtomSpectraService.formatLocalTimeAsISOLikeString(AtomSpectraService.recordingSuspendedAt);
        final AlertDialog.Builder alert = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.recording_suspended_dialog_title))
                .setMessage(message)
                .setPositiveButton(getString(R.string.recording_suspended_dialog_dismiss), (dialog, whichButton) -> {
                    sendBroadcast(new Intent(Constants.ACTION.ACTION_FREEZE_DATA).putExtra(AtomSpectraSerial.EXTRA_DATA_TYPE, true).setPackage(Constants.PACKAGE_NAME));
                    dismissRecordingSuspendedDialog();
                })
                .setCancelable(false);
        recordingSuspendedAlert = alert.show();
    }

    private void dismissRecordingSuspendedDialog() {
        if (recordingSuspendedAlert != null) {
            recordingSuspendedAlert.dismiss();
            recordingSuspendedAlert = null;
        }
    }

    private void checkRecordingSuspended() {
        if (AtomSpectraService.isStarted && AtomSpectraService.isRecordingSuspended && active && recordingSuspendedAlert == null) {
            showRecordingSuspendedDialog();
        }
    }

    private void setDisplayMode(int displayMode) {
        AtomSpectraService.setDisplayMode(displayMode);
        updateDisplayModeViews();
        sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_GRAPH).setPackage(Constants.PACKAGE_NAME));
    }

    private void updateDisplayModeViews() {
        Button keVOrChannelButton = findViewById(R.id.keVOrChannelButton);
        Button addCalPointButton = findViewById(R.id.addCalibrationPointButton);
        Button calibrateButton = findViewById(R.id.calibrateButton);
        Button removeCalibrationButton = findViewById(R.id.removeCalibrationButton);
        Button searchBaselineButton = findViewById(R.id.searchBaselineButton);
        Button fmsButton = findViewById(R.id.fmsButton);
        Button doseButton = findViewById(R.id.doseButton);
        TextView spectrumModeButton = findViewById(R.id.displayModeSpectrumButton);
        TextView spectrumChangeModeButton = findViewById(R.id.displayModeSpectrumChangeButton);
        TextView searchModeButton = findViewById(R.id.displayModeSearchButton);
        // TextView spectrogramModeButton = findViewById(R.id.displayModeSpectrogramButton);

        hideViews(keVOrChannelButton, addCalPointButton, calibrateButton, removeCalibrationButton, searchBaselineButton, fmsButton, doseButton);
        removeTextHighlight(spectrumModeButton, spectrumChangeModeButton, searchModeButton/*, spectrogramModeButton*/);

        switch (AtomSpectraService.getDisplayMode()) {
            case Constants.DISPLAY_MODE_SPECTRUM:
                showViews(keVOrChannelButton, addCalPointButton, calibrateButton, removeCalibrationButton);
                setHighlightedText(spectrumModeButton);
                break;
            case Constants.DISPLAY_MODE_SPECTRUM_CHANGE:
                showViews(keVOrChannelButton, addCalPointButton, calibrateButton, removeCalibrationButton);
                setHighlightedText(spectrumChangeModeButton);
                break;
            case Constants.DISPLAY_MODE_SEARCH:
                showViews(searchBaselineButton, fmsButton, doseButton);
                setHighlightedText(searchModeButton);
                break;
            case Constants.DISPLAY_MODE_SPECTROGRAM:
                // setUnderlineText(spectrogramModeButton);
                // break;
            default:
                AtomSpectraLog.addMessage(this, "ERROR: [AtomSpectraActivity] Unknown display mode: " + AtomSpectraService.getDisplayMode());
                setDisplayMode(Constants.DISPLAY_MODE_DEFAULT);
                break;
        }
    }

    private void hideViews(View ...views) {
        if (views != null) {
            for (View view : views) {
                view.setVisibility(View.INVISIBLE);
            }
        }
    }

    private void showViews(View ...views) {
        if (views != null) {
            for (View view : views) {
                view.setVisibility(View.VISIBLE);
            }
        }
    }

    private void removeTextHighlight(TextView ...views) {
        if (views != null) {
            for (TextView view : views) {
                view.setPaintFlags(view.getPaintFlags() & (~Paint.UNDERLINE_TEXT_FLAG) & (~Paint.FAKE_BOLD_TEXT_FLAG));
                view.setTextColor(Color.WHITE);
            }
        }
    }

    private void setHighlightedText(TextView ...views) {
        if (views != null) {
            for (TextView view : views) {
                view.setPaintFlags(view.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG | Paint.FAKE_BOLD_TEXT_FLAG);
                view.setTextColor(Color.CYAN);
            }
        }
    }

    private boolean isSpectrumDisplayMode() {
        int displayMode = AtomSpectraService.getDisplayMode();
        return displayMode == Constants.DISPLAY_MODE_SPECTRUM || displayMode == Constants.DISPLAY_MODE_SPECTRUM_CHANGE;
    }
}

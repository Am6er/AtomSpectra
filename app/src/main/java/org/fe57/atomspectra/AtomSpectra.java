package org.fe57.atomspectra;


import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.gesture.GestureOverlayView;
import android.gesture.GestureOverlayView.OnGestureListener;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
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
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.window.OnBackInvokedDispatcher;

import androidx.activity.ComponentActivity;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.util.Pair;
import androidx.documentfile.provider.DocumentFile;

import org.fe57.atomspectra.AppPermissions.Capability;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

public class AtomSpectra extends ComponentActivity implements OnGestureListener {

    private final static String TAG = AtomSpectra.class.getSimpleName();

    // foreground-activity guard for dialogs / spectrogram load (survives recreate)
    private static boolean active = false;

    // field initializer: the ActivityResult launcher must be registered before the activity is STARTED
    private final AppPermissions permissions = new AppPermissions(this, this::onPermissionsResult);
    private boolean receiverRegistered = false;
    private boolean serviceBound = false;
    private AtomSpectraService boundService = null;

    private GestureDetector gestureDetector = null;
    private ScaleGestureDetector gestureScaleDetector = null;

    // views
    private SeekBar seekChannel = null;
    private AtomSpectraShapeView mAtomSpectraShapeView = null;
    private TextView mCursorView = null;
    private LinearLayout mSuffixLayoutView = null;
    private TextView statusLine1Text, statusLine2Text, statusLine3Text, statusLine4Text;
    private TextView briefNotification;
    private Button fmsButton;


    // menu items
    private Menu app_menu = null;
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
    private final int ADD_HIST_CODE = 315;
    private final int LOAD_SPG_CODE = 316;
    private final int SELECT_INITIAL_WORKING_DIR_CODE = 317;

    // gestures
    private boolean isPinchMode = false;
    private boolean isPinchModeFinished = false;

    private Uri pendingOpenFileUri = null;

    private SharedPreferences sharedPreferences = null;

    private final int[] searchFMSNextMode = {1, 2, 0};
    private final String[] searchFMSNames = {"F", "M", "S"};

    /**
     * Run {@code action} if storage is available, ask for permission if necessary
     */
    private void withStorage(Runnable action, String deniedMessage) {
        if (AppPermissions.isStorageAllowed(this)) {
            action.run();
        } else {
            permissions.ensure(Capability.STORAGE, action,
                    () -> ToastHelper.showToastAndLog(this, deniedMessage));
        }
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        String lang = PrefHelper.getLocale(newBase);
        super.attachBaseContext(LocaleContextWrapper.wrap(newBase, lang));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        sharedPreferences = PrefHelper.getASSharedPreferences(this);
        sharedPreferences.registerOnSharedPreferenceChangeListener(viewPreferenceListener);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, () -> {
            });
        }
        super.onCreate(savedInstanceState);
        Thread.setDefaultUncaughtExceptionHandler(new TopExceptionHandler(this));
        setContentView(R.layout.activity_atom_spectra);

        bindViews();
        setupSeekChannel();
        initializeGestures();
        setupSearchModeButtons();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        maybeShowPowerAdvice();
        applyInitialViewState();
        stashPendingOpenFile();
        registerDataUpdateReceiver();
        updateDestinationDirectory();
        requestStartupPermissions();
    }

    /**
     * Wire findViewById results and set initial visibility / labels.
     */
    private void bindViews() {
        mCursorView = findViewById(R.id.cursorView);
        mCursorView.setVisibility(TextView.INVISIBLE);
        mSuffixLayoutView = findViewById(R.id.suffixLayout);
        mSuffixLayoutView.setVisibility(LinearLayout.INVISIBLE);
        seekChannel = findViewById(R.id.seekChannel);
        ((TextView) findViewById(R.id.backgroundSuffixView)).setText(SpectrumData.instance.background.getSuffix());
        ((TextView) findViewById(R.id.suffixView)).setText(SpectrumData.instance.foreground.getSuffix());
        AtomSpectraIsotopes.autoUpdateIsotopes = sharedPreferences.getBoolean(Constants.CONFIG.CONF_AUTO_UPDATE_ISOTOPES, false);

        if (UIViewState.instance.showPlusMinusButtons) {
            seekChannel.setVisibility(SeekBar.VISIBLE);
            dateChannelChanged = System.currentTimeMillis();
        }

        fmsButton = findViewById(R.id.fmsButton);
        statusLine1Text = findViewById(R.id.statusLine1Text);
        statusLine2Text = findViewById(R.id.statusLine2Text);
        statusLine3Text = findViewById(R.id.statusLine3Text);
        statusLine4Text = findViewById(R.id.statusLine4Text);
        briefNotification = findViewById(R.id.briefNotification);
        mAtomSpectraShapeView = findViewById(R.id.shape_area);
    }

    /**
     * Configure the channel seek bar and start its auto-hide timer.
     */
    private void setupSeekChannel() {
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
                        int shift_cursor = init_cursor + (1 << StrictMath.max(0, (Constants.SCALE_MAX - UIViewState.instance.getxScaleFactor() - 1))) * progress;
                        UIViewState.instance.cursorX = Constants.MinMax(shift_cursor, 0, SpectrumData.instance.getChannelCount() - 1);
                        showCursorInfo(true);
                        dateChannelChanged = System.currentTimeMillis();
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                    init_cursor = UIViewState.instance.cursorX;
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
                        int shift_cursor = init_cursor + (1 << StrictMath.max(0, (Constants.SCALE_MAX - UIViewState.instance.getxScaleFactor() - 1))) * (progress - 50 * scale);
                        UIViewState.instance.cursorX = Constants.MinMax(shift_cursor, 0, SpectrumData.instance.getChannelCount() - 1);
                        showCursorInfo(true);
                        dateChannelChanged = new Date().getTime();
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                    init_cursor = UIViewState.instance.cursorX;
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    seekBar.setProgress(0);
                }
            });
        }
        seekChannelHideTimer.schedule(seekChannelHideTask, 0, 1000);
    }

    /**
     * Initialize FMS labels and search-mode button enablement.
     */
    private void setupSearchModeButtons() {
        searchFMSNames[0] = getString(R.string.mode_fast_button);
        searchFMSNames[1] = getString(R.string.mode_medium_button);
        searchFMSNames[2] = getString(R.string.mode_slow_button);
        fmsButton.setText(searchFMSNames[sharedPreferences.getInt(Constants.CONFIG.CONF_SEARCH_MODE, 0)]);
        updateSearchBaselineButton();
    }

    /**
     * Enable the search baseline button when sound output is available (API 23+).
     */
    private void updateSearchBaselineButton() {
        Button searchBaselineButton = findViewById(R.id.searchBaselineButton);
        searchBaselineButton.setEnabled((Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                && sharedPreferences.getBoolean(Constants.CONFIG.CONF_OUTPUT_SOUND, false));
    }

    /**
     * One-shot first-launch tip about power / battery.
     */
    @SuppressLint("ApplySharedPref")
    private void maybeShowPowerAdvice() {
        if (!sharedPreferences.getBoolean(Constants.CONFIG.CONF_CHECK_POWER, true)) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.perm_ask_power_title))
                .setMessage(getString(R.string.perm_ask_power_text))
                .setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                })
                .show();
        sharedPreferences.edit().putBoolean(Constants.CONFIG.CONF_CHECK_POWER, false).commit();
    }

    /**
     * Load display prefs into UIViewState and apply them to the main views.
     */
    private void applyInitialViewState() {
        UIViewState.instance.loadFromPreferences(sharedPreferences, getResources());
        setXCalibrated(UIViewState.instance.energyAxis);
        applyDisplayDoseButton(UIViewState.instance.displayDose);
        updateDisplayModeViews();
        showCursorInfo(false);
    }

    /**
     * Cold-start share / "Open with": stash URI until the service binds.
     */
    private void stashPendingOpenFile() {
        Uri openFile = spectrumUriFromIntent(getIntent());
        if (openFile != null) {
            pendingOpenFileUri = openFile;
            clearActivityIntent();
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerDataUpdateReceiver() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mDataUpdateReceiver, makeAtomSpectraUpdateIntentFilter(), Context.RECEIVER_NOT_EXPORTED);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(mDataUpdateReceiver, makeAtomSpectraUpdateIntentFilter(), 0);
        } else {
            registerReceiver(mDataUpdateReceiver, makeAtomSpectraUpdateIntentFilter());
        }
        receiverRegistered = true;
    }

    /**
     * Requests mic, notifications and files, plus location when GPS tagging is enabled (on by
     * default). On the very first launch a dialog explains why each is needed first; afterwards the
     * request is issued directly, so granted permissions cause no prompt.
     */
    private void requestStartupPermissions() {
        List<Capability> caps = new ArrayList<>();
        caps.add(Capability.MIC);
        caps.add(Capability.NOTIFICATIONS);
        caps.add(Capability.STORAGE);
        if (sharedPreferences.getBoolean(Constants.CONFIG.CONF_ADD_GPS_TO_FILES, Constants.ADD_GPS_TO_FILES_DEFAULT)) {
            caps.add(Capability.LOCATION);
        }
        final Capability[] startup = caps.toArray(new Capability[0]);
        boolean firstRun = !sharedPreferences.getBoolean(Constants.CONFIG.CONF_PERMISSIONS_REQUESTED, false);
        if (firstRun && AppPermissions.requestablePermissions(this, startup).length > 0) {
            sharedPreferences.edit().putBoolean(Constants.CONFIG.CONF_PERMISSIONS_REQUESTED, true).apply();
            new AlertDialog.Builder(this)
                    .setTitle(R.string.perm_startup_title)
                    .setMessage(R.string.perm_startup_text)
                    .setCancelable(false)
                    .setPositiveButton(android.R.string.ok, (dialog, whichButton) -> permissions.request(startup))
                    .show();
        } else {
            permissions.request(startup);
        }
    }

    private void onPermissionsResult(Map<Capability, Boolean> granted) {
        // if we asked for location (GPS tagging is on) and it was declined, turn the setting off so
        // we stop re-asking on every launch; the user re-enables it from Settings once granted
        if (granted.containsKey(Capability.LOCATION) && !Boolean.TRUE.equals(granted.get(Capability.LOCATION))) {
            sharedPreferences.edit().putBoolean(Constants.CONFIG.CONF_ADD_GPS_TO_FILES, false).apply();
        }

        startInputService();
        updateSelectedInputIndicator();
        ensureWorkingDirectory();
    }

    /**
     * On API 26+ (SAF) prompt for a working folder if none has been chosen yet.
     */
    private void ensureWorkingDirectory() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && AppPermissions.isStorageAllowed(this)
                && PrefHelper.getWorkingDir(this, false) == null) {
            // Explain why the system folder picker is about to appear.
            new AlertDialog.Builder(this)
                    .setTitle(R.string.working_dir_title)
                    .setMessage(R.string.working_dir_rationale)
                    .setCancelable(false)
                    .setPositiveButton(android.R.string.ok,
                            (dialog, whichButton) -> requestDirectory(SELECT_INITIAL_WORKING_DIR_CODE))
                    .show();
        }
    }

    /**
     * Starts and binds the foreground service, passing the computed FGS type. No-op if already started.
     */
    private void startInputService() {
        if (serviceBound) {
            return;
        }
        Intent intent = new Intent(this, AtomSpectraService.class);
        intent.setAction(Constants.ACTION.ACTION_START_FOREGROUND);
        intent.putExtra(Constants.ACTION_PARAMETERS.FGS_TYPE, AppPermissions.foregroundServiceType(this));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getApplicationContext().startForegroundService(intent);
        } else {
            getApplicationContext().startService(intent);
        }
        getApplicationContext().bindService(intent, mServiceConnection, BIND_IMPORTANT);
        serviceBound = true;
    }

    /**
     * Returns a spectrum file URI from SEND/VIEW text/plain, or null.
     */
    private static Uri spectrumUriFromIntent(Intent intent) {
        if (intent == null || intent.getType() == null || !"text/plain".equals(intent.getType())) {
            return null;
        }
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
            }
            return intent.getParcelableExtra(Intent.EXTRA_STREAM);
        }
        if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            return intent.getData();
        }
        return null;
    }

    /**
     * Drop a consumed SEND/VIEW so recreate does not reopen the same file via getIntent().
     */
    private void clearActivityIntent() {
        setIntent(new Intent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        // a USB_DEVICE_ATTACHED intent only grants USB permission for the device; the locked source
        // reconnects itself and a cold launch goes through the selection screen
        if (intent != null) {
            setIntent(intent);
            Uri file = spectrumUriFromIntent(intent);
            if (file != null) {
                loadSpectrum(file, true);
                clearActivityIntent();
            }
        }
        super.onNewIntent(intent);
    }

    @Override
    public void onStart() {
        super.onStart();
        Log.d(TAG, "-XxX-  onStart");
        // ToastHelper.showToast(this, "On start");
        active = true;
        maybeOpenDeviceSelection();
        syncConnectDecisionDialog();
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
                ToastHelper.showToastAndLog(this, getString(R.string.storage_required));
            }
            prefEditor.apply();
        }
    }

    private void updateCalibrationMenu() {
        if (app_menu != null) {
            double[] coeffs = SpectrumData.instance.foreground.getSpectrumCalibration().getCoeffArray(5);
            app_menu.findItem(R.id.action_cal_point_1).setTitle(String.format(Locale.getDefault(), "c0: %.12g", coeffs[0]));
            app_menu.findItem(R.id.action_cal_point_2).setTitle(String.format(Locale.getDefault(), "c1: %.12g", coeffs[1]));
            app_menu.findItem(R.id.action_cal_point_3).setTitle(String.format(Locale.getDefault(), "c2: %.12g", coeffs[2]));
            app_menu.findItem(R.id.action_cal_point_4).setTitle(String.format(Locale.getDefault(), "c3: %.12g", coeffs[3]));
            app_menu.findItem(R.id.action_cal_point_5).setTitle(String.format(Locale.getDefault(), "c4: %.12g", coeffs[4]));

            Button button = findViewById(R.id.addCalibrationPointButton);
            if (SpectrumData.instance.newCalibration.getPointsCount() >= Constants.MAX_CALIBRATION_POINTS) {
                button.setText("-");
                button.setEnabled(false);
                app_menu.findItem(R.id.action_cal_add_point).setEnabled(false);
            } else {
                button.setText(String.format(Locale.US, "%d", SpectrumData.instance.newCalibration.getPointsCount() + 1));
                app_menu.findItem(R.id.action_cal_add_point).setEnabled(true);
            }
            app_menu.findItem(R.id.action_cal_calc).setEnabled(SpectrumData.instance.newCalibration.getPointsCount() > 1);
            findViewById(R.id.calibrateButton).setEnabled(SpectrumData.instance.newCalibration.getPointsCount() > 1);
            if (SpectrumData.instance.newCalibration.getPointsCount() < 2) {
                app_menu.findItem(R.id.action_cal_draw_function).setChecked(false).setEnabled(false);
                UIViewState.instance.showCalibrationFunction = false;
            }
            app_menu.findItem(R.id.action_cal_new_point1).
                    setTitle(getString(R.string.cal_show_line, SpectrumData.instance.newCalibration.getPointChannel(0), SpectrumData.instance.newCalibration.getPointEnergy(0))).
                    setVisible(SpectrumData.instance.newCalibration.getPointsCount() > 0);
            app_menu.findItem(R.id.action_cal_new_point2).
                    setTitle(getString(R.string.cal_show_line, SpectrumData.instance.newCalibration.getPointChannel(1), SpectrumData.instance.newCalibration.getPointEnergy(1))).
                    setVisible(SpectrumData.instance.newCalibration.getPointsCount() > 1);
            app_menu.findItem(R.id.action_cal_new_point3).
                    setTitle(getString(R.string.cal_show_line, SpectrumData.instance.newCalibration.getPointChannel(2), SpectrumData.instance.newCalibration.getPointEnergy(2))).
                    setVisible(SpectrumData.instance.newCalibration.getPointsCount() > 2);
            app_menu.findItem(R.id.action_cal_new_point4).
                    setTitle(getString(R.string.cal_show_line, SpectrumData.instance.newCalibration.getPointChannel(3), SpectrumData.instance.newCalibration.getPointEnergy(3))).
                    setVisible(SpectrumData.instance.newCalibration.getPointsCount() > 3);
            app_menu.findItem(R.id.action_cal_new_point5).
                    setTitle(getString(R.string.cal_show_line, SpectrumData.instance.newCalibration.getPointChannel(4), SpectrumData.instance.newCalibration.getPointEnergy(4))).
                    setVisible(SpectrumData.instance.newCalibration.getPointsCount() > 4);
            app_menu.findItem(R.id.action_cal_new_point6).
                    setTitle(getString(R.string.cal_show_line, SpectrumData.instance.newCalibration.getPointChannel(5), SpectrumData.instance.newCalibration.getPointEnergy(5))).
                    setVisible(SpectrumData.instance.newCalibration.getPointsCount() > 5);
            app_menu.findItem(R.id.action_cal_new_point7).
                    setTitle(getString(R.string.cal_show_line, SpectrumData.instance.newCalibration.getPointChannel(6), SpectrumData.instance.newCalibration.getPointEnergy(6))).
                    setVisible(SpectrumData.instance.newCalibration.getPointsCount() > 6);
            app_menu.findItem(R.id.action_cal_new_point8).
                    setTitle(getString(R.string.cal_show_line, SpectrumData.instance.newCalibration.getPointChannel(7), SpectrumData.instance.newCalibration.getPointEnergy(7))).
                    setVisible(SpectrumData.instance.newCalibration.getPointsCount() > 7);
            app_menu.findItem(R.id.action_cal_new_point9).
                    setTitle(getString(R.string.cal_show_line, SpectrumData.instance.newCalibration.getPointChannel(8), SpectrumData.instance.newCalibration.getPointEnergy(8))).
                    setVisible(SpectrumData.instance.newCalibration.getPointsCount() > 8);
            app_menu.findItem(R.id.action_cal_new_point10).
                    setTitle(getString(R.string.cal_show_line, SpectrumData.instance.newCalibration.getPointChannel(9), SpectrumData.instance.newCalibration.getPointEnergy(9))).
                    setVisible(SpectrumData.instance.newCalibration.getPointsCount() > 9);

            // every device persists its own calibration
            boolean deviceConnected = AtomSpectraService.isDeviceConnected();
            app_menu.findItem(R.id.action_cal_store_device).setEnabled(deviceConnected);
            app_menu.findItem(R.id.action_cal_retrieve_device).setEnabled(deviceConnected);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.atom_spectra, menu);
        app_menu = menu;
        updateCountDependentMenu();
        menu.findItem(R.id.action_cal_draw_function).setChecked(UIViewState.instance.showCalibrationFunction);
        menu.findItem(R.id.action_cal_draw_function).setEnabled(SpectrumData.instance.newCalibration != null && SpectrumData.instance.newCalibration.getPointsCount() > 1);

        menu.findItem(R.id.action_hist_smooth).setTitle(UIViewState.instance.smooth ? getString(R.string.hist_unsmooth) : getString(R.string.hist_smooth));
        updateCalibrationMenu();

        updateRecordStatusMenu();
        updateVersionInMenu();
        updateSpectrogramMenu();
        updateMapMenu();

        return true;
    }


    // the menu state that follows the background and the channel count: "Last: N" title, background items and suffix
    private void updateCountDependentMenu() {
        if (app_menu == null) return;
        final boolean enable_background = !SpectrumData.instance.background.isEmpty();
        app_menu.findItem(R.id.action_cal_channel).setTitle(getString(R.string.calibration_channel_format, SpectrumData.instance.lastCalibrationChannel));
        app_menu.findItem(R.id.action_background_show).setChecked(UIViewState.instance.backgroundShow);
        app_menu.findItem(R.id.action_background_show).setEnabled(enable_background);
        app_menu.findItem(R.id.action_background_suffix).setEnabled(enable_background);
        app_menu.findItem(R.id.action_background_subtract).setEnabled(UIViewState.instance.backgroundShow);
        app_menu.findItem(R.id.action_background_subtract).setChecked(UIViewState.instance.backgroundSubtract);
        app_menu.findItem(R.id.action_background_clear).setEnabled(enable_background);
        app_menu.findItem(R.id.action_background_save).setEnabled(enable_background);
        TextView view = findViewById(R.id.backgroundSuffixView);
        if (view != null) {
            view.setText(SpectrumData.instance.background.getSuffix());
            view.setVisibility(enable_background ? TextView.VISIBLE : TextView.INVISIBLE);
        }
    }

    private static IntentFilter makeAtomSpectraUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Constants.ACTION.ACTION_DATA_AVAILABLE);
        intentFilter.addAction(AtomSpectraService.ACTION_RECORDING_SUSPENDED);
        intentFilter.addAction(AtomSpectraService.ACTION_RECORDING_RESUMED);
        intentFilter.addAction(Constants.ACTION.ACTION_UPDATE_GPS);
        intentFilter.addAction(Constants.ACTION.ACTION_CLOSE_APP);
        intentFilter.addAction(Constants.ACTION.ACTION_UPDATE_MENU);
        intentFilter.addAction(Constants.ACTION.ACTION_DEVICE_SELECTION_REQUIRED);
        intentFilter.addAction(Constants.ACTION.ACTION_DEVICE_CONNECT_DECISION);
        intentFilter.addAction(Constants.ACTION.ACTION_SPECTROGRAM_UPDATED);
        intentFilter.addAction(AtomSpectraService.ACTION_HISTOGRAM_SKIPPED);
        return intentFilter;
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
                checkRecordingSuspended();
            }

            if (AtomSpectraService.ACTION_HISTOGRAM_SKIPPED.equals(action)) {
                int skipped = intent.getIntExtra(AtomSpectraService.EXTRA_DATA_INT_HISTOGRAM_SKIPPED_COUNT, 0);
                if (skipped > 0) {
                    showBriefNotification(
                            getString(R.string.histogram_updates_skipped, skipped),
                            0xFFFFA500,
                            1500);
                }
            }

            if (Constants.ACTION.ACTION_DATA_AVAILABLE.equals(action)) {
                refreshDisplay();
            }
            if (Constants.ACTION.ACTION_CLOSE_APP.equals(action)) {
                finishAndRemoveTask();
            }
            if (Constants.ACTION.ACTION_UPDATE_GPS.equals(action)
                    || Constants.ACTION.ACTION_SPECTROGRAM_UPDATED.equals(action)) {
                updateMapMenu();
            }
            // TODO: a bit odd action, it's better to react to a particular reason, like service status changed etc.
            if (Constants.ACTION.ACTION_DEVICE_CONNECT_DECISION.equals(action)) {
                syncConnectDecisionDialog();
            }
            if (Constants.ACTION.ACTION_DEVICE_SELECTION_REQUIRED.equals(action)) {
                maybeOpenDeviceSelection();
            }
            if (Constants.ACTION.ACTION_UPDATE_MENU.equals(action)) {
                checkRecordingSuspended();
                syncConnectDecisionDialog();
                updateRecordStatusMenu();
                updateSelectedInputIndicator();
                updateCalibrationMenu();
                updateCountDependentMenu();
                updateSpectrogramMenu();
                updateMapMenu();

                updateSearchBaselineButton();
                // TODO: create method for suffix text
                TextView view = findViewById(R.id.suffixView);
                if (view != null) {
                    view.setText(SpectrumData.instance.foreground.getSuffix());
                }
            }
        }

    };

    private final SharedPreferences.OnSharedPreferenceChangeListener viewPreferenceListener =
            (prefs, key) -> UIViewState.instance.loadFromPreferences(prefs, getResources());

    private double[] shownCalibrationCoeffs = null;
    private int shownCalibrationChannel = -1;

    /**
     * Update the text fields and redraw the view from the shared data and the current view state.
     */
    private void refreshDisplay() {
        if (mAtomSpectraShapeView == null) {
            return;
        }

        int display_mode = UIViewState.instance.displayMode;
        int cp1s = MeasurementData.instance.cp1s;
        int cp1s_interval = MeasurementData.instance.cp1sInterval;

        switch (display_mode) {
            case Constants.DISPLAY_MODE_SPECTRUM:
                Spectrum foreground = SpectrumData.instance.foreground;
                double total_time = foreground.getSpectrumTime();
                long total_counts = foreground.getTotalCounts();

                statusLine2Text.setText(getString(R.string.cps_show, cp1s, cp1s_interval));
                statusLine3Text.setText(getString(R.string.cps_average_show, total_time > 1 ? total_counts / total_time : 0));
                statusLine4Text.setText(getString(R.string.total_time_format, formatSpectrumTime(total_time)));
                statusLine2Text.setTextColor(Color.WHITE);
                statusLine3Text.setTextColor(Color.WHITE);
                statusLine4Text.setTextColor(Color.WHITE);
                break;
            case Constants.DISPLAY_MODE_SPECTRUM_CHANGE:
                SpectrumChangeData.Snapshot change = SpectrumChangeData.instance.get();
                double delta_time = change.foregroundTimeSeconds;
                double delta_back_time = change.backgroundTimeSeconds;

                double delta_cps = delta_time > 0 ? change.foregroundTotalCounts / (double) delta_time : 0.0;
                double delta_back_cps = delta_back_time > 0 ? change.backgroundTotalCounts / (double) delta_back_time : 0.0;

                statusLine2Text.setText(getString(R.string.cps_show, cp1s, cp1s_interval));
                statusLine3Text.setText(getString(R.string.cps_delta_show, delta_cps, delta_back_cps));
                statusLine4Text.setText(getString(R.string.delta_time_format, delta_time, delta_back_time));
                statusLine2Text.setTextColor(Color.WHITE);
                statusLine3Text.setTextColor(Color.WHITE);
                statusLine4Text.setTextColor(Color.WHITE);
                break;
            case Constants.DISPLAY_MODE_SEARCH:
                MeasurementData.DoseRate doseRate = MeasurementData.instance.doseRate;
                statusLine2Text.setText(getString(R.string.cps_show, cp1s, cp1s_interval));
                statusLine2Text.setTextColor(Color.WHITE);

                long error95Percent = 0;
                boolean isAlarmMode = AtomSpectraService.intervalSearchAlarmEnabled;
                AtomSpectraService.AlarmBaseline baseline = AtomSpectraService.getIntervalSearchAlarmBaseline();
                boolean isIntervalMode = Constants.DISPLAY_DOSE_INTERVAL.equals(UIViewState.instance.displayDose);
                if (isAlarmMode && isIntervalMode) {
                    error95Percent = Math.round(2 * baseline.getBaselineError());
                    if (baseline.isStable()) {
                        statusLine4Text.setText(getString(R.string.cps_alarm_levels, baseline.getAlarmLevelHigh(), baseline.getAlarmLevelLow()));
                        statusLine4Text.setTextColor(AtomSpectraShapeView.COLOR_ALARM_CPS);
                    } else {
                        statusLine4Text.setText(getString(R.string.cps_alarm_baseline_timer, baseline.getRemainingTime(), baseline.getBaseline(), error95Percent));
                        statusLine4Text.setTextColor(AtomSpectraShapeView.COLOR_BASELINE_CPS);
                    }
                }

                if (isIntervalMode) {
                    error95Percent = Math.round(doseRate.intervalCpsErrorPercent * 2);

                    statusLine3Text.setText(getString(R.string.dose_rate_interval_prefix, formatCpsWithError(doseRate.intervalCps, error95Percent, doseRate.intervalCpsTimeSeconds)));
                    statusLine3Text.setTextColor(AtomSpectraShapeView.COLOR_INTERVAL_CPS);
                    if (!isAlarmMode) {
                        statusLine4Text.setText(R.string.interval_search_sound_disabled_label);
                        statusLine4Text.setTextColor(AtomSpectraShapeView.COLOR_ALARM_CPS);
                    }
                } else {
                    long error95PercentC = Math.round(doseRate.compensatedErrorPercent * 2);
                    long error95PercentN = Math.round(doseRate.nonCompensatedErrorPercent * 2);

                    statusLine3Text.setText(getString(R.string.dose_rate_noncompensated_prefix, formatDoseRateWithError(doseRate.nonCompensated, error95PercentN, doseRate.nonCompensatedTimeSeconds)));
                    statusLine3Text.setTextColor(AtomSpectraShapeView.COLOR_NON_COMPENSATED_DOSE);
                    statusLine4Text.setText(getString(R.string.dose_rate_compensated_prefix, formatDoseRateWithError(doseRate.compensated, error95PercentC, doseRate.compensatedTimeSeconds)));
                    statusLine4Text.setTextColor(AtomSpectraShapeView.COLOR_COMPENSATED_DOSE);
                }
                break;
            case Constants.DISPLAY_MODE_SPECTROGRAM:
            default:
                // nothing to do so far
                break;
        }

        refreshCalibrationMenu();
        showCursorInfo(false);

        switch (display_mode) {
            case Constants.DISPLAY_MODE_SPECTRUM:
                if (UIViewState.instance.showCalibrationFunction) {
                    mAtomSpectraShapeView.showCalibration(UIViewState.instance, SpectrumData.instance);
                } else {
                    mAtomSpectraShapeView.showSpectrum(UIViewState.instance, SpectrumData.instance, EnergyIntervalData.instance);
                }
                break;
            case Constants.DISPLAY_MODE_SPECTRUM_CHANGE:
                mAtomSpectraShapeView.showSpectrumChange(UIViewState.instance, SpectrumData.instance, SpectrumChangeData.instance, EnergyIntervalData.instance);
                break;
            case Constants.DISPLAY_MODE_SEARCH:
                switch (UIViewState.instance.displayDose) {
                    case Constants.DISPLAY_DOSE_INTERVAL:
                        mAtomSpectraShapeView.showIntervalSearch(UIViewState.instance, MeasurementData.instance, sharedPreferences.getBoolean(Constants.CONFIG.CONF_OUTPUT_SOUND, false));
                        break;
                    case Constants.DISPLAY_DOSE_COMPENSATED:
                    case Constants.DISPLAY_DOSE_NON_COMPENSATED:
                        mAtomSpectraShapeView.showDoseSearch(UIViewState.instance, MeasurementData.instance);
                        break;
                }
                break;
            case Constants.DISPLAY_MODE_SPECTROGRAM:
            default:
                // nothing to do so far
                break;
        }
    }

    // TODO: refreshCalibrationMenu and updateCalibrationMenu look very similar

    /**
     * The calibration menu items only change with the calibration or the last calibrated channel.
     */
    private void refreshCalibrationMenu() {
        if (app_menu == null) {
            return;
        }

        double[] coeffs = SpectrumData.instance.foreground.getSpectrumCalibration().getCoeffArray(5);
        int lastChannel = SpectrumData.instance.lastCalibrationChannel;
        if (lastChannel == shownCalibrationChannel && Arrays.equals(coeffs, shownCalibrationCoeffs)) {
            return;
        }

        shownCalibrationCoeffs = coeffs;
        shownCalibrationChannel = lastChannel;
        app_menu.findItem(R.id.action_cal_channel).setTitle(getString(R.string.calibration_channel_format, lastChannel));
        updateCalibrationMenu();
    }

    private void showBriefNotification(String text, int color, long durationMs) {
        briefNotification.removeCallbacks(briefNotificationHideRunnable);
        briefNotification.setText(text);
        briefNotification.setTextColor(color);
        briefNotification.setVisibility(View.VISIBLE);
        briefNotification.postDelayed(briefNotificationHideRunnable, durationMs);
    }

    private final Runnable briefNotificationHideRunnable =
            () -> briefNotification.setVisibility(View.GONE);

    private String formatDoseRateWithError(double dose_rate, long error95Percent, double measurementTimeSeconds) {
        String formatted;
        if (dose_rate < 10) {
            formatted = getString(R.string.dose_rate_1uSv_format, dose_rate, error95Percent);
        } else if (dose_rate < 100) {
            formatted = getString(R.string.dose_rate_10uSv_format, dose_rate, error95Percent);
        } else if (dose_rate < 1000) {
            formatted = getString(R.string.dose_rate_100uSv_format, dose_rate, error95Percent);
        } else if (dose_rate < 10000) {
            formatted = getString(R.string.dose_rate_1mSv_format, dose_rate / 1000.0, error95Percent);
        } else if (dose_rate < 100000) {
            formatted = getString(R.string.dose_rate_10mSv_format, dose_rate / 1000.0, error95Percent);
        } else { // > 100 mSv/h
            formatted = getString(R.string.dose_rate_100mSv_format, dose_rate / 1000.0, error95Percent);
        }
        return formatted + formatMeasurementTime(measurementTimeSeconds);
    }

    private String formatCpsWithError(double search_int_cps, long error95Percent, double measurementTimeSeconds) {
        String formatted;
        if (search_int_cps < 10) {
            formatted = getString(R.string.dose_rate_1cps_format, search_int_cps, error95Percent);
        } else if (search_int_cps < 100) {
            formatted = getString(R.string.dose_rate_10cps_format, search_int_cps, error95Percent);
        } else if (search_int_cps < 1000) {
            formatted = getString(R.string.dose_rate_100cps_format, search_int_cps, error95Percent);
        } else if (search_int_cps < 10000) {
            formatted = getString(R.string.dose_rate_1kcps_format, search_int_cps / 1000.0, error95Percent);
        } else if (search_int_cps < 100000) {
            formatted = getString(R.string.dose_rate_10kcps_format, search_int_cps / 1000.0, error95Percent);
        } else { // > 100k cps
            formatted = getString(R.string.dose_rate_100kcps_format, search_int_cps / 1000.0, error95Percent);
        }
        return formatted + formatMeasurementTime(measurementTimeSeconds);
    }

    private String formatMeasurementTime(double measurementTimeSeconds) {
        if (measurementTimeSeconds >= 1) {
            return getString(R.string.dose_rate_measurement_time_format, (int) Math.round(measurementTimeSeconds));
        }
        return getString(R.string.dose_rate_measurement_time_format_fractional, measurementTimeSeconds);
    }

    private String formatSpectrumTime(double timeSeconds) {
        long[] parts = Spectrum.splitAcquisitionTime(timeSeconds);
        if (parts[0] > 0) {
            return getString(R.string.spectrum_time_days_hms_format, parts[0], parts[1], parts[2], parts[3]);
        }
        return getString(R.string.spectrum_time_hms_format, parts[1], parts[2], parts[3]);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // ToastHelper.showToast(this, "On resume");

        UIViewState.instance.loadFromPreferences(sharedPreferences, getResources());
        setXCalibrated(UIViewState.instance.energyAxis);
        applyDisplayDoseButton(UIViewState.instance.displayDose);
        updateDisplayModeViews();
        checkSpectrogramIsLoading();
        checkRecordingSuspended();

        refreshDisplay();
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
        seekChannelHideTimer.cancel();
        if (connectDecisionDialog != null) {
            connectDecisionDialog.dismiss();
            connectDecisionDialog = null;
        }
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(viewPreferenceListener);
        if (receiverRegistered) {
            unregisterReceiver(mDataUpdateReceiver);
            receiverRegistered = false;
        }

        if (serviceBound) {
            getApplicationContext().unbindService(mServiceConnection);
            serviceBound = false;
            boundService = null;
        }
        UIViewState.instance.backgroundSubtract = false;
        if (app_menu != null) {
            app_menu.findItem(R.id.action_background_show).setChecked(false);
            app_menu.findItem(R.id.action_background_subtract).setChecked(false);
            app_menu.findItem(R.id.action_background_save).setEnabled(false);
            app_menu.findItem(R.id.action_background_suffix).setEnabled(false);
        }
        UIViewState.instance.cursorX = -1;
        mCursorView.setVisibility(View.INVISIBLE);
        mSuffixLayoutView.setVisibility(LinearLayout.INVISIBLE);
        mCursorView = null;
        mSuffixLayoutView = null;
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
            boundService = ((AtomSpectraService.LocalBinder) service).getService();
            if (pendingOpenFileUri != null) {
                loadSpectrum(pendingOpenFileUri, true);
                pendingOpenFileUri = null;
            }
            maybeOpenDeviceSelection();
            syncConnectDecisionDialog();
            updateSelectedInputIndicator();
            updateRecordStatusMenu();
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            boundService = null;
        }
    };

    private void openDeviceSelection(boolean startRecordingAfter) {
        startActivity(new Intent(this, AtomSpectraDeviceSelect.class)
                .putExtra(AtomSpectraDeviceSelect.EXTRA_START_RECORDING_AFTER, startRecordingAfter));
    }

    // nothing is chosen (first launch, or the chosen device cannot be used): the selection screen opens, singleTop keeps it unique
    private void maybeOpenDeviceSelection() {
        if (boundService == null || !active) return;
        if (AtomSpectraService.sessionState == AtomSpectraService.DeviceSessionState.UNSELECTED) {
            openDeviceSelection(false);
        }
    }

    // "switch device" is available unless a chosen device is still being connected
    private boolean canSwitchDevice() {
        return boundService != null && !AtomSpectraService.isSelectionPending();
    }

    public void onClickShape(View v) {
    }

    public void onClickDeleteSpc(View v) {
        DialogHelper.showStackedActions(this,
            getString(R.string.hist_ask_delete_title),
            getString(R.string.hist_ask_delete_text),
            true,
            new DialogHelper.StackedAction(getString(android.R.string.ok), () -> {
                    AtomSpectraIsotopes.showFoundIsotopes = false;
                    AtomSpectraIsotopes.foundList.clear();
                    sendBroadcast(new Intent(Constants.ACTION.ACTION_CLEAR_SPECTRUM).setPackage(Constants.PACKAGE_NAME));
                    ((TextView) findViewById(R.id.suffixView)).setText(getString(R.string.hist_suffix));
            }),
            new DialogHelper.StackedAction(getString(android.R.string.cancel), null));
    }

    public void onClick_renderModeSpectrum(View v) {
        setDisplayMode(Constants.DISPLAY_MODE_SPECTRUM);
    }

    public void onClick_renderModeSpectrumChange(View v) {
        setDisplayMode(Constants.DISPLAY_MODE_SPECTRUM_CHANGE);
    }

    public void onClick_renderModeSearch(View v) {
        hideSeekChannel();
        setDisplayMode(Constants.DISPLAY_MODE_SEARCH);
    }

    public void onClick_renderModeSpectrogram(View v) {
        showSpectrogramView();
    }

    public void onClick_xAxisScale(View v) {
        UIViewState.instance.setEnergyAxis(!UIViewState.instance.energyAxis, sharedPreferences);
        setXCalibrated(UIViewState.instance.energyAxis);
        refreshDisplay();
    }

    @SuppressLint("ApplySharedPref")
    public void onClick_fms(View v) {
        int nextMode = searchFMSNextMode[sharedPreferences.getInt(Constants.CONFIG.CONF_SEARCH_MODE, 0)];
        SharedPreferences.Editor prefEditor = sharedPreferences.edit();
        prefEditor.putInt(Constants.CONFIG.CONF_SEARCH_MODE, nextMode);
        prefEditor.commit();
        fmsButton.setText(searchFMSNames[nextMode]);
        refreshDisplay();
    }

    @SuppressLint("ApplySharedPref")
    public void onClick_Dose(View v) {
        String newDisplayDose;
        switch (UIViewState.instance.displayDose) {
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
        UIViewState.instance.setDisplayDose(newDisplayDose, sharedPreferences);
        applyDisplayDoseButton(newDisplayDose);

        refreshDisplay();
    }

    public void onClick_Sound(View v) {
        AtomSpectraService.AlarmBaseline baseline = AtomSpectraService.getIntervalSearchAlarmBaseline();
        baseline.reset();
    }

    public void onClick_Channel(View v) {
        final AlertDialog.Builder alert = new AlertDialog.Builder(this);

        alert.setTitle(getString(R.string.ask_channel_title));
        alert.setMessage(getString(R.string.ask_channel_text, UIViewState.instance.cursorX));

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
            intValue = StrictMath.max(0, StrictMath.min(SpectrumData.instance.getChannelCount() - 1, intValue));
            UIViewState.instance.cursorX = intValue;
            showCursorInfo(true);
        });
        alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
            // nothing.
        });
        alert.show();
    }

    @Override
    public void onGesture(GestureOverlayView overlay, MotionEvent event) {
        // Auto-generated method stub

    }

    @Override
    public void onGestureCancelled(GestureOverlayView overlay, MotionEvent event) {
        // Auto-generated method stub

    }

    @Override
    public void onGestureEnded(GestureOverlayView overlay, MotionEvent event) {
        // Auto-generated method stub

    }

    @Override
    public void onGestureStarted(GestureOverlayView overlay, MotionEvent event) {
        // Auto-generated method stub

    }

    /**
     * Ask the service to apply the active device's calibration; the answer arrives as ACTION_DATA_AVAILABLE.
     */
    private void loadCalibration() {
        sendBroadcast(new Intent(Constants.ACTION.ACTION_LOAD_CALIBRATION)
                .setPackage(Constants.PACKAGE_NAME));
    }

    /**
     * Ask the service to store the active calibration into the active device.
     */
    private void storeCalibration() {
        sendBroadcast(new Intent(Constants.ACTION.ACTION_STORE_CALIBRATION)
                .setPackage(Constants.PACKAGE_NAME));
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

                int displayMode = UIViewState.instance.displayMode;
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
                        new_channel = UIViewState.instance.getFirstChannel() + Constants.WINDOW_OUTPUT_SIZE / 4 * (1 << (Constants.SCALE_MAX - UIViewState.instance.getxScaleFactor()));

                        if (new_channel + Constants.WINDOW_OUTPUT_SIZE * (1 << (Constants.SCALE_MAX - UIViewState.instance.getxScaleFactor())) > SpectrumData.instance.getChannelCount()) {
                            new_channel = SpectrumData.instance.getChannelCount() - Constants.WINDOW_OUTPUT_SIZE * (1 << (Constants.SCALE_MAX - UIViewState.instance.getxScaleFactor()));
                        }

                        UIViewState.instance.setFirstChannel(new_channel, sharedPreferences);
                        refreshDisplay();
                        findViewById(R.id.shape_area).performClick();
                    } else if (detector.isSwipeRight(e1, e2, velocityX)) {
                        new_channel = UIViewState.instance.getFirstChannel() - Constants.WINDOW_OUTPUT_SIZE / 4 * (1 << (Constants.SCALE_MAX - UIViewState.instance.getxScaleFactor()));

                        if (new_channel < 0) {
                            new_channel = 0;
                        }

                        UIViewState.instance.setFirstChannel(new_channel, sharedPreferences);
                        refreshDisplay();
                        findViewById(R.id.shape_area).performClick();
                    } else if (detector.isSwipeDown(e1, e2, velocityY)) {
                        if (AtomSpectraShapeView.isotopeFound >= 0 && UIViewState.instance.cursorX > 0) {
                            CheckBox isotopeData = findViewById(Constants.GROUPS.BUTTON_ID_ALIGN + AtomSpectraShapeView.isotopeFound);
                            AtomSpectraIsotopes.checkedIsotopeLine[AtomSpectraShapeView.isotopeFound] = !AtomSpectraIsotopes.checkedIsotopeLine[AtomSpectraShapeView.isotopeFound];
                            isotopeData.toggle();
                        }
                        refreshDisplay();
                        findViewById(R.id.shape_area).performClick();
                    }
                } catch (Exception ignored) {
                    // TODO: add to AtomSpectraLog
                } //for now, ignore
                return false;

            }

            @Override
            public boolean onDoubleTap(@NotNull MotionEvent e1) {
                if (!isSpectrumDisplayMode()) {
                    return true;
                }

                UIViewState.instance.setLogScale(!UIViewState.instance.logScale, sharedPreferences);
                refreshDisplay();
                if (UIViewState.instance.logScale) showToast(getString(R.string.graph_log_info));
                else showToast(getString(R.string.graph_linear_info));
                findViewById(R.id.shape_area).performClick();
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(@NotNull MotionEvent e1) {
                if (UIViewState.instance.showCalibrationFunction) {
                    showCursorInfo(true);
                    hideSeekChannel();
                    return true;
                }

                if (!isSpectrumDisplayMode()) {
                    return true;
                }

                boolean getInside = false;
                if (mAtomSpectraShapeView.isOutOfFrame(e1.getX())) {
                    hideSeekChannel();
                } else {
                    if ((SpectrumData.instance.newCalibration.getPointsCount() < Constants.MAX_CALIBRATION_POINTS)) {
                        for (Isotope i : AtomSpectraIsotopes.foundList) {
                            if (i.getCoord().contains(e1.getX(), e1.getY())) {
                                getInside = true;
                                final int channel_x = SpectrumData.instance.foreground.getSpectrumCalibration().toChannel(i.getEnergy(0));
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
                                DialogHelper.showStackedActions(AtomSpectra.this,
                                    getString(R.string.calibration_add_nuclid_title),
                                    getString(R.string.calibration_add_nuclid2_text, channel_x, i.getName(), i.getEnergy(0)),
                                    true,
                                    input,
                                    new DialogHelper.StackedAction(getString(android.R.string.ok), () -> {
                                            float fValue;// = value.valueOf(value);
                                            try {
                                                fValue = Float.parseFloat(input.getText().toString().replaceAll(",", "."));
                                            } catch (Exception nfe) {
                                                ToastHelper.showToastAndLog(AtomSpectra.this, getString(R.string.cal_error_number));
                                                return;
                                            }
                                            SpectrumData.instance.newCalibration.addPoint(channel_x, fValue);
                                            if (SpectrumData.instance.newCalibration.getPointsCount() > 1) {
                                                app_menu.findItem(R.id.action_cal_draw_function).setEnabled(true);
                                                SpectrumData.instance.newCalibration.Calculate(sharedPreferences.getInt(Constants.CONFIG.CONF_MAX_POLI_FACTOR, Constants.DEFAULT_POLI_FACTOR));
                                                if (!SpectrumData.instance.newCalibration.isCorrect()) {
                                                    ToastHelper.showToastAndLog(AtomSpectra.this, getString(R.string.cal_maybe_wrong, channel_x, fValue));
                                                }
                                            }
                                            updateCalibrationMenu();
                                            showCursorInfo(true);
                                        }),
                                        new DialogHelper.StackedAction(getString(android.R.string.cancel), null));
                            }
                        }
                    }
                    if ((SpectrumData.instance.newCalibration.getPointsCount() < Constants.MAX_CALIBRATION_POINTS) && (UIViewState.instance.cursorX != -1)) {
                        for (Isotope i : AtomSpectraIsotopes.isotopeLineArray) {
                            if (i.getCoord().contains(e1.getX(), e1.getY())) {
                                getInside = true;
                                DialogHelper.showStackedActions(AtomSpectra.this,
                                    getString(R.string.calibration_add_nuclid_title),
                                    getString(R.string.calibration_add_nuclid_text, UIViewState.instance.cursorX, i.getName(), i.getEnergy(0)),
                                    true,
                                    new DialogHelper.StackedAction(getString(android.R.string.ok), () -> {
                                            SpectrumData.instance.newCalibration.addPoint(UIViewState.instance.cursorX, i.getEnergy(0));
                                            if (SpectrumData.instance.newCalibration.getPointsCount() > 1) {
                                                SpectrumData.instance.newCalibration.Calculate(sharedPreferences.getInt(Constants.CONFIG.CONF_MAX_POLI_FACTOR, Constants.DEFAULT_POLI_FACTOR));
                                                app_menu.findItem(R.id.action_cal_draw_function).setEnabled(true);
                                                if (!SpectrumData.instance.newCalibration.isCorrect()) {
                                                    ToastHelper.showToastAndLog(AtomSpectra.this, getString(R.string.cal_maybe_wrong, UIViewState.instance.cursorX, i.getEnergy(0)));
                                                }
                                            }
                                            updateCalibrationMenu();
                                            showCursorInfo(true);
                                        }),
                                        new DialogHelper.StackedAction(getString(android.R.string.cancel), null));
                            }
                        }
                    }
                    if (!getInside) {
                        dateChannelChanged = System.currentTimeMillis();
                        seekChannel.setVisibility(SeekBar.VISIBLE);
                        if (UIViewState.instance.showPlusMinusButtons || (UIViewState.instance.cursorX < UIViewState.instance.getFirstChannel())) {
                            if (UIViewState.instance.energyAxis) {
                                UIViewState.instance.cursorX = SpectrumData.instance.foreground.getSpectrumCalibration().toChannel(mAtomSpectraShapeView.X2scale(e1.getX()));
                            } else {
                                UIViewState.instance.cursorX = (int) StrictMath.rint(mAtomSpectraShapeView.X2scale(e1.getX()));
                            }
                        }
                        UIViewState.instance.showPlusMinusButtons = true;
                    }
                }
                showCursorInfo(true);
                findViewById(R.id.shape_area).performClick();
                return true;
            }

            @Override
            public void onLongPress(@NotNull MotionEvent e1) {
                UIViewState.instance.setBarMode(!UIViewState.instance.barMode, sharedPreferences);
                refreshDisplay();
                findViewById(R.id.shape_area).performClick();
            }

            private void showToast(String phrase) {
                ToastHelper.showToastAndLog(getApplicationContext(), phrase);
            }
        });
    }

    private void hideSeekChannel() {
        UIViewState.instance.cursorX = -1;
        dateChannelChanged = 0;
        seekChannel.setVisibility(SeekBar.INVISIBLE);
        UIViewState.instance.showPlusMinusButtons = false;
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
                    if ((mSpanX > 100) && spanXPrefer && (UIViewState.instance.getxScaleFactor() <= Constants.SCALE_MAX)) {
                        if (UIViewState.instance.getxScaleFactor() < Constants.SCALE_MAX) {
                            UIViewState.instance.setxScaleFactor(UIViewState.instance.getxScaleFactor() + 1, sharedPreferences);
                            refreshDisplay();
                        } else
                            showToast(getString(R.string.graph_max_gain));
                    }

                    if ((mSpanY > 100) && spanYPrefer) {
                        //showToast("Zoom out Y");
                        if (UIViewState.instance.yZoomFactor < 40) {
                            if (UIViewState.instance.yZoomFactor >= 1)
                                UIViewState.instance.yZoomFactor *= 2;
                            else
                                UIViewState.instance.yZoomFactor += 0.25f;
                            refreshDisplay();
                        } else {
                            UIViewState.instance.yZoomFactor = 64;
                            showToast(getString(R.string.graph_max_zoom));
                        }
                    }

                    if ((mSpanX < -100) && spanXPrefer && (UIViewState.instance.getxScaleFactor() <= Constants.SCALE_MAX)) {
                        //showToast("Zoom in X");
                        if (UIViewState.instance.getxScaleFactor() > Constants.scaleMinFor(SpectrumData.instance.getChannelCount())) {

                            if (UIViewState.instance.getFirstChannel() + Constants.WINDOW_OUTPUT_SIZE / 2 * (1 << (1 + Constants.SCALE_MAX - UIViewState.instance.getxScaleFactor())) > SpectrumData.instance.getChannelCount()) {
                                new_channel = SpectrumData.instance.getChannelCount() - Constants.WINDOW_OUTPUT_SIZE / 2 * (1 << (1 + Constants.SCALE_MAX - UIViewState.instance.getxScaleFactor()));

                                if (new_channel < 0) {
                                    new_channel = 0;
                                }

                                UIViewState.instance.setFirstChannel(new_channel, sharedPreferences);
                            }

                            UIViewState.instance.setxScaleFactor(UIViewState.instance.getxScaleFactor() - 1, sharedPreferences);
                            refreshDisplay();
                        } else
                            showToast(getString(R.string.graph_min_gain));
                    }

                    if ((mSpanY < -100) && spanYPrefer) {
                        if (UIViewState.instance.yZoomFactor > 0.4) {
                            if (UIViewState.instance.yZoomFactor > 1)
                                UIViewState.instance.yZoomFactor /= 2;
                            else
                                UIViewState.instance.yZoomFactor -= 0.25f;
                            refreshDisplay();
                        } else {
                            UIViewState.instance.yZoomFactor = 0.25f;
                            showToast(getString(R.string.graph_min_zoom));
                        }
                    }

                }

                findViewById(R.id.shape_area).performClick();
            }

            private void showToast(String phrase) {
                ToastHelper.showToastAndLog(getApplicationContext(), phrase);
            }
        });
    }

    /**
     * Hides the channel seek bar after {@link Constants#CURSOR_TIMEOUT} of inactivity.
     */
    private final Timer seekChannelHideTimer = new Timer();
    private long dateChannelChanged = 0;
    private final TimerTask seekChannelHideTask = new TimerTask() {
        @Override
        public void run() {
            if (UIViewState.instance.showPlusMinusButtons && ((System.currentTimeMillis() - dateChannelChanged) > Constants.CURSOR_TIMEOUT)) {
                UIViewState.instance.showPlusMinusButtons = false;
                final SeekBar seekChannel = findViewById(R.id.seekChannel);
                seekChannel.post(() -> seekChannel.setVisibility(SeekBar.INVISIBLE));
            }
        }
    };

    private void showCursorInfo(boolean requestUpdateGraph) {
        if (UIViewState.instance.cursorX >= 0 && UIViewState.instance.cursorX >= UIViewState.instance.getFirstChannel() / SpectrumData.instance.getChannelCount() * SpectrumData.instance.lastCalibrationChannel && !UIViewState.instance.showCalibrationFunction) {
            mCursorView.setText(getString(R.string.cursor_format, UIViewState.instance.cursorX, SpectrumData.instance.foreground.getSpectrumCalibration().toEnergy(UIViewState.instance.cursorX), SpectrumData.instance.foreground.getDataArray()[UIViewState.instance.cursorX]));
            mCursorView.setVisibility(TextView.VISIBLE);
            mSuffixLayoutView.setVisibility(LinearLayout.VISIBLE);
        } else {
            mCursorView.setVisibility(TextView.INVISIBLE);
            mSuffixLayoutView.setVisibility(LinearLayout.INVISIBLE);
        }

        if (requestUpdateGraph) {
            refreshDisplay();
        }

        Log.d(TAG, "showCursorInfo: " + UIViewState.instance.cursorX);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
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
                withStorage(this::saveDefaultBackground, getString(R.string.perm_no_write_background));
            }
            return true;
        } else if (item.getItemId() == R.id.action_background_load) {
            Log.d(TAG, "loading background file");
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                withStorage(() -> {
                    Intent loadIntent = new Intent()
                            .setType("*/*")
                            .setAction(Intent.ACTION_GET_CONTENT);
                    startActivityForResult(Intent.createChooser(loadIntent, getString(R.string.ask_select_histogram)), LOAD_BACK_CODE);
                }, getString(R.string.perm_no_read_background));
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
                withStorage(() -> loadBackgroundOrDefault(null), getString(R.string.perm_no_read_background));
            }

            refreshDisplay();
            return true;
        } else if (item.getItemId() == R.id.action_background_load_from) {
            Log.d(TAG, "loading background file from...");
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                withStorage(() -> {
                    Intent loadIntent = new Intent()
                            .setType("*/*")
                            .setAction(Intent.ACTION_GET_CONTENT);
                    startActivityForResult(Intent.createChooser(loadIntent, getString(R.string.ask_select_histogram)), LOAD_BACK_CODE);
                }, getString(R.string.perm_no_read_background));
            } else {
                Intent loadIntent = new Intent()
                        .setType("*/*")
                        .setAction(Intent.ACTION_GET_CONTENT);
                startActivityForResult(Intent.createChooser(loadIntent, getString(R.string.ask_select_histogram)), LOAD_BACK_CODE);
            }

            refreshDisplay();
            return true;
        } else if (item.getItemId() == R.id.action_background_copy) {
            moveToBackground();
            refreshDisplay();
            return true;
        } else if (item.getItemId() == R.id.action_hist_suffix) {
            final AlertDialog.Builder alert = new AlertDialog.Builder(this);
            alert.setTitle(getString(R.string.ask_spectrum_suffix));
            alert.setMessage(getString(R.string.ask_suffix_text));

            final EditText input = new EditText(this);
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            input.setImeOptions(EditorInfo.IME_ACTION_DONE);
            input.setText(SpectrumData.instance.foreground.getSuffix(), TextView.BufferType.EDITABLE);
            alert.setView(input);
            alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                SpectrumData.instance.foreground.setSuffix(input.getText().toString());
                TextView text = findViewById(R.id.suffixView);
                text.setText(SpectrumData.instance.foreground.getSuffix());
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
            input.setText(SpectrumData.instance.background.getSuffix(), TextView.BufferType.EDITABLE);
            alert.setView(input);
            alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                SpectrumData.instance.background.setSuffix(input.getText().toString());
                TextView text = findViewById(R.id.backgroundSuffixView);
                text.setText(SpectrumData.instance.background.getSuffix());
            });
            alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
            });
            alert.show();
            return true;
        } else if (item.getItemId() == R.id.action_background_clear) {
            clearBackground();
            refreshDisplay();
            return true;
        } else if (item.getItemId() == R.id.action_background_subtract) {
            if (item.isChecked()) {
                item.setChecked(false);
                UIViewState.instance.backgroundSubtract = false;
            } else {
                if (!SpectrumData.instance.background.isEmpty()) {
                    UIViewState.instance.backgroundSubtract = true;
                    item.setChecked(true);
                }
            }

            refreshDisplay();
            return true;
        } else if (item.getItemId() == R.id.action_background_show) {
            if (item.isChecked()) {
                item.setChecked(false);
                UIViewState.instance.backgroundShow = false;
                UIViewState.instance.backgroundSubtract = false;
                app_menu.findItem(R.id.action_background_subtract).setChecked(false);
                app_menu.findItem(R.id.action_background_subtract).setEnabled(false);
            } else {
                if (!SpectrumData.instance.background.isEmpty()) {
                    UIViewState.instance.backgroundShow = true;
                    item.setChecked(true);
                    app_menu.findItem(R.id.action_background_subtract).setEnabled(true);
                }
            }

            refreshDisplay();
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
                withStorage(() -> {
                    Intent loadIntent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("*/*")
                            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    startActivityForResult(Intent.createChooser(loadIntent, getString(R.string.ask_select_spectrogram)), LOAD_SPG_CODE);
                }, getString(R.string.perm_no_read_spectrogram));
            } else {
                Intent loadIntent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("*/*")
                        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                startActivityForResult(Intent.createChooser(loadIntent, getString(R.string.ask_select_spectrogram)), LOAD_SPG_CODE);
            }
            return true;
        } else if (item.getItemId() == R.id.action_spectrogram_view) {
            showSpectrogramView();
            return true;
        } else if (item.getItemId() == R.id.action_hist_view_map) {
            AtomSpectraMap.openSpectrumMap(this);
            return true;
        } else if (item.getItemId() == R.id.action_spectrogram_view_map) {
            AtomSpectraMap.openSpectrogramMap(this);
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
                            input.setText(SpectrumData.instance.foreground.getSuffix(), TextView.BufferType.EDITABLE);
                            alert.setView(input);
                            alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                                SpectrumData.instance.foreground.setSuffix(input.getText().toString());
                                TextView text = findViewById(R.id.suffixView);
                                text.setText(SpectrumData.instance.foreground.getSuffix());
                                saveSpectrumAS(SpectrumData.instance.foreground.getSuffix());
                            });
                            alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> endConnectDecisionSaving());
                            alert.setOnCancelListener(dialog -> endConnectDecisionSaving());
                            alert.show();
                        } catch (Exception e) {
                            Log.d(TAG, "saving file FAIL");
                        }
                    } else {
                        requestDirectory(SELECT_SAVE_HIST_DIR_CODE);
                    }
                }
            } else {
                withStorage(() -> {
                    try {
                        final AlertDialog.Builder alert = new AlertDialog.Builder(this);
                        alert.setTitle(getString(R.string.ask_spectrum_suffix));
                        alert.setMessage(getString(R.string.ask_suffix_text));

                        final EditText input = new EditText(this);
                        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
                        input.setText(SpectrumData.instance.foreground.getSuffix(), TextView.BufferType.EDITABLE);
                        alert.setView(input);
                        alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                            SpectrumData.instance.foreground.setSuffix(input.getText().toString());
                            TextView text = findViewById(R.id.suffixView);
                            text.setText(SpectrumData.instance.foreground.getSuffix());
                            saveSpectrumAS(SpectrumData.instance.foreground.getSuffix());
                        });
                        alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> endConnectDecisionSaving());
                        alert.setOnCancelListener(dialog -> endConnectDecisionSaving());
                        alert.show();
                    } catch (Exception e) {
                        Log.d(TAG, "saving file FAIL");
                    }
                }, getString(R.string.perm_no_write_histogram));
            }
            return true;
        } else if (item.getItemId() == R.id.action_hist_from_file) {
            if (AtomSpectraService.isRecording()) {
                ToastHelper.showToastAndLog(this, getString(R.string.hist_recording_in_progress));
                return true;
            }
            Log.d(TAG, "loading hist file");
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                confirmReplaceUnsaved(() -> withStorage(this::openLoadSpectrumPicker, getString(R.string.perm_no_read_histogram)));
            } else {
                confirmReplaceUnsaved(this::openLoadSpectrumPicker);
            }
            return true;
        } else if (item.getItemId() == R.id.action_hist_add_from_file) {
            if (AtomSpectraService.isRecording()) {
                ToastHelper.showToastAndLog(this, getString(R.string.hist_recording_in_progress));
                return true;
            }
            Log.d(TAG, "adding hist file");
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                withStorage(() -> {
                    Intent loadIntent = new Intent()
                            .setType("*/*")
                            .setAction(Intent.ACTION_GET_CONTENT);
                    startActivityForResult(Intent.createChooser(loadIntent, getString(R.string.ask_select_histogram)), ADD_HIST_CODE);
                }, getString(R.string.perm_no_read_histogram));
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
                withStorage(() -> {
                    Intent calibIntent = new Intent()
                            .setType("*/*")
                            .setAction(Intent.ACTION_GET_CONTENT);
                    startActivityForResult(Intent.createChooser(calibIntent, getString(R.string.ask_select_histogram)), LOAD_CALIBRATION_CODE);
                }, getString(R.string.perm_no_read_calibration));
            } else {
                Intent calibIntent = new Intent()
                        .setType("*/*")
                        .setAction(Intent.ACTION_GET_CONTENT);
                startActivityForResult(Intent.createChooser(calibIntent, getString(R.string.ask_select_calibration)), LOAD_CALIBRATION_CODE);
            }
            return true;
        } else if (item.getItemId() == R.id.action_cal_store_device) {
            Log.d(TAG, "storing calibration to device");
            storeCalibration();
            return true;
        } else if (item.getItemId() == R.id.action_cal_retrieve_device) {
            Log.d(TAG, "using calibration stored in the device");
            loadCalibration();
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
                            input.setText(SpectrumData.instance.foreground.getSuffix(), TextView.BufferType.EDITABLE);
                            alert.setView(input);
                            alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                                SpectrumData.instance.foreground.setSuffix(input.getText().toString());
                                TextView text = findViewById(R.id.suffixView);
                                text.setText(SpectrumData.instance.foreground.getSuffix());
                                saveCSV(SpectrumData.instance.foreground.getSuffix(), false);
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
                withStorage(() -> {
                    try {
                        final AlertDialog.Builder alert = new AlertDialog.Builder(this);
                        alert.setTitle(getString(R.string.ask_export_suffix));
                        alert.setMessage(getString(R.string.ask_suffix_text));
                        final EditText input = new EditText(this);
                        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
                        input.setText(SpectrumData.instance.foreground.getSuffix(), TextView.BufferType.EDITABLE);
                        alert.setView(input);
                        alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                            SpectrumData.instance.foreground.setSuffix(input.getText().toString());
                            TextView text = findViewById(R.id.suffixView);
                            text.setText(SpectrumData.instance.foreground.getSuffix());
                            saveCSV(SpectrumData.instance.foreground.getSuffix(), false);
                        });
                        alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
                        });
                        alert.show();
                    } catch (Exception e) {
                        Log.d(TAG, "saving file FAIL");
                    }
                }, getString(R.string.perm_no_write_export));
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
                            input.setText(SpectrumData.instance.foreground.getSuffix(), TextView.BufferType.EDITABLE);
                            alert.setView(input);
                            alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                                SpectrumData.instance.foreground.setSuffix(input.getText().toString());
                                TextView text = findViewById(R.id.suffixView);
                                text.setText(SpectrumData.instance.foreground.getSuffix());
                                saveCSV(SpectrumData.instance.foreground.getSuffix(), true);
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
                withStorage(() -> {
                    try {
                        final AlertDialog.Builder alert = new AlertDialog.Builder(this);
                        alert.setTitle(getString(R.string.ask_export_suffix));
                        alert.setMessage(getString(R.string.ask_suffix_text));
                        final EditText input = new EditText(this);
                        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
                        input.setText(SpectrumData.instance.foreground.getSuffix(), TextView.BufferType.EDITABLE);
                        alert.setView(input);
                        alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                            SpectrumData.instance.foreground.setSuffix(input.getText().toString());
                            TextView text = findViewById(R.id.suffixView);
                            text.setText(SpectrumData.instance.foreground.getSuffix());
                            saveCSV(SpectrumData.instance.foreground.getSuffix(), true);
                        });
                        alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
                        });
                        alert.show();
                    } catch (Exception e) {
                        Log.d(TAG, "saving file FAIL");
                    }
                }, getString(R.string.perm_no_write_export_energy));
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
                            input.setText(SpectrumData.instance.foreground.getSuffix(), TextView.BufferType.EDITABLE);
                            alert.setView(input);
                            alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                                SpectrumData.instance.foreground.setSuffix(input.getText().toString());
                                TextView text = findViewById(R.id.suffixView);
                                text.setText(SpectrumData.instance.foreground.getSuffix());
                                saveBqMoni(SpectrumData.instance.foreground.getSuffix());
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
                withStorage(() -> {
                    try {
                        final AlertDialog.Builder alert = new AlertDialog.Builder(this);
                        alert.setTitle(getString(R.string.ask_export_bqmoni));
                        alert.setMessage(getString(R.string.ask_suffix_text));
                        final EditText input = new EditText(this);
                        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
                        input.setText(SpectrumData.instance.foreground.getSuffix(), TextView.BufferType.EDITABLE);
                        alert.setView(input);
                        alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                            SpectrumData.instance.foreground.setSuffix(input.getText().toString());
                            TextView text = findViewById(R.id.suffixView);
                            text.setText(SpectrumData.instance.foreground.getSuffix());
                            saveBqMoni(SpectrumData.instance.foreground.getSuffix());
                        });
                        alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
                        });
                        alert.show();
                    } catch (Exception e) {
                        Log.d(TAG, "saving file FAIL");
                    }
                }, getString(R.string.perm_no_write_bqmoni));
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
                            input.setText(SpectrumData.instance.foreground.getSuffix(), TextView.BufferType.EDITABLE);
                            alert.setView(input);
                            alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                                SpectrumData.instance.foreground.setSuffix(input.getText().toString());
                                TextView text = findViewById(R.id.suffixView);
                                text.setText(SpectrumData.instance.foreground.getSuffix());
                                saveSPE(SpectrumData.instance.foreground.getSuffix());
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
                withStorage(() -> {
                    try {
                        final AlertDialog.Builder alert = new AlertDialog.Builder(this);
                        alert.setTitle(getString(R.string.ask_export_spe));
                        alert.setMessage(getString(R.string.ask_suffix_text));
                        final EditText input = new EditText(this);
                        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
                        input.setText(SpectrumData.instance.foreground.getSuffix(), TextView.BufferType.EDITABLE);
                        alert.setView(input);
                        alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                            SpectrumData.instance.foreground.setSuffix(input.getText().toString());
                            TextView text = findViewById(R.id.suffixView);
                            text.setText(SpectrumData.instance.foreground.getSuffix());
                            saveSPE(SpectrumData.instance.foreground.getSuffix());
                        });
                        alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
                        });
                        alert.show();
                    } catch (Exception e) {
                        Log.d(TAG, "saving file FAIL");
                    }
                }, getString(R.string.perm_no_write_spe));
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
                            input.setText(SpectrumData.instance.foreground.getSuffix(), TextView.BufferType.EDITABLE);
                            alert.setView(input);
                            alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                                SpectrumData.instance.foreground.setSuffix(input.getText().toString());
                                TextView text = findViewById(R.id.suffixView);
                                text.setText(SpectrumData.instance.foreground.getSuffix());
                                saveN42(SpectrumData.instance.foreground.getSuffix());
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
                withStorage(() -> {
                    try {
                        final AlertDialog.Builder alert = new AlertDialog.Builder(this);
                        alert.setTitle(getString(R.string.ask_export_N42));
                        alert.setMessage(getString(R.string.ask_suffix_text));
                        final EditText input = new EditText(this);
                        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
                        input.setText(SpectrumData.instance.foreground.getSuffix(), TextView.BufferType.EDITABLE);
                        alert.setView(input);
                        alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                            SpectrumData.instance.foreground.setSuffix(input.getText().toString());
                            TextView text = findViewById(R.id.suffixView);
                            text.setText(SpectrumData.instance.foreground.getSuffix());
                            saveN42(SpectrumData.instance.foreground.getSuffix());
                        });
                        alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
                        });
                        alert.show();
                    } catch (Exception e) {
                        Log.d(TAG, "saving file FAIL");
                    }
                }, getString(R.string.perm_no_write_N42));
            }
            return true;
        } else if (item.getItemId() == R.id.action_clear_spectrum) {
            onClickDeleteSpc(findViewById(R.id.clearSpectrumButton));
            return true;
        } else if (item.getItemId() == R.id.action_hist_smooth) {
            UIViewState.instance.smooth = !UIViewState.instance.smooth;
            item.setTitle(UIViewState.instance.smooth ? getString(R.string.hist_unsmooth) : getString(R.string.hist_smooth));
            refreshDisplay();
            return true;
        } else if (item.getItemId() == R.id.action_switch_device) {
            openDeviceSelection(false);
            return true;
        } else if (item.getItemId() == R.id.action_record_toggle) {
            if (!AtomSpectraService.isRecording()
                    && AtomSpectraService.sessionState != AtomSpectraService.DeviceSessionState.LOCKED) {
                // no device chosen: pick one first, recording starts after it is connected
                openDeviceSelection(true);
            } else if (!AtomSpectraService.isRecording() && !AtomSpectraService.isDeviceConnected()) {
                ToastHelper.showToastAndLog(this, getString(R.string.device_not_available));
            } else if (!AtomSpectraService.isRecording()) {
                startRecordingAfterDecision();
            } else {
                sendBroadcast(new Intent(Constants.ACTION.ACTION_STOP_RECORDING).setPackage(Constants.PACKAGE_NAME));
            }
            return true;
        } else if (item.getItemId() == R.id.action_share_export) {
            Log.d(TAG, "sharing file");
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                withStorage(() -> {
                    try {
                        //do not delete, may be good
                        Intent loadIntent = new Intent()
                                .setType("*/*")
                                .setAction(Intent.ACTION_GET_CONTENT);
                        startActivityForResult(Intent.createChooser(loadIntent, getString(R.string.ask_share)), SHARE_FILE_CODE);
                    } catch (Exception e) {
                        Log.d(TAG, "Sharing a file FAIL");
                    }
                }, getString(R.string.perm_no_share));
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
            input.setText(String.format(Locale.getDefault(), "%.12g", SpectrumData.instance.foreground.getSpectrumCalibration().getCoeffArray(5)[number]));
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
                    ToastHelper.showToastAndLog(context, getString(R.string.cal_error_number));
                    return;
                }
                Calibration new_cal = new Calibration(SpectrumData.instance.getChannelCount());
                double[] coeffs = SpectrumData.instance.foreground.getSpectrumCalibration().getCoeffArray(5);
                coeffs[finalNumber] = fValue;
                new_cal.Calculate(coeffs);
                if (!new_cal.isCorrect()) {
                    ToastHelper.showToastAndLog(context, getString(R.string.cal_error_number));
                    return;
                }
                itemMenu.setTitle(String.format(Locale.getDefault(), "c%d: %.12g", finalNumber, fValue));
                SpectrumData.instance.applyCalibration(context, new_cal);
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
                DialogHelper.showStackedActions(this,
                    getString(R.string.ask_delete_calibration_line_title),
                    getString(R.string.ask_delete_calibration_line_text, SpectrumData.instance.newCalibration.getPointChannel(finalNumber), SpectrumData.instance.newCalibration.getPointEnergy(finalNumber)),
                    true,
                    new DialogHelper.StackedAction(getString(android.R.string.ok), () -> {
                        int channel_x = SpectrumData.instance.newCalibration.getPointChannel(finalNumber);
                        double fValue = SpectrumData.instance.newCalibration.getPointEnergy(finalNumber);
                        SpectrumData.instance.newCalibration.removePoint(finalNumber);
                        if (SpectrumData.instance.newCalibration.getPointsCount() > 1) {
                            SpectrumData.instance.newCalibration.Calculate(sharedPreferences.getInt(Constants.CONFIG.CONF_MAX_POLI_FACTOR, Constants.DEFAULT_POLI_FACTOR));
                            if (!SpectrumData.instance.newCalibration.isCorrect()) {
                                ToastHelper.showToastAndLog(AtomSpectra.this, getString(R.string.cal_maybe_wrong, channel_x, fValue));
                            }
                        } else {
                            UIViewState.instance.showCalibrationFunction = false;
                            app_menu.findItem(R.id.action_cal_draw_function).setChecked(false);
                            app_menu.findItem(R.id.action_cal_draw_function).setEnabled(false);
                        }
                        updateCalibrationMenu();
                        showCursorInfo(true);
                    }),
                    new DialogHelper.StackedAction(getString(android.R.string.cancel), null));
            return true;
        } else if (item.getItemId() == R.id.action_cal_function) {
            try {
                final AlertDialog.Builder alert = new AlertDialog.Builder(this);
                alert.setTitle(getString(R.string.cal_function_title));
                alert.setMessage(getString(R.string.cal_function_message));

                final TextView output = new TextView(this);
                output.setText(SpectrumData.instance.foreground.getSpectrumCalibration().getFunction());
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
            if (SpectrumData.instance.newCalibration.getPointsCount() > 1) {
                item.setChecked(!UIViewState.instance.showCalibrationFunction);
                UIViewState.instance.showCalibrationFunction = !UIViewState.instance.showCalibrationFunction;
            } else {
                UIViewState.instance.showCalibrationFunction = false;
                item.setChecked(false);
            }
            showCursorInfo(true);
        } else if (item.getItemId() == R.id.action_cal_channel) {
            final MenuItem menuVal = item;
            final AlertDialog.Builder alert = new AlertDialog.Builder(this);
            alert.setTitle(getString(R.string.ask_last_channel_title));
            alert.setMessage(getString(R.string.ask_last_channel_text, Constants.MIN_LAST_CALIBRATION_CHANNEL, SpectrumData.instance.getChannelCount()));

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
                    ToastHelper.showToastAndLog(context, getString(R.string.cal_error_number));
                    return;
                }
                SpectrumData.instance.applyLastCalibrationChannel(context, iValue);
                menuVal.setTitle(getString(R.string.calibration_channel_format, SpectrumData.instance.lastCalibrationChannel));
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
            if (AtomSpectraService.isRecording()) {
                // spectrum recording is in progress, confirm action
                DialogHelper.showActionConfirmationDialog(this, getString(R.string.dialog_confirm_exit_while_recording_message), () -> {
                    exitProgramCompletely();
                });
            } else if (SpectrumData.instance.foreground.isChanged()) {
                DialogHelper.showActionConfirmationDialog(this, getString(R.string.dialog_confirm_exit_unsaved_message), () -> {
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
                loadSpectrumFile(selectedFile, true);
        } else if (requestCode == ADD_HIST_CODE && resultCode == RESULT_OK) {
            selectedFile = data.getData(); //The uri with the location of the file
            if (selectedFile != null)
                addSpectrumFromFile(selectedFile);
        } else if (requestCode == LOAD_BACK_CODE && resultCode == RESULT_OK) {

            selectedFile = data.getData(); //The uri with the location of the file
            if (selectedFile != null)
                loadBackgroundOrDefault(selectedFile);
        } else if (requestCode == LOAD_CALIBRATION_CODE && resultCode == RESULT_OK) {
            selectedFile = data.getData(); //The uri with the location of the file
            if (selectedFile != null)
                loadCalibrationFromSpectrum(selectedFile);
        } else if (requestCode == SHARE_FILE_CODE && resultCode == RESULT_OK) {
            selectedFile = data.getData();
            if (selectedFile != null)
                shareFile(selectedFile);
        } else if (requestCode == LOAD_SPG_CODE && resultCode == RESULT_OK) {
            List<Uri> selectedFiles = new ArrayList<>();
            if (data.getClipData() != null) {
                ClipData clipData = data.getClipData();
                for (int i = 0; i < clipData.getItemCount(); i++) {
                    Uri uri = clipData.getItemAt(i).getUri();
                    if (uri != null) {
                        selectedFiles.add(uri);
                    }
                }
            } else if (data.getData() != null) {
                selectedFiles.add(data.getData());
            }
            if (!selectedFiles.isEmpty())
                loadAndViewSpectrograms(selectedFiles);
        } else if (requestCode == SELECT_INITIAL_WORKING_DIR_CODE && resultCode == RESULT_OK && (data != null)) {
            final Uri dirUri = data.getData();
            if (dirUri != null) {
                final DocumentFile dir = DocumentFile.fromTreeUri(this, dirUri);
                if ((dir != null) && dir.isDirectory()) {
                    SharedPreferences.Editor editor = sharedPreferences.edit();
                    editor.putString(Constants.CONFIG.CONF_DIRECTORY_SELECTED, dirUri.toString());
                    final int takeFlags = data.getFlags()
                            & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    getContentResolver().takePersistableUriPermission(dirUri, takeFlags);
                    editor.commit();
                    final String name = dir.getName();
                    ToastHelper.showToastAndLog(this, getString(R.string.working_dir_set,
                            name != null ? name : dirUri.getLastPathSegment()));
                }
            }
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
                        input.setText(SpectrumData.instance.foreground.getSuffix(), TextView.BufferType.EDITABLE);
                        alert.setView(input);
                        alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                            SpectrumData.instance.foreground.setSuffix(input.getText().toString());
                            TextView text = findViewById(R.id.suffixView);
                            text.setText(SpectrumData.instance.foreground.getSuffix());
                            saveSpectrumAS(SpectrumData.instance.foreground.getSuffix());
                        });
                        alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> endConnectDecisionSaving());
                        alert.setOnCancelListener(dialog -> endConnectDecisionSaving());
                        alert.show();
                    } else {
                        ToastHelper.showToastAndLog(this, getString(R.string.perm_no_write_histogram));
                        endConnectDecisionSaving();
                    }
                } else {
                    ToastHelper.showToastAndLog(this, getString(R.string.perm_no_write_histogram));
                    endConnectDecisionSaving();
                }
            } catch (Exception e) {
                Log.d(TAG, "saving file FAIL");
                endConnectDecisionSaving();
            }
        } else if (requestCode == SELECT_SAVE_HIST_DIR_CODE) {
            endConnectDecisionSaving();
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
                        ToastHelper.showToastAndLog(this, getString(R.string.perm_no_write_background));
                    }
                } else {
                    ToastHelper.showToastAndLog(this, getString(R.string.perm_no_write_background));
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
                        ToastHelper.showToastAndLog(this, getString(R.string.perm_no_read_background));
                    }
                } else {
                    ToastHelper.showToastAndLog(this, getString(R.string.perm_no_read_background));
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
                        input.setText(SpectrumData.instance.foreground.getSuffix(), TextView.BufferType.EDITABLE);
                        alert.setView(input);
                        alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                            SpectrumData.instance.foreground.setSuffix(input.getText().toString());
                            TextView text = findViewById(R.id.suffixView);
                            text.setText(SpectrumData.instance.foreground.getSuffix());
                            saveCSV(SpectrumData.instance.foreground.getSuffix(), false);
                        });
                        alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
                        });
                        alert.show();
                    } else {
                        ToastHelper.showToastAndLog(this, getString(R.string.perm_no_write_export));
                    }
                } else {
                    ToastHelper.showToastAndLog(this, getString(R.string.perm_no_write_export));
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
                        input.setText(SpectrumData.instance.foreground.getSuffix(), TextView.BufferType.EDITABLE);
                        alert.setView(input);
                        alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                            SpectrumData.instance.foreground.setSuffix(input.getText().toString());
                            TextView text = findViewById(R.id.suffixView);
                            text.setText(SpectrumData.instance.foreground.getSuffix());
                            saveCSV(SpectrumData.instance.foreground.getSuffix(), true);
                        });
                        alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
                        });
                        alert.show();
                    } else {
                        ToastHelper.showToastAndLog(this, getString(R.string.perm_no_write_export_energy));
                    }
                } else {
                    ToastHelper.showToastAndLog(this, getString(R.string.perm_no_write_export_energy));
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
                        input.setText(SpectrumData.instance.foreground.getSuffix(), TextView.BufferType.EDITABLE);
                        alert.setView(input);
                        alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                            SpectrumData.instance.foreground.setSuffix(input.getText().toString());
                            TextView text = findViewById(R.id.suffixView);
                            text.setText(SpectrumData.instance.foreground.getSuffix());
                            saveBqMoni(SpectrumData.instance.foreground.getSuffix());
                        });
                        alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
                        });
                        alert.show();
                    } else {
                        ToastHelper.showToastAndLog(this, getString(R.string.perm_no_write_bqmoni));
                    }
                } else {
                    ToastHelper.showToastAndLog(this, getString(R.string.perm_no_write_bqmoni));
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
                        input.setText(SpectrumData.instance.foreground.getSuffix(), TextView.BufferType.EDITABLE);
                        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
                        alert.setView(input);
                        alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                            SpectrumData.instance.foreground.setSuffix(input.getText().toString());
                            TextView text = findViewById(R.id.suffixView);
                            text.setText(SpectrumData.instance.foreground.getSuffix());
                            saveSPE(SpectrumData.instance.foreground.getSuffix());
                        });
                        alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
                        });
                        alert.show();
                    } else {
                        ToastHelper.showToastAndLog(this, getString(R.string.perm_no_write_spe));
                    }
                } else {
                    ToastHelper.showToastAndLog(this, getString(R.string.perm_no_write_spe));
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
                        input.setText(SpectrumData.instance.foreground.getSuffix(), TextView.BufferType.EDITABLE);
                        alert.setView(input);
                        alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                            SpectrumData.instance.foreground.setSuffix(input.getText().toString());
                            TextView text = findViewById(R.id.suffixView);
                            text.setText(SpectrumData.instance.foreground.getSuffix());
                            saveN42(SpectrumData.instance.foreground.getSuffix());
                        });
                        alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
                        });
                        alert.show();
                    } else {
                        ToastHelper.showToastAndLog(this, getString(R.string.perm_no_write_N42));
                    }
                } else {
                    ToastHelper.showToastAndLog(this, getString(R.string.perm_no_write_N42));
                }
            } catch (Exception e) {
                Log.d(TAG, "saving file FAIL");
            }
        } else
            super.onActivityResult(requestCode, resultCode, data);
    }

    // the screen spectrum is replaced by a file: unsaved data is never dropped without asking
    private void loadSpectrum(Uri histFile, boolean showMessage) {
        if (AtomSpectraService.isRecording()) {
            ToastHelper.showToastAndLog(this, getString(R.string.hist_recording_in_progress));
            return;
        }
        confirmReplaceUnsaved(() -> loadSpectrumFile(histFile, showMessage));
    }

    private void confirmReplaceUnsaved(Runnable proceed) {
        if (!SpectrumData.instance.foreground.isChanged()) {
            proceed.run();
            return;
        }
        DialogHelper.showStackedActions(this,
                getString(R.string.dialog_confirm_title),
                getString(R.string.hist_load_unsaved_text),
                true,
                new DialogHelper.StackedAction(getString(R.string.start_save_button), () -> {
                    afterSaveAction = proceed;
                    startSavingSpectrum();
                }),
                new DialogHelper.StackedAction(getString(R.string.hist_load_discard_button), proceed),
                new DialogHelper.StackedAction(getString(R.string.dialog_cancel_button), null));
    }

    private void openLoadSpectrumPicker() {
        Intent loadIntent = new Intent()
                .setType("*/*")
                .setAction(Intent.ACTION_GET_CONTENT);
        startActivityForResult(Intent.createChooser(loadIntent, getString(R.string.ask_select_histogram)), LOAD_HIST_CODE);
    }

    private void loadSpectrumFile(Uri histFile, boolean showMessage) {
        if (AtomSpectraService.isRecording()) {
            ToastHelper.showToastAndLog(this, getString(R.string.hist_recording_in_progress));
            return;
        }
        final String filename = histFile.getPath();
        if (filename == null) {
            Log.d(TAG, "Null filename");
            ToastHelper.showToastAndLog(this, getString(R.string.strange_file_name));
            return;
        }
        Log.d(TAG, filename);

        SpectrumFileAS spectrumFile = new SpectrumFileAS();
        try {
            spectrumFile.loadSpectrum(histFile, this);
            Spectrum spectrum = spectrumFile.getSpectrum(0);
            if (spectrum == null) {
                throw new NullPointerException("Unexpected: spectrum is null after successful load");
            }

            final boolean resized = SpectrumData.instance.replaceForeground(spectrum);
            AtomSpectraService.markScreenForeign();
            if (resized) {
                UIViewState.instance.backgroundShow = false;
                UIViewState.instance.backgroundSubtract = false;
                UIViewState.instance.onChannelCountChanged(spectrum.getDataArray().length);
                SpectrumData.instance.background.setSuffix(getString(R.string.background_suffix));
                SpectrumChangeData.instance.reset();
            } else {
                SpectrumData.instance.applyLastCalibrationChannel(this, SpectrumData.instance.lastCalibrationChannel);
            }
            ((TextView) findViewById(R.id.suffixView)).setText(spectrum.getSuffix());
            updateCountDependentMenu();
            updateCalibrationMenu();
            updateMapMenu();
            refreshForegroundSpectrumView();
            Log.d(TAG, "Histogram is loaded successfully");
            if (showMessage) {
                ToastHelper.showToastAndLog(this, getString(R.string.hist_load_success));
            }
        } catch (Exception e) {
            AtomSpectraLog.addMessage(this, Log.getStackTraceString(e));
            if (showMessage) {
                ToastHelper.showToastAndLog(this, getString(R.string.hist_load_error));
            }
        }
    }

    private void clearBackground() {
        final int channelCount = SpectrumData.instance.getChannelCount();
        UIViewState.instance.backgroundShow = false;
        UIViewState.instance.backgroundSubtract = false;
        SpectrumData.instance.background
                .initSpectrumData(channelCount, Calibration.defaultCalibration(channelCount))
                .setSuffix(getString(R.string.background_suffix));
        updateCountDependentMenu();
    }

    private void addSpectrumFromFile(Uri histFile) {
        if (AtomSpectraService.isRecording()) {
            ToastHelper.showToastAndLog(this, getString(R.string.hist_recording_in_progress));
            return;
        }
        final String filename = histFile.getPath();
        if (filename == null) {
            Log.d(TAG, "Null filename");
            ToastHelper.showToastAndLog(this, getString(R.string.strange_file_name));
            return;
        }
        Log.d(TAG, filename);

        SpectrumFileAS spectrumFile = new SpectrumFileAS();
        try {
            spectrumFile.loadSpectrum(histFile, this);
            Spectrum spectrum = spectrumFile.getSpectrum(0);
            if (spectrum == null) {
                throw new NullPointerException("Unexpected: spectrum is null after successful load");
            }

            Spectrum current = SpectrumData.instance.foreground;
            if (current.getSourceChannelCount() != spectrum.getSourceChannelCount()
                    || current.getDataArray().length != spectrum.getDataArray().length) {
                ToastHelper.showToastAndLog(this, getString(R.string.hist_add_channel_mismatch));
                return;
            }

            if (isSpectrumCombineMismatch(current, spectrum)) {
                DialogHelper.showActionConfirmationDialog(this, getString(R.string.hist_add_mismatch_confirm), () -> applyAddedSpectrum(spectrum));
                return;
            }
            applyAddedSpectrum(spectrum);
        } catch (Exception e) {
            AtomSpectraLog.addMessage(this, Log.getStackTraceString(e));
            ToastHelper.showToastAndLog(this, getString(R.string.hist_add_error));
        }
    }

    private void applyAddedSpectrum(Spectrum spectrum) {
        if (AtomSpectraService.isRecording()) {
            ToastHelper.showToastAndLog(this, getString(R.string.hist_recording_in_progress));
            return;
        }
        SpectrumData.instance.foreground.addSpectrum(spectrum);
        AtomSpectraService.markScreenForeign();
        refreshForegroundSpectrumView();
        Log.d(TAG, "Histogram is added successfully");
        ToastHelper.showToastAndLog(this, getString(R.string.hist_add_success));
    }

    private void refreshForegroundSpectrumView() {
        AtomSpectraIsotopes.showFoundIsotopes = false;
        AtomSpectraIsotopes.foundList.clear();
        refreshDisplay();
    }

    private boolean isSpectrumCombineMismatch(Spectrum a, Spectrum b) {
        String deviceInfoA = a.getDeviceInfo() == null ? "" : a.getDeviceInfo();
        String deviceInfoB = b.getDeviceInfo() == null ? "" : b.getDeviceInfo();
        boolean deviceInfoMismatch = !deviceInfoA.equals(deviceInfoB);

        Calibration calA = a.getSpectrumCalibration();
        Calibration calB = b.getSpectrumCalibration();
        boolean calibrationMismatch = calA == null ? calB != null : !calA.hasEquivalentCoefficients(calB);

        return deviceInfoMismatch || calibrationMismatch;
    }

    private void loadCalibrationFromSpectrum(Uri histFile) {
        final String filename = histFile.getPath();
        if (filename == null) {
            Log.d(TAG, "Null filename");
            ToastHelper.showToastAndLog(this, getString(R.string.strange_file_name));
            return;
        }
        Log.d(TAG, filename);

        SpectrumFileAS spectrumFile = new SpectrumFileAS();
        try {
            spectrumFile.loadSpectrum(histFile, this);
            Spectrum spectrum = spectrumFile.getSpectrum(0);
            if (spectrum == null) {
                throw new NullPointerException("Unexpected: spectrum is null after successful load");
            }

            SpectrumData.instance.applyCalibration(this, spectrum.getSpectrumCalibration());

            Log.d(TAG, "Calibration is loaded successfully");
            ToastHelper.showToastAndLog(this, getString(R.string.cal_load_success));
        } catch (Exception e) {
            AtomSpectraLog.addMessage(this, Log.getStackTraceString(e));
            ToastHelper.showToastAndLog(this, getString(R.string.cal_load_error));
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

    private void loadAndViewSpectrograms(List<Uri> histFiles) {
        if (isLoadingSpectrogram) {
            ToastHelper.showToastAndLog(this, "ERROR: loadAndViewSpectrograms called while spectrogram is loading.");
            return;
        }

        for (Uri histFile : histFiles) {
            if (histFile.getPath() == null) {
                Log.d(TAG, "Null filename");
                ToastHelper.showToastAndLog(this, getString(R.string.strange_file_name));
                return;
            }
        }

        AtomSpectraSpectrogramData.instance.clear();

        loadingSpectrogramCancellationToken = new CancellationToken();
        showSpectrogramLoadingDialog();

        Handler mainHandler = new Handler(Looper.getMainLooper());
        Context context = this;
        new Thread(() -> {
            isLoadingSpectrogram = true;
            mainHandler.post(() -> updateSpectrogramMenu());

            try {
                for (Uri histFile : histFiles) {
                    if (loadingSpectrogramCancellationToken.isCancelled()) {
                        break;
                    }

                    Log.d(TAG, histFile.getPath());
                    SpectrumFileAS spectrumFile = new SpectrumFileAS();
                    spectrumFile.loadSpectrogram(histFile, context, AtomSpectraSpectrogramData.instance,
                            rowCount -> {
                                mainHandler.post(() -> {
                                    if (active && spectrogramLoadingDialog != null) {
                                        spectrogramLoadingDialog.setMessage(getString(R.string.spectrogram_loading_dialog_message, AtomSpectraSpectrogramData.instance.rowCount()));
                                    }
                                });
                            }, loadingSpectrogramCancellationToken);
                }

                if (histFiles.size() > 1) {
                    AtomSpectraSpectrogramData.instance.sortSegmentsRejectOverlap();
                }

                mainHandler.post(() -> showSpectrogramView());
                ToastHelper.showToastAndLog(context, context.getString(R.string.spectrogram_load_success));
            } catch (Exception e) {
                AtomSpectraSpectrogramData.instance.clear();
                AtomSpectraLog.addMessage(context, Log.getStackTraceString(e));
                ToastHelper.showToastAndLog(context, context.getString(R.string.spectrogram_load_error, e.getMessage()));
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
        try {
            saveSpectrumASImpl(suffix);
        } finally {
            endConnectDecisionSaving();
        }
    }

    private void saveSpectrumASImpl(String suffix) {
        Spectrum spectrum = new Spectrum(SpectrumData.instance.foreground);
        if (!sharedPreferences.getBoolean(Constants.CONFIG.CONF_ADD_GPS_TO_FILES, Constants.ADD_GPS_TO_FILES_DEFAULT)) {
            spectrum.setLocation(null).updateComments();
        }

        try {
            Pair<OutputStreamWriter, Uri> streamInfo = SpectrumFile.prepareOutputFileStream(this, getString(R.string.file_atomspectra_spectrum_prefix), spectrum.getSpectrumDate(), suffix, ".txt", "text/plain", false);
            OutputStreamWriter docStream = streamInfo.first;
            String spectrumFileName = streamInfo.second.getPath();

            SpectrumFileAS saveFile = new SpectrumFileAS();
            saveFile.addSpectrum(spectrum)
                    .setChannelCompression(1);
            saveFile.saveSpectrumAndCloseStream(docStream, this);
            SpectrumData.instance.foreground.setChanged(false);
            if (boundService != null) {
                boundService.onScreenSaved();
            }
            ToastHelper.showToastAndLog(this, getString(R.string.hist_save_success, spectrumFileName));
        } catch (Exception e) {
            AtomSpectraLog.addMessage(this, Log.getStackTraceString(e));
            ToastHelper.showToastAndLog(this, getString(R.string.hist_save_error, suffix));
        }
    }

    private void moveToBackground() {
        SpectrumData.instance.background.ReinitializeFrom(SpectrumData.instance.foreground);
        boolean show_back = !SpectrumData.instance.background.isEmpty();
        UIViewState.instance.backgroundShow = show_back;
        app_menu.findItem(R.id.action_background_show).setChecked(show_back);
        app_menu.findItem(R.id.action_background_save).setEnabled(show_back);
        app_menu.findItem(R.id.action_background_show).setEnabled(show_back);
        app_menu.findItem(R.id.action_background_subtract).setEnabled(show_back);
        app_menu.findItem(R.id.action_background_clear).setEnabled(show_back);
        app_menu.findItem(R.id.action_background_suffix).setEnabled(show_back);
        TextView view = findViewById(R.id.backgroundSuffixView);
        view.setVisibility(show_back ? TextView.VISIBLE : TextView.INVISIBLE);
        view.setText(SpectrumData.instance.background.getSuffix());
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
                    ToastHelper.showToastAndLog(this, getString(R.string.error_working_dir_not_valid, workingDir));
                    return;
                }
                DocumentFile dirFile = DocumentFile.fromTreeUri(this, dirUri);
                if ((dirFile == null) || !dirFile.isDirectory()) {
                    AtomSpectraLog.addMessage(this, String.format("Unexpected: working dir path is not a directory '%s'", workingDir));
                    ToastHelper.showToastAndLog(this, getString(R.string.error_working_dir_not_valid, workingDir));
                    return;
                }
                String path = workingDir + "/document/" + Uri.encode(DocumentsContract.getTreeDocumentId(dirUri) + "/Background");
                DocumentFile backgroundFile = DocumentFile.fromSingleUri(this, Uri.parse(path));
                if ((backgroundFile == null) || !backgroundFile.isFile()) {
                    AtomSpectraLog.addMessage(this, String.format("Unexpected: background file '%s' is not a file", path));
                    ToastHelper.showToastAndLog(this, getString(R.string.background_load_error));
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
                    ToastHelper.showToastAndLog(this, getString(R.string.background_load_error));
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
            if (spectrum.getDataArray().length != SpectrumData.instance.getChannelCount()) {
                ToastHelper.showToastAndLog(this, getString(R.string.background_channel_mismatch));
                return;
            }
            SpectrumData.instance.background.ReinitializeFrom(spectrum);
            UIViewState.instance.backgroundSubtract = false;
            boolean show_back = !SpectrumData.instance.background.isEmpty();
            UIViewState.instance.backgroundShow = show_back;
            app_menu.findItem(R.id.action_background_save).setEnabled(show_back);
            app_menu.findItem(R.id.action_background_show).setChecked(show_back);
            app_menu.findItem(R.id.action_background_show).setEnabled(show_back);
            app_menu.findItem(R.id.action_background_subtract).setEnabled(show_back);
            app_menu.findItem(R.id.action_background_subtract).setChecked(false);
            app_menu.findItem(R.id.action_background_clear).setEnabled(show_back);
            app_menu.findItem(R.id.action_background_suffix).setEnabled(show_back);
            TextView view = findViewById(R.id.backgroundSuffixView);
            view.setText(SpectrumData.instance.background.getSuffix());
            view.setVisibility(show_back ? TextView.VISIBLE : TextView.INVISIBLE);
            refreshDisplay();
            Log.d(TAG, "Background is loaded successfully");
            ToastHelper.showToastAndLog(this, getString(R.string.background_load_success));
        } catch (Exception e) {
            AtomSpectraLog.addMessage(this, Log.getStackTraceString(e));
            ToastHelper.showToastAndLog(this, getString(R.string.background_load_error));
        }
    }

    private void saveDefaultBackground() {
        if (SpectrumData.instance.background.isEmpty()) {
            ToastHelper.showToastAndLog(this, getString(R.string.background_no_data));
            return;
        }

        Spectrum spectrum = new Spectrum(SpectrumData.instance.background);

        if (!sharedPreferences.getBoolean(Constants.CONFIG.CONF_ADD_GPS_TO_FILES, Constants.ADD_GPS_TO_FILES_DEFAULT)) {
            spectrum.setLocation(null).updateComments();
        }

        try {
            Pair<OutputStreamWriter, Uri> streamInfo = SpectrumFile.prepareOutputFileStream(this, "Background", spectrum.getSpectrumDate(), "", "", "application/octet-stream", true, false, false, true);
            OutputStreamWriter docStream = streamInfo.first;
            SpectrumFileAS saveFile = new SpectrumFileAS();
            saveFile.
                    addSpectrum(spectrum).
                    setChannelCompression(1);
            saveFile.saveSpectrumAndCloseStream(docStream, this);
            ToastHelper.showToastAndLog(this, getString(R.string.background_save_success));
        } catch (Exception e) {
            AtomSpectraLog.addMessage(this, Log.getStackTraceString(e));
            ToastHelper.showToastAndLog(this, getString(R.string.background_save_error));
        }
    }

    private void saveCSV(String suffix, boolean with_energy) {
        Spectrum spectrum = new Spectrum(SpectrumData.instance.foreground);

        if (!sharedPreferences.getBoolean(Constants.CONFIG.CONF_ADD_GPS_TO_FILES, Constants.ADD_GPS_TO_FILES_DEFAULT)) {
            spectrum.setLocation(null).updateComments();
        }

        try {
            Pair<OutputStreamWriter, Uri> streamInfo = SpectrumFile.prepareOutputFileStream(this, "Export", spectrum.getSpectrumDate(), suffix, ".csv", "text/csv", false);
            OutputStreamWriter docStream = streamInfo.first;
            String spectrumFileName = streamInfo.second.getPath();

            SpectrumFileCSV saveFile = new SpectrumFileCSV();
            saveFile.
                    addSpectrum(spectrum).
                    setChannelCompression(sharedPreferences.getInt(Constants.CONFIG.CONF_EXPORT_COMPRESSION, Constants.EXPORT_COMPRESSION_DEFAULT));
            saveFile.setAddEnergy(with_energy);
            saveFile.saveSpectrumAndCloseStream(docStream, this);
            ToastHelper.showToastAndLog(this, getString(R.string.export_save_success, spectrumFileName));
        } catch (Exception e) {
            AtomSpectraLog.addMessage(this, Log.getStackTraceString(e));
            ToastHelper.showToastAndLog(this, getString(R.string.export_save_error, suffix));
        }
    }

    private void saveBqMoni(String suffix) {
        Spectrum spectrum = new Spectrum(SpectrumData.instance.foreground);
        Spectrum backSpectrum = new Spectrum(SpectrumData.instance.background);

        if (!sharedPreferences.getBoolean(Constants.CONFIG.CONF_ADD_GPS_TO_FILES, Constants.ADD_GPS_TO_FILES_DEFAULT)) {
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
                    .setChannelCompression(sharedPreferences.getInt(Constants.CONFIG.CONF_EXPORT_COMPRESSION, Constants.EXPORT_COMPRESSION_DEFAULT));
            saveFile.saveSpectrumAndCloseStream(docStream, this);
            ToastHelper.showToastAndLog(this, getString(R.string.export_save_success, spectrumFileName));
        } catch (Exception e) {
            AtomSpectraLog.addMessage(this, Log.getStackTraceString(e));
            ToastHelper.showToastAndLog(this, getString(R.string.export_save_error, suffix));
        }
    }

    private void saveSPE(String suffix) {
        Spectrum spectrum = new Spectrum(SpectrumData.instance.foreground);
        if (!sharedPreferences.getBoolean(Constants.CONFIG.CONF_ADD_GPS_TO_FILES, Constants.ADD_GPS_TO_FILES_DEFAULT)) {
            spectrum.setLocation(null).updateComments();
        }

        try {
            Pair<OutputStreamWriter, Uri> streamInfo = SpectrumFile.prepareOutputFileStream(this, "MCA", spectrum.getSpectrumDate(), suffix, ".spe", "application/octet-stream", false);
            OutputStreamWriter docStream = streamInfo.first;
            String spectrumFileName = streamInfo.second.getPath();

            SpectrumFileSPE saveFile = new SpectrumFileSPE();
            saveFile.addSpectrum(spectrum)
                    .setChannelCompression(sharedPreferences.getInt(Constants.CONFIG.CONF_EXPORT_COMPRESSION, Constants.EXPORT_COMPRESSION_DEFAULT));
            saveFile.saveSpectrumAndCloseStream(docStream, this);
            ToastHelper.showToastAndLog(this, getString(R.string.export_save_success, spectrumFileName));
        } catch (Exception e) {
            AtomSpectraLog.addMessage(this, Log.getStackTraceString(e));
            ToastHelper.showToastAndLog(this, getString(R.string.export_save_error, suffix));
        }
    }

    private void saveN42(String suffix) {
        Spectrum spectrum = new Spectrum(SpectrumData.instance.foreground);
        Spectrum backSpectrum = new Spectrum(SpectrumData.instance.background);

        if (!sharedPreferences.getBoolean(Constants.CONFIG.CONF_ADD_GPS_TO_FILES, Constants.ADD_GPS_TO_FILES_DEFAULT)) {
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
                    .setChannelCompression(sharedPreferences.getInt(Constants.CONFIG.CONF_EXPORT_COMPRESSION, Constants.EXPORT_COMPRESSION_DEFAULT));
            saveFile.saveSpectrumAndCloseStream(docStream, this);
            ToastHelper.showToastAndLog(this, getString(R.string.export_save_success, spectrumFileName));
        } catch (Exception e) {
            AtomSpectraLog.addMessage(this, Log.getStackTraceString(e));
            ToastHelper.showToastAndLog(this, getString(R.string.export_save_error, suffix));
        }
    }

    public void shareFile(Uri file) {
//        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US);
        final String filename = file.getPath();
        String onlyName, onlyLoName;
        if (filename == null) {
            ToastHelper.showToastAndLog(this, getString(R.string.strange_file_name));
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
            ToastHelper.showToastAndLog(this, getString(R.string.perm_no_outside));
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
            ToastHelper.showToastAndLog(this, getString(R.string.something_wrong));
        }
    }

    //Save device data
    public void onAddCalibrationPoint(View view) {
//		final Button button = (Button) view;
        if (SpectrumData.instance.newCalibration.getPointsCount() >= Constants.MAX_CALIBRATION_POINTS) {
            ToastHelper.showToastAndLog(this, getString(R.string.cal_no_more));
            return;
        }
        if (SpectrumData.instance.newCalibration.containsPointChannel(UIViewState.instance.cursorX)) {
            ToastHelper.showToastAndLog(this, getString(R.string.cal_have_channel));
            return;
        }
        if ((UIViewState.instance.cursorX >= 0) && (UIViewState.instance.cursorX < SpectrumData.instance.getChannelCount())) {
            final AlertDialog.Builder alert = new AlertDialog.Builder(this);
            alert.setTitle(getString(R.string.cal_point_title, UIViewState.instance.cursorX));
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
                    ToastHelper.showToastAndLog(context, getString(R.string.cal_error_number));
                    return;
                }
                SpectrumData.instance.newCalibration.addPoint(UIViewState.instance.cursorX, fValue);
                if (SpectrumData.instance.newCalibration.getPointsCount() > 1) {
                    SpectrumData.instance.newCalibration.Calculate(sharedPreferences.getInt(Constants.CONFIG.CONF_MAX_POLI_FACTOR, Constants.DEFAULT_POLI_FACTOR));
                    app_menu.findItem(R.id.action_cal_draw_function).setEnabled(true);
                    if (!SpectrumData.instance.newCalibration.isCorrect()) {
                        ToastHelper.showToastAndLog(this, getString(R.string.cal_maybe_wrong, UIViewState.instance.cursorX, fValue));
                    }
                }
                updateCalibrationMenu();
                showCursorInfo(true);
            });
            alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
            });
            alert.show();
        } else
            ToastHelper.showToastAndLog(this, getString(R.string.put_cursor_first));
    }

    public void onCalibrateButton(View view) {
        if (SpectrumData.instance.newCalibration.getPointsCount() < 2) {
            ToastHelper.showToastAndLog(this, getString(R.string.cal_no_enough_data));
            return;
        }
        SpectrumData.instance.newCalibration.Calculate(sharedPreferences.getInt(Constants.CONFIG.CONF_MAX_POLI_FACTOR, Constants.DEFAULT_POLI_FACTOR));
        if (SpectrumData.instance.newCalibration.isCorrect()) {
            UIViewState.instance.showCalibrationFunction = false;
            app_menu.findItem(R.id.action_cal_draw_function).setChecked(false);
            app_menu.findItem(R.id.action_cal_draw_function).setEnabled(false);
            Calibration calibration = SpectrumData.instance.newCalibration;
            SpectrumData.instance.newCalibration = new Calibration(SpectrumData.instance.getChannelCount());
            SpectrumData.instance.applyCalibration(this, calibration);
            Button button = findViewById(R.id.addCalibrationPointButton);
            button.setText("1");
            button.setEnabled(true);
            ToastHelper.showToastAndLog(this, getString(R.string.cal_applied));
        } else {
            ToastHelper.showToastAndLog(this, getString(R.string.cal_load_error));
        }
    }

    public void onClearCalibrationButton(View view) {
        UIViewState.instance.showCalibrationFunction = false;
        app_menu.findItem(R.id.action_cal_draw_function).setChecked(false);
        app_menu.findItem(R.id.action_cal_draw_function).setEnabled(false);
        SpectrumData.instance.newCalibration.clear();
        updateCalibrationMenu();
        refreshDisplay();
    }


    private void requestDirectory(int dir_code) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, dir_code);
    }

    private static final int INPUT_WAITING_ALPHA = 110;

    private void updateSelectedInputIndicator() {
        ImageButton inputType = findViewById(R.id.inputTypeButton);
        final AtomSpectraService.DeviceState state = AtomSpectraService.deviceState();

        int baseRes;
        switch (AtomSpectraService.lockedSourceType()) {
            case SpectrumSource.TYPE_SPECTRA_PRO:
                baseRes = R.drawable.input_usb;
                break;
            case SpectrumSource.TYPE_AUDIO:
                baseRes = R.drawable.input_mic;
                break;
            case SpectrumSource.TYPE_BLUZ:
                baseRes = R.drawable.input_bt;
                break;
            default:
                baseRes = R.drawable.input_none;
                break;
        }
        Drawable icon = ContextCompat.getDrawable(this, baseRes);
        if (icon != null) {
            icon = icon.mutate();
            icon.setAlpha(state == AtomSpectraService.DeviceState.WAITING ? INPUT_WAITING_ALPHA : 255);
            Drawable badge = inputStateBadge(state);
            if (badge != null) {
                float density = getResources().getDisplayMetrics().density;
                int inputLeftMargin = Math.round(4 * density);
                int badgeRightMargin = Math.round(8 * density);
                int gap = Math.round(4 * density); // adjusts the status badge's horizontal spacing
                int badgeTop = (icon.getIntrinsicHeight() - badge.getIntrinsicHeight()) / 2;
                LayerDrawable indicator = new LayerDrawable(new Drawable[]{icon, badge});
                indicator.setLayerInset(0, inputLeftMargin, 0, inputType.getWidth() / 2 - inputLeftMargin, 0);
                indicator.setLayerInset(1, inputType.getWidth() / 2 + gap, badgeTop,
                        badgeRightMargin,
                        icon.getIntrinsicHeight() - badge.getIntrinsicHeight() - badgeTop);
                icon = indicator;
            }
            inputType.setImageDrawable(icon);
        }

        final String info = userFacingDeviceName();
        switch (state) {
            case WAITING:
                inputType.setContentDescription(getString(R.string.device_waiting_description, info));
                break;
            case IDLE:
                inputType.setContentDescription(getString(R.string.device_idle_description, info));
                break;
            case RECORDING:
                inputType.setContentDescription(getString(R.string.device_recording_description, info));
                break;
            case BUSY:
                inputType.setContentDescription(getString(R.string.device_busy_description, info));
                break;
            case ERROR:
                inputType.setContentDescription(getString(R.string.device_error_description, info));
                break;
            default:
                inputType.setContentDescription(info);
                break;
        }

        boolean canSwitch = canSwitchDevice();
        inputType.setEnabled(canSwitch);
        inputType.setClickable(canSwitch);
        inputType.setOnClickListener(canSwitch ? v -> onClickInputType() : null);
    }

    private Drawable inputStateBadge(AtomSpectraService.DeviceState state) {
        switch (state) {
            case WAITING:
                return ContextCompat.getDrawable(this, R.drawable.badge_waiting);
            case IDLE:
                return ContextCompat.getDrawable(this, R.drawable.badge_idle);
            case RECORDING:
                return ContextCompat.getDrawable(this, R.drawable.badge_recording);
            case BUSY:
                return ContextCompat.getDrawable(this, R.drawable.badge_busy);
            case ERROR:
                return ContextCompat.getDrawable(this, R.drawable.badge_error);
            default:
                return null;
        }
    }

    // an error or a pending decision has something to offer besides switching the device
    private void onClickInputType() {
        if (AtomSpectraService.isConnectDecisionPending()) {
            syncConnectDecisionDialog();
        } else if (AtomSpectraService.deviceState() == AtomSpectraService.DeviceState.ERROR) {
            showDeviceStatusDialog();
        } else {
            openDeviceSelection(false);
        }
    }

    private String userFacingDeviceName() {
        switch (AtomSpectraService.lockedSourceType()) {
            case SpectrumSource.TYPE_AUDIO:
                return getString(R.string.device_type_audio);
            case SpectrumSource.TYPE_SPECTRA_PRO:
                return getString(R.string.device_type_spectra_pro);
            case SpectrumSource.TYPE_BLUZ:
                return getString(R.string.device_type_bluz);
            default:
                return getString(R.string.device_none_selected_notification);
        }
    }

    private void showDeviceStatusDialog() {
        final SourceError error = AtomSpectraService.deviceError();

        StringBuilder message = new StringBuilder(userFacingDeviceName());
        if (error != null && error.text != null) {
            message.append("\n\n").append(error.text);
        }

        if (error != null && error.isTerminal()) {
            DialogHelper.showStackedActions(this,
                    getString(R.string.device_status_title),
                    message.toString(),
                    true,
                    new DialogHelper.StackedAction(getString(R.string.device_status_retry), () -> {
                        if (boundService != null) boundService.retryConnect();
                    }),
                    new DialogHelper.StackedAction(getString(R.string.device_status_select_other),
                            () -> openDeviceSelection(false)),
                    new DialogHelper.StackedAction(getString(R.string.dialog_cancel_button), null));
            return;
        }
        DialogHelper.showStackedActions(this,
            getString(R.string.device_status_title),
            message.toString(),
            true,
            new DialogHelper.StackedAction(getString(R.string.device_status_select_other),
                () -> openDeviceSelection(false)),
            new DialogHelper.StackedAction(getString(R.string.dialog_cancel_button), null));
    }

    private AlertDialog connectDecisionDialog = null;
    // the save started from the decision dialog is under way: the decision dialog stays hidden until it ends
    private boolean connectDecisionSaving = false;
    // runs when the save flow ends with the screen spectrum saved
    private Runnable afterSaveAction = null;

    // the end of every save flow, saved or cancelled
    private void endConnectDecisionSaving() {
        final Runnable afterSave = afterSaveAction;
        afterSaveAction = null;
        if (afterSave != null && !SpectrumData.instance.foreground.isChanged()) {
            afterSave.run();
        }
        if (!connectDecisionSaving) return;
        connectDecisionSaving = false;
        syncConnectDecisionDialog();
    }

    // a connected device waits to show its own spectrum while the screen has unsaved data: the dialog stays until the user decides
    private void syncConnectDecisionDialog() {
        if (!AtomSpectraService.isConnectDecisionPending()) {
            if (connectDecisionDialog != null) {
                connectDecisionDialog.dismiss();
                connectDecisionDialog = null;
            }
            return;
        }
        if (boundService == null || !active || connectDecisionDialog != null || connectDecisionSaving)
            return;

        connectDecisionDialog = DialogHelper.showStackedActions(this,
                getString(R.string.device_connect_decision_title),
                getString(R.string.device_connect_decision_text, userFacingDeviceName()),
                false,
                new DialogHelper.StackedAction(getString(R.string.device_decision_discard_switch), () -> {
                    connectDecisionDialog = null;
                    boundService.discardScreenForDevice();
                }),
                new DialogHelper.StackedAction(getString(R.string.device_decision_save), () -> {
                    connectDecisionDialog = null;
                    connectDecisionSaving = true;
                    startSavingSpectrum();
                }),
                new DialogHelper.StackedAction(getString(R.string.device_decision_go_offline), () -> {
                    connectDecisionDialog = null;
                    boundService.stopAndGoOffline();
                }));
    }

    // the regular save flow (folder, suffix); the service hears about it when the spectrum is saved
    private void startSavingSpectrum() {
        if (app_menu != null) {
            onOptionsItemSelected(app_menu.findItem(R.id.action_hist_to_file));
        } else {
            endConnectDecisionSaving();
        }
    }

    private void startRecording(int foreignSpectrumPolicy) {
        sendBroadcast(new Intent(Constants.ACTION.ACTION_START_RECORDING)
                .setPackage(Constants.PACKAGE_NAME)
                .putExtra(Constants.ACTION_PARAMETERS.START_FOREIGN_SPECTRUM, foreignSpectrumPolicy));
        ((TextView) findViewById(R.id.suffixView)).setText(SpectrumData.instance.foreground.getSuffix());
    }

    // the screen spectrum was not produced by the device: recording starts once the user says what happens to it
    private void startRecordingAfterDecision() {
        final int decision = boundService != null ? boundService.startDecision() : AtomSpectraService.START_FREE;
        if (decision == AtomSpectraService.START_FREE) {
            startRecording(Constants.ACTION_PARAMETERS.FOREIGN_SPECTRUM_UNDECIDED);
            return;
        }

        final DialogHelper.StackedAction cancel =
                new DialogHelper.StackedAction(getString(R.string.dialog_cancel_button), null);
        switch (decision) {
            case AtomSpectraService.START_CONTINUE_OR_NEW:
                DialogHelper.showStackedActions(this,
                        getString(R.string.dialog_confirm_title),
                        getString(R.string.start_continue_or_new_text),
                        true,
                        new DialogHelper.StackedAction(getString(R.string.start_continue_button), () ->
                                startRecording(Constants.ACTION_PARAMETERS.FOREIGN_SPECTRUM_CONTINUE)),
                        new DialogHelper.StackedAction(getString(R.string.start_new_button), () ->
                                startRecording(Constants.ACTION_PARAMETERS.FOREIGN_SPECTRUM_REPLACE)),
                        cancel);
                break;
            case AtomSpectraService.START_CONTINUE_OR_DISCARD:
                DialogHelper.showStackedActions(this,
                        getString(R.string.dialog_confirm_title),
                        getString(R.string.start_continue_or_discard_text),
                        true,
                        new DialogHelper.StackedAction(getString(R.string.start_continue_button), () ->
                                startRecording(Constants.ACTION_PARAMETERS.FOREIGN_SPECTRUM_CONTINUE)),
                        new DialogHelper.StackedAction(getString(R.string.start_discard_button), () ->
                                startRecording(Constants.ACTION_PARAMETERS.FOREIGN_SPECTRUM_REPLACE)),
                        cancel);
                break;
            default:
                DialogHelper.showStackedActions(this,
                        getString(R.string.dialog_confirm_title),
                        getString(R.string.start_save_or_discard_text),
                        true,
                        new DialogHelper.StackedAction(getString(R.string.start_save_button), this::startSavingSpectrum),
                        new DialogHelper.StackedAction(getString(R.string.start_discard_button), () ->
                                startRecording(Constants.ACTION_PARAMETERS.FOREIGN_SPECTRUM_REPLACE)),
                        cancel);
                break;
        }
    }

    private void showSpectrogramView() {
        Intent intent_spectrogram = new Intent(this, AtomSpectraSpectrogram.class);
        startActivity(intent_spectrogram);
    }

    private void updateSpectrogramMenu() {
        if (app_menu != null) {
            app_menu.findItem(R.id.action_spectrogram_load).setEnabled(!AtomSpectraService.isRecording() && !isLoadingSpectrogram);
            updateMapMenu();
        }
    }

    private void updateMapMenu() {
        if (app_menu == null) {
            return;
        }
        app_menu.findItem(R.id.action_hist_view_map)
                .setEnabled(MapHelper.hasValidSpectrumLocation(SpectrumData.instance.foreground));
        app_menu.findItem(R.id.action_spectrogram_view_map)
                .setEnabled(AtomSpectraSpectrogramData.instance.hasLocatedRows());
    }

    private void updateVersionInMenu() {
        if (app_menu != null) {
            String testSuffix = "";
            AtomSpectraHelp.VersionInfo versionInfo = AtomSpectraHelp.getVersionInfo(this);
            app_menu.findItem(R.id.action_app_version).setTitle("Ver. " + versionInfo.version + "." + versionInfo.verCode + testSuffix);
        }
    }

    private void updateRecordStatusMenu() {
        if (app_menu != null) {
            // without a usable device the record action opens the selection screen instead of being hidden
            app_menu.findItem(R.id.action_switch_device).setVisible(canSwitchDevice());
            boolean recordingPaused = !AtomSpectraService.isRecording();
            if (recordingPaused) {
                app_menu.findItem(R.id.action_record_toggle).setIcon(R.drawable.record);
                app_menu.findItem(R.id.action_record_toggle).setTitle(R.string.hist_continue_update);
            } else {
                app_menu.findItem(R.id.action_record_toggle).setIcon(R.drawable.menu_block);
                app_menu.findItem(R.id.action_record_toggle).setTitle(R.string.hist_pause_update);
            }
            app_menu.findItem(R.id.action_record_toggle)
                    .setEnabled(AtomSpectraService.deviceState() != AtomSpectraService.DeviceState.BUSY);
            app_menu.findItem(R.id.action_hist_from_file).setEnabled(recordingPaused);
            app_menu.findItem(R.id.action_hist_add_from_file).setEnabled(recordingPaused);
        }
    }

    private void setXCalibrated(Boolean newXCalibrated) {
        UIViewState.instance.energyAxis = newXCalibrated;
        Button keVOrChannelButton = findViewById(R.id.keVOrChannelButton);
        CharSequence text = newXCalibrated
                ? getText(R.string.mode_axis_kev_button)
                : getText(R.string.mode_axis_ch_button);
        keVOrChannelButton.setText(text);
    }

    private void applyDisplayDoseButton(String mode) {
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
                UIViewState.instance.displayDose = Constants.DISPLAY_DOSE_DEFAULT;
                applyDisplayDoseButton(Constants.DISPLAY_DOSE_DEFAULT);
                break;
        }
    }

    private AlertDialog recordingSuspendedAlert = null;
    private long recordingSuspendedDialogEpisode = 0;

    private void showRecordingSuspendedDialog(long episode) {
        String message = getString(R.string.recording_suspended_dialog_message);
        message += "\n" + AtomSpectraService.formatLocalTimeAsISOLikeString(AtomSpectraService.recordingSuspendedAt);
        recordingSuspendedDialogEpisode = episode;
        recordingSuspendedAlert = DialogHelper.showStackedActions(this,
                getString(R.string.recording_suspended_dialog_title),
                message,
                false,
                new DialogHelper.StackedAction(getString(R.string.recording_suspended_dialog_wait), () -> {
                    AtomSpectraService.acknowledgeRecordingSuspension(episode);
                    dismissRecordingSuspendedDialog();
                    checkRecordingSuspended();
                }),
                new DialogHelper.StackedAction(getString(R.string.recording_suspended_dialog_dismiss), () -> {
                    // stop recording and release the device; the loaded spectrum and the remembered device stay
                    if (boundService != null) {
                        boundService.stopAndGoOffline();
                    }
                    dismissRecordingSuspendedDialog();
                }));
    }

    private void dismissRecordingSuspendedDialog() {
        if (recordingSuspendedAlert != null) {
            recordingSuspendedAlert.dismiss();
            recordingSuspendedAlert = null;
        }
        recordingSuspendedDialogEpisode = 0;
    }

    private void checkRecordingSuspended() {
        long episode = AtomSpectraService.pendingRecordingSuspensionEpisode();
        if (recordingSuspendedAlert != null && recordingSuspendedDialogEpisode != episode) {
            dismissRecordingSuspendedDialog();
        }
        if (AtomSpectraService.isStarted && episode != 0 && active && recordingSuspendedAlert == null) {
            showRecordingSuspendedDialog(episode);
        }
    }

    private void setDisplayMode(int displayMode) {
        UIViewState.instance.displayMode = displayMode;
        updateDisplayModeViews();
        refreshDisplay();
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

        switch (UIViewState.instance.displayMode) {
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
                AtomSpectraLog.addMessage(this, "ERROR: [AtomSpectraActivity] Unknown display mode: " + UIViewState.instance.displayMode);
                setDisplayMode(Constants.DISPLAY_MODE_DEFAULT);
                break;
        }
    }

    private void hideViews(View... views) {
        if (views != null) {
            for (View view : views) {
                view.setVisibility(View.INVISIBLE);
            }
        }
    }

    private void showViews(View... views) {
        if (views != null) {
            for (View view : views) {
                view.setVisibility(View.VISIBLE);
            }
        }
    }

    private void removeTextHighlight(TextView... views) {
        if (views != null) {
            for (TextView view : views) {
                view.setPaintFlags(view.getPaintFlags() & (~Paint.UNDERLINE_TEXT_FLAG) & (~Paint.FAKE_BOLD_TEXT_FLAG));
                view.setTextColor(Color.WHITE);
            }
        }
    }

    private void setHighlightedText(TextView... views) {
        if (views != null) {
            for (TextView view : views) {
                view.setPaintFlags(view.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG | Paint.FAKE_BOLD_TEXT_FLAG);
                view.setTextColor(Color.CYAN);
            }
        }
    }

    private boolean isSpectrumDisplayMode() {
        int displayMode = UIViewState.instance.displayMode;
        return displayMode == Constants.DISPLAY_MODE_SPECTRUM || displayMode == Constants.DISPLAY_MODE_SPECTRUM_CHANGE;
    }
}

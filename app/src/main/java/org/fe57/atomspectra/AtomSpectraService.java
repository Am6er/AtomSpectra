package org.fe57.atomspectra;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.graphics.drawable.IconCompat;
import androidx.core.util.Pair;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedList;
import java.util.Locale;
import java.util.TimeZone;
import java.util.Timer;
import java.util.TimerTask;

public class AtomSpectraService extends Service {
    private final static String TAG = AtomSpectraService.class.getSimpleName();

    private static final int FOREGROUND_PROCESS_ID = 1;


    //Output sound to the speaker
    public static boolean intervalSearchAlarmEnabled = false;
    private static int outputSoundID = -1;
    private static String outputSoundName = null;

    private static final Object intervalSearchAlarmSync = new Object();
    private static AudioTrack intervalSearchAlarmAudioTrack = null;
    private static final int intervalSearchAlarmAudioTrackSampleRate = 44100;
    private static final double intervalSearchAlarmDuration = 0.5; // seconds
    private static float intervalSearchAlarmVolume = Constants.ALARM_VOLUME_DEFAULT;
    private static int intervalSearchAlarmDetectionLevel = Constants.ALARM_DETECTION_LEVEL_DEFAULT; // number of sigmas
    private static final int intervalSearchLowFreq = 500;
    private static final int intervalSearchBaseFreq = 1000;
    private static final int intervalSearchHighFreq = 1500;
    private static final AlarmBaseline intervalSearchAlarmBaseline = new AlarmBaseline();


    private boolean addGPS = false;


    private static int delta_time = Constants.DEFAULT_DELTA_TIME;
    private static int delta_back_time_ratio = 4;
    public static boolean isStarted = false;

    // atom swift integration
    private static boolean sendDataToAtomSwiftAppEnabled = false;
    private static String atomSwiftDRType = Constants.ATOMSWIFT_DR_DEFAULT;
    private static int atomSwiftIntermediateCps = 0;
    private static boolean atomSwiftHasIntermediateData = false;

    private static final LinkedList<long[]> histogram_all_queue = new LinkedList<long[]>();      //array to store delta window
    private static boolean is_recording = false;

    private int skippedIncompleteHistogramCount = 0;

    private final Object spgAutosaveSync = new Object();
    private static int spgInterval = 0;
    private static boolean spgMidnightReset = false;
    private Spectrum spgAutosaveSpectrum = null;
    private Uri spgAutosaveFilePath = null;
    private Date spgAutosaveFileCreated = null;

    private final int mutabilityFlag = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ? PendingIntent.FLAG_IMMUTABLE : 0;

    // sent by AtomSpectraService when recording is suspended/resumed due to hardware issues (disconnects)
    public final static String ACTION_RECORDING_SUSPENDED =
            "org.fe57.atomspectra.ACTION_RECORDING_SUSPENDED";
    public final static String ACTION_RECORDING_RESUMED =
            "org.fe57.atomspectra.ACTION_RECORDING_RESUMED";

    // sent by AtomSpectraService each time an incomplete histogram is skipped (strict mode)
    public final static String ACTION_HISTOGRAM_SKIPPED =
            "org.fe57.atomspectra.ACTION_HISTOGRAM_SKIPPED";
    // number of incomplete histograms skipped since recording started (int)
    public final static String EXTRA_DATA_INT_HISTOGRAM_SKIPPED_COUNT =
            "org.fe57.atomspectra.EXTRA_DATA_INT_HISTOGRAM_SKIPPED_COUNT";


    public final static String CHANNEL_ID = "AtomSpectraService";


    // active dose-rate sensitivity profile: the custom profile loaded from preferences, or a built-in
    // constant. Sensitivities are absolute pSv/count. Binning edges and per-bin values are read
    // directly from this profile (see getEnergyBinIndex and doseRateSearch).
    private static SensitivityProfile activeProfile = SensitivityProfile.builtInById(SensitivityProfile.ID_DEFAULT);

    private static int SearchFSM = 0; //0 - fast, 1 - medium, 2 - slow (shared selector for both dose rates)


    public static String inputDeviceInfo = "";          // current device info
    // TODO: verify it actually requires sync
    private static final Object inputSync = new Object();

    private static final Object recordingSuspendedSync = new Object();
    public static Date recordingSuspendedAt = null;
    public static Date recordingResumedAt = null;
    public static int recordingSuspendSourceType = SpectrumSource.TYPE_NONE;
    public static final int RECORDING_SUSPEND_REASON_NONE = 0;
    public static final int RECORDING_SUSPEND_REASON_AUDIO_REMOVED = 1;
    public static final int RECORDING_SUSPEND_REASON_BT_DISCONNECT = 2;
    public static final int RECORDING_SUSPEND_REASON_USB_DISCONNECT = 3;
    public static int recordingSuspendReason = RECORDING_SUSPEND_REASON_NONE;
    public static boolean isRecordingSuspended = false;
    private static long recordingSuspensionEpisode = 0;
    private static boolean recordingSuspensionAcknowledged = false;

    public static long pendingRecordingSuspensionEpisode() {
        synchronized (recordingSuspendedSync) {
            return isRecordingSuspended && !recordingSuspensionAcknowledged ? recordingSuspensionEpisode : 0;
        }
    }

    public static void acknowledgeRecordingSuspension(long episode) {
        synchronized (recordingSuspendedSync) {
            if (isRecordingSuspended && recordingSuspensionEpisode == episode) {
                recordingSuspensionAcknowledged = true;
            }
        }
    }

    /**
     * The device the user selected; survives a disconnect, cleared by a switch or by going offline.
     */
    static final class LockedDevice {
        final int type;         // SpectrumSource.TYPE_*
        final String identity;  // see DeviceIdentity
        final String name;

        LockedDevice(int type, String identity, String name) {
            this.type = type;
            this.identity = identity;
            this.name = name;
        }
    }

    /**
     * Which device the session works with. Whether that device is there is {@link #deviceState()}.
     */
    public enum DeviceSessionState {
        UNSELECTED,             // no device has been chosen; the user has to decide
        LOCKED,                 // a device is chosen and held by a source, present or not
        OFFLINE                 // the user opted out of hardware; files only
    }

    /**
     * What the user sees of the locked device; derived, never stored.
     */
    public enum DeviceState {
        NONE,                   // nothing is locked
        WAITING,                // locked, the device is absent
        IDLE,                   // connected, not collecting
        RECORDING,              // connected and collecting
        BUSY,                   // connecting or executing a command
        ERROR                   // a failed connect, or the last command failed
    }

    public static volatile DeviceSessionState sessionState = DeviceSessionState.UNSELECTED;

    // the user picked a device in the selection screen and the result is not reported yet
    private static volatile boolean selectionPending = false;
    // the locked source has not reported ready yet: its first ready is a connect, not a return
    private static volatile boolean firstConnectPending = false;
    // the source reported ready and has not reported a loss since
    private static volatile boolean deviceReady = false;
    // mirrors of the locked source, updated from its replies
    private static volatile int deviceStatus = SpectrumSource.STATUS_DISCONNECTED;
    private static volatile SourceError deviceError = null;
    // the device data on the screen is the only thing that may replace it without asking: set when a device writes
    // to the screen, cleared when a file or another device takes it over
    private static volatile boolean screenFromDevice = false;
    // a connected device that holds data of its own waits for the user to decide about the unsaved screen spectrum;
    // its data is ignored and recording cannot start until then
    private static volatile boolean connectDecisionPending = false;
    // whether the pending decision belongs to the first connect (device calibration applies) or to a return
    private static volatile boolean connectDecisionFirstConnect = false;
    private static volatile LockedDevice lockedDevice = null;
    // the channel count the locked source reported with its first ready, 0 until then
    private static volatile int deviceChannelCount = 0;
    // a frame of a wrong length was already reported for the locked device
    private static volatile boolean wrongFrameLogged = false;

    /**
     * True while a device chosen in the selection screen is still being connected.
     */
    public static boolean isSelectionPending() {
        return selectionPending;
    }

    /**
     * The locked device is present and has finished its hand-shake.
     */
    public static boolean isDeviceConnected() {
        return sessionState == DeviceSessionState.LOCKED && deviceReady;
    }

    public static DeviceState deviceState() {
        if (sessionState != DeviceSessionState.LOCKED) {
            return DeviceState.NONE;
        }
        SourceError error = deviceError;
        if (error != null && error.isTerminal()) {
            return DeviceState.ERROR;
        }
        if (!deviceReady) {
            return deviceStatus == SpectrumSource.STATUS_CONNECTING ? DeviceState.BUSY : DeviceState.WAITING;
        }
        switch (deviceStatus) {
            case SpectrumSource.STATUS_CONNECTED_COLLECTING:
                return DeviceState.RECORDING;
            case SpectrumSource.STATUS_CONNECTED_EXECUTING_COMMAND:
                return DeviceState.BUSY;
            case SpectrumSource.STATUS_CONNECTED_COMMAND_FAILED:
                return DeviceState.ERROR;
            default:
                return DeviceState.IDLE;
        }
    }

    /**
     * The last error of the locked device, null when there is none.
     */
    public static SourceError deviceError() {
        return deviceError;
    }

    /**
     * A connected device that holds data of its own waits for the user's decision about the unsaved screen spectrum.
     */
    public static boolean isConnectDecisionPending() {
        return connectDecisionPending;
    }

    // what the user has to decide before recording starts, see startDecision()
    public static final int START_FREE = 0;                 // nothing to ask
    public static final int START_CONTINUE_OR_NEW = 1;      // a saved spectrum the device can continue
    public static final int START_CONTINUE_OR_DISCARD = 2;  // an unsaved spectrum the device can continue
    public static final int START_SAVE_OR_DISCARD = 3;      // an unsaved spectrum the device cannot continue

    /**
     * SpectrumSource.TYPE_* of the locked device; stays set while the device is absent, TYPE_NONE when nothing is locked.
     */
    public static int lockedSourceType() {
        LockedDevice locked = lockedDevice;
        return locked == null ? SpectrumSource.TYPE_NONE : locked.type;
    }

    static LockedDevice selectedDevice() {
        return lockedDevice;
    }

    private static String lockedSourceName(String audio, String usb, String bluetooth, String none) {
        switch (lockedSourceType()) {
            case SpectrumSource.TYPE_AUDIO:
                return audio;
            case SpectrumSource.TYPE_SPECTRA_PRO:
                return usb;
            case SpectrumSource.TYPE_BLUZ:
                return bluetooth;
            default:
                return none;
        }
    }

    private SharedPreferences sp;

    // The one source the service holds; non-null exactly while a device is locked.
    private SpectrumSource activeSource = null;

    private GPSLocator Locator = null;

    private static volatile DeviceLocationSnapshot deviceLocationSnapshot =
            DeviceLocationSnapshot.UNAVAILABLE;

    /**
     * Immutable copy of the latest map-facing device position.
     */
    @NonNull
    public static DeviceLocationSnapshot getDeviceLocationSnapshot() {
        return deviceLocationSnapshot;
    }

    private void publishDeviceLocationSnapshot() {
        DeviceLocationSnapshot snapshot = DeviceLocationSnapshot.capture(this, Locator);
        deviceLocationSnapshot = snapshot;
        sendBroadcast(snapshot.toBroadcastIntent());
    }

    public static AlarmBaseline getIntervalSearchAlarmBaseline() {
        return intervalSearchAlarmBaseline;
    }


    public void onCreate() {
        super.onCreate();
        Start(this);
        Log.d(TAG, "onCreate");
    }

    public void onDestroy() {
        Log.d(TAG, "onDestroy");
        super.onDestroy();
        Stop();
        DeleteSpc();
        service_context = null;
        SpectrumData.instance.reset(Constants.DEFAULT_CHANNEL_COUNT);
        UIViewState.instance.backgroundShow = false;
        is_recording = false;
        sp.unregisterOnSharedPreferenceChangeListener(onSharedPreferenceChangeListener);
        isStarted = false;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // service_context must be defined here, method is called after Start()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    getStringOrDefaultLocale(R.string.app_channel),
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null)
                manager.createNotificationChannel(serviceChannel);
        }
        refreshServiceNotification();
        Notification notification = createNewServiceNotification();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Type is computed by the activity from currently granted permissions and passed
                // in; fall back to deriving it here if the service was (re)started without it.
                int fgsType = (intent != null && intent.hasExtra(Constants.ACTION_PARAMETERS.FGS_TYPE))
                        ? intent.getIntExtra(Constants.ACTION_PARAMETERS.FGS_TYPE, 0)
                        : AppPermissions.foregroundServiceType(this);
                startForeground(FOREGROUND_PROCESS_ID, notification, fgsType);
            } else {
                startForeground(FOREGROUND_PROCESS_ID, notification);
            }
        } catch (Exception e) {
            // e.g. ForegroundServiceStartNotAllowedException / SecurityException when the granted
            // permissions do not cover the requested FGS type. Don't crash the app over it.
            Log.e(TAG, "startForeground failed", e);
            showToastInMainLooper(getStringOrDefaultLocale(R.string.log_foreground_start_failed, String.valueOf(e.getMessage())), Toast.LENGTH_LONG);
        }
        if (isStarted)
            return START_NOT_STICKY;
        notifyDataAvailable();

        updateMenu();
        isStarted = true;
        restoreDeviceChoice();
        return START_NOT_STICKY;
    }

    private Notification createNewServiceNotification() {
        String notifyString = "";
        if (isRecordingSuspended) {
            notifyString = getStringOrDefaultLocale(R.string.recording_suspended_notification).toUpperCase();
        } else if (deviceState() == DeviceState.ERROR) {
            notifyString = getStringOrDefaultLocale(R.string.device_error_notification);
        } else if (deviceState() == DeviceState.WAITING) {
            notifyString = getStringOrDefaultLocale(R.string.device_waiting_notification);
        } else if (sessionState == DeviceSessionState.UNSELECTED) {
            notifyString = getStringOrDefaultLocale(R.string.device_none_selected_notification);
        } else if (is_recording) {
            if (lockedSourceType() == SpectrumSource.TYPE_AUDIO) {
                notifyString = getStringOrDefaultLocale(R.string.app_bar_audio_action);
            }
            if (lockedSourceType() == SpectrumSource.TYPE_SPECTRA_PRO) {
                notifyString = getStringOrDefaultLocale(R.string.app_bar_usb_action);
            }
            if (lockedSourceType() == SpectrumSource.TYPE_BLUZ) {
                notifyString = getStringOrDefaultLocale(R.string.app_bar_bluetooth_action);
            }
            notifyString += " " + getStringOrDefaultLocale(R.string.app_bar_spectrum_update);
        } else {
            notifyString = getStringOrDefaultLocale(R.string.app_bar_pause);
        }

        if (recordingSuspendedAt != null) {
            notifyString += "\n" + getStringOrDefaultLocale(R.string.recording_suspended_notification_suspended_at, formatLocalTimeAsISOLikeString(recordingSuspendedAt));

            if (recordingResumedAt != null && recordingResumedAt.getTime() > recordingSuspendedAt.getTime()) {
                notifyString += "\n" + getStringOrDefaultLocale(R.string.recording_suspended_notification_resumed_at, formatLocalTimeAsISOLikeString(recordingResumedAt));
            }
        }

        Intent notificationIntent = new Intent(this, AtomSpectra.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(this,
                0, notificationIntent, mutabilityFlag);
        Notification notification;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent exitIntent = new Intent(Constants.ACTION.ACTION_STOP_FOREGROUND).setPackage(Constants.PACKAGE_NAME);
            PendingIntent exitPendingIntent =
                    PendingIntent.getBroadcast(this, 0, exitIntent, mutabilityFlag);

            notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(notifyString))
                    .setContentText(notifyString)
                    .setSmallIcon(R.drawable.logo_atom)
                    .setColor(0xFFFFA629)
                    .setContentIntent(pendingIntent)
                    .addAction(new NotificationCompat.Action.Builder(IconCompat.createWithResource(this, android.R.drawable.ic_delete), getStringOrDefaultLocale(R.string.action_exit), exitPendingIntent).build())
                    .build();
        } else {
            notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(notifyString))
                    .setContentText(notifyString)
                    .setSmallIcon(R.drawable.logo_atom)
                    .setColor(0xFFFFA629)
                    .setContentIntent(pendingIntent)
                    .build();
        }

        return notification;
    }

    private final SharedPreferences.OnSharedPreferenceChangeListener onSharedPreferenceChangeListener = (sharedPreferences, s) -> {
        loadSettings();
        postToInputThread(() -> {
            final SpectrumSource source;
            synchronized (inputSync) {
                source = activeSource;
            }
            if (source != null) source.onAppPreferencesChanged();
        });
    };

    private void loadSettings() {
        // dose-rate sensitivity (pSv/count) comes from the active profile, loaded further below
        delta_time = sp.getInt(Constants.CONFIG.CONF_SPECTRUM_CHANGE_DIFF_TIME, Constants.DEFAULT_DELTA_TIME);
        SearchFSM = sp.getInt(Constants.CONFIG.CONF_SEARCH_MODE, 0);
        spgInterval = sp.getInt(Constants.CONFIG.CONF_SPG_DELTA_DURATION, Constants.SPG_INTERVAL_DEFAULT);
        spgMidnightReset = sp.getBoolean(Constants.CONFIG.CONF_SPG_MIDNIGHT_RESET, Constants.SPG_MIDNIGHT_RESET_DEFAULT);
        // --- interval search
        boolean intervalSearchAlarmEnabledPrev = intervalSearchAlarmEnabled;
        intervalSearchAlarmEnabled = sp.getBoolean(Constants.CONFIG.CONF_OUTPUT_SOUND, false) && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M);
        outputSoundID = sp.getInt(Constants.CONFIG.CONF_OUTPUT_SOUND_DEVICE_ID, -1);
        outputSoundName = sp.getString(Constants.CONFIG.CONF_OUTPUT_SOUND_DEVICE_NAME, "(none)");
        intervalSearchAlarmVolume = sp.getInt(Constants.CONFIG.CONF_SEARCH_ALARM_VOLUME, Constants.ALARM_VOLUME_DEFAULT) / 100.0f;
        intervalSearchAlarmDetectionLevel = sp.getInt(Constants.CONFIG.CONF_SEARCH_DETECTION_LEVEL, Constants.ALARM_DETECTION_LEVEL_DEFAULT);
        // --- end interval search

        addGPS = sp.getBoolean(Constants.CONFIG.CONF_ADD_GPS_TO_FILES, Constants.ADD_GPS_TO_FILES_DEFAULT);
        sendDataToAtomSwiftAppEnabled = sp.getBoolean(Constants.CONFIG.CONF_SEND_DATA_TO_ATOMSWIFT, Constants.SEND_DATA_TO_ATOMSWIFT_DEFAULT);
        atomSwiftDRType = sp.getString(Constants.CONFIG.CONF_ATOMSWIFT_DOSE_RATE, Constants.ATOMSWIFT_DR_DEFAULT);

        activeProfile = PrefHelper.getActiveSensitivityProfile(service_context);

        // post read actions
        setAlarmAudioTrackDevice();
        if (is_recording) {
            // TODO: reset ONLY when relevant settings has been changed
            // resetSearchWindow();
            if (intervalSearchAlarmEnabled != intervalSearchAlarmEnabledPrev) {
                stopIntervalSearchAlarmTimer();
                if (intervalSearchAlarmEnabled) {
                    startIntervalSearchAlarmTimer();
                }
            }
        }

    }

    // --- calibration ---------------------------------------------------------------------------
    // The active calibration lives on SpectrumData.foreground; the service owns loading it at
    // startup and exchanging it with the device.

    /**
     * Apply the calibration the active device holds.
     */
    private void loadCalibration() {
        if (!isDeviceAvailable())
            return;
        double[] coeffs = activeSource.calibration();
        if (coeffs == null) {
            showToastInMainLooper(R.string.cal_none_stored, Toast.LENGTH_SHORT);
            return;
        }
        SpectrumData.instance.applyDeviceCalibration(this, coeffs);
    }

    /**
     * Apply the calibration the active device holds, if any, without telling the user.
     */
    private void restoreDeviceCalibration() {
        if (activeSource == null || !isDeviceConnected())
            return;
        double[] coeffs = activeSource.calibration();
        if (coeffs == null)
            return;
        Calibration calibration = new Calibration(SpectrumData.instance.getChannelCount());
        calibration.Calculate(coeffs);
        if (calibration.isCorrect())
            SpectrumData.instance.applyCalibration(this, calibration);
    }

    /**
     * Store the active calibration into the device (the result arrives asynchronously).
     */
    private void storeCalibration() {
        if (!isDeviceAvailable())
            return;
        Calibration calibration = SpectrumData.instance.foreground.getSpectrumCalibration();
        showToastInMainLooper(R.string.cal_store_wait, Toast.LENGTH_SHORT);
        activeSource.requestSaveCalibration(calibration.getCoeffArray(AtomSpectraProSource.CALIBRATION_COEFFICIENTS));
    }

    private boolean isDeviceAvailable() {
        if (isDeviceConnected() && activeSource != null)
            return true;
        showToastInMainLooper(R.string.device_not_available, Toast.LENGTH_SHORT);
        return false;
    }

    // --- device selection and lock -------------------------------------------------------------
    // The service never scans and never chooses a device: it connects the one it is handed, locks
    // it, and follows its loss and return as reported by the source.

    private void postToInputThread(Runnable task) {
        Handler handler = inputHandler;
        if (handler != null) {
            handler.post(task);
        }
    }

    private void updateMenu() {
        sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_MENU).setPackage(Constants.PACKAGE_NAME));
    }

    private void notifyDeviceStateChanged() {
        sendBroadcast(new Intent(Constants.ACTION.ACTION_DEVICE_STATE_CHANGED).setPackage(Constants.PACKAGE_NAME));
    }

    /**
    * Asynchronous; the outcome is sent as ACTION_DEVICE_SELECTED or ACTION_DEVICE_SELECTION_REQUIRED.
    * Once connected, the choice is remembered only if the user opted in.
     */
    public void selectDevice(DeviceDescriptor device) {
        postToInputThread(() -> doSelectDevice(device));
    }

    /**
    * Stops any recording, releases the device and leaves the loaded spectrum as it is.
    * The next launch starts offline too only if the user opted in to remembering the choice.
     */
    public void selectOffline() {
        sessionState = DeviceSessionState.OFFLINE;
        postToInputThread(() -> doGoOffline(true));
    }

    /**
     * The same, but the remembered choice stays: the next launch waits for the remembered device again.
     */
    public void stopAndGoOffline() {
        stopAndGoOffline(null);
    }

    /**
     * Same, and runs {@code afterOffline} on the main thread once the device is released.
     */
    public void stopAndGoOffline(Runnable afterOffline) {
        postToInputThread(() -> {
            doGoOffline(false);
            if (afterOffline != null) {
                new Handler(Looper.getMainLooper()).post(afterOffline);
            }
        });
    }

    /**
     * Connects a locked device again after a failed connect; the source never retries that on its own.
     */
    public void retryConnect() {
        postToInputThread(() -> {
            final SpectrumSource source = activeSource;
            if (sessionState != DeviceSessionState.LOCKED || source == null) return;
            deviceError = null;
            source.requestConnect();
            updateMenu();
            refreshServiceNotification();
            notifyDeviceStateChanged();
        });
    }

    /**
     * What has to be decided before recording can start on the connected device; START_FREE when nothing.
     * A screen spectrum the device did not produce is never replaced without the user knowing.
     */
    public int startDecision() {
        if (!isDeviceConnected() || screenFromDevice || SpectrumData.instance.foreground.isEmpty()) {
            return START_FREE;
        }
        final boolean unsaved = SpectrumData.instance.foreground.isChanged();
        if (canContinueScreenSpectrum()) {
            return unsaved ? START_CONTINUE_OR_DISCARD : START_CONTINUE_OR_NEW;
        }
        return unsaved ? START_SAVE_OR_DISCARD : START_FREE;
    }

    /**
     * The user gave up the unsaved screen spectrum: the waiting device takes the screen over.
     */
    public void discardScreenForDevice() {
        postToInputThread(this::doAdoptWaitingDevice);
    }

    /**
     * The screen spectrum was saved; a device that waits for that takes the screen over.
     */
    public void onScreenSaved() {
        postToInputThread(() -> {
            if (connectDecisionPending && !SpectrumData.instance.foreground.isChanged()) {
                doAdoptWaitingDevice();
            }
        });
    }

    /**
     * The screen spectrum now comes from a file, not from the device.
     */
    public static void markScreenForeign() {
        screenFromDevice = false;
    }

    // the connected device can take over the screen spectrum and go on with it
    private boolean canContinueScreenSpectrum() {
        final SpectrumSource source = activeSource;
        return source != null && source.supportsInitialHistogram()
                && source.channelCount() == SpectrumData.instance.getChannelCount();
    }

    private SpectrumSource createSource(int type, String identity) {
        switch (type) {
            case SpectrumSource.TYPE_AUDIO:
                return new AtomSpectraAudioSource(this, identity);
            case SpectrumSource.TYPE_SPECTRA_PRO:
                return new AtomSpectraProSource(this, identity);
            case SpectrumSource.TYPE_BLUZ:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && AppPermissions.isBluetoothGranted(this)) {
                    try {
                        startForeground(FOREGROUND_PROCESS_ID, createNewServiceNotification(),
                                AppPermissions.foregroundServiceType(this));
                    } catch (SecurityException error) {
                        Log.e(TAG, "Cannot update Bluetooth foreground service type", error);
                    }
                }
                return new BluZBleSource(this, identity, inputHandler);
            default:
                return null;
        }
    }

    // a remembered device is locked at once and its source waits for it; no selection screen is involved
    private void restoreDeviceChoice() {
        final DeviceChoice choice = PrefHelper.getDeviceChoice(this);
        switch (choice.mode) {
            case DeviceChoice.MODE_DEVICE:
                sessionState = DeviceSessionState.LOCKED;
                postToInputThread(() -> lockDevice(choice.type, choice.identity, choice.name, false));
                break;
            case DeviceChoice.MODE_OFFLINE:
                sessionState = DeviceSessionState.OFFLINE;
                postToInputThread(() -> doGoOffline(false));
                break;
            default:
                break;
        }
    }

    private void doSelectDevice(DeviceDescriptor device) {
        final LockedDevice locked = lockedDevice;
        if (sessionState == DeviceSessionState.LOCKED && activeSource != null && locked != null
                && device.matches(locked.type, locked.identity)) {
            if (!isDeviceConnected()) {
                deviceError = null;
                activeSource.requestConnect();
            }
            updateMenu();
            refreshServiceNotification();
            notifyDeviceStateChanged();
            if (!firstConnectPending) {
                sendBroadcast(new Intent(Constants.ACTION.ACTION_DEVICE_SELECTED).setPackage(Constants.PACKAGE_NAME));
            }
            return;
        }
        // switching away from a recording device stops the recording; the loaded spectrum stays
        if (is_recording) {
            setRecordingState(false);
        }
        closeSources();
        resetRecordingSuspendedStatus(false);
        lockDevice(device.type, device.identity, device.displayName, true);
    }

    // locks the device and lets its source connect; the source waits if the device is not there
    private void lockDevice(int type, String identity, String name, boolean byUser) {
        selectionPending = byUser;
        SpectrumSource source = createSource(type, identity);
        if (source == null) {
            abandonLock("Unsupported device");
            return;
        }

        synchronized (inputSync) {
            activeSource = source;
        }
        lockedDevice = new LockedDevice(type, identity, name);
        sessionState = DeviceSessionState.LOCKED;
        firstConnectPending = true;
        deviceReady = false;
        deviceChannelCount = 0;
        wrongFrameLogged = false;
        deviceStatus = source.status();
        deviceError = null;
        screenFromDevice = false;
        connectDecisionPending = false;

        updateInputDeviceInfo(null);
        updateMenu();
        refreshServiceNotification();
        notifyDeviceStateChanged();
        source.requestConnect();
    }

    private void doGoOffline(boolean remember) {
        if (is_recording) {
            setRecordingState(false);
        }
        closeSources();
        releaseLock();
        sessionState = DeviceSessionState.OFFLINE;
        if (remember) {
            PrefHelper.setDeviceChoice(this, DeviceChoice.offline());
        }
        updateInputDeviceInfo(null);
        resetRecordingSuspendedStatus(false);
        updateMenu();
        refreshServiceNotification();
        notifyDeviceStateChanged();
        notifyDataAvailable();
    }

    private void doAdoptWaitingDevice() {
        final SpectrumSource source = activeSource;
        if (!connectDecisionPending || source == null || !isDeviceConnected()) return;

        connectDecisionPending = false;
        adoptDeviceSpectrum(source, connectDecisionFirstConnect);
        updateMenu();
        refreshServiceNotification();
        notifyDataAvailable();
    }

    private void closeSources() {
        SpectrumSource active;
        synchronized (inputSync) {
            active = activeSource;
            activeSource = null;
        }
        if (active != null) active.close();
    }

    // forgets the locked device; the remembered choice is not touched
    private void releaseLock() {
        lockedDevice = null;
        selectionPending = false;
        firstConnectPending = false;
        deviceReady = false;
        deviceChannelCount = 0;
        deviceStatus = SpectrumSource.STATUS_DISCONNECTED;
        deviceError = null;
        screenFromDevice = false;
        connectDecisionPending = false;
    }

    // the chosen device cannot be used: back to the selection screen, the remembered choice is not touched
    private void abandonLock(String text) {
        final boolean wasSelecting = selectionPending;
        if (is_recording) {
            setRecordingState(false);
        }
        closeSources();
        releaseLock();
        sessionState = DeviceSessionState.UNSELECTED;
        updateInputDeviceInfo(null);
        resetRecordingSuspendedStatus(false);

        AtomSpectraLog.addMessage(service_context, "Device selection failed: " + text);
        if (!wasSelecting && text != null) {
            showToastInMainLooper(text, Toast.LENGTH_LONG);
        }
        Intent required = new Intent(Constants.ACTION.ACTION_DEVICE_SELECTION_REQUIRED).setPackage(Constants.PACKAGE_NAME);
        if (text != null) {
            required.putExtra(Constants.ACTION_PARAMETERS.SELECTION_ERROR_TEXT, text);
        }
        sendBroadcast(required);
        updateMenu();
        refreshServiceNotification();
        notifyDeviceStateChanged();
        notifyDataAvailable();
    }

    // the name of the locked device until it is connected and can describe itself
    private void updateInputDeviceInfo(SpectrumSource connected) {
        synchronized (inputSync) {
            final LockedDevice locked = lockedDevice;
            if (locked == null) {
                inputDeviceInfo = "NONE";
            } else if (connected == null) {
                inputDeviceInfo = locked.name != null ? locked.name : locked.identity;
            } else {
                inputDeviceInfo = locked.type == SpectrumSource.TYPE_AUDIO
                        ? getAudioDeviceInfoText(connected.deviceId())
                    : locked.type == SpectrumSource.TYPE_BLUZ ? connected.deviceId()
                    : getUsbDeviceInfoText(connected.deviceId());
            }
        }
    }


    // a device that is already collecting puts the service into recording, whatever the previous intent was
    private void adoptCollectingDevice() {
        if (!is_recording) {
            setRecordingState(true);
        }
    }

    // the device shows what it holds: a collecting one starts streaming, an idle one is asked once
    private void requestDeviceSpectrum(SpectrumSource source) {
        if (deviceStatus == SpectrumSource.STATUS_CONNECTED_COLLECTING) {
            adoptCollectingDevice();
        } else {
            source.requestShowData();
        }
    }

    // the screen follows the device: cleared, calibrated by the device and filled with what the device holds
    private void adoptDeviceSpectrum(SpectrumSource source, boolean firstConnect) {
        final boolean replacesData = !SpectrumData.instance.foreground.isEmpty();
        resetServiceSpectrum();
        screenFromDevice = true;
        if (firstConnect) {
            double[] coeffs = source.calibration();
            if (coeffs != null) {
                SpectrumData.instance.applyDeviceCalibration(this, coeffs);
            }
        } else {
            restoreDeviceCalibration();
        }
        SpectrumData.instance.foreground.setDeviceInfo(inputDeviceInfo).updateComments();
        requestDeviceSpectrum(source);
        if (replacesData) {
            showToastInMainLooper(R.string.device_spectrum_adopted, Toast.LENGTH_SHORT);
        }
    }

    // A device that holds data of its own (one that cannot start from a supplied histogram) takes a clean screen over
    // at once and asks before replacing unsaved data. A device without data of its own leaves the screen alone:
    // what happens to that spectrum is settled when recording starts.
    // A device with a different channel count than the screen always takes the screen over, whatever it can hold.
    private void reconcileScreenWithDevice(SpectrumSource source, boolean firstConnect) {
        if (deviceChannelCount != SpectrumData.instance.getChannelCount()) {
            adoptOrAskAboutScreen(source, firstConnect);
            return;
        }
        if (source.supportsInitialHistogram()) {
            if (firstConnect && SpectrumData.instance.foreground.isEmpty()) {
                double[] coeffs = source.calibration();
                if (coeffs != null) {
                    SpectrumData.instance.applyDeviceCalibration(this, coeffs);
                }
                SpectrumData.instance.foreground.setDeviceInfo(inputDeviceInfo).updateComments();
            }
            return;
        }
        adoptOrAskAboutScreen(source, firstConnect);
    }

    private void adoptOrAskAboutScreen(SpectrumSource source, boolean firstConnect) {
        if (SpectrumData.instance.foreground.isChanged()) {
            connectDecisionFirstConnect = firstConnect;
            connectDecisionPending = true;
            sendBroadcast(new Intent(Constants.ACTION.ACTION_DEVICE_CONNECT_DECISION).setPackage(Constants.PACKAGE_NAME));
        } else {
            adoptDeviceSpectrum(source, firstConnect);
        }
    }

    // the service holds the spectrum, so a source that can start from one is given it before every start
    private void pushSpectrumToSource() {
        SpectrumSource source = activeSource;
        if (source == null || !source.supportsInitialHistogram()) return;

        long[] histogram;
        double recordingTime;
        synchronized (SpectrumData.instance.lock) {
            long[] data = SpectrumData.instance.foreground.getDataArray();
            histogram = Arrays.copyOf(data, data.length);
            recordingTime = SpectrumData.instance.foreground.getSpectrumTime();
        }
        source.setInitialHistogram(histogram, recordingTime);
    }

    private void onSourceReply(String action, Intent intent) {
        final int id = intent.getIntExtra(SpectrumSource.EXTRA_SOURCE_INSTANCE_ID, -1);
        final SpectrumSource active = activeSource;
        if (active == null || active.instanceId() != id) return;

        deviceError = active.lastError();
        switch (action) {
            case SpectrumSource.ACTION_SOURCE_READY:
                onSourceReady(intent);
                notifyDeviceStateChanged();
                break;
            case SpectrumSource.ACTION_SOURCE_DISCONNECTED:
                onSourceDisconnected(intent);
                notifyDeviceStateChanged();
                break;
            case SpectrumSource.ACTION_SOURCE_ERROR:
                onSourceError(intent);
                notifyDeviceStateChanged();
                break;
            case SpectrumSource.ACTION_SOURCE_STATUS:
                deviceStatus = intent.getIntExtra(SpectrumSource.EXTRA_SOURCE_STATUS, deviceStatus);
                updateMenu();
                refreshServiceNotification();
                notifyDeviceStateChanged();
                break;
            case SpectrumSource.ACTION_SOURCE_DATA:
                onSourceData(intent);
                break;
            case SpectrumSource.ACTION_SOURCE_DATA_SKIPPED:
                onSourceDataSkipped();
                break;
            case SpectrumSource.ACTION_SOURCE_CALIBRATION_SAVED:
                showToastInMainLooper(R.string.cal_stored, Toast.LENGTH_SHORT);
                break;
        }
    }

    private void onSourceReady(Intent ready) {
        deviceStatus = ready.getIntExtra(SpectrumSource.EXTRA_SOURCE_STATUS, deviceStatus);
        if (firstConnectPending) {
            deviceChannelCount = ready.getIntExtra(SpectrumSource.EXTRA_SOURCE_CHANNEL_COUNT, 0);
            onFirstReady();
        } else if (!deviceReady) {
            onDeviceReturned();
        }
    }

    // the first time the locked device answers: remember the choice if opted in and reconcile the screen with the device
    private void onFirstReady() {
        final SpectrumSource source = activeSource;
        final LockedDevice locked = lockedDevice;
        final boolean byUser = selectionPending;
        selectionPending = false;
        firstConnectPending = false;
        deviceReady = true;
        updateInputDeviceInfo(source);

        if (byUser) {
            PrefHelper.setDeviceChoice(this, DeviceChoice.device(locked.type, locked.identity, locked.name));
        }
        reconcileScreenWithDevice(source, true);
        AtomSpectraLog.addMessage(service_context, "Device info: " + inputDeviceInfo);

        if (byUser) {
            sendBroadcast(new Intent(Constants.ACTION.ACTION_DEVICE_SELECTED).setPackage(Constants.PACKAGE_NAME));
        }
        updateMenu();
        refreshServiceNotification();
        notifyDataAvailable();
    }

    // the locked device is back: a suspended recording follows the device, otherwise only a collecting device matters
    private void onDeviceReturned() {
        final SpectrumSource source = activeSource;
        deviceReady = true;
        if (lockedSourceType() == SpectrumSource.TYPE_SPECTRA_PRO) {
            showToastInMainLooper(R.string.action_usb_attached, Toast.LENGTH_SHORT);
        } else if (lockedSourceType() == SpectrumSource.TYPE_BLUZ) {
            showToastInMainLooper(R.string.action_bluetooth_attached, Toast.LENGTH_SHORT);
        }

        final boolean collecting = deviceStatus == SpectrumSource.STATUS_CONNECTED_COLLECTING;
        if (is_recording) {
            if (!collecting) {
                resetSkippedHistogramCount();
                pushSpectrumToSource();
                source.requestStart();
            }
        } else if (collecting) {
            reconcileScreenWithDevice(source, false);
        }

        if (isRecordingSuspended) {
            resetRecordingSuspendedStatus(false);
            onRecordingResumed();
        }
        updateMenu();
        refreshServiceNotification();
    }

    private void onSourceDisconnected(Intent intent) {
        deviceStatus = SpectrumSource.STATUS_DISCONNECTED;
        final String reason = intent.getStringExtra(SpectrumSource.EXTRA_SOURCE_DISCONNECT_REASON);

        if (firstConnectPending) {
            if (selectionPending) {
                abandonLock(reason);
            } else {
                updateMenu();
                refreshServiceNotification();
            }
            return;
        }
        if (!deviceReady) return;

        deviceReady = false;
        connectDecisionPending = false;
        AtomSpectraLog.addMessage(service_context, "Device disconnected: " + reason);

        if (is_recording) {
            synchronized (recordingSuspendedSync) {
                if (!isRecordingSuspended) {
                    isRecordingSuspended = true;
                    recordingSuspendReason = lockedSourceType() == SpectrumSource.TYPE_SPECTRA_PRO
                            ? RECORDING_SUSPEND_REASON_USB_DISCONNECT
                        : lockedSourceType() == SpectrumSource.TYPE_BLUZ ? RECORDING_SUSPEND_REASON_BT_DISCONNECT
                            : RECORDING_SUSPEND_REASON_AUDIO_REMOVED;
                    recordingSuspendSourceType = lockedSourceType();
                    onRecordingSuspended();
                }
            }
        }
        updateMenu();
        refreshServiceNotification();
    }

    private void onSourceError(Intent intent) {
        final int op = intent.getIntExtra(SpectrumSource.EXTRA_SOURCE_ERROR_OP, -1);
        final int reason = intent.getIntExtra(SpectrumSource.EXTRA_SOURCE_ERROR_REASON, SpectrumSource.REASON_ERROR);
        final String text = intent.getStringExtra(SpectrumSource.EXTRA_SOURCE_ERROR_TEXT);

        if (op == SpectrumSource.OP_CONNECT && reason == SpectrumSource.REASON_PERMISSION) {
            abandonLock(getStringOrDefaultLocale(R.string.device_permission_lost));
            return;
        }
        if (firstConnectPending && selectionPending) {
            abandonLock(text);
            return;
        }
        if (op == SpectrumSource.OP_CONNECT) {
            // the device stays locked; the source does not retry and the status icon offers the recovery
            AtomSpectraLog.addMessage(service_context, "Device connect failed: " + text);
            updateMenu();
            refreshServiceNotification();
            return;
        }
        onInputError(op, reason, text);
    }

    /**
     * A source request failed. The op code decides whether the failure reverts the recording
     * state, the label is whatever the source calls the failed operation.
     */
    private void onInputError(int op, int reason, String label) {
        if (!isDeviceConnected()) return;

        if (op == SpectrumSource.OP_START || op == SpectrumSource.OP_STOP) {
            // the service is optimistic about start/stop, so a failure has to put recording back
            is_recording = false;
            updateMenu();
        }

        if (op == SpectrumSource.OP_CALIBRATION_SAVE
                && lockedSourceType() != SpectrumSource.TYPE_BLUZ) {
            showToastInMainLooper(R.string.cal_wrong_store_device, Toast.LENGTH_SHORT);
            return;
        }

        if (label != null) {
        showToastInMainLooper(getStringOrDefaultLocale(
            reason == SpectrumSource.REASON_TIMEOUT
                ? R.string.log_source_operation_timeout
                : R.string.log_source_operation_failed,
            label), Toast.LENGTH_SHORT);
        }
    }

private Timer intervalSearchAlarmTimer;

private void startIntervalSearchAlarmTimer() {
    synchronized (intervalSearchAlarmSync) {
        if (intervalSearchAlarmTimer != null) {
            // TODO: localize
            String message = "ERROR: trying to start interval search alarm timer while timer is already in progress";
            showToastInMainLooper(message, Toast.LENGTH_SHORT);
            AtomSpectraLog.addMessage(service_context, message);
            return;
        }
        intervalSearchAlarmTimer = new Timer();
        TimerTask intervalSearchAlarmTask = new TimerTask() {
            @Override
            public void run() {
                synchronized (intervalSearchAlarmSync) {
                    if (intervalSearchAlarmEnabled && is_recording && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        if (intervalSearchAlarmAudioTrack != null) {
                            double currentCps = MeasurementData.instance.doseRate.intervalCps;
                            boolean isStable = intervalSearchAlarmBaseline.isStable();
                            double baseCps = intervalSearchAlarmBaseline.getBaseline();
                            double levelLow = intervalSearchAlarmBaseline.getAlarmLevelLow();
                            double levelHigh = intervalSearchAlarmBaseline.getAlarmLevelHigh();
                            if (!isStable || baseCps == 0 || (currentCps > levelLow && currentCps < levelHigh)) {
                                return;
                            }

                            int totalDurationFrames = intervalSearchAlarmAudioTrack.getBufferSizeInFrames();
                            int[] beeps;
                            int[] beepStartFrames;
                            double ratio = currentCps / baseCps;
                            int pow = 0;
                            if (ratio >= 1) {
                                if (ratio >= 2) pow = 1;
                                if (ratio >= 4) pow = 2;
                                if (ratio >= 8) pow = 3;
                                if (ratio >= 16) pow = 4;
                                if (ratio >= 32) pow = 5;
                                if (ratio >= 64) pow = 6;
                                if (ratio >= 128) pow = 7;
                                if (ratio >= 256) pow = 8;
                                if (ratio >= 512) pow = 9;
                                if (ratio >= 1024) pow = 10;
                                if (pow < 10) {
                                    beeps = new int[]{
                                            intervalSearchBaseFreq,
                                            intervalSearchHighFreq + pow * 200,
                                            0,
                                    };
                                    beepStartFrames = new int[]{
                                            0,                                // beep
                                            totalDurationFrames * 150 / 500,  // short beep
                                            totalDurationFrames * 250 / 500,  // silence
                                    };
                                } else {
                                    beeps = new int[]{
                                            intervalSearchBaseFreq,
                                            intervalSearchHighFreq + 2000,
                                    };
                                    beepStartFrames = new int[]{
                                            0,                                // beep
                                            totalDurationFrames * 150 / 500,  // long beep
                                    };
                                }
                            } else {
                                if (ratio <= 1.0 / 2) pow = 1;
                                if (ratio <= 1.0 / 4) pow = 2;
                                if (ratio <= 1.0 / 8) pow = 3;
                                if (ratio <= 1.0 / 16) pow = 4;
                                if (ratio <= 1.0 / 32) pow = 5;
                                if (ratio <= 1.0 / 64) pow = 6;
                                if (ratio <= 1.0 / 128) pow = 7;
                                if (ratio <= 1.0 / 256) pow = 8;
                                if (ratio <= 1.0 / 512) pow = 9;
                                if (ratio <= 1.0 / 1024) pow = 10;
                                if (pow < 10) {
                                    beeps = new int[]{
                                            intervalSearchBaseFreq,
                                            intervalSearchLowFreq - pow * 20,
                                            0
                                    };
                                    beepStartFrames = new int[]{
                                            0,                                // beep
                                            totalDurationFrames * 150 / 400,  // longer beep
                                            totalDurationFrames * 400 / 500,  // silence
                                    };
                                } else {
                                    beeps = new int[]{
                                            intervalSearchBaseFreq,
                                            intervalSearchHighFreq - 200,
                                    };
                                    beepStartFrames = new int[]{
                                            0,                                // beep
                                            totalDurationFrames * 150 / 500,  // long beep
                                    };
                                }

//                                    beeps = new int[] {
//                                            intervalSearchBaseFreq,
//                                            intervalSearchLowFreq,
//                                    };
//                                    beepStartFrames = new int[] {
//                                            0,                                // beep
//                                            totalDurationFrames * 150 / 400,  // long beep
//                                    };
                            }

                            int beepIndex = 0;
                            int previousBeepsDuration = 0;
                            float[] outputAudioBuffer = new float[totalDurationFrames];
                            for (int i = 0; i < totalDurationFrames; i++) {
                                // silent noise
                                outputAudioBuffer[i] = ((float) Math.random() - 0.5f) * 0.0001f;

                                int j = i - previousBeepsDuration; // beep timeline
                                int beepDuration;
                                if (beepIndex + 1 < beeps.length) {
                                    beepDuration = beepStartFrames[beepIndex + 1] - previousBeepsDuration;
                                } else {
                                    beepDuration = totalDurationFrames - previousBeepsDuration;
                                }
                                int beepFrequency = beeps[beepIndex];

                                if (j < beepDuration && beepFrequency > 0) {
                                    // beep
                                    outputAudioBuffer[i] = (float) generateTriangleWave((double) i / intervalSearchAlarmAudioTrackSampleRate, beepFrequency, 0.05, 0);

                                    int fadeInOutDuration = beepDuration / 20;
                                    if (j < fadeInOutDuration) {
                                        outputAudioBuffer[i] *= (float) (j + 1) / fadeInOutDuration;
                                    }
                                    if (beepDuration - j < fadeInOutDuration) {
                                        outputAudioBuffer[i] *= (float) (beepDuration - j) / fadeInOutDuration;
                                    }
                                }

                                if (beepIndex + 1 < beeps.length && i >= beepStartFrames[beepIndex + 1]) {
                                    beepIndex++;
                                    previousBeepsDuration = i;
                                }
                            }

                            setAlarmAudioTrackDevice();
                            intervalSearchAlarmAudioTrack.setVolume(intervalSearchAlarmVolume);
                            int playState = intervalSearchAlarmAudioTrack.getPlayState();
                            if (playState == AudioTrack.PLAYSTATE_PAUSED || playState == AudioTrack.PLAYSTATE_PLAYING) {
                                intervalSearchAlarmAudioTrack.stop();
                            }
                            intervalSearchAlarmAudioTrack.reloadStaticData();
                            intervalSearchAlarmAudioTrack.write(outputAudioBuffer, 0, totalDurationFrames, AudioTrack.WRITE_NON_BLOCKING);
                            intervalSearchAlarmAudioTrack.play();
                        }
                    }
                }
            }
        };

        intervalSearchAlarmTimer.schedule(intervalSearchAlarmTask, 1000, 1000);
    }
}

private static double generateTriangleWave(double time, double frequency, double amplitude, double offset) {
    double phase = (time * frequency) % 1.0;

    double waveValue;
    if (phase < 0.5) {
        waveValue = phase * 2.0;
    } else {
        waveValue = 1.0 - ((phase - 0.5) * 2.0);
    }

    return (waveValue * 2.0 - 1.0) * amplitude + offset;
}

private void stopIntervalSearchAlarmTimer() {
    synchronized (intervalSearchAlarmSync) {
        if (intervalSearchAlarmTimer != null) {
            intervalSearchAlarmTimer.cancel();
            intervalSearchAlarmTimer.purge();
            intervalSearchAlarmTimer = null;
        }

        intervalSearchAlarmBaseline.reset();
    }
}

private Context service_context = null;

@SuppressLint({"UnspecifiedRegisterReceiverFlag", "DiscouragedApi"})
public void Start(final Context context) {
    setLocaleFromPreferences(context);
    this.service_context = context;

    boolean hasFeatureGPS = getPackageManager().hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS);
    boolean hasFeatureNetwork = getPackageManager().hasSystemFeature(PackageManager.FEATURE_LOCATION_NETWORK);
    if (Locator == null) {
        Locator = new GPSLocator(getApplicationContext());
    }
    if ((hasFeatureGPS || hasFeatureNetwork) && PrefHelper.getASSharedPreferences(this).getBoolean(Constants.CONFIG.CONF_ADD_GPS_TO_FILES, Constants.ADD_GPS_TO_FILES_DEFAULT)) {
        if (!AppPermissions.isLocationGranted(this)) {
            Locator.stopUsingGPS();
            addGPS = false;
        } else {
            Locator.startUsingGPS();
            if (!Locator.hasGPS) {
                Locator.stopUsingGPS();
                addGPS = false;
            }
        }
    }
    publishDeviceLocationSnapshot();

    SpectrumData.instance.foreground.setSuffix(getStringOrDefaultLocale(R.string.hist_suffix));
    SpectrumData.instance.background.setSuffix(getStringOrDefaultLocale(R.string.background_suffix));
    sp = PrefHelper.getASSharedPreferences(this);
    sp.registerOnSharedPreferenceChangeListener(onSharedPreferenceChangeListener);

    initOutputAudioTrack();
    loadSettings();
    SpectrumData.instance.lastCalibrationChannel = PrefHelper.getLastCalibrationChannel(this, SpectrumData.instance.getChannelCount());

    inputThread = new HandlerThread("AtomSpectraInput");
    inputThread.start();
    inputHandler = new Handler(inputThread.getLooper());

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        registerReceiver(broadcastReceiver,
                makeAtomSpectraServiceIntentFilter(), null, inputHandler, Context.RECEIVER_NOT_EXPORTED);
    } else {
        registerReceiver(broadcastReceiver,
                makeAtomSpectraServiceIntentFilter(), null, inputHandler);
    }

    releaseLock();
    sessionState = DeviceSessionState.UNSELECTED;
    updateInputDeviceInfo(null);
    Log.d(TAG, "AtomSpectraService START");
}

private void initOutputAudioTrack() {
    synchronized (intervalSearchAlarmSync) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                int durationSamples = (int) (intervalSearchAlarmAudioTrackSampleRate * intervalSearchAlarmDuration);
                intervalSearchAlarmAudioTrack = new AudioTrack.Builder().
                        setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setSampleRate(intervalSearchAlarmAudioTrackSampleRate)
                                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build())
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .setBufferSizeInBytes(durationSamples * 4)
                        .build();
            } catch (Exception ignored) {
                intervalSearchAlarmAudioTrack = null;
                AtomSpectraLog.addMessage(service_context, "Unable to configure output audio: " + ignored.getMessage());
                showToastInMainLooper(R.string.no_audio_output_available, Toast.LENGTH_SHORT);
            }
            if (intervalSearchAlarmAudioTrack != null && intervalSearchAlarmAudioTrack.getState() != AudioTrack.STATE_NO_STATIC_DATA) {
                intervalSearchAlarmAudioTrack.release();
                intervalSearchAlarmAudioTrack = null;
                showToastInMainLooper(R.string.no_audio_output_available, Toast.LENGTH_SHORT);
            }
        }
    }
}

private static void releaseOutputAudioTrack() {
    synchronized (intervalSearchAlarmSync) {
        if (intervalSearchAlarmAudioTrack != null) {
            int playState = intervalSearchAlarmAudioTrack.getPlayState();
            if (playState == AudioTrack.PLAYSTATE_PAUSED || playState == AudioTrack.PLAYSTATE_PLAYING) {
                intervalSearchAlarmAudioTrack.stop();
            }
            intervalSearchAlarmAudioTrack.release();
            intervalSearchAlarmAudioTrack = null;
        }
    }
}

private static void setLocaleFromPreferences(Context context) {
    String lang = PrefHelper.getLocale(context);
    Locale locale = new Locale(lang);
    Locale.setDefault(locale);
    Resources resources = context.getResources();
    Configuration config = resources.getConfiguration();
    config.setLocale(locale);
    resources.updateConfiguration(config, resources.getDisplayMetrics());
}

private void setAlarmAudioTrackDevice() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && service_context != null) {
        if (intervalSearchAlarmAudioTrack != null) {
            AudioDeviceInfo deviceOut = getDeviceOutput(service_context, outputSoundID, outputSoundName, true);
            if (deviceOut == null) {
                deviceOut = getDeviceOutput(service_context, -1, null, false);
            }
            if (deviceOut != null) {
                synchronized (intervalSearchAlarmSync) {
                    outputSoundID = deviceOut.getId();
                    outputSoundName = deviceOut.getProductName().toString();
                    intervalSearchAlarmAudioTrack.setPreferredDevice(deviceOut);
                }
            }
        }
    }
}

public static final String[] audioDeviceNames = new String[]{
        "UNKNOWN",      //0
        "EAR",          //1
        "SPEAKER",      //2
        "WIRED HEADSET",//3
        "WIRED PHONES", //4
        "ANALOG",       //5
        "DIGITAL",      //6
        "SCO",          //7
        "A2DP",         //8
        "HDMI",         //9
        "ARC HDMI",     //10
        "USB DEV",      //11
        "USB ACC",      //12
        "DOCK",         //13
        "FM",           //14
        "MIC",          //15
        "TUNER",        //16
        "TV",           //17
        "PHONE",        //18
        "AUX",          //19
        "IP",           //20
        "BUS",          //21
        "USB HEADSET",  //22
        "AID",          //23
        "SAFE SPEAKER", //24
        "UNKNOWN"       //25
};

public static AudioDeviceInfo getDeviceOutput(Context context, int lastID, String lastName, boolean same) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        AudioDeviceInfo deviceOut = null;
        AudioDeviceInfo deviceEmptyOut = null;
        int newID = -1;
        int newEmptyID = -1;
        AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        AudioDeviceInfo[] devices = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        if (devices != null && devices.length > 0) {
            for (AudioDeviceInfo device : devices) {
                if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
                        device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        device.getType() == AudioDeviceInfo.TYPE_USB_HEADSET ||
                        device.getType() == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                        device.getType() == AudioDeviceInfo.TYPE_WIRED_HEADSET) {
                    //find device with minimal id not less than desired
                    if (same) {
                        if (lastID == device.getId() || device.getProductName().equals(lastName)) {
                            return device;   //Return the device we want to find
                        }
                    } else {
                        if (lastID < device.getId()) {
                            if (newID == -1 || newID > device.getId()) {
                                newID = device.getId();
                                deviceOut = device;
                            }
                        }
                        //find the smallest device id
                        if (newEmptyID == -1 || newEmptyID > device.getId()) {
                            newEmptyID = device.getId();
                            deviceEmptyOut = device;
                        }
                    }
                }
            }
            if (same) {
                return null;
            }
            if (newID == -1) {
                //no more devices
                return deviceEmptyOut;
            }
        }
        return deviceOut;
    } else {
        return null;
    }
}

private static IntentFilter makeAtomSpectraServiceIntentFilter() {
    final IntentFilter intentFilter = new IntentFilter();
    intentFilter.addAction(Constants.ACTION.ACTION_STOP_FOREGROUND);
    intentFilter.addAction(Constants.ACTION.ACTION_START_FOREGROUND);
    intentFilter.addAction(Constants.ACTION.ACTION_UPDATE_NOTIFICATION);
    intentFilter.addAction(SpectrumSource.ACTION_SOURCE_READY);
    intentFilter.addAction(SpectrumSource.ACTION_SOURCE_STATUS);
    intentFilter.addAction(SpectrumSource.ACTION_SOURCE_ERROR);
    intentFilter.addAction(SpectrumSource.ACTION_SOURCE_DISCONNECTED);
    intentFilter.addAction(SpectrumSource.ACTION_SOURCE_DATA);
    intentFilter.addAction(SpectrumSource.ACTION_SOURCE_DATA_SKIPPED);
    intentFilter.addAction(SpectrumSource.ACTION_SOURCE_CALIBRATION_SAVED);
    intentFilter.addAction(Constants.ACTION.ACTION_START_RECORDING);
    intentFilter.addAction(Constants.ACTION.ACTION_STOP_RECORDING);
    intentFilter.addAction(Constants.ACTION.ACTION_CLEAR_SPECTRUM);
    intentFilter.addAction(Constants.ACTION.ACTION_UPDATE_GPS);
    intentFilter.addAction(Intent.ACTION_BATTERY_LOW);
    intentFilter.addAction(Constants.ACTION.ACTION_CHECK_GPS_AVAILABILITY);
    intentFilter.addAction(Constants.ACTION.ACTION_LOAD_CALIBRATION);
    intentFilter.addAction(Constants.ACTION.ACTION_STORE_CALIBRATION);
    return intentFilter;
}

// Everything broadcastReceiver handles runs on this thread, which keeps the data path and the
// control actions serialized against each other without additional locks.
private HandlerThread inputThread = null;
private Handler inputHandler = null;

private final BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null)
            return;
        String action = intent.getAction();
        if (action == null)
            return;
        if (Constants.ACTION.ACTION_UPDATE_NOTIFICATION.equals(action)) {
            refreshServiceNotification();
            return;
        }
        if (Constants.ACTION.ACTION_CLEAR_SPECTRUM.equals(action)) {
            startStopRecording(false);
            DeleteSpc();
            updateMenu();
            // DeleteSpc() resets the spectrum to the default calibration, restore the device's one
            restoreDeviceCalibration();
            if (connectDecisionPending) {
                doAdoptWaitingDevice();
            }
            notifyDataAvailable();
            return;
        }
        if (Constants.ACTION.ACTION_START_RECORDING.equals(action)) {
            if (isDeviceConnected() && deviceState() != DeviceState.BUSY && !is_recording && !connectDecisionPending
                    && settleForeignSpectrumForStart(intent.getIntExtra(Constants.ACTION_PARAMETERS.START_FOREIGN_SPECTRUM,
                    Constants.ACTION_PARAMETERS.FOREIGN_SPECTRUM_UNDECIDED))) {
                startStopRecording(true);
            }
            return;
        }
        if (Constants.ACTION.ACTION_STOP_RECORDING.equals(action)) {
            if (deviceState() != DeviceState.BUSY) {
                startStopRecording(false);
            }
            return;
        }
        if (action.startsWith("org.fe57.atomspectra.ACTION_SOURCE_")) {
            onSourceReply(action, intent);
            return;
        }
        if (Constants.ACTION.ACTION_STOP_FOREGROUND.equals(action)) {
            Log.i(TAG, "Received Stop Foreground Intent");
            releaseOutputAudioTrack();
            Log.d(TAG, "recording Stop");
            Stop();
            return;
        }
        if (Constants.ACTION.ACTION_START_FOREGROUND.equals(action)) {
            Log.d(TAG, "Received Start Foreground Intent");
            return;
        }
        if (Intent.ACTION_BATTERY_LOW.equals(action) && SpectrumData.instance.foreground.isChanged()) {
            saveCurrentSpectrum("battery_low");
        }
        if (Constants.ACTION.ACTION_LOAD_CALIBRATION.equals(action)) {
            loadCalibration();
            return;
        }
        if (Constants.ACTION.ACTION_STORE_CALIBRATION.equals(action)) {
            storeCalibration();
            return;
        }
        if (Constants.ACTION.ACTION_UPDATE_GPS.equals(action)) {
            if (is_recording) {
                SpectrumData.instance.foreground.setLocation(Locator.getLocation()).updateComments();
            }
            publishDeviceLocationSnapshot();
        }
    }
};

private void onSourceDataSkipped() {
    if (!is_recording) {
        return;
    }

    skippedIncompleteHistogramCount++;
    if (skippedIncompleteHistogramCount >= 3) {
        sendBroadcast(new Intent(ACTION_HISTOGRAM_SKIPPED)
                .setPackage(Constants.PACKAGE_NAME)
                .putExtra(EXTRA_DATA_INT_HISTOGRAM_SKIPPED_COUNT, skippedIncompleteHistogramCount));
    }
}

// the integration seam: a cumulative snapshot from the active source drives dose rate, spectrogram and search
private void onSourceData(Intent intent) {
    if (!isDeviceConnected() || connectDecisionPending) {
        return;
    }
    if (!is_recording) {
        showIdleSnapshot(intent);
        return;
    }

    final long[] frame = intent.getLongArrayExtra(SpectrumSource.EXTRA_SOURCE_DATA_HISTOGRAM);
    if (frame != null && !isFrameSizeValid(frame)) {
        return;
    }

    skippedIncompleteHistogramCount = 0;
    double new_time;
    double old_time;
    long[] new_histogram;
    long[] old_histogram;
    Spectrum foregroundSpectrumCopy;
    synchronized (SpectrumData.instance.lock) {
        old_time = SpectrumData.instance.foreground.getSpectrumTime();
        old_histogram = SpectrumData.instance.foreground.getDataArray();
        old_histogram = Arrays.copyOf(old_histogram, old_histogram.length);

        new_time = intent.getDoubleExtra(SpectrumSource.EXTRA_SOURCE_DATA_RECORDING_TIME, 1);
        new_histogram = intent.getLongArrayExtra(SpectrumSource.EXTRA_SOURCE_DATA_HISTOGRAM);
        if (new_histogram != null) {
            new_histogram = Arrays.copyOf(new_histogram, new_histogram.length);
            SpectrumData.instance.foreground
                    .setSpectrum(new_histogram)
                    .setSpectrumTime(new_time)
                    .setDeviceInfo(inputDeviceInfo)
                    .updateComments();
            screenFromDevice = true;
        }

        foregroundSpectrumCopy = new Spectrum(SpectrumData.instance.foreground);
    }

    MeasurementData.instance.cp1s = intent.getIntExtra(SpectrumSource.EXTRA_SOURCE_DATA_CP1S, 0);
    boolean isReliableData = false;

    if (new_histogram != null && new_time > old_time) {
        int interval_counts = 0;
        int[] binned_counts = new int[SensitivityProfile.MAX_BINS];
        EnergyIntervalData.Snapshot interval = EnergyIntervalData.instance.get();
        for (int i = 0; i < StrictMath.min(old_histogram.length, new_histogram.length); i++) {
            int value = (int) (new_histogram[i] - old_histogram[i]);

            if (i >= interval.leftChannel && i <= interval.rightChannel) {
                interval_counts += value;
            }

            int bin_index = getEnergyBinIndex(SpectrumData.instance.foreground.getSpectrumCalibration().toEnergy(i));
            if (bin_index != -1) {
                binned_counts[bin_index] += value;
            }
        }

        if (old_time > 0) { // comparing to zero spectrum will produce large CPS in case collecting device attached
            MeasurementData.instance.cp1sInterval = interval_counts;
            MeasurementData.instance.doseRate = doseRateSearch(interval_counts, binned_counts, new_time - old_time);
            isReliableData = true;
        } else {
            MeasurementData.instance.cp1sInterval = 0;
            MeasurementData.instance.doseRate = new MeasurementData.DoseRate();
        }
    }

    calcAndSendFoundIsotopesData();
    if (isReliableData) {
        calcSpectrumChangeData();
    }
    notifyDataAvailable();
    if (isReliableData) {
        sendDataToAtomSwift(MeasurementData.instance.cp1s, MeasurementData.instance.doseRate);
        handleSpectrogramRecording(foregroundSpectrumCopy);
    }
}

// an idle device shows what it holds right now; nothing is derived from it (dose rate, spectrogram, search)
private void showIdleSnapshot(Intent intent) {
    long[] histogram = intent.getLongArrayExtra(SpectrumSource.EXTRA_SOURCE_DATA_HISTOGRAM);
    if (histogram == null || !isFrameSizeValid(histogram)) {
        return;
    }

    final double deviceTime = intent.getDoubleExtra(SpectrumSource.EXTRA_SOURCE_DATA_RECORDING_TIME, 1);
    synchronized (SpectrumData.instance.lock) {
        // mirroring the device is not a change the user made
        boolean wasChanged = SpectrumData.instance.foreground.isChanged();
        // unsaved data that did not come from the device is never overwritten by a snapshot
        if (!screenFromDevice && wasChanged) {
            return;
        }
        SpectrumData.instance.foreground
                .setSpectrum(Arrays.copyOf(histogram, histogram.length))
                .setSpectrumTime(deviceTime)
                .setDeviceInfo(inputDeviceInfo)
                .updateComments()
                .setChanged(wasChanged);
        screenFromDevice = true;
    }
    notifyDataAvailable();
}

public void notify_cancel_all() {
    if (service_context != null) {
        NotificationManager nm = (NotificationManager) service_context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null)
            nm.cancelAll();
    }
}

public void Stop() {
    notify_cancel_all();
    isStarted = false;
    try {
        unregisterReceiver(broadcastReceiver);
    } catch (IllegalArgumentException ignored) {
        // receiver was not registered or already unregistered
    }

    resetRecordingSuspendedStatus(true);

    Log.d(TAG, "recording Stop");
    completeSpectrogramRecording();
    closeSources();
    releaseLock();
    if (AtomSpectraIsotopes.checkedChains != null) {
        Arrays.fill(AtomSpectraIsotopes.checkedChains, false);
    }
    if (AtomSpectraIsotopes.checkedIsotope != null) {
        Arrays.fill(AtomSpectraIsotopes.checkedIsotope, false);
    }
    if (AtomSpectraIsotopes.checkedIsotopeLine != null) {
        Arrays.fill(AtomSpectraIsotopes.checkedIsotopeLine, false);
    }
    AtomSpectraIsotopes.foundList.clear();
    AtomSpectraIsotopes.showFoundIsotopes = false;
    SpectrumData.instance.newCalibration.clear();
    AtomSpectraLog.clear(service_context);
    sendBroadcast(new Intent(Constants.ACTION.ACTION_CLOSE_SETTINGS).setPackage(Constants.PACKAGE_NAME));
    sendBroadcast(new Intent(Constants.ACTION.ACTION_CLOSE_SEARCH).setPackage(Constants.PACKAGE_NAME));
    sendBroadcast(new Intent(Constants.ACTION.ACTION_CLOSE_ISOTOPES).setPackage(Constants.PACKAGE_NAME));
    sendBroadcast(new Intent(Constants.ACTION.ACTION_CLOSE_HELP).setPackage(Constants.PACKAGE_NAME));
    sendBroadcast(new Intent(Constants.ACTION.ACTION_CLOSE_LOG).setPackage(Constants.PACKAGE_NAME));
    sendBroadcast(new Intent(Constants.ACTION.ACTION_CLOSE_SPECTROGRAM).setPackage(Constants.PACKAGE_NAME));
    sendBroadcast(new Intent(Constants.ACTION.ACTION_CLOSE_SENSITIVITY).setPackage(Constants.PACKAGE_NAME));
    sendBroadcast(new Intent(Constants.ACTION.ACTION_CLOSE_APP).setPackage(Constants.PACKAGE_NAME));
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        stopForeground(STOP_FOREGROUND_REMOVE);
    } else {
        stopForeground(true);
    }
    stopSelf();

    // after the receiver is unregistered nothing else posts to this thread; quitSafely() lets the
    // current dispatch finish, which matters because Stop() itself may run on it
    if (inputThread != null) {
        inputThread.quitSafely();
        inputThread = null;
        inputHandler = null;
    }
}

// false when the start must not go ahead: the screen spectrum is not the device's and the user has not said what to do with it
private boolean settleForeignSpectrumForStart(int policy) {
    if (startDecision() == START_FREE) {
        return true;
    }
    switch (policy) {
        case Constants.ACTION_PARAMETERS.FOREIGN_SPECTRUM_REPLACE:
            resetServiceSpectrum();
            restoreDeviceCalibration();
            return true;
        case Constants.ACTION_PARAMETERS.FOREIGN_SPECTRUM_CONTINUE:
            // the start hands the screen spectrum to the device
            return canContinueScreenSpectrum();
        default:
            return false;
    }
}

public void DeleteSpc() {
    if (activeSource != null && isDeviceConnected()) {
        activeSource.requestReset();
    }
    resetServiceSpectrum();
}

// clears the service's own spectrum and baselines without touching the source
private void resetServiceSpectrum() {
    screenFromDevice = false;
    completeSpectrogramRecording();
    AtomSpectraSpectrogramData.instance.clear();
    notifySpectrogramUpdated();

    MeasurementData.instance.resetCp1s();
    resetDoseRateData();

    // once the device has reported ready its count rules, otherwise the screen keeps its own
    final int channelCount = deviceChannelCount > 0 ? deviceChannelCount : SpectrumData.instance.getChannelCount();
    final boolean resized = SpectrumData.instance.reset(channelCount);
    UIViewState.instance.backgroundShow = false;
    UIViewState.instance.backgroundSubtract = false;
    if (resized) {
        UIViewState.instance.onChannelCountChanged(channelCount);
    }
    SpectrumData.instance.foreground
            .setSuffix(getStringOrDefaultLocale(R.string.hist_suffix))
            .setDeviceInfo(inputDeviceInfo)
            .updateComments();
    SpectrumData.instance.background.setSuffix(getStringOrDefaultLocale(R.string.background_suffix));
    AtomSpectraIsotopes.foundList.clear();
    AtomSpectraIsotopes.showFoundIsotopes = false;

    resetSpectrumChangeWindow();
    skippedIncompleteHistogramCount = 0;
    updateMenu();
}

// data never resizes the screen: a frame of another length than the screen's is dropped and reported as skipped
private boolean isFrameSizeValid(long[] histogram) {
    if (histogram.length == SpectrumData.instance.getChannelCount()) {
        return true;
    }
    if (!wrongFrameLogged) {
        wrongFrameLogged = true;
        AtomSpectraLog.addMessage(service_context, "Error: histogram of " + histogram.length
                + " channels ignored, the screen has " + SpectrumData.instance.getChannelCount());
    }
    onSourceDataSkipped();
    return false;
}

public static boolean isRecording() {
    return is_recording;
}

// starts/stops data collecting: updates the recording state and commands the active source
private void startStopRecording(boolean recording) {
    setRecordingState(recording);

    synchronized (inputSync) {
        if (activeSource != null) {
            if (recording) {
                resetSkippedHistogramCount();
                pushSpectrumToSource();
                activeSource.requestStart();
            } else {
                activeSource.requestStop();
            }
        }
    }
}

// updates the recording state and everything derived from it, without commanding the source
private void setRecordingState(boolean recording) {
    if (recording != is_recording) {
        // log event to debug view
        String inputTypeText = lockedSourceName("audio", "usb", "bluz", "none");

        if (recording) {
            AtomSpectraLog.addMessage(service_context, getStringOrDefaultLocale(R.string.log_start_recording, inputTypeText));
        } else {
            AtomSpectraLog.addMessage(service_context, getStringOrDefaultLocale(R.string.log_stop_recording, inputTypeText));
        }
    }

    is_recording = recording;

    if (recording) {
        startIntervalSearchAlarmTimer();
    } else {
        skippedIncompleteHistogramCount = 0;
        resetSearchWindow();
        resetSpectrumChangeWindow();
        resetRecordingSuspendedStatus(false);
        stopIntervalSearchAlarmTimer();
        completeSpectrogramRecording();
        SpectrumData.instance.foreground.updateComments();
    }

    updateMenu();
    refreshServiceNotification();
    notifyDeviceStateChanged();
}

// when new data arrives either from audio or USB we preserve it in historical sliding time window
// used to calculate dose rate
private static final int SEARCH_WINDOW_SIZE = MeasurementData.SEARCH_WINDOW_SIZE;
// per-sample energy-binned counts; also the total-count source since total == sum of bins
// (every detected event is assigned to a bin, see getEnergyBinIndex)
private static final LinkedList<int[]> windowBinnedCounts = new LinkedList<>();
// per-sample interval counts (channel-range subset) as a single-bin array
private static final LinkedList<int[]> windowIntervalCounts = new LinkedList<>();
private static final LinkedList<Double> windowDeltaTime = new LinkedList<>();

private static void resetSearchWindow() {
    synchronized (windowBinnedCounts) {
        windowDeltaTime.clear();
        windowBinnedCounts.clear();
        windowIntervalCounts.clear();
    }
}

private static void resetDoseRateData() {
    resetSearchWindow();
    MeasurementData.instance.resetDoseRate();
}

// called each [0.1, 0.2, 0.5, 1] sec for audio, each 1 sec for USB
private MeasurementData.DoseRate doseRateSearch(int interval_counts, int[] binned_counts, double delta_time) {
    if (delta_time == 0) {
        return MeasurementData.instance.doseRate;
    }

    synchronized (windowBinnedCounts) {
        windowDeltaTime.addLast(delta_time);
        if (windowDeltaTime.size() > SEARCH_WINDOW_SIZE) {
            windowDeltaTime.removeFirst();
        }

        windowBinnedCounts.addLast(binned_counts);
        if (windowBinnedCounts.size() > SEARCH_WINDOW_SIZE) {
            windowBinnedCounts.removeFirst();
        }

        windowIntervalCounts.addLast(new int[]{interval_counts});
        if (windowIntervalCounts.size() > SEARCH_WINDOW_SIZE) {
            windowIntervalCounts.removeFirst();
        }

        if (intervalSearchAlarmEnabled) {
            intervalSearchAlarmBaseline.updateBaseline(interval_counts, delta_time);
        } else {
            intervalSearchAlarmBaseline.reset();
        }
    }

    // shared active mode selects the minimum integration period; count targets differ per dose rate
    double min_period;
    switch (SearchFSM) {
        case 1:
            min_period = 1;
            break;
        case 2:
            min_period = 2;
            break;
        default:
            min_period = 0.2;
            break;
    }

    SensitivityProfile profile = activeProfile;
    int bins = Math.min(profile.binEdges.length, SensitivityProfile.MAX_BINS);

    WindowSum nonComp = accumulateWindow(windowBinnedCounts, profile.searchTargetNonComp(SearchFSM), min_period);
    if (nonComp.time < min_period) {
        return MeasurementData.instance.doseRate;
    }
    WindowSum comp = accumulateWindow(windowBinnedCounts, profile.searchTargetComp(SearchFSM), min_period);
    WindowSum interval = accumulateWindow(windowIntervalCounts, profile.searchTargetNonComp(SearchFSM), min_period);

    // compensated dose rate: sum over energy bins of counts * pSv/count, converted to uSv/h
    double comp_dose_rate = 0;
    double comp_dose_rate_error_sum_of_squares = 0;
    for (int bin = 0; bin < bins; bin++) {
        int bin_counts = comp.binned[bin];
        double bin_psv = profile.compPsvPerCount[bin];
        double bin_dose_rate = bin_counts * bin_psv / comp.time * SensitivityProfile.PSV_PER_COUNT_TO_USV_H;
        comp_dose_rate += bin_dose_rate;
        if (bin_counts > 0) {
            double bin_dose_rate_error = (Math.sqrt(bin_counts) / bin_counts) * bin_dose_rate;
            comp_dose_rate_error_sum_of_squares += bin_dose_rate_error * bin_dose_rate_error;
        } else {
            // no counts in bin: bound the error by the dose rate of a single count
            double upper_dose_rate_bound = bin_psv / comp.time * SensitivityProfile.PSV_PER_COUNT_TO_USV_H;
            comp_dose_rate_error_sum_of_squares += upper_dose_rate_bound * upper_dose_rate_bound;
        }
    }
    double comp_dose_rate_error = 0;
    if (comp_dose_rate > 0) {
        comp_dose_rate_error = Math.sqrt(comp_dose_rate_error_sum_of_squares) / comp_dose_rate * 100.0;
    }

    // non-compensated dose rate: total counts * pSv/count, converted to uSv/h
    double dose_rate = 0;
    if (profile.nonCompPsvPerCount > 0) {
        dose_rate = nonComp.counts * profile.nonCompPsvPerCount / nonComp.time * SensitivityProfile.PSV_PER_COUNT_TO_USV_H;
    }
    double dose_rate_error = nonComp.counts > 0 ? Math.sqrt(nonComp.counts) / nonComp.counts * 100.0 : 0;

    double interval_cps = interval.time > 0 ? (interval.counts / interval.time) : 0;
    double interval_cps_error = interval.counts > 0 ? Math.sqrt(interval.counts) / interval.counts * 100.0 : 0;
    if (intervalSearchAlarmEnabled) {
        intervalSearchAlarmBaseline.updateAlarmLevels(interval_cps, interval_cps_error, intervalSearchAlarmDetectionLevel);
    }

    MeasurementData.instance.appendSearchSample(System.currentTimeMillis(), dose_rate, comp_dose_rate, interval_cps,
            intervalSearchAlarmBaseline.getAlarmLevelHigh(), intervalSearchAlarmBaseline.getAlarmLevelLow(),
            intervalSearchAlarmBaseline.getBaseline());

    return new MeasurementData.DoseRate(
            comp_dose_rate, comp_dose_rate_error, comp.time,
            dose_rate, dose_rate_error, nonComp.time,
            interval_cps, interval_cps_error, interval.time);
}

private static final class WindowSum {
    int counts;
    double time;
    final int[] binned = new int[SensitivityProfile.MAX_BINS];
}

// Sum the most-recent samples until both the count target and the minimum period are reached.
// Each sample is an array of per-bin counts (a single-element array for the interval source);
// counts is the sum across the sample's bins - for the energy-binned source this equals the
// total count, since every detected event is assigned to a bin (see getEnergyBinIndex).
private WindowSum accumulateWindow(LinkedList<int[]> source, int target, double minPeriod) {
    WindowSum s = new WindowSum();
    synchronized (windowBinnedCounts) {
        for (int i = source.size() - 1; i >= 0; i--) {
            int[] sample = source.get(i);
            int n = Math.min(sample.length, s.binned.length);
            for (int bin = 0; bin < n; bin++) {
                s.binned[bin] += sample[bin];
                s.counts += sample[bin];
            }
            s.time += windowDeltaTime.get(i);
            if (s.counts >= target && s.time >= minPeriod) {
                break;
            }
        }
    }
    return s;
}

private static int getEnergyBinIndex(double energy) {
    float[] edges = activeProfile.binEdges;
    if (edges.length == 0) {
        return -1;
    }
    for (int i = 0; i < edges.length; i++) {
        if (energy <= edges[i]) {
            return i;
        }
    }
    return edges.length - 1; // above the last edge: top band
}

// finds isotopes and sends data to UI
// should to be called each second
private final void calcAndSendFoundIsotopesData() {
    if (AtomSpectraIsotopes.autoUpdateIsotopes && is_recording && AtomSpectraIsotopes.showFoundIsotopes) {
        AtomSpectraFindIsotope.updateFoundIsotopes(this);
        sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_ISOTOPE_LIST).setPackage(Constants.PACKAGE_NAME));
    }
}

// spectrum change mode
// shows spectrum for the last n seconds (sliding window)
// window size - delta_time
private final void calcSpectrumChangeData() {
    long[] currentState = Arrays.copyOf(SpectrumData.instance.foreground.getDataArray(), SpectrumData.instance.foreground.getDataArray().length);
    long[] previousState = currentState;
    long[] backState = currentState;
    int queueSize;
    synchronized (histogram_all_queue) {
        if (!histogram_all_queue.isEmpty() && histogram_all_queue.peek().length != currentState.length) {
            histogram_all_queue.clear();
        }
        histogram_all_queue.add(currentState);
        while (histogram_all_queue.size() > delta_time * delta_back_time_ratio + 1) {
            histogram_all_queue.remove();
        }

        if (histogram_all_queue.size() <= delta_time + 1) {
            previousState = histogram_all_queue.peek();
            backState = previousState;
        } else {
            previousState = histogram_all_queue.get(histogram_all_queue.size() - delta_time);
            backState = histogram_all_queue.peek();
        }
        queueSize = histogram_all_queue.size();
    }

    long[] deltaFg = new long[currentState.length];
    long[] deltaBg = new long[currentState.length];
    for (int i = 0; i < currentState.length; i++) {
        deltaFg[i] = currentState[i] - previousState[i];
        deltaBg[i] = currentState[i] - backState[i];
    }

    SpectrumChangeData.instance.set(deltaFg, deltaBg, Math.min(queueSize - 1, delta_time), queueSize - 1);
}

private void notifyDataAvailable() {
    if (service_context != null) {
        service_context.sendBroadcast(new Intent(Constants.ACTION.ACTION_DATA_AVAILABLE).setPackage(Constants.PACKAGE_NAME));
    }
}

public class LocalBinder extends Binder {
    AtomSpectraService getService() {
        return AtomSpectraService.this;
    }
}

@Override
public IBinder onBind(Intent intent) {

//        timerTask_started = true;

    return new LocalBinder();
}

@Override
public boolean onUnbind(Intent intent) {
    // After using a given device, you should make sure that this.close() is called
    // such that resources are cleaned up properly.  In this particular example, close() is
    // invoked when the UI is disconnected from the Service.
    //close();
    return super.onUnbind(intent);
}

private void saveCurrentSpectrum(String suffix) {
    try {
        Pair<OutputStreamWriter, Uri> streamInfo = SpectrumFile.prepareOutputFileStream(this, getStringOrDefaultLocale(R.string.file_atomspectra_spectrum_prefix), SpectrumData.instance.foreground.getSpectrumDate(), suffix, ".txt", "text/plain", false);
        OutputStreamWriter docStream = streamInfo.first;
        Spectrum spectrum = new Spectrum(SpectrumData.instance.foreground);
        if (!addGPS) {
            spectrum.setLocation(null).updateComments();
        }

        SpectrumFileAS saveFile = new SpectrumFileAS();
        saveFile.addSpectrum(spectrum)
                .setChannelCompression(1)
                .saveSpectrumAndCloseStream(docStream, this);
    } catch (Exception e) {
        AtomSpectraLog.addMessage(service_context, Log.getStackTraceString(e));
        showToastInMainLooper(getStringOrDefaultLocale(R.string.hist_save_error, suffix), Toast.LENGTH_SHORT);
    }
}

private void handleSpectrogramRecording(Spectrum foregroundSpectrumCopy) {
    if (spgInterval > 0) {
        boolean updateIsRequired = false;
        synchronized (spgAutosaveSync) {
            if (spgAutosaveSpectrum == null) {
                updateIsRequired = true;
            } else {
                double elapsedTime = foregroundSpectrumCopy.getSpectrumTime() - spgAutosaveSpectrum.getSpectrumTime();
                updateIsRequired = elapsedTime >= spgInterval;
            }
        }

        if (updateIsRequired) {
            new Thread(() -> createOrUpdateSpectrogramFile(foregroundSpectrumCopy)).start();
        }
    } else {
        completeSpectrogramRecording();
    }
}

private void notifySpectrogramUpdated() {
    sendBroadcast(new Intent(Constants.ACTION.ACTION_SPECTROGRAM_UPDATED).setPackage(Constants.PACKAGE_NAME));
}

private void createOrUpdateSpectrogramFile(Spectrum foregroundSpectrumCopy) {
    synchronized (spgAutosaveSync) {
        // reset spectrogram file if midnight has passed
        if (spgMidnightReset && spgAutosaveFilePath != null && spgAutosaveFileCreated != null) {
            Date now = new Date();
            if (now.getDate() != spgAutosaveFileCreated.getDate()) {
                appendDeltaToSpectrogram(foregroundSpectrumCopy);
                completeSpectrogramRecording();
                showToastInMainLooper(R.string.log_spg_midnight_restart, Toast.LENGTH_SHORT);
            }
        }

        // spectrogram recording is starting or restarting
        if (spgAutosaveSpectrum == null) {
            spgAutosaveSpectrum = foregroundSpectrumCopy;
            spgAutosaveSpectrum.updateComments();

            try {
                Pair<OutputStreamWriter, Uri> spgAutosaveFileStreamInfo = SpectrumFile.prepareOutputFileStream(this, "Spectrogram-" + spgAutosaveSpectrum.getSuffix(), System.currentTimeMillis(), "", ".txt", "text/plain", true, true, true, false);
                spgAutosaveFilePath = spgAutosaveFileStreamInfo.second;
                spgAutosaveFileCreated = new Date();
                OutputStreamWriter docStream = spgAutosaveFileStreamInfo.first;
                SpectrumFileAS saveFile = new SpectrumFileAS();
                saveFile.addSpectrum(spgAutosaveSpectrum)
                        .setChannelCompression(1)
                        .saveSpectrumAndCloseStream(docStream, this);

                AtomSpectraSpectrogramData.instance.addSegment(spgAutosaveSpectrum, spgAutosaveFilePath);
                notifySpectrogramUpdated();
                this.showToastInMainLooper(R.string.log_spg_autosave_start, Toast.LENGTH_SHORT);
            } catch (Exception e) {
                AtomSpectraLog.addMessage(service_context, Log.getStackTraceString(e));
                showToastInMainLooper(getStringOrDefaultLocale(R.string.log_spg_autosave_start_error, e.getMessage()), Toast.LENGTH_SHORT);
            }

            return;
        }

        // spectrogram recording is ongoing
        double elapsedTime = foregroundSpectrumCopy.getSpectrumTime() - spgAutosaveSpectrum.getSpectrumTime();
        if (elapsedTime >= spgInterval) {
            appendDeltaToSpectrogram(foregroundSpectrumCopy);
        }
    }
}

private void appendDeltaToSpectrogram(Spectrum foregroundSpectrumCopy) {
    try {
        Spectrum deltaSpectrum = new Spectrum(foregroundSpectrumCopy).convertToDeltaSpectrum(spgAutosaveSpectrum);

        // Persist to the file system first. The in-memory spectrogram and the
        // baseline are only advanced if the write succeeds, so a failed write
        // leaves the baseline intact and the missed interval is folded into the
        // next delta instead of being permanently lost from the file.
        if (!addGPS) {
            deltaSpectrum.setLocation(null);
        }
        deltaSpectrum.updateComments();
        SpectrumFileAS saveFile = new SpectrumFileAS();
        saveFile.addSpectrum(deltaSpectrum)
                .setChannelCompression(1);
        OutputStream out = service_context.getContentResolver().openOutputStream(spgAutosaveFilePath, "wa");
        if (out == null) {
            throw new IOException("Unable to open spectrogram file for append: " + spgAutosaveFilePath);
        }
        OutputStreamWriter docStream = new OutputStreamWriter(out);
        saveFile.saveDeltaSpectrumAndCloseStream(docStream);

        // Write succeeded: commit to memory and advance the baseline.
        AtomSpectraSpectrogramData.instance.addDelta(deltaSpectrum);
        notifySpectrogramUpdated();
        spgAutosaveSpectrum = foregroundSpectrumCopy;
    } catch (Exception e) {
        this.showToastInMainLooper(getStringOrDefaultLocale(R.string.error_unable_to_save_delta_spectrum, e.getMessage()), Toast.LENGTH_SHORT);
        AtomSpectraLog.addMessage(service_context, Log.getStackTraceString(e));
    }
}

private void completeSpectrogramRecording() {
    synchronized (spgAutosaveSync) {
        spgAutosaveSpectrum = null;
        if (spgAutosaveFilePath != null) {
            showToastInMainLooper(R.string.log_spg_autosave_completed, Toast.LENGTH_SHORT);
        }

        spgAutosaveFilePath = null;
        spgAutosaveFileCreated = null;
    }
}

private void ensureGPSConfigured() {
    boolean hasFeatureGPS = getPackageManager().hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS);
    boolean hasFeatureNetwork = getPackageManager().hasSystemFeature(PackageManager.FEATURE_LOCATION_NETWORK);

    if ((hasFeatureGPS || hasFeatureNetwork) && addGPS && AppPermissions.isLocationGranted(this)) {
        Locator.startUsingGPS();
        addGPS = Locator.hasGPS; // only stamp coordinates once a provider is actually available
        if (!Locator.hasGPS) {
            Locator.stopUsingGPS();
        }
    } else {
        // setting off, permission missing, or no hardware: write no coordinates, but keep the
        // user's "add GPS" intent so startup can ask for the permission again
        Locator.stopUsingGPS();
        addGPS = false;
    }
    publishDeviceLocationSnapshot();
}

// sends intent with dose/count rate etc. to AtomSwift app
// expected to be called each second
// dose rates expected to be uSv/h
private void sendDataToAtomSwift(int cps, MeasurementData.DoseRate doseRate) {
    if (!sendDataToAtomSwiftAppEnabled || !is_recording) {
        resetAtomSwiftIntermediateData();
        return;
    }

    if (!atomSwiftHasIntermediateData) {
        atomSwiftIntermediateCps = cps;
        atomSwiftHasIntermediateData = true;
        return;
    }

    int cp2s = atomSwiftIntermediateCps + cps;
    double dr = 0;
    double dr_error = 0;
    switch (atomSwiftDRType) {
        case Constants.ATOMSWIFT_DR_COMPENSATED:
            dr = doseRate.compensated;
            dr_error = doseRate.compensatedErrorPercent;
            break;
        case Constants.ATOMSWIFT_DR_NON_COMPENSATED:
            dr = doseRate.nonCompensated;
            dr_error = doseRate.nonCompensatedErrorPercent;
            break;
        case Constants.ATOMSWIFT_DR_INTERVAL:
            dr = doseRate.intervalCps;
            dr_error = doseRate.intervalCpsErrorPercent;
            break;
    }

    String searchMode = "";
    switch (SearchFSM) {
        case 0:
            searchMode = "F";
            break;
        case 1:
            searchMode = "M";
            break;
        case 2:
            searchMode = "S";
            break;
    }

    String inputTypeStr = lockedSourceName("MIC", "USB", "BLUZ", "NONE");

    resetAtomSwiftIntermediateData();

    Intent dataIntent = new Intent("org.fe57.atomtag.atomspectradata");
    dataIntent.setPackage("com.youratom.scid");
    dataIntent.putExtra("CP2S", cp2s); // double (imp/2s)
    dataIntent.putExtra("DR", dr); // double (uSv/h)
    dataIntent.putExtra("DR_ERROR", dr_error); // double (%), 1 sigma
    dataIntent.putExtra("SEARCH_MODE", searchMode); // String (F/M/S)
    dataIntent.putExtra("INPUT_TYPE", inputTypeStr); // String (USB/MIC/NONE)
    getApplicationContext().sendBroadcast(dataIntent);

    // debug toast
//        showToastInMainLooper(
//                "cp2s: " + cp2s
//                + "; dr: " + dr + " (+-" + dr_error + "%)"
//                + "; search: " + searchMode + ";",
//                Toast.LENGTH_SHORT);
}

private static void resetAtomSwiftIntermediateData() {
    atomSwiftHasIntermediateData = false;
    atomSwiftIntermediateCps = 0;
}

private void showToastInMainLooper(String text, int duration) {
    new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(getApplicationContext(), text, duration).show());
    AtomSpectraLog.addMessage(service_context, text);
}

private void showToastInMainLooper(int res_id, int duration) {
    String text = getStringOrDefaultLocale(res_id);
    showToastInMainLooper(text, duration);
}

private void onRecordingSuspended() {
    synchronized (recordingSuspendedSync) {
        recordingSuspensionEpisode++;
        recordingSuspensionAcknowledged = false;
    }
    recordingSuspendedAt = new Date();
    AtomSpectraLog.addMessage(service_context, getStringOrDefaultLocale(R.string.log_recording_suspended));

    saveCurrentSpectrum("recording_suspended");
    completeSpectrogramRecording();
    // the source arms its own unreliable-data window when it is started again
    resetSkippedHistogramCount();
    resetSpectrumChangeWindow();
    resetSearchWindow();
    resetAtomSwiftIntermediateData();
    sendBroadcast(new Intent(ACTION_RECORDING_SUSPENDED).setPackage(Constants.PACKAGE_NAME));
    refreshServiceNotification();
    playNotificationSound();
}

private void onRecordingResumed() {
    recordingResumedAt = new Date();
    sendBroadcast(new Intent(ACTION_RECORDING_RESUMED).setPackage(Constants.PACKAGE_NAME));
    refreshServiceNotification();
    playNotificationSound();

    AtomSpectraLog.addMessage(service_context, getStringOrDefaultLocale(R.string.log_recording_resumed));
}

// the consecutive-rejection counter behind ACTION_HISTOGRAM_SKIPPED: a restart is not the user's
// "no data is arriving" problem, so it starts counting from scratch
private void resetSkippedHistogramCount() {
    skippedIncompleteHistogramCount = 0;
}

private void refreshServiceNotification() {
    if (service_context != null && AppPermissions.areNotificationsAllowed(service_context)) {
        NotificationManagerCompat.from(service_context).notify(FOREGROUND_PROCESS_ID, createNewServiceNotification());
    }
}

private void playNotificationSound() {
    try {
        Uri notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        Ringtone r = RingtoneManager.getRingtone(getApplicationContext(), notification);
        r.play();
    } catch (Exception e) {
        // ignore
    }
}

private static void resetRecordingSuspendedStatus(boolean withDates) {
    synchronized (recordingSuspendedSync) {
        isRecordingSuspended = false;
        recordingSuspensionAcknowledged = false;
        recordingSuspendReason = RECORDING_SUSPEND_REASON_NONE;
        recordingSuspendSourceType = SpectrumSource.TYPE_NONE;

        if (withDates) {
            recordingSuspendedAt = null;
            recordingResumedAt = null;
        }
    }
}

private static void resetSpectrumChangeWindow() {
    SpectrumChangeData.instance.reset();
    if (!histogram_all_queue.isEmpty()) {
        synchronized (histogram_all_queue) {
            histogram_all_queue.clear();
        }
    }
}

public static String formatLocalTimeAsISOLikeString(Date date) {
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
    sdf.setTimeZone(TimeZone.getDefault());

    return sdf.format(date);
}

private String getStringOrDefaultLocale(int res_id) {
    if (service_context != null) {
        return service_context.getString(res_id);
    }

    return getString(res_id);
}

private String getStringOrDefaultLocale(int res_id, Object... formatArgs) {
    if (service_context != null) {
        return service_context.getString(res_id, formatArgs);
    }

    return getString(res_id, formatArgs);
}

private String getAudioDeviceInfoText(String deviceMeta) {
    if (deviceMeta == null) {
        deviceMeta = "UNKNOWN";
    }

    return "AUDIO: " + deviceMeta;
}

private String getUsbDeviceInfoText(String deviceMeta) {
    if (deviceMeta == null) {
        deviceMeta = "UNKNOWN";
    }

    return "USB: " + deviceMeta;
}

public static class AlarmBaseline {
    private int totalCounts;
    private double totalTime;
    private double alarmLevelHigh;
    private double alarmLevelLow;
    private final double errorThresholdPercent; // 1 sigma %
    private final int maxDuration;

    public AlarmBaseline() {
        this.errorThresholdPercent = Constants.ALARM_BASELINE_ERROR_PERCENT_THRESHOLD;
        this.maxDuration = Constants.ALARM_BASELINE_MAX_DURATION;
    }

    public AlarmBaseline(double errorThresholdPercent, int maxDuration) {
        this.errorThresholdPercent = errorThresholdPercent;
        this.maxDuration = maxDuration;
    }

    public void updateBaseline(int counts, double time) {
        if (!this.isStable()) {
            this.totalCounts += counts;
            this.totalTime += time;
        }
    }

    public void updateAlarmLevels(double cps, double cpsErrorPercent, int detectionLevel) {
        if (!this.isStable()) {
            return;
        }

        double baseCps = this.getBaseline();
        double baseErrorValue = baseCps * (this.getBaselineError() / 100);
        double cpsErrorValue = baseCps * (cpsErrorPercent / 100);
        double overallErrorValue = Math.sqrt(baseErrorValue * baseErrorValue + cpsErrorValue * cpsErrorValue);
        double delta = detectionLevel * overallErrorValue;

        alarmLevelHigh = baseCps + delta;
        if (delta > baseCps) {
            alarmLevelLow = 0;
        } else {
            alarmLevelLow = baseCps - delta;
        }
    }

    public void reset() {
        this.totalTime = 0;
        this.totalCounts = 0;
        this.alarmLevelLow = 0;
        this.alarmLevelHigh = 0;
    }

    public boolean isStable() {
        double error = this.getBaselineError();
        boolean isPrecise = error > 0 && error <= errorThresholdPercent;

        return this.totalTime >= maxDuration || isPrecise;
    }

    public double getBaseline() {
        if (this.totalTime <= 0) {
            return 0;
        }

        return this.totalCounts / this.totalTime;
    }

    public double getBaselineError() {
        if (totalCounts <= 0) {
            return 0;
        }

        double sigma = Math.sqrt(totalCounts);
        return sigma / totalCounts * 100.0;
    }

    public double getAlarmLevelHigh() {
        return alarmLevelHigh;
    }

    public double getAlarmLevelLow() {
        return alarmLevelLow;
    }

    public int getRemainingTime() {
        int remaining = this.maxDuration - (int) this.totalTime;
        if (remaining < 0) {
            remaining = 0;
        }

        return remaining;
    }
}
}

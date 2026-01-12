package org.fe57.atomspectra;

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
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
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.PermissionChecker;
import androidx.core.graphics.drawable.IconCompat;
import androidx.core.util.Pair;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Locale;
import java.util.TimeZone;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeMap;

public class AtomSpectraService extends Service {
    private final static String TAG = AtomSpectraService.class.getSimpleName();

    private static final int FOREGROUND_PROCESS_ID = 1;

    private int frontCountsMin = 4, frontCountsMax = 8, histogramMinChannel = Constants.NOISE_DISCRIMINATOR_DEFAULT, adc_effective_bits = Constants.ADC_MAX;//34; // DPP discriminator parameter
    private boolean inversion = false;
    private boolean pileup = true;
    private static int first_channel = 0;   //First channel to show
    public static boolean canOpenAudio = false;
    public static boolean isCalibrated = false;

    //Output sound to the speaker
    private static boolean intervalSearchAlarmEnabled = false;
    private static int outputSoundID = -1;
    private static String outputSoundName = null;
    private static boolean inputSound = false;
    private static int inputSoundID = -1;
    private static String inputSoundName = null;

    private static final Integer intervalSearchAlarmSync = 1;
    private static AudioTrack intervalSearchAlarmAudioTrack = null;
    private static final int intervalSearchAlarmAudioTrackSampleRate = 44100;
    private static final double intervalSearchAlarmDuration = 0.5; // seconds
    private static float intervalSearchAlarmVolume = Constants.ALARM_VOLUME_DEFAULT;
    private static int intervalSearchAlarmDetectionLevel = Constants.ALARM_DETECTION_LEVEL_DEFAULT; // number of sigmas
    private static final int intervalSearchLowFreq = 500;
    private static final int intervalSearchBaseFreq = 1000;
    private static final int intervalSearchHighFreq = 1500;
    private static final AlarmBaseline intervalSearchAlarmBaseline = new AlarmBaseline();

    public static int lastCalibrationChannel = Constants.NUM_HIST_POINTS;
    public static int leftChannelInterval = 0;
    public static int rightChannelInterval = Constants.NUM_HIST_POINTS - 1;
    private static double leftEnergyInterval = 0;
    private static double rightEnergyInterval = 0;

    public static Calibration newCalibration = new Calibration();
    public static Spectrum ForegroundSpectrum = new Spectrum();
    public static Spectrum BackgroundSpectrum = new Spectrum();
    private boolean addGPS = false;


    public static boolean setSmooth = false;
    public static boolean showSpectrumChange = false;
    private static int delta_time = Constants.DEFAULT_DELTA_TIME;
    private static int delta_back_time_ratio = 4;
    public static boolean isStarted = false;
    public static boolean showCalibrationFunction = false;

    // atom swift integration
    private static boolean sendDataToAtomSwiftAppEnabled = false;
    private static String atomSwiftDRType = Constants.ATOMSWIFT_DR_DEFAULT;
    private static int atomSwiftIntermediateCps = 0;
    private static boolean atomSwiftHasIntermediateData = false;

    // data for spectrum
    private static final double[] histogram = new double[1024];
    public static long[] histogram_all_sp_change_fg = new long[Constants.NUM_HIST_POINTS];       //array to store delta
    public static long[] histogram_all_sp_change_bg = new long[Constants.NUM_HIST_POINTS];       //array to store delta
    public static final LinkedList<long[]> histogram_all_queue = new LinkedList<long[]>();      //array to store delta window
    private static final long[] referencePulse = new long[256];
    private static final double[] referenceDoublePulse = new double[256];
    private static final double[] realTimeAudioData = new double[1024];

    //data for background
    private static final double[] background_histogram = new double[1024];                              //back histogram to draw with the main histogram
    public static boolean background_show = false;
    private static boolean freeze_update_data = true;

    // dose rate
    private static DoseRate doseRateValue = new DoseRate();
    public static final float[] EnergyBinsDefault = new float[]{
            100f,
            200f,
            300f,
            400f,
            500f,
            600f,
            700f,
            800f,
            900f,
            1000f,
            1100f,
            1200f,
            1300f,
            1400f,
            1500f,
            1600f,
            1700f,
            1800f,
            1900f,
            2000f,
            2100f,
            2200f,
            2300f,
            2400f,
            2500f,
            2600f,
            2700f,
            2800f,
            2900f,
            3000f
    }; // energy bins in keV
    private static float[] EnergyBins = Arrays.copyOf(EnergyBinsDefault, EnergyBinsDefault.length);
    // for CsI 10x10x30 crystal
    public static final double[] EnergySensitivityDefault = new double[]{
            0.017197, //    0 - 100
            0.048018, //  100 - 200
            0.143591, //  200 - 300
            0.328291, //  300 - 400
            0.590599, //  400 - 500
            0.892094, //  500 - 600
            1.174569, //  600 - 700
            1.411520, //  700 - 800
            1.582268, //  800 - 900
            1.719324, //  900 - 1000
            1.830092, // 1000 - 1100
            1.959896, // 1100 - 1200
            2.083848, // 1200 - 1300
            2.196503, // 1300 - 1400
            2.305422, // 1400 - 1500
            2.412208, // 1500 - 1600
            2.518462, // 1600 - 1700
            2.625785, // 1700 - 1800
            2.735778, // 1800 - 1900
            2.850044, // 1900 - 2000
            2.970182, // 2000 - 2100
            3.097796, // 2100 - 2200
            3.234485, // 2200 - 2300
            3.381852, // 2300 - 2400
            3.541498, // 2400 - 2500
            3.715024, // 2500 - 2600
            3.904032, // 2600 - 2700
            4.110123, // 2700 - 2800
            4.317476, // 2800 - 2900
            4.579959, // 2900 - 3000
    }; // photon energy relative to Cs-137 energy (1.0 for 662 keV)
    private static double[] EnergySensitivity = Arrays.copyOf(EnergySensitivityDefault, EnergySensitivityDefault.length);

    // audio counts processing
    public static int counts_from_audio = 0; // number of counts detected from audio source during UPDATE_PERIOD
    public static int interval_counts_from_audio = 0; // number of counts in user defined energy range detected from audio source during UPDATE_PERIOD
    public static int[] binned_counts_from_audio = new int[EnergyBinsDefault.length]; // number of counts by energy bins detected from audio source during UPDATE_PERIOD

    public static long total_counts = 0; // number of counts collected in current spectrum (either from audio or USB)
    private static int cps = 0; // current cps value
    private static int cpsInterval = 0; // current cps value in user defined energy range

    private final int USB_DATA_SKIP_SECONDS = 3;
    private int skip_next_cps_int_usb_calc = 0; // 'hack' for usb devices to overcome issues with invalid data after reattach for the first few seconds
    private static int spgInterval = 0;
    private static boolean spgMidnightReset = false;
    private static int spgTimerIncrement = 0;
    private Spectrum spgAutosaveSpectrum = null;
    private Pair<OutputStreamWriter, Uri> spgAutosaveFile = null;
    private Date spgAutosaveFileCreated = null;

    private static int scale_factor = Constants.SCALE_DEFAULT;
    private static int main_scale_factor = Constants.SCALE_DEFAULT;
    private static final Integer sync_factor = 1;
    private final int mutabilityFlag = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ? PendingIntent.FLAG_IMMUTABLE : 0;

    // sent by AtomSpectraService with all the calculated values
    // read by AtomSpectra (histogram, cps, etc.)
    // read by AtomSpectraSettings (audio pulse data)
    public final static String ACTION_DATA_AVAILABLE =
            "org.fe57.atomspectra.ACTION_DATA_AVAILABLE";

    // sent by AtomSpectraService when recording is suspended/resumed due to hardware issues (disconnects)
    public final static String ACTION_RECORDING_SUSPENDED =
            "org.fe57.atomspectra.ACTION_RECORDING_SUSPENDED";
    public final static String ACTION_RECORDING_RESUMED =
            "org.fe57.atomspectra.ACTION_RECORDING_RESUMED";


    // --- AtomSpectraService data bundle parameters ---

    // +++ count rates +++
    // cps during the last second
    public final static String EXTRA_DATA_INT_CP1S =
            "org.fe57.atomspectra.EXTRA_DATA_INT_CP1S";
    // cps in user defined energy range during the last second
    public final static String EXTRA_DATA_INT_CP1S_INTERVAL =
            "org.fe57.atomspectra.EXTRA_DATA_INT_CP1S_INTERVAL";

    // +++ spectrum data (also used for spectrum change mode) +++
    // total counts in the foreground spectrum
    public final static String EXTRA_DATA_LONG_TOTAL_FG_COUNTS =
            "org.fe57.atomspectra.EXTRA_DATA_LONG_TOTAL_FG_COUNTS";
    // time in seconds in the foreground spectrum
    public final static String EXTRA_DATA_INT_FG_TOTAL_TIME =
            "org.fe57.atomspectra.EXTRA_DATA_INT_FG_TOTAL_TIME";
    // foreground spectrum counts array
    public final static String EXTRA_DATA_ARRAY_DOUBLE_FG_COUNTS =
            "org.fe57.atomspectra.EXTRA_DATA_ARRAY_DOUBLE_FG_COUNTS";
    // background spectrum counts array
    public final static String EXTRA_DATA_ARRAY_DOUBLE_BG_COUNTS =
            "org.fe57.atomspectra.EXTRA_DATA_ARRAY_DOUBLE_BG_COUNTS";
    // whether to show background spectrum
    public final static String EXTRA_DATA_BOOL_SHOW_BG_SPECTRUM =
            "org.fe57.atomspectra.EXTRA_DATA_BOOL_SHOW_BG_SPECTRUM";
    // calibration function
    public final static String EXTRA_DATA_ARRAY_DOUBLE_CALIBRATION_FUNCTION =
            "org.fe57.atomspectra.EXTRA_DATA_ARRAY_DOUBLE_CALIBRATION_FUNCTION";

    // +++ spectrum change counts and time +++
    // total counts in the foreground spectrum change window
    public final static String EXTRA_DATA_LONG_SP_CHNG_FG_TOTAL_COUNTS =
            "org.fe57.atomspectra.EXTRA_DATA_LONG_SP_CHNG_FG_TOTAL_COUNTS";
    // time in seconds in the foreground spectrum change window
    public final static String EXTRA_DATA_INT_SP_CHNG_FG_TOTAL_TIME =
            "org.fe57.atomspectra.EXTRA_DATA_INT_SP_CHNG_FG_TOTAL_TIME";
    // total counts in the background spectrum change window
    public final static String EXTRA_DATA_LONG_SP_CHNG_BG_TOTAL_COUNTS =
            "org.fe57.atomspectra.EXTRA_DATA_LONG_SP_CHNG_BG_TOTAL_COUNTS";
    // time in seconds in the background spectrum change window
    public final static String EXTRA_DATA_INT_SP_CHNG_BG_TOTAL_TIME =
            "org.fe57.atomspectra.EXTRA_DATA_INT_SP_CHNG_BG_TOTAL_TIME";

    // +++ search mode values +++
    // compensated dose rate in uSv/h
    public final static String EXTRA_DATA_DOUBLE_SEARCH_DR_C =
            "org.fe57.atomspectra.EXTRA_DATA_DOUBLE_SEARCH_DR_C";
    // error in compensated dose rate (1 sigma percent)
    public final static String EXTRA_DATA_DOUBLE_SEARCH_DR_C_ERROR =
            "org.fe57.atomspectra.EXTRA_DATA_DOUBLE_SEARCH_DR_C_ERROR";
    // compensated dose rate history array
    public final static String EXTRA_DATA_ARRAY_DOUBLE_SEARCH_DR_C_HISTORY =
            "org.fe57.atomspectra.EXTRA_DATA_ARRAY_DOUBLE_SEARCH_DR_C_HISTORY";
    // non-compensated dose rate in uSv/h
    public final static String EXTRA_DATA_DOUBLE_SEARCH_DR_N =
            "org.fe57.atomspectra.EXTRA_DATA_DOUBLE_SEARCH_DR_N";
    // error in non-compensated dose rate (1 sigma percent)
    public final static String EXTRA_DATA_DOUBLE_SEARCH_DR_N_ERROR =
            "org.fe57.atomspectra.EXTRA_DATA_DOUBLE_SEARCH_DR_N_ERROR";
    // non-compensated dose rate history array
    public final static String EXTRA_DATA_ARRAY_DOUBLE_SEARCH_DR_N_HISTORY =
            "org.fe57.atomspectra.EXTRA_DATA_ARRAY_DOUBLE_SEARCH_DR_N_HISTORY";
    // interval cps
    public final static String EXTRA_DATA_DOUBLE_SEARCH_INT_CPS =
            "org.fe57.atomspectra.EXTRA_DATA_DOUBLE_SEARCH_INT_CPS";
    // error in interval cps (1 sigma percent)
    public final static String EXTRA_DATA_DOUBLE_SEARCH_INT_CPS_ERROR =
            "org.fe57.atomspectra.EXTRA_DATA_DOUBLE_SEARCH_INT_CPS_ERROR";
    // TODO: add alarm levels and baseline info
    // interval cps history array
    public final static String EXTRA_DATA_ARRAY_DOUBLE_SEARCH_INT_CPS_HISTORY =
            "org.fe57.atomspectra.EXTRA_DATA_ARRAY_DOUBLE_SEARCH_INT_CPS_HISTORY";
    // interval cps high alarm history array
    public final static String EXTRA_DATA_ARRAY_DOUBLE_SEARCH_INT_CPS_HIGH_ALARM_HISTORY =
            "org.fe57.atomspectra.EXTRA_DATA_ARRAY_DOUBLE_SEARCH_INT_CPS_HIGH_ALARM_HISTORY";
    // interval cps low alarm history array
    public final static String EXTRA_DATA_ARRAY_DOUBLE_SEARCH_INT_CPS_LOW_ALARM_HISTORY =
            "org.fe57.atomspectra.EXTRA_DATA_ARRAY_DOUBLE_SEARCH_INT_CPS_LOW_ALARM_HISTORY";
    public final static String EXTRA_DATA_ARRAY_DOUBLE_SEARCH_INT_CPS_BASELINE_HISTORY =
            "org.fe57.atomspectra.EXTRA_DATA_ARRAY_DOUBLE_SEARCH_INT_CPS_BASELINE_HISTORY";

    // +++ spectra pro data +++
    public final static String EXTRA_DATA_ARRAY_LONG_SERIAL_SCOPE_COUNTS =
            "org.fe57.atomspectra.EXTRA_DATA_ARRAY_LONG_SERIAL_SCOPE_COUNTS";
    public final static String EXTRA_DATA_ARRAY_LONG_SERIAL_SPECTRUM_COUNTS =
            "org.fe57.atomspectra.EXTRA_DATA_ARRAY_LONG_SERIAL_SPECTRUM_COUNTS";

    // +++ audio data +++
    // latest data from audio input
    public final static String EXTRA_DATA_ARRAY_DOUBLE_REALTIME_AUDIO_DATA =
            "org.fe57.atomspectra.EXTRA_DATA_ARRAY_DOUBLE_REALTIME_AUDIO_DATA";
    // reference pulse shape from audio input
    public final static String EXTRA_DATA_ARRAY_DOUBLE_REFERENCE_PULSE_DATA =
            "org.fe57.atomspectra.EXTRA_DATA_ARRAY_DOUBLE_REFERENCE_PULSE_DATA";

    // --- [end] AtomSpectraService data bundle parameters ---


    private final static String SERVICE_INF_ID = "Service command -inf";
    private final static String SERVICE_CAL_ID = "Service command -cal";
    private final static String SERVICE_STA_ID = "Service command -sta";
    private final static String SERVICE_STO_ID = "Service command -sto";
    private final static String SERVICE_STT_ID = "Service command -stt";
    private final static String SERVICE_MODE_ID = "Service command -mode 0";

    public final static String CHANNEL_ID = "AtomSpectraService";

    private static final int USB_WAIT_DEVICE = 600;
    private static final Integer data_from_usb_sync = 1;

    // RECORDING VARIABLES  
    private static AudioRecord AR = null;
    private static final Integer ARLock = 1;        //Locker for AudioRecord
    private static final Integer audioCaptureSync = 1; // lock for managing audio capturing timer
    private static boolean ARShowAbsentMessage = true;
    private static int BufferSize;                    // Length of the chunks read from the hardware audio buffer
    //    private static Thread Record_Thread = null;      // The thread filling up the audio buffer (queue)
    private static final int AUDIO_SOURCE_VOICE = MediaRecorder.AudioSource.VOICE_RECOGNITION;
    private static final int AUDIO_SOURCE_RAW = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N ? MediaRecorder.AudioSource.UNPROCESSED : MediaRecorder.AudioSource.VOICE_RECOGNITION;      //only from API>=24
    private static int SAMPLE_RATE = 44100;
    private static final int CHANNEL_CONFIG = android.media.AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = android.media.AudioFormat.ENCODING_PCM_16BIT;

    private static double SensG;
    private static double SensGCompensated;
    private static double backgroundCps;
    private static int SEARCH_FAST = 25, SEARCH_MEDIUM = 35, SEARCH_SLOW = 70;

    private static int SearchFSM = 0; //0 - fast, 1 - medium, 2 - slow

    private int dataFromAudioSourceUpdatePeriod = 1000; // ms

    private static final int[] cpsArray = new int[1000 / Constants.UPDATE_PERIOD]; // number of counts during last second, measured approximately each 0.1 sec
    private static final int[] cpsArrayInterval = new int[1000 / Constants.UPDATE_PERIOD]; // same as above but for user selected energy range
    private static final int[][] cpsArrayEnergyBins = new int[1000 / Constants.UPDATE_PERIOD][EnergyBins.length]; // same as above but counts stored separate for each energy bin
    private static long audioCaptureTimer = 0;
    private static long audioCaptureOldTimer = 0;
    private static long captureAudioTaskInterval = 0;
    private static int cpsPos = 0;
    private static int compressGraph = Constants.COMPRESS_GRAPH_SUM;

    public final static int INPUT_NONE = 0;             //Nothing
    public final static int INPUT_SERIAL = 1;           //Use serial for NanoPro
    public final static int INPUT_AUDIO = 2;            //Use audio channel
    public static int inputType = INPUT_NONE;           // current input type
    public static String inputDeviceInfo = "";          // current device info
    // TODO: verify it actually requires sync
    private static final Integer inputSync = 1;

    private static final Integer recordingSuspendedSync = 1;
    public static Date recordingSuspendedAt = null;
    public static Date recordingResumedAt = null;
    public static int recordingSuspendInputType = INPUT_NONE;
    public static final int RECORDING_SUSPEND_REASON_NONE = 0;
    public static final int RECORDING_SUSPEND_REASON_AUDIO_REMOVED = 1;
    public static final int RECORDING_SUSPEND_REASON_AUDIO_ADDED = 2;
    public static final int RECORDING_SUSPEND_REASON_USB_DISCONNECT = 3;
    public static int recordingSuspendReason = RECORDING_SUSPEND_REASON_NONE;
    public static boolean isRecordingSuspended = false;
    public static HashSet<String> recordingSuspendedWithAudioDevices = new HashSet<>();


    private SharedPreferences sp;

    private final AudioDeviceCallback audioChanged = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) ? createAudioDeviceCallback() : null;

    private AtomSpectraSerial usbDevice = null;

    private GPSLocator Locator = null;

    public static AlarmBaseline getIntervalSearchAlarmBaseline() {
        return intervalSearchAlarmBaseline;
    }

    @TargetApi(Build.VERSION_CODES.M)
    private AudioDeviceCallback createAudioDeviceCallback() {
        // simple (based on assumptions) tracking of audio device configuration changes
        // case 1: working with external audio device, external removed - waiting for reconnect
        // case 2: working with internal audio device, external added - waiting for external disconnect
        // note: external deices are sometimes trigger multiple added/removed calls
        return new AudioDeviceCallback() {
            final HashSet<String> activeInputDeviceList = new HashSet<>();

            @Override
            public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
//                String debug_msg = "Added audio devices:";
//                for (AudioDeviceInfo device : addedDevices) {
//                    debug_msg += "\n" + device.getProductName().toString() + "|source:" + device.isSource() + "|sink:" + device.isSink();
//                }
//                showToastInMainLooper(debug_msg, Toast.LENGTH_LONG);

                super.onAudioDevicesAdded(addedDevices);
                HashSet<String> prevState = new HashSet<>(activeInputDeviceList);
                for (AudioDeviceInfo addedDevice : addedDevices) {
                    String deviceName = addedDevice.getProductName().toString();
                    if (!activeInputDeviceList.contains(deviceName)) {
                        activeInputDeviceList.add(deviceName);
                        AtomSpectraLog.addMessage(service_context, getStringOrDefaultLocale(R.string.log_audio_input_added, deviceName));
                    }
                }
                if (prevState.equals(activeInputDeviceList)) {
                    return;
                }

                if (!freeze_update_data && inputType == INPUT_AUDIO) {
                    synchronized (recordingSuspendedSync) {
                        if (isRecordingSuspended) {
                            if (recordingSuspendInputType != INPUT_AUDIO) {
                                return;
                            }

                            if (recordingSuspendReason == RECORDING_SUSPEND_REASON_AUDIO_REMOVED) {
                                if (activeInputDeviceList.equals(recordingSuspendedWithAudioDevices)) {
                                    restoreAudioRecording();
                                }
                            }
                        } else {
                            recordingSuspendedWithAudioDevices = new HashSet<>(prevState);
                            suspendAudioRecording(RECORDING_SUSPEND_REASON_AUDIO_ADDED);
                        }
                    }
                }
            }

            @Override
            public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
//                String debug_msg = "Removed audio devices:";
//                for (AudioDeviceInfo device : removedDevices) {
//                    debug_msg += "\n" + device.getProductName().toString() + "|source:" + device.isSource() + "|sink:" + device.isSink();
//                }
//                showToastInMainLooper(debug_msg, Toast.LENGTH_LONG);

                super.onAudioDevicesRemoved(removedDevices);
                HashSet<String> prevState = new HashSet<>(activeInputDeviceList);
                for (AudioDeviceInfo removedDevice : removedDevices) {
                    if (removedDevice.isSource()) {
                        String deviceName = removedDevice.getProductName().toString();
                        if (activeInputDeviceList.contains(deviceName)) {
                            activeInputDeviceList.remove(deviceName);
                            AtomSpectraLog.addMessage(service_context, getStringOrDefaultLocale(R.string.log_audio_input_removed, deviceName));
                        }
                    }
                }
                if (prevState.equals(activeInputDeviceList)) {
                    return;
                }

                if (!freeze_update_data && inputType == INPUT_AUDIO) {
                    synchronized (recordingSuspendedSync) {
                        if (isRecordingSuspended) {
                            if (recordingSuspendInputType != INPUT_AUDIO) {
                                return;
                            }

                            if (recordingSuspendReason == RECORDING_SUSPEND_REASON_AUDIO_ADDED) {
                                if (activeInputDeviceList.equals(recordingSuspendedWithAudioDevices)) {
                                    restoreAudioRecording();
                                }
                            }
                        } else {
                            recordingSuspendedWithAudioDevices = new HashSet<>(prevState);
                            suspendAudioRecording(RECORDING_SUSPEND_REASON_AUDIO_REMOVED);
                        }
                    }
                }
            }
        };
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
        BackgroundSpectrum.initSpectrumData(Constants.NUM_HIST_POINTS, Calibration.defaultCalibration(Constants.NUM_HIST_POINTS));
        background_show = false;
        freeze_update_data = true;
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(FOREGROUND_PROCESS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE | ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(FOREGROUND_PROCESS_ID, notification);
        }
        if (isStarted)
            return START_NOT_STICKY;
        if (intent != null) {
            UsbDevice device;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                device = intent.getParcelableExtra(Constants.USB_DEVICE, UsbDevice.class);
            } else {
                device = intent.getParcelableExtra(Constants.USB_DEVICE);
            }
            if (device != null) {
                SystemClock.sleep(USB_WAIT_DEVICE);
                onUSBAttached(device);
            } else {
                inputType = INPUT_AUDIO;
                inputDeviceInfo = getAudioDeviceInfoText(null);
                sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_CALIBRATION).putExtra(Constants.ACTION_PARAMETERS.UPDATE_USB_CALIBRATION, false).setPackage(Constants.PACKAGE_NAME));
                sendDataToUI();
            }
        } else {
            UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
            UsbDevice device = AtomSpectraSerial.scanForSpectraProDevice(manager);
            if (device != null) {
                if (manager.hasPermission(device)) {
                    SystemClock.sleep(USB_WAIT_DEVICE);
                    onUSBAttached(device);
                } else {
                    onUSBNoAccess();
                }
            } else {
                inputType = INPUT_AUDIO;
                inputDeviceInfo = getAudioDeviceInfoText(null);
                sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_CALIBRATION).putExtra(Constants.ACTION_PARAMETERS.UPDATE_USB_CALIBRATION, false).setPackage(Constants.PACKAGE_NAME));
                sendDataToUI();
            }
        }

        sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_MENU).setPackage(Constants.PACKAGE_NAME));
        isStarted = true;
        return START_NOT_STICKY;
    }

    private Notification createNewServiceNotification() {
        String notifyString = "";
        if (isRecordingSuspended) {
            notifyString = getStringOrDefaultLocale(R.string.recording_suspended_notification).toUpperCase();
        } else if (!freeze_update_data) {
            if (inputType == INPUT_AUDIO) {
                notifyString = getStringOrDefaultLocale(R.string.app_bar_audio_action);
            }
            if (inputType == INPUT_SERIAL) {
                notifyString = getStringOrDefaultLocale(R.string.app_bar_usb_action);
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

    private final SharedPreferences.OnSharedPreferenceChangeListener onSharedPreferenceChangeListener = (sharedPreferences, s) -> loadSettings();

    private void loadSettings() {
        try {
            SensG = sp.getInt(Constants.CONFIG.CONF_SENSG, Constants.SENSG_DEFAULT);
        } catch (Exception e) {
            SensG = (int) sp.getFloat(Constants.CONFIG.CONF_SENSG, Constants.SENSG_DEFAULT);
            SharedPreferences.Editor editor = sp.edit();
            editor.putInt(Constants.CONFIG.CONF_SENSG, (int) SensG);
            editor.apply();
        }

        try {
            SensGCompensated = sp.getInt(Constants.CONFIG.CONF_SENSG_COMPENSATED, Constants.SENSG_COMPENSATED_DEFAULT);
        } catch (Exception e) {
            SensGCompensated = (int) sp.getFloat(Constants.CONFIG.CONF_SENSG_COMPENSATED, Constants.SENSG_COMPENSATED_DEFAULT);
            SharedPreferences.Editor editor = sp.edit();
            editor.putInt(Constants.CONFIG.CONF_SENSG_COMPENSATED, (int) SensGCompensated);
            editor.apply();
        }

        try {
            backgroundCps = sp.getInt(Constants.CONFIG.CONF_BACKGROUND, Constants.BACKGND_CPS_DEFAULT);
        } catch (Exception e) {
            backgroundCps = (int) sp.getFloat(Constants.CONFIG.CONF_BACKGROUND, Constants.BACKGND_CPS_DEFAULT);
            SharedPreferences.Editor editor = sp.edit();
            editor.putInt(Constants.CONFIG.CONF_BACKGROUND, (int) backgroundCps);
            editor.apply();
        }

        smooth_basic_window = -1 + 8 * sp.getInt(Constants.CONFIG.CONF_GOLAY_WINDOW, Constants.DEFAULT_GOLAY_WINDOW);
        delta_time = sp.getInt(Constants.CONFIG.CONF_SPECTRUM_CHANGE_DIFF_TIME, Constants.DEFAULT_DELTA_TIME);
        SearchFSM = sp.getInt(Constants.CONFIG.CONF_SEARCH_MODE, 0);
        SEARCH_FAST = sp.getInt(Constants.CONFIG.CONF_SEARCH_FAST, Constants.SEARCH_FAST_DEFAULT);
        SEARCH_SLOW = sp.getInt(Constants.CONFIG.CONF_SEARCH_SLOW, Constants.SEARCH_SLOW_DEFAULT);
        SEARCH_MEDIUM = sp.getInt(Constants.CONFIG.CONF_SEARCH_MEDIUM, Constants.SEARCH_MEDIUM_DEFAULT);
        dataFromAudioSourceUpdatePeriod = 1000 / sp.getInt(Constants.CONFIG.CONF_DOSE_UPDATE, Constants.UPDATE_DOSE_DEFAULT);
        adc_effective_bits = Constants.MinMax(sp.getInt(Constants.CONFIG.CONF_ROUNDED, Constants.ADC_DEFAULT), Constants.ADC_MIN, Constants.ADC_MAX);
        frontCountsMin = sp.getInt(Constants.CONFIG.CONF_MIN_POINTS, Constants.MIN_FRONT_POINTS_DEFAULT);
        frontCountsMax = sp.getInt(Constants.CONFIG.CONF_MAX_POINTS, Constants.MAX_FRONT_POINTS_DEFAULT);
        histogramMinChannel = sp.getInt(Constants.CONFIG.CONF_NOISE, Constants.NOISE_DISCRIMINATOR_DEFAULT);
        inversion = sp.getBoolean(Constants.CONFIG.CONF_INVERSION, Constants.INVERSE_DEFAULT);
        pileup = sp.getBoolean(Constants.CONFIG.CONF_PILE_UP, Constants.PILE_UP_DEFAULT);
        spgInterval = sp.getInt(Constants.CONFIG.CONF_SPG_DELTA_DURATION, Constants.SPG_INTERVAL_DEFAULT);
        spgMidnightReset = sp.getBoolean(Constants.CONFIG.CONF_SPG_MIDNIGHT_RESET, Constants.SPG_MIDNIGHT_RESET_DEFAULT);
        try {
            compressGraph = sp.getInt(Constants.CONFIG.CONF_COMPRESS_GRAPH, Constants.COMPRESS_GRAPH_SUM);
        } catch (Exception e) {
            compressGraph = Constants.COMPRESS_GRAPH_SUM;
        }
        CharSequence[] data = getResources().getTextArray(R.array.compress_graph_array);
        compressGraph = Constants.MinMax(compressGraph, 0, data.length - 1);
        isCalibrated = sp.getBoolean(Constants.CONFIG.CONF_CALIBRATED, true);
        // --- interval search
        boolean intervalSearchAlarmEnabledPrev = intervalSearchAlarmEnabled;
        intervalSearchAlarmEnabled = sp.getBoolean(Constants.CONFIG.CONF_OUTPUT_SOUND, false) && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M);
        outputSoundID = sp.getInt(Constants.CONFIG.CONF_OUTPUT_SOUND_DEVICE_ID, -1);
        outputSoundName = sp.getString(Constants.CONFIG.CONF_OUTPUT_SOUND_DEVICE_NAME, "(none)");
        intervalSearchAlarmVolume = sp.getInt(Constants.CONFIG.CONF_SEARCH_ALARM_VOLUME, Constants.ALARM_VOLUME_DEFAULT) / 100.0f;
        intervalSearchAlarmDetectionLevel = sp.getInt(Constants.CONFIG.CONF_SEARCH_DETECTION_LEVEL, Constants.ALARM_DETECTION_LEVEL_DEFAULT);
        // --- end interval search

        addGPS = sp.getBoolean(Constants.CONFIG.CONF_ADD_GPS_TO_FILES, false);
        sendDataToAtomSwiftAppEnabled = sp.getBoolean(Constants.CONFIG.CONF_SEND_DATA_TO_ATOMSWIFT, Constants.SEND_DATA_TO_ATOMSWIFT_DEFAULT);
        atomSwiftDRType = sp.getString(Constants.CONFIG.CONF_ATOMSWIFT_DOSE_RATE, Constants.ATOMSWIFT_DR_DEFAULT);

        boolean inputS = sp.getBoolean(Constants.CONFIG.CONF_INPUT_SOUND, false) && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M);
        int inputSID = sp.getInt(Constants.CONFIG.CONF_INPUT_SOUND_DEVICE_ID, -1);
        String inputSN = sp.getString(Constants.CONFIG.CONF_INPUT_SOUND_DEVICE_NAME, "(none)");
        if (inputS != inputSound || inputSID != inputSoundID || !inputSN.equals(inputSoundName)) {
            synchronized (ARLock) {
                if (AR != null) {
                    AR.stop();
                    AR.release();
                    AR = null;
                }
                inputSound = inputS;
                inputSoundID = inputSID;
                inputSoundName = inputSN;
            }
        }

        TreeMap<Float, Double> sensitivityTable = PrefHelper.getSensitivityTableOrDefault(service_context);
        ArrayList<Float> sortedEnergyList = new ArrayList<>(sensitivityTable.keySet());
        Arrays.fill(EnergyBins, 0);
        Arrays.fill(EnergySensitivity, 0);
        int baseBin = EnergyBins.length - sortedEnergyList.size();
        if (baseBin < 0) {
            baseBin = 0;
        }
        for (int i = 0; i < sortedEnergyList.size(); i++) {
            float energy = sortedEnergyList.get(i);
            double sens = sensitivityTable.get(energy);
            EnergyBins[i + baseBin] = energy;
            EnergySensitivity[i + baseBin] = sens;
        }

        // post read actions
        setAlarmAudioTrackDevice();
        if (!freeze_update_data) {
            resetSearchWindow();
            if (intervalSearchAlarmEnabled != intervalSearchAlarmEnabledPrev) {
                stopIntervalSearchAlarmTimer();
                if (intervalSearchAlarmEnabled) {
                    startIntervalSearchAlarmTimer();
                }
            }
        }

    }

    public static int getScaleFactor() {
        return scale_factor;
    }

    public static void setScaleFactor(int factor) {
        synchronized (sync_factor) {
            scale_factor = factor;
            switch (scale_factor) {
                case 0:
                case 1:
                case 2:
                case 3:
                case 4:
                case 5:
                case 6:
                case 7:
                    scale_factor = Constants.MinMax(
                            factor,
                            Constants.SCALE_MIN,
                            Constants.SCALE_MAX);
                    first_channel =
                            Constants.MinMax(
                                    first_channel,
                                    0,
                                    Constants.NUM_HIST_POINTS - Constants.WINDOW_OUTPUT_SIZE * (1 << (Constants.SCALE_MAX - scale_factor)));
                    break;
                case Constants.SCALE_DOSE_MODE:
                    break;
                case Constants.SCALE_OSCILLOSCOPE_MODE:
                    break;
                case Constants.SCALE_AUDIO_REFERENCE_PULSE_MODE:
                    break;
            }
        }
    }

    public static void saveScaleFactor() {
        synchronized (sync_factor) {
            if (scale_factor <= Constants.SCALE_MAX)
                main_scale_factor = scale_factor;
        }
    }

    public static int getSavedScaleFactor() {
        return main_scale_factor;
    }

    public static void restoreScaleFactor() {
        setScaleFactor(main_scale_factor <= Constants.SCALE_MAX ? main_scale_factor : Constants.SCALE_DEFAULT);
    }

    public static int getFirstChannel() {
        synchronized (sync_factor) {
            return first_channel;
        }
    }

    public static void setFirstChannel(int channel) {
        synchronized (sync_factor) {
            first_channel = Constants.MinMax(
                    channel,
                    0,
                    Constants.NUM_HIST_POINTS - Constants.WINDOW_OUTPUT_SIZE * (1 << (Constants.SCALE_MAX - Constants.MinMax(
                            scale_factor,
                            Constants.SCALE_MIN,
                            Constants.SCALE_MAX))));
        }
    }

    public static void setEnergyInterval(double leftEnergy, double rightEnergy) {
        leftChannelInterval = Constants.MinMax(ForegroundSpectrum.getSpectrumCalibration().toChannel(leftEnergy), 0, Constants.NUM_HIST_POINTS - 1);
        leftEnergyInterval = leftEnergy;
        rightChannelInterval = Constants.MinMax(ForegroundSpectrum.getSpectrumCalibration().toChannel(rightEnergy), 0, Constants.NUM_HIST_POINTS - 1);
        rightEnergyInterval = rightEnergy;
    }

    public static void setChannelInterval(int leftChannel, int rightChannel) {
        leftChannelInterval = Constants.MinMax(leftChannel, 0, Constants.NUM_HIST_POINTS - 1);
        leftEnergyInterval = ForegroundSpectrum.getSpectrumCalibration().toEnergy(leftChannelInterval);
        rightChannelInterval = Constants.MinMax(rightChannel, 0, Constants.NUM_HIST_POINTS - 1);
        rightEnergyInterval = ForegroundSpectrum.getSpectrumCalibration().toEnergy(rightChannelInterval);
    }

    public static void resetInterval() {
        leftChannelInterval = 0;
        rightChannelInterval = Constants.NUM_HIST_POINTS - 1;
        leftEnergyInterval = rightEnergyInterval = 0;
    }

    public static void recalculateInterval() {
        if (leftChannelInterval != 0 || rightChannelInterval != Constants.NUM_HIST_POINTS - 1) {
            setEnergyInterval(leftEnergyInterval, rightEnergyInterval);
        }
    }

    private final Integer spgAutosaveSync = 1;
    private Timer spgAutosaveTimer;

    private void startSpgAutosaveTimer() {
        synchronized (spgAutosaveSync) {
            if (spgAutosaveTimer != null) {
                // TODO: localize
                String message = "ERROR: trying to start spectrogram recording while recording is already in progress";
                showToastInMainLooper(message, Toast.LENGTH_LONG);
                AtomSpectraLog.addMessage(service_context, message);
                return;
            }

            spgAutosaveTimer = new Timer();
            TimerTask spgAutosaveTask = new TimerTask() {
                @Override
                public void run() {
                    synchronized (spgAutosaveSync) {
                        if (spgInterval > 0) {
                            if (!freeze_update_data) {
                                spgTimerIncrement += 1;
                                if (spgTimerIncrement >= spgInterval) {
                                    createOrUpdateSpectrogramFile();
                                    spgTimerIncrement = 0;
                                }
                            } else {
                                spgTimerIncrement = 0;
                                closeSpectrogramFile();
                            }
                        }
                    }
                }
            };

            spgTimerIncrement = spgInterval; // save base spectrum on first timer trigger
            // delay to let pro device provide valid data
            spgAutosaveTimer.schedule(spgAutosaveTask, USB_DATA_SKIP_SECONDS * 1000, 1000);
        }
    }

    private void stopSpgAutosaveTimer() {
        synchronized (spgAutosaveSync) {
            if (spgAutosaveTimer != null) {
                spgAutosaveTimer.cancel();
                spgAutosaveTimer.purge();
                spgAutosaveTimer = null;
            }

            closeSpectrogramFile();
        }
    }

    private Timer intervalSearchAlarmTimer;

    private void startIntervalSearchAlarmTimer() {
        synchronized (intervalSearchAlarmSync) {
            if (intervalSearchAlarmTimer != null) {
                // TODO: localize
                String message = "ERROR: trying to start interval search alarm timer while timer is already in progress";
                showToastInMainLooper(message, Toast.LENGTH_LONG);
                AtomSpectraLog.addMessage(service_context, message);
                return;
            }
            intervalSearchAlarmTimer = new Timer();
            TimerTask intervalSearchAlarmTask = new TimerTask() {
                @Override
                public void run() {
                    synchronized (intervalSearchAlarmSync) {
                        if (intervalSearchAlarmEnabled && !freeze_update_data && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            if (intervalSearchAlarmAudioTrack != null) {
                                double currentCps = doseRateValue.intervalCps;
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

    private void closeSpectrogramFile() {
        synchronized (spgAutosaveSync) {
            spgAutosaveSpectrum = null;
            if (spgAutosaveFile != null) {
                try {
                    spgAutosaveFile.first.close();
                } catch (IOException e) {
                    //
                }
            }
            spgAutosaveFile = null;
            spgAutosaveFileCreated = null;
        }
    }

    //Audio input data
    public static final int SET_AUDIO_RAW = 2;
    public static final int SET_AUDIO_VOICE = 1;
    public static final int SET_AUDIO_OK = 0;
    public static final int SET_AUDIO_ERROR = -1;
    private byte[] AudioBytes = null; //Array containing the audio data bytes
    private int AudioBytesRead = 0;
    private int[] AudioData = null; //Array containing the audio samples
    private static int AudioSource = AUDIO_SOURCE_VOICE;
    public static int SetAudioSource = SET_AUDIO_OK;
    private Context service_context = null;
    private static int smooth_basic_window = 7;

    @SuppressLint({"UnspecifiedRegisterReceiverFlag", "DiscouragedApi"})
    public void Start(final Context context) {
        setLocaleFromPreferences(context);
        this.service_context = context;

        boolean hasFeatureGPS = getPackageManager().hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS);
        boolean hasFeatureNetwork = getPackageManager().hasSystemFeature(PackageManager.FEATURE_LOCATION_NETWORK);
        if (Locator == null) {
            Locator = new GPSLocator(getApplicationContext());
        }
        if ((hasFeatureGPS || hasFeatureNetwork) && getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE).getBoolean(Constants.CONFIG.CONF_ADD_GPS_TO_FILES, false)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                final Context id = this;
                if (PermissionChecker.checkSelfPermission(id, Manifest.permission.ACCESS_FINE_LOCATION) != PermissionChecker.PERMISSION_GRANTED) {
                    Locator.stopUsingGPS();
                    addGPS = false;
                } else {
                    Locator.startUsingGPS();
                    if (!Locator.hasGPS) {
                        Locator.stopUsingGPS();
                        addGPS = false;
                    }
                }
            } else {
                Locator.startUsingGPS();
                if (!Locator.hasGPS) {
                    Locator.stopUsingGPS();
                }
            }
        }

        ForegroundSpectrum.setSuffix(getStringOrDefaultLocale(R.string.hist_suffix));
        BackgroundSpectrum.setSuffix(getStringOrDefaultLocale(R.string.background_suffix));
        usbDevice = new AtomSpectraSerial(context);
        sp = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        sp.registerOnSharedPreferenceChangeListener(onSharedPreferenceChangeListener);
        smooth_basic_window = -1 + 8 * sp.getInt(Constants.CONFIG.CONF_GOLAY_WINDOW, Constants.DEFAULT_GOLAY_WINDOW);

        initOutputAudioTrack();
        loadSettings();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(broadcastReceiver,
                    makeAtomSpectraServiceIntentFilter(), Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(broadcastReceiver,
                    makeAtomSpectraServiceIntentFilter());
        }

        try {
            //Some devices says they have this ability by it doesn't work. Switched off for a delay
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                if (manager != null && manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) != null)
                    AudioSource = ((sp.getInt(Constants.CONFIG.CONF_AUDIO_SOURCE, SET_AUDIO_RAW)) == SET_AUDIO_RAW) ? AUDIO_SOURCE_RAW : AUDIO_SOURCE_VOICE;
            }
        } catch (IllegalArgumentException e) {
            showToastInMainLooper(R.string.no_audio_available, Toast.LENGTH_LONG);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (manager != null)
                manager.registerAudioDeviceCallback(audioChanged, null);
        }

        inputType = INPUT_NONE;
        inputDeviceInfo = "NONE";
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
                    showToastInMainLooper(R.string.no_audio_output_available, Toast.LENGTH_LONG);
                }
                if (intervalSearchAlarmAudioTrack != null && intervalSearchAlarmAudioTrack.getState() != AudioTrack.STATE_NO_STATIC_DATA) {
                    intervalSearchAlarmAudioTrack.release();
                    intervalSearchAlarmAudioTrack = null;
                    showToastInMainLooper(R.string.no_audio_output_available, Toast.LENGTH_LONG);
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

    public static AudioDeviceInfo getDeviceInput(Context context, int lastID, String lastName, boolean same) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioDeviceInfo deviceIn = null;
            AudioDeviceInfo deviceEmptyIn = null;
            int newID = -1;
            int newEmptyID = -1;
            AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            AudioDeviceInfo[] devices = manager.getDevices(AudioManager.GET_DEVICES_INPUTS);
            if (devices != null && devices.length > 0) {
                for (AudioDeviceInfo device : devices) {
                    if (device.getType() == AudioDeviceInfo.TYPE_AUX_LINE ||
                            device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                            device.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC ||
                            device.getType() == AudioDeviceInfo.TYPE_LINE_ANALOG ||
                            device.getType() == AudioDeviceInfo.TYPE_LINE_DIGITAL ||
                            device.getType() == AudioDeviceInfo.TYPE_USB_HEADSET ||
                            device.getType() == AudioDeviceInfo.TYPE_USB_DEVICE ||
                            device.getType() == AudioDeviceInfo.TYPE_USB_ACCESSORY ||
                            device.getType() == AudioDeviceInfo.TYPE_WIRED_HEADSET) {
                        if (same) {
                            if (lastID == device.getId() || device.getProductName().equals(lastName)) {
                                return device;   //Return the device we want to find
                            }
                        } else {
                            if (lastID < device.getId()) {
                                if (newID == -1 || newID > device.getId()) {
                                    newID = device.getId();
                                    deviceIn = device;
                                }
                            }
                            //find the smallest device id
                            if (newEmptyID == -1 || newEmptyID > device.getId()) {
                                newEmptyID = device.getId();
                                deviceEmptyIn = device;
                            }
                        }
                    }
                }
                if (same) {
                    return null;
                }
                if (newID == -1) {
                    //no more devices
                    return deviceEmptyIn;
                }
            }
            return deviceIn;
        } else {
            return null;
        }
    }

    private static IntentFilter makeAtomSpectraServiceIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Constants.ACTION.ACTION_STOP_FOREGROUND);
        intentFilter.addAction(Constants.ACTION.ACTION_START_FOREGROUND);
        intentFilter.addAction(Constants.ACTION.ACTION_UPDATE_NOTIFICATION);
        intentFilter.addAction(Constants.ACTION.ACTION_USB_ATTACHED);
        intentFilter.addAction(Constants.ACTION.ACTION_USB_DETACHED);
        intentFilter.addAction(Constants.ACTION.ACTION_USB_HAS_DATA);
        intentFilter.addAction(Constants.ACTION.ACTION_USB_HAS_ANSWER);
        intentFilter.addAction(Constants.ACTION.ACTION_FREEZE_DATA);
        intentFilter.addAction(Constants.ACTION.ACTION_CLEAR_SPECTRUM);
        intentFilter.addAction(Constants.ACTION.ACTION_CLEAR_IMPULSE);
        intentFilter.addAction(Constants.ACTION.ACTION_SEND_USB_COMMAND);
        intentFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        intentFilter.addAction(Constants.ACTION.ACTION_UPDATE_GPS);
        intentFilter.addAction(Intent.ACTION_BATTERY_LOW);
        intentFilter.addAction(Constants.ACTION.ACTION_CHECK_GPS_AVAILABILITY);
        intentFilter.addAction(Constants.ACTION.ACTION_UPDATE_GRAPH);
        return intentFilter;
    }

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
                setFreeze(true);
                DeleteSpc();
                sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_MENU).setPackage(Constants.PACKAGE_NAME));
                sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_CALIBRATION).putExtra(Constants.ACTION_PARAMETERS.UPDATE_USB_CALIBRATION, false).setPackage(Constants.PACKAGE_NAME));
                sendDataToUI();
                return;
            }
            if (Constants.ACTION.ACTION_CLEAR_IMPULSE.equals(action)) {
                Arrays.fill(referencePulse, 0);
                return;
            }
            if (Constants.ACTION.ACTION_SEND_USB_COMMAND.equals(action)) {
                if (inputType != INPUT_SERIAL || usbDevice == null || !usbDevice.isOpened()) {
                    return;
                }
                String command = intent.getStringExtra(Constants.ACTION_PARAMETERS.USB_COMMAND_DATA);
                String id = intent.getStringExtra(Constants.ACTION_PARAMETERS.USB_COMMAND_ID);
                if (command != null && id != null) {
                    usbDevice.sendTextCommand(command, id);
                }
                return;
            }
            if (Constants.ACTION.ACTION_FREEZE_DATA.equals(action)) {
                setFreeze(intent.getBooleanExtra(AtomSpectraSerial.EXTRA_DATA_TYPE, true));
                return;
            }
            if (Constants.ACTION.ACTION_STOP_FOREGROUND.equals(action)) {
                Log.i(TAG, "Received Stop Foreground Intent");
                canOpenAudio = false;
                releaseOutputAudioTrack();
                Log.d(TAG, "recording Stop");
                Stop();
                return;
            }
            if (Constants.ACTION.ACTION_START_FOREGROUND.equals(action)) {
                Log.d(TAG, "Received Start Foreground Intent");
                return;
            }
            if (Intent.ACTION_BATTERY_LOW.equals(action) && AtomSpectraService.ForegroundSpectrum.isChanged()) {
                saveCurrentSpectrum("battery_low");
            }
            if (Constants.ACTION.ACTION_CHECK_GPS_AVAILABILITY.equals(action)) {
                checkGPS();
            }
            if (Constants.ACTION.ACTION_UPDATE_GRAPH.equals(action)) {
                sendDataToUI();
            }
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                if (inputType == INPUT_SERIAL) {
                    onUSBDetached();
                }

                return;
            }
            if (Constants.ACTION.ACTION_UPDATE_GPS.equals(action)) {
                if (!freeze_update_data) {
                    ForegroundSpectrum.setLocation(Locator.getLocation()).updateComments();
                }
            }
            if (Constants.ACTION.ACTION_USB_ATTACHED.equals(action)) {
                UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
                UsbDevice device;
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    device = intent.getParcelableExtra(Constants.USB_DEVICE, UsbDevice.class);
                } else {
                    device = intent.getParcelableExtra(Constants.USB_DEVICE);
                }
                if (device != null && usbManager != null) {
                    usbDevice.Close();
                    SystemClock.sleep(USB_WAIT_DEVICE);
                    if (!usbManager.hasPermission(device)) {
                        //showToastInMainLooper("Asking permissions", Toast.LENGTH_LONG);
                        PendingIntent pi = PendingIntent.getBroadcast(context, 0, new Intent(Constants.ACTION.ACTION_GET_USB_PERMISSION), mutabilityFlag);
                        usbManager.requestPermission(device, pi);
                    } else {
                        onUSBAttached(device);
                    }
                }
                return;
            }
            if (Constants.ACTION.ACTION_USB_DETACHED.equals(action)) {
                onUSBDetached();
                return;
            }
            if (Constants.ACTION.ACTION_USB_HAS_DATA.equals(action)) {
                switch (intent.getIntExtra(AtomSpectraSerial.EXTRA_DATA_TYPE, AtomSpectraSerial.CODE_NONE)) {
                    case AtomSpectraSerial.CODE_DATA:
                        if (freeze_update_data) {
                            return;
                        }

                        restartUsbDataWatchdog();

                        double new_time;
                        double old_time;
                        long[] new_histogram;
                        long[] old_histogram;
                        synchronized (data_from_usb_sync) {
                            old_time = ForegroundSpectrum.getRealSpectrumTime();
                            old_histogram = ForegroundSpectrum.getDataArray();
                            old_histogram = Arrays.copyOf(old_histogram, old_histogram.length);

                            new_time = intent.getIntExtra(EXTRA_DATA_INT_FG_TOTAL_TIME, 1);
                            new_histogram = intent.getLongArrayExtra(EXTRA_DATA_ARRAY_LONG_SERIAL_SPECTRUM_COUNTS);
                            if (new_histogram != null) {
                                new_histogram = Arrays.copyOf(new_histogram, new_histogram.length);
                                ForegroundSpectrum
                                        .setSpectrum(new_histogram)
                                        .setRealSpectrumTime(new_time)
                                        .setDeviceInfo(inputDeviceInfo)
                                        .updateComments();
                            }

                            /* debugging of serial data
                            long old_count = 0;
                            long new_count = 0;
                            for (int i = 0; i < new_histogram.length; i++) {
                                old_count += old_histogram[i];
                                new_count += new_histogram[i];
                            }
                            showToastInMainLooper("old_time: " + old_time + " new_time: " + new_time + " old_count: " + old_count + " new_count: " + new_count, Toast.LENGTH_SHORT);
                             */
                        }

                        cps = intent.getIntExtra(EXTRA_DATA_INT_CP1S, 0);
                        total_counts = 0;
                        boolean isReliableData = false;

                        if (new_histogram != null && new_time > old_time) {
                            int counts = 0, interval_counts = 0;
                            int[] binned_counts = new int[EnergyBins.length];
                            for (int i = 0; i < StrictMath.min(Constants.NUM_HIST_POINTS, new_histogram.length); i++) {
                                total_counts += new_histogram[i];
                                int value = 0;
                                value = (int) (new_histogram[i] - old_histogram[i]);

                                counts += value;
                                if (i >= leftChannelInterval && i <= rightChannelInterval) {
                                    interval_counts += value;
                                }

                                int bin_index = getEnergyBinIndex(ForegroundSpectrum.getSpectrumCalibration().toEnergy(i));
                                if (bin_index != -1) {
                                    binned_counts[bin_index] += value;
                                }
                            }

                            if (skip_next_cps_int_usb_calc > 0) {
                                skip_next_cps_int_usb_calc--;
                                cpsInterval = 0;
                                doseRateValue = new DoseRate();
                            } else if (old_time > 0) { // comparing to zero spectrum will produce large CPS in case collecting device attached
                                cpsInterval = (int) interval_counts;
                                doseRateValue = doseRateSearch(counts, interval_counts, binned_counts, new_time - old_time);
                                isReliableData = true;
                            } else {
                                cpsInterval = 0;
                                doseRateValue = new DoseRate();
                            }
                        }

                        calcAndSendFoundIsotopesData();
                        sendDataToUI();
                        if (isReliableData) {
                            calcSpectrumChangeData();
                            sendDataToAtomSwift(cps, doseRateValue);
                        }

                        break;

                    case AtomSpectraSerial.CODE_SCOPE:
                        //it is useless for Nano Pro
//                        long[] scope = intent.getLongArrayExtra(EXTRA_DATA_ARRAY_LONG_SERIAL_SCOPE_COUNTS);
//                        if(scope != null) {
//                            if (scale_factor == Constants.SCALE_OSCILLOSCOPE_MODE) {
//                                Arrays.fill(x, 0);
//                                System.arraycopy(scope, 0, x, 0, StrictMath.min(scope.length, x.length));
//                            } else if (scale_factor == Constants.SCALE_AUDIO_REFERENCE_PULSE_MODE) {
//                                Arrays.fill(referencePulse, 0);
//                                System.arraycopy(scope, 0, referencePulse, 0, StrictMath.min(scope.length, referencePulse.length));
//                            }
//                        } else {
//                            Arrays.fill(referencePulse, 0);
//                            Arrays.fill(x, 0);
//                        }
                        break;

                }
            }
            if (Constants.ACTION.ACTION_USB_HAS_ANSWER.equals(action)) {
                // answer from USB device
                if (SERVICE_INF_ID.equals(intent.getStringExtra(AtomSpectraSerial.EXTRA_ID))) {
                    String commandResult = intent.getStringExtra(AtomSpectraSerial.EXTRA_RESULT);
                    // showToastInMainLooper("AtomSpectraService -inf answer: " + commandResult, Toast.LENGTH_LONG);
                    if (AtomSpectraSerial.COMMAND_RESULT_ERR.equals(commandResult)) {
                        showToastInMainLooper(getStringOrDefaultLocale(R.string.log_usb_command_failed, "-inf"), Toast.LENGTH_LONG);
                        return;
                    }
                    if (AtomSpectraSerial.COMMAND_RESULT_TIMEOUT.equals(commandResult)) {
                        showToastInMainLooper(getStringOrDefaultLocale(R.string.log_usb_command_timeout, "-inf"), Toast.LENGTH_LONG);
                        return;
                    }

                    String version;
                    if (commandResult != null) {
                        version = AtomSpectraSerial.getParameter(commandResult, "VERSION");
                        if (version != null) {
                            try {
                                if (Integer.decode(version) < Constants.USB_DEVICE_MINIMAL_VERSION)
                                    showToastInMainLooper(getStringOrDefaultLocale(R.string.usb_below_minimal_version, Constants.USB_DEVICE_MINIMAL_VERSION), Toast.LENGTH_SHORT);
                            } catch (Exception e) {
                                showToastInMainLooper(R.string.log_usb_device_version_error, Toast.LENGTH_SHORT);
                            }
                        } else {
                            showToastInMainLooper(R.string.log_usb_device_version_unknown, Toast.LENGTH_SHORT);
                        }
                    } else {
                        showToastInMainLooper(R.string.log_usb_device_version_unknown, Toast.LENGTH_SHORT);
                    }
                }
                if (SERVICE_CAL_ID.equals(intent.getStringExtra(AtomSpectraSerial.EXTRA_ID))) {
                    String commandResult = intent.getStringExtra(AtomSpectraSerial.EXTRA_RESULT);
                    // showToastInMainLooper("AtomSpectraService -cal answer: " + commandResult, Toast.LENGTH_LONG);
                    if (AtomSpectraSerial.COMMAND_RESULT_ERR.equals(commandResult)) {
                        showToastInMainLooper(getStringOrDefaultLocale(R.string.log_usb_command_failed, "-cal"), Toast.LENGTH_LONG);
                        return;
                    }
                    if (AtomSpectraSerial.COMMAND_RESULT_TIMEOUT.equals(commandResult)) {
                        showToastInMainLooper(getStringOrDefaultLocale(R.string.log_usb_command_timeout, "-cal"), Toast.LENGTH_LONG);
                        return;
                    }
                    if (commandResult != null) {
                        final String[] dataArray = commandResult.split("\\s+");
                        if (dataArray.length != 40) {
                            showToastInMainLooper("Unable to read USB device metadata, unexpected register count: " + dataArray.length, Toast.LENGTH_LONG);
                        }
                        inputDeviceInfo = getUsbDeviceInfoText(dataArray[39]);
                        ForegroundSpectrum
                                .setDeviceInfo(inputDeviceInfo)
                                .updateComments();
                        AtomSpectraLog.addMessage(service_context, "Device info: " + inputDeviceInfo);
                    } else {
                        showToastInMainLooper("Unable to read USB device metadata, null command result", Toast.LENGTH_LONG);
                    }
                }
                if (SERVICE_MODE_ID.equals(intent.getStringExtra(AtomSpectraSerial.EXTRA_ID))) {
                    String commandResult = intent.getStringExtra(AtomSpectraSerial.EXTRA_RESULT);
                    // showToastInMainLooper("AtomSpectraService -mode 0 answer: " + commandResult, Toast.LENGTH_LONG);
                    if (AtomSpectraSerial.COMMAND_RESULT_ERR.equals(commandResult)) {
                        showToastInMainLooper(getStringOrDefaultLocale(R.string.log_usb_command_failed, "-mode 0"), Toast.LENGTH_LONG);
                        return;
                    }
                    if (AtomSpectraSerial.COMMAND_RESULT_TIMEOUT.equals(commandResult)) {
                        showToastInMainLooper(getStringOrDefaultLocale(R.string.log_usb_command_timeout, "-mode 0"), Toast.LENGTH_LONG);
                        return;
                    }
                }
                if (SERVICE_STT_ID.equals(intent.getStringExtra(AtomSpectraSerial.EXTRA_ID))) {
                    String commandResult = intent.getStringExtra(AtomSpectraSerial.EXTRA_RESULT);
                    // showToastInMainLooper("AtomSpectraService -stt answer: " + commandResult, Toast.LENGTH_LONG);
                    if (AtomSpectraSerial.COMMAND_RESULT_ERR.equals(commandResult)) {
                        showToastInMainLooper(getStringOrDefaultLocale(R.string.log_usb_command_failed, "-stt"), Toast.LENGTH_LONG);
                        return;
                    }
                    if (AtomSpectraSerial.COMMAND_RESULT_TIMEOUT.equals(commandResult)) {
                        showToastInMainLooper(getStringOrDefaultLocale(R.string.log_usb_command_timeout, "-stt"), Toast.LENGTH_LONG);
                        return;
                    }
                    if (AtomSpectraSerial.COMMAND_RESULT_OK_COLLECTING.equals(commandResult)) {
                        setFreeze(false);
                    } else {
                        freeze_update_data = true;
                        sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_MENU).setPackage(Constants.PACKAGE_NAME));
                    }

                    sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_CALIBRATION).putExtra(Constants.ACTION_PARAMETERS.UPDATE_USB_CALIBRATION, true));
                }
                if (SERVICE_STA_ID.equals(intent.getStringExtra(AtomSpectraSerial.EXTRA_ID))) {
                    String commandResult = intent.getStringExtra(AtomSpectraSerial.EXTRA_RESULT);
                    // showToastInMainLooper("AtomSpectraService -sta answer: " + commandResult, Toast.LENGTH_LONG);
                    if (AtomSpectraSerial.COMMAND_RESULT_ERR.equals(commandResult)) {
                        freeze_update_data = true;
                        sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_MENU).setPackage(Constants.PACKAGE_NAME));
                        showToastInMainLooper(getStringOrDefaultLocale(R.string.log_usb_command_failed, "-sta"), Toast.LENGTH_LONG);
                        return;
                    }
                    if (AtomSpectraSerial.COMMAND_RESULT_TIMEOUT.equals(commandResult)) {
                        freeze_update_data = true;
                        sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_MENU).setPackage(Constants.PACKAGE_NAME));
                        showToastInMainLooper(getStringOrDefaultLocale(R.string.log_usb_command_timeout, "-sta"), Toast.LENGTH_LONG);
                        return;
                    }

                    restartUsbDataWatchdog();
                }
                if (SERVICE_STO_ID.equals(intent.getStringExtra(AtomSpectraSerial.EXTRA_ID))) {
                    String commandResult = intent.getStringExtra(AtomSpectraSerial.EXTRA_RESULT);
                    // showToastInMainLooper("AtomSpectraService -sto answer: " + commandResult, Toast.LENGTH_LONG);
                    if (AtomSpectraSerial.COMMAND_RESULT_ERR.equals(commandResult)) {
                        freeze_update_data = true;
                        sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_MENU).setPackage(Constants.PACKAGE_NAME));
                        showToastInMainLooper(getStringOrDefaultLocale(R.string.log_usb_command_failed, "-sto"), Toast.LENGTH_LONG);
                        return;
                    }
                    if (AtomSpectraSerial.COMMAND_RESULT_TIMEOUT.equals(commandResult)) {
                        freeze_update_data = true;
                        sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_MENU).setPackage(Constants.PACKAGE_NAME));
                        showToastInMainLooper(getStringOrDefaultLocale(R.string.log_usb_command_timeout, "-sto"), Toast.LENGTH_LONG);
                        return;
                    }
                }
            }
        }
    };

    public void notify_cancel_all() {
        if (service_context != null) {
            NotificationManager nm = (NotificationManager) service_context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null)
                nm.cancelAll();
        }
    }

    public void Stop() {
        notify_cancel_all();
        canOpenAudio = false;
        isStarted = false;
        showSpectrumChange = false;

        resetRecordingSuspendedStatus(true);

        Log.d(TAG, "recording Stop");
        stopCapturingAudioSource();
        cancelUsbDataWatchdog();
        stopSpgAutosaveTimer();
        usbDevice.Close();
        usbDevice.Destroy();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && service_context != null) {
            AudioManager manager = (AudioManager) service_context.getSystemService(Context.AUDIO_SERVICE);
            if (manager != null)
                manager.unregisterAudioDeviceCallback(audioChanged);
        }
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
        newCalibration.clear();
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
    }

    public void DeleteSpc() {
        if (inputType == INPUT_SERIAL) {
            usbDevice.ClearHistogram();
        }
        synchronized (spgAutosaveSync) {
            closeSpectrogramFile();
        }
        AtomSpectraSpectrogramData.instance.clear();
        notifySpectrogramUpdated();

        resetCpsData();
        resetDoseRateData();

        Arrays.fill(histogram, 0);
        Arrays.fill(referencePulse, 0);
        ForegroundSpectrum
                .initSpectrumData(Constants.NUM_HIST_POINTS, Calibration.defaultCalibration(Constants.NUM_HIST_POINTS))
                .setSuffix(getStringOrDefaultLocale(R.string.hist_suffix))
                .setDeviceInfo(inputDeviceInfo)
                .updateComments();

        resetSpectrumChangeWindow();
        synchronized (histogram_all_queue) {
            histogram_all_queue.clear();
        }
        total_counts = 0;
    }

    public static boolean getFreeze() {
        return freeze_update_data;
    }

    public static void freeze(boolean freeze) {
        // TODO: looks like a hack to immediately stop everything on spectrum load
        freeze_update_data = freeze;
    }

    // method used to start/stop data collecting timers
    private void setFreeze(boolean freeze) {
        if (freeze != freeze_update_data) {
            // log event to debug view
            String inputTypeText = "none";
            switch (inputType) {
                case INPUT_AUDIO:
                    inputTypeText = "audio";
                    break;
                case INPUT_SERIAL:
                    inputTypeText = "usb";
                    break;
            }

            if (freeze) {
                AtomSpectraLog.addMessage(service_context, getStringOrDefaultLocale(R.string.log_stop_recording, inputTypeText));
            } else {
                AtomSpectraLog.addMessage(service_context, getStringOrDefaultLocale(R.string.log_start_recording, inputTypeText));
            }
        }

        freeze_update_data = freeze;

        if (freeze) {
            resetSearchWindow();
            resetSpectrumChangeWindow();
            resetRecordingSuspendedStatus(false);
            stopSpgAutosaveTimer();
            stopIntervalSearchAlarmTimer();
            ForegroundSpectrum.updateComments();
        } else {
            if (spgInterval > 0) {
                startSpgAutosaveTimer();
            }

            startIntervalSearchAlarmTimer();
        }

        synchronized (inputSync) {
            if (inputType == INPUT_SERIAL) {
                if (freeze_update_data) {
                    cancelUsbDataWatchdog();
                    usbDevice.sendTextCommand("-sto", SERVICE_STO_ID);
                } else {
                    // HACK! when started, AtomSpectraSerial often sends wrong data for 1-2 seconds
                    // calculate spectrum based values (cps interval, dose rate etc.) only when data is more stable
                    skipUnreliableUSBData();
                    usbDevice.sendTextCommand("-sta", SERVICE_STA_ID);
                }
            }
            if (inputType == INPUT_AUDIO) {
                if (freeze_update_data) {
                    stopCapturingAudioSource();
                } else {
                    startCapturingAudioSource();
                }
            }
        }

        sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_MENU).setPackage(Constants.PACKAGE_NAME));
        refreshServiceNotification();
    }

    // when new data arrives either from audio or USB we preserve it in historical sliding time window
    // used to calculate dose rate
    public final static int SEARCH_WINDOW_SIZE = 240;
    private static final LinkedList<Integer> windowCounts = new LinkedList<>();
    private static final LinkedList<Integer> windowIntervalCounts = new LinkedList<>();
    private static final LinkedList<int[]> windowBinnedCounts = new LinkedList<>();
    private static final LinkedList<Double> windowDeltaTime = new LinkedList<>();

    private static final LinkedList<Double> doseHistory = new LinkedList<>();
    private static final LinkedList<Double> doseCompensatedHistory = new LinkedList<>();
    private static final LinkedList<Double> doseIntervalHistory = new LinkedList<>();
    private static final LinkedList<Double> doseIntervalHighAlarmHistory = new LinkedList<>();
    private static final LinkedList<Double> doseIntervalLowAlarmHistory = new LinkedList<>();
    private static final LinkedList<Double> doseIntervalBaselineHistory = new LinkedList<>();

    private static void resetCpsData() {
        Arrays.fill(cpsArray, 0);
        Arrays.fill(cpsArrayInterval, 0);
        Arrays.fill(cpsArrayEnergyBins, new int[EnergyBins.length]);
        cps = 0;
        cpsInterval = 0;
    }

    private static void resetSearchWindow() {
        synchronized (windowCounts) {
            windowDeltaTime.clear();
            windowBinnedCounts.clear();
            windowCounts.clear();
            windowIntervalCounts.clear();
        }
    }

    private static void resetDoseRateData() {
        resetSearchWindow();

        synchronized (doseHistory) {
            doseHistory.clear();
            doseCompensatedHistory.clear();
            doseIntervalHistory.clear();
            doseIntervalHighAlarmHistory.clear();
            doseIntervalLowAlarmHistory.clear();
            doseIntervalBaselineHistory.clear();
        }

        doseRateValue = new DoseRate();
    }

    // called each [0.1, 0.2, 0.5, 1] sec for audio, each 1 sec for USB
    private DoseRate doseRateSearch(int counts, int interval_counts, int[] binned_counts, double delta_time) {
        if (delta_time == 0) {
            return doseRateValue;
        }

        synchronized (windowCounts) {
            windowDeltaTime.addLast(delta_time);
            if (windowDeltaTime.size() > SEARCH_WINDOW_SIZE) {
                windowDeltaTime.removeFirst();
            }

            windowBinnedCounts.addLast(binned_counts);
            if (windowBinnedCounts.size() > SEARCH_WINDOW_SIZE) {
                windowBinnedCounts.removeFirst();
            }

            windowCounts.addLast(counts);
            if (windowCounts.size() > SEARCH_WINDOW_SIZE) {
                windowCounts.removeFirst();
            }

            windowIntervalCounts.addLast(interval_counts);
            if (windowIntervalCounts.size() > SEARCH_WINDOW_SIZE) {
                windowIntervalCounts.removeFirst();
            }

            if (intervalSearchAlarmEnabled) {
                intervalSearchAlarmBaseline.updateBaseline(interval_counts, delta_time);
            } else {
                intervalSearchAlarmBaseline.reset();
            }
        }

        int counts_search_window = 0;
        double min_period = 1;   // in seconds
        switch (SearchFSM) {
            case 0:
                counts_search_window = SEARCH_FAST;
                min_period = 0.2;
                break;
            case 1:
                counts_search_window = SEARCH_MEDIUM;
                min_period = 1;
                break;
            case 2:
                counts_search_window = SEARCH_SLOW;
                min_period = 2;
                break;
        }
        int total_counts = 0;
        int total_interval_counts = 0;
        double total_time = 0;
        double total_interval_time = 0;
        int[] total_binned_counts = new int[EnergyBins.length];
        synchronized (windowCounts) {
            int start = windowCounts.size() > 0 ? windowCounts.size() - 1 : 0;
            for (int i = start; i >= 0; i--) {
                if (total_counts < counts_search_window || total_time < min_period) {
                    total_counts += windowCounts.get(i);
                    total_time += windowDeltaTime.get(i);
                    for (int bin = 0; bin < EnergyBins.length; bin++) {
                        total_binned_counts[bin] += windowBinnedCounts.get(i)[bin];
                    }
                }

                if (total_interval_counts < counts_search_window || total_interval_time < min_period) {
                    total_interval_counts += windowIntervalCounts.get(i);
                    total_interval_time += windowDeltaTime.get(i);
                }

                if ((total_counts >= counts_search_window) && (total_time >= min_period)
                        && (total_interval_counts >= counts_search_window) && (total_interval_time >= min_period))
                    break;
            }
        }

        if (total_time < min_period) {
            return doseRateValue;
        }

        double comp_dose_rate = 0;
        ArrayList<Double> bin_dose_rate_values = new ArrayList<Double>(EnergyBins.length);
        for (int bin = 0; bin < EnergyBins.length; bin++) {
            if (EnergySensitivity[bin] == 0 || SensGCompensated == 0) {
                continue;
            }

            int bin_counts = total_binned_counts[bin];
            double bin_cps = bin_counts / total_time;
            double bin_sens = EnergySensitivity[bin];
            double bin_dose_rate = bin_cps * bin_sens / SensGCompensated;
            bin_dose_rate_values.add(bin_dose_rate);
            comp_dose_rate += bin_dose_rate;
        }
        double comp_dose_rate_error = 0;
        if (comp_dose_rate > 0) {
            double bin_dose_rate_mean = comp_dose_rate / bin_dose_rate_values.size();
            double square_deviation_sum = 0;
            for (int i = 0; i < bin_dose_rate_values.size(); i++) {
                double bin_value = bin_dose_rate_values.get(i);
                square_deviation_sum += (bin_value - bin_dose_rate_mean) * (bin_value - bin_dose_rate_mean);
            }
            double std_deviation = Math.sqrt(square_deviation_sum / bin_dose_rate_values.size());
            comp_dose_rate_error = std_deviation / comp_dose_rate * 100.0;
        }

        double dose_rate = 0;
        if (SensG > 0) {
            dose_rate = StrictMath.max(0.0, (total_counts / total_time - backgroundCps) / SensG);
        }
        double dose_rate_error = total_counts > 0 ? Math.sqrt(total_counts) / total_counts * 100.0 : 0;

        double interval_cps = (total_interval_counts / total_interval_time);
        double interval_cps_error = total_interval_counts > 0 ? Math.sqrt(total_interval_counts) / total_interval_counts * 100.0 : 0;
        if (intervalSearchAlarmEnabled) {
            intervalSearchAlarmBaseline.updateAlarmLevels(interval_cps, interval_cps_error, intervalSearchAlarmDetectionLevel);
        }

        synchronized (doseHistory) {
            doseHistory.addLast(dose_rate);
            if (doseHistory.size() > SEARCH_WINDOW_SIZE) {
                doseHistory.removeFirst();
            }
            doseCompensatedHistory.addLast(comp_dose_rate);
            if (doseCompensatedHistory.size() > SEARCH_WINDOW_SIZE) {
                doseCompensatedHistory.removeFirst();
            }
            doseIntervalHistory.addLast(interval_cps);
            if (doseIntervalHistory.size() > SEARCH_WINDOW_SIZE) {
                doseIntervalHistory.removeFirst();
            }
            doseIntervalHighAlarmHistory.addLast(intervalSearchAlarmBaseline.getAlarmLevelHigh());
            if (doseIntervalHighAlarmHistory.size() > SEARCH_WINDOW_SIZE) {
                doseIntervalHighAlarmHistory.removeFirst();
            }
            doseIntervalLowAlarmHistory.addLast(intervalSearchAlarmBaseline.getAlarmLevelLow());
            if (doseIntervalLowAlarmHistory.size() > SEARCH_WINDOW_SIZE) {
                doseIntervalLowAlarmHistory.removeFirst();
            }
            doseIntervalBaselineHistory.addLast(intervalSearchAlarmBaseline.getBaseline());
            if (doseIntervalBaselineHistory.size() > SEARCH_WINDOW_SIZE) {
                doseIntervalBaselineHistory.removeFirst();
            }
        }

        return new DoseRate(comp_dose_rate, comp_dose_rate_error, dose_rate, dose_rate_error, interval_cps, interval_cps_error);
    }

    private static int getEnergyBinIndex(double energy) {
        if (energy <= EnergyBins[0]) {
            return 0;
        }

        if (energy >= EnergyBins[EnergyBins.length - 1]) {
            return EnergyBins.length - 1;
        }

        int energy_bin = 1;
        while ((energy_bin < (EnergyBins.length - 1)) && (energy > EnergyBins[energy_bin])) {
            energy_bin++;
        }

        return energy_bin;
    }

    // this function is used to release sound input
    private void releaseAR() {
        synchronized (ARLock) {
            if (AR != null) {
                AR.stop();
                AR.release();
                AR = null;
            }
        }
    }

    // this task is used to read from audio input and update cps and spectrum information
    int audioZeroDataCount = 0;
    final int audioZeroDataMaxCount = 5;

    private void captureAudioTask() {
        if (inputType != INPUT_AUDIO) {
            return;
        }
        if (freeze_update_data) {
            return;
        }
        if (isRecordingSuspended) {
            return;
        }
        if (SetAudioSource == SET_AUDIO_VOICE) {
            AudioSource = AUDIO_SOURCE_VOICE;
            releaseAR();
            SetAudioSource = SET_AUDIO_OK;
        }
        if (SetAudioSource == SET_AUDIO_RAW && service_context != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                AudioManager manager = (AudioManager) service_context.getSystemService(Context.AUDIO_SERVICE);
                if (manager != null && manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) != null)
                    AudioSource = AUDIO_SOURCE_RAW;
                SetAudioSource = SET_AUDIO_OK;
                releaseAR();
            } else {
                SetAudioSource = SET_AUDIO_ERROR;
            }
        }
        synchronized (ARLock) {
            if (canOpenAudio && (AR == null)) {
                AR = new AudioRecord(AudioSource, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, BufferSize);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (inputSound && service_context != null) {
                        AudioDeviceInfo device = getDeviceInput(service_context, inputSoundID, inputSoundName, true);
                        if (device != null) {
                            AR.setPreferredDevice(device);
                            ARShowAbsentMessage = true;
                        } else {
                            if (ARShowAbsentMessage) {
                                this.showToastInMainLooper(R.string.input_sound_absent, Toast.LENGTH_SHORT);
                                ARShowAbsentMessage = false;
                            }
                            AR.setPreferredDevice(null);
                        }
                    } else {
                        AR.setPreferredDevice(null);
                    }
                }
                if (AR.getState() == AudioRecord.STATE_UNINITIALIZED) {
                    AR = null;
                } else {
                    try {
                        AR.startRecording();
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                            AudioDeviceInfo device = AR.getRoutedDevice();
                            if (device != null) {
                                String deviceName = device.getProductName().toString();
                                AtomSpectraLog.addMessage(service_context, getStringOrDefaultLocale(R.string.log_fetching_data_from_audio, deviceName));
                                inputDeviceInfo = getAudioDeviceInfoText(deviceName);
                            }
                        } else {
                            AtomSpectraLog.addMessage(service_context, getStringOrDefaultLocale(R.string.log_fetching_data_from_audio, ""));
                            inputDeviceInfo = getAudioDeviceInfoText(null);
                        }

                        ForegroundSpectrum
                                .setDeviceInfo(inputDeviceInfo)
                                .updateComments();
                    } catch (IllegalStateException e) {
                        AR.release();
                        AR = null;
                    }
                }
            }
            if (AR != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    AudioBytesRead = AR.read(AudioBytes, 0, BufferSize, AudioRecord.READ_NON_BLOCKING); // This is the guy reading the bytes out of the buffer!!
                } else {
                    AudioBytesRead = AR.read(AudioBytes, 0, BufferSize); // This is the guy reading the bytes out of the buffer!!
                }
                if (AudioBytesRead < 0) {
                    switch (AudioBytesRead) {
                        case AudioRecord.ERROR_INVALID_OPERATION:             //object is not initialized
                            if (AR != null) {
                                AR.stop();
                                AR.release();
                            }
                            AR = null;
                            break;
                        case AudioRecord.ERROR_DEAD_OBJECT:                   //object is not accessible now, try to reopen
                        case AudioRecord.ERROR:                               //other errors found
                            if (AR != null) {
                                AR.stop();
                                AR.release();
                            }
                            AR = null;
                            break;
                        case AudioRecord.ERROR_BAD_VALUE:                     //error in input parameters, must not happen
                            break;
                    }
                    AudioBytesRead = 0;
                }
            } else {
                AudioBytesRead = 0;
            }
        }

        if (AudioBytesRead == 0) {
            audioZeroDataCount++;

            if (audioZeroDataCount >= audioZeroDataMaxCount) {
                AtomSpectraLog.addMessage(service_context, getStringOrDefaultLocale(R.string.log_audio_zero_buffers_detected, audioZeroDataCount));
                audioZeroDataCount = 0;
                if (AR != null) {
                    AR.stop();
                    AR.release();
                    AR = null;
                }
            }
            return;
        } else {
            audioZeroDataCount = 0;
        }

        // First we will pass the 2 bytes into one sample
        // It's an extra loop but avoids repeating the same sum many times later during the filter
        int HighBitsShift = Math.max(0, 15 - Constants.ADC_MAX);
        for (int i = 0, r = 0; i < AudioBytesRead - 2; i += 2, r++) {// Before the 8 we had the end of the previous data
            if (AudioBytes[i] < 0)
                AudioData[r] = AudioBytes[i] + 256;
            else
                AudioData[r] = AudioBytes[i];
            AudioData[r] = AudioData[r] + 256 * AudioBytes[i + 1];//+32768;

            if (inversion)
                AudioData[r] = -AudioData[r];
            AudioData[r] = (AudioData[r] >> HighBitsShift);
        }

//-----------------DPP started--------------------------------------------------

        int initial_amp = 0, initial_time = 0;
        float corrector;

        for (int i = 1; i < AudioBytesRead / 2 - 2; i++) {
            if (((AudioData[i] - AudioData[i - 1]) <= 0) && ((AudioData[i + 1] - AudioData[i]) > 0)) {
                initial_amp = AudioData[i];
                initial_time = i;
            }
            if (((AudioData[i] - AudioData[i - 1]) >= 0) && ((AudioData[i + 1] - AudioData[i]) < 0)) {
                if (((i - initial_time) >= frontCountsMin) && ((i - initial_time) <= frontCountsMax)) {
                    if (i > frontCountsMax * 2)
                        corrector = AudioData[initial_time - (i - initial_time)] - AudioData[initial_time];
                    else corrector = 0;
                    if (!pileup) corrector = 0;
                    int channel = (AudioData[i] - initial_amp + (int) corrector);
                    if ((channel >= histogramMinChannel) && (channel < Constants.NUM_HIST_POINTS)) {
                        if (ForegroundSpectrum.incSpectrumValue(channel))
                            ForegroundSpectrum.updateComments();
                        total_counts++;

//-----------------DPP finished-------------------------------------------------

                        counts_from_audio++;
                        if ((channel >= leftChannelInterval) && (channel <= rightChannelInterval)) {
                            interval_counts_from_audio++;
                        }
                        int energy_bin_index = getEnergyBinIndex(ForegroundSpectrum.getSpectrumCalibration().toEnergy(channel));
                        if (energy_bin_index != -1) {
                            binned_counts_from_audio[energy_bin_index] += 1;
                        }

                        if ((i > 128) && (i < (1024 - 128)) && (i < ((AudioBytesRead - 128) / 2)))
                            for (int j = -128; j < 127; j++)
                                referencePulse[j + 128] += AudioData[i + j];
                    }
                }
            }
        }

        audioCaptureTimer += captureAudioTaskInterval;

        // expected to be called 10 times per second
        if ((audioCaptureOldTimer + Constants.UPDATE_PERIOD) < audioCaptureTimer) {
            audioCaptureOldTimer += Constants.UPDATE_PERIOD;
            cpsPos = cpsPos < (1000 / Constants.UPDATE_PERIOD - 1) ? (cpsPos + 1) : 0;
            cpsArray[cpsPos] = counts_from_audio;
            cpsArrayInterval[cpsPos] = interval_counts_from_audio;
            System.arraycopy(binned_counts_from_audio, 0, cpsArrayEnergyBins[cpsPos], 0, EnergyBins.length);
            counts_from_audio = 0;
            interval_counts_from_audio = 0;
            Arrays.fill(binned_counts_from_audio, 0);
        }
    }

    ;

    // finds isotopes and sends data to UI
    // should to be called each second
    private final void calcAndSendFoundIsotopesData() {
        if (AtomSpectraIsotopes.autoUpdateIsotopes && !freeze_update_data && AtomSpectraIsotopes.showFoundIsotopes) {
            AtomSpectraFindIsotope.updateFoundIsotopes();
            sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_ISOTOPE_LIST).setPackage(Constants.PACKAGE_NAME));
        }
    }

    // spectrum change mode
    // shows spectrum for the last n seconds (sliding window)
    // window size - delta_time
    private final void calcSpectrumChangeData() {
        if (showSpectrumChange) {
            long[] currentState = Arrays.copyOf(ForegroundSpectrum.getDataArray(), ForegroundSpectrum.getDataArray().length);
            long[] previousState = currentState;
            long[] backState = currentState;
            synchronized (histogram_all_queue) {
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
            }

            for (int i = 0; i < currentState.length; i++) {
                histogram_all_sp_change_fg[i] = currentState[i] - previousState[i];
                histogram_all_sp_change_bg[i] = currentState[i] - backState[i];
            }
        } else {
            resetSpectrumChangeWindow();
        }
    }

    private final void sendDataToUI() {
        final Intent intent = new Intent(ACTION_DATA_AVAILABLE).setPackage(Constants.PACKAGE_NAME);
        Bundle mBundle = new Bundle();

        mBundle.putDouble(EXTRA_DATA_DOUBLE_SEARCH_DR_C, doseRateValue.compensated);
        mBundle.putDouble(EXTRA_DATA_DOUBLE_SEARCH_DR_C_ERROR, doseRateValue.compensatedErrorPercent);
        mBundle.putDouble(EXTRA_DATA_DOUBLE_SEARCH_DR_N, doseRateValue.nonCompensated);
        mBundle.putDouble(EXTRA_DATA_DOUBLE_SEARCH_DR_N_ERROR, doseRateValue.nonCompensatedErrorPercent);
        mBundle.putDouble(EXTRA_DATA_DOUBLE_SEARCH_INT_CPS, doseRateValue.intervalCps);
        mBundle.putDouble(EXTRA_DATA_DOUBLE_SEARCH_INT_CPS_ERROR, doseRateValue.intervalCpsErrorPercent);

        mBundle.putInt(EXTRA_DATA_INT_CP1S, cps);
        mBundle.putInt(EXTRA_DATA_INT_CP1S_INTERVAL, cpsInterval);

        mBundle.putLong(EXTRA_DATA_LONG_TOTAL_FG_COUNTS, total_counts);
        mBundle.putDouble(EXTRA_DATA_INT_FG_TOTAL_TIME, ForegroundSpectrum.getRealSpectrumTime());

        if (AtomSpectraService.showSpectrumChange && histogram_all_queue.size() > 1) {
            int delta_current_time = Math.min(histogram_all_queue.size() - 1, delta_time);
            int delta_current_back_time = histogram_all_queue.size() - 1;
            long delta_counts = 0;
            long delta_back_counts = 0;
            for (int i = 0; i < histogram_all_sp_change_fg.length; i++) {
                delta_counts += histogram_all_sp_change_fg[i];
                delta_back_counts += histogram_all_sp_change_bg[i];
            }
            mBundle.putLong(EXTRA_DATA_LONG_SP_CHNG_FG_TOTAL_COUNTS, delta_counts);
            mBundle.putLong(EXTRA_DATA_LONG_SP_CHNG_BG_TOTAL_COUNTS, delta_back_counts);
            mBundle.putInt(EXTRA_DATA_INT_SP_CHNG_FG_TOTAL_TIME, delta_current_time);
            mBundle.putInt(EXTRA_DATA_INT_SP_CHNG_BG_TOTAL_TIME, delta_current_back_time);
        } else {
            mBundle.putLong(EXTRA_DATA_LONG_SP_CHNG_FG_TOTAL_COUNTS, 0);
            mBundle.putLong(EXTRA_DATA_LONG_SP_CHNG_BG_TOTAL_COUNTS, 0);
            mBundle.putInt(EXTRA_DATA_INT_SP_CHNG_FG_TOTAL_TIME, 0);
            mBundle.putInt(EXTRA_DATA_INT_SP_CHNG_BG_TOTAL_TIME, 0);
        }

        int num_values;
        int num_scale_factor;
        int num_first_channel;
        synchronized (sync_factor) {
            num_values = 1 << (Constants.SCALE_MAX - scale_factor - 1);
            num_scale_factor = scale_factor;
            num_first_channel = first_channel;
        }

        switch (num_scale_factor) {
            case 0:
            case 1:
            case 2:
            case 3:
            case 4:
            case 5:
            case 6:
                if (showCalibrationFunction) {
                    double[] histogram_temp = newCalibration.getApproximationList();
                    for (int i = 0; i < 1024; i++) {
                        histogram[i] = 0;
                        background_histogram[i] = 0;
                        for (int j = 0; j < num_values; j++) {
                            histogram[i] += histogram_temp[num_first_channel + i * num_values + j];
                        }
                        histogram[i] = histogram[i] / num_values;
                    }

                    mBundle.putDoubleArray(EXTRA_DATA_ARRAY_DOUBLE_CALIBRATION_FUNCTION, histogram);
                } else {
                    if (showSpectrumChange) {
                        int delta_current_time = Math.min(histogram_all_queue.size() - 1, delta_time);
                        int delta_current_back_time = histogram_all_queue.size() - 1;
                        double backgroundScale = (double) delta_current_time / (double) delta_current_back_time;
                        if (isCalibrated) {
                            double[] histogram_e_all = ForegroundSpectrum.getSpectrumCalibration().toEnergy(makeSmooth(histogram_all_sp_change_fg, AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration()), adc_effective_bits, lastCalibrationChannel);
                            double[] background_e_all = ForegroundSpectrum.getSpectrumCalibration().toEnergy(makeSmooth(histogram_all_sp_change_bg, AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration()), adc_effective_bits, lastCalibrationChannel);
                            double sum_element;
                            double back_sum_element;
                            switch (compressGraph) {
                                case Constants.COMPRESS_GRAPH_SUM:
                                    for (int i = 0; i < 1024; i++) {
                                        sum_element = 0;
                                        back_sum_element = 0;
                                        for (int j = 0; j < num_values; j++) {
                                            sum_element += histogram_e_all[num_first_channel + i * num_values + j];
                                            back_sum_element += background_e_all[num_first_channel + i * num_values + j];
                                        }
                                        histogram[i] = sum_element;
                                        background_histogram[i] = back_sum_element * backgroundScale;
                                    }
                                    break;
                                case Constants.COMPRESS_GRAPH_AVERAGE:
                                    for (int i = 0; i < 1024; i++) {
                                        sum_element = 0;
                                        back_sum_element = 0;
                                        for (int j = 0; j < num_values; j++) {
                                            sum_element += histogram_e_all[num_first_channel + i * num_values + j];
                                            back_sum_element += background_e_all[num_first_channel + i * num_values + j];
                                        }
                                        histogram[i] = sum_element / num_values;
                                        background_histogram[i] = back_sum_element / num_values * backgroundScale;
                                    }
                                    break;
                                case Constants.COMPRESS_GRAPH_MAX:
                                    for (int i = 0; i < 1024; i++) {
                                        sum_element = 0;
                                        back_sum_element = 0;
                                        if (compressGraph == Constants.COMPRESS_GRAPH_MAX) {
                                            for (int j = 0; j < num_values; j++) {
                                                sum_element = StrictMath.max(sum_element, histogram_e_all[num_first_channel + i * num_values + j]);
                                                back_sum_element = StrictMath.max(back_sum_element, background_e_all[num_first_channel + i * num_values + j]);
                                            }
                                            histogram[i] = sum_element;
                                            background_histogram[i] = back_sum_element * backgroundScale;
                                        }
                                    }
                                    break;
                                default:
                                    break;
                            }
                        } else {
                            double[] histogram_temp = ForegroundSpectrum.getSpectrumCalibration().linearChannel(makeSmooth(histogram_all_sp_change_fg, AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration()), adc_effective_bits);
                            double[] back_temp = ForegroundSpectrum.getSpectrumCalibration().linearChannel(makeSmooth(histogram_all_sp_change_bg, AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration()), adc_effective_bits);
                            switch (compressGraph) {
                                case Constants.COMPRESS_GRAPH_SUM:
                                    for (int i = 0; i < 1024; i++) {
                                        histogram[i] = 0;
                                        background_histogram[i] = 0;
                                        for (int j = 0; j < num_values; j++) {
                                            histogram[i] += histogram_temp[num_first_channel + i * num_values + j];
                                            background_histogram[i] += back_temp[num_first_channel + i * num_values + j];
                                        }
                                        background_histogram[i] *= backgroundScale;
                                    }
                                    break;
                                case Constants.COMPRESS_GRAPH_AVERAGE:
                                    for (int i = 0; i < 1024; i++) {
                                        histogram[i] = 0;
                                        background_histogram[i] = 0;
                                        for (int j = 0; j < num_values; j++) {
                                            histogram[i] += histogram_temp[num_first_channel + i * num_values + j];
                                            background_histogram[i] += back_temp[num_first_channel + i * num_values + j];
                                        }
                                        histogram[i] = histogram[i] / num_values;
                                        background_histogram[i] = background_histogram[i] / num_values;
                                        background_histogram[i] *= backgroundScale;
                                    }
                                    break;
                                case Constants.COMPRESS_GRAPH_MAX:
                                    for (int i = 0; i < 1024; i++) {
                                        histogram[i] = 0;
                                        background_histogram[i] = 0;
                                        for (int j = 0; j < num_values; j++) {
                                            histogram[i] = StrictMath.max(histogram[i], histogram_temp[num_first_channel + i * num_values + j]);
                                            background_histogram[i] = StrictMath.max(background_histogram[i], back_temp[num_first_channel + i * num_values + j]);
                                        }

                                        background_histogram[i] *= backgroundScale;
                                    }
                                    break;
                                default:
                                    break;
                            }
                        }
                    } else {
                        if (isCalibrated) {
                            double[] histogram_e_all = ForegroundSpectrum.getSpectrumCalibration().toEnergy(makeSmooth(ForegroundSpectrum.getDataArray(), AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration()), adc_effective_bits, lastCalibrationChannel);
                            double sum_element;
                            switch (compressGraph) {
                                case Constants.COMPRESS_GRAPH_SUM:
                                    for (int i = 0; i < 1024; i++) {
                                        sum_element = 0;
                                        background_histogram[i] = 0;
                                        for (int j = 0; j < num_values; j++) {
                                            sum_element += histogram_e_all[num_first_channel + i * num_values + j];
                                        }
                                        histogram[i] = sum_element;
                                    }
                                    break;
                                case Constants.COMPRESS_GRAPH_AVERAGE:
                                    for (int i = 0; i < 1024; i++) {
                                        sum_element = 0;
                                        background_histogram[i] = 0;
                                        for (int j = 0; j < num_values; j++) {
                                            sum_element += histogram_e_all[num_first_channel + i * num_values + j];
                                        }
                                        histogram[i] = sum_element / num_values;
                                    }
                                    break;
                                case Constants.COMPRESS_GRAPH_MAX:
                                    for (int i = 0; i < 1024; i++) {
                                        sum_element = 0;
                                        background_histogram[i] = 0;
                                        if (compressGraph == Constants.COMPRESS_GRAPH_MAX) {
                                            for (int j = 0; j < num_values; j++) {
                                                sum_element = StrictMath.max(sum_element, histogram_e_all[num_first_channel + i * num_values + j]);
                                            }
                                            histogram[i] = sum_element;
                                        }
                                    }
                                    break;
                                default:
                                    break;
                            }
                        } else {
                            double[] histogram_temp = ForegroundSpectrum.getSpectrumCalibration().linearChannel(makeSmooth(ForegroundSpectrum.getDataArray(), AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration()), adc_effective_bits);
                            switch (compressGraph) {
                                case Constants.COMPRESS_GRAPH_SUM:
                                    for (int i = 0; i < 1024; i++) {
                                        histogram[i] = 0;
                                        background_histogram[i] = 0;
                                        for (int j = 0; j < num_values; j++) {
                                            histogram[i] += histogram_temp[num_first_channel + i * num_values + j];
                                        }
                                    }
                                    break;
                                case Constants.COMPRESS_GRAPH_AVERAGE:
                                    for (int i = 0; i < 1024; i++) {
                                        histogram[i] = 0;
                                        background_histogram[i] = 0;
                                        for (int j = 0; j < num_values; j++) {
                                            histogram[i] += histogram_temp[num_first_channel + i * num_values + j];
                                        }
                                        histogram[i] = histogram[i] / num_values;
                                    }
                                    break;
                                case Constants.COMPRESS_GRAPH_MAX:
                                    for (int i = 0; i < 1024; i++) {
                                        histogram[i] = 0;
                                        background_histogram[i] = 0;
                                        for (int j = 0; j < num_values; j++) {
                                            histogram[i] = StrictMath.max(histogram[i], histogram_temp[num_first_channel + i * num_values + j]);
                                        }
                                    }
                                    break;
                                default:
                                    break;
                            }
                        }
                        if (background_show && (!BackgroundSpectrum.isEmpty())) {
                            double backgroundScale = (double) ForegroundSpectrum.getSpectrumTime() / (double) BackgroundSpectrum.getSpectrumTime();
                            if (isCalibrated) {
                                double[] background_data_e_total = ForegroundSpectrum.getSpectrumCalibration().toEnergy(makeSmooth(BackgroundSpectrum.getDataArray(), AtomSpectraService.BackgroundSpectrum.getSpectrumCalibration()), adc_effective_bits, BackgroundSpectrum.getSpectrumCalibration(), lastCalibrationChannel);
                                double sum_element;
                                switch (compressGraph) {
                                    case Constants.COMPRESS_GRAPH_SUM:
                                        for (int i = 0; i < 1024; i++) {
                                            sum_element = 0;
                                            for (int j = 0; j < num_values; j++) {
                                                sum_element += background_data_e_total[num_first_channel + i * num_values + j];
                                            }
                                            background_histogram[i] = sum_element * backgroundScale;
                                        }
                                        break;
                                    case Constants.COMPRESS_GRAPH_AVERAGE:
                                        for (int i = 0; i < 1024; i++) {
                                            sum_element = 0;
                                            for (int j = 0; j < num_values; j++) {
                                                sum_element += background_data_e_total[num_first_channel + i * num_values + j];
                                            }
                                            background_histogram[i] = sum_element * backgroundScale / num_values;
                                        }
                                        break;
                                    case Constants.COMPRESS_GRAPH_MAX:
                                        for (int i = 0; i < 1024; i++) {
                                            sum_element = 0;
                                            for (int j = 0; j < num_values; j++) {
                                                sum_element = StrictMath.max(sum_element, background_data_e_total[num_first_channel + i * num_values + j]);
                                            }
                                            background_histogram[i] = sum_element * backgroundScale;
                                        }
                                        break;
                                    default:
                                        break;
                                }
                            } else {
                                double[] data = ForegroundSpectrum.getSpectrumCalibration().toChannel(makeSmooth(BackgroundSpectrum.getDataArray(), AtomSpectraService.BackgroundSpectrum.getSpectrumCalibration()), adc_effective_bits, BackgroundSpectrum.getSpectrumCalibration(), lastCalibrationChannel);
                                switch (compressGraph) {
                                    case Constants.COMPRESS_GRAPH_SUM:
                                        for (int i = 0; i < 1024; i++) {
                                            for (int j = 0; j < num_values; j++) {
                                                background_histogram[i] += data[num_first_channel + i * num_values + j];
                                            }
                                            background_histogram[i] = background_histogram[i] * backgroundScale;
                                        }
                                        break;
                                    case Constants.COMPRESS_GRAPH_AVERAGE:
                                        for (int i = 0; i < 1024; i++) {
                                            for (int j = 0; j < num_values; j++) {
                                                background_histogram[i] += data[num_first_channel + i * num_values + j];
                                            }
                                            background_histogram[i] = background_histogram[i] * backgroundScale / num_values;
                                        }
                                        break;
                                    case Constants.COMPRESS_GRAPH_MAX:
                                        for (int i = 0; i < 1024; i++) {
                                            for (int j = 0; j < num_values; j++) {
                                                background_histogram[i] = StrictMath.max(background_histogram[i], data[num_first_channel + i * num_values + j]);
                                            }
                                            background_histogram[i] = background_histogram[i] * backgroundScale;
                                        }
                                        break;
                                    default:
                                        break;
                                }
                            }
                        }
                    }

                    mBundle.putDoubleArray(EXTRA_DATA_ARRAY_DOUBLE_FG_COUNTS, histogram);
                    mBundle.putDoubleArray(EXTRA_DATA_ARRAY_DOUBLE_BG_COUNTS, background_histogram);
                    mBundle.putBoolean(EXTRA_DATA_BOOL_SHOW_BG_SPECTRUM, background_show && (!BackgroundSpectrum.isEmpty()));
                }
                break;

            case 7:
                if (showCalibrationFunction) {
                    double[] histogram_temp = newCalibration.getApproximationList();
                    for (int i = 0; i < 512; i++) {
                        histogram[2 * i] = histogram_temp[num_first_channel + i];
                        histogram[2 * i + 1] = histogram_temp[num_first_channel + i];
                        background_histogram[2 * i] = 0;
                        background_histogram[2 * i + 1] = 0;
                    }

                    mBundle.putDoubleArray(EXTRA_DATA_ARRAY_DOUBLE_CALIBRATION_FUNCTION, histogram);
                } else {
                    if (isCalibrated) {
                        double[] histogram_e_all = ForegroundSpectrum.getSpectrumCalibration().toEnergy(makeSmooth(ForegroundSpectrum.getDataArray(), AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration()), adc_effective_bits, lastCalibrationChannel);
                        for (int i = 0; i < 512; i++) {
                            histogram[2 * i] = histogram_e_all[num_first_channel + i];
                            histogram[2 * i + 1] = histogram_e_all[num_first_channel + i];
                        }
                    } else {
                        double[] histogram_temp = ForegroundSpectrum.getSpectrumCalibration().linearChannel(makeSmooth(ForegroundSpectrum.getDataArray(), AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration()), adc_effective_bits);
                        for (int i = 0; i < 512; i++) {
                            histogram[2 * i] = histogram_temp[num_first_channel + i];
                            histogram[2 * i + 1] = histogram_temp[num_first_channel + i];
                        }
                    }
                    if (background_show && (!BackgroundSpectrum.isEmpty())) {
                        double backgroundScale = (double) ForegroundSpectrum.getSpectrumTime() / (double) BackgroundSpectrum.getSpectrumTime();
                        if (isCalibrated) {
                            double[] background_data_e_total = ForegroundSpectrum.getSpectrumCalibration().toEnergy(makeSmooth(BackgroundSpectrum.getDataArray(), AtomSpectraService.BackgroundSpectrum.getSpectrumCalibration()), adc_effective_bits, BackgroundSpectrum.getSpectrumCalibration(), lastCalibrationChannel);
                            for (int i = 0; i < 512; i++) {
                                background_histogram[2 * i] = background_data_e_total[num_first_channel + i] * backgroundScale;
                                background_histogram[2 * i + 1] = background_data_e_total[num_first_channel + i] * backgroundScale;
                            }
                        } else {
                            double[] data = ForegroundSpectrum.getSpectrumCalibration().toChannel(makeSmooth(BackgroundSpectrum.getDataArray(), AtomSpectraService.BackgroundSpectrum.getSpectrumCalibration()), adc_effective_bits, BackgroundSpectrum.getSpectrumCalibration(), lastCalibrationChannel);
                            for (int i = 0; i < 512; i++) {
                                background_histogram[2 * i] = data[num_first_channel + i] * backgroundScale;
                                background_histogram[2 * i + 1] = data[num_first_channel + i] * backgroundScale;
                            }
                        }
                    }

                    mBundle.putDoubleArray(EXTRA_DATA_ARRAY_DOUBLE_FG_COUNTS, histogram);
                    mBundle.putDoubleArray(EXTRA_DATA_ARRAY_DOUBLE_BG_COUNTS, background_histogram);
                    mBundle.putBoolean(EXTRA_DATA_BOOL_SHOW_BG_SPECTRUM, background_show && (!BackgroundSpectrum.isEmpty()));
                }
                break;

            case Constants.SCALE_DOSE_MODE:
                mBundle.putDoubleArray(EXTRA_DATA_ARRAY_DOUBLE_SEARCH_DR_C_HISTORY, searchHistoryToArray(doseCompensatedHistory));
                mBundle.putDoubleArray(EXTRA_DATA_ARRAY_DOUBLE_SEARCH_DR_N_HISTORY, searchHistoryToArray(doseHistory));
                mBundle.putDoubleArray(EXTRA_DATA_ARRAY_DOUBLE_SEARCH_INT_CPS_HISTORY, searchHistoryToArray(doseIntervalHistory));
                mBundle.putDoubleArray(EXTRA_DATA_ARRAY_DOUBLE_SEARCH_INT_CPS_HIGH_ALARM_HISTORY, searchHistoryToArray(doseIntervalHighAlarmHistory));
                mBundle.putDoubleArray(EXTRA_DATA_ARRAY_DOUBLE_SEARCH_INT_CPS_LOW_ALARM_HISTORY, searchHistoryToArray(doseIntervalLowAlarmHistory));
                mBundle.putDoubleArray(EXTRA_DATA_ARRAY_DOUBLE_SEARCH_INT_CPS_BASELINE_HISTORY, searchHistoryToArray(doseIntervalBaselineHistory));
                break;

            case Constants.SCALE_OSCILLOSCOPE_MODE:
                Arrays.fill(realTimeAudioData, 0);
                synchronized (inputSync) {
                    if (inputType == INPUT_AUDIO) {
                        for (int i = 0; i < StrictMath.min(realTimeAudioData.length, (AudioBytesRead / 2)); i++)
                            realTimeAudioData[i] = AudioData[i];
                    }
                }
                mBundle.putDoubleArray(EXTRA_DATA_ARRAY_DOUBLE_REALTIME_AUDIO_DATA, realTimeAudioData);
                break;

            case Constants.SCALE_AUDIO_REFERENCE_PULSE_MODE:
                synchronized (inputSync) {
                    if (inputType != INPUT_AUDIO) {
                        Arrays.fill(referenceDoublePulse, 0);
                    } else {
                        for (int i = 0; i < referencePulse.length; i++) {
                            referenceDoublePulse[i] = referencePulse[i];
                        }
                    }
                }
                mBundle.putDoubleArray(EXTRA_DATA_ARRAY_DOUBLE_REFERENCE_PULSE_DATA, referenceDoublePulse);
                break;

            default:
                break;
        }

        intent.putExtras(mBundle);

        if (service_context != null) {
            service_context.sendBroadcast(intent);
        }
    }

    private double[] searchHistoryToArray(LinkedList<Double> history) {
        double[] histData = new double[SEARCH_WINDOW_SIZE];
        int num_data = StrictMath.max(SEARCH_WINDOW_SIZE - history.size(), 0);
        synchronized (doseHistory) {
            for (double v : history) {
                if (num_data >= SEARCH_WINDOW_SIZE)
                    break;
                histData[num_data] = v;
                num_data++;
            }
        }

        return histData;
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

    // used for audio source only
    // elapsed time in ms since last method call, expected value from 100 to 1000 ms
    private void calcCpsAndDoseRateForAudioSource(int elapsed_time) {
        int last_second_cps = 0;
        int last_second_interval_cps = 0;
        for (int i = 0; i < cpsArray.length; i++) {
            last_second_cps += cpsArray[i];
            last_second_interval_cps += cpsArrayInterval[i];
        }
        cps = last_second_cps;
        cpsInterval = last_second_interval_cps;

        int elapsed_periods = elapsed_time / Constants.UPDATE_PERIOD;
        int counts = 0;
        int interval_counts = 0;
        int[] binned_counts = new int[EnergyBins.length];
        int periodCpsPos = cpsPos;
        while (elapsed_periods > 0) {
            counts += cpsArray[periodCpsPos];
            interval_counts += cpsArrayInterval[periodCpsPos];
            for (int bin = 0; bin < EnergyBins.length; bin++) {
                binned_counts[bin] += cpsArrayEnergyBins[periodCpsPos][bin];
            }
            periodCpsPos--;
            if (periodCpsPos < 0) {
                periodCpsPos = cpsArray.length - 1;
            }

            elapsed_periods--;
        }
        doseRateValue = doseRateSearch(
                counts,
                interval_counts,
                binned_counts,
                (double) elapsed_time / 1000.0);
    }

    // audio data capture/send timers
    // capture timer called based on buffer size and sampling frequency
    // send data timer must be called with Constants.UPDATE_PERIOD interval
    // calculates and sends data collected by audio channel
    private int dataFromAudioSourceElapsedTime = 0;
    private int eachSecondDataFromAudioSourceElapsedTime = 0;
    private Timer sendDataFromAudioSourceTimer = null;
    private Timer captureDataFromAudioSourceTimer = null;

    private void sendDataFromAudioSourceTimerTask() {
        // hack to stop data send on spectrum load
        if (freeze_update_data) {
            return;
        }

        if (inputType != INPUT_AUDIO) {
            return;
        }

        ForegroundSpectrum.setSpectrumTime(ForegroundSpectrum.getSpectrumTime() + 1);
        dataFromAudioSourceElapsedTime += Constants.UPDATE_PERIOD;
        if (dataFromAudioSourceElapsedTime >= dataFromAudioSourceUpdatePeriod) { // from 0.1 to 1 sec (based on user settings)
            calcCpsAndDoseRateForAudioSource(dataFromAudioSourceElapsedTime);
            sendDataToUI();

            dataFromAudioSourceElapsedTime = 0;
        }

        eachSecondDataFromAudioSourceElapsedTime += Constants.UPDATE_PERIOD;
        if (eachSecondDataFromAudioSourceElapsedTime >= 1000) { // each second
            calcAndSendFoundIsotopesData();
            calcSpectrumChangeData();
            sendDataToAtomSwift(cps, doseRateValue);

            eachSecondDataFromAudioSourceElapsedTime = 0;
        }
    }

    private final void startCapturingAudioSource() {
        synchronized (audioCaptureSync) {
            stopCapturingAudioSource(); // resetting timer just in case

            dataFromAudioSourceElapsedTime = 0;
            eachSecondDataFromAudioSourceElapsedTime = 0;
            sendDataFromAudioSourceTimer = new Timer();
            TimerTask sendDataTask = new TimerTask() {
                @Override
                public void run() {
                    sendDataFromAudioSourceTimerTask();
                }
            };
            sendDataFromAudioSourceTimer.schedule(sendDataTask, Constants.UPDATE_PERIOD, Constants.UPDATE_PERIOD);

            // audio capture settings
            BufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT) * 2; //read two buffers at a time to reduce time consumption
            AudioBytes = new byte[BufferSize]; //Array containing the audio data bytes
            AudioData = new int[BufferSize / 2]; //Array containing the audio samples
            AudioSource = AUDIO_SOURCE_VOICE;
            captureAudioTaskInterval = 1000L * BufferSize / 2 / SAMPLE_RATE; // 46 ms with the default settings
            audioCaptureTimer = 0;
            audioCaptureOldTimer = 0;
            captureDataFromAudioSourceTimer = new Timer();
            TimerTask captureTask = new TimerTask() {
                @Override
                public void run() {
                    captureAudioTask();
                }
            };
            captureDataFromAudioSourceTimer.schedule(captureTask, 0, captureAudioTaskInterval);
        }
    }

    private final void stopCapturingAudioSource() {
        synchronized (audioCaptureSync) {
            releaseAR();
            if (sendDataFromAudioSourceTimer != null) {
                sendDataFromAudioSourceTimer.cancel();
                sendDataFromAudioSourceTimer.purge();
                sendDataFromAudioSourceTimer = null;
            }

            if (captureDataFromAudioSourceTimer != null) {
                captureDataFromAudioSourceTimer.cancel();
                captureDataFromAudioSourceTimer.purge();
                captureDataFromAudioSourceTimer = null;
            }
        }
    }


    // USB data watch dog
    // in rare cases spectrum data receiving randomly stops
    // physical device itself continue working, but android does not provide any data through serial port and all commands end with timeout
    // this timer checks that data is constantly receiving, if no data for some period - try to restart serial interface with -sta command
    private Timer usbDataWatchdogTimer = null;
    private final double usbDataWatchdogInterval = 10; // sec

    private final void usbDataWatchdogTimerTask() {
        synchronized (inputSync) {
            if (inputType != INPUT_SERIAL || freeze_update_data || isRecordingSuspended) {
                return;
            }

            showToastInMainLooper(getStringOrDefaultLocale(R.string.log_usb_watchdog_no_data_triggered, usbDataWatchdogInterval), Toast.LENGTH_LONG);
            UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
            UsbDevice device = AtomSpectraSerial.scanForSpectraProDevice(manager);
            if (device != null) {
                if (manager.hasPermission(device)) {
                    usbDevice.Close();
                    SystemClock.sleep(USB_WAIT_DEVICE);
                    if (usbDevice.Open(device)) {
                        usbDevice.sendTextCommand("-sta", SERVICE_STA_ID);
                    } else {
                        showToastInMainLooper(R.string.log_usb_watchdog_unable_open_device, Toast.LENGTH_LONG);
                        this.onUSBDetached();
                    }
                } else {
                    showToastInMainLooper(R.string.log_usb_watchdog_device_no_perm, Toast.LENGTH_LONG);
                    this.onUSBDetached();
                }
            } else {
                showToastInMainLooper(R.string.log_usb_watchdog_device_not_found, Toast.LENGTH_LONG);
                this.onUSBDetached();
            }
        }
    }

    private final void restartUsbDataWatchdog() {
        // debug line
        // showToastInMainLooper("USB data watchdog: re-schedule timer.", Toast.LENGTH_SHORT);
        synchronized (inputSync) {
            cancelUsbDataWatchdog();
            if (inputType != INPUT_SERIAL) {
                return;
            }

            usbDataWatchdogTimer = new Timer();
            TimerTask watchDogTask = new TimerTask() {
                @Override
                public void run() {
                    usbDataWatchdogTimerTask();
                }
            };
            usbDataWatchdogTimer.schedule(watchDogTask, (int) (usbDataWatchdogInterval * 1000));
        }
    }

    private final void cancelUsbDataWatchdog() {
        synchronized (inputSync) {
            if (usbDataWatchdogTimer != null) {
                usbDataWatchdogTimer.cancel();
                usbDataWatchdogTimer.purge();
                usbDataWatchdogTimer = null;
            }
        }
    }

    private final void onUSBAttached(UsbDevice device) {
        synchronized (inputSync) {
            if (!freeze_update_data && inputType == INPUT_AUDIO) {
                showToastInMainLooper(R.string.usb_attached_while_recording_audio, Toast.LENGTH_LONG);
                return;
            }

            inputType = INPUT_SERIAL;
            inputDeviceInfo = getUsbDeviceInfoText(null);
        }

        if (usbDevice.isOpened() || usbDevice.Open(device)) {
            showToastInMainLooper(R.string.action_usb_attached, Toast.LENGTH_LONG);

            usbDevice.sendTextCommand("-inf", SERVICE_INF_ID);
            usbDevice.sendTextCommand("-cal", SERVICE_CAL_ID);
            usbDevice.sendTextCommand("-mode 0", SERVICE_MODE_ID);
            synchronized (recordingSuspendedSync) {
                if (isRecordingSuspended && recordingSuspendInputType == INPUT_SERIAL) {
                    usbDevice.sendTextCommand("-sta", SERVICE_STA_ID);
                    onUSBConnectionRestored();
                } else {
                    usbDevice.sendTextCommand("-stt", SERVICE_STT_ID);
                }
            }

            sendDataToUI(); // initial render
            refreshServiceNotification();
        } else {
            onUSBNoAccess();
        }
    }

    private final void onUSBDetached() {
        showToastInMainLooper(R.string.action_usb_detached, Toast.LENGTH_LONG);

        if (!freeze_update_data && inputType == INPUT_SERIAL) {
            onUSBConnectionLostDuringRecording();
        } else {
            synchronized (inputSync) {
                if (inputType == INPUT_SERIAL) {
                    inputType = INPUT_AUDIO;
                    inputDeviceInfo = getAudioDeviceInfoText(null);
                }
            }
        }

        usbDevice.Close();
        cancelUsbDataWatchdog();
        refreshServiceNotification();
    }

    private final void onUSBNoAccess() {
        synchronized (inputSync) {
            if (inputType == INPUT_SERIAL) {
                inputType = INPUT_AUDIO;
                inputDeviceInfo = getAudioDeviceInfoText(null);
                setFreeze(true);
            }
        }

        showToastInMainLooper(R.string.action_usb_no_access, Toast.LENGTH_LONG);
    }

    public static double[] makeSmooth(double[] input, Calibration calibration) {
        if (setSmooth) {
            double[] result = new double[input.length];
            int shift_window, old_window;
            shift_window = old_window = StrictMath.max((int) (0.3 * smooth_basic_window), 4);
            int channel_0 = StrictMath.max(100, calibration.toChannel(662.0));
            double[] GolayArray = AtomSpectraFindIsotope.calcSavitzkyGolayWeight(0, 3, shift_window);
            double temp;
            for (int i = 0; i < input.length; i++) {
                shift_window = StrictMath.max((int) ((0.3 + 0.7 * StrictMath.sqrt(calibration.toChannel(662.0) / (double) channel_0)) * smooth_basic_window), 4);
                if (old_window != shift_window) {
                    old_window = shift_window;
                    GolayArray = AtomSpectraFindIsotope.calcSavitzkyGolayWeight(0, 3, shift_window);
                }
                if (i < shift_window || (i >= (input.length - shift_window))) {
                    result[i] = input[i];
                } else {
                    temp = 0.0;
                    for (int j = i - shift_window; j <= i + shift_window; j++) {
                        temp += input[j] * GolayArray[j - (i - shift_window)];
                    }
                    result[i] = StrictMath.max(temp, 0.0);
                }
            }
            return result;
        } else {
            return input;
        }
    }

    public static double[] makeSmooth(long[] input, Calibration calibration) {
        double[] result = new double[input.length];
        if (setSmooth) {
            int shift_window, old_window;
            shift_window = old_window = StrictMath.max((int) (0.3 * smooth_basic_window), 4);
            int channel_0 = StrictMath.max(100, calibration.toChannel(662.0));
            double[] GolayArray = AtomSpectraFindIsotope.calcSavitzkyGolayWeight(0, 3, shift_window);
            //calculate the derivative for the spectrum
            double temp;
            for (int i = 0; i < input.length; i++) {
                shift_window = StrictMath.max((int) ((0.3 + 0.7 * StrictMath.sqrt(calibration.toChannel(662.0) / (double) channel_0)) * smooth_basic_window), 4);
                if (old_window != shift_window) {
                    old_window = shift_window;
                    GolayArray = AtomSpectraFindIsotope.calcSavitzkyGolayWeight(0, 3, shift_window);
                }
                if (i < shift_window || (i >= (input.length - shift_window))) {
                    result[i] = input[i];
                } else {
                    temp = 0.0;
                    for (int j = i - shift_window; j <= i + shift_window; j++) {
                        temp += input[j] * GolayArray[j - (i - shift_window)];
                    }
                    result[i] = StrictMath.max(temp, 0.0);
                }
            }
        } else {
            for (int i = 0; i < input.length; i++)
                result[i] = input[i];
        }
        return result;
    }

    private void saveCurrentSpectrum(String suffix) {
        SharedPreferences sharedPreferences = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        boolean fileNamePrefix = sharedPreferences.getBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_PREFIX, Constants.OUTPUT_FILE_NAME_PREFIX_DEFAULT);
        boolean fileNameDate = sharedPreferences.getBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_DATE, Constants.OUTPUT_FILE_NAME_DATE_DEFAULT);
        boolean fileNameTime = sharedPreferences.getBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_TIME, Constants.OUTPUT_FILE_NAME_TIME_DEFAULT);
        String workingDir = sharedPreferences.getString(Constants.CONFIG.CONF_DIRECTORY_SELECTED, null);
        if (workingDir == null) {
            showToastInMainLooper(R.string.error_working_dir_not_set, Toast.LENGTH_LONG);
            return;
        }
        Pair<OutputStreamWriter, Uri> returnPair = SpectrumFile.prepareOutputStream(this, workingDir, ForegroundSpectrum.getSpectrumDate(), "Spectrum", fileNamePrefix, suffix, ".txt", "text/plain", fileNameDate, fileNameTime, false);
        if (returnPair == null) {
            showToastInMainLooper(getStringOrDefaultLocale(R.string.log_no_perm_to_save_spectrum, suffix), Toast.LENGTH_LONG);
            return;
        }

        OutputStreamWriter docStream = returnPair.first;

        Spectrum spectrum = new Spectrum(AtomSpectraService.ForegroundSpectrum);
        Spectrum backSpectrum = new Spectrum(AtomSpectraService.BackgroundSpectrum);

        if (!addGPS) {
            spectrum.setLocation(null).updateComments();
            backSpectrum.setLocation(null).updateComments();
        }

        SpectrumFileAS saveFile = new SpectrumFileAS();
        saveFile.
                addSpectrum(spectrum).
                setChannels(spectrum.getDataArray().length).
                setChannelCompression(1).
                saveSpectrum(docStream, this);
    }

    private void notifySpectrogramUpdated() {
        sendBroadcast(new Intent(Constants.ACTION.ACTION_SPECTROGRAM_UPDATED).setPackage(Constants.PACKAGE_NAME));
    }

    private void createOrUpdateSpectrogramFile() {
        if (spgMidnightReset && spgAutosaveFile != null && spgAutosaveFileCreated != null) {
            Date now = new Date();
            if (now.getDate() != spgAutosaveFileCreated.getDate()) {
                appendDeltaToSpectrogram();
                closeSpectrogramFile();
                showToastInMainLooper(R.string.log_spg_midnight_restart, Toast.LENGTH_LONG);
            }
        }

        SharedPreferences sharedPreferences = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        boolean fileNamePrefix = sharedPreferences.getBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_PREFIX, Constants.OUTPUT_FILE_NAME_PREFIX_DEFAULT);

        if (spgAutosaveSpectrum == null) {
            spgAutosaveSpectrum = new Spectrum(AtomSpectraService.ForegroundSpectrum);
            spgAutosaveSpectrum.updateComments();
            String workingDir = sharedPreferences.getString(Constants.CONFIG.CONF_DIRECTORY_SELECTED, null);
            if (workingDir == null) {
                showToastInMainLooper(R.string.error_working_dir_not_set, Toast.LENGTH_LONG);
                showToastInMainLooper(R.string.log_spg_autosave_start_error, Toast.LENGTH_LONG);
                return;
            }
            spgAutosaveFile = SpectrumFile.prepareOutputStream(this, workingDir, System.currentTimeMillis(), "Spectrogram" + '-' + spgAutosaveSpectrum.getSuffix(), fileNamePrefix, "", ".txt", "text/plain", true, true, false);
            spgAutosaveFileCreated = new Date();

            if (spgAutosaveFile == null) {
                this.showToastInMainLooper(R.string.log_spg_no_perm_to_save, Toast.LENGTH_LONG);

                spgAutosaveSpectrum = null;
                spgAutosaveFileCreated = null;
                return;
            }

            OutputStreamWriter docStream = spgAutosaveFile.first;
            SpectrumFileAS saveFile = new SpectrumFileAS();
            saveFile.
                    addSpectrum(spgAutosaveSpectrum).
                    setChannels(spgAutosaveSpectrum.getDataArray().length).
                    setChannelCompression(1).
                    saveSpectrum(docStream, this);

            AtomSpectraSpectrogramData.instance.clear();
            AtomSpectraSpectrogramData.instance.setBaseSpectrum(spgAutosaveSpectrum);
            notifySpectrogramUpdated();
            this.showToastInMainLooper(R.string.log_spg_autosave_start, Toast.LENGTH_LONG);

            return;
        }

        appendDeltaToSpectrogram();
    }

    private void appendDeltaToSpectrogram() {
        Spectrum newSpectrum = new Spectrum(ForegroundSpectrum);
        Spectrum deltaSpectrum = new Spectrum(newSpectrum).getDeltaSpectrum(spgAutosaveSpectrum);

        if (deltaSpectrum == null) {
            // TODO: localize
            showToastInMainLooper("Unexpected: delta spectrum is null", Toast.LENGTH_LONG);
            return;
        }

        if (!addGPS) {
            deltaSpectrum.setLocation(null);
        }
        deltaSpectrum.updateComments();
        spgAutosaveSpectrum = newSpectrum;

        OutputStreamWriter docStream;
        try {
            docStream = new OutputStreamWriter(service_context.getContentResolver().openOutputStream(spgAutosaveFile.second, "wa"));// new (new Uri.Builder().build());
            SpectrumFileAS saveFile = new SpectrumFileAS();
            saveFile.
                    addSpectrum(deltaSpectrum).
                    setChannels(deltaSpectrum.getDataArray().length).
                    setChannelCompression(1).
                    saveDeltaSpectrum(docStream, this);
        } catch (Exception e) {
            this.showToastInMainLooper(String.format("!%s: %s", e.getMessage(), spgAutosaveFile.second), Toast.LENGTH_LONG);
        }

        AtomSpectraSpectrogramData.instance.addDelta(deltaSpectrum);
        notifySpectrogramUpdated();
    }

    private void checkGPS() {
        boolean hasFeatureGPS = getPackageManager().hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS);
        boolean hasFeatureNetwork = getPackageManager().hasSystemFeature(PackageManager.FEATURE_LOCATION_NETWORK);
        SharedPreferences sharedPreferences = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        addGPS = sharedPreferences.getBoolean(Constants.CONFIG.CONF_ADD_GPS_TO_FILES, false);

        if ((hasFeatureGPS || hasFeatureNetwork) && addGPS) {
            if (PermissionChecker.checkSelfPermission(getApplicationContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PermissionChecker.PERMISSION_GRANTED) {
                Locator.startUsingGPS();
                if (!Locator.hasGPS) {
                    SharedPreferences.Editor editor = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE).edit();
                    editor.putBoolean(Constants.CONFIG.CONF_ADD_GPS_TO_FILES, false);
                    editor.apply();
                    sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_SETTINGS).setPackage(Constants.PACKAGE_NAME));
                }
            } else {
                Locator.stopUsingGPS();
                SharedPreferences.Editor editor = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE).edit();
                editor.putBoolean(Constants.CONFIG.CONF_ADD_GPS_TO_FILES, false);
                editor.apply();
                sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_SETTINGS).setPackage(Constants.PACKAGE_NAME));
            }
        } else {
            Locator.stopUsingGPS();
        }
    }

    // sends intent with dose/count rate etc. to AtomSwift app
    // expected to be called each second
    // dose rates expected to be uSv/h
    private void sendDataToAtomSwift(int cps, DoseRate doseRate) {
        if (!sendDataToAtomSwiftAppEnabled || freeze_update_data) {
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

        String inputTypeStr = "";
        switch (inputType) {
            case INPUT_AUDIO:
                inputTypeStr = "MIC";
                break;
            case INPUT_SERIAL:
                inputTypeStr = "USB";
                break;
            default:
                inputTypeStr = "NONE";
        }

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

    private void suspendAudioRecording(int suspend_reason) {
        synchronized (recordingSuspendedSync) {
            isRecordingSuspended = true;
            recordingSuspendReason = suspend_reason;
            recordingSuspendInputType = INPUT_AUDIO;

            stopCapturingAudioSource();
            onRecordingSuspended();
        }
    }

    private void restoreAudioRecording() {
        resetRecordingSuspendedStatus(false);
        startCapturingAudioSource();
        onRecordingResumed();
    }

    private void onUSBConnectionLostDuringRecording() {
        synchronized (recordingSuspendedSync) {
            isRecordingSuspended = true;
            recordingSuspendReason = RECORDING_SUSPEND_REASON_USB_DISCONNECT;
            recordingSuspendInputType = INPUT_SERIAL;

            onRecordingSuspended();
        }
    }

    private void onUSBConnectionRestored() {
        resetRecordingSuspendedStatus(false);
        onRecordingResumed();
    }

    private void onRecordingSuspended() {
        recordingSuspendedAt = new Date();

        saveCurrentSpectrum("recording_suspended");
        if (recordingSuspendInputType == INPUT_SERIAL) {
            skipUnreliableUSBData();
        }
        resetSpectrumChangeWindow();
        resetSearchWindow();
        resetAtomSwiftIntermediateData();
        sendBroadcast(new Intent(ACTION_RECORDING_SUSPENDED).setPackage(Constants.PACKAGE_NAME));
        refreshServiceNotification();
        playNotificationSound();

        AtomSpectraLog.addMessage(service_context, getStringOrDefaultLocale(R.string.log_recording_suspended));
    }

    private void onRecordingResumed() {
        recordingResumedAt = new Date();
        sendBroadcast(new Intent(ACTION_RECORDING_RESUMED).setPackage(Constants.PACKAGE_NAME));
        refreshServiceNotification();
        playNotificationSound();

        AtomSpectraLog.addMessage(service_context, getStringOrDefaultLocale(R.string.log_recording_resumed));
    }

    private void skipUnreliableUSBData() {
        skip_next_cps_int_usb_calc = USB_DATA_SKIP_SECONDS;
    }

    private void refreshServiceNotification() {
        if (service_context != null) {
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
            recordingSuspendReason = RECORDING_SUSPEND_REASON_NONE;
            recordingSuspendInputType = INPUT_NONE;

            if (withDates) {
                recordingSuspendedAt = null;
                recordingResumedAt = null;
            }
        }
    }

    private static void resetSpectrumChangeWindow() {
        Arrays.fill(histogram_all_sp_change_fg, 0);
        Arrays.fill(histogram_all_sp_change_bg, 0);
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

    private static class DoseRate {
        public final double compensated; // uSv/h
        public final double compensatedErrorPercent; // 1 sigma %
        public final double nonCompensated; // uSv/h
        public final double nonCompensatedErrorPercent; // 1 sigma %
        public final double intervalCps; // cps
        public final double intervalCpsErrorPercent; // 1 sigma %

        private DoseRate() {
            this.compensated = 0;
            this.compensatedErrorPercent = 0;
            this.nonCompensated = 0;
            this.nonCompensatedErrorPercent = 0;
            this.intervalCps = 0;
            this.intervalCpsErrorPercent = 0;
        }

        private DoseRate(
                double compensated,
                double compensatedErrorPercent,
                double nonCompensated,
                double nonCompensatedErrorPercent,
                double intervalCps,
                double intervalCpsErrorPercent) {
            this.compensated = compensated;
            this.compensatedErrorPercent = compensatedErrorPercent;
            this.nonCompensated = nonCompensated;
            this.nonCompensatedErrorPercent = nonCompensatedErrorPercent;
            this.intervalCps = intervalCps;
            this.intervalCpsErrorPercent = intervalCpsErrorPercent;
        }
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

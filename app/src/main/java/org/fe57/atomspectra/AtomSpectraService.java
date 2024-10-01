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
import android.graphics.drawable.Icon;
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
import androidx.core.util.Pair;
import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Timer;
import java.util.TimerTask;

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
    private static boolean outputSound = false;
    private static int outputSoundID = -1;
    private static String outputSoundName = null;
    private static boolean inputSound = false;
    private static int inputSoundID = -1;
    private static String inputSoundName = null;
    private static double cpsBaseLevel = 0;
    private static double cpsBaseSignal = 0;
    private static double cpsCurrentLevel = 0;
    private static final Integer soundSync = 1;
    private static AudioTrack soundTrack = null;
    private static int soundBufferSize = 0;
    private static float[] soundBuffer = null;
    private static int soundBufferSwitch = 0;
    private static final double minMeanSoundTime = 10.0;
    private static int soundPeriodCount = 0;
    private static int soundPeriodNum = 0;
    private static final double[] soundFreqList = new double[]{400, 420, 450, 490, 580, 630, 700, 800, 900, 980, 1050, 1260, 1470, 2100, 2450, 3150, 4010, 4410, 4900, 6300, 7350};
    private static final double soundSigma = 4.0;
    private static final double soundSigmaTime = 4.0; //in seconds
    public static int lastCalibrationChannel = Constants.NUM_HIST_POINTS;
    public static int leftChannelInterval = 0;
    public static int rightChannelInterval = Constants.NUM_HIST_POINTS - 1;
    private static double leftEnergyInterval = 0;
    private static double rightEnergyInterval = 0;

    public static Calibration newCalibration = new Calibration();
    public static Spectrum ForegroundSpectrum = new Spectrum();
    public static Spectrum ForegroundSaveSpectrum = new Spectrum();
    public static Spectrum BackgroundSpectrum = new Spectrum();
//    private GPSLocator Locator = null;
    private boolean addGPS = false;


    public static boolean setSmooth = false;
    public static boolean showDelta = false;
    private int deltaTimeAccumulator = 0;
    private int delta_time = Constants.DEFAULT_DELTA_TIME;
    public static boolean isStarted = false;
    public static boolean showCalibrationFunction = false;

    //data for spectrum
    private static final double[] histogram = new double[1024];
    public static long[] histogram_all_delta = new long[Constants.NUM_HIST_POINTS];       //array to save delta
    public static long[] histogram_all_prev_time = new long[Constants.NUM_HIST_POINTS];       //array to save delta
    private static final long[] referencePulse = new long[1024];
    private static final double[] referenceDoublePulse = new double[1024];
    private static final double[] realTimeX = new double[1024];

    //data for background
    private static final double[] background_histogram = new double[1024];                              //back histogram to draw with the main histogram
    public static boolean background_show = false;
    private static boolean freeze_update_data = true;

    public static int total_pulses_cps = 0;
    public static int total_pulses_cps_interval = 0;
    public static long total_pulses = 0;
    private static int cps = 0;
    private static int cpsInterval = 0;
    public static double total_energy_cps = 0.0;
    private static int autosaveTimeout = 0;
    private static int autosaveIncrement = 0;
    private Spectrum autosaveSpectrum = null;
    private Pair<OutputStreamWriter, Uri> autosavePair = null;

    private static int scale_factor = Constants.SCALE_DEFAULT;
    private static int main_scale_factor = Constants.SCALE_DEFAULT;
    private static final Integer sync_factor = 1;
    private final int mutabilityFlag = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ? PendingIntent.FLAG_IMMUTABLE : 0;

    public final static String ACTION_DATA_AVAILABLE =
            "org.fe57.atomspectra.ACTION_DATA_AVAILABLE";
    public final static String EXTRA_DATA =
            "org.fe57.atomspectra.EXTRA_DATA";

    public final static String EXTRA_DATA_LONG_COUNTS =
            "org.fe57.atomspectra.EXTRA_DATA_LONG_COUNTS";
    public final static String EXTRA_DATA_INT_CPS =
            "org.fe57.atomspectra.EXTRA_DATA_INT_CPS";
    public final static String EXTRA_DATA_INT_CPS_INTERVAL =
            "org.fe57.atomspectra.EXTRA_DATA_INT_CPS_INTERVAL";

    public final static String EXTRA_DATA_ARRAY_LONG_COUNTS =
            "org.fe57.atomspectra.EXTRA_DATA_ARRAY_LONG_COUNTS";
    public final static String EXTRA_DATA_ARRAY_BACK_COUNTS =
            "org.fe57.atomspectra.EXTRA_DATA_ARRAY_BACK_COUNTS";
    public final static String EXTRA_DATA_SHOW_BACK_COUNTS =
            "org.fe57.atomspectra.EXTRA_DATA_SHOW_BACK_COUNTS";
    public final static String EXTRA_DATA_ARRAY_INT_SOUND =
            "org.fe57.atomspectra.EXTRA_DATA_ARRAY_INT_SOUND";
    public final static String EXTRA_DATA_ARRAY_INT_SOUND_LENGTH =
            "org.fe57.atomspectra.EXTRA_DATA_ARRAY_INT_SOUND_LENGTH";
    public final static String EXTRA_DATA_TOTAL_TIME =
            "org.fe57.atomspectra.EXTRA_DATA_TOTAL_TIME";
    public final static String EXTRA_DATA_DOSERATE_SEARCH =
            "org.fe57.atomspectra.EXTRA_DATA_DOSERATE_SEARCH";
    public final static String EXTRA_DATA_SCOPE_COUNTS =
            "org.fe57.atomspectra.EXTRA_DATA_SCOPE_COUNTS";
    private final static String SERVICE_ID = "Service";
    private final static String SERVICE_VERSION_ID = "Service version";

    public final static String EXTRA_SOURCE = "Data source";
    public final static String EXTRA_SOURCE_USB = "USB";
    public final static String EXTRA_SOURCE_AUDIO = "Audio";

    public final static String CHANNEL_ID = "AtomSpectraService";

    private static final int USB_WAIT_DEVICE = 600;
    // RECORDING VARIABLES  
    private static AudioRecord AR = null;
    private static final Integer ARLock = 1;        //Locker for AudioRecord
    private static boolean ARShowAbsentMessage = true;
    private static int BufferSize;                    // Length of the chunks read from the hardware audio buffer
    //    private static Thread Record_Thread = null;      // The thread filling up the audio buffer (queue)
    public static boolean isRecording = false;
    private static final int AUDIO_SOURCE_VOICE = MediaRecorder.AudioSource.VOICE_RECOGNITION;
    private static final int AUDIO_SOURCE_RAW = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N ? MediaRecorder.AudioSource.UNPROCESSED : MediaRecorder.AudioSource.VOICE_RECOGNITION;      //only from API>=24
    private static int SAMPLE_RATE = 44100;
    private static final int CHANNEL_CONFIG = android.media.AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = android.media.AudioFormat.ENCODING_PCM_16BIT;

    private static int SensG, backgroundCount;
    private static int SEARCH_FAST = 25, SEARCH_MEDIUM = 35, SEARCH_SLOW = 70;

    private static int SearchFSM = 0; //0 - fast, 1 - medium, 2 - slow

    private static double doseRateValue = 0.0;
    private int doseRateUpdateFreq = 1;
    private int periodUpdate = 10;

    private static int timer = 0;
    private static int doseRateTimer = 0;
    private static final int[] cpsArray = new int[1000 / Constants.UPDATE_PERIOD];
    private static final int[] cpsArrayInterval = new int[1000 / Constants.UPDATE_PERIOD];
    private static final double[] cpsArrayEnergy = new double[1000 / Constants.UPDATE_PERIOD];
    private static long audioCaptureTimer = 0;
    private static long audioCaptureOldTimer = 0;
    private static long audioTaskTimeout = 0;
    private static int cpsPos = 0;
    private static int compressGraph = Constants.COMPRESS_GRAPH_SUM;
    //private double cps_energy_sec = 0.0;

    public final static int INPUT_NONE = 0;             //Nothing
    public final static int INPUT_SERIAL = 1;           //Use serial for NanoPro
    public final static int INPUT_AUDIO = 2;            //Use audio channel
    private static int inputSelectNext = INPUT_NONE;    //Select type on next work (do not switch if INPUT_NONE)
    public static int inputType = INPUT_AUDIO;           //synchronisation data value
//    public static int inputLastState = INPUT_NONE;
    private static final Integer inputSync = 1;

    private SharedPreferences sp;

    private final AudioDeviceCallback audioChanged = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) ? createAudioDeviceCallback() : null;

    private AtomSpectraSerial usbDevice = null;

    private GPSLocator Locator = null;

//    private static FileOutputStream trace;

    @TargetApi(Build.VERSION_CODES.M)
    private AudioDeviceCallback createAudioDeviceCallback() {
        return new AudioDeviceCallback() {
            boolean startExecution = false;
            @Override
            public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
                super.onAudioDevicesAdded(addedDevices);
                if (startExecution) {
                    context.sendBroadcast(new Intent(Constants.ACTION.ACTION_AUDIO_CHANGED).setPackage(Constants.PACKAGE_NAME));
                    releaseAR();
                } else {
                    startExecution = true;
                }
            }

            @Override
            public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
                super.onAudioDevicesRemoved(removedDevices);
                if (startExecution) {
                    context.sendBroadcast(new Intent(Constants.ACTION.ACTION_AUDIO_CHANGED).setPackage(Constants.PACKAGE_NAME));
                    releaseAR();
                } else {
                    startExecution = true;
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
        super.onDestroy();
        Stop();
        DeleteSpc();
        Log.d(TAG, "onDestroy");
        context = null;
        BackgroundSpectrum.initSpectrumData();
//        background_time = 0;
        background_show = false;
        freeze_update_data = true;
        releaseAR();
        sp.unregisterOnSharedPreferenceChangeListener(onSharedPreferenceChangeListener);
        usbDevice.Destroy();
        isStarted = false;
//        Locator.stopUsingGPS();
//        Locator = null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.app_channel),
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null)
                manager.createNotificationChannel(serviceChannel);
        }
        Notification notification = createNewServiceNotification();
        NotificationManagerCompat.from(context).notify(FOREGROUND_PROCESS_ID, createNewServiceNotification());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(FOREGROUND_PROCESS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE|ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
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
                if (usbDevice.isOpened() || usbDevice.Open(device)) {
                    usbDevice.sendTextCommand("-inf", SERVICE_VERSION_ID);
                    usbDevice.sendTextCommand("-mode 0", SERVICE_ID);
//                    usbDevice.sendTextCommand("-sta", SERVICE_ID);
                    synchronized (inputSync) {
                        if (inputType != INPUT_SERIAL)
                            Toast.makeText(this, getString(R.string.action_usb_attached), Toast.LENGTH_LONG).show();
                        inputSelectNext = INPUT_SERIAL;
//                        inputLastState = INPUT_SERIAL;
                        releaseAR();
                    }
                    setFreeze(false);
//                    sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_MENU));
//                    sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_CALIBRATION).putExtra(Constants.ACTION_PARAMETERS.UPDATE_USB_CALIBRATION, true));
                } else {
                    Toast.makeText(this, getString(R.string.action_usb_no_access), Toast.LENGTH_LONG).show();
                    synchronized (inputSync) {
                        inputSelectNext = INPUT_AUDIO;
//                        inputLastState = INPUT_AUDIO;
                    }
                }
            }
        } else {
            UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
            if (manager != null) {
                HashMap<String, UsbDevice> deviceList = manager.getDeviceList();
                for (UsbDevice device : deviceList.values()) {
                    if (device.getVendorId() != 1027)
                        continue;
                    if (!manager.hasPermission(device)) {
//                        Toast.makeText(this, device.getDeviceName() + " is not permitted", Toast.LENGTH_LONG).show();
                        continue;
                    }
                    if (device.getProductId() == 24577 || device.getProductId() == 1002) {
                        SystemClock.sleep(USB_WAIT_DEVICE);
                        if (usbDevice.isOpened() || usbDevice.Open(device)) {
                            usbDevice.sendTextCommand("-inf", SERVICE_VERSION_ID);
                            usbDevice.sendTextCommand("-mode 0", SERVICE_ID);
                            usbDevice.sendTextCommand("-sta", SERVICE_ID);
                            synchronized (inputSync) {
                                if (inputType != INPUT_SERIAL)
                                    Toast.makeText(this, getString(R.string.action_usb_attached), Toast.LENGTH_LONG).show();
                                inputSelectNext = INPUT_SERIAL;
//                                inputLastState = INPUT_SERIAL;
                                releaseAR();
                            }
                            setFreeze(false);
                            sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_MENU).setPackage(Constants.PACKAGE_NAME));
//                            sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_CALIBRATION).putExtra(Constants.ACTION_PARAMETERS.UPDATE_USB_CALIBRATION, true));
                        } else {
                            Toast.makeText(this, getString(R.string.action_usb_no_access), Toast.LENGTH_LONG).show();
                            synchronized (inputSync) {
                                inputSelectNext = INPUT_AUDIO;
//                                inputLastState = INPUT_AUDIO;
                            }
                        }
                    }
                }
            } else {
                synchronized (inputSync) {
                    inputSelectNext = INPUT_AUDIO;
//                    inputLastState = INPUT_AUDIO;
                }
            }
        }
        sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_MENU).setPackage(Constants.PACKAGE_NAME));
        isStarted = true;
        return START_NOT_STICKY;
    }

    private Notification createNewServiceNotification() {
        String notifyString = "";
        synchronized (inputSync) {
            if (inputType == INPUT_AUDIO) {
                notifyString = getString(R.string.app_bar_audio_action);
            }
            if (inputType == INPUT_SERIAL) {
                notifyString = getString(R.string.app_bar_usb_action);
            }
        }
        if (!freeze_update_data) {
            notifyString += " " + getString(R.string.app_bar_spectrum_update);
        }
        Intent notificationIntent = new Intent(this, AtomSpectra.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
//        notificationIntent.setAction(Intent.ACTION_MAIN);
//        notificationIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        PendingIntent pendingIntent = PendingIntent.getActivity(this,
                0, notificationIntent, mutabilityFlag);
        Notification notification;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//            Intent exitIntent = new Intent(this, AtomSpectraService.class).setAction(Constants.ACTION.ACTION_STOP_FOREGROUND);
            Intent exitIntent = new Intent(Constants.ACTION.ACTION_STOP_FOREGROUND).setPackage(Constants.PACKAGE_NAME);
//            exitIntent.setAction(Constants.ACTION.ACTION_STOP_FOREGROUND);
            PendingIntent exitPendingIntent =
                    PendingIntent.getBroadcast(this, 0, exitIntent, mutabilityFlag);

            notification = new Notification.Builder(this, CHANNEL_ID)
//                    .setContentTitle(getString(R.string.app_name))
                    .setContentText(notifyString)
                    .setSmallIcon(R.drawable.logo_atom)
                    .setColor(0xFFFFA629)
                    .setContentIntent(pendingIntent)
                    .addAction(new Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_delete), getString(R.string.action_exit), exitPendingIntent).build())
                    .build();
        } else {
            notification = new NotificationCompat.Builder(this, CHANNEL_ID)
//                    .setContentTitle(getString(R.string.app_name))
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
            editor.putInt(Constants.CONFIG.CONF_SENSG, SensG);
            editor.apply();
        }
        try {
            backgroundCount = sp.getInt(Constants.CONFIG.CONF_BACKGROUND, Constants.BACKGND_CNT_DEFAULT);
        } catch (Exception e) {
            backgroundCount = (int) sp.getFloat(Constants.CONFIG.CONF_BACKGROUND, Constants.BACKGND_CNT_DEFAULT);
            SharedPreferences.Editor editor = sp.edit();
            editor.putInt(Constants.CONFIG.CONF_BACKGROUND, backgroundCount);
            editor.apply();
        }
//        GolayArray = AtomSpectraFindIsotope.calcSavitzkyGolayWeight(0,3, -1 + 8 * sp.getInt(Constants.CONFIG.CONF_GOLAY_WINDOW, Constants.DEFAULT_GOLAY_WINDOW));
        basic_window = -1 + 8 * sp.getInt(Constants.CONFIG.CONF_GOLAY_WINDOW, Constants.DEFAULT_GOLAY_WINDOW);
        delta_time = sp.getInt(Constants.CONFIG.CONF_DELTA_TIME, Constants.DEFAULT_DELTA_TIME);
        SearchFSM = sp.getInt(Constants.CONFIG.CONF_SEARCH_MODE, 0);
        SEARCH_FAST = sp.getInt(Constants.CONFIG.CONF_SEARCH_FAST, Constants.SEARCH_FAST_DEFAULT);
        SEARCH_SLOW = sp.getInt(Constants.CONFIG.CONF_SEARCH_SLOW, Constants.SEARCH_SLOW_DEFAULT);
        SEARCH_MEDIUM = sp.getInt(Constants.CONFIG.CONF_SEARCH_MEDIUM, Constants.SEARCH_MEDIUM_DEFAULT);
        doseRateUpdateFreq = sp.getInt(Constants.CONFIG.CONF_DOSE_UPDATE, Constants.UPDATE_DOSE_DEFAULT);
        adc_effective_bits = Constants.MinMax(sp.getInt(Constants.CONFIG.CONF_ROUNDED, Constants.ADC_DEFAULT), Constants.ADC_MIN, Constants.ADC_MAX);
        frontCountsMin = sp.getInt(Constants.CONFIG.CONF_MIN_POINTS, Constants.MIN_FRONT_POINTS_DEFAULT);
        frontCountsMax= sp.getInt(Constants.CONFIG.CONF_MAX_POINTS, Constants.MAX_FRONT_POINTS_DEFAULT);
        histogramMinChannel = sp.getInt(Constants.CONFIG.CONF_NOISE, Constants.NOISE_DISCRIMINATOR_DEFAULT);
        inversion = sp.getBoolean(Constants.CONFIG.CONF_INVERSION, Constants.INVERSE_DEFAULT);
        pileup = sp.getBoolean(Constants.CONFIG.CONF_PILE_UP, Constants.PILE_UP_DEFAULT);
        periodUpdate = 10 / doseRateUpdateFreq;
        autosaveTimeout = sp.getInt(Constants.CONFIG.CONF_AUTOSAVE, Constants.AUTOSAVE_DEFAULT);
        try {
            compressGraph = sp.getInt(Constants.CONFIG.CONF_COMPRESS_GRAPH, Constants.COMPRESS_GRAPH_SUM);
        } catch (Exception e) {
            compressGraph = Constants.COMPRESS_GRAPH_SUM;
        }
        CharSequence[] data = getResources().getTextArray(R.array.compress_graph_array);
        compressGraph = Constants.MinMax(compressGraph, 0, data.length - 1);
        isCalibrated = sp.getBoolean(Constants.CONFIG.CONF_CALIBRATED, true);
        outputSound = sp.getBoolean(Constants.CONFIG.CONF_OUTPUT_SOUND, false) && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M);
        outputSoundID = sp.getInt(Constants.CONFIG.CONF_OUTPUT_SOUND_DEVICE_ID, -1);
        outputSoundName = sp.getString(Constants.CONFIG.CONF_OUTPUT_SOUND_DEVICE_NAME, "(none)");
        addGPS = sp.getBoolean(Constants.CONFIG.CONF_ADD_GPS_TO_FILES, false);
        outputSoundInit();
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

        int newETomSvCount = sp.getInt(Constants.CONFIG.CONF_CALIBRATION_SIZE, ETomSvDefault.length);
        if (newETomSvCount == ETomSv.length) {
            EnergyListCount = newETomSvCount;
            try {
                EnergyList = new double[EnergyListCount];
                ETomSv = new double[EnergyListCount];
                for (int i = 0; i < EnergyListCount; i++) {
                    ETomSv[i] = Double.longBitsToDouble(sp.getLong(Constants.configCalibration(i), Double.doubleToRawLongBits(ETomSvDefault[i])));
                    EnergyList[i] = sp.getFloat(Constants.configCalibrationEnergy(i), (float) EnergyListDefault[i]);
                }
            } catch (Exception e) {
                SharedPreferences.Editor editor = sp.edit();
                editor.putInt(Constants.CONFIG.CONF_CALIBRATION_SIZE, AtomSpectraService.ETomSvDefault.length);
                EnergyListCount = EnergyListDefault.length;
                EnergyList = new double[EnergyListCount];
                ETomSv = new double[EnergyListCount];
                for (int i = 0; i < EnergyListDefault.length; i++) {
                    ETomSv[i] = ETomSvDefault[i];
                    EnergyList[i] = EnergyListDefault[i];
                    editor.putLong(Constants.configCalibration(i), Double.doubleToRawLongBits(ETomSvDefault[i]));
                    editor.putFloat(Constants.configCalibrationEnergy(i), (float) EnergyListDefault[i]);
                }
                editor.apply();
            }
        }
    }

    public static int getScaleFactor() {
        synchronized (sync_factor) {
            return scale_factor;
        }
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
//                    if ((inputType == INPUT_SERIAL) && (usbDevice.isOpened())) {
//                        usbDevice.sendTextCommand("-mode 0", SERVICE_ID);
//                    }
                    break;
                case Constants.SCALE_COUNT_MODE:
//                if ((inputType == INPUT_SERIAL) && (usbDevice.isOpened())) {
//                    usbDevice.sendTextCommand("-mode 1", SERVICE_ID);
//                }
                    break;
                case Constants.SCALE_IMPULSE_MODE:
//                if ((inputType == INPUT_SERIAL) && (usbDevice.isOpened())) {
//                    usbDevice.sendTextCommand("-mode 2", SERVICE_ID);
//                }
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

    public static void reloadBaseLevel() {
        synchronized (soundSync) {
            cpsBaseLevel = 0;
            cpsCurrentLevel = 0;
            cpsBaseSignal = 0;
        }
    }

    public static void setEnergyInterval(double leftEnergy, double rightEnergy) {
        leftChannelInterval = Constants.MinMax(ForegroundSpectrum.getSpectrumCalibration().toChannel(leftEnergy),0, Constants.NUM_HIST_POINTS - 1);
        leftEnergyInterval = leftEnergy;
        rightChannelInterval = Constants.MinMax(ForegroundSpectrum.getSpectrumCalibration().toChannel(rightEnergy),0, Constants.NUM_HIST_POINTS - 1);
        rightEnergyInterval = rightEnergy;
    }

    public static void setChannelInterval(int leftChannel, int rightChannel) {
        leftChannelInterval = Constants.MinMax(leftChannel,0, Constants.NUM_HIST_POINTS - 1);
        leftEnergyInterval = ForegroundSpectrum.getSpectrumCalibration().toEnergy(leftChannelInterval);
        rightChannelInterval = Constants.MinMax(rightChannel,0, Constants.NUM_HIST_POINTS - 1);
        rightEnergyInterval = ForegroundSpectrum.getSpectrumCalibration().toEnergy(rightChannelInterval);
    }

    public static void resetInterval () {
        leftChannelInterval = 0;
        rightChannelInterval = Constants.NUM_HIST_POINTS - 1;
        leftEnergyInterval = rightEnergyInterval = 0;
    }

    public static void recalculateInterval () {
        if (leftChannelInterval != 0 || rightChannelInterval != Constants.NUM_HIST_POINTS - 1) {
            setEnergyInterval(leftEnergyInterval, rightEnergyInterval);
        }
    }

    private final Timer autosaveTimer = new Timer();
    private final TimerTask autosaveTask = new TimerTask() {
        @Override
        public void run() {
            synchronized (autosaveTimer) {
                if (autosaveTimeout > 0) {
                    if (!freeze_update_data) {
                        autosaveIncrement += 1;
                        if (autosaveIncrement >= autosaveTimeout) {
                            autosaveHist();
                            autosaveIncrement = 0;
                        }
                    } else {
                        autosaveIncrement = 0;
                        if (autosaveSpectrum != null) {
                            autosaveSpectrum = null;
                            try {
                                autosavePair.first.close();
                            } catch (IOException e) {
                                //
                            }
                            autosavePair = null;
                        }
                    }
                }
            }
        }
    };

    private final Timer serialTimer = new Timer();
    private final TimerTask serialTask = new TimerTask() {
        @Override
        public void run() {
            //TODO:
            synchronized (inputSync) {
                if (inputSelectNext == INPUT_SERIAL) {
                    inputType = inputSelectNext;
                    inputSelectNext = INPUT_NONE;
//                    new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(getApplicationContext(), "test1: "+ForegroundSpectrum.getSpectrumTime(), Toast.LENGTH_SHORT).show());
                    if (ForegroundSpectrum.getSpectrumTime() == 0) {
                        sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_CALIBRATION).putExtra(Constants.ACTION_PARAMETERS.UPDATE_USB_CALIBRATION, true).setPackage(Constants.PACKAGE_NAME));
//                        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(getApplicationContext(), "test2", Toast.LENGTH_SHORT).show());
                    }
                    freeze_update_data = false;
                    NotificationManagerCompat.from(context).notify(FOREGROUND_PROCESS_ID, createNewServiceNotification());
                    releaseAR();
                }

                if (inputType != INPUT_SERIAL)
                    return;
            }

            total_pulses = ForegroundSpectrum.getTotalCounts();
        }
    };

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
    private Context context = null;
    private static int basic_window = 7;

    @SuppressLint({"UnspecifiedRegisterReceiverFlag", "DiscouragedApi"})
    public void Start(final Context context) {
        this.context = context;
        synchronized (inputSync) {
            inputType = INPUT_AUDIO;
        }

        boolean hasFeatureGPS = getPackageManager().hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS);
        boolean hasFeatureNetwork = getPackageManager().hasSystemFeature(PackageManager.FEATURE_LOCATION_NETWORK);
        if (Locator == null)
            Locator = new GPSLocator(getApplicationContext());
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
//                    final AlertDialog.Builder alert = new AlertDialog.Builder(this)
//                            .setTitle(getString(R.string.perm_ask_gps_off_title))
//                            .setMessage(getString(R.string.perm_ask_gps_off_text))
//                            .setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
//                            });
//                    alert.show();
                }
            }
        }

        ForegroundSpectrum.setSuffix(context.getString(R.string.hist_suffix));
        BackgroundSpectrum.setSuffix(context.getString(R.string.background_suffix));
        usbDevice = new AtomSpectraSerial(context);
        sp = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        sp.registerOnSharedPreferenceChangeListener(onSharedPreferenceChangeListener);
        basic_window = -1 + 8 * sp.getInt(Constants.CONFIG.CONF_GOLAY_WINDOW, Constants.DEFAULT_GOLAY_WINDOW);

        soundBufferSize = AudioTrack.getMinBufferSize(44100, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT) / 4;
        soundPeriodCount = soundBufferSize / 4410;    //4410 - number of samples in 100ms buffer
        int fraction = soundBufferSize % 4410;
        if (fraction != 0) {
            soundPeriodCount++;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            soundBufferSize = soundPeriodCount * 4410;
            soundBuffer = new float[soundBufferSize * 2];
            try {
                soundTrack = new AudioTrack.Builder().
                        setAudioAttributes(new AudioAttributes.Builder().
                                setUsage(AudioAttributes.USAGE_ALARM).
                                setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).
                                setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED).
                                build()).
                        setAudioFormat(new AudioFormat.Builder().
                                setSampleRate(44100).
                                setEncoding(AudioFormat.ENCODING_PCM_FLOAT).
                                setChannelMask(AudioFormat.CHANNEL_OUT_MONO).
                                build()).
                        setTransferMode(AudioTrack.MODE_STREAM).
                        setBufferSizeInBytes(soundBufferSize * 4).
                        build();
            } catch (Exception ignored) {
                soundTrack = null;
            }
            if (soundTrack != null && soundTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                soundTrack.release();
                soundTrack = null;
                Toast.makeText(context, getText(R.string.no_audio_output_available), Toast.LENGTH_LONG).show();
            }
        }

        loadSettings();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(broadcastReceiver,
                    makeAtomSpectraServiceIntentFilter(), Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(broadcastReceiver,
                    makeAtomSpectraServiceIntentFilter());
        }

        BufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT) * 2; //read two buffers at a time to reduce time consumption
        AudioBytes = new byte[BufferSize]; //Array containing the audio data bytes
        AudioData = new int[BufferSize / 2]; //Array containing the audio samples
        AudioSource = AUDIO_SOURCE_VOICE;

        try {
            //Some devices says they have this ability by it doesn't work. Switched off for a delay
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                if (manager != null && manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) != null)
                    AudioSource = ((sp.getInt(Constants.CONFIG.CONF_AUDIO_SOURCE, SET_AUDIO_RAW)) == SET_AUDIO_RAW) ? AUDIO_SOURCE_RAW : AUDIO_SOURCE_VOICE;
            }
        } catch (IllegalArgumentException e) {
            Toast.makeText(context, getString(R.string.no_audio_available), Toast.LENGTH_LONG).show();
        }
        isRecording = true;
        audioTaskTimeout = 1000L * BufferSize / 2 / SAMPLE_RATE;
        audioTimer.scheduleAtFixedRate(captureAudioTask, 0, audioTaskTimeout);
        serialTimer.scheduleAtFixedRate(serialTask, 0, 300);
        autosaveTimer.scheduleAtFixedRate(autosaveTask, 0, 1000);
        sendDataTimer = new Timer();
        sendDataTimer.scheduleAtFixedRate(sendData, 0, 100);
        timerExec.scheduleAtFixedRate(periodicTask, 0, Constants.UPDATE_PERIOD);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (manager != null)
                manager.registerAudioDeviceCallback(audioChanged, null);
        }
        sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_MENU).setPackage(Constants.PACKAGE_NAME));
        Log.d(TAG, "recording START");
    }

    private void outputSoundInit() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (outputSound && soundTrack != null) {
                AudioDeviceInfo deviceOut = getDeviceOutput(context, outputSoundID, outputSoundName, true);
                if (deviceOut == null) {
                    deviceOut = getDeviceOutput(context, -1, null, false);
                }
                if (deviceOut != null) {
                    synchronized (soundSync) {
                        outputSoundID = deviceOut.getId();
                        outputSoundName = deviceOut.getProductName().toString();
                        soundPeriodNum = 0;
                        soundTrack.setPreferredDevice(deviceOut);
                        soundTrack.play();
                    }
                } else {
                    soundTrack.pause();
                    soundTrack.flush();
//                    soundPeriodCount = 10;
                }
            } else {
                cpsBaseLevel = 0;
                cpsBaseSignal = 0;
                cpsCurrentLevel = 0;
//                cpsPrevLevel = 0;
                if (soundTrack != null) {
                    synchronized (soundSync) {
                        soundTrack.pause();
                        soundTrack.flush();
                        soundBufferSwitch = 0;
                    }
                }
            }
        }
    }

    public static final String[] audioDeviceNames = new String[] {
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
//                        boolean isFound = false;
//                        for (int i = 0; i < device.getEncodings().length; i++) {
//                            if (device.getEncodings()[i] == AudioFormat.ENCODING_PCM_FLOAT)
//                                isFound = true;
//                        }
//                        if (!isFound)
//                            continue;
//                        isFound = false;
//                        for (int i = 0; i < device.getSampleRates().length; i++) {
//                            if (device.getSampleRates()[i] == 44100)
//                                isFound = true;
//                        }
//                        if (!isFound && (device.getSampleRates().length > 0))
//                            continue;
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

    public static double getCpsBaseLevel() {
        return cpsBaseLevel;
    }

    public static double getCpsBaseSignal() {
        return cpsBaseSignal;
    }

    private static IntentFilter makeAtomSpectraServiceIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Constants.ACTION.ACTION_STOP_FOREGROUND);
        intentFilter.addAction(Constants.ACTION.ACTION_START_FOREGROUND);
        intentFilter.addAction(Constants.ACTION.ACTION_UPDATE_NOTIFICATION);
        intentFilter.addAction(Constants.ACTION.ACTION_SOURCE_CHANGED);
        intentFilter.addAction(Constants.ACTION.ACTION_HAS_DATA);
        intentFilter.addAction(Constants.ACTION.ACTION_HAS_ANSWER);
        intentFilter.addAction(Constants.ACTION.ACTION_FREEZE_DATA);
        intentFilter.addAction(Constants.ACTION.ACTION_CLEAR_SPECTRUM);
        intentFilter.addAction(Constants.ACTION.ACTION_CLEAR_IMPULSE);
        intentFilter.addAction(Constants.ACTION.ACTION_SEND_USB_COMMAND);
        intentFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        intentFilter.addAction(Constants.ACTION.ACTION_UPDATE_GPS);
        intentFilter.addAction(Intent.ACTION_BATTERY_LOW);
        intentFilter.addAction(Constants.ACTION.ACTION_CHECK_GPS_AVAILABILITY);
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
                NotificationManagerCompat.from(context).notify(FOREGROUND_PROCESS_ID, createNewServiceNotification());
                return;
            }
            if (Constants.ACTION.ACTION_CLEAR_SPECTRUM.equals(action)) {
                setFreeze(true);
                DeleteSpc();
                sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_MENU).setPackage(Constants.PACKAGE_NAME));
                sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_CALIBRATION).putExtra(Constants.ACTION_PARAMETERS.UPDATE_USB_CALIBRATION, false).setPackage(Constants.PACKAGE_NAME));
                return;
            }
            if (Constants.ACTION.ACTION_CLEAR_IMPULSE.equals(action)) {
                Arrays.fill(referencePulse, 0);
                return;
            }
            if (Constants.ACTION.ACTION_SEND_USB_COMMAND.equals(action)) {
                synchronized (inputSync) {
                    if (inputType != INPUT_SERIAL || usbDevice == null || !usbDevice.isOpened())
                        return;
                }
                String command = intent.getStringExtra(Constants.ACTION_PARAMETERS.USB_COMMAND_DATA);
                String id = intent.getStringExtra(Constants.ACTION_PARAMETERS.USB_COMMAND_ID);
                if (command != null && id != null)
                    usbDevice.sendTextCommand(command, id);
                return;
            }
            if (Constants.ACTION.ACTION_FREEZE_DATA.equals(action)) {
                setFreeze(intent.getBooleanExtra(AtomSpectraSerial.EXTRA_DATA_TYPE, true));
                if (freeze_update_data) {
                    ForegroundSpectrum.updateComments();
                }
                return;
            }
            if (Constants.ACTION.ACTION_STOP_FOREGROUND.equals(action)) {
                Log.i(TAG, "Received Stop Foreground Intent");
                canOpenAudio = false;
                isRecording = false;
                if (soundTrack != null) {
                    synchronized (soundSync) {
                        soundTrack.pause();
                        soundTrack.flush();
                        soundTrack.release();
                        soundTrack = null;
                        soundBuffer = null;
                        soundBufferSwitch = 0;
                    }
                }
                releaseAR();
                Log.d(TAG, "recording Stop");
                Stop();
                return;
            }
            if (Constants.ACTION.ACTION_START_FOREGROUND.equals(action)) {
                Log.d(TAG, "Received Start Foreground Intent");
                return;
            }
            if (Intent.ACTION_BATTERY_LOW.equals(action) && AtomSpectraService.ForegroundSpectrum.isChanged()) {
                saveHist();
            }
            if (Constants.ACTION.ACTION_CHECK_GPS_AVAILABILITY.equals(action)) {
                checkGPS();
            }
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                synchronized (inputSync) {
                    if (inputType != INPUT_AUDIO) {
                        freeze_update_data = true;
                        Toast.makeText(context, getString(R.string.action_usb_detached), Toast.LENGTH_SHORT).show();
                        inputSelectNext = INPUT_AUDIO;
                    }
//                    inputLastState = INPUT_AUDIO;
                }
                usbDevice.Close();
                sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_MENU).setPackage(Constants.PACKAGE_NAME));
                NotificationManagerCompat.from(context).notify(FOREGROUND_PROCESS_ID, createNewServiceNotification());
                return;
            }
            if (Constants.ACTION.ACTION_UPDATE_GPS.equals(action)) {
                if (!freeze_update_data) {
                    ForegroundSpectrum.setLocation(Locator.getLocation()).updateComments();
                }
            }
            if (Constants.ACTION.ACTION_SOURCE_CHANGED.equals(action)) {
                if (EXTRA_SOURCE_USB.equals(intent.getStringExtra(EXTRA_SOURCE))) {
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
                            //Toast.makeText(context, "Asking permissions", Toast.LENGTH_LONG).show();
                            PendingIntent pi = PendingIntent.getBroadcast(context, 0, new Intent(Constants.ACTION.ACTION_GET_USB_PERMISSION), mutabilityFlag);
                            usbManager.requestPermission(device, pi);
                        } else {
//                            long temp_time = ForegroundSpectrum.getSpectrumTime();
                            if (usbDevice.Open(device)) {
                                usbDevice.sendTextCommand("-inf", SERVICE_VERSION_ID);
                                usbDevice.sendTextCommand("-mode 0", SERVICE_ID);
//                                if (temp_time == 0)
//                                    sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_CALIBRATION).putExtra(Constants.ACTION_PARAMETERS.UPDATE_USB_CALIBRATION, true));
                                usbDevice.sendTextCommand("-sta", SERVICE_ID);
//                                sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_MENU).setPackage(Constants.PACKAGE_NAME));
                                ForegroundSaveSpectrum.Clone(ForegroundSpectrum);
                                synchronized (inputSync) {
                                    if (inputType != INPUT_SERIAL)
                                        Toast.makeText(context, getString(R.string.action_usb_attached), Toast.LENGTH_SHORT).show();
//                                    inputLastState = INPUT_SERIAL;
                                    inputSelectNext = INPUT_SERIAL;
                                    freeze_update_data = true;
                                }
                            } else {
                                usbDevice.Close();
                                Toast.makeText(context, getString(R.string.action_usb_no_access), Toast.LENGTH_LONG).show();
                                synchronized (inputSync) {
                                    inputSelectNext = INPUT_AUDIO;
                                }
                            }
                        }
                    }
                }
                if (EXTRA_SOURCE_AUDIO.equals(intent.getStringExtra(EXTRA_SOURCE))) {
                    synchronized (inputSync) {
                        if (inputType != INPUT_AUDIO) {
                            freeze_update_data = true;
                            Toast.makeText(context, getString(R.string.action_usb_detached), Toast.LENGTH_SHORT).show();
                            inputSelectNext = INPUT_AUDIO;
                        }
//                        inputLastState = INPUT_AUDIO;
                    }
                }
                sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_MENU).setPackage(Constants.PACKAGE_NAME));
                NotificationManagerCompat.from(context).notify(FOREGROUND_PROCESS_ID, createNewServiceNotification());
                return;
            }
            if (Constants.ACTION.ACTION_HAS_DATA.equals(action)) {
                switch (intent.getIntExtra(AtomSpectraSerial.EXTRA_DATA_TYPE, AtomSpectraSerial.CODE_NONE)) {
                    case AtomSpectraSerial.CODE_DATA:
                        long[] new_histogram = intent.getLongArrayExtra(EXTRA_DATA_ARRAY_LONG_COUNTS);
                        cps = intent.getIntExtra(EXTRA_DATA_INT_CPS, 0);
                        long new_time = intent.getIntExtra(EXTRA_DATA_TOTAL_TIME, 1);
                        if (new_histogram != null) {
                            long[] histogram_all_backup = ForegroundSaveSpectrum.getDataArray();
                            if (new_time > (ForegroundSpectrum.getSpectrumTime() - ForegroundSaveSpectrum.getSpectrumTime()) / 1000.0 * Constants.UPDATE_PERIOD) {
                                long count = 0, count_interval = 0;
                                double count_e = 0;
                                long[] histogram_all = ForegroundSpectrum.getDataArray();
                                for (int i = 0; i < StrictMath.min(Constants.NUM_HIST_POINTS, new_histogram.length); i++) {
                                    long value = (new_histogram[i] + histogram_all_backup[i] - histogram_all[i]);
                                    count += value;
                                    if (i >= leftChannelInterval && i <= rightChannelInterval)
                                        count_interval += value;
                                    count_e += value * getEnergyPulse(ForegroundSpectrum.getSpectrumCalibration().toEnergy(i));
                                }
                                cpsInterval = (int) count_interval;
                                doseRateValue = doseRateSearch(count, count_interval, count_e, new_time - (ForegroundSpectrum.getSpectrumTime() - ForegroundSaveSpectrum.getSpectrumTime()) / 1000.0 * Constants.UPDATE_PERIOD, AtomSpectra.XCalibrated);
                            }
                            if (!freeze_update_data) {
//                                if (Locator != null && addGPS)
//                                    ForegroundSpectrum.setLocationOnly(Locator.getLocation());
                                ForegroundSpectrum.setSpectrum(new_histogram).setRealSpectrumTime(new_time).addSpectrum(ForegroundSaveSpectrum).updateComments();
                            }
                        } else {
                            cpsInterval = 0;
                        }
                        break;

                    case AtomSpectraSerial.CODE_SCOPE:
                        //it is useless for Nano Pro
//                        long[] scope = intent.getLongArrayExtra(EXTRA_DATA_SCOPE_COUNTS);
//                        if(scope != null) {
//                            if (scale_factor == Constants.SCALE_COUNT_MODE) {
//                                Arrays.fill(x, 0);
//                                System.arraycopy(scope, 0, x, 0, StrictMath.min(scope.length, x.length));
//                            } else if (scale_factor == Constants.SCALE_IMPULSE_MODE) {
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
            if (Constants.ACTION.ACTION_HAS_ANSWER.equals(action)) {
                //We get answer from device
//                Toast.makeText(context, "Full got answer: " + intent.getStringExtra(AtomSpectraSerial.EXTRA_DATA_PACKET), Toast.LENGTH_LONG).show();
                if (SERVICE_ID.equals(intent.getStringExtra(AtomSpectraSerial.EXTRA_ID))) {
//                    Toast.makeText(context, "Service got answer: " + intent.getStringExtra(AtomSpectraSerial.EXTRA_DATA_PACKET), Toast.LENGTH_LONG).show();
                    if (AtomSpectraSerial.COMMAND_RESULT_ERR.equals(intent.getStringExtra(AtomSpectraSerial.EXTRA_RESULT))) {
                        //nothing to do
                    }else {
                        if ("-sta".equals(intent.getStringExtra(AtomSpectraSerial.EXTRA_COMMAND))) {
                            freeze_update_data = false;
                            sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_MENU).setPackage(Constants.PACKAGE_NAME));
                        } else if ("-sto".equals(intent.getStringExtra(AtomSpectraSerial.EXTRA_COMMAND))) {
                            freeze_update_data = true;
                            sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_MENU).setPackage(Constants.PACKAGE_NAME));
                        }
                    }
                }
                if (SERVICE_VERSION_ID.equals(intent.getStringExtra(AtomSpectraSerial.EXTRA_ID))) {
                    if (AtomSpectraSerial.COMMAND_RESULT_OK.equals(intent.getStringExtra(AtomSpectraSerial.EXTRA_RESULT))) {
                        String data = intent.getStringExtra(AtomSpectraSerial.EXTRA_RESULT);
                        String version;
                        if (data != null) {
                            version = AtomSpectraSerial.getParameter(data, "VERSION");
                            if (version != null) {
                                try {
                                    if (Integer.decode(version) < Constants.USB_DEVICE_MINIMAL_VERSION)
                                        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(getApplicationContext(), getString(R.string.usb_below_minimal_version, Constants.USB_DEVICE_MINIMAL_VERSION), Toast.LENGTH_SHORT).show());
                                } catch (Exception e) {
                                    new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(getApplicationContext(), "Device version read error", Toast.LENGTH_SHORT).show());
                                }
                            } else {
                                new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(getApplicationContext(), "Device version in unknown", Toast.LENGTH_SHORT).show());
                            }
                        }
                    }
                }
            }
        }
    };

    public void notify_cancel_all() {
//        Context context = getApplicationContext();
        if (context != null) {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null)
                nm.cancelAll();
        }
    }

    public void Stop() {
        notify_cancel_all();
//        stopForeground(true);
        canOpenAudio = false;
        isRecording = false;
        isStarted = false;

        Log.d(TAG, "recording Stop");
        releaseAR();
//        if (timerTask_started)
            timerExec.cancel();
        if (sendDataTimer != null)
            sendDataTimer.cancel();
        audioTimer.cancel();
        serialTimer.cancel();
        autosaveTimer.cancel();
        usbDevice.Close();
        usbDevice.Destroy();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
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
        sendBroadcast(new Intent(Constants.ACTION.ACTION_CLOSE_SETTINGS).setPackage(Constants.PACKAGE_NAME));
        sendBroadcast(new Intent(Constants.ACTION.ACTION_CLOSE_SEARCH).setPackage(Constants.PACKAGE_NAME));
        sendBroadcast(new Intent(Constants.ACTION.ACTION_CLOSE_ISOTOPES).setPackage(Constants.PACKAGE_NAME));
        sendBroadcast(new Intent(Constants.ACTION.ACTION_CLOSE_HELP).setPackage(Constants.PACKAGE_NAME));
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
        synchronized (inputSync) {
            if (inputType == INPUT_SERIAL) {
                usbDevice.ClearHistogram();
            }
        }
        synchronized (autosaveTimer) {
            autosaveSpectrum = null;
            try {
                autosavePair.first.close();
            } catch (Exception e) {
                //
            }
            autosavePair = null;
        }
        Arrays.fill(histogram, 0);
        Arrays.fill(referencePulse, 0);
        ForegroundSpectrum.initSpectrumData().setSuffix(getString(R.string.hist_suffix));
        ForegroundSaveSpectrum.initSpectrumData();
        Arrays.fill(histogram_all_delta, 0);
        Arrays.fill(histogram_all_prev_time, 0);
        total_pulses = 0;
        synchronized (windowCps) {
            windowCps.clear();
            windowEnergy.clear();
            deltaTime.clear();
            window.clear();
        }
        synchronized (doseHistory) {
            doseHistory.clear();
            doseEnergyHistory.clear();
        }
        timer = 0;
        doseRateTimer = 0;
        synchronized (countTimeSync) {
            countTime = 10;
        }
        synchronized (soundSync) {
            cpsBaseLevel = 0;
            cpsBaseSignal = 0;
            cpsCurrentLevel = 0;
        }
    }

    public static boolean getFreeze() {
        return freeze_update_data;
    }

    public static void freeze (boolean freeze) {
        freeze_update_data = freeze;
    }
    private void setFreeze(boolean freeze) {
        freeze_update_data = freeze;
        if (freeze) {
            releaseAR();
        }
        synchronized (inputSync) {
            if (inputType == INPUT_SERIAL) {
                if (freeze_update_data)
                    usbDevice.sendTextCommand("-sto", SERVICE_ID);
                else
                    usbDevice.sendTextCommand("-sta", SERVICE_ID);
            }
        }
        sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_MENU).setPackage(Constants.PACKAGE_NAME));
        NotificationManagerCompat.from(context).notify(FOREGROUND_PROCESS_ID, createNewServiceNotification());
    }

    final static int SEARCH_WINDOW_SIZE = 1024;
    private static final LinkedList<Double> windowCps = new LinkedList<>();
    private static final LinkedList<Double> window = new LinkedList<>();
    private static final LinkedList<Double> windowEnergy = new LinkedList<>();
    private static final LinkedList<Double> doseHistory = new LinkedList<>();
    private static final LinkedList<Double> doseEnergyHistory = new LinkedList<>();
    private static final LinkedList<Double> deltaTime = new LinkedList<>();
    private static double exp_T = 0.1;

    private static double doseRateSearch(double pulses, double pulses_interval, double pulses_e, double time, boolean useEnergy) {

        synchronized (windowCps) {
            deltaTime.addLast(time);
            if (deltaTime.size() > SEARCH_WINDOW_SIZE) {
                deltaTime.removeFirst();
            }
            windowEnergy.addLast(pulses_e);
            if (windowEnergy.size() > SEARCH_WINDOW_SIZE) {
                windowEnergy.removeFirst();
            }
            windowCps.addLast(pulses);
            if (windowCps.size() > SEARCH_WINDOW_SIZE) {
                windowCps.removeFirst();
            }
            window.addLast(pulses_interval);
            if (window.size() > SEARCH_WINDOW_SIZE) {
                window.removeFirst();
            }
        }

        int sens = 0;
        double min_period = 1;   //in seconds
        switch (SearchFSM) {
            case 0:
                sens = SEARCH_FAST;
                min_period = 0.1;
                break;
            case 1:
                sens = SEARCH_MEDIUM;
                min_period = 0.5;
                break;
            case 2:
                sens = SEARCH_SLOW;
                min_period = 1.0;
                break;
        }
        if (pulses <= 10) {
            exp_T = StrictMath.max(exp_T / (1.0 + time / 10.0), 0.001);
        } else if (pulses >= 100) {
            exp_T = StrictMath.min(exp_T * (1.0 + time / 10.0), 0.1);
        } else {
            if (exp_T > (pulses * pulses / 100000.0)) exp_T /= (1.0 + time / 10.0);
            if (exp_T < (pulses * pulses / 100000.0)) exp_T *= (1.0 + time / 10.0);
        }
        double sum = 0;
        double clear_sum = 0;
        double totalTime = 0;
        double sumEnergy = 0.0;
        double accumulated = 0.0;
        double weight;
        synchronized (windowCps) {
            int start = windowCps.size() > 0 ? windowCps.size() - 1 : 0;
            for (int i = start; i >= 0; i--) {
                clear_sum += windowCps.get(i);                      //total number of pulses
                weight = StrictMath.exp(-exp_T * totalTime) / deltaTime.get(i);
                sum += windowCps.get(i) * weight;
                sumEnergy += windowEnergy.get(i) * weight;
                accumulated += weight;
                totalTime += deltaTime.get(i);
                if ((clear_sum >= sens) && (totalTime >= min_period))
                    break;
            }
        }
        double weightSensG = 7 * 0.88 * 1.0e-2 * 3600 / SensG;
        double hist_dose_e = sumEnergy / accumulated * 4.3 * weightSensG;
        double hist_dose = StrictMath.max(0.0, ((sum / accumulated) - backgroundCount) * weightSensG);
        synchronized (doseHistory) {
            doseHistory.addLast(hist_dose);
            if (doseHistory.size() > SEARCH_WINDOW_SIZE) {
                doseHistory.removeFirst();
            }
            doseEnergyHistory.addLast(hist_dose_e);
            if (doseEnergyHistory.size() > SEARCH_WINDOW_SIZE) {
                doseEnergyHistory.removeFirst();
            }
        }
        if (useEnergy) {
            return hist_dose_e;    //calculate energy compensated dose rate
        } else {
            return hist_dose;
        }
    }

    //for CsI 10x10x30 crystal
    private static double[] EnergyList = new double[]{
            0.0,
            100.0,
            200.0,
            300.0,
            400.0,
            500.0,
            600.0,
            700.0,
            800.0,
            900.0,
            1000.0,
            1100.0,
            1200.0,
            1300.0,
            1400.0,
            1500.0,
            1600.0,
            1700.0,
            1800.0,
            1900.0,
            2000.0,
            2100.0,
            2200.0,
            2300.0,
            2400.0,
            2500.0,
            2600.0,
            2700.0,
            2800.0,
            2900.0
    }; //energy list in keV
    public static final double[] EnergyListDefault = new double[]{
            0.0,
            100.0,
            200.0,
            300.0,
            400.0,
            500.0,
            600.0,
            700.0,
            800.0,
            900.0,
            1000.0,
            1100.0,
            1200.0,
            1300.0,
            1400.0,
            1500.0,
            1600.0,
            1700.0,
            1800.0,
            1900.0,
            2000.0,
            2100.0,
            2200.0,
            2300.0,
            2400.0,
            2500.0,
            2600.0,
            2700.0,
            2800.0,
            2900.0
    }; //energy list in keV
    private static int EnergyListCount = EnergyListDefault.length;
//    private static final double[] Sensitivity = new double[EnergyListCount];
    private static double[] ETomSv = new double[]{
            0.0,
            0.0171979600731997,
            0.0480184835146140,
            0.1435917027218390,
            0.3282911602228690,
            0.5905998429192040,
            0.8920944634987190,
            1.1745696899418600,
            1.4115205995972900,
            1.5822683732872000,
            1.7193245130080400,
            1.8300925688571300,
            1.9598962219392600,
            2.1527699267401500,
            2.3779144044044100,
            2.5751926000784900,
            2.6687959009367600,
            2.6614382236336400,
            2.6501896868633800,
            2.7655103687783900,
            2.9332404784454300,
            3.1635329688032900,
            3.2657941250968600,
            3.3349624711773100,
            3.0682650024761000,
            2.8705814847550800,
            2.9826045411205400,
            3.5934184594279600,
            4.2014869361989400,
            5.2692760307823300
    }; //photon energy to Cs-137 energy
    public static final double[] ETomSvDefault = new double[]{
            0.0,
            0.0171979600731997,
            0.0480184835146140,
            0.1435917027218390,
            0.3282911602228690,
            0.5905998429192040,
            0.8920944634987190,
            1.1745696899418600,
            1.4115205995972900,
            1.5822683732872000,
            1.7193245130080400,
            1.8300925688571300,
            1.9598962219392600,
            2.1527699267401500,
            2.3779144044044100,
            2.5751926000784900,
            2.6687959009367600,
            2.6614382236336400,
            2.6501896868633800,
            2.7655103687783900,
            2.9332404784454300,
            3.1635329688032900,
            3.2657941250968600,
            3.3349624711773100,
            3.0682650024761000,
            2.8705814847550800,
            2.9826045411205400,
            3.5934184594279600,
            4.2014869361989400,
            5.2692760307823300
    }; //photon energy to Cs-137 energy

    private static double getEnergyPulse(double energy) {
        if (energy >= EnergyList[EnergyList.length - 1])
            return ETomSv[EnergyListCount - 1];
        if (energy <= EnergyList[0])
            return ETomSv[0];
        int energy_pos = 1;
        // Calculation using energy list
        while ((energy_pos < (EnergyList.length - 1)) && (energy > EnergyList[energy_pos]))
            energy_pos++;
        return StrictMath.max(0.0, (energy - EnergyList[energy_pos - 1]) / (EnergyList[energy_pos] - EnergyList[energy_pos - 1]) * (ETomSv[energy_pos] - ETomSv[energy_pos - 1]) + ETomSv[energy_pos - 1]);
    }

    //this function is used to release sound input
    private void releaseAR () {
        synchronized (ARLock) {
            if (AR != null) {
                AR.stop();
                AR.release();
                AR = null;
            }
        }
    }

    //this task is used to read from audio input and update information
    private final Timer audioTimer = new Timer();
    int audioZeroDataCount = 0;
    final int audioZeroDataMaxCount = 5;
    private final TimerTask captureAudioTask = new TimerTask() {
        @Override
        public void run() {
            synchronized (inputSync) {
                if (inputSelectNext == INPUT_AUDIO) {
                    inputType = inputSelectNext;
                    inputSelectNext = INPUT_NONE;
                    releaseAR();
//                    new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(getApplicationContext(), "test3: "+ForegroundSpectrum.getSpectrumTime(), Toast.LENGTH_SHORT).show());
                    NotificationManagerCompat.from(context).notify(FOREGROUND_PROCESS_ID, createNewServiceNotification());
                    if (ForegroundSpectrum.getSpectrumTime() == 0) {
//                        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(getApplicationContext(), "test4", Toast.LENGTH_SHORT).show());
                        sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_CALIBRATION).putExtra(Constants.ACTION_PARAMETERS.UPDATE_USB_CALIBRATION, false).setPackage(Constants.PACKAGE_NAME));
                    }
                }
                if (inputType != INPUT_AUDIO)
                    return;
            }
            if(freeze_update_data) {
                return;
            }
            if (SetAudioSource == SET_AUDIO_VOICE) {
                AudioSource = AUDIO_SOURCE_VOICE;
                releaseAR();
                SetAudioSource = SET_AUDIO_OK;
            }
            if (SetAudioSource == SET_AUDIO_RAW && context != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
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
                        if (inputSound) {
                            AudioDeviceInfo device = getDeviceInput(context, inputSoundID, inputSoundName, true);
                            if (device != null) {
                                AR.setPreferredDevice(device);
                                ARShowAbsentMessage = true;
                            } else {
                                if (ARShowAbsentMessage) {
                                    new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(getApplicationContext(), getString(R.string.input_sound_absent), Toast.LENGTH_SHORT).show());
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
//                            if (AutomaticGainControl.isAvailable()) {
//                                AutomaticGainControl.create(AR.getAudioSessionId()).setEnabled(false);
//                            }
                            AR.startRecording();
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

            //First we will pass the 2 bytes into one sample
            //It's an extra loop but avoids repeating the same sum many times later during the filter
//            int Mask = ((-1) << (Constants.ADC_MAX - adc_effective_bits));
            int HighBitsShift = Math.max(0, 15 - Constants.ADC_MAX);
            for (int i = 0, r = 0; i < AudioBytesRead - 2; i += 2, r++) {// Before the 8 we had the end of the previous data
                if (AudioBytes[i] < 0)
                    AudioData[r] = AudioBytes[i] + 256;
                else
                    AudioData[r] = AudioBytes[i];
                AudioData[r] = AudioData[r] + 256 * AudioBytes[i + 1];//+32768;

                if (inversion) AudioData[r] = -AudioData[r];
//                    AudioData[r] = Constants.MinMax(AudioData[r] >> (Math.max(0, 15 - Constants.ADC_MAX)), 0, Constants.NUM_HIST_POINTS - 1);
                AudioData[r] = (AudioData[r] >> HighBitsShift);// & Mask;
//                    AudioData[r] = ((AudioData[r] >> (Math.max(0, 15 - Constants.ADC_MAX))) >> (Constants.ADC_MAX - adc_effective_bits)) << (Constants.ADC_MAX - adc_effective_bits);
//                    AudioData[r] = Constants.MinMax(((AudioData[r] >> (Math.max(0, 15 - Constants.ADC_MAX))) >> (Constants.ADC_MAX - adc_effective_bits)) << (Constants.ADC_MAX - adc_effective_bits), 0, Constants.NUM_HIST_POINTS - 1);
//                    AudioData[r] = (AudioData[r] >> (16 - adc_effective_bits)) << (16 - adc_effective_bits);
            }

//-----------------LPF started--------------------------------------------------
             /*
             float f_cutting = (float) 15000.0;
             AudioData[0]=(int)(AudioData[BufferSize/2-1]*(SAMPLE_RATE/(SAMPLE_RATE+f_cutting))
            		 +AudioData[0]*(f_cutting/(SAMPLE_RATE+f_cutting)));
             for (int k=1;k<BufferSize/2;k++)
             {
            	 AudioData[k]=(int)(AudioData[k-1]*(SAMPLE_RATE/(SAMPLE_RATE+f_cutting))+AudioData[k]*(f_cutting/(SAMPLE_RATE+f_cutting)));
             }
*/
            //for (int k=0;k<BufferSize/2;k++) AudioData[k]=((65535-AudioData[k])>>4)<<3;
            //for (int k=0;k<BufferSize/2;k++) AudioData[k]=(AudioData[k]>>4)<<3;

//-----------------LPF finished-------------------------------------------------


//-----------------DPP started--------------------------------------------------

            int initial_amp = 0, initial_time = 0;
            float corrector;
            double energy_pulse;

            for (int i = 1; i < AudioBytesRead / 2 - 2; i++) {
                if ((audioCaptureOldTimer + Constants.UPDATE_PERIOD) < (audioCaptureTimer + (i * 1000 / SAMPLE_RATE))) {
                    audioCaptureOldTimer += Constants.UPDATE_PERIOD;
                    cpsPos = cpsPos < (1000 / Constants.UPDATE_PERIOD - 1) ? (cpsPos + 1) : 0;
                    cpsArray[cpsPos] = total_pulses_cps;
                    cpsArrayInterval[cpsPos] = total_pulses_cps_interval;
                    cpsArrayEnergy[cpsPos] = total_energy_cps;
                    total_pulses_cps = 0;
                    total_pulses_cps_interval = 0;
                    total_energy_cps = 0.0;
                }

                if (((AudioData[i] - AudioData[i - 1]) <= 0) && ((AudioData[i + 1] - AudioData[i]) > 0)) {
                    initial_amp = AudioData[i];
                    initial_time = i;
                }
                if (((AudioData[i] - AudioData[i - 1]) >= 0) && ((AudioData[i + 1] - AudioData[i]) < 0)) {
                    if (((i - initial_time) >= frontCountsMin) && ((i - initial_time) <= frontCountsMax)) {

                        //if (((initial_time-(i-initial_time))>=0)&&(initial_time>=0))
                        if (i > frontCountsMax * 2)
                            corrector = AudioData[initial_time - (i - initial_time)] - AudioData[initial_time];
                        else corrector = 0;
                        if (!pileup) corrector = 0;
                        int amp = (AudioData[i] - initial_amp + (int) corrector);//>>4;
//                            amp = Constants.MinMax(amp >> Math.max(0, 15 - Constants.ADC_MAX), 0, Constants.NUM_HIST_POINTS - 1);
                        if ((amp >= histogramMinChannel) && (amp < Constants.NUM_HIST_POINTS)) {

//                                int amp = ((AudioData[i] - initial_amp + (int) corrector));

//                                if ((amp >= 0) && (amp < Constants.NUM_HIST_POINTS)) {
                            energy_pulse = getEnergyPulse(ForegroundSpectrum.getSpectrumCalibration().toEnergy(amp));
//                            if (!freeze_update_data) {
                                if (ForegroundSpectrum.incSpectrumValue(amp))
                                    ForegroundSpectrum.updateComments();
                                total_pulses++;
//                            }

//-----------------DPP finished-------------------------------------------------

                            total_pulses_cps++;
                            if ((amp >= leftChannelInterval) && (amp <= rightChannelInterval))
                                total_pulses_cps_interval++;
                            total_energy_cps += energy_pulse;
//                                }

                            if ((i > 128) && (i < (1024 - 128)) && (i < ((AudioBytesRead - 128) / 2)))
                                for (int j = -128; j < 128; j++)
                                    referencePulse[j + 128] += AudioData[i + j];
                        }

                    }
                }
            }

            audioCaptureTimer += audioTaskTimeout;
        }
    };

    public static void requestUpdateGraph() {
        synchronized (countTimeSync) {
            countTime = 10;
        }
    }

    private static int countTime = 0;
    private static int countUpdateIsotopes = 0;
    private static final Integer countTimeSync = 1;

    private Timer sendDataTimer = null;
    private final TimerTask sendData = new TimerTask() {
        @Override
        public void run() {
            boolean toUpdateIsotopes = false;
            synchronized (countTimeSync) {
                countTime++;
                countUpdateIsotopes++;
                if (countUpdateIsotopes >= 10) {
                    countUpdateIsotopes = 0;
                    toUpdateIsotopes = true;
                }
            }
            if (toUpdateIsotopes) {
                if (AtomSpectraIsotopes.autoUpdateIsotopes && !freeze_update_data && AtomSpectraIsotopes.showFoundIsotopes) {
                    AtomSpectraFindIsotope.updateFoundIsotopes();
                    sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_ISOTOPE_LIST).setPackage(Constants.PACKAGE_NAME));
                }
            }
            deltaTimeAccumulator++;
            if (deltaTimeAccumulator >= (delta_time * 1000 / Constants.UPDATE_PERIOD)) {
                deltaTimeAccumulator = 0;
                long[] temp = Arrays.copyOf(ForegroundSpectrum.getDataArray(), ForegroundSpectrum.getDataArray().length);
                for (int i = 0; i < temp.length; i++) {
                    histogram_all_delta[i] = temp[i] - histogram_all_prev_time[i];
                    histogram_all_prev_time[i] = temp[i];
                }
            }
            synchronized (countTimeSync) {
                if (countTime < periodUpdate) {
                    return;
                }
                countTime = 0;
            }
            final Intent intent = new Intent(ACTION_DATA_AVAILABLE).setPackage(Constants.PACKAGE_NAME);
            Bundle mBundle = new Bundle();
            mBundle.putDouble(EXTRA_DATA_DOSERATE_SEARCH, doseRateValue);
            mBundle.putLong(EXTRA_DATA_LONG_COUNTS, total_pulses);
            mBundle.putInt(EXTRA_DATA_INT_CPS, cps);
            mBundle.putInt(EXTRA_DATA_INT_CPS_INTERVAL, cpsInterval);
            mBundle.putDouble(EXTRA_DATA_TOTAL_TIME, ForegroundSpectrum.getRealSpectrumTime());

            mBundle.putInt(EXTRA_DATA_ARRAY_INT_SOUND_LENGTH, BufferSize / 2);
            mBundle.putIntArray(EXTRA_DATA_ARRAY_INT_SOUND, AudioData);

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
                    } else if (showDelta) {
                        if (isCalibrated) {
                            double[] histogram_e_all = ForegroundSpectrum.getSpectrumCalibration().toEnergy(makeSmooth(histogram_all_delta, AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration()), adc_effective_bits, lastCalibrationChannel);
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
                            double[] histogram_temp = ForegroundSpectrum.getSpectrumCalibration().linearChannel(makeSmooth(histogram_all_delta, AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration()), adc_effective_bits);
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

                    mBundle.putDoubleArray(EXTRA_DATA_ARRAY_LONG_COUNTS, histogram);
                    mBundle.putDoubleArray(EXTRA_DATA_ARRAY_BACK_COUNTS, background_histogram);
                    mBundle.putBoolean(EXTRA_DATA_SHOW_BACK_COUNTS, background_show && (!BackgroundSpectrum.isEmpty()));
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
                    }

                    mBundle.putDoubleArray(EXTRA_DATA_ARRAY_LONG_COUNTS, histogram);
                    mBundle.putDoubleArray(EXTRA_DATA_ARRAY_BACK_COUNTS, background_histogram);
                    mBundle.putBoolean(EXTRA_DATA_SHOW_BACK_COUNTS, background_show && (!BackgroundSpectrum.isEmpty()));
                    break;

                case Constants.SCALE_DOSE_MODE:
                    double[] histData = new double[SEARCH_WINDOW_SIZE];
                    int num_data = StrictMath.max(SEARCH_WINDOW_SIZE - doseHistory.size(), 0);
                    if (isCalibrated)
                        synchronized (doseHistory) {
                            for (double v : doseEnergyHistory) {
                                if (num_data >= SEARCH_WINDOW_SIZE)
                                    break;
                                histData[num_data] = v / Constants.DOSE_SCALE;
                                num_data++;
                            }
                        }
                    else
                        synchronized (doseHistory) {
                            for (double v : doseHistory) {
                                if (num_data >= SEARCH_WINDOW_SIZE)
                                    break;
                                histData[num_data] = v / Constants.DOSE_SCALE;
                                num_data++;
                            }
                        }
                    mBundle.putDoubleArray(EXTRA_DATA_ARRAY_LONG_COUNTS, histData);
                    mBundle.putDoubleArray(EXTRA_DATA_ARRAY_BACK_COUNTS, new double[1024]);
                    mBundle.putBoolean(EXTRA_DATA_SHOW_BACK_COUNTS, false);
                    break;

                case Constants.SCALE_COUNT_MODE:
                    Arrays.fill(realTimeX, 0);
                    synchronized (inputSync) {
                        if (inputType == INPUT_AUDIO) {
                            for (int i = 0; i < StrictMath.min(realTimeX.length, (AudioBytesRead / 2)); i++)
                                realTimeX[i] = AudioData[i];
                        }
                    }
                    mBundle.putDoubleArray(EXTRA_DATA_ARRAY_LONG_COUNTS, realTimeX);
                    mBundle.putDoubleArray(EXTRA_DATA_ARRAY_BACK_COUNTS, background_histogram);
                    mBundle.putBoolean(EXTRA_DATA_SHOW_BACK_COUNTS, false);
                    break;

                case Constants.SCALE_IMPULSE_MODE:
                    synchronized (inputSync) {
                        if (inputType != INPUT_AUDIO) {
                            Arrays.fill(referenceDoublePulse, 0);
                        } else {
                            for (int i = 0; i < 1024; i++) {
                                referenceDoublePulse[i] = referencePulse[i];
                            }
                        }
                    }
                    mBundle.putDoubleArray(EXTRA_DATA_ARRAY_LONG_COUNTS, referenceDoublePulse);
                    mBundle.putDoubleArray(EXTRA_DATA_ARRAY_BACK_COUNTS, background_histogram);
                    mBundle.putBoolean(EXTRA_DATA_SHOW_BACK_COUNTS, false);
                    break;

                default:
                    break;
            }

            intent.putExtras(mBundle);

            if (context != null)
                context.sendBroadcast(intent);
        }
    };

    public class LocalBinder extends Binder {
        AtomSpectraService getService() {
            return AtomSpectraService.this;
        }
    }

    private final Timer timerExec = new Timer();

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

    private final TimerTask periodicTask = new TimerTask() {
        @Override
        public void run() {
            if (outputSound) {
                if (outputSoundID == -1) {
                    outputSoundInit();
                }
                soundPeriodNum++;
                if (cpsBaseLevel == 0) {
                    cpsCurrentLevel = 0;
                    double soundTime = 0;
                    double cpsTime = 0;
                    synchronized (window) {
                        if (!window.isEmpty()) {
                            for (int i = window.size() - 1; i >= 0; i--) {
                                soundTime += deltaTime.get(i);
                                cpsTime += window.get(i);
                                if (soundTime >= minMeanSoundTime)
                                    break;
                            }
                        }
                    }
                    if (soundTime >= minMeanSoundTime) {
                        cpsBaseLevel = cpsTime / soundTime;
                        cpsBaseSignal = cpsBaseLevel + soundSigma * StrictMath.sqrt(cpsBaseLevel * soundSigmaTime + 80) / soundSigmaTime;
                        cpsCurrentLevel = cpsBaseLevel;
                    }
                }
                if (soundPeriodNum >= soundPeriodCount) {
                    soundPeriodNum = 0;
                    double boost = 0.0;
                    synchronized (window) {
                        int sizeDose = window.size();
                        if (sizeDose > 2) {
                            if (window.get(sizeDose - 1) > 1.05 * window.get(sizeDose - 2) && window.get(sizeDose - 2) > 1.05 * window.get(sizeDose - 3))
                                boost = 0.5;
                            if (1.05 * window.get(sizeDose - 1) < window.get(sizeDose - 2) && 1.05 * window.get(sizeDose - 2) < window.get(sizeDose - 3))
                                boost = 0.5;
                        }
                    }
                    if (cpsInterval > 1.02 * cpsCurrentLevel) {
                        cpsCurrentLevel = cpsInterval + boost * (cpsInterval - cpsCurrentLevel);
                    } else if (cpsInterval * 1.02 < cpsCurrentLevel) {
                        cpsCurrentLevel = cpsInterval + boost * (cpsInterval - cpsCurrentLevel);
                    }
                    cpsCurrentLevel = StrictMath.max(cpsCurrentLevel, 0.0);
                    if (cpsBaseSignal > 0 && (cpsCurrentLevel > cpsBaseSignal)) {
                        double soundAngle = 0.0;

                        double angleStep = soundFreqList[Constants.MinMax((int) (cpsCurrentLevel / cpsBaseSignal * 2.0) - 1, 0, soundFreqList.length - 1)] * 2.0 * StrictMath.PI / 44100.0; //let's begin from 100Hz
                        for (int i = 0; i < soundBufferSize; i++) {
                                soundBuffer[i + soundBufferSize * soundBufferSwitch] = (float) StrictMath.sin(soundAngle);
                                soundAngle += angleStep;
                        }
                        synchronized (soundSync) {
                            if (soundTrack != null && soundTrack.write(soundBuffer, soundBufferSize * soundBufferSwitch, soundBufferSize, AudioTrack.WRITE_BLOCKING) < 0) {
                                soundTrack.pause();
                                soundTrack.flush();
                                outputSoundID = -1;
                            }
                        }
                        soundBufferSwitch = 1 - soundBufferSwitch;
                    }
                }
            }
            synchronized (inputSync) {
                if (inputType == INPUT_SERIAL)
                    return;
            }
            if (freeze_update_data)
                return;
            timer += Constants.UPDATE_PERIOD;
//            if (!freeze_update_data) ForegroundSpectrum.setSpectrumTime(ForegroundSpectrum.getSpectrumTime() + 1);
            ForegroundSpectrum.setSpectrumTime(ForegroundSpectrum.getSpectrumTime() + 1);
            if (timer >= 1000 / doseRateUpdateFreq) {
                int int_cps = 0;
                int int_cps_interval = 0;
                for (int i = 0; i < (1000 / Constants.UPDATE_PERIOD); i++) {
                    int_cps += cpsArray[i];
                    int_cps_interval += cpsArrayInterval[i];
                }
                cps = int_cps;
                cpsInterval = int_cps_interval;
                timer = 0;
            }
            doseRateTimer += Constants.UPDATE_PERIOD;
            double temp_dose = doseRateSearch(
                    cpsArray[cpsPos],
                    cpsArrayInterval[cpsPos],
                    cpsArrayEnergy[cpsPos],
                    (double) Constants.UPDATE_PERIOD / 1000.0,
                    AtomSpectra.XCalibrated);
            if (doseRateTimer >= (1000 / doseRateUpdateFreq)) {
                doseRateTimer = 0;
                doseRateValue = temp_dose;
            }
        }
    };

    public static double[] makeSmooth(double[] input, Calibration calibration) {
        if (setSmooth) {
            double[] result = new double[input.length];
            int shift_window, old_window;
            shift_window = old_window = StrictMath.max((int) (0.3 * basic_window), 4);
            int channel_0 = StrictMath.max(100, calibration.toChannel(662.0));
            double[] GolayArray = AtomSpectraFindIsotope.calcSavitzkyGolayWeight(0, 3, shift_window);
            double temp;
            for (int i = 0; i < input.length; i++) {
                shift_window = StrictMath.max((int) ((0.3 + 0.7 * StrictMath.sqrt(calibration.toChannel(662.0) / (double) channel_0)) * basic_window), 4);
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
            shift_window = old_window = StrictMath.max((int)(0.3 * basic_window), 4);
            int channel_0 = StrictMath.max (100, calibration.toChannel(662.0));
            double[] GolayArray = AtomSpectraFindIsotope.calcSavitzkyGolayWeight(0, 3, shift_window);
            //calculate the derivative for the spectrum
            double temp;
            for (int i = 0; i < input.length; i++) {
                shift_window = StrictMath.max((int)((0.3 + 0.7 * StrictMath.sqrt(calibration.toChannel(662.0) / (double)channel_0)) * basic_window), 4);
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

    private void saveHist() {
        SharedPreferences sharedPreferences = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        boolean fileNamePrefix = sharedPreferences.getBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_PREFIX, Constants.OUTPUT_FILE_NAME_PREFIX_DEFAULT);
        boolean fileNameDate = sharedPreferences.getBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_DATE, Constants.OUTPUT_FILE_NAME_DATE_DEFAULT);
        boolean fileNameTime = sharedPreferences.getBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_TIME, Constants.OUTPUT_FILE_NAME_TIME_DEFAULT);
        Pair<OutputStreamWriter, Uri> returnPair = SpectrumFile.prepareOutputStream(this, sharedPreferences.getString(Constants.CONFIG.CONF_DIRECTORY_SELECTED, null), ForegroundSpectrum.getSpectrumDate(), "Spectrum", fileNamePrefix, "battery_low", ".txt", "text/plain", fileNameDate, fileNameTime, false);
        if (returnPair == null) {
            Toast.makeText(this, getString(R.string.perm_no_write_histogram), Toast.LENGTH_LONG).show();
            return;
        }

        OutputStreamWriter docStream = returnPair.first;
//        String spectrumFileName = returnPair.second;

        Spectrum spectrum = new Spectrum(AtomSpectraService.ForegroundSpectrum);
        Spectrum backSpectrum = new Spectrum(AtomSpectraService.BackgroundSpectrum);

        if (!sharedPreferences.getBoolean(Constants.CONFIG.CONF_ADD_GPS_TO_FILES, false)) {
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

    private void autosaveHist() {
        SharedPreferences sharedPreferences = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
        boolean fileNamePrefix = sharedPreferences.getBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_PREFIX, Constants.OUTPUT_FILE_NAME_PREFIX_DEFAULT);

        if (autosaveSpectrum == null) {
            autosaveSpectrum = new Spectrum(AtomSpectraService.ForegroundSpectrum);
            autosavePair = SpectrumFile.prepareOutputStream(this, sharedPreferences.getString(Constants.CONFIG.CONF_DIRECTORY_SELECTED, null), autosaveSpectrum.getSpectrumDate(), "Spectrogram" + '-' + autosaveSpectrum.getSuffix(), fileNamePrefix,"auto", ".txt", "text/plain", true, true, false);

            if (autosavePair == null) {
                new Handler(getMainLooper()).post(() -> Toast.makeText(getApplicationContext(), getApplicationContext().getString(R.string.perm_no_write_histogram), Toast.LENGTH_LONG).show());
                autosaveSpectrum = null;
                return;
            }

            OutputStreamWriter docStream = autosavePair.first;
            SpectrumFileAS saveFile = new SpectrumFileAS();
            saveFile.
                    addSpectrum(autosaveSpectrum).
                    setChannels(autosaveSpectrum.getDataArray().length).
                    setChannelCompression(1).
                    saveSpectrum(docStream, this);
            new Handler(getMainLooper()).post(() -> Toast.makeText(getApplicationContext(), getApplicationContext().getString(R.string.autosave_start), Toast.LENGTH_LONG).show());
            return;
        }

        Spectrum newSpectrum = new Spectrum(ForegroundSpectrum);
        Spectrum deltaSpectrum = new Spectrum(newSpectrum).subtractSpectrum(autosaveSpectrum);
        autosaveSpectrum = newSpectrum;

        if (!sharedPreferences.getBoolean(Constants.CONFIG.CONF_ADD_GPS_TO_FILES, false)) {
            deltaSpectrum.setLocation(null);
        }

        deltaSpectrum.updateComments();
        OutputStreamWriter docStream;
        try {
//            DocumentFile fileDoc = DocumentFile.fromSingleUri(this, new File(autosavePair.second).toURI());
            docStream = new OutputStreamWriter(context.getContentResolver().openOutputStream(autosavePair.second, "wa"));// new (new Uri.Builder().build());
            SpectrumFileAS saveFile = new SpectrumFileAS();
            saveFile.
                    addSpectrum(deltaSpectrum).
                    setChannels(deltaSpectrum.getDataArray().length).
                    setChannelCompression(1).
                    saveIncrementalSpectrum(docStream, this);
        } catch (Exception e) {
            new Handler(getMainLooper()).post(() -> Toast.makeText(getApplicationContext(), String.format("!%s: %s", e.getMessage(), autosavePair.second), Toast.LENGTH_LONG).show());
        }
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
}

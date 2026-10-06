package org.fe57.atomspectra;

import java.text.DecimalFormatSymbols;

/**
 * Created by ENDulov on 20.02.17.
 */
public class Constants {

    public static final String ATOMSPECTRA_PREFERENCES = "AtomSpectra Preferences";
    public static final String PACKAGE_NAME = "org.fe57.atomspectra";
    public static final int SEARCH_FAST_DEFAULT = 100, SEARCH_MEDIUM_DEFAULT = 500, SEARCH_SLOW_DEFAULT = 2000;          //dose rate impulse count
    public static final int MIN_CHANNEL_COUNT = 1024;
    public static final int DEFAULT_CHANNEL_COUNT = 8192;
    public static final int VIEW_CHANNELS_DEFAULT = 1024;                                                                //number of channels to view on graph (128, 256, 512, 1024)
    public static final int EXPORT_COMPRESSION_DEFAULT = 1;                                                              //number of channels in one export channel
    public static final int WINDOW_OUTPUT_SIZE = 512;                                                                    //minimum number of points on the screen
    public static final boolean LOG_SCALE_DEFAULT = false;
    // !!! WARNING: all scale constants are fragile to change, code changes will be required
    public static final int SCALE_MAX = 16 - Integer.numberOfTrailingZeros(WINDOW_OUTPUT_SIZE);                         //maximum scale factor (32X)

    public static int scaleMinFor(int channelCount) {
        return SCALE_MAX - (Integer.numberOfTrailingZeros(channelCount) - Integer.numberOfTrailingZeros(WINDOW_OUTPUT_SIZE));
    }

    public static boolean isValidChannelCount(int channelCount) {
        return channelCount >= MIN_CHANNEL_COUNT && (channelCount & (channelCount - 1)) == 0;
    }

    public static final int DISPLAY_MODE_SPECTRUM = 0;
    public static final int DISPLAY_MODE_SPECTRUM_CHANGE = 1;
    public static final int DISPLAY_MODE_SEARCH = 2;
    public static final int DISPLAY_MODE_SPECTROGRAM = 3;
    public static final int DISPLAY_MODE_DEFAULT = DISPLAY_MODE_SPECTRUM;
    public static final int UPDATE_DOSE_DEFAULT = 1;                                                                     //dose rate update per second
    public static final int MAX_POLI_SIZE = 4;                                                                           //maximum polynom size of y=a+bx+cx^2+... To add higher size you need to add more menu items to menu and its checks
    public static final int MAX_CALIBRATION_POINTS = 10;                                                                 //maximum new calibration points
    // lowest channel the calibration may end at: the energy scale is spanned by channels 0 and lastChannel - 1
    public static final int MIN_LAST_CALIBRATION_CHANNEL = 2;
    public static final double DOSE_SCALE = 0.001;                                                                       //scale input data to draw
    public static final double DOSE_OVERHEAD = 1.05;                                                                     //maximum to be shown
    public static final int CURSOR_TIMEOUT = 7000;                                                                       //timeout of buttons in ms
    public static final int WINDOW_SEARCH_DEFAULT = 60;                                                                  //window search size
    public static final long SEARCH_HISTORY_TIME_GAP_THRESHOLD_MS = 3000L;                                               //search history pause/resume gap threshold
    public static final float TOLERANCE_DEFAULT = 5.0f;                                                                  //tolerance default
    public static final float THRESHOLD_DEFAULT = 0.5f;                                                                  //threshold default
    public static final int ORDER_DEFAULT = 5;                                                                           //order size
    public static final int ORDER_MAX = 5;                                                                               //maximum order size
    public static final int COMPRESS_GRAPH_SUM = 0;                                                                      //show sum values
    public static final int COMPRESS_GRAPH_AVERAGE = 1;                                                                  //show average values
    public static final int COMPRESS_GRAPH_MAX = 2;                                                                      //show max values
    public static final int DEFAULT_POLI_FACTOR = 1;                                                                     //maximum factor for energy calibration
    public static final int DEFAULT_GOLAY_WINDOW = 1;                                                                    //default Golay window
    public static final boolean OUTPUT_FILE_NAME_USE_PREFIX_DEFAULT = true;                                                  //add prefix to output file name
    public static final boolean OUTPUT_FILE_NAME_ADD_DATE_DEFAULT = true;                                                    //add date to output file name
    public static final boolean OUTPUT_FILE_NAME_ADD_TIME_DEFAULT = true;                                                    //add date to output file name
    public static final int DEFAULT_DELTA_TIME = 1;                                                                      //default delta time in seconds between output
    public static String[] LOCALES = {"Default", "Russian", "English"};
    public static String[] LOCALES_ID = {"", "ru", "en"};
    public static final int SPG_DELTA_DURATION_MIN = 5;                                                                          //minimal delta between auto saves
    public static final int SPG_DELTA_DURATION_MAX = 60;                                                                     //maximum delta between auto saves
    public static final int SPG_INTERVAL_DEFAULT = 0;
    public static final boolean SPG_MIDNIGHT_RESET_DEFAULT = false;
    public static final boolean ADD_GPS_TO_FILES_DEFAULT = true;                                                        //add GPS coordinates to files by default
    public static final boolean SEND_DATA_TO_ATOMSWIFT_DEFAULT = false;
    public static final boolean ALLOW_PARTIAL_HISTOGRAM_DEFAULT = false;
    public static final String ATOMSWIFT_DR_COMPENSATED = "compensated";
    public static final String ATOMSWIFT_DR_NON_COMPENSATED = "non-compensated";
    public static final String ATOMSWIFT_DR_INTERVAL = "interval";
    public static final String ATOMSWIFT_DR_DEFAULT = ATOMSWIFT_DR_NON_COMPENSATED;
    public static String[] ATOMSWIFT_DOSE_RATES = {ATOMSWIFT_DR_NON_COMPENSATED, ATOMSWIFT_DR_COMPENSATED, ATOMSWIFT_DR_INTERVAL};
    public static final String DISPLAY_DOSE_COMPENSATED = "dose-compensated";
    public static final String DISPLAY_DOSE_NON_COMPENSATED = "dose-non-compensated";
    public static final String DISPLAY_DOSE_INTERVAL = "dose-interval";
    public static final String DISPLAY_DOSE_DEFAULT = DISPLAY_DOSE_NON_COMPENSATED;
    public static final double ALARM_BASELINE_ERROR_PERCENT_THRESHOLD = 5;
    public static final int ALARM_BASELINE_MAX_DURATION = 30;
    public static final int ALARM_DETECTION_LEVEL_DEFAULT = 4;
    public static final int ALARM_VOLUME_DEFAULT = 50; // %


    public interface CONFIG {
        String CONF_REDUCED_TO = "Reduced to:";
        String CONF_EXPORT_COMPRESSION = "Channel compression:";
        String CONF_MIN_POINTS = "Min front points:";
        String CONF_MAX_POINTS = "Max front points:";
        String CONF_NOISE = "Noise discriminator:";
        String CONF_INVERSION = "Inversion:";
        String CONF_PILE_UP = "PileUp correction:";
        String CONF_SCALE_FACTOR = "ScaleFactor:";
        String CONF_LOG_SCALE = "LogScale";
        String CONF_CAL_POLI_SIZE = "Poli size:";
        String CONF_CAL_POLI_COEFFICIENT = "CalCoeff";
        String CONF_CAL_CHANNEL = "Cal";
        String CONF_CAL_ENERGY = "CalE";
        // followed by the device id; semicolon-separated polynomial coefficients
        String CONF_CAL_DEVICE = "DeviceCal:";
        String CONF_FIRST_CHANNEL = "FirstChannel:";
        String CONF_CHECK_POWER = "CheckPower:";
        String CONF_CHECK_AUDIO = "Check audio:";
        String CONF_BAR_MODE = "BarMode:";
        String CONF_CALIBRATED = "Xcalibrated:";
        String CONF_DISPLAY_DOSE = "display_dose:";
        String CONF_SEARCH_MODE = "searchfsm";

        // sensitivity profiles (single custom slot)
        String CONF_SENSITIVITY_PROFILE = "sensitivity_profile";        // active profile id
        String CONF_CUSTOM_PROFILE_NAME = "custom_profile_name";
        String CONF_NONCOMP_PSV = "noncomp_psv";                      // custom non-compensated pSv/count (double bits)
        String CONF_SEARCH_COMP_FAST = "search_comp_fast";
        String CONF_SEARCH_COMP_MEDIUM = "search_comp_medium";
        String CONF_SEARCH_COMP_SLOW = "search_comp_slow";
        String CONF_SEARCH_NONCOMP_FAST = "search_noncomp_fast";
        String CONF_SEARCH_NONCOMP_MEDIUM = "search_noncomp_medium";
        String CONF_SEARCH_NONCOMP_SLOW = "search_noncomp_slow";

        String CONF_DOSE_UPDATE = "doserate_update_freq";
        String CONF_SENS_TABLE_SIZE = "CalSize";
        String CONF_SENS_TABLE_VALUE = "CalSense";
        String CONF_SENS_TABLE_ENERGY = "CalCEnergy";
        String CONF_AUDIO_SOURCE = "Audio source:";
        String CONF_DIRECTORY_SELECTED = "Directory selected";
        String CONF_OUTPUT_FILE_NAME_ADD_PREFIX = "File name prefix";
        String CONF_OUTPUT_FILE_NAME_ADD_DATE = "File name date";
        String CONF_OUTPUT_FILE_NAME_ADD_TIME = "File name time";
        String CONF_ADD_GPS_TO_FILES = "Add GPS coord";
        String CONF_OUTPUT_SOUND = "Sound output";
        String CONF_OUTPUT_SOUND_DEVICE_ID = "Sound device ID";
        String CONF_OUTPUT_SOUND_DEVICE_NAME = "Sound device name";
        String CONF_SEARCH_ALARM_VOLUME = "Interval Search Alarm Volume";
        String CONF_SEARCH_DETECTION_LEVEL = "Interval Search Detection Level";
        String CONF_COMPRESS_GRAPH = "Compress graph";
        String CONF_LAST_CHANNEL = "Last calibration channel";
        String CONF_MAX_POLI_FACTOR = "Polinom factor";
        String CONF_GOLAY_WINDOW = "Golay window";
        String CONF_AUTO_UPDATE_ISOTOPES = "Auto update isotopes";
        String CONF_SPECTRUM_CHANGE_DIFF_TIME = "Delta time";
        String CONF_LOCALE_ID = "Locale";
        // the device choice restored on the next launch, see DeviceChoice
        String CONF_DEVICE_MODE = "Device mode";
        String CONF_DEVICE_TYPE = "Device type";
        String CONF_DEVICE_IDENTITY = "Device identity";
        String CONF_DEVICE_NAME = "Device name";
        String CONF_DEVICE_REMEMBER = "Remember device choice";
        String CONF_SPG_DELTA_DURATION = "File autosave";
        String CONF_SPG_MIDNIGHT_RESET = "Reset spectrogram at midnight";
        String CONF_SEND_DATA_TO_ATOMSWIFT = "Send data to AtomSwift app";
        String CONF_ATOMSWIFT_DOSE_RATE = "AtomSwift dose rate";
        String CONF_ALLOW_PARTIAL_HISTOGRAM = "usb_allow_partial_histogram";
        String CONF_PERMISSIONS_REQUESTED = "permissions_requested";
        /**
         * Map basemap provider id: {@code none}, {@code osm}, or {@code custom}.
         */
        String CONF_MAP_TILE_PROVIDER = "map_tile_provider";
        /**
         * Custom tile URL template with {z}/{x}/{y} when provider is {@code custom}.
         */
        String CONF_MAP_TILE_CUSTOM_URL = "map_tile_custom_url";
        /**
         * Optional HTML attribution for the custom tile provider.
         */
        String CONF_MAP_TILE_CUSTOM_ATTRIBUTION = "map_tile_custom_attribution";
    }

    public interface SEARCH {
        String PREF_COMPRESSION = "Compression:";
        String PREF_WINDOW_SIZE = "Window size:";
        String PREF_TOLERANCE = "Tolerance:";
        String PREF_THRESHOLD = "Threshold:";
        String PREF_ORDER = "Order:";
        String PREF_LIBRARY = "Library:";
    }

    public interface ACTION {
        String ACTION_START_FOREGROUND = "org.fe57.AtomSpectraService.action.START_FOREGROUND";
        String ACTION_STOP_FOREGROUND = "org.fe57.AtomSpectraService.action.STOP_FOREGROUND";
        String ACTION_CLOSE_APP = "org.fe57.atomspectra.ACTION_CLOSE_APP";
        String ACTION_CLOSE_SETTINGS = "org.fe57.atomspectra.ACTION_CLOSE_SETTINGS";
        String ACTION_CLOSE_ISOTOPES = "org.fe57.atomspectra.ACTION_CLOSE_ISOTOPES";
        String ACTION_CLOSE_SEARCH = "org.fe57.atomspectra.ACTION_CLOSE_SEARCH";
        String ACTION_CLOSE_HELP = "org.fe57.atomspectra.ACTION_CLOSE_HELP";
        String ACTION_CLOSE_LOG = "org.fe57.atomspectra.ACTION_CLOSE_LOG";
        String ACTION_LOG_UPDATED = "org.fe57.atomspectra.ACTION_LOG_UPDATED";
        String ACTION_CLOSE_SPECTROGRAM = "org.fe57.atomspectra.ACTION_CLOSE_SPECTROGRAM";
        String ACTION_SPECTROGRAM_UPDATED = "org.fe57.atomspectra.ACTION_SPECTROGRAM_UPDATED";

        // attach/detach USB
        // sent by AtomSpectraSerial on errors
        // sent by AtomSpectra on system events
        // read by AtomSpectraService

        String ACTION_UPDATE_NOTIFICATION = "org.fe57.atomspectra.ACTION_UPDATE_NOTIFICATION";

        // shared data changed, consumers re-read what they need
        String ACTION_DATA_AVAILABLE = "org.fe57.atomspectra.ACTION_DATA_AVAILABLE";
        // payload-less: AudioScopeData has a new raw audio snapshot, sent by the audio source
        String ACTION_RAW_AUDIO_SNAPSHOT = "org.fe57.atomspectra.ACTION_RAW_AUDIO_SNAPSHOT";

        // produced when start/stop of data collection is needed
        String ACTION_START_RECORDING = "org.fe57.atomspectra.ACTION_START_RECORDING";
        String ACTION_STOP_RECORDING = "org.fe57.atomspectra.ACTION_STOP_RECORDING";
        // sent by the service when the device chosen with selectDevice() has connected
        String ACTION_DEVICE_SELECTED = "org.fe57.atomspectra.ACTION_DEVICE_SELECTED";
        // sent by the service when the chosen device cannot be used and the user has to choose again; ACTION_PARAMETERS.SELECTION_ERROR_TEXT when known
        String ACTION_DEVICE_SELECTION_REQUIRED = "org.fe57.atomspectra.ACTION_DEVICE_SELECTION_REQUIRED";
        // sent by the service when a connected device waits for the user to decide what happens to the unsaved screen spectrum
        String ACTION_DEVICE_CONNECT_DECISION = "org.fe57.atomspectra.ACTION_DEVICE_CONNECT_DECISION";
        String ACTION_DEVICE_STATE_CHANGED = "org.fe57.atomspectra.ACTION_DEVICE_STATE_CHANGED";
        String ACTION_CLEAR_SPECTRUM = "org.fe57.atomspectra.ACTION_CLEAR_SPECTRUM";

        // read by AtomSpectra to actualize menu status after capturing status change
        String ACTION_UPDATE_MENU = "org.fe57.atomspectra.ACTION_UPDATE_MENU";

        // calibration is owned by AtomSpectraService:
        // sent to the service to (re)load the calibration from preferences or from the USB device
        String ACTION_LOAD_CALIBRATION = "org.fe57.atomspectra.ACTION_LOAD_CALIBRATION";
        // sent to the service to store the active calibration into preferences or into the USB device
        String ACTION_STORE_CALIBRATION = "org.fe57.atomspectra.ACTION_STORE_CALIBRATION";
        String ACTION_CHECK_GPS_AVAILABILITY = "org.fe57.atomspectra.ACTION_CHECK_GPS";
        String ACTION_UPDATE_SETTINGS = "org.fe57.atomspectra.ACTION_UPDATE_SETTINGS";
        String ACTION_CLOSE_SENSITIVITY = "org.fe57.atomspectra.ACTION_CLOSE_SENSITIVITY";
        String ACTION_UPDATE_ISOTOPE_LIST = "org.fe57.atomspectra.ACTION_UPDATE_ISOTOPE_LIST";
        String ACTION_UPDATE_GPS = "org.fe57.atomspectra.SEND_GPS_COMMAND";
        /**
         * Map-facing device position snapshot (immutable extras).
         */
        String ACTION_DEVICE_LOCATION_UPDATED = "org.fe57.atomspectra.ACTION_DEVICE_LOCATION_UPDATED";
    }

    public interface ACTION_PARAMETERS {
        String USB_COMMAND_ID = "ID";
        String USB_COMMAND_DATA = "Data";
        String GPS_STATUS = "Status";
        // foreground-service type bitmask computed by the activity and passed to the service
        String FGS_TYPE = "fgs_type";
        String SELECTION_ERROR_TEXT = "selection_error_text";
        // what ACTION_START_RECORDING does with a screen spectrum the device did not produce
        String START_FOREIGN_SPECTRUM = "start_foreign_spectrum";
        int FOREIGN_SPECTRUM_UNDECIDED = 0;     // the start is refused when the user has to decide first
        int FOREIGN_SPECTRUM_CONTINUE = 1;      // the screen spectrum is handed to the device, which continues it
        int FOREIGN_SPECTRUM_REPLACE = 2;       // the screen spectrum is discarded
        String DEVICE_LOCATION_AVAILABLE = "device_location_available";
        String DEVICE_LOCATION_LATITUDE = "device_location_latitude";
        String DEVICE_LOCATION_LONGITUDE = "device_location_longitude";
        String DEVICE_LOCATION_TIME = "device_location_time";
        String DEVICE_LOCATION_ACCURACY = "device_location_accuracy";
        String DEVICE_LOCATION_PROVIDER = "device_location_provider";
    }

    public interface GROUPS {
        int GROUP_SENSE_TABLE = 10000;
        int GROUP_ISOTOPES_DETECTED = 3000;
        int GROUP_ID_ALIGN = 900;
        int ENERGY_ID_ALIGN = 1000;
        int ENERGY_ID_HALF_LIFE_ALIGN = 1200;
        int BUTTON_ID_ALIGN = 1400;
        int BUTTON_ENERGY_ID_ALIGN = 2400;
        int BUTTON_INTENSE_ID_ALIGN = 3400;
        int FILE_SELECTION_GROUP = 4400;
    }

    public static int MinMax(int val, int min, int max) {
        return val >= max ? max : (Math.max(val, min));
    }

    public static long MinMax(long val, long min, long max) {
        return val >= max ? max : (Math.max(val, min));
    }

    public static float MinMax(float val, float min, float max) {
        return val >= max ? max : (Math.max(val, min));
    }

    public static double MinMax(double val, double min, double max) {
        return val >= max ? max : (Math.max(val, min));
    }

    public static String getPower(int pow) {
        final String[] powers = {"°", "¹", "²", "³", "⁴", "⁵", "⁶", "⁷", "⁸", "⁹"};

        if (pow == 0)
            return powers[0];

        StringBuilder res = new StringBuilder();
        int cur_pow = pow;

        while (cur_pow > 0) {
            res.insert(0, powers[cur_pow % 10]);
            cur_pow = cur_pow / 10;
        }

        return res.toString();
    }


    /**
     * @param number - number to be converted to the exponential form
     * @return String in certain notation
     */
    public static String numberToPower(long number) {
        final String[] powers = {"°", "¹", "²", "³", "⁴", "⁵", "⁶", "⁷", "⁸", "⁹"};

        if (number <= 10)
            return String.valueOf(number);

        StringBuilder numbers = new StringBuilder();

        while (number > 0) {
            numbers.insert(0, number % 10);
            number /= 10;
        }

        int cur_pow = numbers.length() - 1;

        StringBuilder res = new StringBuilder();

        if (cur_pow == 0)
            res.append(powers[0]);

        while (cur_pow > 0) {
            res.insert(0, powers[cur_pow % 10]);
            cur_pow = cur_pow / 10;
        }

        while (numbers.length() > 1 && '0' == numbers.charAt(numbers.length() - 1)) {
            numbers.deleteCharAt(numbers.length() - 1);
        }

        if (numbers.length() > 1) {
            numbers.insert(1, DecimalFormatSymbols.getInstance().getDecimalSeparator());
        }

        numbers.append("·10");
        numbers.append(res);

        return numbers.toString();
    }
}

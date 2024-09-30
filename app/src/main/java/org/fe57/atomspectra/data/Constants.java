package org.fe57.atomspectra.data;

/**
 * Created by ENDulov on 20.02.17.
 */
public class Constants {

    public static final String ATOMSPECTRA_PREFERENCES = "AtomSpectra Preferences";
    public static final int SEARCH_FAST_DEFAULT = 100, SEARCH_MEDIUM_DEFAULT = 500, SEARCH_SLOW_DEFAULT = 2000;          //dose rate impulse count
    public static final int ADC_MAX = 13;                                                                                //maximum ADC bit size (not more than 16)
    public static final int ADC_DEFAULT = ADC_MAX;                                                                       //number of usable ADC high bits
    public static final int ADC_MIN = 10;                                                                                //minimum ADC bit size (not less than 10)
    public static final int NUM_HIST_POINTS = 1 << ADC_MAX;                                                              //size of histogram
    public static final int MIN_NUM_HIST_POINTS = 128;                                                                   //minimum size of histogram
    public static final int SENSG_DEFAULT = 1700;                                                                        //spectrometer sensitivity
    public static final int BACKGROUND_CNT_DEFAULT = 0;                                                                  //spectrometer noise level
    public static final int VIEW_CHANNELS_DEFAULT = 1024;                                                                //number of channels to view on graph (128, 256, 512, 1024)
    public static final int EXPORT_CHANNELS_DEFAULT = NUM_HIST_POINTS;                                                   //number of channels to export
    public static final int LOAD_CHANNELS_DEFAULT = NUM_HIST_POINTS;                                                     //number of channels to load
    public static final int EXPORT_COMPRESSION_DEFAULT = 1;                                                              //number of channels in one export channel
    public static final int MIN_FRONT_POINTS_DEFAULT = 4;                                                                //low level of peak front
    public static final int MAX_FRONT_POINTS_DEFAULT = 10;                                                               //high level of peak front
    public static final int NOISE_DISCRIMINATOR_DEFAULT = 256 >> (16 - ADC_MAX);                                         //noise discriminator
    public static final int WINDOW_OUTPUT_SIZE = 512;                                                                    //minimum number of points on the screen
    public static final boolean INVERSE_DEFAULT = false;                                                                 //inverse signal
    public static final boolean PILE_UP_DEFAULT = true;                                                                  //detect pile-up
    public static final int SCALE_DEFAULT = 16 - ADC_MAX;                                                                //default scale: 8192 channels on graph
    public static final int SCALE_MIN = 16 - ADC_MAX;                                                                    //minimum scale factor
    public static final int SCALE_MAX = 16 - ADC_MIN + 1;                                                                //maximum scale factor
    public static final int SCALE_DOSE_MODE = 10;                                                                        //dose scale factor
    public static final int SCALE_COUNT_MODE = 100;                                                                      //data from audio input
    public static final int SCALE_IMPULSE_MODE = 101;                                                                    //impulse show
    public static final int UPDATE_DOSE_DEFAULT = 1;                                                                     //dose rate update per second
    public static final int MAX_POLI_SIZE = 4;                                                                           //maximum polynom size of y=a+bx+cx^2+... To add higher size you need to add more menu items to menu and its checks
    public static final int MAX_CALIBRATION_POINTS = 10;                                                                 //maximum new calibration points
    public static final double DOSE_SCALE = 0.001;                                                                       //scale input data to draw
    public static final double DOSE_OVERHEAD = 1.05;                                                                     //maximum to be shown
    public static final int CURSOR_TIMEOUT = 7000;                                                                       //timeout of buttons in ms
    public static final int LABEL_TIMEOUT = 3000;                                                                        //timeout of labels in ms
    public static final int WINDOW_SEARCH_DEFAULT = 60;                                                                  //window search size
    public static final float TOLERANCE_DEFAULT = 5.0f;                                                                  //tolerance default
    public static final float THRESHOLD_DEFAULT = 0.5f;                                                                  //threshold default
    public static final int ORDER_DEFAULT = 5;                                                                           //order size
    public static final int ORDER_MAX = 5;                                                                               //maximum order size
    public static final int COMPRESS_GRAPH_SUM = 0;                                                                      //show sum values
    public static final int COMPRESS_GRAPH_AVERAGE = 1;                                                                  //show average values
    public static final int COMPRESS_GRAPH_MAX = 2;                                                                      //show max values
    public static final int DEFAULT_POLI_FACTOR = 1;                                                                     //maximum factor for energy calibration
    public static final int DEFAULT_GOLAY_WINDOW = 1;                                                                    //default Golay window
    public static final boolean OUTPUT_FILE_NAME_PREFIX_DEFAULT = true;                                                  //add prefix to output file name
    public static final boolean OUTPUT_FILE_NAME_DATE_DEFAULT = true;                                                    //add date to output file name
    public static final boolean OUTPUT_FILE_NAME_TIME_DEFAULT = true;                                                    //add date to output file name
    public static final int DEFAULT_DELTA_TIME = 1;                                                                      //default delta time in seconds between output
    public static final int UPDATE_PERIOD = 100; //in ms
    public static String[] LOCALES = {"Default", "Russian", "English", "French", "Deutsch"};
    public static String[] LOCALES_ID = {"", "ru", "en", "fr", "de"};
    public static final int USB_DEVICE_MINIMAL_VERSION = 11;                                                             //minimal firmware version for AtomSpectra Pro to operate correctly

    public interface CONFIG {
        String CONF_REDUCED_TO = "Reduced to:";
        String CONF_SAVE_CHANNELS = "Save channels:";
        String CONF_LOAD_CHANNELS = "Load channels:";
        String CONF_COMPRESSION = "Channel compression:";
        String CONF_MIN_POINTS = "Min front points:";
        String CONF_MAX_POINTS = "Max front points:";
        String CONF_NOISE = "Noise discriminator:";
        String CONF_ROUNDED = "ADC is rounded to:";
        String CONF_INVERSION = "Inversion:";
        String CONF_PILE_UP = "PileUp correction:";
        String CONF_SCALE_FACTOR = "ScaleFactor:";
        String CONF_POLI_SIZE = "Poli size:";
        String CONF_FIRST_CHANNEL = "FirstChannel:";
        String CONF_CHECK_POWER = "CheckPower:";
        String CONF_CHECK_AUDIO = "Check audio:";
        String CONF_BAR_MODE = "BarMode:";
        String CONF_CALIBRATED = "Xcalibrated:";
        String CONF_SEARCH_MODE = "searchfsm";
        String CONF_SENSG = "sensg";
        String CONF_BACKGROUND = "backgcnt";
        String CONF_SEARCH_FAST = "search_fast";
        String CONF_SEARCH_SLOW = "search_slow";
        String CONF_SEARCH_MEDIUM = "search_medium";
        String CONF_DOSE_UPDATE = "doserate_update_freq";
        String CONF_CHANNEL = "Cal";
        String CONF_ENERGY = "CalE";
        String CONF_COEFFICIENT = "CalCoeff";
        String CONF_CALIBRATION_SIZE = "CalSize";
        String CONF_CALIBRATION_SENSE = "CalSense";
        String CONF_CALIBRATION_ENERGY = "CalCEnergy";
        String CONF_AUDIO_SOURCE = "Audio source:";
        String CONF_DIRECTORY_SELECTED = "Directory selected";
        String CONF_OUTPUT_FILE_NAME_PREFIX = "File name prefix";
        String CONF_OUTPUT_FILE_NAME_DATE = "File name date";
        String CONF_OUTPUT_FILE_NAME_TIME = "File name time";
        String CONF_ADD_GPS_TO_FILES = "Add GPS coord";
        String CONF_E_TO_MSV_COUNT = "E to mSv count";
        String CONF_OUTPUT_SOUND = "Sound output";
        String CONF_OUTPUT_SOUND_DEVICE_ID = "Sound device ID";
        String CONF_OUTPUT_SOUND_DEVICE_NAME = "Sound device name";
        String CONF_INPUT_SOUND = "Input sound";
        String CONF_INPUT_SOUND_DEVICE_ID = "Input sound device ID";
        String CONF_INPUT_SOUND_DEVICE_NAME = "Input sound device name";
        String CONF_COMPRESS_GRAPH = "Compress graph";
        String CONF_LAST_CHANNEL = "Last calibration channel";
        String CONF_MAX_POLI_FACTOR = "Polinom factor";
        String CONF_GOLAY_WINDOW = "Golay window";
        String CONF_AUTO_UPDATE_ISOTOPES = "Auto update isotopes";
        String CONF_DELTA_TIME = "Delta time";
        String CONF_LOCALE_ID = "Locale";
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
        String ACTION_SOURCE_CHANGED = "org.fe57.atomspectra.ACTION_SOURCE_CHANGED";                   //USB or audio selection
        String ACTION_AUDIO_CHANGED = "org.fe57.atomspectra.ACTION_AUDIO_CHANGED";                     //Added or removed audio input
        String ACTION_UPDATE_NOTIFICATION = "org.fe57.atomspectra.ACTION_UPDATE_NOTIFICATION";
        String ACTION_HAS_DATA = "org.fe57.atomspectra.ACTION_HAS_DATA";
        String ACTION_HAS_ANSWER = "org.fe57.atomspectra.ACTION_HAS_ANSWER";
        String ACTION_FREEZE_DATA = "org.fe57.atomspectra.ACTION_FREEZE_DATA";
        String ACTION_CLEAR_SPECTRUM = "org.fe57.atomspectra.ACTION_CLEAR_SPECTRUM";
        String ACTION_CLEAR_IMPULSE = "org.fe57.atomspectra.ACTION_CLEAR_IMPULSE";
        String ACTION_UPDATE_MENU = "org.fe57.atomspectra.ACTION_UPDATE_MENU";
        String ACTION_UPDATE_CALIBRATION = "org.fe57.atomspectra.ACTION_UPDATE_CALIBRATION";
        String ACTION_CHECK_GPS_AVAILABILITY = "org.fe57.atomspectra.ACTION_CHECK_GPS";
        String ACTION_UPDATE_SETTINGS = "org.fe57.atomspectra.ACTION_UPDATE_SETTINGS";
        String ACTION_CLOSE_SENSITIVITY = "org.fe57.atomspectra.ACTION_CLOSE_SENSITIVITY";
        String ACTION_UPDATE_ISOTOPE_LIST = "org.fe57.atomspectra.ACTION_UPDATE_ISOTOPE_LIST";
        String ACTION_GET_USB_PERMISSION = "org.fe57.atomspectra.GET_USB_PERMISSION";
        String ACTION_SEND_USB_COMMAND = "org.fe57.atomspectra.SEND_USB_COMMAND";
        String ACTION_UPDATE_GPS = "org.fe57.atomspectra.SEND_GPS_COMMAND";
    }

    public interface ACTION_PARAMETERS {
        String USB_COMMAND_ID = "ID";
        String USB_COMMAND_DATA = "Data";
        String UPDATE_USB_CALIBRATION = "USB";
        String GPS_STATUS = "Status";
        String USB_DEVICE = "USB device";
        String FREEZE_STATE = "Freeze state";
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

    public static final String SCALE_FACTOR = "Scale factor";

    public static String configChannel(int i) {
        return CONFIG.CONF_CHANNEL + i + ":";
    }

    public static String configEnergy(int i) {
        return CONFIG.CONF_ENERGY + i + ":";
    }

    public static String configCoefficient(int i) {
        return CONFIG.CONF_COEFFICIENT + i + ":";
    }

    public static String configCalibration(int i) {
        return CONFIG.CONF_CALIBRATION_SENSE + i + ":";
    }

    public static String configCalibrationEnergy(int i) {
        return CONFIG.CONF_CALIBRATION_ENERGY + i + ":";
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
}

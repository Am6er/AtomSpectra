package org.fe57.atomspectra;

/**
 * An asynchronous spectrum source: microphone, USB spectrometer or wireless device.
 * Abstraction used to unify device communication protocol related to spectrum data flow:
 * 1. connecting to device
 * 2. running/stopping/resetting spectrum acquisition
 * 3. storing calibration
 * 4. defining data format and intents for asynchronous operation
 */
public interface SpectrumSource {
    // source type codes
    int TYPE_NONE = 0;
    int TYPE_AUDIO = 1;
    int TYPE_SPECTRA_PRO = 2;
    int TYPE_BLUZ = 3;

    // status codes
    public static final int STATUS_DISCONNECTED = 0; // initial status with no physical connection
    public static final int STATUS_CONNECTING = 1; // physical connection established
    public static final int STATUS_CONNECTED_IDLE = 2; // device paused spectrum collection
    public static final int STATUS_CONNECTED_COLLECTING = 3; // device is collecting spectrum
    public static final int STATUS_CONNECTED_EXECUTING_COMMAND = 4; // device is executing command
    public static final int STATUS_CONNECTED_COMMAND_FAILED = 5; // last command errored out or timed out
    public static final int STATUS_CLOSED = 6; // source is closed and couldn't be reused anymore

    // op codes
    int OP_CONNECT = 1;
    int OP_START = 2;
    int OP_STOP = 3;
    int OP_SHOW = 4;
    int OP_RESET = 5;
    int OP_CALIBRATION_SAVE = 6;
    int OP_SETTINGS_SAVE = 7;

    /** Human-readable name for an OP_* code, for logging. */
    static String opName(int op) {
        switch (op) {
            case OP_CONNECT: return "OP_CONNECT";
            case OP_START: return "OP_START";
            case OP_STOP: return "OP_STOP";
            case OP_SHOW: return "OP_SHOW";
            case OP_RESET: return "OP_RESET";
            case OP_CALIBRATION_SAVE: return "OP_CALIBRATION_SAVE";
            case OP_SETTINGS_SAVE: return "OP_SETTINGS_SAVE";
            default: return "op=" + op;
        }
    }

    // failure reasons carried by the error intent
    int REASON_ERROR = 1;
    int REASON_TIMEOUT = 2;
    int REASON_PERMISSION = 3; // the system permission needed to use the device is missing or was denied

    // +++ extras common to every reply intent +++
    String EXTRA_SOURCE_INPUT_TYPE = "org.fe57.atomspectra.EXTRA_SOURCE_INPUT_TYPE";
    String EXTRA_SOURCE_INSTANCE_ID = "org.fe57.atomspectra.EXTRA_SOURCE_INSTANCE_ID";

    // +++ ACTION_SOURCE_READY +++
    String ACTION_SOURCE_READY = "org.fe57.atomspectra.ACTION_SOURCE_READY";
    String EXTRA_SOURCE_STATUS = "org.fe57.atomspectra.EXTRA_SOURCE_STATUS";
    String EXTRA_SOURCE_DEVICE_ID = "org.fe57.atomspectra.EXTRA_SOURCE_DEVICE_ID";
    String EXTRA_SOURCE_CALIBRATION_COEFFS = "org.fe57.atomspectra.EXTRA_SOURCE_CALIBRATION_COEFFS";
    String EXTRA_SOURCE_CHANNEL_COUNT = "org.fe57.atomspectra.EXTRA_SOURCE_CHANNEL_COUNT";

    // +++ ACTION_SOURCE_STATUS +++
    String ACTION_SOURCE_STATUS = "org.fe57.atomspectra.ACTION_SOURCE_STATUS";

    // +++ ACTION_SOURCE_ERROR +++
    String ACTION_SOURCE_ERROR = "org.fe57.atomspectra.ACTION_SOURCE_ERROR";
    String EXTRA_SOURCE_ERROR_OP = "org.fe57.atomspectra.EXTRA_SOURCE_ERROR_OP";
    String EXTRA_SOURCE_ERROR_REASON = "org.fe57.atomspectra.EXTRA_SOURCE_ERROR_REASON";
    String EXTRA_SOURCE_ERROR_TEXT = "org.fe57.atomspectra.EXTRA_SOURCE_ERROR_TEXT";

    // +++ ACTION_SOURCE_DISCONNECTED +++
    String ACTION_SOURCE_DISCONNECTED = "org.fe57.atomspectra.ACTION_SOURCE_DISCONNECTED";
    String EXTRA_SOURCE_DISCONNECT_REASON = "org.fe57.atomspectra.EXTRA_SOURCE_DISCONNECT_REASON";

    // +++ ACTION_SOURCE_DATA +++
    String ACTION_SOURCE_DATA = "org.fe57.atomspectra.ACTION_SOURCE_DATA";
    String EXTRA_SOURCE_DATA_HISTOGRAM = "org.fe57.atomspectra.EXTRA_SOURCE_DATA_HISTOGRAM";
    String EXTRA_SOURCE_DATA_RECORDING_TIME = "org.fe57.atomspectra.EXTRA_SOURCE_DATA_RECORDING_TIME";
    String EXTRA_SOURCE_DATA_CP1S = "org.fe57.atomspectra.EXTRA_SOURCE_DATA_CPS";

    // +++ ACTION_SOURCE_DATA_SKIPPED +++
    String ACTION_SOURCE_DATA_SKIPPED = "org.fe57.atomspectra.ACTION_SOURCE_DATA_SKIPPED";
    String EXTRA_SOURCE_DATA_SKIPPED_REASON = "org.fe57.atomspectra.EXTRA_SOURCE_DATA_SKIPPED_REASON";

    // +++ ACTION_SOURCE_CALIBRATION_SAVED +++
    String ACTION_SOURCE_CALIBRATION_SAVED = "org.fe57.atomspectra.ACTION_SOURCE_CALIBRATION_SAVED";

    /**
     * Connect and hand-shake. Load necessary device metadata like calibration, device id etc. Success is opened intent.
     * A device that is not there yet is not an error: the source stays disconnected, waits for it and connects when it appears.
     * The same wait applies after the device is lost. A failed hand-shake is an error reply and the source stops trying
     * until the next requestConnect().
     */
    void requestConnect();

    /** Request current data state. Success is single data intent. */
    void requestShowData();

    /** Begin acquisition. Success is data intents starting to arrive. */
    void requestStart();

    /** Halt acquisition. Success is data intents stopping. */
    void requestStop();

    /** Clear the device-side spectrum. */
    void requestReset();

    /** Save calibration to device. */
    void requestSaveCalibration(double[] coeffs);

    /**
     * The application preferences (shared app settings, not settings stored on the device) have changed.
     * A connected source re-reads the ones it depends on. Default is no-op.
     */
    default void onAppPreferencesChanged() {
    }

    /** Close connection and free up all the resources, spectrum source couldn't be used after close. */
    void close();

    /** Whether the source can start from a histogram supplied by {@link #setInitialHistogram}. */
    boolean supportsInitialHistogram();

    /**
     * Replace the source's histogram and recording time; the next acquisition continues from them.
     * Valid while connected and not collecting, otherwise an error reply.
     */
    void setInitialHistogram(long[] histogram, double recordingTimeSec);

    /** Unique id of this source object, carried by every reply intent. */
    int instanceId();

    /** Source input type. */
    int inputType();

    /** Device status. */
    int status();

    /**
     * The last error reply, null when there is none. Dropped once the source works again
     * (ready, or a successful return to idle or collecting).
     */
    SourceError lastError();

    /**
     * Channel count. Valid and consistent with {@link #calibration()} from the ready reply on, constant afterwards.
     * The ready reply carries it as {@link #EXTRA_SOURCE_CHANNEL_COUNT}.
     */
    int channelCount();

    /** Device identification for this source. */
    String deviceId();

    /** Device calibration coefficients. */
    double[] calibration();

    // TODO: add specific device stored settings abstraction, calibration is separate as it is required for a spectrum, other settings are optional
    // we might have both software and hardware settings and have to distinguish them
    // for instance, all audio processing settings will be bound to a specific audio device, partial histogram allowed for pro, last calibration channel etc.
}

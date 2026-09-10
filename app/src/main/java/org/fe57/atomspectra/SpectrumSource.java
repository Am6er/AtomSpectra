package org.fe57.atomspectra;

/**
 * An asynchronous spectrum source: microphone, USB spectrometer or, later, a wireless device.
 * The service owns exactly one at a time.
 *
 * Control flows service -> source as method calls, data and answers flow source -> service as
 * intents. Every request returns nothing and never blocks on a device; the answer arrives as one
 * of the reply intents declared below.
 *
 * requestStart(), requestStop() and requestReset() have no success intent - success is observable
 * because data intents start, stop, or resume from a cleared baseline. Only failure is reported.
 */
public interface SpectrumSource {
    // op codes carried by the error intent so the service can tell which request failed
    int OP_OPEN = 1;
    int OP_START = 2;
    int OP_STOP = 3;
    int OP_RESET = 4;
    int OP_STATUS = 5;
    int OP_DEVICE_META = 6;

    // failure reasons carried by the error intent
    int REASON_ERROR = 1;
    int REASON_TIMEOUT = 2;

    // +++ extras common to every reply intent +++
    String EXTRA_SOURCE_INPUT_TYPE = "org.fe57.atomspectra.EXTRA_SOURCE_INPUT_TYPE";

    // +++ ACTION_INPUT_STATUS +++
    String EXTRA_STATUS_COLLECTING = "org.fe57.atomspectra.EXTRA_STATUS_COLLECTING";

    // +++ ACTION_INPUT_METADATA +++
    // calibration coefficients as read from the device, null when the device has none to give
    String EXTRA_META_CALIBRATION_COEFFS = "org.fe57.atomspectra.EXTRA_META_CALIBRATION_COEFFS";
    String EXTRA_META_CALIBRATION_CHECKSUM_VALID = "org.fe57.atomspectra.EXTRA_META_CALIBRATION_CHECKSUM_VALID";
    // device identification, without any input-type prefix
    String EXTRA_META_DEVICE = "org.fe57.atomspectra.EXTRA_META_DEVICE";

    // +++ ACTION_INPUT_ERROR +++
    String EXTRA_ERROR_OP = "org.fe57.atomspectra.EXTRA_ERROR_OP";
    String EXTRA_ERROR_REASON = "org.fe57.atomspectra.EXTRA_ERROR_REASON";
    // what the failure is called in the UI; the source supplies it so the service never has to know
    // the command grammar of any particular device
    String EXTRA_ERROR_LABEL = "org.fe57.atomspectra.EXTRA_ERROR_LABEL";

    // +++ ACTION_INPUT_DISCONNECTED +++
    String EXTRA_DISCONNECT_REASON = "org.fe57.atomspectra.EXTRA_DISCONNECT_REASON";

    // +++ ACTION_INPUT_HAS_DATA +++
    // a report the source knows to be junk: it still updates the histogram, but the service derives
    // no cps interval or dose rate from it
    String EXTRA_DATA_BOOL_UNRELIABLE = "org.fe57.atomspectra.EXTRA_DATA_BOOL_UNRELIABLE";

    /** Connect and hand-shake. Completes when the metadata intent lands. */
    void requestOpen();

    /** Begin acquisition. Success is data intents starting to arrive. */
    void requestStart();

    /** Halt acquisition. Success is data intents stopping. */
    void requestStop();

    /** Clear the device-side spectrum. The first report after a reset is always unreliable. */
    void requestReset();

    /** Ask whether the source is collecting; answered by the status intent. */
    void requestStatus();

    /** Ask for calibration and device info; answered by the metadata intent. */
    void requestDeviceMeta();

    /** Release the source locally. Synchronous: nothing waits on a device answer. */
    void close();

    boolean isOpened();

    /** One of the AtomSpectraService.INPUT_* ids. */
    int inputTypeId();

    /** Device identification for this source, without any input-type prefix. */
    String deviceInfo();
}

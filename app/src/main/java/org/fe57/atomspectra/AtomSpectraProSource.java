package org.fe57.atomspectra;

import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.Process;
import android.os.SystemClock;

import androidx.annotation.NonNull;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;
import java.util.zip.CRC32;

public class AtomSpectraProSource implements SerialInputOutputManager.Listener, SpectrumSource {
    private static final int CIRCULAR_BUFFER_SIZE = 128 * 1024; // ~2 seconds of data at max baud rate (600kbps / 8N1 = 60KB/s)
    private static final int SERIAL_MANAGER_READ_BUFFER_SIZE = 4 * 1024;
    private static final int SERIAL_MANAGER_READ_QUEUE_SIZE = 4;
    private static final int SERIAL_MANAGER_WRITE_TIMEOUT = 1000;

    // time the device needs to settle between a close and the following open
    public static final int USB_WAIT_DEVICE = 600;
    // number of registers in a "-cal" answer; the last one carries the device metadata
    private static final int CAL_REGISTERS = 40;
    private static final int CAL_REGISTER_DEVICE_ID = 39;
    private static final int MINIMAL_FIRMWARE_VERSION = 11;
    // number of histogram bins the device protocol always uses, independent of the app's display settings
    private static final int SPECTRA_PRO_HIST_POINTS = 8192;

    // ids of the commands this source issues on its own behalf; answers to them are routed into the
    private final static String OP_ID_CHECK_FIRMWARE = "Check firmware version (-inf)";
    private final static String OP_ID_LOAD_CALIBRATION_AND_ID = "Load calibration and serial number (-cal)";
    private final static String OP_ID_ENABLE_SPECTROMETER_MODE = "Enable spectrometer mode (-mode 0)";
    private final static String OP_ID_INITIAL_START_COLLECTING = "Initial start collecting just after connect (-sta)";
    private final static String OP_ID_START_COLLECTING = "Start collecting (-sta)";
    private final static String OP_ID_STOP_COLLECTING = "Stop collecting (-sto)";
    private final static String OP_ID_CHECK_STATUS = "Check status (-stt)";
    private final static String OP_ID_RESET_HISTOGRAM = "Reset histogram (-rst)";
    private final static String OP_ID_INITIAL_SHOW_HISTOGRAM = "Initial load histogram just after connect (-sho)";
    private final static String OP_ID_SHOW_HISTOGRAM = "Load histogram (-sho)";
    private final static String OP_ID_SAVE_CALIBRATION = "Save calibration (-cal)";
    private final static String OP_ID_RESYNC_STATUS = "Resync status (-stt)";
    private final static String OP_ID_WATCHDOG_RESTART_COLLECTING = "Restart collecting after watchdog reconnect (-sta)";

    private volatile int status = SpectrumSource.STATUS_DISCONNECTED;
    private UsbDevice device;
    private int firmwareVersion = 0;
    private volatile String deviceId = null;
    private volatile double[] calibrationCoeffs = new double[CALIBRATION_COEFFICIENTS];

    // Guards the connect/disconnect/reconnect lifecycle (driver, connection, port, manager,
    // processingThread, device) as one atomic sequence, so a watchdog-triggered reconnect can
    // never interleave with a caller-triggered connect/close. Always the outermost lock relative
    // to syncCommand/batchSync/watchdogSync/errorReportingLock - never taken while already
    // holding one of those - to avoid lock-order inversion.
    private final Object connectionLock = new Object();
    private final Object batchSync = new Object();
    // keyed by batchId, which is also reused as the id on each CommandCode in the batch
    private final HashMap<String, CommandBatch> pendingBatches = new HashMap<>();
    private UsbSerialDriver driver;
    private UsbDeviceConnection connection;
    private UsbSerialPort port;
    private SerialInputOutputManager manager;
    private volatile Context context;

    // circular buffer for incoming data
    // head == end means empty buffer, (end + 1) % size == head means full buffer
    private final byte[] inputData = new byte[CIRCULAR_BUFFER_SIZE];
    private volatile int inputDataHead = 0; // first meaningful byte in inputData
    private volatile int inputDataEnd = 0; // first free byte in inputData

    private final Object circularBufferSync = new Object();
    private Thread processingThread = null;
    private Handler asyncTasksHandler = null;

    public long[] histogram = new long[SPECTRA_PRO_HIST_POINTS];
    private final boolean[] histBinsReceived = new boolean[SPECTRA_PRO_HIST_POINTS];
    private int histBinsMissing = SPECTRA_PRO_HIST_POINTS;
    // start pos of the last CODE_HIST chunk; used to detect the start of a new histogram
    // sweep so per-sweep completeness cannot leak across a DATA packet lost to a CRC error
    private int lastHistStartPos = -1;

    // read once per connect (not per packet) from preferences
    private volatile boolean allowIncompleteData = false;

    // HACK! when started, the device sends wrong data for at least 1 second
    // as it cannot fit a full spectrum into the time left before the next DATA packet
    // skipping 2 just in case
    private static final int UNRELIABLE_DATA_REPORTS = 2;
    private int unreliableDataReportsLeft = 0;

    // in rare cases spectrum data receiving randomly stops: the physical device keeps working, but
    // android delivers no data through the serial port and every command ends with a timeout. This
    // timer checks that data keeps arriving and restarts the serial interface when it does not.
    private static final int DATA_WATCHDOG_INTERVAL_SECONDS = 30;
    private final Object watchdogSync = new Object();
    private Timer dataWatchdogTimer = null;

    // serial data error counting
    private static final long SUPPRESSION_DURATION_MINUTES = 2;
    private static final boolean DEBUG_LOG = false; // set to true only briefly for debugging - it will spam a lot of messages in logs and may cause performance issues
    private final HashMap<Integer, Integer> serialPacketErrorCrcByCode = new HashMap<>();
    private final HashMap<Integer, Integer> serialPacketErrorEscapingByCode = new HashMap<>();
    private final HashMap<Integer, Integer> serialPacketErrorMinLengthByCode = new HashMap<>();
    private final Object errorReportingLock = new Object();
    private long errorDetectedEpisodeStartTime = 0;
    private boolean errorLoggingSuppressed = false;
    private boolean errorsOccurredDuringLogSuppression = false;

    private final Runnable errorReportingRunnable = new Runnable() {
        @Override
        public void run() {
            final Context ctx = AtomSpectraProSource.this.context;
            if (ctx == null) {
                return;
            }

            String summary;
            long startTime;
            boolean hadErrors;
            synchronized (AtomSpectraProSource.this.errorReportingLock) {
                summary = AtomSpectraProSource.this.formatErrorSummary();
                startTime = AtomSpectraProSource.this.errorDetectedEpisodeStartTime;
                hadErrors = AtomSpectraProSource.this.errorsOccurredDuringLogSuppression;
            }

            if (hadErrors) {
                log(ctx, ctx.getString(R.string.log_serial_errors_ongoing, summary, formatTime(startTime), SUPPRESSION_DURATION_MINUTES));
                synchronized (AtomSpectraProSource.this.errorReportingLock) {
                    AtomSpectraProSource.this.errorsOccurredDuringLogSuppression = false;
                }
                AtomSpectraProSource.this.asyncTasksHandler.postDelayed(AtomSpectraProSource.this.errorReportingRunnable, SUPPRESSION_DURATION_MINUTES * 60 * 1000);
            } else {
                log(ctx, ctx.getString(R.string.log_serial_errors_resolved, SUPPRESSION_DURATION_MINUTES, summary, formatTime(startTime)));
                AtomSpectraProSource.this.resetErrorSuppression();
            }
        }
    };

    private static final short PACKET_BEGIN = 0xFF;
    private static final short PACKET_START = 0xFE;
    private static final short PACKET_ESC = 0xFD;
    private static final short PACKET_END = 0xA5;

    public static final int CODE_NONE = 0x00;
    public static final int CODE_HIST = 0x01;
    public static final int CODE_SCOPE = 0x02;
    public static final int CODE_TEXT = 0x03;
    public static final int CODE_DATA = 0x04;

    public final static String COMMAND_RESULT_OK = "-ok\r\n";
    private final static String COMMAND_RESULT_OK2 = "ok\r\n";  // legacy
    public final static String COMMAND_RESULT_ERR = "-err\r\n";
    public final static String COMMAND_RESULT_TIMEOUT = "-timeout\r\n";
    public final static String COMMAND_RESULT_OK_COLLECTING = "-ok collecting\r\n";

    //Constructor
    public AtomSpectraProSource(Context context, UsbDevice device) {
        this.context = context;
        this.device = device;
        this.asyncTasksHandler = new Handler(context.getMainLooper());
    }

    @Override
    public void requestConnect() {
        synchronized (this.connectionLock) {
            if (this.rejectIfClosed(SpectrumSource.OP_CONNECT)) return;

            if (this.isOpened()) {
                this.emitError(SpectrumSource.OP_CONNECT, SpectrumSource.REASON_ERROR, "Already connected");
                return;
            }

            if (this.status == SpectrumSource.STATUS_CONNECTING) {
                this.emitError(SpectrumSource.OP_CONNECT, SpectrumSource.REASON_ERROR, "Connection already in progress");
                return;
            }

            this.allowIncompleteData = PrefHelper.getASSharedPreferences(this.context)
                    .getBoolean(Constants.CONFIG.CONF_ALLOW_PARTIAL_HISTOGRAM, Constants.ALLOW_PARTIAL_HISTOGRAM_DEFAULT);

            this.setAndEmitStatus(SpectrumSource.STATUS_CONNECTING);
            String openError = this.openPort();
            if (openError != null) {
                this.setAndEmitStatus(SpectrumSource.STATUS_DISCONNECTED);
                this.emitError(SpectrumSource.OP_CONNECT, SpectrumSource.REASON_ERROR, openError);
                return;
            }

            this.enqueueTextCommand("-inf", OP_ID_CHECK_FIRMWARE, SpectrumSource.OP_CONNECT);
            this.enqueueTextCommand("-mode 0", OP_ID_ENABLE_SPECTROMETER_MODE, SpectrumSource.OP_CONNECT);
            this.enqueueTextCommand("-cal", OP_ID_LOAD_CALIBRATION_AND_ID, SpectrumSource.OP_CONNECT);
            this.enqueueTextCommand("-stt", OP_ID_CHECK_STATUS, SpectrumSource.OP_CONNECT);
        }
    }

    @Override
    public void requestShowData() {
        this.requestShowData(false);
    }

    @Override
    public void requestStart() {
        this.requestStart(false);
    }

    @Override
    public void requestStop() {
        if (this.rejectIfDisconnected(SpectrumSource.OP_STOP)) return;

        this.cancelDataWatchdog();
        this.enqueueTextCommand("-sto", OP_ID_STOP_COLLECTING, SpectrumSource.OP_STOP);
    }

    @Override
    public void requestReset() {
        if (this.rejectIfDisconnected(SpectrumSource.OP_RESET)) return;

        this.armUnreliableDataWindow();
        this.enqueueTextCommand("-rst", OP_ID_RESET_HISTOGRAM, SpectrumSource.OP_RESET);
    }

    @Override
    public void requestSaveCalibration(double[] coeffs) {
        if (this.rejectIfDisconnected(SpectrumSource.OP_CALIBRATION_SAVE)) return;

        this.executeCommandBatch(buildCalibrationCommands(coeffs), OP_ID_SAVE_CALIBRATION, SpectrumSource.OP_CALIBRATION_SAVE, (success, failureReason) -> {
            if (success) {
                this.broadcastReply(new Intent(SpectrumSource.ACTION_SOURCE_CALIBRATION_SAVED));
                this.requestStatusResync(SpectrumSource.OP_CALIBRATION_SAVE);
            } else {
                this.setAndEmitStatus(SpectrumSource.STATUS_CONNECTED_COMMAND_FAILED);
                this.emitError(SpectrumSource.OP_CALIBRATION_SAVE, failureReason, "Save calibration (-cal)");
            }
        });
    }

    @Override
    public void close() {
        synchronized (this.connectionLock) {
            this.cancelDataWatchdog();
            this.stopProcessingThread();
            this.cancelAsyncTasks();
            this.closePortAndConnection();
            this.stopUsbManager();
            this.reportIncompleteCommandsAsFailed();

            this.setAndEmitStatus(SpectrumSource.STATUS_CLOSED);
            this.context = null;
        }
    }

    @Override
    public int inputType() {
        return SpectrumSource.TYPE_SPECTRA_PRO;
    }

    @Override
    public int status() {
        return this.status;
    }

    @Override
    public int channelCount() {
        return SPECTRA_PRO_HIST_POINTS;
    }

    @Override
    public String deviceId() {
        return this.deviceId;
    }

    @Override
    public double[] calibration() {
        return Arrays.copyOf(this.calibrationCoeffs, this.calibrationCoeffs.length);
    }

    private void requestStart(boolean isFirstStartAfterConnect) {
        if (this.rejectIfDisconnected(isFirstStartAfterConnect ? SpectrumSource.OP_CONNECT : SpectrumSource.OP_START)) return;

        this.armUnreliableDataWindow();
        this.enqueueTextCommand("-sta", isFirstStartAfterConnect ? OP_ID_INITIAL_START_COLLECTING : OP_ID_START_COLLECTING,
                isFirstStartAfterConnect ? SpectrumSource.OP_CONNECT : SpectrumSource.OP_START);
    }

    private void requestShowData(boolean isFirstShowAfterConnect) {
        if (this.rejectIfDisconnected(isFirstShowAfterConnect ? SpectrumSource.OP_CONNECT : SpectrumSource.OP_SHOW)) return;

        this.enqueueTextCommand("-sho", isFirstShowAfterConnect ? OP_ID_INITIAL_SHOW_HISTOGRAM : OP_ID_SHOW_HISTOGRAM,
                isFirstShowAfterConnect ? SpectrumSource.OP_CONNECT : SpectrumSource.OP_SHOW);
    }

    private boolean rejectIfClosed(int op) {
        if (this.status == SpectrumSource.STATUS_CLOSED) {
            this.emitError(op, SpectrumSource.REASON_ERROR, "Request to an already closed source");
            return true;
        }
        return false;
    }

    private boolean rejectIfDisconnected(int op) {
        if (this.rejectIfClosed(op)) return true;

        if (this.status == SpectrumSource.STATUS_DISCONNECTED || this.status == SpectrumSource.STATUS_CONNECTING) {
            this.emitError(op, SpectrumSource.REASON_ERROR, "Request to a disconnected source");
            return true;
        }

        return false;
    }

    private void reportIncompleteCommandsAsFailed() {
        LinkedList<CommandCode> failedCommands = new LinkedList<>();
        synchronized (this.syncCommand) {
            while (!this.commands.isEmpty()) {
                CommandCode failed = this.commands.pop();
                failedCommands.add(failed);
                log(this.context, "Serial command failed due close() call: " + new String(failed.command));
            }
            this.answerNumber = 0;
        }
        for (CommandCode failed : failedCommands) {
            this.handleDeviceAnswer(failed, COMMAND_RESULT_ERR);
        }
    }

    private void stopUsbManager() {
        if (this.manager != null) {
            this.manager.stop();
        }
        this.manager = null;
    }

    private void closePort() {
        if (this.port != null && this.port.isOpen()) {
            try {
                this.port.close();
            } catch (Exception ignore) {
                // nothing
            }
        }
        this.port = null;
    }

    // closing the port does not release the underlying UsbDeviceConnection handle
    private void closeConnection() {
        if (this.connection != null) {
            try {
                this.connection.close();
            } catch (Exception ignore) {
                // nothing
            }
        }
        this.connection = null;
    }

    private void closePortAndConnection() {
        this.closePort();
        this.closeConnection();
    }

    /**
     * Opens the driver/connection/port for {@link #device} and starts the serial IO manager and
     * processing thread. Returns null on success, or an error message on failure (already logged,
     * with any partially-opened resources cleaned up) - the caller decides how to report it.
     */
    private String openPort() {
        this.driver = UsbSerialProber.getDefaultProber().probeDevice(this.device);
        if (this.driver == null) {
            log(this.context, "USB connection failed: driver is not available");
            return "USB driver is not available";
        }
        UsbManager usbManager = (UsbManager) this.context.getSystemService(Context.USB_SERVICE);
        if (usbManager == null) {
            log(this.context, "USB connection failed: UsbManager is not available");
            return "UsbManager is not available";
        }
        try {
            this.connection = usbManager.openDevice(this.driver.getDevice());
            if (this.connection == null) {
                log(this.context, "USB connection failed: could not open device");
                return "Could not open device";
            }
        } catch (Exception e) {
            log(this.context, "USB connection failed: " + e.getMessage());
            return "Could not open device: " + e.getMessage();
        }
        this.port = this.driver.getPorts().get(0);
        try {
            this.port.open(this.connection);
            this.port.setParameters(600000, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            this.inputDataHead = 0;
            this.inputDataEnd = 0;
            this.manager = new SerialInputOutputManager(this.port, this);
            this.manager.setReadBufferSize(SERIAL_MANAGER_READ_BUFFER_SIZE);
            this.manager.setReadQueue(SERIAL_MANAGER_READ_QUEUE_SIZE);
            this.manager.start();
            this.processingThread = new Thread(this::processBufferLoop, "AtomSpectra-Packet-Processor");
            this.processingThread.setDaemon(true);
            this.processingThread.start();
        } catch (Exception e) {
            log(this.context, "USB port setup failed: " + e.getMessage());
            this.closePortAndConnection();
            return "USB port setup failed: " + e.getMessage();
        }
        return null;
    }

    private void cancelAsyncTasks() {
        if (this.asyncTasksHandler != null) {
            this.asyncTasksHandler.removeCallbacksAndMessages(null);
        }
        this.asyncTasksHandler = null;
    }

    // test if port is actually working
    private boolean isOpened() {
        return (this.port != null) && (this.port.isOpen());
    }

    // marks the whole spectrum as not-yet-received for a fresh acquisition sweep
    private void resetHistogramCompleteness() {
        Arrays.fill(this.histBinsReceived, false);
        this.histBinsMissing = SPECTRA_PRO_HIST_POINTS;
        this.lastHistStartPos = -1;
    }

    private void armUnreliableDataWindow() {
        this.unreliableDataReportsLeft = UNRELIABLE_DATA_REPORTS;
    }

    private static final String LOG_TAG = "Spectra Pro";

    private static void log(Context ctx, String message) {
        AtomSpectraLog.addMessage(ctx, LOG_TAG, message);
    }

    // +++ reply intents +++

    private void broadcastReply(@NonNull Intent intent) {
        final Context ctx = this.context;
        if (ctx == null) {
            return;
        }

        ctx.sendBroadcast(intent
                .setPackage(Constants.PACKAGE_NAME)
                .putExtra(EXTRA_SOURCE_INPUT_TYPE, this.inputType()));
    }

    private void emitError(int op, int reason, String text) {
        this.broadcastReply(new Intent(SpectrumSource.ACTION_SOURCE_ERROR)
                .putExtra(SpectrumSource.EXTRA_SOURCE_ERROR_OP, op)
                .putExtra(SpectrumSource.EXTRA_SOURCE_ERROR_REASON, reason)
                .putExtra(SpectrumSource.EXTRA_SOURCE_ERROR_TEXT, text));
    }

    private void emitDisconnected(String reason) {
        this.broadcastReply(new Intent(SpectrumSource.ACTION_SOURCE_DISCONNECTED)
                .putExtra(SpectrumSource.EXTRA_SOURCE_DISCONNECT_REASON, reason));
    }

    private void setAndEmitStatus(int status) {
        if (this.status != status) {
            this.status = status;
            this.broadcastReply(new Intent(SpectrumSource.ACTION_SOURCE_STATUS)
                    .putExtra(SpectrumSource.EXTRA_SOURCE_STATUS, this.status));
        }
    }

    private void emitReady() {
        this.broadcastReply(new Intent(SpectrumSource.ACTION_SOURCE_READY)
                .putExtra(SpectrumSource.EXTRA_SOURCE_CALIBRATION_COEFFS, this.calibrationCoeffs)
                .putExtra(SpectrumSource.EXTRA_SOURCE_STATUS, this.status)
                .putExtra(SpectrumSource.EXTRA_SOURCE_DEVICE_ID, this.deviceId));
    }

    private void handleDeviceAnswer(@NonNull CommandCode cmd, String answer) {
        final Context ctx = this.context;
        if (ctx == null) {
            return;
        }

        if (this.trackBatchAnswer(cmd.id, answer)) {
            return;
        }

        String command = new String(cmd.command);
        int op = cmd.op;

        if (OP_ID_WATCHDOG_RESTART_COLLECTING.equals(cmd.id)
                && (COMMAND_RESULT_ERR.equals(answer) || COMMAND_RESULT_TIMEOUT.equals(answer))) {
            // TODO: decide if tear down (closePortAndConnection/stopUsbManager/stopProcessingThread)
            // is actually needed here instead of relying on the caller to call close().
            log(ctx, "Watchdog reconnect failed: \"" + command.trim() + "\" " + answer.trim());
            this.emitDisconnected("Reconnect failed");
            return;
        }

        if (COMMAND_RESULT_ERR.equals(answer)) {
            log(ctx, "Command \"" + command.trim() + "\" (" + SpectrumSource.opName(op) + ") failed: " + answer.trim());
            this.setAndEmitStatus(SpectrumSource.STATUS_CONNECTED_COMMAND_FAILED);
            this.emitError(op, REASON_ERROR, command);
            return;
        }
        if (COMMAND_RESULT_TIMEOUT.equals(answer)) {
            log(ctx, "Command \"" + command.trim() + "\" (" + SpectrumSource.opName(op) + ") timed out");
            this.setAndEmitStatus(SpectrumSource.STATUS_CONNECTED_COMMAND_FAILED);
            this.emitError(op, REASON_TIMEOUT, command);
            return;
        }

        if (DEBUG_LOG) {
            log(ctx, "Command \"" + command.trim() + "\" (" + SpectrumSource.opName(op) + ") succeeded: " + answer.trim());
        }

        switch (cmd.id) {
            case OP_ID_CHECK_FIRMWARE:
                this.checkDeviceVersion(answer);
                break;
            case OP_ID_LOAD_CALIBRATION_AND_ID:
                this.readDeviceCalibrationAndId(answer);
                break;
            case OP_ID_CHECK_STATUS:
                if (COMMAND_RESULT_OK_COLLECTING.equals(answer)) {
                    this.requestStart(true);
                } else {
                    // TODO: check with real device if it works, otherwise just report ready
                    this.requestShowData(true);
                }
                break;
            case OP_ID_INITIAL_START_COLLECTING:
            case OP_ID_START_COLLECTING:
            case OP_ID_WATCHDOG_RESTART_COLLECTING:
                this.setAndEmitStatus(SpectrumSource.STATUS_CONNECTED_COLLECTING);
                this.resetHistogramCompleteness();
                this.flushErrorSuppressionLog();
                this.restartDataWatchdog();
                if (cmd.id == OP_ID_INITIAL_START_COLLECTING) {
                    this.emitReady();
                }
                break;
            case OP_ID_INITIAL_SHOW_HISTOGRAM:
                this.setAndEmitStatus(SpectrumSource.STATUS_CONNECTED_IDLE);
                this.emitReady();
                break;
            case OP_ID_SHOW_HISTOGRAM:
                this.requestStatusResync(SpectrumSource.OP_SHOW);
                break;
            case OP_ID_STOP_COLLECTING:
                this.setAndEmitStatus(SpectrumSource.STATUS_CONNECTED_IDLE);
                this.flushErrorSuppressionLog();
                break;
            case OP_ID_RESET_HISTOGRAM:
                this.resetHistogramCompleteness();
                Arrays.fill(this.histogram, 0);
                this.requestStatusResync(SpectrumSource.OP_RESET);
                break;
            case OP_ID_ENABLE_SPECTROMETER_MODE:
                // nothing to do for now
                break;
            case OP_ID_RESYNC_STATUS:
                this.resyncStatus(answer);
                break;
            default:
                log(ctx, "Unexpected command: " + cmd.id);
                break;
        }
    }

    private void requestStatusResync(int op) {
        this.enqueueTextCommand("-stt", OP_ID_RESYNC_STATUS, op);
    }

    private void resyncStatus(String answer) {
        this.setAndEmitStatus(COMMAND_RESULT_OK_COLLECTING.equals(answer)
                ? SpectrumSource.STATUS_CONNECTED_COLLECTING
                : SpectrumSource.STATUS_CONNECTED_IDLE);
    }

    /** Logs any errors accumulated while suppressed, then clears suppression state. */
    private void flushErrorSuppressionLog() {
        final Context ctx = this.context;
        if (ctx == null) return;

        boolean wasSuppressed;
        String summary;
        long startTime;
        synchronized (this.errorReportingLock) {
            wasSuppressed = this.errorLoggingSuppressed;
            summary = this.formatErrorSummary();
            startTime = this.errorDetectedEpisodeStartTime;
        }
        if (wasSuppressed) {
            log(ctx, ctx.getString(R.string.log_serial_errors_resolved, SUPPRESSION_DURATION_MINUTES, summary, formatTime(startTime)));
        }
        this.resetErrorSuppression();
    }

    private interface BatchCallback {
        void onBatchFinished(boolean success, int failureReason);
    }

    private static class CommandBatch {
        int remaining;
        boolean failed = false;
        int failureReason = SpectrumSource.REASON_ERROR;
        final BatchCallback callback;

        CommandBatch(int size, BatchCallback callback) {
            this.remaining = size;
            this.callback = callback;
        }
    }

    /** Sends commands as one all-or-nothing unit: callback fires once every answer is in, or as soon as one fails. */
    private void executeCommandBatch(@NonNull String[] commands, @NonNull String batchId, int op, @NonNull BatchCallback callback) {
        synchronized (this.batchSync) {
            this.pendingBatches.put(batchId, new CommandBatch(commands.length, callback));
        }
        for (String command : commands) {
            this.enqueueTextCommand(command, batchId, op);
        }
    }

    /**
     * Records one answer (ok, err or timeout) toward a pending batch. Returns false if cmdId is not a batch.
     * A failing answer aborts the batch immediately and drains its not-yet-sent commands, rather than letting
     * the rest continue against a connection that just proved unreliable.
     */
    private boolean trackBatchAnswer(String batchId, String answer) {
        boolean isFailureAnswer = !COMMAND_RESULT_OK.equals(answer);
        int reason = COMMAND_RESULT_TIMEOUT.equals(answer) ? SpectrumSource.REASON_TIMEOUT : SpectrumSource.REASON_ERROR;
        boolean finished;
        boolean failed;
        int failureReason;
        BatchCallback callback;
        synchronized (this.batchSync) {
            CommandBatch batch = this.pendingBatches.get(batchId);
            if (batch == null) return false;
            if (isFailureAnswer) {
                batch.failed = true;
                batch.failureReason = reason;
            }
            batch.remaining--;
            failed = batch.failed;
            finished = failed || batch.remaining == 0;
            failureReason = batch.failureReason;
            callback = batch.callback;
            if (finished) this.pendingBatches.remove(batchId);
        }
        if (finished && failed) {
            log(this.context, "Command batch \"" + batchId + "\" failed: " + answer.trim());
        }
        if (finished) {
            if (failed) this.drainQueuedCommands(batchId);
            callback.onBatchFinished(!failed, failureReason);
        }
        return true;
    }

    /** Removes any not-yet-sent commands belonging to a failed batch so they never reach the device. */
    private void drainQueuedCommands(String id) {
        synchronized (this.syncCommand) {
            Iterator<CommandCode> it = this.commands.iterator();
            while (it.hasNext()) {
                CommandCode dropped = it.next();
                if (id.equals(dropped.id)) {
                    log(this.context, "Dropping queued command \"" + new String(dropped.command).trim() + "\" from failed batch \"" + id + "\"");
                    it.remove();
                }
            }
        }
    }

    /** Warn about firmware this app is not able to drive correctly. */
    private void checkDeviceVersion(String answer) {
        final Context ctx = this.context;
        if (ctx == null) {
            return;
        }
        if (answer == null) {
            this.emitError(SpectrumSource.OP_CONNECT, SpectrumSource.REASON_ERROR, ctx.getString(R.string.log_usb_device_version_unknown));
            return;
        }

        String version = getParameter(answer, "VERSION");
        if (version == null) {
            this.emitError(SpectrumSource.OP_CONNECT, SpectrumSource.REASON_ERROR, ctx.getString(R.string.log_usb_device_version_unknown));
            return;
        }
        try {
            int parsedVersion = Integer.decode(version);
            this.firmwareVersion = parsedVersion;
            // firmware below MINIMAL_FIRMWARE_VERSION still operates, so this is advisory, not a connect failure
            if (parsedVersion < MINIMAL_FIRMWARE_VERSION)
                // TODO: think about firmware version as a hard gate, check how actual devices work
                ToastHelper.showToastAndLog(ctx, ctx.getString(R.string.usb_below_minimal_version, MINIMAL_FIRMWARE_VERSION));
        } catch (Exception ignored) {
            ToastHelper.showToastAndLog(ctx, R.string.log_usb_device_version_error);
        }
    }

    /** One "-cal" answer carries both the calibration and the device metadata. */
    private void readDeviceCalibrationAndId(String answer) {
        final Context ctx = this.context;
        if (ctx == null) {
            return;
        }
        if (answer == null) {
            this.emitError(SpectrumSource.OP_CONNECT, SpectrumSource.REASON_ERROR, ctx.getString(R.string.log_usb_answer_missing));
            return;
        }

        final String[] dataArray = answer.trim().split("\\s+");
        if (dataArray.length != CAL_REGISTERS) {
            this.emitError(SpectrumSource.OP_CONNECT, SpectrumSource.REASON_ERROR, ctx.getString(R.string.log_usb_answer_unexpected_registers, dataArray.length, CAL_REGISTERS));
            return;
        }
        if (!allWordsAreHex(dataArray)) {
            // a register that is not valid hex means the response itself is broken, not just an absent calibration
            this.emitError(SpectrumSource.OP_CONNECT, SpectrumSource.REASON_ERROR, ctx.getString(R.string.log_usb_answer_malformed_calibration));
            return;
        }

        // dataArray is already verified to hold CAL_REGISTERS valid hex words, well more than the
        // CALIBRATION_WORDS parseCalibrationAnswer needs, so that method can parse without re-checking
        CalibrationAnswer calibration = parseCalibrationAnswer(dataArray);
        if (calibration.checksumValid) {
            this.calibrationCoeffs = calibration.coeffs;
        } else {
            // a bad checksum means the device has no valid calibration stored, not a transient read error
            ToastHelper.showToastAndLog(ctx, R.string.cal_wrong_checksum);
            this.calibrationCoeffs = defaultLinearCalibrationCoeffs();
        }
        this.deviceId = dataArray[CAL_REGISTER_DEVICE_ID];
    }

    /** Linear calibration coefficients spanning the device's whole channel range over 0-3MeV, for when the device has none stored. */
    private static double[] defaultLinearCalibrationCoeffs() {
        double[] coeffs = new double[CALIBRATION_COEFFICIENTS];
        coeffs[1] = 3000.0 / (SPECTRA_PRO_HIST_POINTS + 1);
        return coeffs;
    }

    private static boolean allWordsAreHex(String[] words) {
        for (String word : words) {
            try {
                Long.parseUnsignedLong(word, 16);
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    // +++ data watchdog +++

    private void restartDataWatchdog() {
        synchronized (this.watchdogSync) {
            this.cancelDataWatchdog();
            if (this.status != SpectrumSource.STATUS_CONNECTED_COLLECTING) {
                return;
            }

            this.dataWatchdogTimer = new Timer();
            this.dataWatchdogTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    AtomSpectraProSource.this.dataWatchdogTask();
                }
            }, DATA_WATCHDOG_INTERVAL_SECONDS * 1000L);
        }
    }

    private void cancelDataWatchdog() {
        synchronized (this.watchdogSync) {
            if (this.dataWatchdogTimer != null) {
                this.dataWatchdogTimer.cancel();
                this.dataWatchdogTimer.purge();
                this.dataWatchdogTimer = null;
            }
        }
    }

    private void dataWatchdogTask() {
        // context becomes null only via close(); Timer.cancel() does not interrupt an already-running
        // task, so this task can still be executing after close() has nulled it out. The whole check+
        // teardown+reopen sequence runs under connectionLock so it can never interleave with a
        // caller-triggered close()/requestConnect() - a concurrent close() just makes the status/isOpened
        // check below observe STATUS_CLOSED and bail out once this task gets the lock.
        synchronized (this.connectionLock) {
            final Context ctx = this.context;
            if (ctx == null || this.status != SpectrumSource.STATUS_CONNECTED_COLLECTING || !this.isOpened()) {
                return;
            }

            // discard previous connection as unreliable
            this.reportIncompleteCommandsAsFailed();
            this.closePortAndConnection();
            this.stopUsbManager();
            this.stopProcessingThread();
            this.status = SpectrumSource.STATUS_DISCONNECTED;

            ToastHelper.showToastAndLog(ctx, ctx.getString(R.string.log_usb_watchdog_no_data_triggered, DATA_WATCHDOG_INTERVAL_SECONDS));
            UsbManager manager = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
            UsbDevice device = scanForSpectraProDevice(manager);
            boolean hasPermission = device != null && manager.hasPermission(device);

            if (device == null) {
                ToastHelper.showToastAndLog(ctx, R.string.log_usb_watchdog_device_not_found);
                this.emitDisconnected("Device not found");
                return;
            }

            if (!hasPermission) {
                ToastHelper.showToastAndLog(ctx, R.string.log_usb_watchdog_device_no_perm);
                this.emitDisconnected("No permission for device");
                return;
            }

            this.device = device;
            SystemClock.sleep(USB_WAIT_DEVICE);

            // TODO: Status still moves through EXECUTING_COMMAND -> COLLECTING like any other command - check if it harms
            // try reopen port to the same device
            String openError = this.openPort();
            if (openError != null) {
                ToastHelper.showToastAndLog(ctx, "Watchdog reconnect failed: " + openError);
                this.emitDisconnected("Watchdog reconnect failed: " + openError);
                return;
            }
            this.armUnreliableDataWindow();
            this.enqueueTextCommand("-sta", OP_ID_WATCHDOG_RESTART_COLLECTING, SpectrumSource.OP_START);
        }
    }

    // CRC-16 (MODBUS version)
    public static int crc16(int crc, byte data) {
        crc = crc ^ (data & 0xFF);
        for (int i = 0; i < 8; ++i) {
            if ((crc & 0x0001) != 0)
                crc = (crc >>> 1) ^ 0xA001;
            else
                crc = (crc >>> 1);
        }
        return crc;
    }

    public static long crc32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }

    // --- calibration wire format ------------------------------------------------------------
    // The device keeps the calibration as CALIBRATION_COEFFICIENTS doubles, transferred as two
    // 8-digit hex words each, followed by a CRC32 word over all of them.

    public static final int CALIBRATION_COEFFICIENTS = 5;
    private static final int CALIBRATION_WORDS = 2 * CALIBRATION_COEFFICIENTS + 1; // + crc word
    private static final int CALIBRATION_WORD_LENGTH = 8;

    /** Calibration coefficients read from the device, with the result of the checksum check. */
    // A checksum mismatch means the device has no valid calibration stored, not a transient read error.
    private static class CalibrationAnswer {
        public final double[] coeffs;
        public final boolean checksumValid;

        CalibrationAnswer(double[] coeffs, boolean checksumValid) {
            this.coeffs = coeffs;
            this.checksumValid = checksumValid;
        }
    }

    /**
     * Parse the leading CALIBRATION_WORDS of an already hex-validated "-cal" answer into
     * coefficients, with the result of the CRC32 checksum check.
     */
    private static CalibrationAnswer parseCalibrationAnswer(String[] words) {
        double[] coeffs = new double[CALIBRATION_COEFFICIENTS];
        StringBuilder combined = new StringBuilder();
        for (int i = 0; i < CALIBRATION_COEFFICIENTS; i++) {
            coeffs[i] = Double.longBitsToDouble(Long.parseUnsignedLong(words[2 * i] + words[2 * i + 1], 16));
            combined.append(words[2 * i]).append(words[2 * i + 1]);
        }
        long crc = Long.parseUnsignedLong(words[CALIBRATION_WORDS - 1], 16);
        return new CalibrationAnswer(coeffs, crc32(combined.toString().getBytes()) == crc);
    }

    /** Build the "-cal" commands that store the calibration on the device, CRC word included. */
    private static String[] buildCalibrationCommands(@NonNull double[] coeffs) {
        String[] commands = new String[CALIBRATION_WORDS];
        StringBuilder combined = new StringBuilder();
        for (int i = 0; i < CALIBRATION_COEFFICIENTS; i++) {
            String value = toCalibrationWords(i < coeffs.length ? coeffs[i] : 0.0);
            String high = value.substring(0, CALIBRATION_WORD_LENGTH);
            String low = value.substring(CALIBRATION_WORD_LENGTH);
            combined.append(high.toUpperCase(Locale.US)).append(low.toUpperCase(Locale.US));
            commands[2 * i] = String.format(Locale.US, "-cal %d %s", 2 * i, high);
            commands[2 * i + 1] = String.format(Locale.US, "-cal %d %s", 2 * i + 1, low);
        }
        String crc = Long.toHexString(crc32(combined.toString().getBytes()));
        while (crc.length() < CALIBRATION_WORD_LENGTH)
            crc = "0" + crc;
        commands[CALIBRATION_WORDS - 1] = String.format(Locale.US, "-cal %d %s", CALIBRATION_WORDS - 1, crc);
        return commands;
    }

    private static String toCalibrationWords(double value) {
        StringBuilder hex = new StringBuilder(Long.toHexString(Double.doubleToRawLongBits(value)));
        while (hex.length() < 2 * CALIBRATION_WORD_LENGTH)
            hex.insert(0, "0");
        return hex.toString();
    }

    // test if byte is needed to be escaped
    private static boolean isSpecialByte(byte b) {
        return (b == (byte) PACKET_BEGIN) || (b == (byte) PACKET_START) || (b == (byte) PACKET_END) || (b == (byte) PACKET_ESC);
    }

    private void addWithEscape(@NonNull ArrayList<Byte> array, byte b) {
        if (isSpecialByte(b)) {
            array.add((byte) PACKET_ESC);
            array.add((byte) ~b);
        } else {
            array.add(b);
        }
    }

    private static void incrementByCode(HashMap<Integer, Integer> map, int code) {
        Integer current = map.get(code);
        map.put(code, current == null ? 1 : current + 1);
    }

    private static String formatErrorsByCode(String label, HashMap<Integer, Integer> map) {
        StringBuilder sb = new StringBuilder(label).append("(");
        for (HashMap.Entry<Integer, Integer> entry : map.entrySet()) {
            sb.append(String.format("0x%02X:%d,", entry.getKey(), entry.getValue()));
        }
        sb.setLength(sb.length() - 1);
        sb.append(")");
        return sb.toString();
    }

    // Called under errorReportingLock
    private String formatErrorSummary() {
        StringBuilder sb = new StringBuilder();
        if (!this.serialPacketErrorCrcByCode.isEmpty()) {
            sb.append(formatErrorsByCode("CRC", this.serialPacketErrorCrcByCode));
        }
        if (!this.serialPacketErrorEscapingByCode.isEmpty()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(formatErrorsByCode("escaping", this.serialPacketErrorEscapingByCode));
        }
        if (!this.serialPacketErrorMinLengthByCode.isEmpty()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(formatErrorsByCode("minimum length", this.serialPacketErrorMinLengthByCode));
        }
        return sb.toString();
    }

    private static String formatTime(long epochMs) {
        return String.format("%tT", epochMs);
    }

    private void resetErrorSuppression() {
        synchronized (this.errorReportingLock) {
            this.errorLoggingSuppressed = false;
            this.errorDetectedEpisodeStartTime = 0;
            this.errorsOccurredDuringLogSuppression = false;
            this.serialPacketErrorCrcByCode.clear();
            this.serialPacketErrorEscapingByCode.clear();
            this.serialPacketErrorMinLengthByCode.clear();
        }

        if (this.asyncTasksHandler != null) {
            this.asyncTasksHandler.removeCallbacks(this.errorReportingRunnable);
        }
    }

    private void onPacketError(String type, int code, HashMap<Integer, Integer> map) {
        final Context ctx = this.context;
        if (ctx == null) {
            return;
        }

        long now = System.currentTimeMillis();
        String logMsg;
        synchronized (this.errorReportingLock) {
            incrementByCode(map, code);
            if (this.errorLoggingSuppressed) {
                this.errorsOccurredDuringLogSuppression = true;
                return;
            }
            this.errorDetectedEpisodeStartTime = now;
            this.errorLoggingSuppressed = true;
            String summary = this.formatErrorSummary();
            logMsg = ctx.getString(R.string.log_serial_packet_error, summary, formatTime(now), SUPPRESSION_DURATION_MINUTES);
        }
        this.asyncTasksHandler.removeCallbacks(this.errorReportingRunnable);
        this.asyncTasksHandler.postDelayed(this.errorReportingRunnable, SUPPRESSION_DURATION_MINUTES * 60 * 1000);
        log(ctx, logMsg);
    }

    // main method to search packets from input stream
    // returns packet with leading code operation and trailing crc16 two-byte code
    private byte[] searchPacket(int tillInputDataEnd) {
        while (true) {
            if (this.inputDataHead == tillInputDataEnd) {
                return null;
            }

            // Remove data before first PACKET_BEGIN byte
            while ((this.inputData[this.inputDataHead] & 0xFF) != PACKET_BEGIN) {
                this.inputDataHead = (this.inputDataHead + 1) % CIRCULAR_BUFFER_SIZE;
                if (this.inputDataHead == tillInputDataEnd) { // empty buffer
                    return null;
                }
            }

            int curPos = (this.inputDataHead + 1) % CIRCULAR_BUFFER_SIZE; // first byte after PACKET_BEGIN
            if (curPos == tillInputDataEnd) { // no bytes after PACKET_BEGIN, wait for more data
                return null;
            }

            if ((this.inputData[curPos] & 0xFF) != PACKET_START) { // first byte after PACKET_BEGIN is not PACKET_START, search for the next PACKET_BEGIN
                this.inputDataHead = curPos;
                continue;
            }

            // We have 0xFF, 0xFE as two first bytes
            // Search for packet end
            int packetEnd = -1;
            byte lastCheckedByte = -1;
            int numBytes = 0; // number of bytes in packet
            int packetBegin = (curPos + 1) % CIRCULAR_BUFFER_SIZE; // first byte of packet
            for (curPos = packetBegin; curPos != tillInputDataEnd; curPos = (curPos + 1) % CIRCULAR_BUFFER_SIZE) {
                if ((this.inputData[curPos] & 0xFF) == PACKET_END) {
                    packetEnd = curPos;
                    break;
                }

                lastCheckedByte = this.inputData[curPos];
                if ((lastCheckedByte & 0xFF) != PACKET_ESC) {
                    numBytes++;
                }
            }
            if (packetEnd == -1) { // partial data: packet has begin and no end, wait for more data
                return null;
            }

            if ((lastCheckedByte & 0xFF) == PACKET_ESC) {
                this.onPacketError("escaping", this.inputData[packetBegin] & 0xFF, this.serialPacketErrorEscapingByCode);
                this.inputDataHead = (this.inputDataHead + 1) % CIRCULAR_BUFFER_SIZE;
                continue;
            }

            if (numBytes < 3) {
                this.onPacketError("minimum length", this.inputData[packetBegin] & 0xFF, this.serialPacketErrorMinLengthByCode);
                this.inputDataHead = (this.inputDataHead + 1) % CIRCULAR_BUFFER_SIZE;
                continue;
            }

            // Have full packet. Get it and test it
            byte d;
            byte[] res = new byte[numBytes];  // with crc16
            int bytesSaved = 0;
            boolean isEscapedByte = false;
            int crc = 0xFFFF;
            for (int i = packetBegin; i != packetEnd; i = (i + 1) % CIRCULAR_BUFFER_SIZE) {
                d = this.inputData[i];
                if (isEscapedByte) {
                    d = (byte) (~d);
                    res[bytesSaved] = d;
                    bytesSaved++;
                    isEscapedByte = false;
                    crc = crc16(crc, d);
                } else {
                    if ((d & 0xFF) == PACKET_ESC) {
                        isEscapedByte = true;
                    } else {
                        res[bytesSaved] = d;
                        bytesSaved++;
                        crc = crc16(crc, d);
                    }
                }
            }

            this.inputDataHead = (packetEnd + 1) % CIRCULAR_BUFFER_SIZE;

            if (crc != 0) {
                this.onPacketError("CRC", res[0] & 0xFF, this.serialPacketErrorCrcByCode);
                continue;
            }

            return res;
        }
    }

    private void findPackets(int tillInputDataEnd) {
        byte[] newPacket;
        while (true) {
            newPacket = this.searchPacket(tillInputDataEnd);
            if (newPacket == null || newPacket.length == 0) {
                return;
            }

            int code = newPacket[0] & 0xFF;
            switch (code) {
                case CODE_HIST:
                    if (newPacket.length % 4 != 1) {
                        // TODO: report discrepancy (but keep in mind CODE_HIST is sent very often)
                        break;
                    }

                    int pos = (newPacket[1] & 0xFF) | ((newPacket[2] & 0xFF) << 8);
                    if (DEBUG_LOG) {
                        log(this.context, "Packet HIST code=0x01 pos=" + pos + " bins=" + ((newPacket.length - 5) / 4));
                    }

                    // The device emits each sweep as chunks with strictly ascending pos (chunk
                    // size may vary, but the order is guaranteed); when pos steps back a new sweep
                    // has begun. Reset completeness here (not only on the DATA packet) so that a
                    // DATA packet lost to a CRC error cannot leave stale received-flags that
                    // mislabel a partially-refreshed frame as complete.
                    if (pos <= this.lastHistStartPos) {
                        this.resetHistogramCompleteness();
                    }
                    this.lastHistStartPos = pos;

                    int bin;
                    for (int i = 3; i < newPacket.length - 2; i += 4) {
                        if (pos >= SPECTRA_PRO_HIST_POINTS)
                            break;
                        bin = (newPacket[i] & 0xFF) |
                                ((newPacket[i + 1] & 0xFF) << 8) |
                                ((newPacket[i + 2] & 0xFF) << 16) |
                                ((newPacket[i + 3] & 0xFF) << 24);
                        this.histogram[pos] = bin;
                        if (!this.histBinsReceived[pos]) {
                            this.histBinsReceived[pos] = true;
                            this.histBinsMissing--;
                        }
                        pos++;
                    }
                    break;

                case CODE_SCOPE:
                    if (newPacket.length % 2 != 1) {
                        break;
                    }

                    if (DEBUG_LOG) {
                        log(this.context, "Packet SCOPE code=0x02");
                    }

                    long[] scope = new long[(newPacket.length - 3) >> 1];
                    for (int i = 1, j = 0; i < newPacket.length - 2; i += 2, j += 1) {
                        scope[j] = (newPacket[i] & 0xFF) | ((newPacket[i + 1] & 0xFF) << 8);
                    }

                    // no consumer for that data for now
                    break;

                case CODE_TEXT:
                    CommandCode answered = null;
                    String answer;
                    synchronized (this.syncCommand) {
                        int newLength = newPacket.length - 3;    //remove 0x03 code operation and trailing crc16 two-byte code
                        byte[] answerPacket = new byte[newLength];   //remove first code byte and last 0x0D,0x0A bytes
                        System.arraycopy(newPacket, 1, answerPacket, 0, newLength);
                        answer = new String(answerPacket);
                        //fix some sort of error in Spectra Pro
                        if (COMMAND_RESULT_OK2.equals(answer)) {
                            answer = COMMAND_RESULT_OK;
                        }
                        if (this.commands.isEmpty()) {
                            log(this.context, "Unexpected TEXT from device (no pending commands): " + answer.trim());
                            break;
                        }
                        if (DEBUG_LOG) {
                            log(this.context, "Packet TEXT code=0x03 text=" + answer.trim());
                        }
                        answered = this.commands.pop();
                        this.answerNumber = 0; //data received
                    }

                    // delivered outside syncCommand: handling an own answer may restart the
                    // watchdog, whose recovery path takes syncCommand itself
                    this.handleDeviceAnswer(answered, answer);

                    this.sendPacket(); // send next packet
                    break;

                case CODE_DATA:
                    if (newPacket.length < (11 + 2)) {
                        break;
                    }

                    int total_time = (newPacket[1] & 0xFF) |
                            ((newPacket[2] & 0xFF) << 8) |
                            ((newPacket[3] & 0xFF) << 16) |
                            ((newPacket[4] & 0xFF) << 24);
                    int cpu_load = (newPacket[5] & 0xFF) |
                            ((newPacket[6] & 0xFF) << 8);
                    int cps = (newPacket[7] & 0xFF) |
                            ((newPacket[8] & 0xFF) << 8) |
                            ((newPacket[9] & 0xFF) << 16) |
                            ((newPacket[10] & 0xFF) << 24);

                    if (DEBUG_LOG) {
                        log(this.context, "Packet DATA code=0x04 time=" + total_time + " cps=" + cps);
                    }

                    int lost_impulses = 0;
                    if (newPacket.length >= (15 + 2)) {
                        lost_impulses = (newPacket[11] & 0xFF) |
                                ((newPacket[12] & 0xFF) << 8) |
                                ((newPacket[13] & 0xFF) << 16) |
                                ((newPacket[14] & 0xFF) << 24);
                    }

                    if (newPacket.length >= (28 + 2)) {
                        //newPacket[15] & 0x01 - has temperature sensor1
                        //newPacket[15] & 0x02 - has temperature sensor2
                        //newPacket[15] & 0x04 - has temperature sensor3
                        //newPacket[15-18] - float temperature 1
                        //newPacket[19-23] - float temperature 2
                        //newPacket[24-28] - float temperature 3
//                        total_impulse_length = (newPacket[15] & 0xFF) |
//                                ((newPacket[16] & 0xFF) << 8) |
//                                ((newPacket[17] & 0xFF) << 16) |
//                                ((newPacket[18] & 0xFF) << 24);
                    }

                    boolean isHistogramComplete = this.histBinsMissing == 0;
                    boolean isUnreliable = false;
                    if (this.unreliableDataReportsLeft > 0) {
                        if (isHistogramComplete) {
                            this.unreliableDataReportsLeft = 0;
                        } else {
                            this.unreliableDataReportsLeft--;
                            isUnreliable = true;
                        }
                    }

                    Intent intent;
                    if (isHistogramComplete || this.allowIncompleteData) {
                        intent = new Intent(SpectrumSource.ACTION_SOURCE_DATA).setPackage(Constants.PACKAGE_NAME);
                        intent.putExtra(SpectrumSource.EXTRA_SOURCE_DATA_HISTOGRAM, this.histogram);
                        intent.putExtra(SpectrumSource.EXTRA_SOURCE_DATA_CP1S, cps);
                        intent.putExtra(SpectrumSource.EXTRA_SOURCE_DATA_RECORDING_TIME, (double) total_time);
                        intent.putExtra(SpectrumSource.EXTRA_SOURCE_INPUT_TYPE, this.inputType());
                    } else {
                        intent = new Intent(SpectrumSource.ACTION_SOURCE_DATA_SKIPPED).setPackage(Constants.PACKAGE_NAME);
                        if (isUnreliable) {
                            intent.putExtra(SpectrumSource.EXTRA_SOURCE_DATA_SKIPPED_REASON, "Skip due to recording start");
                        } else {
                            intent.putExtra(SpectrumSource.EXTRA_SOURCE_DATA_SKIPPED_REASON, "Incomplete histogram received");
                        }
                    }

                    this.resetHistogramCompleteness();
                    this.context.sendBroadcast(intent);
                    this.restartDataWatchdog();
                    break;
                default:
                    //Toast.makeText(context, context.getString(R.string.unknown_code, code & 0xFF), Toast.LENGTH_SHORT).show();
                    break;
            }
        }
    }

    private static class CommandCode {
        public static final long DROP_TIMEOUT = 5000; //in milliseconds;
        public final byte[] command;
        public final String id;
        public final int op;
        public final byte code;
        //        public final long time;  //for timeout
        public final long Number;
        private static long NextNumber = 1;
        private static final Object sync = new Object();

        CommandCode(String cmd, String id, int op) {
            command = cmd.getBytes(Charset.defaultCharset());
            this.id = id;
            this.op = op;
            code = CODE_TEXT; //shows command as text
//            time = (new Date()).getTime() + DROP_TIMEOUT;  //time to drop as not delivered
            synchronized (sync) {
                Number = NextNumber;
                NextNumber++;
                if (NextNumber > 1e9)
                    NextNumber = 1;
            }
        }

        CommandCode(byte code, byte[] cmd, String id, int op) {
            command = cmd;
            this.id = id;
            this.op = op;
            this.code = code; //shows command as array of bytes
//            time = (new Date()).getTime() + DROP_TIMEOUT;  //time to drop as not delivered
            synchronized (sync) {
                Number = NextNumber;
                NextNumber++;
                if (NextNumber > 1e9)
                    NextNumber = 1;
            }
        }

    }

    private final LinkedList<CommandCode> commands = new LinkedList<>();
    private long answerNumber = 0;
    private final Object syncCommand = new Object();

    private boolean sendPacket() {
        synchronized (this.syncCommand) {
            //nothing or nowhere to send
            if (this.commands.isEmpty())
                return false;

            if (!this.isOpened()) {
                CommandCode failed = this.commands.pop();
                this.answerNumber = 0;
                this.handleDeviceAnswer(failed, COMMAND_RESULT_ERR);
                this.sendPacket();
                return false;
            }

            final CommandCode cmd = this.commands.getFirst();
            //waiting the device to answer
            if (this.answerNumber == cmd.Number) {
                return true;
            }

            //AnswerNumber=0 - nothing is sent before
            int crc = 0xFFFF;
            crc = crc16(crc, cmd.code);
            ArrayList<Byte> outputArray = new ArrayList<>();
            outputArray.add((byte) PACKET_BEGIN);
            outputArray.add((byte) PACKET_START);
            this.addWithEscape(outputArray, cmd.code);
            for (byte datum : cmd.command) {
                this.addWithEscape(outputArray, datum);
                crc = crc16(crc, datum);
            }
            byte d = (byte) (crc & 0xFF);
            this.addWithEscape(outputArray, d);
            d = (byte) ((crc >> 8) & 0xFF);
            this.addWithEscape(outputArray, d);
            outputArray.add((byte) PACKET_END);
            byte[] command_data = new byte[outputArray.size()];
            for (int i = 0; i < outputArray.size(); i++) {
                command_data[i] = outputArray.get(i);
            }
            try {
                this.answerNumber = cmd.Number;
                this.port.write(command_data, SERIAL_MANAGER_WRITE_TIMEOUT);
                this.asyncTasksHandler.postDelayed(new Runnable() {
                    final long Number = cmd.Number;

                    @Override
                    public void run() {
                        final Context ctx = AtomSpectraProSource.this.context;
                        if (ctx == null) return;
                        CommandCode code = null;
                        synchronized (AtomSpectraProSource.this.syncCommand) {
                            if (!AtomSpectraProSource.this.commands.isEmpty() && Number == AtomSpectraProSource.this.answerNumber) {
                                //timeout is here, remove old packet
                                code = AtomSpectraProSource.this.commands.pop();
                                AtomSpectraProSource.this.answerNumber = 0;
                            }
                        }
                        if (code != null) {
                            AtomSpectraProSource.this.handleDeviceAnswer(code, COMMAND_RESULT_TIMEOUT);
                        }
                        AtomSpectraProSource.this.sendPacket(); //try to send next packet
                    }
                }, CommandCode.DROP_TIMEOUT + 500);
            } catch (Exception e) {
                log(this.context, "USB write failed: " + e.getMessage());
                CommandCode failed = this.commands.pop();
                this.answerNumber = 0;
                this.handleDeviceAnswer(failed, COMMAND_RESULT_ERR);
                this.sendPacket();
                return false;
            }
        }
        return true;
    }

    private boolean enqueueTextCommand(@NonNull String command, @NonNull String id, int op) {
        if (DEBUG_LOG) {
            log(this.context, "Enqueuing command \"" + command.trim() + "\" (" + SpectrumSource.opName(op) + ")");
        }
        this.setAndEmitStatus(SpectrumSource.STATUS_CONNECTED_EXECUTING_COMMAND);
        synchronized (this.syncCommand) {
            this.commands.add(new CommandCode(command, id, op));
        }

        return this.sendPacket();
    }

    //get parameter value from info line or null if none
    private static String getParameter(@NonNull String data, @NonNull String parameter) {
        String[] pairs = data.split("\\s+");
        String key = "";
        StringBuilder val = new StringBuilder();
        boolean isArray = false;
        boolean isKey = true;
        for (String pair : pairs) {
            //key itself
            if (isKey) {
                key = pair;
                val = new StringBuilder();
                isKey = false;
                continue;
            }
            //array value for key
            if (isArray && pair.endsWith("]")) {
                isArray = false;
                isKey = true;
                val.append(" ").append(pair);
                if (parameter.equals(key))
                    return val.toString();
                continue;
            }
            //strange data input
            if (!isArray && pair.endsWith("]")) {
                return null;
            }
            if (pair.startsWith("[")) {
                isArray = true;
                val.append(" ").append(pair);
                continue;
            }
            if (isArray) {
                val.append(" ").append(pair);
                continue;
            }
            //single value for key
            if (parameter.equals(key))
                return pair;
            //return to key
            isKey = true;
        }
        return null;
    }

    private void stopProcessingThread() {
        if (this.processingThread != null) {
            this.processingThread.interrupt();
            try {
                this.processingThread.join();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            this.processingThread = null;
        }
    }

    // local thread method to perform read from circular buffer
    private void processBufferLoop() {
        if (DEBUG_LOG) {
            log(this.context, "Packet processing thread started");
        }
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);

        int currentInputDataEnd = this.inputDataEnd;
        while (true) {
            synchronized (this.circularBufferSync) {
                // input data end could only be changed under sync block in onNewData
                // if this sync block is reached before onNewData sync block, this thread will wait until notify
                // if this sync block is reached after onNewData sync block and there is new data, this thread will not wait
                while (this.inputDataEnd == currentInputDataEnd) {
                    try {
                        this.circularBufferSync.wait();
                    } catch (InterruptedException e) {
                        if (DEBUG_LOG) {
                            log(this.context, "Packet processing thread interrupted");
                        }
                        return;
                    }
                }
                currentInputDataEnd = this.inputDataEnd;
            }

            // called only once per new data arrival, even if contains partial packet in the end, next loop cycle will wait for new data
            this.findPackets(currentInputDataEnd);
        }
    }

    @Override
    // serial thread method to perform write to circular buffer
    public void onNewData(byte[] data) {
        int currentDataEnd = this.inputDataEnd;
        int bytesWritten = 0;
        // write bytes to circular buffer
        for (int i = 0; i < data.length; i++) {
            if ((currentDataEnd + 1) % CIRCULAR_BUFFER_SIZE == this.inputDataHead) {
                int bytesLost = data.length - i;
                log(this.context, "Circular buffer overflow: " + bytesLost + " bytes lost");
                break;
            }
            this.inputData[currentDataEnd] = data[i];
            currentDataEnd = (currentDataEnd + 1) % CIRCULAR_BUFFER_SIZE;
            bytesWritten++;
        }
        if (bytesWritten > 0) {
            // bump up end pointer and notify processing thread
            synchronized (this.circularBufferSync) {
                this.inputDataEnd = currentDataEnd;
                this.circularBufferSync.notify();
            }
        }
    }

    @Override
    public void onRunError(Exception e) {
        log(this.context, "USB serial error: " + e.getMessage());
    }

    public static UsbDevice scanForSpectraProDevice(UsbManager manager) {
        if (manager != null) {
            HashMap<String, UsbDevice> deviceList = manager.getDeviceList();
            for (UsbDevice dev : deviceList.values()) {
                if (!isSpectraPro(dev)) {
                    continue;
                }

                return dev;
            }
        }

        return null;
    }

    public static boolean isSpectraPro(UsbDevice device) {
        return (device.getVendorId() == 1027) && (device.getProductId() == 1002 || device.getProductId() == 24577);
    }
}

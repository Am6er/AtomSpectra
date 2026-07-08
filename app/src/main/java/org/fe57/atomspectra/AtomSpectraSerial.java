package org.fe57.atomspectra;

import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.Process;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.zip.CRC32;

public class AtomSpectraSerial implements SerialInputOutputManager.Listener {
    private static final int CIRCULAR_BUFFER_SIZE = 128 * 1024; // ~2 seconds of data at max baud rate (600kbps / 8N1 = 60KB/s)
    private static final int SERIAL_MANAGER_READ_BUFFER_SIZE = 4 * 1024;
    private static final int SERIAL_MANAGER_READ_QUEUE_SIZE = 4;
    private static final int SERIAL_MANAGER_WRITE_TIMEOUT = 1000;

    private UsbDevice Device;
    private UsbSerialDriver Driver;
    private UsbDeviceConnection Connection;
    private UsbSerialPort Port;
    private SerialInputOutputManager Manager;
    private volatile Context context;

    // circular buffer for incoming data
    // head == end means empty buffer, (end + 1) % size == head means full buffer
    private final byte[] inputData = new byte[CIRCULAR_BUFFER_SIZE];
    private volatile int inputDataHead; // first meaningful byte in inputData
    private volatile int inputDataEnd; // first free byte in inputData

    private final Object circularBufferSync = new Object();
    private Thread processingThread = null;
    Handler asyncTasksHandler;

    public long[] histogram = new long[Constants.NUM_HIST_POINTS];
    private final boolean[] histBinsReceived = new boolean[Constants.NUM_HIST_POINTS];
    private int histBinsMissing = Constants.NUM_HIST_POINTS;
    private int cps = 0;
    private int total_time = 0;
    private int cpu_load = 0;
    private long lost_impulses = 0;
    private long total_impulse_length = 0;

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
            final Context ctx = context;
            if (ctx == null) {
                return;
            }

            String summary;
            long startTime;
            boolean hadErrors;
            synchronized (errorReportingLock) {
                summary = formatErrorSummary();
                startTime = errorDetectedEpisodeStartTime;
                hadErrors = errorsOccurredDuringLogSuppression;
            }

            if (hadErrors) {
                AtomSpectraLog.addMessage(ctx, ctx.getString(R.string.log_serial_errors_ongoing, summary, formatTime(startTime), SUPPRESSION_DURATION_MINUTES));
                synchronized (errorReportingLock) {
                    errorsOccurredDuringLogSuppression = false;
                }
                asyncTasksHandler.postDelayed(errorReportingRunnable, SUPPRESSION_DURATION_MINUTES * 60 * 1000);
            } else {
                AtomSpectraLog.addMessage(ctx, ctx.getString(R.string.log_serial_errors_resolved, SUPPRESSION_DURATION_MINUTES, summary, formatTime(startTime)));
                resetErrorSuppression();
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
    private final static String COMMAND_RESULT_OK2 = "ok\r\n";  //replace this one with COMMAND_RESULT_OK as to be wrong
    public final static String COMMAND_RESULT_ERR = "-err\r\n";
    public final static String COMMAND_RESULT_TIMEOUT = "-timeout\r\n";
    public final static String COMMAND_RESULT_OK_COLLECTING = "-ok collecting\r\n";

    public final static String EXTRA_ID = "Id";
    public final static String EXTRA_NUMBER = "Number";
    public final static String EXTRA_COMMAND = "Command";
    private final static String SERIAL_ID = "internal";

    public final static String EXTRA_DATA_TYPE =
            "org.fe57.atomspectra.EXTRA_DATA_TYPE";
    public final static String EXTRA_RESULT =
            "org.fe57.atomspectra.EXTRA_DATA_PACKET";
    public final static String EXTRA_DATA_BOOL_HISTOGRAM_COMPLETE =
            "org.fe57.atomspectra.EXTRA_DATA_BOOL_HISTOGRAM_COMPLETE";

    //Constructor
    public AtomSpectraSerial(Context context) {
        this.context = context;
        asyncTasksHandler = new Handler(context.getMainLooper());
        Manager = null;
        Init();
    }

    private void Init() {
        Driver = null;
        Device = null;
        Connection = null;
        Port = null;
        inputDataHead = 0;
        inputDataEnd = 0;
        processingThread = null;
        Arrays.fill(histBinsReceived, false);
        histBinsMissing = Constants.NUM_HIST_POINTS;
        resetErrorSuppression();
        synchronized (syncCommand) {
            AnswerNumber = 0;
            Commands.clear();
        }
    }

    public void Destroy() {
        stopProcessingThread();

        if (Port != null && Port.isOpen()) {
            try {
                Port.close();
            } catch (Exception ignore) {
                //nothing
            }
        }

        if (Manager != null) {
            Manager.stop();
        }
        Manager = null;

        if (asyncTasksHandler != null) {
            asyncTasksHandler.removeCallbacksAndMessages(null);
        }
        asyncTasksHandler = null;

        context = null;
    }

    //Test if device is working
    public boolean isOpened() {
        return (Port != null) && (Port.isOpen());
    }

    //Open the port
    public boolean Open(@NonNull UsbDevice device) {
        Driver = UsbSerialProber.getDefaultProber().probeDevice(device);
        if (Driver == null) {
            return false;
        }
        Device = device;
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (manager == null) {
            AtomSpectraLog.addMessage(context, "USB connection failed: UsbManager is not available");
            Close();
            Intent intent = new Intent(Constants.ACTION.ACTION_USB_DETACHED).setPackage(Constants.PACKAGE_NAME);
            context.sendBroadcast(intent);
            return false;
        }
        try {
            Connection = manager.openDevice(Driver.getDevice());
            if (Connection == null) {
                AtomSpectraLog.addMessage(context, "USB connection failed: could not open device");
                Close();
                return false;
            }
        } catch (Exception e) {
            AtomSpectraLog.addMessage(context, "USB connection failed: " + e.getMessage());
            Close();
            Intent intent = new Intent(Constants.ACTION.ACTION_USB_DETACHED).setPackage(Constants.PACKAGE_NAME);
            context.sendBroadcast(intent);
            return false;
        }
        Port = Driver.getPorts().get(0);
        try {
            Port.open(Connection);
            Port.setParameters(600000, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            inputDataHead = 0;
            inputDataEnd = 0;
            Manager = new SerialInputOutputManager(Port, this);
            Manager.setReadBufferSize(SERIAL_MANAGER_READ_BUFFER_SIZE);
            Manager.setReadQueue(SERIAL_MANAGER_READ_QUEUE_SIZE);
            Manager.start();
            processingThread = new Thread(this::processBufferLoop, "AtomSpectra-Packet-Processor");
            processingThread.setDaemon(true);
            processingThread.start();
        } catch (Exception e) {
            AtomSpectraLog.addMessage(context, "USB port setup failed: " + e.getMessage());
            Close();
            Intent intent = new Intent(Constants.ACTION.ACTION_USB_DETACHED).setPackage(Constants.PACKAGE_NAME);
            context.sendBroadcast(intent);
            return false;
        }
        return true;
    }

    public void Close() {
        if (Manager != null) {
            Manager.stop();
        }
        Manager = null;

        if (Port != null) {
            try {
                Port.close();
            } catch (Exception ignore) {
                //nothing
            }
        }

        synchronized (syncCommand) {
            while (!Commands.isEmpty()) {
                CommandCode failed = Commands.pop();
                Intent intentText = new Intent(Constants.ACTION.ACTION_USB_HAS_ANSWER).setPackage(Constants.PACKAGE_NAME);
                intentText.putExtra(EXTRA_RESULT, COMMAND_RESULT_ERR);
                intentText.putExtra(EXTRA_NUMBER, failed.Number);
                intentText.putExtra(EXTRA_COMMAND, new String(failed.command));
                intentText.putExtra(EXTRA_ID, failed.id);
                context.sendBroadcast(intentText);
                AtomSpectraLog.addMessage(context, "Serial command failed due Close() call: " + new String(failed.command));
            }
            AnswerNumber = 0;
        }

        stopProcessingThread();
        Init();
    }

    private void resetTelemetry() {
        cps = 0;
        total_time = 0;
        cpu_load = 0;
        lost_impulses = 0;
        total_impulse_length = 0;
    }

    // clear histogram
    // The histogram array is owned by the packet-processing thread; the actual
    // zeroing happens on that thread when the device acknowledges "-rst" (see CODE_TEXT
    // handler), so this method only issues the command and never touches the array.
    public void ClearHistogram() {
        if (Port == null || !Port.isOpen()) {
            return;
        }

        sendTextCommand("-rst", SERIAL_ID);
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
        if (!serialPacketErrorCrcByCode.isEmpty()) {
            sb.append(formatErrorsByCode("CRC", serialPacketErrorCrcByCode));
        }
        if (!serialPacketErrorEscapingByCode.isEmpty()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(formatErrorsByCode("escaping", serialPacketErrorEscapingByCode));
        }
        if (!serialPacketErrorMinLengthByCode.isEmpty()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(formatErrorsByCode("minimum length", serialPacketErrorMinLengthByCode));
        }
        return sb.toString();
    }

    private static String formatTime(long epochMs) {
        return String.format("%tT", epochMs);
    }

    private void resetErrorSuppression() {
        synchronized (errorReportingLock) {
            errorLoggingSuppressed = false;
            errorDetectedEpisodeStartTime = 0;
            errorsOccurredDuringLogSuppression = false;
            serialPacketErrorCrcByCode.clear();
            serialPacketErrorEscapingByCode.clear();
            serialPacketErrorMinLengthByCode.clear();
        }

        if (asyncTasksHandler != null) {
            asyncTasksHandler.removeCallbacks(errorReportingRunnable);
        }
    }

    private void onPacketError(String type, int code, HashMap<Integer, Integer> map) {
        long now = System.currentTimeMillis();
        String logMsg;
        synchronized (errorReportingLock) {
            incrementByCode(map, code);
            if (errorLoggingSuppressed) {
                errorsOccurredDuringLogSuppression = true;
                return;
            }
            errorDetectedEpisodeStartTime = now;
            errorLoggingSuppressed = true;
            String summary = formatErrorSummary();
            logMsg = context.getString(R.string.log_serial_packet_error, summary, formatTime(now), SUPPRESSION_DURATION_MINUTES);
        }
        asyncTasksHandler.removeCallbacks(errorReportingRunnable);
        asyncTasksHandler.postDelayed(errorReportingRunnable, SUPPRESSION_DURATION_MINUTES * 60 * 1000);
        AtomSpectraLog.addMessage(context, logMsg);
    }

    // main method to search packets from input stream
    // returns packet with leading code operation and trailing crc16 two-byte code
    private byte[] searchPacket(int tillInputDataEnd) {
        while (true) {
            if (inputDataHead == tillInputDataEnd) {
                return null;
            }

            // Remove data before first PACKET_BEGIN byte
            while ((inputData[inputDataHead] & 0xFF) != PACKET_BEGIN) {
                inputDataHead = (inputDataHead + 1) % CIRCULAR_BUFFER_SIZE;
                if (inputDataHead == tillInputDataEnd) { // empty buffer
                    return null;
                }
            }

            int curPos = (inputDataHead + 1) % CIRCULAR_BUFFER_SIZE; // first byte after PACKET_BEGIN
            if (curPos == tillInputDataEnd) { // no bytes after PACKET_BEGIN, wait for more data
                return null;
            }

            if ((inputData[curPos] & 0xFF) != PACKET_START) { // first byte after PACKET_BEGIN is not PACKET_START, search for the next PACKET_BEGIN
                inputDataHead = curPos;
                continue;
            }

            // We have 0xFF, 0xFE as two first bytes
            // Search for packet end
            int packetEnd = -1;
            byte lastCheckedByte = -1;
            int numBytes = 0; // number of bytes in packet
            int packetBegin = (curPos + 1) % CIRCULAR_BUFFER_SIZE; // first byte of packet
            for (curPos = packetBegin; curPos != tillInputDataEnd; curPos = (curPos + 1) % CIRCULAR_BUFFER_SIZE) {
                if ((inputData[curPos] & 0xFF) == PACKET_END) {
                    packetEnd = curPos;
                    break;
                }

                lastCheckedByte = inputData[curPos];
                if ((lastCheckedByte & 0xFF) != PACKET_ESC) {
                    numBytes++;
                }
            }
            if (packetEnd == -1) { // partial data: packet has begin and no end, wait for more data
                return null;
            }

            if ((lastCheckedByte & 0xFF) == PACKET_ESC) {
                onPacketError("escaping", inputData[packetBegin] & 0xFF, serialPacketErrorEscapingByCode);
                inputDataHead = (inputDataHead + 1) % CIRCULAR_BUFFER_SIZE;
                continue;
            }

            if (numBytes < 3) {
                onPacketError("minimum length", inputData[packetBegin] & 0xFF, serialPacketErrorMinLengthByCode);
                inputDataHead = (inputDataHead + 1) % CIRCULAR_BUFFER_SIZE;
                continue;
            }

            // Have full packet. Get it and test it
            byte d;
            byte[] res = new byte[numBytes];  // with crc16
            int bytesSaved = 0;
            boolean isEscapedByte = false;
            int crc = 0xFFFF;
            for (int i = packetBegin; i != packetEnd; i = (i + 1) % CIRCULAR_BUFFER_SIZE) {
                d = inputData[i];
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

            inputDataHead = (packetEnd + 1) % CIRCULAR_BUFFER_SIZE;

            if (crc != 0) {
                onPacketError("CRC", res[0] & 0xFF, serialPacketErrorCrcByCode);
                continue;
            }

            return res;
        }
    }

    private void findPackets(int tillInputDataEnd) {
        byte[] newPacket;
        while (true) {
            newPacket = searchPacket(tillInputDataEnd);
            if (newPacket == null || newPacket.length == 0) {
                return;
            }

            int code = newPacket[0] & 0xFF;
            switch (code) {
                case CODE_HIST:
                    if (newPacket.length % 4 != 1) {
                        // TODO: report discrepancy
                        return;
                    }

                    int pos = (newPacket[1] & 0xFF) | ((newPacket[2] & 0xFF) << 8);
                    if (DEBUG_LOG) {
                        AtomSpectraLog.addMessage(context, "Packet HIST code=0x01 pos=" + pos + " bins=" + ((newPacket.length - 5) / 4));
                    }

                    int bin;
                    for (int i = 3; i < newPacket.length - 2; i += 4) {
                        if (pos >= Constants.NUM_HIST_POINTS)
                            break;
                        bin = (newPacket[i] & 0xFF) |
                                ((newPacket[i + 1] & 0xFF) << 8) |
                                ((newPacket[i + 2] & 0xFF) << 16) |
                                ((newPacket[i + 3] & 0xFF) << 24);
                        histogram[pos] = bin;
                        if (!histBinsReceived[pos]) {
                            histBinsReceived[pos] = true;
                            histBinsMissing--;
                        }
                        pos++;
                    }
                    break;

                case CODE_SCOPE:
                    if (newPacket.length % 2 != 1) {
                        break;
                    }

                    if (DEBUG_LOG) {
                        AtomSpectraLog.addMessage(context, "Packet SCOPE code=0x02");
                    }

                    long[] scope = new long[(newPacket.length - 3) >> 1];
                    for (int i = 1, j = 0; i < newPacket.length - 2; i += 2, j += 1) {
                        scope[j] = (newPacket[i] & 0xFF) | ((newPacket[i + 1] & 0xFF) << 8);
                    }

                    Intent intentScope = new Intent(Constants.ACTION.ACTION_USB_HAS_DATA).setPackage(Constants.PACKAGE_NAME);
                    intentScope.putExtra(AtomSpectraService.EXTRA_DATA_ARRAY_LONG_SERIAL_SPECTRUM_COUNTS, histogram);
                    intentScope.putExtra(AtomSpectraService.EXTRA_DATA_ARRAY_LONG_SERIAL_SCOPE_COUNTS, scope);
                    intentScope.putExtra(EXTRA_DATA_TYPE, CODE_SCOPE);
                    context.sendBroadcast(intentScope);
                    break;

                case CODE_TEXT:
                    synchronized (syncCommand) {
                        int newLength = newPacket.length - 3;    //remove 0x03 code operation and trailing crc16 two-byte code
                        byte[] answerPacket = new byte[newLength];   //remove first code byte and last 0x0D,0x0A bytes
                        System.arraycopy(newPacket, 1, answerPacket, 0, newLength);
                        String answer = new String(answerPacket);
                        //fix some sort of error in Spectra Pro
                        if (COMMAND_RESULT_OK2.equals(answer)) {
                            answer = COMMAND_RESULT_OK;
                        }
                        if (Commands.isEmpty()) {
                            AtomSpectraLog.addMessage(context, "Unexpected TEXT from device (no pending commands): " + answer.trim());
                            break;
                        }
                        if (DEBUG_LOG) {
                            AtomSpectraLog.addMessage(context, "Packet TEXT code=0x03 text=" + answer.trim());
                        }
                        String commandStr = new String(Commands.getFirst().command);
                        Intent intentText = new Intent(Constants.ACTION.ACTION_USB_HAS_ANSWER).setPackage(Constants.PACKAGE_NAME);
                        intentText.putExtra(EXTRA_RESULT, answer);
                        intentText.putExtra(EXTRA_ID, Commands.getFirst().id);
                        intentText.putExtra(EXTRA_COMMAND, commandStr);
                        intentText.putExtra(EXTRA_NUMBER, Commands.pop().Number);
                        AnswerNumber = 0; //data received
                        context.sendBroadcast(intentText);

                        if ((commandStr.equals("-sta") || commandStr.equals("-rst")) &&
                                (COMMAND_RESULT_OK.equals(answer) || COMMAND_RESULT_OK_COLLECTING.equals(answer))) {
                            Arrays.fill(histBinsReceived, false);
                            histBinsMissing = Constants.NUM_HIST_POINTS;
                        }

                        if (commandStr.equals("-rst") && (COMMAND_RESULT_OK.equals(answer))) {
                            Arrays.fill(histogram, 0);
                            resetTelemetry();
                        }

                        if ((commandStr.equals("-sta") || commandStr.equals("-sto")) &&
                                (COMMAND_RESULT_OK.equals(answer) || COMMAND_RESULT_OK_COLLECTING.equals(answer))) {
                            // TODO: report errors if we are in counting (suppressing) state
                            resetErrorSuppression();
                        }
                    }

                    sendPacket(); // send next packet
                    break;

                case CODE_DATA:
                    if (newPacket.length < (11 + 2)) {
                        break;
                    }

                    total_time = (newPacket[1] & 0xFF) |
                            ((newPacket[2] & 0xFF) << 8) |
                            ((newPacket[3] & 0xFF) << 16) |
                            ((newPacket[4] & 0xFF) << 24);
                    cpu_load = (newPacket[5] & 0xFF) |
                            ((newPacket[6] & 0xFF) << 8);
                    cps = (newPacket[7] & 0xFF) |
                            ((newPacket[8] & 0xFF) << 8) |
                            ((newPacket[9] & 0xFF) << 16) |
                            ((newPacket[10] & 0xFF) << 24);

                    if (DEBUG_LOG) {
                        AtomSpectraLog.addMessage(context, "Packet DATA code=0x04 time=" + total_time + " cps=" + cps);
                    }

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

                    Intent intent = new Intent(Constants.ACTION.ACTION_USB_HAS_DATA).setPackage(Constants.PACKAGE_NAME);
                    intent.putExtra(AtomSpectraService.EXTRA_DATA_ARRAY_LONG_SERIAL_SCOPE_COUNTS, new long[1024]);
                    intent.putExtra(AtomSpectraService.EXTRA_DATA_ARRAY_LONG_SERIAL_SPECTRUM_COUNTS, histogram);
                    intent.putExtra(AtomSpectraService.EXTRA_DATA_INT_CP1S, cps);
                    intent.putExtra(AtomSpectraService.EXTRA_DATA_INT_FG_TOTAL_TIME, total_time);
                    intent.putExtra(EXTRA_DATA_TYPE, CODE_DATA);
                    intent.putExtra(EXTRA_DATA_BOOL_HISTOGRAM_COMPLETE, histBinsMissing == 0);
                    Arrays.fill(histBinsReceived, false);
                    histBinsMissing = Constants.NUM_HIST_POINTS;
                    context.sendBroadcast(intent);
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
        public final byte code;
        //        public final long time;  //for timeout
        public final long Number;
        private static long NextNumber = 1;
        private static final Object sync = new Object();

        CommandCode(String cmd, String id) {
            command = cmd.getBytes(Charset.defaultCharset());
            this.id = id;
            code = CODE_TEXT; //shows command as text
//            time = (new Date()).getTime() + DROP_TIMEOUT;  //time to drop as not delivered
            synchronized (sync) {
                Number = NextNumber;
                NextNumber++;
                if (NextNumber > 1e9)
                    NextNumber = 1;
            }
        }

        CommandCode(byte code, byte[] cmd, String id) {
            command = cmd;
            this.id = id;
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

    private final LinkedList<CommandCode> Commands = new LinkedList<>();
    private long AnswerNumber = 0;
    private final Object syncCommand = new Object();

    private boolean sendPacket() {
        synchronized (syncCommand) {
            //nothing or nowhere to send
            if (Port == null || !Port.isOpen() || Commands.isEmpty())
                return false;

            final CommandCode cmd = Commands.getFirst();
            //waiting the device to answer
            if (AnswerNumber == cmd.Number) {
                return true;
            }

            //AnswerNumber=0 - nothing is sent before
            int crc = 0xFFFF;
            crc = crc16(crc, cmd.code);
            ArrayList<Byte> outputArray = new ArrayList<>();
            outputArray.add((byte) PACKET_BEGIN);
            outputArray.add((byte) PACKET_START);
            addWithEscape(outputArray, cmd.code);
            for (byte datum : cmd.command) {
                addWithEscape(outputArray, datum);
                crc = crc16(crc, datum);
            }
            byte d = (byte) (crc & 0xFF);
            addWithEscape(outputArray, d);
            d = (byte) ((crc >> 8) & 0xFF);
            addWithEscape(outputArray, d);
            outputArray.add((byte) PACKET_END);
            byte[] command_data = new byte[outputArray.size()];
            for (int i = 0; i < outputArray.size(); i++) {
                command_data[i] = outputArray.get(i);
            }
            try {
                AnswerNumber = cmd.Number;
                Port.write(command_data, SERIAL_MANAGER_WRITE_TIMEOUT);
                asyncTasksHandler.postDelayed(new Runnable() {
                    final long Number = cmd.Number;

                    @Override
                    public void run() {
                        final Context ctx = context;
                        if (ctx == null) return;
                        CommandCode code = null;
                        synchronized (syncCommand) {
                            if (!Commands.isEmpty() && Number == AnswerNumber) {
                                //timeout is here, remove old packet
                                code = Commands.pop();
                                AnswerNumber = 0;
                            }
                        }
                        if (code != null) {
                            AtomSpectraLog.addMessage(ctx, "Serial command timed out: " + new String(code.command));
                            Intent intentText = new Intent(Constants.ACTION.ACTION_USB_HAS_ANSWER).setPackage(Constants.PACKAGE_NAME);
                            intentText.putExtra(EXTRA_RESULT, COMMAND_RESULT_TIMEOUT);
                            intentText.putExtra(EXTRA_NUMBER, code.Number);
                            intentText.putExtra(EXTRA_COMMAND, new String(code.command));
                            intentText.putExtra(EXTRA_ID, code.id);
                            ctx.sendBroadcast(intentText);
                        }
                        sendPacket(); //try to send next packet
                    }
                }, CommandCode.DROP_TIMEOUT + 500);
            } catch (Exception e) {
                AtomSpectraLog.addMessage(context, "USB write failed: " + e.getMessage());
                CommandCode failed = Commands.pop();
                AnswerNumber = 0;
                Intent intentText = new Intent(Constants.ACTION.ACTION_USB_HAS_ANSWER).setPackage(Constants.PACKAGE_NAME);
                intentText.putExtra(EXTRA_RESULT, COMMAND_RESULT_ERR);
                intentText.putExtra(EXTRA_NUMBER, failed.Number);
                intentText.putExtra(EXTRA_COMMAND, new String(failed.command));
                intentText.putExtra(EXTRA_ID, failed.id);
                context.sendBroadcast(intentText);
                sendPacket();
                return false;
            }
        }
        return true;
    }

    public boolean sendCommand(byte cmd, @NonNull byte[] data, @NonNull String id) {
        if (id.isEmpty())
            return false;
        synchronized (syncCommand) {
            Commands.add(new CommandCode(cmd, data, id));
        }
        return sendPacket();
    }

    public boolean sendTextCommand(@NonNull String command, @NonNull String id) {
        if (id.isEmpty())
            return false;
        synchronized (syncCommand) {
            Commands.add(new CommandCode(command, id));
        }
        return sendPacket();
    }

    //get parameter value from info line or null if none
    public static String getParameter(@NonNull String data, @NonNull String parameter) {
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
        if (processingThread != null) {
            processingThread.interrupt();
            try {
                processingThread.join();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            processingThread = null;
        }
    }

    // local thread method to perform read from circular buffer
    private void processBufferLoop() {
        if (DEBUG_LOG) {
            AtomSpectraLog.addMessage(context, "Packet processing thread started");
        }
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);

        int currentInputDataEnd = inputDataEnd;
        while (true) {
            synchronized (circularBufferSync) {
                // input data end could only be changed under sync block in onNewData
                // if this sync block is reached before onNewData sync block, this thread will wait until notify
                // if this sync block is reached after onNewData sync block and there is new data, this thread will not wait
                while (inputDataEnd == currentInputDataEnd) {
                    try {
                        circularBufferSync.wait();
                    } catch (InterruptedException e) {
                        if (DEBUG_LOG) {
                            AtomSpectraLog.addMessage(context, "Packet processing thread interrupted");
                        }
                        return;
                    }
                }
                currentInputDataEnd = inputDataEnd;
            }

            // called only once per new data arrival, even if contains partial packet in the end, next loop cycle will wait for new data
            findPackets(currentInputDataEnd);
        }
    }

    @Override
    // serial thread method to perform write to circular buffer
    public void onNewData(byte[] data) {
        int currentDataEnd = inputDataEnd;
        int bytesWritten = 0;
        // write bytes to circular buffer
        for (int i = 0; i < data.length; i++) {
            if ((currentDataEnd + 1) % CIRCULAR_BUFFER_SIZE == inputDataHead) {
                int bytesLost = data.length - i;
                AtomSpectraLog.addMessage(context, "Circular buffer overflow: " + bytesLost + " bytes lost");
                break;
            }
            inputData[currentDataEnd] = data[i];
            currentDataEnd = (currentDataEnd + 1) % CIRCULAR_BUFFER_SIZE;
            bytesWritten++;
        }
        if (bytesWritten > 0) {
            // bump up end pointer and notify processing thread
            synchronized (circularBufferSync) {
                inputDataEnd = currentDataEnd;
                circularBufferSync.notify();
            }
        }
    }

    @Override
    public void onRunError(Exception e) {
        AtomSpectraLog.addMessage(context, "USB serial error: " + e.getMessage());
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

package org.fe57.atomspectra.data;

import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Handler;

import androidx.annotation.NonNull;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import org.fe57.atomspectra.AtomSpectraService;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.zip.CRC32;

public class AtomSpectraSerial implements SerialInputOutputManager.Listener {
    private static final int MAX_BUFFER_SIZE = 655360;
    private static final int TIMEOUT = 1000;

    private UsbDevice Device;
    private UsbSerialDriver Driver;
    private UsbDeviceConnection Connection;
    private UsbSerialPort Port;
    private SerialInputOutputManager Manager;
    private Context context;
    private byte[] inputData;
    private int inputDataHead;
    private int inputDataEnd;
    private boolean hasInputData;
    private final Integer updateArray = 0;
    Handler handler;

    public long[] histogram = new long[Constants.NUM_HIST_POINTS];
    public int cps = 0;
    public int total_time = 0;
    public int cpu_load = 0;
    public long lost_impulses = 0;
    public long total_impulse_length = 0;

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

    public final static String EXTRA_ID = "Id";
    public final static String EXTRA_NUMBER = "Number";
    public final static String EXTRA_COMMAND = "Command";
    private final static String SERIAL_ID = "internal";

    public final static String EXTRA_DATA_TYPE =
            "org.fe57.atomspectra.EXTRA_DATA_TYPE";
    public final static String EXTRA_RESULT =
            "org.fe57.atomspectra.EXTRA_DATA_PACKET";

    //Constructor
    public AtomSpectraSerial(Context context) {
        this.context = context;
        handler = new Handler(context.getMainLooper());
        Manager = null;
        Init();
    }

    private void Init() {
        Driver = null;
        Device = null;
        Connection = null;
        Port = null;
        inputData = null;
        inputDataHead = MAX_BUFFER_SIZE - 1;
        inputDataEnd = 0;
        hasInputData = false;
        synchronized (syncCommand) {
            AnswerNumber = 0;
            Commands.clear();
        }
    }

    public void Destroy () {
        if (Port != null && Port.isOpen()) {
            try {
                Port.close();
            }
            catch (Exception ignore) {
                //nothing
            }        }
        if (Manager != null)
            Manager.stop();
        context = null;
        Manager = null;
        handler = null;
    }

    //Test if device is working
    public boolean isOpened () {
        return (Port != null) && (Port.isOpen());
    }

    public boolean isMyDevice (int vendor, int device) {
        if (Device == null || Port == null || !Port.isOpen())
            return false;

        return Device.getVendorId() == vendor && Device.getDeviceId() == device;
    }

    //Open the port
    public boolean Open (@NonNull UsbDevice device) {
        Driver = UsbSerialProber.getDefaultProber().probeDevice(device);
        if (Driver == null) {
            return false;
        }
        Device = device;
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (manager == null) {
            Delete();
            Intent intent = new Intent(Constants.ACTION.ACTION_SOURCE_CHANGED);
            intent.putExtra(AtomSpectraService.EXTRA_SOURCE, AtomSpectraService.EXTRA_SOURCE_AUDIO);
            context.sendBroadcast(intent);
            return false;
        }
        try {
            Connection = manager.openDevice(Driver.getDevice());
            if (Connection == null) {
                //Toast.makeText(context, "No connection", Toast.LENGTH_LONG).show();
                Close();
                return false;
            }
        }
        catch (Exception ignored) {
            Delete();
            Intent intent = new Intent(Constants.ACTION.ACTION_SOURCE_CHANGED);
            intent.putExtra(AtomSpectraService.EXTRA_SOURCE, AtomSpectraService.EXTRA_SOURCE_AUDIO);
            context.sendBroadcast(intent);
            return false;
        }
        Port = Driver.getPorts().get(0);
        try {
            Port.open(Connection);
            Port.setParameters(600000, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            inputData = new byte[MAX_BUFFER_SIZE];
            inputDataHead = MAX_BUFFER_SIZE - 1;
            inputDataEnd = 0;
            hasInputData = false;
            Manager = new SerialInputOutputManager(Port, this);
            Manager.start();
        }
        catch (Exception ignored) {
            Delete();
            Intent intent = new Intent(Constants.ACTION.ACTION_SOURCE_CHANGED);
            intent.putExtra(AtomSpectraService.EXTRA_SOURCE, AtomSpectraService.EXTRA_SOURCE_AUDIO);
            context.sendBroadcast(intent);
            return false;
        }
        return true;
    }

    //delete all data except context
    private void Delete() {
        if (Manager != null)
            Manager.stop();
        Manager = null;
        if (Port != null) {
            try {
                Port.close();
            }
            catch (Exception ignore) {
                //nothing
            }
        }
        synchronized (syncCommand) {
            Commands.clear();
            AnswerNumber = 0;
        }
        Init();
    }

    //public method to remove data
    public void Close () {
        Delete();
    }

    //clear histogram
    public void ClearHistogram() {
        if (Port == null || !Port.isOpen())
            return;
        for (int i = 0; i < Constants.NUM_HIST_POINTS; i++)
            histogram[i] = 0;
        cps = 0;
        total_time = 0;
        cpu_load = 0;
        lost_impulses = 0;
        total_impulse_length = 0;
        sendTextCommand("-rst", SERIAL_ID);
    }

    //CRC-16 (MODBUS version)
    public static int crc16 (int crc, byte data) {
        crc = crc ^ (data & 0xFF);
        for (int i = 0; i < 8; ++i) {
            if ((crc & 0x0001) != 0)
                crc = (crc >>> 1) ^ 0xA001;
            else
                crc = (crc >>> 1);
        }
        return crc;
    }

    public static long crc32 (byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }

    //Test if byte is needed to be escaped
    private static boolean isSpecialByte (byte b) {
        return (b == (byte)PACKET_BEGIN) || (b == (byte)PACKET_START) || (b == (byte)PACKET_END) || (b == (byte)PACKET_ESC);
    }

    private void addWithEscape (@NonNull ArrayList<Byte> array, byte b) {
        if (isSpecialByte(b)) {
            array.add((byte)PACKET_ESC);
            array.add((byte)~b);
        } else {
            array.add(b);
        }
    }

    //main method to search packets from input stream
    //returns packet with leading code operation and trailing crc16 two-byte code
    private byte[] searchPacket () {
        if (inputData == null || !hasInputData)
            return null;
        int arrayHead, arrayEnd;
        synchronized (updateArray) {
            arrayHead = inputDataHead;
            arrayEnd = inputDataEnd;
        }
        //Remove data before first start byte
        while ((inputData[arrayHead] & 0xFF) != PACKET_BEGIN) {
            arrayHead = (arrayHead + 1) % MAX_BUFFER_SIZE;
            if (arrayHead == arrayEnd) {
                synchronized (updateArray) {
                    inputDataHead = arrayHead;
                    hasInputData = (inputDataHead != inputDataEnd);
                }
                return null;
            }
        }
        int curPos = (arrayHead + 1) % MAX_BUFFER_SIZE;
        if (curPos == arrayEnd) {
            synchronized (updateArray) {
                inputDataHead = arrayHead;
            }
            return null;
        }
        if ((inputData[curPos] & 0xFF) != PACKET_START) {
            synchronized (updateArray) {
                inputDataHead = curPos;
                hasInputData = (inputDataHead != inputDataEnd);
            }
            return searchPacket();
        }
        //We have 0xFF, 0xFE as two first bytes
        //Search for packet end
        int packetEnd = -1;
        byte byteBefore = -1;
        int numBytes = 0;
        int packetBegin = (curPos + 1) % MAX_BUFFER_SIZE;
        for (curPos = packetBegin; curPos != arrayEnd; curPos = (curPos + 1) % MAX_BUFFER_SIZE) {
            if((inputData[curPos] & 0xFF) == PACKET_END) {
                packetEnd = curPos;
                break;
            }
            byteBefore = inputData[curPos];
            if ((byteBefore & 0xFF) != PACKET_ESC)
                numBytes++;
        }
        //Packet has begin and no end. Wait for more data
        if (packetEnd == -1)
            return null;
        //Last byte must not be PACKET_ESC. Drop packet begin marker and try again
        if (((byteBefore & 0xFF) == PACKET_ESC) || (numBytes < 3)) {
            synchronized (updateArray) {
                inputDataHead = (inputDataHead + 1) % MAX_BUFFER_SIZE;
                hasInputData = (inputDataHead != inputDataEnd);
            }
            return searchPacket();
        }
        //Have full packet. Get it and test it
        byte d;
        byte[] res = new byte[numBytes];  //with crc16
        int bytesSaved = 0;
        boolean isSpecialChar = false;
        int crc = 0xFFFF;
        for (int i = packetBegin; i != packetEnd; i = (i + 1) % MAX_BUFFER_SIZE) {
            d = inputData[i];
            if (isSpecialChar) {
                d = (byte) (~d);
                res[bytesSaved] = d;
                bytesSaved++;
                isSpecialChar = false;
                crc = crc16(crc, d);
            } else {
                if ((d & 0xFF) != PACKET_ESC) {
                    res[bytesSaved] = d;
                    bytesSaved++;
                    crc = crc16(crc, d);
                } else {
                    isSpecialChar = true;
                }
            }
        }
        synchronized (updateArray) {
            inputDataHead = (packetEnd + 1) % MAX_BUFFER_SIZE;
            hasInputData = (inputDataHead != inputDataEnd);
        }
        if (crc != 0)
            return searchPacket();
        return res;
    }

    private void findPackets() {
        byte[] newPacket;
        while (true) {
            newPacket = searchPacket();
            if (newPacket == null || newPacket.length == 0) {
                return;
            }
            int code = newPacket[0] & 0xFF;
            switch (code) {
                case CODE_HIST:
                    if (newPacket.length % 4 != 1)
                        return;
                    int pos = (newPacket[1] & 0xFF) | ((newPacket[2] & 0xFF) << 8);
                    int bin;
                    for (int i = 3; i < newPacket.length - 2; i += 4) {
                        if (pos >= Constants.NUM_HIST_POINTS)
                            break;
                        bin = (newPacket[i] & 0xFF) |
                                ((newPacket[i + 1] & 0xFF) << 8) |
                                ((newPacket[i + 2] & 0xFF) << 16) |
                                ((newPacket[i + 3] & 0xFF) << 24);
                        histogram[pos] = bin;
                        pos++;
                    }
                    break;

                case CODE_SCOPE:
                    if (newPacket.length % 2 != 1)
                        break;
                    long[] scope = new long[(newPacket.length - 3) >> 1];
                    for (int i = 1, j = 0; i < newPacket.length - 2; i += 2, j += 1) {
                        scope[j] = (newPacket[i] & 0xFF) | ((newPacket[i + 1] & 0xFF) << 8);
                    }
                    Intent intentScope = new Intent(Constants.ACTION.ACTION_HAS_DATA);
                    intentScope.putExtra(AtomSpectraService.EXTRA_DATA_ARRAY_LONG_COUNTS, histogram);
                    intentScope.putExtra(AtomSpectraService.EXTRA_DATA_SCOPE_COUNTS, scope);
                    intentScope.putExtra(EXTRA_DATA_TYPE, CODE_SCOPE);
                    context.sendBroadcast(intentScope);
                    break;

                case CODE_TEXT:
                    synchronized (syncCommand) {
                        Intent intentText = new Intent(Constants.ACTION.ACTION_HAS_ANSWER);
                        int newLength = newPacket.length - 3;    //remove 0x03 code operation and trailing crc16 two-byte code
                        byte[] answerPacket = new byte[newLength];   //remove first code byte and last 0x0D,0x0A bytes
                        System.arraycopy(newPacket, 1, answerPacket, 0, newLength);
                        String answer = new String(answerPacket);
                        //fix some sort of error in Spectra Pro
                        if (COMMAND_RESULT_OK2.equals(answer))
                            answer = COMMAND_RESULT_OK;
                        intentText.putExtra(EXTRA_RESULT, answer);
                        intentText.putExtra(EXTRA_ID, Commands.getFirst().id);
                        intentText.putExtra(EXTRA_COMMAND, new String(Commands.getFirst().command));
                        intentText.putExtra(EXTRA_NUMBER, Commands.pop().Number);
                        AnswerNumber = 0; //data received
                        context.sendBroadcast(intentText);
                    }
                    sendPacket(); //send next packet
                    break;

                case CODE_DATA:
                    if (newPacket.length < (11 + 2))
                        break;
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
                    if (newPacket.length >= (15 + 2)) {
                        lost_impulses = (newPacket[11] & 0xFF) |
                                ((newPacket[12] & 0xFF) << 8) |
                                ((newPacket[13] & 0xFF) << 16) |
                                ((long) (newPacket[14] & 0xFF) << 24);
                    }
                    if (newPacket.length >= (28 + 2)) {
                        //TODO: get information about the detector
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
                    Intent intent = new Intent(Constants.ACTION.ACTION_HAS_DATA);
                    intent.putExtra(AtomSpectraService.EXTRA_DATA_SCOPE_COUNTS, new long[1024]);
                    intent.putExtra(AtomSpectraService.EXTRA_DATA_ARRAY_LONG_COUNTS, histogram);
                    intent.putExtra(AtomSpectraService.EXTRA_DATA_INT_CPS, cps);
                    intent.putExtra(AtomSpectraService.EXTRA_DATA_TOTAL_TIME, total_time);
                    intent.putExtra(EXTRA_DATA_TYPE, CODE_DATA);
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
        private static final Integer sync = 1;

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
    private final Integer syncCommand = 1;

    private boolean sendPacket () {
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
                Port.write(command_data, TIMEOUT);
                handler.postDelayed(new Runnable() {
                    final long Number = cmd.Number;
                    @Override
                    public void run() {
                        CommandCode code = null;
                        synchronized (syncCommand) {
                            if (!Commands.isEmpty() && Number == AnswerNumber) {
                                //timeout is here, remove old packet
                                code = Commands.pop();
                                AnswerNumber = 0;
                            }
                        }
                        if (code != null) {
                            Intent intentText = new Intent(Constants.ACTION.ACTION_HAS_ANSWER);
                            intentText.putExtra(EXTRA_RESULT, COMMAND_RESULT_TIMEOUT);
                            intentText.putExtra(EXTRA_NUMBER, code.Number);
                            intentText.putExtra(EXTRA_COMMAND, new String(code.command));
                            intentText.putExtra(EXTRA_ID, code.id);
                            context.sendBroadcast(intentText);
                        }
                        sendPacket(); //try to send next packet
                    }
                }, CommandCode.DROP_TIMEOUT + 500);
            } catch (Exception ignored) {
                Close();
                Intent intent = new Intent(Constants.ACTION.ACTION_SOURCE_CHANGED);
                intent.putExtra(AtomSpectraService.EXTRA_SOURCE, AtomSpectraService.EXTRA_SOURCE_AUDIO);
                context.sendBroadcast(intent);
                return false;
            }
        }
        return true;
    }

    public boolean sendCommand (byte cmd, @NonNull byte[] data, @NonNull String id) {
        if (id.isEmpty())
            return false;
        synchronized (syncCommand) {
            Commands.add(new CommandCode(cmd, data, id));
        }
        return sendPacket();
    }

    public boolean sendTextCommand (@NonNull String command, @NonNull String id) {
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

    @Override
    public void onNewData(byte[] data) {
        synchronized (updateArray) {
            for (byte datum : data) {
                if (inputDataEnd == inputDataHead && hasInputData)
                    break;
                inputData[inputDataEnd] = datum;
                inputDataEnd = (inputDataEnd + 1) % MAX_BUFFER_SIZE;
                hasInputData = true;
            }
        }
        findPackets();
    }

    @Override
    public void onRunError(Exception e) {
        //nothing
    }
}

package org.fe57.atomspectra;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

final class BluZFrameDecoder {
    static final int CHANNEL_COUNT = 4096;
    private static final int NOTIFICATION_SIZE = 244;
    private static final int SPECTRUM_OFFSET = 1424;
    private static final int MAX_NOTIFICATIONS = 73;
    private static final int FLAG_RUNNING = 0x02;
    private static final int HW_INFO_TYPE = 7;
    private static final int PROTOCOL_MAJOR = 1;
    private final byte[] buffer = new byte[MAX_NOTIFICATIONS * NOTIFICATION_SIZE];
    private int remaining;
    private int length;
    private int checksum;
    private int type;
    private int version;
    private int expected;
    private boolean running;
    private int format;
    private int payloadBytes;

    void reset() {
        remaining = 0;
        length = 0;
        checksum = 0;
    }

    boolean isAssembling() {
        return remaining > 0;
    }

    /**
     * Whether the packet begins a new frame. Continuations of a frame carry spectrum bytes, which may
     * also look like a header, so while one is assembling nothing starts a frame.
     */
    boolean startsFrame(byte[] packet) {
        boolean header = packet != null && packet.length >= 4 && packet[0] == '<' && packet[1] == 'B' && packet[2] == '>';
        return header && remaining == 0;
    }

    /**
     * Notifications needed for a frame whose spectrum takes the given number of bytes.
     */
    static int notificationsFor(int payloadBytes) {
        return (SPECTRUM_OFFSET + payloadBytes + 2 + NOTIFICATION_SIZE - 1) / NOTIFICATION_SIZE;
    }

    Frame accept(byte[] packet) {
        if (packet == null || (packet.length != NOTIFICATION_SIZE && packet.length != NOTIFICATION_SIZE + 4)) {
            reset();
            throw new IllegalArgumentException("Invalid BluZ notification length");
        }
        if (startsFrame(packet)) {
            reset();
            beginFrame(packet);
        } else if (remaining == 0) {
            return null;
        }
        remaining--;
        int payloadLength = remaining == 0 ? 242 : NOTIFICATION_SIZE;
        if (length + payloadLength > buffer.length) {
            reset();
            throw new IllegalArgumentException("BluZ frame overflow");
        }
        System.arraycopy(packet, 0, buffer, length, payloadLength);
        length += payloadLength;
        for (int index = 0; index < payloadLength; index++) {
            checksum = (checksum + (packet[index] & 255)) & 65535;
        }
        if (remaining > 0) return null;
        int received = (packet[242] & 255) | ((packet[243] & 255) << 8);
        if (checksum != received) {
            reset();
            throw new IllegalArgumentException("BluZ checksum mismatch");
        }
        // only a complete frame with a valid checksum proves the version is real and not a stray header
        if (version != PROTOCOL_MAJOR) {
            reset();
            throw new UnsupportedVersionException(version);
        }
        int channels = type == 0 || type == HW_INFO_TYPE ? 0 : 1024 << ((type - 1) % 3);
        byte[] snapshot = Arrays.copyOf(buffer, 100);
        long[] histogram = type == HW_INFO_TYPE ? new long[0] : decodeSpectrum(channels);
        Frame frame = new Frame(type, channels, snapshot, histogram, running, format == 1, payloadBytes, expected);
        reset();
        return frame;
    }

    /**
     * Reads the header of the first notification: byte 3 is the frame type, byte 4 the flags, byte 5
     * the number of notifications, byte 6 the major and byte 7 the minor protocol version, byte 96 the spectrum format.
     */
    private void beginFrame(byte[] packet) {
        type = packet[3] & 255;
        if (type > HW_INFO_TYPE) throw new IllegalArgumentException("Unknown BluZ frame type");
        version = packet[6] & 255; // major only, the minor version (byte 7) never affects compatibility
        running = (packet[4] & FLAG_RUNNING) != 0;
        format = packet[96] & 255;
        expected = packet[5] & 255;
        if (expected == 0 || expected > MAX_NOTIFICATIONS) {
            throw new IllegalArgumentException("BluZ frame declares " + expected + " notifications");
        }
        if (type == HW_INFO_TYPE && expected != 1) {
            throw new IllegalArgumentException("BluZ hardware info must be one notification");
        }
        remaining = expected;
    }

    /**
     * Decodes the spectrum of a frame and checks that the rest of the frame is zero padding of the
     * declared number of notifications. History spectra are validated only.
     */
    private long[] decodeSpectrum(int channels) {
        int end = length;
        long[] histogram = new long[channels];
        int consumed = 0;
        if (channels > 0) {
            try {
                if (format == 0) consumed = BluZSpectrumCodec.readRaw(buffer, SPECTRUM_OFFSET, end, histogram);
                else if (format == 1) consumed = BluZSpectrumCodec.decode(buffer, SPECTRUM_OFFSET, end, histogram);
                else throw new IllegalArgumentException("Unknown BluZ spectrum format " + format);
            } catch (IllegalArgumentException error) {
                reset();
                throw error;
            }
        }
        for (int index = SPECTRUM_OFFSET + consumed; index < end; index++) {
            if (buffer[index] != 0) {
                reset();
                throw new IllegalArgumentException("BluZ frame padding is not zero");
            }
        }
        if (notificationsFor(consumed) != expected) {
            reset();
            throw new IllegalArgumentException("BluZ frame has extra padding notifications");
        }
        payloadBytes = consumed;
        return type >= 1 && type <= 3 ? histogram : new long[0];
    }

    private static final double[] BATTERY_VOLTS = {2.9, 3.0, 3.3, 3.5, 3.6, 3.7, 3.8, 3.9, 4.0, 4.1, 4.2};
    private static final int[] BATTERY_PERCENT = {0, 1, 5, 10, 15, 30, 50, 70, 80, 90, 100};

    /**
     * Battery charge for a voltage, interpolated over the discharge curve used by the BluZ app.
     */
    static int batteryPercent(double volts) {
        if (volts <= BATTERY_VOLTS[0]) return BATTERY_PERCENT[0];
        int last = BATTERY_VOLTS.length - 1;
        if (volts >= BATTERY_VOLTS[last]) return BATTERY_PERCENT[last];
        int upper = 1;
        while (volts > BATTERY_VOLTS[upper]) upper++;
        double share = (volts - BATTERY_VOLTS[upper - 1]) / (BATTERY_VOLTS[upper] - BATTERY_VOLTS[upper - 1]);
        return (int) Math.round(BATTERY_PERCENT[upper - 1] + share * (BATTERY_PERCENT[upper] - BATTERY_PERCENT[upper - 1]));
    }

    static long uint32(byte[] bytes, int offset) {
        return ((long) bytes[offset] & 255)
                | (((long) bytes[offset + 1] & 255) << 8)
                | (((long) bytes[offset + 2] & 255) << 16)
                | (((long) bytes[offset + 3] & 255) << 24);
    }

    /**
     * The device sends a protocol version this decoder does not know: newer firmware needs a newer app,
     * older firmware needs an update.
     */
    static final class UnsupportedVersionException extends IllegalArgumentException {
        UnsupportedVersionException(int version) {
            super("Unsupported BluZ protocol major version " + version);
        }
    }

    static final class Frame {
        final int type;
        final int channels;
        final byte[] settings;
        final long[] histogram;
        /**
         * The device is collecting; a spectrum frame is also sent on request while it is stopped.
         */
        final boolean running;
        final boolean compressed;
        /**
         * Size of the spectrum data without padding and the number of notifications.
         */
        final int payloadBytes;
        final int notifications;
        final double time;
        final long pulses;
        final int cps;
        /**
         * Device temperature, °C; null when the frame has no valid reading.
         */
        final Integer temperatureC;
        /**
         * Battery voltage, V; null when the frame has no valid reading.
         */
        final Double batteryVolts;

        Frame(int type, int channels, byte[] settings, long[] histogram, boolean running, boolean compressed,
              int payloadBytes, int notifications) {
            this.type = type;
            this.channels = channels;
            this.settings = settings;
            this.histogram = histogram;
            this.running = running;
            this.compressed = compressed;
            this.payloadBytes = payloadBytes;
            this.notifications = notifications;
            time = uint32(settings, 86);
            pulses = uint32(settings, 90);
            cps = (int) Math.min(Integer.MAX_VALUE, uint32(settings, 14));
            ByteBuffer bytes = ByteBuffer.wrap(settings).order(ByteOrder.LITTLE_ENDIAN);
            float temperature = bytes.getFloat(26);
            float voltage = bytes.getFloat(30);
            temperatureC = Float.isFinite(temperature) ? Math.round(temperature) : null;
            batteryVolts = Float.isFinite(voltage) && voltage > 0 ? Math.round(voltage * 100) / 100.0 : null;
        }

        boolean isNormal() {
            return type <= 3;
        }

        boolean hasSpectrum() {
            return type >= 1 && type <= 3;
        }

        boolean isCollecting() {
            return running;
        }

        boolean isHardwareInfo() {
            return type == HW_INFO_TYPE;
        }

        double[] calibration() {
            int[] offsets = {70, 66, 82, 78, 74};
            double[] coefficients = new double[offsets.length];
            ByteBuffer bytes = ByteBuffer.wrap(settings).order(ByteOrder.LITTLE_ENDIAN);
            for (int index = 0; index < offsets.length; index++) {
                coefficients[index] = bytes.getFloat(offsets[index]);
            }
            return coefficients;
        }
    }
}
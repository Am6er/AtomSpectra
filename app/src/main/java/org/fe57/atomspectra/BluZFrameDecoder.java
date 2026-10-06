package org.fe57.atomspectra;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

final class BluZFrameDecoder {
    static final int CHANNEL_COUNT = 4096;
    private final byte[] buffer = new byte[40 * 244];
    private int remaining;
    private int length;
    private int checksum;
    private int type;
    private int packetLength;

    void reset() {
        remaining = 0;
        length = 0;
        checksum = 0;
    }

    boolean isAssembling() {
        return remaining > 0;
    }

    Frame accept(byte[] packet) {
        if (packet == null || (packet.length != 244 && packet.length != 248)) {
            reset();
            throw new IllegalArgumentException("Invalid BluZ notification length");
        }
        boolean header = packet[0] == '<' && packet[1] == 'B' && packet[2] == '>';
        if (header) {
            boolean interrupted = remaining > 0;
            reset();
            type = packet[3] & 255;
            switch (type) {
                case 0:
                    remaining = 6;
                    break;
                case 1:
                case 4:
                    remaining = 16;
                    break;
                case 2:
                case 5:
                    remaining = 23;
                    break;
                case 3:
                case 6:
                    remaining = 40;
                    break;
                default:
                    throw new IllegalArgumentException("Unknown BluZ frame type");
            }
            packetLength = packet.length;
            if (interrupted) droppedFrames++;
        } else if (remaining == 0) {
            return null;
        }
        if (packetLength != packet.length) {
            reset();
            throw new IllegalArgumentException("BluZ notification size changed within frame");
        }
        remaining--;
        int payloadLength = remaining == 0 ? 242 : 244;
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
        int channels = type == 0 ? 0 : 1024 << ((type - 1) % 3);
        if (length < 1424 + channels * 2) {
            reset();
            throw new IllegalArgumentException("Incomplete BluZ frame");
        }
        byte[] snapshot = Arrays.copyOf(buffer, 100);
        int bits = snapshot[96] & 255;
        if (bits < 16 || bits > 32) bits = 20;
        long[] histogram = null;
        if (type >= 1 && type <= 3) {
            histogram = new long[CHANNEL_COUNT];
            int factor = CHANNEL_COUNT / channels;
            double multiplier = bits * Math.log(2.0) / 65535.0;
            for (int channel = 0; channel < channels; channel++) {
                int offset = 1424 + channel * 2;
                int encoded = (buffer[offset] & 255) | ((buffer[offset + 1] & 255) << 8);
                long count = Math.max(0, Math.round(Math.expm1(encoded * multiplier)));
                long quotient = count / factor;
                int remainder = (int) (count % factor);
                for (int part = 0; part < factor; part++) {
                    histogram[channel * factor + part] = quotient + (part < remainder ? 1 : 0);
                }
            }
        }
        Frame frame = new Frame(type, channels, snapshot, histogram);
        reset();
        return frame;
    }

    private int droppedFrames;

    int takeDroppedFrames() {
        int count = droppedFrames;
        droppedFrames = 0;
        return count;
    }

    static long uint32(byte[] bytes, int offset) {
        return ((long) bytes[offset] & 255)
                | (((long) bytes[offset + 1] & 255) << 8)
                | (((long) bytes[offset + 2] & 255) << 16)
                | (((long) bytes[offset + 3] & 255) << 24);
    }

    static final class Frame {
        final int type;
        final int channels;
        final byte[] settings;
        final long[] histogram;
        final double time;
        final long pulses;
        final int cps;

        Frame(int type, int channels, byte[] settings, long[] histogram) {
            this.type = type;
            this.channels = channels;
            this.settings = settings;
            this.histogram = histogram;
            time = uint32(settings, 86);
            pulses = uint32(settings, 90);
            cps = (int) Math.min(Integer.MAX_VALUE, uint32(settings, 14));
        }

        boolean isNormal() {
            return type <= 3;
        }

        boolean isCollecting() {
            return type >= 1 && type <= 3;
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
package org.fe57.atomspectra;

import java.util.Map;

final class BluZCommandCodec {
    static final int PACKET_LENGTH = 255;
    static final int WRITE_CHUNK_LENGTH = 248;

    private BluZCommandCodec() {
    }

    static byte[] command(int code) {
        byte[] packet = new byte[PACKET_LENGTH];
        packet[0] = '<';
        packet[1] = 'S';
        packet[2] = '>';
        packet[3] = (byte) code;
        checksum(packet);
        return packet;
    }

    static byte[] resolution4096(byte[] snapshot) {
        if (snapshot == null || snapshot.length < 100) {
            throw new IllegalArgumentException("Missing BluZ settings snapshot");
        }
        byte[] packet = command(0);
        for (int level = 0; level < 3; level++) {
            int readOffset = 54 + level * 2;
            int writeOffset = 4 + level * 4;
            packet[writeOffset + 2] = snapshot[readOffset + 1];
            packet[writeOffset + 3] = snapshot[readOffset];
        }
        reverseFloat(snapshot, 46, packet, 16);
        packet[20] = snapshot[60];
        int[] readOffsets = {34, 38, 42, 62, 66, 70, 74, 78, 82};
        int[] writeOffsets = {21, 25, 29, 39, 43, 47, 51, 55, 59};
        for (int index = 0; index < readOffsets.length; index++) {
            reverseFloat(snapshot, readOffsets[index], packet, writeOffsets[index]);
        }
        System.arraycopy(snapshot, 50, packet, 33, 4);
        packet[37] = 2;
        int flags = snapshot[61] & 255;
        packet[38] = (byte) ((flags & 1) | ((snapshot[97] & 7) << 1)
                | ((flags & 2) << 3) | ((flags & 4) << 3));
        System.arraycopy(snapshot, 94, packet, 63, 2);
        packet[65] = snapshot[96];
        checksum(packet);
        return packet;
    }

    /**
     * Command 0 that keeps every setting of the snapshot except the changed ones, at 4096 channels.
     * The changes are keyed as in {@link BluZSettings}.
     */
    static byte[] settings4096(byte[] snapshot, Map<String, Object> changes) {
        byte[] packet = resolution4096(snapshot);
        int[] calibrationOffsets = {47, 43, 59, 55, 51};
        for (Map.Entry<String, Object> change : changes.entrySet()) {
            String key = change.getKey();
            Object value = change.getValue();
            int calibration = BluZSettings.calibrationIndex(key);
            if (calibration >= 0) {
                double requested = BluZSettings.number(value);
                float stored = (float) requested;
                if (!Double.isFinite(requested) || !Float.isFinite(stored)) {
                    throw new IllegalArgumentException("Invalid BluZ calibration coefficient");
                }
                int bits = Float.floatToIntBits(stored);
                for (int byteIndex = 0; byteIndex < 4; byteIndex++) {
                    packet[calibrationOffsets[calibration] + byteIndex] = (byte) (bits >>> (24 - byteIndex * 8));
                }
                continue;
            }
            switch (key) {
                case BluZSettings.PULSE_LED:
                    setBit(packet, 20, 0, flag(value));
                    break;
                case BluZSettings.PULSE_SOUND:
                    setBit(packet, 20, 1, flag(value));
                    break;
                case BluZSettings.AUTOSTART:
                    setBit(packet, 38, 0, flag(value));
                    break;
                case BluZSettings.SOUND_DIV10:
                    setBit(packet, 38, 4, flag(value));
                    break;
                case BluZSettings.LED_DIV10:
                    setBit(packet, 38, 5, flag(value));
                    break;
                case BluZSettings.ADC_SAMPLE:
                    int sample = integer(value, 7);
                    packet[38] = (byte) ((packet[38] & ~0x0E) | (sample << 1));
                    break;
                case BluZSettings.HV:
                    putUint16(packet, 33, integer(value, 1023));
                    break;
                case BluZSettings.COMPARATOR:
                    putUint16(packet, 35, integer(value, 1023));
                    break;
                default:
                    throw new IllegalArgumentException("Unknown BluZ setting " + key);
            }
        }
        checksum(packet);
        return packet;
    }

    private static boolean flag(Object value) {
        if (!(value instanceof Boolean)) throw new IllegalArgumentException("BluZ setting is not a flag");
        return (Boolean) value;
    }

    private static int integer(Object value, int max) {
        double number = BluZSettings.number(value);
        if (number != Math.rint(number) || number < 0 || number > max) {
            throw new IllegalArgumentException("BluZ setting is out of range");
        }
        return (int) number;
    }

    private static void setBit(byte[] packet, int index, int bit, boolean on) {
        packet[index] = (byte) (on ? packet[index] | (1 << bit) : packet[index] & ~(1 << bit));
    }

    private static void putUint16(byte[] packet, int offset, int value) {
        packet[offset] = (byte) value;
        packet[offset + 1] = (byte) (value >> 8);
    }

    private static void reverseFloat(byte[] snapshot, int readOffset, byte[] packet, int writeOffset) {
        for (int index = 0; index < 4; index++) {
            packet[writeOffset + index] = snapshot[readOffset + 3 - index];
        }
    }

    private static void checksum(byte[] packet) {
        int checksum = 0;
        for (int index = 0; index < 242; index++) checksum += packet[index] & 255;
        packet[242] = (byte) checksum;
        packet[243] = (byte) (checksum >> 8);
    }
}
package org.fe57.atomspectra;

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

    static byte[] calibration4096(byte[] snapshot, double[] coefficients) {
        if (coefficients == null || coefficients.length != 5) {
            throw new IllegalArgumentException("BluZ calibration requires five coefficients");
        }
        byte[] packet = resolution4096(snapshot);
        int[] writeOffsets = {47, 43, 59, 55, 51};
        for (int index = 0; index < coefficients.length; index++) {
            float value = (float) coefficients[index];
            if (!Double.isFinite(coefficients[index]) || !Float.isFinite(value)) {
                throw new IllegalArgumentException("Invalid BluZ calibration coefficient");
            }
            int bits = Float.floatToIntBits(value);
            for (int byteIndex = 0; byteIndex < 4; byteIndex++) {
                packet[writeOffsets[index] + byteIndex] = (byte) (bits >>> (24 - byteIndex * 8));
            }
        }
        checksum(packet);
        return packet;
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
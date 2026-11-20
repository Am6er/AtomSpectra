package org.fe57.atomspectra;

/**
 * Spectrum data of a BluZ protocol version 1 frame: raw uint32 little-endian, or DPCM + ZigZag +
 * varint (base-128, least-significant group first). Counts are returned as unsigned values.
 */
final class BluZSpectrumCodec {
    private static final int MAX_VARINT_BYTES = 5;

    private BluZSpectrumCodec() {
    }

    /**
     * Reads {@code out.length} raw counts from {@code src[offset, limit)}.
     *
     * @return the number of bytes read
     * @throws IllegalArgumentException if the data is shorter than the spectrum
     */
    static int readRaw(byte[] src, int offset, int limit, long[] out) {
        if (limit - offset < 4 * out.length) throw new IllegalArgumentException("Truncated BluZ raw spectrum");
        for (int index = 0; index < out.length; index++) {
            out[index] = BluZFrameDecoder.uint32(src, offset + 4 * index);
        }
        return 4 * out.length;
    }

    /**
     * Decodes {@code out.length} channels from {@code src[offset, limit)}. The difference to the
     * previous channel is taken modulo 2^32, the first channel is compared with 0.
     *
     * @return the number of bytes read, so the caller can check the padding after the data
     * @throws IllegalArgumentException if the data is truncated or a varint is malformed
     */
    static int decode(byte[] src, int offset, int limit, long[] out) {
        int pos = offset;
        int previous = 0;
        for (int channel = 0; channel < out.length; channel++) {
            long value = 0;
            for (int count = 0; ; count++) {
                if (pos >= limit) throw new IllegalArgumentException("Truncated BluZ spectrum at channel " + channel);
                if (count == MAX_VARINT_BYTES) throw new IllegalArgumentException("BluZ varint is too long at channel " + channel);
                int b = src[pos++] & 255;
                value |= (long) (b & 0x7F) << (7 * count);
                if ((b & 0x80) == 0) break;
            }
            if (value > 0xFFFFFFFFL) throw new IllegalArgumentException("BluZ varint exceeds 32 bits at channel " + channel);
            int zigzag = (int) value;
            previous += (zigzag >>> 1) ^ -(zigzag & 1);
            out[channel] = previous & 0xFFFFFFFFL;
        }
        return pos - offset;
    }
}

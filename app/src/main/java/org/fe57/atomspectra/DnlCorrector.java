package org.fe57.atomspectra;

import java.util.Arrays;

/**
 * Corrects the differential nonlinearity of a 4096-channel ADC whose channel widths repeat every
 * {@link #PERIOD} channels. The width table is fitted from the raw cumulative spectrum on every call;
 * raw counts added since the previous call are split between the uniform bins their channel overlaps, in
 * proportion to the overlap, and accumulated, so the corrected spectrum never decreases while the device
 * keeps collecting.
 */
final class DnlCorrector {
    static final int CHANNEL_COUNT = 4096;
    private static final int PERIOD = 64;
    // half-width of the local quadratic fit that stands in for the DNL-free spectrum
    private static final int HALF_WIDTH = 12;
    private static final int FIRST_CHANNEL = 100;
    private static final int LAST_CHANNEL = 4000;
    private static final double MIN_BASELINE = 2;
    // folded chi-square (PERIOD - 1 degrees of freedom, Poisson noise) below which the pattern is not told from noise;
    private static final double MIN_CHI2 = 150;
    private static final int ITERATIONS = 3;
    private static final double EDGE_SIGMA = 3;
    private static final double MIN_EDGE = 0.25;
    private static final double MIN_SPREAD_WIDTH = 1e-9;

    private long[] lastRaw;
    private double[] accumulated;

    void reset() {
        lastRaw = null;
        accumulated = null;
    }

    long[] apply(long[] raw) {
        if (raw.length != CHANNEL_COUNT) {
            reset();
            return raw.clone();
        }
        Widths widths = fit(raw);
        boolean anchor = lastRaw == null;
        for (int i = 0; !anchor && i < raw.length; i++) {
            if (raw[i] < lastRaw[i]) anchor = true;
        }
        if (anchor) accumulated = new double[raw.length];
        Layout layout = widths == null ? null : new Layout(widths);
        for (int i = 0; i < raw.length; i++) {
            long added = anchor ? raw[i] : raw[i] - lastRaw[i];
            if (added == 0) continue;
            if (layout != null) spread(i, added, layout);
            else accumulated[i] += added;
        }
        long[] corrected = new long[raw.length];
        for (int i = 0; i < raw.length; i++) corrected[i] = Math.round(accumulated[i]);
        lastRaw = raw.clone();
        return corrected;
    }

    private void spread(int channel, long added, Layout layout) {
        double start = layout.start(channel);
        double end = layout.end(channel);
        double width = end - start;
        int base = channel - channel % PERIOD;
        if (!(width > MIN_SPREAD_WIDTH)) {
            accumulated[base + Math.min(PERIOD - 1, (int) start)] += added;
            return;
        }
        int last = (int) Math.ceil(end) - 1;
        for (int bin = (int) start; bin <= last; bin++) {
            accumulated[base + bin] += added * (Math.min(end, bin + 1) - Math.max(start, bin)) / width;
        }
    }

    /** Channel widths: by channel % PERIOD, and for the first channel of each period by the period itself. */
    private static final class Widths {
        final double[] table;
        final double[] edge;

        Widths(double[] table, double[] edge) {
            this.table = table;
            this.edge = edge;
        }
    }

    /**
     * Where the channels lie on the uniform axis, the widths of each period scaled to add up to PERIOD;
     * positions are in uniform bins counted from the start of the channel's period.
     */
    private static final class Layout {
        private final Widths widths;
        private final double[] before = new double[PERIOD];
        private final double[] scale = new double[CHANNEL_COUNT / PERIOD];

        Layout(Widths widths) {
            this.widths = widths;
            double sum = 0;
            for (int phase = 1; phase < PERIOD; phase++) {
                before[phase] = sum;
                sum += widths.table[phase];
            }
            for (int block = 0; block < scale.length; block++) scale[block] = PERIOD / (sum + widths.edge[block]);
        }

        double start(int channel) {
            int phase = channel % PERIOD;
            return phase == 0 ? 0 : (widths.edge[channel / PERIOD] + before[phase]) * scale[channel / PERIOD];
        }

        double end(int channel) {
            int phase = channel % PERIOD;
            if (phase == PERIOD - 1) return PERIOD;
            double width = phase == 0 ? widths.edge[channel / PERIOD] : widths.table[phase];
            return start(channel) + width * scale[channel / PERIOD];
        }
    }

    /**
     * Relative channel widths by channel % PERIOD, pulled toward 1 by how far the pattern stands out
     * of the counting noise; null while it does not stand out. The first channel of a period takes its own
     * width where it differs from the average first channel by more than EDGE_SIGMA counting errors.
     */
    private static Widths fit(long[] raw) {
        double[] table = new double[PERIOD];
        Arrays.fill(table, 1);
        double[] edge = new double[CHANNEL_COUNT / PERIOD];
        Arrays.fill(edge, 1);
        double[] corrected = new double[raw.length];
        double[] counts = new double[PERIOD];
        double[] expected = new double[PERIOD];
        for (int iteration = 0; iteration < ITERATIONS; iteration++) {
            for (int i = 0; i < raw.length; i++) corrected[i] = raw[i] / table[i % PERIOD];
            double[] baseline = baseline(corrected);
            Arrays.fill(counts, 0);
            Arrays.fill(expected, 0);
            for (int i = FIRST_CHANNEL; i < LAST_CHANNEL; i++) {
                if (baseline[i] <= MIN_BASELINE) continue;
                counts[i % PERIOD] += raw[i];
                expected[i % PERIOD] += baseline[i];
            }
            double countsTotal = 0, expectedTotal = 0;
            for (int phase = 0; phase < PERIOD; phase++) {
                countsTotal += counts[phase];
                expectedTotal += expected[phase];
            }
            double mean = countsTotal / expectedTotal;
            double chi2 = 0;
            for (int phase = 0; phase < PERIOD; phase++) {
                double deviation = counts[phase] - mean * expected[phase];
                chi2 += deviation * deviation / expected[phase];
            }
            if (!(chi2 >= MIN_CHI2)) return null;
            double strength = 1 - (PERIOD - 1) / chi2;
            for (int phase = 0; phase < PERIOD; phase++) {
                table[phase] = 1 + strength * (counts[phase] / expected[phase] / mean - 1);
            }
            for (int block = 0; block < edge.length; block++) {
                int channel = block * PERIOD;
                edge[block] = table[0];
                if (channel < FIRST_CHANNEL || channel >= LAST_CHANNEL || baseline[channel] <= MIN_BASELINE) continue;
                double expectedCounts = mean * baseline[channel];
                double deviation = raw[channel] - expectedCounts;
                double chi2Channel = deviation * deviation / expectedCounts;
                if (chi2Channel > EDGE_SIGMA * EDGE_SIGMA) {
                    double edgeStrength = 1 - EDGE_SIGMA * EDGE_SIGMA / chi2Channel;
                    edge[block] = Math.max(MIN_EDGE, table[0] + edgeStrength * (raw[channel] / expectedCounts - table[0]));
                }
            }
        }
        return new Widths(table, edge);
    }

    // value at each channel of a quadratic fitted to its neighbours, the channel itself left out
    private static double[] baseline(double[] values) {
        double s0 = 0, s2 = 0, s4 = 0;
        for (int x = -HALF_WIDTH; x <= HALF_WIDTH; x++) {
            if (x == 0) continue;
            s0++;
            s2 += x * x;
            s4 += (double) x * x * x * x;
        }
        double determinant = s0 * s4 - s2 * s2;
        int last = values.length - 1;
        double[] result = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            double t0 = 0, t2 = 0;
            for (int x = -HALF_WIDTH; x <= HALF_WIDTH; x++) {
                if (x == 0) continue;
                double value = values[Math.min(last, Math.max(0, i + x))];
                t0 += value;
                t2 += x * x * value;
            }
            result[i] = (s4 * t0 - s2 * t2) / determinant;
        }
        return result;
    }
}

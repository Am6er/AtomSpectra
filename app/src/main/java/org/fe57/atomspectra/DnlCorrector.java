package org.fe57.atomspectra;

import java.util.Arrays;

/**
 * Corrects the differential nonlinearity of a 4096-channel ADC whose channel widths repeat every
 * {@link #PERIOD} channels. The width table is fitted from the raw cumulative spectrum on every call;
 * raw counts added since the previous call are divided by the current table and accumulated, so the
 * corrected spectrum never decreases while the device keeps collecting.
 */
final class DnlCorrector {
    static final int CHANNEL_COUNT = 4096;
    private static final int PERIOD = 64;
    // half-width of the local quadratic fit that stands in for the DNL-free spectrum
    private static final int HALF_WIDTH = 12;
    private static final int FIRST_CHANNEL = 100;
    private static final int LAST_CHANNEL = 4000;
    private static final double MIN_BASELINE = 2;
    // folded chi-square (PERIOD - 1 degrees of freedom, Poisson noise) below which the pattern is not
    // told from noise; set high because BluZ records most events twice, doubling the real variance
    private static final double MIN_CHI2 = 300;
    private static final int ITERATIONS = 3;

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
        double[] table = fit(raw);
        boolean anchor = lastRaw == null;
        for (int i = 0; !anchor && i < raw.length; i++) {
            if (raw[i] < lastRaw[i]) anchor = true;
        }
        if (anchor) accumulated = new double[raw.length];
        long[] corrected = new long[raw.length];
        for (int i = 0; i < raw.length; i++) {
            long added = anchor ? raw[i] : raw[i] - lastRaw[i];
            accumulated[i] += table == null ? added : added / table[i % PERIOD];
            corrected[i] = Math.round(accumulated[i]);
        }
        lastRaw = raw.clone();
        return corrected;
    }

    /**
     * Relative channel widths by channel % PERIOD, pulled toward 1 by how far the pattern stands out
     * of the counting noise; null while it does not stand out.
     */
    private static double[] fit(long[] raw) {
        double[] table = new double[PERIOD];
        Arrays.fill(table, 1);
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
        }
        return table;
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

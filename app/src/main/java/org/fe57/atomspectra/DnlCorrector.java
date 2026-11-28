package org.fe57.atomspectra;

import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.Random;

/**
 * Corrects the differential nonlinearity of a 4096-channel ADC whose channel widths repeat every
 * {@link #PERIOD} channels. The width table is fitted from the raw cumulative spectrum on every call;
 * raw counts added since the previous call are corrected with the current table and accumulated, so the
 * corrected spectrum never decreases while the device keeps collecting.
 */
final class DnlCorrector {
    enum Mode {
        OFF,
        /** added counts are split between the uniform bins their channel overlaps, in proportion to the overlap */
        REBINNING,
        /** each added count is placed in one of the overlapped bins at random, with the overlap as probability */
        JITTER;

        static Mode load(SharedPreferences prefs) {
            if (prefs.contains(Constants.CONFIG.CONF_BLUZ_DNL_MODE)) {
                int id = prefs.getInt(Constants.CONFIG.CONF_BLUZ_DNL_MODE, 0);
                return values()[Math.max(0, Math.min(values().length - 1, id))];
            }
            return prefs.getBoolean(Constants.CONFIG.CONF_BLUZ_DNL_CORRECTION, Constants.BLUZ_DNL_CORRECTION_DEFAULT) ? REBINNING : OFF;
        }
    }

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
    private static final int NORMAL_MIN_EXPECTED = 2000;

    private final Random random = new Random();
    private Mode mode;
    private long[] lastRaw;
    private double[] accumulated;

    void reset() {
        lastRaw = null;
        accumulated = null;
    }

    long[] apply(long[] raw, Mode mode) {
        if (mode != this.mode) {
            reset();
            this.mode = mode;
        }
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
        Layout layout = table == null ? null : new Layout(table);
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
        int phase = channel % PERIOD;
        int base = channel - phase + layout.first[phase];
        double[] share = layout.share[phase];
        if (mode == Mode.REBINNING) {
            for (int k = 0; k < share.length; k++) accumulated[base + k] += added * share[k];
            return;
        }
        long left = added;
        double rest = 1;
        for (int k = 0; k < share.length - 1; k++) {
            long taken = binomial(left, Math.min(1, share[k] / rest));
            accumulated[base + k] += taken;
            left -= taken;
            rest -= share[k];
        }
        accumulated[base + share.length - 1] += left;
    }

    // binomial sample: exact by drawing the gaps between successes of the less likely outcome, normal approximation
    // once that outcome is expected so often that the approximation is indistinguishable from the exact distribution
    private long binomial(long trials, double probability) {
        if (probability <= 0) return 0;
        if (probability >= 1) return trials;
        boolean complement = probability > 0.5;
        double rare = complement ? 1 - probability : probability;
        if (trials * rare >= NORMAL_MIN_EXPECTED) {
            double mean = trials * probability;
            double sample = Math.rint(mean + Math.sqrt(mean * (1 - probability)) * random.nextGaussian());
            return (long) Math.min(trials, Math.max(0, sample));
        }
        double logMiss = Math.log1p(-rare);
        long hits = 0;
        long position = 0;
        while (true) {
            position += (long) Math.floor(Math.log(1 - random.nextDouble()) / logMiss) + 1;
            if (position > trials) break;
            hits++;
        }
        return complement ? trials - hits : hits;
    }

    /**
     * Where the channels of one period lie on the uniform axis, the widths scaled to add up to PERIOD:
     * the first uniform bin each phase overlaps and the share of its counts that falls into each bin from there.
     */
    private static final class Layout {
        final int[] first = new int[PERIOD];
        final double[][] share = new double[PERIOD][];

        Layout(double[] table) {
            double total = 0;
            for (double width : table) total += width;
            double scale = PERIOD / total;
            double start = 0;
            for (int phase = 0; phase < PERIOD; phase++) {
                double end = phase == PERIOD - 1 ? PERIOD : start + table[phase] * scale;
                double width = end - start;
                first[phase] = (int) start;
                int last = (int) Math.ceil(end) - 1;
                share[phase] = new double[last - first[phase] + 1];
                for (int k = 0; k < share[phase].length; k++) {
                    int bin = first[phase] + k;
                    share[phase][k] = (Math.min(end, bin + 1) - Math.max(start, bin)) / width;
                }
                start = end;
            }
        }
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

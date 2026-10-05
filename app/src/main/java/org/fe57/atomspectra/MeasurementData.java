package org.fe57.atomspectra;

import java.util.LinkedList;

/**
 * Shared measurement results computed by the service: count rates, dose rate and the search-mode
 * histories.
 */
public final class MeasurementData {
    public static final MeasurementData instance = new MeasurementData();

    /** Number of samples kept in the search histories. */
    public final static int SEARCH_WINDOW_SIZE = 240;

    /** cps during the last second. */
    public volatile int cp1s = 0;
    /** cps in the user defined energy range during the last second. */
    public volatile int cp1sInterval = 0;

    public volatile DoseRate doseRate = new DoseRate();

    private final Object historyLock = new Object();
    private final LinkedList<Double> doseHistory = new LinkedList<>();
    private final LinkedList<Double> doseCompensatedHistory = new LinkedList<>();
    private final LinkedList<Double> doseIntervalHistory = new LinkedList<>();
    private final LinkedList<Double> doseIntervalHighAlarmHistory = new LinkedList<>();
    private final LinkedList<Double> doseIntervalLowAlarmHistory = new LinkedList<>();
    private final LinkedList<Double> doseIntervalBaselineHistory = new LinkedList<>();
    private final LinkedList<Long> searchHistoryTimestamps = new LinkedList<>();

    private MeasurementData() {
    }

    // --- count rates and dose rate -------------------------------------------------------------

    public void resetCp1s() {
        cp1s = 0;
        cp1sInterval = 0;
    }

    public void resetDoseRate() {
        synchronized (historyLock) {
            doseHistory.clear();
            doseCompensatedHistory.clear();
            doseIntervalHistory.clear();
            doseIntervalHighAlarmHistory.clear();
            doseIntervalLowAlarmHistory.clear();
            doseIntervalBaselineHistory.clear();
            searchHistoryTimestamps.clear();
        }

        doseRate = new DoseRate();
    }

    // --- search histories ----------------------------------------------------------------------

    /** Append one sample to every search history. */
    public void appendSearchSample(long timestamp, double nonCompensated, double compensated,
                                   double intervalCps, double highAlarm, double lowAlarm, double baseline) {
        synchronized (historyLock) {
            append(searchHistoryTimestamps, timestamp);
            append(doseHistory, nonCompensated);
            append(doseCompensatedHistory, compensated);
            append(doseIntervalHistory, intervalCps);
            append(doseIntervalHighAlarmHistory, highAlarm);
            append(doseIntervalLowAlarmHistory, lowAlarm);
            append(doseIntervalBaselineHistory, baseline);
        }
    }

    private static <T> void append(LinkedList<T> history, T value) {
        history.addLast(value);
        if (history.size() > SEARCH_WINDOW_SIZE) {
            history.removeFirst();
        }
    }

    public double[] getCompensatedHistory() {
        return searchHistoryToArray(doseCompensatedHistory);
    }

    public double[] getNonCompensatedHistory() {
        return searchHistoryToArray(doseHistory);
    }

    public double[] getIntervalHistory() {
        return searchHistoryToArray(doseIntervalHistory);
    }

    public double[] getIntervalHighAlarmHistory() {
        return searchHistoryToArray(doseIntervalHighAlarmHistory);
    }

    public double[] getIntervalLowAlarmHistory() {
        return searchHistoryToArray(doseIntervalLowAlarmHistory);
    }

    public double[] getIntervalBaselineHistory() {
        return searchHistoryToArray(doseIntervalBaselineHistory);
    }

    public long[] getTimestamps() {
        return searchHistoryToLongArray(searchHistoryTimestamps);
    }

    private static int searchHistoryArrayStartIndex(int historySize) {
        return StrictMath.max(SEARCH_WINDOW_SIZE - historySize, 0);
    }

    private double[] searchHistoryToArray(LinkedList<Double> history) {
        double[] histData = new double[SEARCH_WINDOW_SIZE];
        synchronized (historyLock) {
            int index = searchHistoryArrayStartIndex(history.size());
            for (double v : history) {
                if (index >= SEARCH_WINDOW_SIZE) {
                    break;
                }
                histData[index] = v;
                index++;
            }
        }

        return histData;
    }

    private long[] searchHistoryToLongArray(LinkedList<Long> history) {
        long[] histData = new long[SEARCH_WINDOW_SIZE];
        synchronized (historyLock) {
            int index = searchHistoryArrayStartIndex(history.size());
            for (long v : history) {
                if (index >= SEARCH_WINDOW_SIZE) {
                    break;
                }
                histData[index] = v;
                index++;
            }
        }

        return histData;
    }

    public static class DoseRate {
        public final double compensated; // uSv/h
        public final double compensatedErrorPercent; // 1 sigma %
        public final double compensatedTimeSeconds; // integration duration
        public final double nonCompensated; // uSv/h
        public final double nonCompensatedErrorPercent; // 1 sigma %
        public final double nonCompensatedTimeSeconds; // integration duration
        public final double intervalCps; // cps
        public final double intervalCpsErrorPercent; // 1 sigma %
        public final double intervalCpsTimeSeconds; // integration duration

        public DoseRate() {
            this(0, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        public DoseRate(
                double compensated,
                double compensatedErrorPercent,
                double compensatedTimeSeconds,
                double nonCompensated,
                double nonCompensatedErrorPercent,
                double nonCompensatedTimeSeconds,
                double intervalCps,
                double intervalCpsErrorPercent,
                double intervalCpsTimeSeconds) {
            this.compensated = compensated;
            this.compensatedErrorPercent = compensatedErrorPercent;
            this.compensatedTimeSeconds = compensatedTimeSeconds;
            this.nonCompensated = nonCompensated;
            this.nonCompensatedErrorPercent = nonCompensatedErrorPercent;
            this.nonCompensatedTimeSeconds = nonCompensatedTimeSeconds;
            this.intervalCps = intervalCps;
            this.intervalCpsErrorPercent = intervalCpsErrorPercent;
            this.intervalCpsTimeSeconds = intervalCpsTimeSeconds;
        }
    }
}

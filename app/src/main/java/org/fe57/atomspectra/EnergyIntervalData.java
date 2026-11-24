package org.fe57.atomspectra;

/**
 * Shared user-selected energy interval that the interval cps, the search alarm and the spectrum
 * highlight work on. Readers take one immutable {@link Snapshot} so both edges are consistent.
 */
public final class EnergyIntervalData {
    public static final EnergyIntervalData instance = new EnergyIntervalData();

    private volatile Snapshot snapshot = new Snapshot(0, Constants.DEFAULT_CHANNEL_COUNT - 1, 0, 0, true);

    private EnergyIntervalData() {
    }

    public Snapshot get() {
        return snapshot;
    }

    public synchronized void setEnergy(double leftEnergy, double rightEnergy) {
        int channelCount = SpectrumData.instance.getChannelCount();
        Calibration calibration = SpectrumData.instance.foreground.getSpectrumCalibration();
        int left = Constants.MinMax(calibration.toChannel(leftEnergy), 0, channelCount - 1);
        int right = Constants.MinMax(calibration.toChannel(rightEnergy), 0, channelCount - 1);
        snapshot = new Snapshot(left, right, leftEnergy, rightEnergy, left == 0 && right == channelCount - 1);
    }

    public synchronized void setChannel(int leftChannel, int rightChannel) {
        int channelCount = SpectrumData.instance.getChannelCount();
        Calibration calibration = SpectrumData.instance.foreground.getSpectrumCalibration();
        int left = Constants.MinMax(leftChannel, 0, channelCount - 1);
        int right = Constants.MinMax(rightChannel, 0, channelCount - 1);
        snapshot = new Snapshot(left, right, calibration.toEnergy(left), calibration.toEnergy(right), left == 0 && right == channelCount - 1);
    }

    /**
     * Select the whole spectrum.
     */
    public synchronized void reset() {
        snapshot = new Snapshot(0, SpectrumData.instance.getChannelCount() - 1, 0, 0, true);
    }

    /**
     * Follow a calibration or channel count change: the selected energies stay, the channels move.
     */
    public synchronized void recalculate() {
        Snapshot current = snapshot;
        if (current.full) {
            reset();
        } else {
            setEnergy(current.leftEnergy, current.rightEnergy);
        }
    }

    public static final class Snapshot {
        public final int leftChannel;
        public final int rightChannel;
        public final double leftEnergy;
        public final double rightEnergy;
        /**
         * The whole spectrum is selected, whatever its channel count.
         */
        public final boolean full;

        private Snapshot(int leftChannel, int rightChannel, double leftEnergy, double rightEnergy, boolean full) {
            this.leftChannel = leftChannel;
            this.rightChannel = rightChannel;
            this.leftEnergy = leftEnergy;
            this.rightEnergy = rightEnergy;
            this.full = full;
        }
    }
}

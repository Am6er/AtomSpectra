package org.fe57.atomspectra;

import java.util.ArrayList;
import java.util.Date;

public class AtomSpectraSpectrogramData {
    public static final AtomSpectraSpectrogramData instance = new AtomSpectraSpectrogramData();
    public static final int CHANNEL_COUNT = 512; // must be 2^n and less then 8192
    public static final int MAX_ROWS = 25000;

    private final Integer spectrogramSync = 1;
    private final ArrayList<double[]> spectrogram = new ArrayList<>();
    private final ArrayList<Long> timestamps = new ArrayList<>();
    private final ArrayList<Double> durations = new ArrayList<>();
    private Spectrum baseSpectrum = null;

    public void setBaseSpectrum(Spectrum baseSpectrum) {
        this.baseSpectrum = baseSpectrum;
    }

    public void addDelta(Spectrum delta) {
        long[] channels = delta.getDataArray();
        double duration = delta.getRealSpectrumTime();
        long timestamp = delta.getSpectrumDate();
        
        this.addDelta(channels, duration, timestamp);
    }

    public void addDelta(long[] channels, double duration, long timestamp) {
        if (duration == 0) {
            return;
        }

        int channelBinning = channels.length / CHANNEL_COUNT;
        if (channelBinning < 1) {
            throw new IllegalArgumentException("Unsupported channels array lenght: " + channels.length);
        }

        double[] binnedCpsData = new double[CHANNEL_COUNT];
        for (int i = 0; i < channels.length; i += channelBinning) {
            long summ = 0;
            for (int j = 0; j < channelBinning && (i + j) < channels.length; j++) {
                summ += channels[i + j];
            }

            binnedCpsData[i / channelBinning] = summ / duration;
        }

        synchronized (spectrogramSync) {
            this.spectrogram.add(binnedCpsData);
            this.durations.add(duration);
            this.timestamps.add(timestamp);

            if (this.rowCount() > MAX_ROWS) {
                this.spectrogram.remove(0);
                this.durations.remove(0);
                this.timestamps.remove(0);
            }
        }
    }

    public int rowCount() {
        return this.spectrogram.size();
    }

    public void clear() {
        synchronized (spectrogramSync) {
            this.baseSpectrum = null;
            this.timestamps.clear();
            this.spectrogram.clear();
            this.durations.clear();
        }
    }

    public ArrayList<double[]> getSpectrogram() {
        return new ArrayList<>(this.spectrogram);
    }

   public ArrayList<Long> getTimestamps() {
       return new ArrayList<>(this.timestamps);
   }

   public ArrayList<Double> getDurations() {
       return new ArrayList<>(this.durations);
   }

    public double channelToEnergy(int channel) {
        if (this.baseSpectrum == null) {
            return 0;
        }
        int channelBinning = this.baseSpectrum.getDataArray().length / CHANNEL_COUNT;
        int originalChannel = channel * channelBinning + (channelBinning - 1);

        return this.baseSpectrum.getSpectrumCalibration().toEnergy(originalChannel);
    }
}

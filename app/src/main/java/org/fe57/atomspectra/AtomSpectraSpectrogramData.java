package org.fe57.atomspectra;

import java.util.ArrayList;
import java.util.Date;

public class AtomSpectraSpectrogramData {
    public static final AtomSpectraSpectrogramData instance = new AtomSpectraSpectrogramData();
    public static final int CHANNEL_COUNT = 512; // must be 2^n and less then 8192

    private final Integer spectrogramSync = 1;
    private final ArrayList<double[]> spectrogram = new ArrayList<>();
    private final ArrayList<Date> timestamps = new ArrayList<>();
    private final ArrayList<Double> durations = new ArrayList<>();
    private Spectrum baseSpectrum = null;

    public void setBaseSpectrum(Spectrum baseSpectrum) {
        this.baseSpectrum = baseSpectrum;
    }

    public void addDelta(Spectrum delta) {
        long[] channels = delta.getDataArray();
        double duration = delta.getRealSpectrumTime();
        Date timestamp = new Date(delta.getSpectrumDate());
        if (duration == 0) {
            return;
        }

        int channelBinning = channels.length / CHANNEL_COUNT;
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
        }
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

//    public Date[] getTimestamps() {
//        return this.timestamps.toArray();
//    }
//
//    public Date[] getDurations() {
//        return this.durations.toArray();
//    }

    public double channelToEnergy(int channel) {
        if (this.baseSpectrum == null) {
            return 0;
        }
        int channelBinning = this.baseSpectrum.getDataArray().length / CHANNEL_COUNT;
        int originalChannel = channel * channelBinning + (channelBinning - 1);

        return this.baseSpectrum.getSpectrumCalibration().toEnergy(originalChannel);
    }
}

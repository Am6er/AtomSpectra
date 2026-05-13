package org.fe57.atomspectra;

import android.net.Uri;

import java.util.ArrayList;
import java.util.Date;

public class AtomSpectraSpectrogramData {
    public static final AtomSpectraSpectrogramData instance = new AtomSpectraSpectrogramData();
    public static final int CHANNEL_COUNT = 1024; // must be 2^n and less then 8192
    public static final int MAX_ROWS = 25000;

    private final Object spectrogramSync = new Object();
    private final ArrayList<float[]> spectrogram = new ArrayList<>();
    private final ArrayList<Long> timestamps = new ArrayList<>();
    private final ArrayList<Float> durations = new ArrayList<>();
    private Spectrum baseSpectrum = null;

    private String recordingId = java.util.UUID.randomUUID().toString();

    private Uri spectrogramFileName = null;

    public String getRecordingId() {
        return recordingId;
    }

    public Uri getSpectrogramFileName() {
        return spectrogramFileName;
    }

    public void setBaseSpectrum(Spectrum baseSpectrum, Uri spectrogramFileName) {
        this.baseSpectrum = baseSpectrum;
        this.spectrogramFileName = spectrogramFileName;
    }

    public void addDelta(Spectrum delta) {
        long[] channels = delta.getDataArray();
        double duration = delta.getRealSpectrumTime();
        long timestamp = delta.getSpectrumDate();
        
        this.addDelta(channels, duration, timestamp);
    }

    public void addDelta(long[] channels, double duration, long timestamp) {
        if (duration <= 0) {
            duration = 1;
        }

        int channelBinning = channels.length / CHANNEL_COUNT;
        if (channelBinning < 1) {
            throw new IllegalArgumentException("Unsupported channels array length: " + channels.length);
        }

        float[] binnedCpsData = new float[CHANNEL_COUNT];
        for (int i = 0; i < channels.length; i += channelBinning) {
            long summ = 0;
            for (int j = 0; j < channelBinning && (i + j) < channels.length; j++) {
                summ += channels[i + j];
            }

            binnedCpsData[i / channelBinning] = (float) (summ / duration);
        }

        synchronized (spectrogramSync) {
            this.spectrogram.add(binnedCpsData);
            this.durations.add((float) duration);
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
            this.recordingId = java.util.UUID.randomUUID().toString();
        }
    }

    public ArrayList<float[]> getSpectrogram() {
        return new ArrayList<>(this.spectrogram);
    }

   public ArrayList<Long> getTimestamps() {
       return new ArrayList<>(this.timestamps);
   }

   public ArrayList<Float> getDurations() {
       return new ArrayList<>(this.durations);
   }

    public double[] averageSpectrum(int bound1, int bound2) {
        synchronized (spectrogramSync) {
            int rowCount = this.spectrogram.size();
            if (rowCount == 0) {
                return null;
            }

            int from = Math.max(0, Math.min(bound1, bound2));
            int to = Math.min(rowCount - 1, Math.max(bound1, bound2));
            if (from > to) {
                return null;
            }

            double[] result = new double[CHANNEL_COUNT];
            double totalDuration = 0;
            for (int i = from; i <= to; i++) {
                double duration = this.durations.get(i);
                float[] row = this.spectrogram.get(i);
                totalDuration += duration;
                for (int k = 0; k < CHANNEL_COUNT; k++) {
                    result[k] += row[k] * duration;
                }
            }

            if (totalDuration <= 0) {
                return null;
            }

            for (int k = 0; k < CHANNEL_COUNT; k++) {
                result[k] /= totalDuration;
            }

            return result;
        }
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

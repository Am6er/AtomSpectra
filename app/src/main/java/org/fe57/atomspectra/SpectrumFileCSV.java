package org.fe57.atomspectra;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.function.Consumer;

import kotlin.NotImplementedError;

public class SpectrumFileCSV extends SpectrumFile {
    private boolean addEnergy = false;

    public boolean isAddEnergy() {
        return addEnergy;
    }

    public void setAddEnergy(boolean addEnergy) {
        this.addEnergy = addEnergy;
    }

    @Override
    public void loadSpectrum(@NonNull Uri histFile, Context context) {
        throw new UnsupportedOperationException("Load CSV is not supported");
    }

    @Override
    public void saveSpectrumAndCloseStream(@NonNull OutputStreamWriter docStream, Context context) throws IOException {
        try (OutputStreamWriter fw = docStream) {
            validateSaveState();

            Spectrum spectrum = spectrumList.get(0);
            final SimpleDateFormat dateZoneFormat = new SimpleDateFormat("yyyy.MM.dd HH:mm:ss Z", Locale.US);
            int calc_pulses;
            long[] tmp = spectrum.getDataArray();
            int num_channels = tmp.length / channelCompression;
            fw.append(String.format(Locale.US, "\"Comments:\";\"%s\"\n", spectrum.getComments()));                                                                                          //version 2
            fw.append(String.format(Locale.US, "\"Date:\";\"%s\"\n", dateZoneFormat.format(new Date(spectrum.getSpectrumDate()))));                                                         //version 2
            fw.append(String.format(Locale.US, "\"GPS date:\";\"%s\"\n", dateZoneFormat.format(new Date(spectrum.getGPSDate()))));                                                          //version 2
            fw.append(String.format(Locale.US, "\"Latitude:\";\"%s\"\n", GPSLocator.getFormattedLatitude(spectrum.getLatitude()).replaceAll("\"", "\"\"")));              //version 2
            fw.append(String.format(Locale.US, "\"Longitude:\";\"%s\"\n", GPSLocator.getFormattedLongitude(spectrum.getLongitude()).replaceAll("\"", "\"\"")));           //version 2

            if (addEnergy) {
                fw.append("\"Channel\";\"Energy\";\"Counts\"\n");
                for (int k = 0; k < num_channels * channelCompression; k += channelCompression) {
                    fw.append(String.format(Locale.US, "%5d;", k / channelCompression));
                    fw.append(String.format(Locale.US, "%8.3f;", spectrum.getSpectrumCalibration().toEnergy(k)));
                    calc_pulses = 0;
                    for (int l = 0; l < channelCompression; l++)
                        calc_pulses += tmp[k + l];
                    fw.append(String.format(Locale.US, "%10d\n", calc_pulses));
                }
            } else {
                fw.append("\"Channel\";\"Counts\"\n");
                for (int k = 0; k < num_channels * channelCompression; k += channelCompression) {
                    fw.append(String.format(Locale.US, "%5d;", k / channelCompression));
                    calc_pulses = 0;
                    for (int l = 0; l < channelCompression; l++)
                        calc_pulses += tmp[k + l];
                    fw.append(String.format(Locale.US, "%10d\n", calc_pulses));
                }
            }
        }
    }

    private void validateSaveState() throws IllegalStateException {
        if (this.spectrumList.isEmpty()) {
            throw new IllegalStateException("No spectrum to save.");
        }

        if (this.spectrumList.size() > 1) {
            throw new IllegalStateException("Only single spectrum is supported by CSV file format.");
        }

        if (this.backgroundSpectrum != null) {
            throw new IllegalStateException("Background spectrum is not supported by CSV file format.");
        }
    }
}

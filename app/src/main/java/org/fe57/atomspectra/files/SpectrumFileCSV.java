package org.fe57.atomspectra.files;

import android.content.Context;

import androidx.annotation.NonNull;

import org.fe57.atomspectra.data.GPSLocator;
import org.fe57.atomspectra.data.Spectrum;

import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * This class is used export spectra in CSV format.
 * @author S. Epiphanov.
 * @version 1.0
 */
public class SpectrumFileCSV extends SpectrumFile {
    private boolean addEnergy = false;

    public boolean isAddEnergy() {
        return addEnergy;
    }

    public void setAddEnergy(boolean addEnergy) {
        this.addEnergy = addEnergy;
    }

    @Override
    public boolean loadSpectrum(@NonNull InputStream histFile, Context context) {
        return false;
    }

    @Override
    public boolean saveSpectrum(@NonNull OutputStream docStream, Context context) {
        //We save the only one spectrum
        if (spectrumNumber() != 1 || backgroundSpectrum != null)
            return false;

        Spectrum spectrum = spectrumList.get(0);
        final SimpleDateFormat dateZoneFormat = new SimpleDateFormat("yyyy.MM.dd HH:mm:ss Z", Locale.US);
        try {
            BufferedWriter fw = new BufferedWriter(new OutputStreamWriter(docStream));
            int calc_pulses;
            int num_channels = Channels / channelCompression;
            long[] tmp = spectrum.getSpectrum();
            fw.append(String.format(Locale.US, "\"Comments:\";\"%s\"\n", spectrum.getComments()));                                                                                          //version 2
            fw.append(String.format(Locale.US, "\"Date:\";\"%s\"\n", dateZoneFormat.format(new Date(spectrum.getSpectrumDate()))));                                                         //version 2
            fw.append(String.format(Locale.US, "\"GPS date:\";\"%s\"\n", dateZoneFormat.format(new Date(spectrum.getGPSDate()))));                                                          //version 2
            fw.append(String.format(Locale.US, "\"Latitude:\";\"%s\"\n", GPSLocator.getFormattedLatitude(spectrum.getLatitude()).replaceAll("\"", "\"\"")));              //version 2
            fw.append(String.format(Locale.US, "\"Longitude:\";\"%s\"\n", GPSLocator.getFormattedLongitude(spectrum.getLongitude()).replaceAll("\"", "\"\"")));           //version 2

            if (addEnergy) {
                fw.append("\"Channel\";\"Energy\";\"Counts\"\n");
                for (int k = 0; k < num_channels * channelCompression; k += channelCompression) {
                    fw.append(String.format(Locale.getDefault(), "%5d;", k / channelCompression));
                    fw.append(String.format(Locale.getDefault(), "%8.3f;", spectrum.getSpectrumCalibration().toEnergy(k)));
                    calc_pulses = 0;
                    for (int l = 0; l < channelCompression; l++)
                        calc_pulses += tmp[k + l];
                    fw.append(String.format(Locale.getDefault(), "%10d\n", calc_pulses));
                }
            } else {
                fw.append("\"Channel\";\"Counts\"\n");
                for (int k = 0; k < num_channels * channelCompression; k += channelCompression) {
                    fw.append(String.format(Locale.getDefault(), "%5d;", k / channelCompression));
                    calc_pulses = 0;
                    for (int l = 0; l < channelCompression; l++)
                        calc_pulses += tmp[k + l];
                    fw.append(String.format(Locale.getDefault(), "%10d\n", calc_pulses));
                }
            }
            fw.close();
        } catch (Exception e) {
            return false;
        }
        return true;
    }
}

package org.fe57.atomspectra;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.function.Consumer;

public class SpectrumFileSPE extends SpectrumFile {
    @Override
    public void loadSpectrum(@NonNull Uri histFile, Context context) {
        throw new UnsupportedOperationException("Load SPE is not supported");
    }

    @Override
    public void saveSpectrumAndCloseStream(@NonNull OutputStreamWriter docStream, Context context) throws IOException, PackageManager.NameNotFoundException {
        try (OutputStreamWriter fw = docStream) {
            validateSaveState();

            GregorianCalendar dateNow = new GregorianCalendar(Locale.US);
            dateNow.setTime(new Date(spectrumList.get(0).getSpectrumDate()));
            GregorianCalendar dateBegin = (GregorianCalendar) dateNow.clone();
            int time_count = (int) (spectrumList.get(0).getRealSpectrumTime());
            dateBegin.add(Calendar.SECOND, -time_count);
            Spectrum spectrum = spectrumList.get(0);
            double time = StrictMath.max(spectrumList.get(0).getRealSpectrumTime(), 1.0);
            long counts = 0;
            long calc_pulses;
            long[] tmp = spectrumList.get(0).getDataArray();
            int num_channels = Channels / channelCompression;
            for (int k = 0; k < num_channels * channelCompression; k += channelCompression) {
                for (int l = 0; l < channelCompression; l++) {
                    counts += tmp[k + l];
                }
            }
            Calibration exportCalibration = new Calibration();
            double[] coeffs = spectrumList.get(0).getSpectrumCalibration().getCoeffArray();
            int count = 1;
            for (int i = 0; i < coeffs.length; i++) {
                coeffs[i] *= count;
                count *= channelCompression;
            }
            exportCalibration.Calculate(coeffs);
            fw.append("$APPLICATION_ID:\nAtom Spectra Version ").append(context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName).append("\n");
            fw.append("$MCA_166_ID:\n" +
                    "SN# unknown\n" +
                    "HW# unknown\n" +
                    "FW# unknown\n" +
                    "$SPEC_REM:\n");
            fw.append(spectrum.getComments()).append("\n");
            SimpleDateFormat dateUSFormat = new SimpleDateFormat("MM/dd/yyyy HH:mm:ss", Locale.US);
            fw.append(String.format(Locale.US, "$DATE_MEA:\n%s\n", dateUSFormat.format(spectrum.getSpectrumDate() == 0 ? new Date() : new Date(spectrum.getSpectrumDate()))));
            fw.append(String.format(Locale.US, "$MEAS_TIM:\n%d %d\n", (long) time, (long) time));
            fw.append(String.format(Locale.US, "$COUNTS:\n%d\n", counts));
            fw.append(String.format(Locale.US, "$CPS:\n%.6f\n", counts / time));
            fw.append("$NEUTRON_CPS:\n" +
                    "0.000000\n" +
                    "$NEUTRON_COUNT:\n" +
                    "0\n" +
                    "$NEUTRON_DOSERATE:\n" +
                    "0.000000\n" +
                    "$STATUS_OF_HEALTH:\n" +
                    "\n");
            fw.append(String.format(Locale.US, "$DATA:\n%d %d\n", 0, num_channels - 1));
            for (int k = 0; k < Channels - channelCompression + 1; k += channelCompression) {
                calc_pulses = 0;
                for (int l = 0; l < channelCompression; l++)
                    calc_pulses += tmp[k + l];
                fw.append(String.format(Locale.US, "%d\n", calc_pulses));
            }
            fw.append(String.format(Locale.US, "$ENER_FIT:\n%.6f %.6f\n", exportCalibration.toEnergy(0), (exportCalibration.toEnergy(num_channels - 1) - exportCalibration.toEnergy(0)) / (num_channels - 1)));
            fw.append("$ENER_DATA:\n").append(String.valueOf(exportCalibration.getFactor() + 1)).append("\n");
            for (int i = 0; i <= exportCalibration.getFactor(); i++) {
                fw.append(String.format(Locale.US, "%.6f %.6f\n", (float) ((num_channels - 1) * i / exportCalibration.getFactor()), exportCalibration.toEnergy((num_channels - 1) * i / exportCalibration.getFactor())));
            }
            fw.append("$ENER_TABLE:\n");
            fw.append(String.format(Locale.US, "%d\n", num_channels));
            for (int k = 0; k < num_channels; k++) {
                fw.append(String.format(Locale.US, "%.6f %.6f\n", (float) k, exportCalibration.toEnergy(k)));
            }
            fw.append("$TEMPERATURE:\n");
            fw.append("22.000000\n");
            fw.append("$SCALE_MODE:\n");
            fw.append("0\n");
            fw.append("$DU_NAME:\n");
            fw.append("SPRD\n");
            fw.append("$RADIONUCLIDES:\n\n");
            fw.append("$ACTIVITYRESULT:\n\n");
            fw.append("$EFFECTIVEACTIVITYRESULT:\n\n");
            fw.append("$MIX:\n\n");
            fw.append("$GEOMETRY:\n\n");
            fw.append("$SPECTRUMPROCESSED:\n");
            fw.append("0\n");
            fw.append("$BGNDSUBTRACTED:\n");
            fw.append("0\n");
            fw.append("$ENCRYPTED:\n");
            fw.append("0\n");
            fw.append("$DATE_MANUFACT:\n");
            fw.append("unknown\n");
            fw.append("$GPS:\n");
            if (spectrum.getGPSDate() != 0) {
                fw.append(String.format(Locale.US, "Lon= %.10f\n", spectrum.getLongitude()));
                fw.append(String.format(Locale.US, "Lat= %.10f\n", spectrum.getLatitude()));
            } else {
                fw.append(String.format(Locale.US, "Lon= %.6f\n", 0.0));
                fw.append(String.format(Locale.US, "Lat= %.6f\n", 0.0));
            }
            fw.append("Alt= 0.000000\n");
            fw.append("Speed= 0.000000\n");
            fw.append("Dir= 0.000000\n");
            if (spectrum.getGPSDate() != 0) {
                fw.append("Valid=1\n");
            } else {
                fw.append("Valid=0\n");
            }
        }
    }

    private void validateSaveState() throws IllegalStateException {
        if (this.spectrumList.isEmpty()) {
            throw new IllegalStateException("No spectrum to save.");
        }

        if (this.spectrumList.size() > 1) {
            throw new IllegalStateException("Only single spectrum is supported by SPE file format.");
        }

        if (this.backgroundSpectrum != null) {
            throw new IllegalStateException("Background spectrum is not supported by SPE file format.");
        }
    }
}

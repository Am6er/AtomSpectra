package org.fe57.atomspectra.files;

import android.content.Context;

import androidx.annotation.NonNull;

import org.fe57.atomspectra.data.Calibration;
import org.fe57.atomspectra.data.Spectrum;

import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Locale;

/**
 * This class is used to store spectrum in SPE format.
 * @author S. Epiphanov.
 * @version 1.0
 */
public class SpectrumFileSPE extends SpectrumFile {
    @Override
    public boolean loadSpectrum(@NonNull InputStream histFile, Context context) {
        return false;
    }

    @Override
    public boolean saveSpectrum(@NonNull OutputStream docStream, Context context) {
        //We just save the only one spectrum
        if (spectrumNumber() != 1 || backgroundSpectrum != null)
            return false;

        GregorianCalendar dateNow = new GregorianCalendar(Locale.US);
        dateNow.setTime(new Date(spectrumList.get(0).getSpectrumDate()));
        GregorianCalendar dateBegin = (GregorianCalendar) dateNow.clone();
        int time_count = (int) (spectrumList.get(0).getRealSpectrumTime());
        dateBegin.add(Calendar.SECOND, -time_count);
        Spectrum spectrum = spectrumList.get(0);
        try {
            BufferedWriter fw = new BufferedWriter(new OutputStreamWriter(docStream));

            double time = StrictMath.max(spectrumList.get(0).getRealSpectrumTime(), 1.0);
            long counts = 0;
            long calc_pulses;
            long[] tmp = spectrumList.get(0).getSpectrum();
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
//            if (spectrum.getSpectrumDate() != 0) {
                fw.append(spectrum.getComments()).append("\n");
//            } else {
//                if (spectrum.getGPSDate() != 0) {
//                    fw.append(spectrum.prepareCommentString(new Date(), counts, counts / time, time, Locator != null ? Locator.gpsTime : 0, Locator != null ? Locator.getLatitude() : 0, Locator != null ? Locator.getLongitude() : 0)).append("\n");
//                } else {
//                    fw.append(prepareCommentString(new Date(), counts, counts / time, time, 0, 0, 0)).append("\n");
//                }
//            }
            SimpleDateFormat dateUSFormat = new SimpleDateFormat("MM/dd/yyyy HH:mm:ss", Locale.US);
            fw.append(String.format(Locale.US, "$DATE_MEA:\n%s\n", dateUSFormat.format(spectrum.getSpectrumDate() == 0 ? new Date() : new Date(spectrum.getSpectrumDate()))));
            fw.append(String.format(Locale.US, "$MEAS_TIM:\n%d %d\n", (long)time, (long)time));
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
//			fw.append("$GPS_RMCHOUR:\n");
//			0
//			fw.append("$GPS_RMCMINUTE:\n");
//			0
//			fw.append("$GPS_RMCSECOND:\n");
//			0
//			fw.append("$GPS_RMCDAY:\n");
//			0
//			fw.append("$GPS_RMCMONTH:\n");
//			0
//			fw.append("$GPS_RMCYEAR:\n");
//			0
//			fw.append("$GPS_GSAPDOP:\n");
//			0.000000
//			fw.append("$GPS_GSAHDOP:\n");
//			0.000000
//			fw.append("$GPS_GSAVDOP:\n");
//			0.000000
//			fw.append("$ACTIVITY:\n" +
//					"0.000000\n" +
//					"$DISTANCE:\n" +
//					"0.000000\n" +
//					"$SWIDTH:\n" +
//					"0.000000\n" +
//					"$CPSOUTOFRANGE:\n" +
//					"0.000000\n");
            fw.append(String.format(Locale.US, "$DATA:\n%d %d\n", 0, num_channels - 1));
            for (int k = 0; k < Channels - channelCompression + 1; k += channelCompression) {
                calc_pulses = 0;
                for (int l = 0; l < channelCompression; l++)
                    calc_pulses += tmp[k + l];
                fw.append(String.format(Locale.US, "%d\n", calc_pulses));
            }
            fw.append(String.format(Locale.US, "$ENER_FIT:\n%.6f %.6f\n", exportCalibration.toEnergy(0), (exportCalibration.toEnergy(num_channels - 1) - exportCalibration.toEnergy(0)) / (num_channels - 1)));
//			fw.append(String.format(Locale.US, "$ENER_DATA:\n" +
//					"2\n" +
//					"0.000000 %.6f\n" +
//					"%.6f %.6f\n", exportCalibration.toEnergy(0), (float)(num_channels - 1), exportCalibration.toEnergy(num_channels - 1)));
            fw.append("$ENER_DATA:\n").append(String.valueOf(exportCalibration.getFactor() + 1)).append("\n");
            for (int i = 0; i <= exportCalibration.getFactor(); i++) {
                fw.append(String.format(Locale.US, "%.6f %.6f\n", (float)((num_channels - 1) * i / exportCalibration.getFactor()), exportCalibration.toEnergy((num_channels - 1) * i / exportCalibration.getFactor())));
            }
            fw.append("$ENER_TABLE:\n");
            fw.append(String.format(Locale.US, "%d\n", num_channels));
            for (int k = 0; k < num_channels; k++) {
                fw.append(String.format(Locale.US, "%.6f %.6f\n", (float)k, exportCalibration.toEnergy(k)));
            }
//			fw.append("$SIGM_DATA:\n");
//			fw.append(String.format(Locale.US, "%d\n", num_channels));
            //sigma table:
            //ch1 sigma1
            //ch2 sigma2
            //and so on
            fw.append("$TEMPERATURE:\n");
            fw.append("22.000000\n");
            fw.append("$SCALE_MODE:\n");
            fw.append("0\n");
//			fw.append("$DOSE_RATE:\n");
//			110.318558
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
//			fw.append("$DR_COEF:\n");
//			fw.append("1.0e-9\n");
//			fw.append("$DR_UNIT:\n");
//			fw.append("Sv\n");
            fw.close();
//            Log.d(TAG, spectrumFileName + " saved successfully");
//            Toast.makeText(this, getString(R.string.export_save_success, spectrumFileName), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            return false;
//            Toast.makeText(this, getString(R.string.export_save_error, spectrumFileName), Toast.LENGTH_LONG).show();
        }
        return true;
    }
}

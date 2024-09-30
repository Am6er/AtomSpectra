package org.fe57.atomspectra;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.Date;
import java.util.Locale;

//This is the main class to load and store own Atom Spectra spectra
public class SpectrumFileAS extends SpectrumFile {
    @Override
    public boolean loadSpectrum(@NonNull InputStream histFile, Context context) {
        boolean result = false;
        if (spectrumNumber() == 0 && backgroundSpectrum == null) {
            try {
                BufferedReader fr = new BufferedReader(new InputStreamReader(histFile));
//            Log.d(TAG, filename + " loading started...");
                String ident = fr.readLine();
                if (!ident.matches("^[+-]?\\d+(\\.(\\d+)?)?$")) {
                    result = loadSpectrumV3(fr, ident, context);
                } else {
                    result = loadSpectrumV1(fr, ident, context);
                }
            } catch (Exception e) {
                return false;
            }
        }
        return result;
    }

    //old spectrum data
    private boolean loadSpectrumV1(BufferedReader fr, String ident, Context context) {
        Spectrum spectrum = new Spectrum();
        try {
            spectrum.setSpectrumTime((long) (Double.parseDouble(ident) * 1000.0 / Constants.UPDATE_PERIOD)); //convert from seconds to counts
            int poli_save = Integer.parseInt(fr.readLine());
            if ((poli_save < 1) || (poli_save > Constants.MAX_POLI_SIZE)) {
                throw new Exception("Poli scale read error");
            }
            Calibration save_calibration = new Calibration();
            double cal, cal_E;
            int hist_compress;
            if (Channels % (1 << Constants.ADC_MAX) == 0)
                hist_compress = Channels / (1 << Constants.ADC_MAX);
            else
                hist_compress = Channels / (1 << Constants.ADC_MAX) + 1;
            if (hist_compress == 0)
                hist_compress = 1;
            for (int i = 0; i <= poli_save; i++) {
                cal = Double.parseDouble(fr.readLine()) / hist_compress;
                cal_E = Double.parseDouble(fr.readLine());
                if (save_calibration.containsChannel((int) cal))
                    throw new Exception("Poli data read error");
                save_calibration.addLine((int) cal, cal_E);
            }
            save_calibration.Calculate();
            if (!save_calibration.isCorrect())
                throw new Exception("Poli calc error");
            long[] tmp = new long[Constants.NUM_HIST_POINTS];
            for (int i = 0; i < Constants.NUM_HIST_POINTS; i++) {
                for (int j = 0; j < hist_compress; j++) {
                    if ((i * hist_compress + j) < 65536)
                        tmp[i] += Long.parseLong(fr.readLine());                                               //get channel data
                }
            }
            spectrum
                    .setSpectrumCalibration(save_calibration)
                    .setSpectrumOnly(tmp)
                    .setChanged(false);

            fr.close();
        } catch (Exception e) {
            return false;
        }
        spectrumList.add(spectrum);
        return true;
    }

    //V2, V3 spectrum data
    private boolean loadSpectrumV3(BufferedReader fr, String ident, Context context) {
        Spectrum spectrum = new Spectrum();
        int formatCode;
        try {
            formatCode = Integer.parseInt(ident.substring(8));
        } catch (Exception ignored) {
            return false;
        }
        if (formatCode < 1 || formatCode > 3) {
            return false;
        }
        try {
            if (formatCode >= 2) {
                //version 2
                String comments = fr.readLine();
                long date = Long.parseLong(fr.readLine());//version 2
                long GPSDate = Long.parseLong(fr.readLine());//version 2
                double Latitude = Double.parseDouble(fr.readLine());//version 2
                double Longitude = Double.parseDouble(fr.readLine());//version 2
                spectrum
                        .setLocationOnly(Latitude, Longitude, GPSDate)
                        .setComments(comments)
                        .setSpectrumDate(date);
            }
            if (formatCode >= 3) {
                //version 3
                spectrum
                        .setSuffix(fr.readLine())
                        .setDetectedIsotopes(fr.readLine());
            }
            spectrum.setSpectrumTime((long) (Double.parseDouble(fr.readLine()) * 1000.0 / Constants.UPDATE_PERIOD)); //convert from seconds to counts
            int num_points = StrictMath.min(Integer.parseInt(fr.readLine()), Channels);
            int poli_save = Integer.parseInt(fr.readLine());
            if ((poli_save < 1) || (poli_save > Constants.MAX_POLI_SIZE)) {
                throw new IOException("Poli scale read error");
            }
            Calibration save_calibration = new Calibration();
            int compactness = num_points / Constants.NUM_HIST_POINTS;
            if (num_points % Constants.NUM_HIST_POINTS != 0)
                compactness++;
            if (formatCode >= 3) {
                double x = 1;
                double[] coeffs = new double[poli_save + 1];
                for (int i = 0; i <= poli_save; i++) {
                    coeffs[i] = Double.parseDouble(fr.readLine()) * x;
                    x *= compactness;
                }
                save_calibration.Calculate(coeffs);
            } else {
                double cal, cal_E;
                for (int i = 0; i <= poli_save; i++) {
                    cal = Double.parseDouble(fr.readLine()) / compactness;
                    cal_E = Double.parseDouble(fr.readLine());
                    if (save_calibration.containsChannel((int) cal))
                        throw new IOException("Poli data read error");
                    save_calibration.addLine((int) cal, cal_E);
                }
                save_calibration.Calculate();
            }
            if (!save_calibration.isCorrect())
                throw new IOException("Poli calc error");
            long[] tmp = new long[Constants.NUM_HIST_POINTS];
            for (int i = 0; i < Constants.NUM_HIST_POINTS; i++) {
                for (int j = 0; j < compactness; j++) {
                    if ((i * compactness + j) < num_points)
                        tmp[i] += Long.parseLong(fr.readLine());                                               //get channel data
                }
            }
            spectrum
                    .setSpectrumCalibration(save_calibration)
                    .setSpectrumOnly(tmp)
                    .setChanged(false);
            fr.close();
        } catch (Exception e) {
            return false;
        }
        spectrumList.add(spectrum);
        return true;
    }

    @Override
    public boolean saveSpectrum(@NonNull OutputStreamWriter docStream, Context context) {
        //We just save the only one spectrum
        if (spectrumNumber() != 1 || backgroundSpectrum != null)
            return false;

        Spectrum spectrum = spectrumList.get(0);
        try {
            OutputStreamWriter fw = docStream;
            long[] tmp = spectrum.getDataArray();
            double time = StrictMath.max(spectrum.getRealSpectrumTime(), 1.0);
            fw.append("FORMAT: 3\n");
            fw.append(String.format(Locale.US, "%s\n", spectrum.getComments()));    //version 2
            if (spectrum.getSpectrumDate() == 0) {
                fw.append(String.format(Locale.US, "%d\n", new Date().getTime()));    //version 2
            } else {
                fw.append(String.format(Locale.US, "%s\n", spectrum.getSpectrumDate()));                   //version 2
            }
            fw.append(String.format(Locale.US, "%s\n", spectrum.getGPSDate()));                //version 2
            fw.append(String.format(Locale.US, "%s\n", spectrum.getLatitude()));               //version 2
            fw.append(String.format(Locale.US, "%s\n", spectrum.getLongitude()));              //version 2
            fw.append(spectrum.getSuffix()).append("\n"); //version 3
            fw.append(spectrum.getDetectedIsotopes()).append("\n");//version 3
            fw.append(String.format(Locale.US, "%f\n", time)); //convert from counts to seconds
            fw.append(String.format(Locale.US, "%d\n", Constants.NUM_HIST_POINTS));
            fw.append(String.format(Locale.US, "%d\n", spectrum.getSpectrumCalibration().getFactor()));
            for (double coeff : spectrum.getSpectrumCalibration().getCoeffArray()) {
                fw.append(String.format(Locale.US, "%.12g\n", coeff));
            }
            for (long l : tmp) {
                fw.append(String.format(Locale.US, "%d\n", l));
            }
            fw.close();
        } catch (Exception e) {
            return false;
        }
        return true;
    }

    @Override
    public boolean saveIncrementalSpectrum(@NonNull OutputStreamWriter docStream, Context context) {
        //We just save the only one incremental spectrum
        if (spectrumNumber() != 1 || backgroundSpectrum != null)
            return false;

        Spectrum spectrum = spectrumList.get(0);
        try {
            OutputStreamWriter fw = docStream;
            long[] tmp = spectrum.getDataArray();
            double time = StrictMath.max(spectrum.getRealSpectrumTime(), 1.0);
//            fw.append("FORMAT: 3\n");
//            fw.append(String.format(Locale.US, "%s\n", spectrum.getComments()));    //version 2
            if (spectrum.getSpectrumDate() == 0) {
                fw.append(String.format(Locale.US, "%d\n", new Date().getTime()));    //version 2
            } else {
                fw.append(String.format(Locale.US, "%s\n", spectrum.getSpectrumDate()));                   //version 2
            }
//            fw.append(String.format(Locale.US, "%s\n", spectrum.getGPSDate()));                //version 2
            fw.append(String.format(Locale.US, "%s\n", spectrum.getLatitude()));               //version 2
            fw.append(String.format(Locale.US, "%s\n", spectrum.getLongitude()));              //version 2
//            fw.append(spectrum.getSuffix()).append("\n"); //version 3
//            fw.append(spectrum.getDetectedIsotopes()).append("\n");//version 3
            fw.append(String.format(Locale.US, "%f\n", time)); //convert from counts to seconds
//            fw.append(String.format(Locale.US, "%d\n", Constants.NUM_HIST_POINTS));
//            fw.append(String.format(Locale.US, "%d\n", spectrum.getSpectrumCalibration().getFactor()));
//            for (double coeff : spectrum.getSpectrumCalibration().getCoeffArray()) {
//                fw.append(String.format(Locale.US, "%.12g\n", coeff));
//            }
            for (long l : tmp) {
                fw.append(String.format(Locale.US, "%d\t", l));
            }
            fw.append("\n");
            fw.close();
        } catch (Exception e) {
            return false;
        }
        return true;
    }
}

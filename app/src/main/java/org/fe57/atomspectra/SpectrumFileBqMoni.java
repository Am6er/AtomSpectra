package org.fe57.atomspectra;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.security.InvalidParameterException;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.function.Consumer;

public class SpectrumFileBqMoni extends SpectrumFile {
    @Override
    public void loadSpectrum(@NonNull Uri histFile, Context context) {
        throw new UnsupportedOperationException("Load BqMoni is not supported");
    }

    @Override
    public void saveSpectrumAndCloseStream(@NonNull OutputStreamWriter docStream, Context context) throws IOException, InvalidParameterException {
        GregorianCalendar dateNow;
        long[] tmp;
        long calc_pulses;
        int time_count;
        Spectrum spectrum;
        Calibration exportCalibration;
        double[] coeffs;
        int num_channels;
        int count;
        try (OutputStreamWriter fw = docStream) {
            validateSaveState();

            fw.append("<?xml version=\"1.0\"?>\n");
            fw.append("  <ResultDataFile xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\">\n");
            fw.append("  <FormatVersion>120920</FormatVersion>\n");
            fw.append("  <ResultDataList>\n");
            for (int i = 0; i < spectrumCount(); i++) {
                spectrum = spectrumList.get(i);
                dateNow = new GregorianCalendar(Locale.US);
                if (spectrum.getSpectrumDate() != 0) {
                    try {
                        dateNow.setTime(new Date(spectrum.getSpectrumDate()));
                    } catch (Exception ignored) {
                        //
                    }
                }
                GregorianCalendar dateBegin = (GregorianCalendar) dateNow.clone();
                time_count = (int) (spectrum.getRealSpectrumTime());
                dateBegin.add(Calendar.SECOND, -time_count);
                tmp = spectrum.getDataArray();
                num_channels = tmp.length / channelCompression;
                calc_pulses = 0;
                for (int k = 0; k < num_channels * channelCompression; k += channelCompression) {
                    for (int l = 0; l < channelCompression; l++) {
                        calc_pulses += tmp[k + l];
                    }
                }
                exportCalibration = spectrum.getSpectrumCalibration();
                if (exportCalibration == null) {
                    throw new InvalidParameterException("Spectrum calibration is null");
                }

                coeffs = exportCalibration.getCoeffArray();
                count = 1;
                for (int j = 0; j < coeffs.length; j++) {
                    coeffs[j] *= count;
                    count *= channelCompression;
                }
                fw.append("    <ResultData>\n");
                fw.append("      <SampleInfo>\n");
                fw.append("        <Name>").append(xmlEscape(spectrum.getSuffix())).append("</Name>\n");
                fw.append("        <Location />\n");
                fw.append(String.format(Locale.US, "        <Time>%04d-%02d-%02dT%02d:%02d:%02d.%03d%+03d:%02d</Time>\n",
                        dateBegin.get(Calendar.YEAR),
                        dateBegin.get(Calendar.MONTH) + 1,
                        dateBegin.get(Calendar.DAY_OF_MONTH),
                        dateBegin.get(Calendar.HOUR_OF_DAY),
                        dateBegin.get(Calendar.MINUTE),
                        dateBegin.get(Calendar.SECOND),
                        dateBegin.get(Calendar.MILLISECOND),
                        dateBegin.get(Calendar.ZONE_OFFSET) / (60 * 60 * 1000),
                        dateBegin.get(Calendar.ZONE_OFFSET) - (dateBegin.get(Calendar.ZONE_OFFSET) / (60 * 60 * 1000)) * (60 * 60 * 1000)));
                fw.append("        <Weight>1</Weight>\n");
                fw.append("        <Volume>1</Volume>\n");
                fw.append("        <Note />\n");
                fw.append("      </SampleInfo>\n");
                fw.append("      <DeviceConfigReference>\n");
                fw.append("        <Name>Atomspectra 2.0</Name>\n");
                fw.append("        <Guid>379b5443-f07f-4ddb-afcc-59d95222d295</Guid>\n");
                fw.append("      </DeviceConfigReference>\n");
                if (backgroundSpectrum != null && (!backgroundSpectrum.isEmpty())) {
                    fw.append("      <BackgroundSpectrumFile>Background.xml</BackgroundSpectrumFile>\n");
                }
                fw.append(String.format(Locale.US, "      <StartTime>%04d-%02d-%02dT%02d:%02d:%02d.%03d%+03d:%02d</StartTime>\n",
                        dateBegin.get(Calendar.YEAR),
                        dateBegin.get(Calendar.MONTH) + 1,
                        dateBegin.get(Calendar.DAY_OF_MONTH),
                        dateBegin.get(Calendar.HOUR_OF_DAY),
                        dateBegin.get(Calendar.MINUTE),
                        dateBegin.get(Calendar.SECOND),
                        dateBegin.get(Calendar.MILLISECOND),
                        dateBegin.get(Calendar.ZONE_OFFSET) / (60 * 60 * 1000),
                        dateBegin.get(Calendar.ZONE_OFFSET) - (dateBegin.get(Calendar.ZONE_OFFSET) / (60 * 60 * 1000)) * (60 * 60 * 1000)));
                fw.append(String.format(Locale.US, "      <EndTime>%04d-%02d-%02dT%02d:%02d:%02d.%03d%+03d:%02d</EndTime>\n",
                        dateNow.get(Calendar.YEAR),
                        dateNow.get(Calendar.MONTH) + 1,
                        dateNow.get(Calendar.DAY_OF_MONTH),
                        dateNow.get(Calendar.HOUR_OF_DAY),
                        dateNow.get(Calendar.MINUTE),
                        dateNow.get(Calendar.SECOND),
                        dateNow.get(Calendar.MILLISECOND),
                        dateNow.get(Calendar.ZONE_OFFSET) / (60 * 60 * 1000),
                        dateNow.get(Calendar.ZONE_OFFSET) - (dateNow.get(Calendar.ZONE_OFFSET) / (60 * 60 * 1000)) * (60 * 60 * 1000)));
                fw.append(String.format(Locale.US, "      <PresetTime>%d</PresetTime>\n", time_count));
                fw.append("      <EnergySpectrum>\n");
                fw.append(String.format(Locale.US, "        <NumberOfChannels>%d</NumberOfChannels>\n", num_channels));
                fw.append(String.format(Locale.US, "        <ChannelPitch>%.4f</ChannelPitch>\n", 100.0 * channelCompression / Constants.NUM_HIST_POINTS));
                fw.append("        <EnergyCalibration>\n");
                fw.append(String.format(Locale.US, "          <PolynomialOrder>%d</PolynomialOrder>\n", coeffs.length - 1));
                fw.append("          <Coefficients>\n");
                for (double coeff : coeffs) {
                    fw.append(String.format(Locale.US, "            <Coefficient>%.12g</Coefficient>\n", coeff));
                }
                fw.append("          </Coefficients>\n");
                fw.append("        </EnergyCalibration>\n");
                fw.append(String.format(Locale.US, "        <ValidPulseCount>%d</ValidPulseCount>\n", calc_pulses));
                fw.append(String.format(Locale.US, "        <TotalPulseCount>%d</TotalPulseCount>\n", calc_pulses));
                fw.append(String.format(Locale.US, "        <MeasurementTime>%d</MeasurementTime>\n", time_count));
                fw.append(String.format(Locale.US, "        <NumberOfSamples>%d</NumberOfSamples>\n", 0));
                fw.append("        <Spectrum>\n");

                for (int k = 0; k < tmp.length - channelCompression + 1; k += channelCompression) {
                    calc_pulses = 0;
                    for (int l = 0; l < channelCompression; l++)
                        calc_pulses += tmp[k + l];
                    fw.append(String.format(Locale.US, "          <DataPoint>%d</DataPoint>\n", calc_pulses));
                }

                fw.append("        </Spectrum>\n");
                fw.append("      </EnergySpectrum>\n");

                if (backgroundSpectrum != null && (!backgroundSpectrum.isEmpty())) {
                    time_count = (int) (backgroundSpectrum.getRealSpectrumTime());
                    tmp = backgroundSpectrum.getDataArray();
                    num_channels = tmp.length / channelCompression;
                    calc_pulses = 0;
                    for (int k = 0; k < num_channels * channelCompression; k += channelCompression) {
                        for (int l = 0; l < channelCompression; l++) {
                            calc_pulses += tmp[k + l];
                        }
                    }
                    exportCalibration = backgroundSpectrum.getSpectrumCalibration();
                    if (exportCalibration == null) {
                        throw new InvalidParameterException("Spectrum calibration is null");
                    }
                    coeffs = exportCalibration.getCoeffArray();
                    count = 1;
                    for (int j = 0; j < coeffs.length; j++) {
                        coeffs[j] *= count;
                        count *= channelCompression;
                    }
                    fw.append("      <BackgroundEnergySpectrum>\n");
                    fw.append(String.format(Locale.US, "        <NumberOfChannels>%d</NumberOfChannels>\n", num_channels));
                    fw.append(String.format(Locale.US, "        <ChannelPitch>%.4f</ChannelPitch>\n", 100.0 * channelCompression / Constants.NUM_HIST_POINTS));
                    fw.append("        <EnergyCalibration>\n");
                    fw.append(String.format(Locale.US, "          <PolynomialOrder>%d</PolynomialOrder>\n", coeffs.length - 1));
                    fw.append("          <Coefficients>\n");
                    for (double coeff : coeffs) {
                        fw.append(String.format(Locale.US, "            <Coefficient>%.12g</Coefficient>\n", coeff));
                    }
                    fw.append("          </Coefficients>\n");
                    fw.append("        </EnergyCalibration>\n");
                    fw.append(String.format(Locale.US, "        <ValidPulseCount>%d</ValidPulseCount>\n", calc_pulses));
                    fw.append(String.format(Locale.US, "        <TotalPulseCount>%d</TotalPulseCount>\n", calc_pulses));
                    fw.append(String.format(Locale.US, "        <MeasurementTime>%d</MeasurementTime>\n", time_count));
                    fw.append(String.format(Locale.US, "        <NumberOfSamples>%d</NumberOfSamples>\n", 0));
                    fw.append("        <Spectrum>\n");

                    for (int k = 0; k < tmp.length - channelCompression + 1; k += channelCompression) {
                        calc_pulses = 0;
                        for (int l = 0; l < channelCompression; l++)
                            calc_pulses += tmp[k + l];
                        fw.append(String.format(Locale.US, "          <DataPoint>%d</DataPoint>\n", calc_pulses));
                    }

                    fw.append("        </Spectrum>\n");
                    fw.append("      </BackgroundEnergySpectrum>\n");
                }
                fw.append("      <Visible>true</Visible>\n");
                fw.append("      <PulseCollection>\n");
                fw.append("        <Format>Base64 encoded binary</Format>\n");
                fw.append("        <Pulses />\n");
                fw.append("      </PulseCollection>\n");
                fw.append("    </ResultData>\n");
            }

            fw.append("  </ResultDataList>\n");
            fw.append("</ResultDataFile>\n");
        }
    }

    private void validateSaveState() throws IllegalStateException {
        if (this.spectrumList.isEmpty()) {
            throw new IllegalStateException("No spectrum to save.");
        }
    }
}

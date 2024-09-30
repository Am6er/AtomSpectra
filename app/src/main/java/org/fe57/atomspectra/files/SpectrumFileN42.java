package org.fe57.atomspectra.files;

import android.content.Context;

import androidx.annotation.NonNull;

import org.fe57.atomspectra.data.Calibration;
import org.fe57.atomspectra.data.Spectrum;

import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.UUID;

/**
 * This class is used to load and store N42 spectra.
 * @author S. Epiphanov.
 * @version 1.0
 */
public class SpectrumFileN42 extends SpectrumFile {
    @Override
    public boolean loadSpectrum(@NonNull InputStream histFile, Context context) {
        return false;
    }

    @Override
    public boolean saveSpectrum(@NonNull OutputStream docStream, Context context) {
        //can't save if nothing
        if (spectrumNumber() == 0)
            return false;

        BufferedWriter fw;
        try {
            fw = new BufferedWriter(new OutputStreamWriter(docStream));
            fw.append("<?xml version=\"1.0\"?>\n");
            fw.append("<?xml-model href=\"http://physics.nist.gov/N42/2011/N42/schematron/n42.sch\" type=\"application/xml\" schematypens=\"http://purl.oclc.org/dsdl/schematron\"?>\n");
            fw.append("<RadInstrumentData xmlns=\"http://physics.nist.gov/N42/2011/N42\"\n");
            fw.append("                   xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
            fw.append("                   xsi:schemaLocation=\"http://physics.nist.gov/N42/2011/N42 http://physics.nist.gov/N42/2011/n42.xsd\"\n");
            fw.append("                   n42DocUUID=\"").append(String.valueOf(UUID.randomUUID())).append("\">\n");
            fw.append("  <RadInstrumentInformation id=\"RadInstrument\">\n");
            fw.append("    <RadInstrumentManufacturerName>KB Radar</RadInstrumentManufacturerName>\n");
            fw.append("    <RadInstrumentModelName>Nano</RadInstrumentModelName>\n");
            fw.append("    <RadInstrumentClassCode>Radionuclide Identifier</RadInstrumentClassCode>\n");
            fw.append("    <RadInstrumentVersion>\n");
            fw.append("      <RadInstrumentComponentName>Hardware</RadInstrumentComponentName>\n");
            fw.append("      <RadInstrumentComponentVersion>Spectra</RadInstrumentComponentVersion>\n");
            fw.append("    </RadInstrumentVersion>\n");
            fw.append("    <RadInstrumentVersion>\n");
            fw.append("      <RadInstrumentComponentName>SoftwareName</RadInstrumentComponentName>\n");
            fw.append("      <RadInstrumentComponentVersion>AtomSpectra</RadInstrumentComponentVersion>\n");
            fw.append("    </RadInstrumentVersion>\n");
            fw.append("    <RadInstrumentVersion>\n");
            fw.append("      <RadInstrumentComponentName>Software</RadInstrumentComponentName>\n");
            fw.append("      <RadInstrumentComponentVersion>").append(context.getApplicationContext().getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName).append("</RadInstrumentComponentVersion>\n");
            fw.append("    </RadInstrumentVersion>\n");
            fw.append("  </RadInstrumentInformation>\n");

            fw.append("  <RadDetectorInformation id=\"Detector\">\n");
            fw.append("    <RadDetectorCategoryCode>Gamma</RadDetectorCategoryCode>\n");
            fw.append("    <RadDetectorKindCode>CsI</RadDetectorKindCode>\n");
            fw.append("  </RadDetectorInformation>\n");
        } catch (Exception e) {
            return false;
        }

        GregorianCalendar dateNow;
        GregorianCalendar dateBegin;
        Calibration exportCalibration;
        Spectrum spectrum;
        double[] coeffs;
        int count;
        for (int spc = 0; spc < spectrumList.size(); spc++) {
            spectrum = spectrumList.get(spc);
            dateNow = new GregorianCalendar(Locale.US);
            dateNow.setTime(new Date(spectrum.getSpectrumDate()));
            dateBegin = (GregorianCalendar) dateNow.clone();
            dateBegin.add(Calendar.SECOND, (int) -(spectrum.getRealSpectrumTime()));
            try {
                double time = StrictMath.max(spectrum.getRealSpectrumTime(), 1.0);
                long counts = 0;
                long calc_pulses;
                long[] tmp = spectrum.getSpectrum();
                for (int k = 0; k < Channels - channelCompression + 1; k += channelCompression) {
                    for (int l = 0; l < channelCompression; l++)
                        counts += tmp[k + l];
                }
                exportCalibration = new Calibration();
                coeffs = spectrum.getSpectrumCalibration().getCoeffArray();
                count = 1;
                for (int i = 0; i < coeffs.length; i++) {
                    coeffs[i] *= count;
                    count *= channelCompression;
                }
                exportCalibration.Calculate(coeffs);

                //Spectrum calibration
                fw.append("  <EnergyCalibration id=\"SpectrumCalibration-").append(String.format(Locale.US,"%d", spc)).append("\">\n");
                {
                    fw.append("    <CoefficientValues>\n");
                    double[] coefficientArray = exportCalibration.getCoeffArray();
                    for (double v : coefficientArray)
                        fw.append(String.format(Locale.US, "%.12g ", v));
                    fw.append("\n");
                    fw.append("    </CoefficientValues>\n");
                }
                fw.append("  </EnergyCalibration>\n");

                //Spectrum data
                fw.append("  <RadMeasurement id=\"SpectrumMeasurement-").append(String.format(Locale.US,"%d", spc)).append("\">\n");
                fw.append("    <MeasurementClassCode>Foreground</MeasurementClassCode>\n");
                fw.append(String.format(Locale.US, "    <StartDateTime>%04d-%02d-%02dT%02d:%02d:%02d.%03d%+03d:%02d</StartDateTime>\n",
                        dateBegin.get(Calendar.YEAR),
                        dateBegin.get(Calendar.MONTH) + 1,
                        dateBegin.get(Calendar.DAY_OF_MONTH),
                        dateBegin.get(Calendar.HOUR_OF_DAY),
                        dateBegin.get(Calendar.MINUTE),
                        dateBegin.get(Calendar.SECOND),
                        dateBegin.get(Calendar.MILLISECOND),
                        dateBegin.get(Calendar.ZONE_OFFSET) / (60 * 60 * 1000),
                        dateBegin.get(Calendar.ZONE_OFFSET) - (dateBegin.get(Calendar.ZONE_OFFSET) / (60 * 60 * 1000)) * (60 * 60 * 1000)));
                fw.append(String.format(Locale.US, "    <RealTimeDuration>PT%.2fS</RealTimeDuration>\n", time));
                fw.append("    <Spectrum id=\"SpectrumData\" radDetectorInformationReference=\"Detector\" energyCalibrationReference=\"SpectrumCalibration-").append(String.format(Locale.US,"%d", spc)).append("\">\n");
                fw.append(String.format(Locale.US, "        <LiveTimeDuration>PT%.2fS</LiveTimeDuration>\n", time));
                fw.append("      <ChannelData compressionCode=\"None\">\n");
                fw.append("        ");

                for (int k = 0; k < Channels - channelCompression + 1; k += channelCompression) {
                    calc_pulses = 0;
                    for (int l = 0; l < channelCompression; l++)
                        calc_pulses += tmp[k + l];
                    fw.append(String.format(Locale.US, "%d ", calc_pulses));
                }

                fw.append("\n");
                fw.append("      </ChannelData>\n");
                fw.append("    </Spectrum>\n");
                fw.append("    <GrossCounts id=\"GrossForeground\" radDetectorInformationReference=\"Detector\">\n");
                fw.append(String.format(Locale.US, "      <TotalCounts>%d</TotalCounts>\n", counts));
                fw.append("    </GrossCounts>\n");

                fw.append("    <RadInstrumentState radInstrumentInformationReference=\"RadInstrument\">\n");
                fw.append("      <StateVector>\n");
                fw.append("        <GeographicPoint>\n");
                if (spectrum.getGPSDate() != 0) {
                    fw.append(String.format(Locale.US, "          <LatitudeValue>%s</LatitudeValue>\n", spectrum.getLatitude()));
                    fw.append(String.format(Locale.US, "          <LongitudeValue>%s</LongitudeValue>\n", spectrum.getLongitude()));
                    } else {
                    fw.append("          <LatitudeValue>0</LatitudeValue>\n");
                    fw.append("          <LongitudeValue>0</LongitudeValue>\n");
                }
                fw.append("        </GeographicPoint>\n");
                fw.append("      </StateVector>\n");
                fw.append("    </RadInstrumentState>\n");

                fw.append("  </RadMeasurement>\n");
            } catch (Exception e) {
                return false;
            }
        }
        try {
            double time;
            long counts = 0;
            long calc_pulses;

            if (backgroundSpectrum != null && !backgroundSpectrum.isEmpty()) {
                long[] tmp = backgroundSpectrum.getSpectrum();
                GregorianCalendar dateBackgroundNow = new GregorianCalendar(Locale.US);
                dateBackgroundNow.setTime(new Date(backgroundSpectrum.getSpectrumDate()));
                GregorianCalendar dateBackgroundBegin = (GregorianCalendar) dateBackgroundNow.clone();
                dateBackgroundBegin.add(Calendar.SECOND, (int) -(backgroundSpectrum.getRealSpectrumTime()));
                time = StrictMath.max((double) backgroundSpectrum.getRealSpectrumTime(), 1.0);

                Calibration exportBackgroundCalibration = new Calibration();
                coeffs = backgroundSpectrum.getSpectrumCalibration().getCoeffArray();
                count = 1;
                for (int i = 0; i < coeffs.length; i++) {
                    coeffs[i] *= count;
                    count *= channelCompression;
                }
                exportBackgroundCalibration.Calculate(coeffs);
                if (!exportBackgroundCalibration.isCorrect())
                    throw new Exception("Calibration");
                //Background calibration
                fw.append("  <EnergyCalibration id=\"BackgroundCalibration\">\n");
                {
                    fw.append("    <CoefficientValues>\n");
                    double[] coefficientArray = exportBackgroundCalibration.getCoeffArray();
                    for (double v : coefficientArray)
                        fw.append(String.format(Locale.US, "%.12g ", v));
                    fw.append("\n");
                    fw.append("    </CoefficientValues>\n");
                }
                fw.append("  </EnergyCalibration>\n");
                //Background data
                fw.append("  <RadMeasurement id=\"BackgroundMeasurement\">\n");
                fw.append("    <MeasurementClassCode>Background</MeasurementClassCode>\n");
                fw.append(String.format(Locale.US, "    <StartDateTime>%04d-%02d-%02dT%02d:%02d:%02d.%03d%+03d:%02d</StartDateTime>\n",
                        dateBackgroundBegin.get(Calendar.YEAR),
                        dateBackgroundBegin.get(Calendar.MONTH) + 1,
                        dateBackgroundBegin.get(Calendar.DAY_OF_MONTH),
                        dateBackgroundBegin.get(Calendar.HOUR_OF_DAY),
                        dateBackgroundBegin.get(Calendar.MINUTE),
                        dateBackgroundBegin.get(Calendar.SECOND),
                        dateBackgroundBegin.get(Calendar.MILLISECOND),
                        dateBackgroundBegin.get(Calendar.ZONE_OFFSET) / (60 * 60 * 1000),
                        dateBackgroundBegin.get(Calendar.ZONE_OFFSET) - (dateBackgroundBegin.get(Calendar.ZONE_OFFSET) / (60 * 60 * 1000)) * (60 * 60 * 1000)));
                fw.append(String.format(Locale.US, "    <RealTimeDuration>PT%.2fS</RealTimeDuration>\n", time));
                fw.append("    <Spectrum id=\"BackgroundData\" radDetectorInformationReference=\"Detector\" energyCalibrationReference=\"BackgroundCalibration\">\n");
                fw.append(String.format(Locale.US, "        <LiveTimeDuration>PT%.2fS</LiveTimeDuration>\n", time));
                fw.append("      <ChannelData compressionCode=\"None\">\n");
                fw.append("        ");

                for (int k = 0; k < Channels - channelCompression + 1; k += channelCompression) {
                    calc_pulses = 0;
                    for (int l = 0; l < channelCompression; l++) {
                        calc_pulses += tmp[k + l];
                        counts += tmp[k + l];
                    }
                    fw.append(String.format(Locale.US, "%d ", calc_pulses));
                }

                fw.append("\n");
                fw.append("      </ChannelData>\n");
                fw.append("    </Spectrum>\n");

                fw.append("    <GrossCounts id=\"GrossBackground\" radDetectorInformationReference=\"Detector\">\n");
                fw.append(String.format(Locale.US, "      <TotalCounts>%d</TotalCounts>\n", counts));
                fw.append("    </GrossCounts>\n");

                fw.append("    <RadInstrumentState radInstrumentInformationReference=\"RadInstrument\">\n");
                fw.append("      <StateVector>\n");
                fw.append("        <GeographicPoint>\n");
                fw.append(String.format(Locale.US, "          <LatitudeValue>%.10f</LatitudeValue>\n", backgroundSpectrum.getLatitude()));
                fw.append(String.format(Locale.US, "          <LongitudeValue>%.10f</LongitudeValue>\n", backgroundSpectrum.getLongitude()));
                fw.append("        </GeographicPoint>\n");
                fw.append("      </StateVector>\n");
                fw.append("    </RadInstrumentState>\n");

                fw.append("  </RadMeasurement>\n");
            }

            fw.append("</RadInstrumentData>\n");
            fw.close();
        } catch (Exception e) {
            return false;
        }
        return true;
    }
}

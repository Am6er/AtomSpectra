package org.fe57.atomspectra;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.core.util.Pair;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.security.InvalidParameterException;
import java.util.Date;
import java.util.List;
import java.util.Locale;

//This is the main class to load and store own Atom Spectra spectrum
public class SpectrumFileAS extends SpectrumFile {
    @Override
    public void loadSpectrum(@NonNull Uri spectrumFilePath, Context context) throws InvalidParameterException, IOException {
        validateLoadState();

        InputStream inputFile = context.getContentResolver().openInputStream(spectrumFilePath);
        if (inputFile == null) {
            throw new IOException("Unable to open spectrum file: " + spectrumFilePath);
        }
        try (InputStream in = inputFile;
             BufferedReader fr = new BufferedReader(new InputStreamReader(in))) {
            String version = fr.readLine();
            if (!version.matches("^[+-]?\\d+(\\.(\\d+)?)?$")) {
                loadSpectrumV3(fr, version);
            } else {
                loadSpectrumV1(fr, version);
            }
        }
    }

    // old spectrum data
    private void loadSpectrumV1(BufferedReader fr, String version) throws InvalidParameterException, IOException {
        Spectrum spectrum = new Spectrum();

        double spectrumTime;
        try {
            spectrumTime = Double.parseDouble(version);
        } catch (Exception e) {
            throw new InvalidParameterException(String.format("Unable to parse spectrum time: %s", version));
        }
        spectrum.setRealSpectrumTime(spectrumTime);

        String poliFactorStr = fr.readLine();
        int poliFactor;
        try {
            poliFactor = Integer.parseInt(poliFactorStr);
        } catch (Exception e) {
            throw new InvalidParameterException(String.format("Unable to parse polinom factor: %s", poliFactorStr));
        }

        if ((poliFactor < 1) || (poliFactor > Constants.MAX_POLI_SIZE)) {
            throw new InvalidParameterException(String.format("Unsupported polinom factor value: %d", poliFactor));
        }
        Calibration save_calibration = new Calibration();
        // V1 files carry no channel count, so they are assumed to hold exactly NUM_HIST_POINTS channels.
        int hist_compress = 1;

        double cal, cal_E;
        for (int i = 0; i <= poliFactor; i++) {
            String channelStr = fr.readLine();
            double channel;
            try {
                channel = Double.parseDouble(channelStr);
            } catch (Exception e) {
                throw new InvalidParameterException(String.format("Unable to parse calibration channel: %s", channelStr));
            }
            cal = channel / hist_compress;

            String energyStr = fr.readLine();
            double energy;
            try {
                energy = Double.parseDouble(energyStr);
            } catch (Exception e) {
                throw new InvalidParameterException(String.format("Unable to parse calibration energy: %s", energyStr));
            }
            cal_E = energy;

            if (save_calibration.containsPointChannel((int) cal)) {
                throw new InvalidParameterException("The same calibration point has been already added");
            }
            save_calibration.addPoint((int) cal, cal_E);
        }
        save_calibration.Calculate();
        if (!save_calibration.isCorrect()) {
            throw new InvalidParameterException("Incorrect calibration");
        }
        long[] tmp = new long[Constants.NUM_HIST_POINTS];
        for (int i = 0; i < Constants.NUM_HIST_POINTS; i++) {
            for (int j = 0; j < hist_compress; j++) {
                if ((i * hist_compress + j) < 65536) {
                    String countsStr = fr.readLine();
                    long counts;
                    try {
                        counts = Long.parseLong(countsStr);
                    } catch (Exception e) {
                        throw new InvalidParameterException(String.format("Unable to parse channel counts: %s", countsStr));
                    }

                    tmp[i] += counts;
                }
            }
        }
        spectrum.setSpectrumCalibration(save_calibration)
                .setSpectrumOnly(tmp)
                .setChanged(false);

        spectrumList.add(spectrum);
    }

    //V2, V3 spectrum data
    private void loadSpectrumV3(BufferedReader fr, String version) throws InvalidParameterException, IOException {
        Spectrum spectrum = new Spectrum();
        int formatCode;
        try {
            formatCode = Integer.parseInt(version.substring(8));
        } catch (Exception ignored) {
            throw new InvalidParameterException(String.format("Unable to parse version string: %s", version));
        }

        if (formatCode < 1 || formatCode > 3) {
            throw new InvalidParameterException(String.format("Unsupported version: %d", formatCode));
        }
        if (formatCode >= 2) {
            String comments = fr.readLine();
            String dateStr = fr.readLine();
            long date;
            try {
                date = Long.parseLong(dateStr);
            } catch (Exception e) {
                throw new InvalidParameterException(String.format("Unable to parse date: %s", dateStr));
            }

            String GPSDateStr = fr.readLine();
            long GPSDate;
            try {
                GPSDate = Long.parseLong(GPSDateStr);
            } catch (Exception e) {
                throw new InvalidParameterException(String.format("Unable to parse gps date: %s", GPSDateStr));
            }

            String latStr = fr.readLine();
            double lat;
            try {
                lat = Double.parseDouble(latStr);
            } catch (Exception e) {
                throw new InvalidParameterException(String.format("Unable to parse gps latitude: %s", latStr));
            }

            String lonStr = fr.readLine();
            double lon;
            try {
                lon = Double.parseDouble(lonStr);
            } catch (Exception e) {
                throw new InvalidParameterException(String.format("Unable to parse gps longitude: %s", lonStr));
            }

            spectrum
                    .setLocationOnly(lat, lon, GPSDate)
                    .setComments(comments)
                    .setSpectrumDate(date);
        }
        if (formatCode >= 3) {
            spectrum
                    .setSuffix(fr.readLine())
                    .setDeviceInfo(fr.readLine());
        }

        String spectrumTimeStr = fr.readLine();
        double spectrumTime;
        try {
            spectrumTime = Double.parseDouble(spectrumTimeStr);
        } catch (Exception e) {
            throw new InvalidParameterException(String.format("Unable to parse spectrum time: %s", spectrumTimeStr));
        }
        spectrum.setRealSpectrumTime(spectrumTime);

        String channelCountStr = fr.readLine();
        int channelCount;
        try {
            channelCount = Integer.parseInt(channelCountStr);
        } catch (Exception e) {
            throw new InvalidParameterException(String.format("Unable to parse channel count: %s", channelCountStr));
        }

        String calPoliFactorStr = fr.readLine();
        int calPoliFactor;
        try {
            calPoliFactor = Integer.parseInt(calPoliFactorStr);
        } catch (Exception e) {
            throw new InvalidParameterException(String.format("Unable to parse calibration polinom factor: %s", calPoliFactorStr));
        }

        if ((calPoliFactor < 1) || (calPoliFactor > Constants.MAX_POLI_SIZE)) {
            throw new InvalidParameterException(String.format("Unsupported calibration polinom factor: %d", calPoliFactor));
        }

        Calibration save_calibration = new Calibration();
        int num_points = StrictMath.min(channelCount, Constants.NUM_HIST_POINTS);
        int compactness = num_points / Constants.NUM_HIST_POINTS;
        if (num_points % Constants.NUM_HIST_POINTS != 0) {
            compactness++;
        }
        if (formatCode >= 3) {
            double x = 1;
            double[] coeffs = new double[calPoliFactor + 1];
            for (int i = 0; i <= calPoliFactor; i++) {
                String poliCoeffStr = fr.readLine();
                double poliCoeff;
                try {
                    poliCoeff = Double.parseDouble(poliCoeffStr);
                } catch (Exception e) {
                    throw new InvalidParameterException(String.format("Unable to parse calibration coefficient: %s", poliCoeffStr));
                }

                coeffs[i] = poliCoeff * x;
                x *= compactness;
            }
            save_calibration.Calculate(coeffs);
        } else {
            double cal, cal_E;
            for (int i = 0; i <= calPoliFactor; i++) {
                String channelStr = fr.readLine();
                double channel;
                try {
                    channel = Double.parseDouble(channelStr);
                } catch (Exception e) {
                    throw new InvalidParameterException(String.format("Unable to parse calibration channel: %s", channelStr));
                }
                cal = channel / compactness;

                String energyStr = fr.readLine();
                double energy;
                try {
                    energy = Double.parseDouble(energyStr);
                } catch (Exception e) {
                    throw new InvalidParameterException(String.format("Unable to parse calibration energy: %s", energyStr));
                }
                cal_E = energy;

                if (save_calibration.containsPointChannel((int) cal)) {
                    throw new InvalidParameterException("The same calibration point has been already added");
                }
                save_calibration.addPoint((int) cal, cal_E);
            }
            save_calibration.Calculate();
        }
        if (!save_calibration.isCorrect()) {
            throw new InvalidParameterException("Incorrect calibration");
        }

        long[] tmp = new long[Constants.NUM_HIST_POINTS];
        for (int i = 0; i < Constants.NUM_HIST_POINTS; i++) {
            for (int j = 0; j < compactness; j++) {
                if ((i * compactness + j) < num_points) {
                    String countsStr = fr.readLine();
                    long counts;
                    try {
                        counts = Long.parseLong(countsStr);
                    } catch (Exception e) {
                        throw new InvalidParameterException(String.format("Unable to parse channel counts: %s", countsStr));
                    }

                    tmp[i] += counts;
                }
            }
        }

        spectrum.setSpectrumCalibration(save_calibration)
                .setSpectrumOnly(tmp)
                .setChanged(false);

        spectrumList.add(spectrum);
    }

    @Override
    public void saveSpectrumAndCloseStream(@NonNull OutputStreamWriter docStream, Context context) throws IOException, IllegalStateException {
        try (OutputStreamWriter fw = docStream) {
            validateSaveState();
            Spectrum spectrum = spectrumList.get(0);
            long[] tmp = spectrum.getDataArray();
            double time = StrictMath.max(spectrum.getRealSpectrumTime(), 1.0);

            fw.append("FORMAT: 3\n");
            fw.append(String.format(Locale.US, "%s\n", spectrum.getComments()));                           //version 2
            if (spectrum.getSpectrumDate() == 0) {
                fw.append(String.format(Locale.US, "%d\n", new Date().getTime()));                         //version 2
            } else {
                fw.append(String.format(Locale.US, "%s\n", spectrum.getSpectrumDate()));                   //version 2
            }
            fw.append(String.format(Locale.US, "%s\n", spectrum.getGPSDate()));                            //version 2
            fw.append(String.format(Locale.US, "%s\n", spectrum.getLatitude()));                           //version 2
            fw.append(String.format(Locale.US, "%s\n", spectrum.getLongitude()));                          //version 2
            fw.append(spectrum.getSuffix()).append("\n");                                                         //version 3
            fw.append(spectrum.getDeviceInfo()).append("\n");                                                    //version 3
            fw.append(String.format(Locale.US, "%f\n", time));
            fw.append(String.format(Locale.US, "%d\n", Constants.NUM_HIST_POINTS));
            fw.append(String.format(Locale.US, "%d\n", spectrum.getSpectrumCalibration().getFactor()));
            for (double coeff : spectrum.getSpectrumCalibration().getCoeffArray()) {
                fw.append(String.format(Locale.US, "%.12g\n", coeff));
            }
            for (long l : tmp) {
                fw.append(String.format(Locale.US, "%d\n", l));
            }
        }
    }

    /**
     * Loads a single spectrogram file (base spectrum + deltas) into target as a new
     * segment, appended via addSegment(). Callers loading multiple files must clear()
     * target once up front themselves and call this once per file - it no longer clears
     * on its own, so it can be called repeatedly to build up a multi-segment load.
     */
    public void loadSpectrogram(@NonNull Uri spectrogramFilePath, Context context, AtomSpectraSpectrogramData target, ProgressCallback<Integer> onDeltasLoaded, CancellationToken cancellationToken) throws InvalidParameterException, IOException {
        validateLoadState();

        InputStream histFile = context.getContentResolver().openInputStream(spectrogramFilePath);
        if (histFile == null) {
            throw new IOException("Unable to open spectrogram file: " + spectrogramFilePath);
        }
        try (InputStream in = histFile;
             BufferedReader fr = new BufferedReader(new InputStreamReader(in))) {
            String versionStr = fr.readLine();
            loadSpectrumV3(fr, versionStr);
            target.addSegment(this.spectrumList.get(0), spectrogramFilePath);
            // load deltas
            while (true) {
                if (cancellationToken.isCancelled()) {
                    break;
                }

                if (target.rowCount() >= AtomSpectraSpectrogramData.MAX_ROWS) {
                    ToastHelper.showToast(context, "WARNING: Spectrogram max rows limit reached: " + AtomSpectraSpectrogramData.MAX_ROWS);
                    break;
                }

                SpectrumDelta delta = readNextDelta(fr, Constants.NUM_HIST_POINTS / AtomSpectraSpectrogramData.CHANNEL_COUNT);
                if (delta == null) {
                    break;
                }
                target.addDelta(delta.channels, delta.duration, delta.date);

                if (target.rowCount() > 0 && target.rowCount() % 50 == 0) {
                    onDeltasLoaded.accept(target.rowCount());
                }
            }
        }
    }

    /**
     * Combines deltas from one or more segment ranges (see
     * {@link AtomSpectraSpectrogramData#resolveExportRanges}) into a single exported
     * spectrum file. Ranges are processed in order; only deltas are combined across
     * ranges/segments — base spectrums are read solely to locate/skip to each range's
     * deltas within its own file. The combined output's calibration and device info come
     * from the first (chronologically earliest) range's base spectrum only.
     */
    public String exportSpectrogramPartAsSpectrum(@NonNull List<AtomSpectraSpectrogramData.SegmentExportRange> ranges, String spectrumName, Context context, ProgressCallback<String> onProgress, CancellationToken cancellationToken) throws InvalidParameterException, IOException {
        validateLoadState();

        if (ranges.isEmpty()) {
            throw new InvalidParameterException("No segment ranges to export");
        }

        int totalDeltaCount = 0;
        for (AtomSpectraSpectrogramData.SegmentExportRange range : ranges) {
            if (range.fileToDelta < range.fileFromDelta || range.fileToDelta < 0 || range.fileFromDelta < 0) {
                throw new InvalidParameterException(String.format("Invalid delta indices [%d, %d]", range.fileFromDelta, range.fileToDelta));
            }
            totalDeltaCount += range.fileToDelta - range.fileFromDelta + 1;
        }

        Spectrum baseSpectrum = null;
        long[] combinedSpectrum = new long[Constants.NUM_HIST_POINTS];
        double combinedDuration = 0;
        long lastDeltaDate = 0;
        int processedDeltaCount = 0;

        for (AtomSpectraSpectrogramData.SegmentExportRange range : ranges) {
            if (cancellationToken.isCancelled()) {
                break;
            }

            InputStream histFile = context.getContentResolver().openInputStream(range.spectrogramFileName);
            if (histFile == null) {
                throw new IOException("Unable to open spectrogram file: " + range.spectrogramFileName);
            }

            // Each segment's own file is loaded via its own SpectrumFileAS instance:
            // validateLoadState()/spectrumList are per-instance, and each range's base
            // spectrum is only needed transiently to skip to that range's deltas (except
            // for the very first range, whose base spectrum becomes the combined output's
            // calibration).
            SpectrumFileAS segmentFile = new SpectrumFileAS();
            try (InputStream in = histFile;
                 BufferedReader fr = new BufferedReader(new InputStreamReader(in))) {
                String versionStr = fr.readLine();
                onProgress.accept(context.getString(R.string.spectrogram_spectrum_export_progress_loading_base, spectrumName));
                segmentFile.loadSpectrumV3(fr, versionStr);
                if (baseSpectrum == null) {
                    baseSpectrum = segmentFile.spectrumList.get(0);
                }
                // TODO: validate channel count

                int deltaIndex = 0;
                onProgress.accept(context.getString(R.string.spectrogram_spectrum_export_progress_seeking_deltas, spectrumName));
                while (true) {
                    if (cancellationToken.isCancelled()) {
                        break;
                    }

                    if (deltaIndex < range.fileFromDelta) {
                        segmentFile.skipNextDelta(fr);
                        deltaIndex++;
                        continue;
                    }

                    if (deltaIndex > range.fileToDelta) {
                        break;
                    }

                    if (processedDeltaCount % 50 == 0) {
                        int progressPercent = processedDeltaCount * 100 / totalDeltaCount;
                        onProgress.accept(context.getString(R.string.spectrogram_spectrum_export_progress_combining_deltas_percent, spectrumName, progressPercent));
                    }
                    SpectrumDelta delta = segmentFile.readNextDelta(fr, 1);
                    if (delta == null) {
                        throw new InvalidParameterException(String.format("Null delta for index: %d", deltaIndex));
                    }
                    lastDeltaDate = delta.date;
                    combinedDuration += delta.duration;
                    for (int i = 0; i < combinedSpectrum.length; i++) {
                        combinedSpectrum[i] += delta.channels[i];
                    }

                    deltaIndex++;
                    processedDeltaCount++;
                }
            }
        }

        if (cancellationToken.isCancelled()) {
            return null;
        }

        onProgress.accept(context.getString(R.string.spectrogram_spectrum_export_progress_saving_spectrum, spectrumName));
        Spectrum spectrumToSave = new Spectrum(baseSpectrum);
        spectrumToSave.setRealSpectrumTime(combinedDuration);
        spectrumToSave.setSuffix(spectrumName);
        spectrumToSave.setLocation(0, 0, 0);
        spectrumToSave.setSpectrumDate(lastDeltaDate);
        spectrumToSave.setSpectrumOnly(combinedSpectrum);
        spectrumToSave.updateComments();

        Pair<OutputStreamWriter, Uri> streamInfo = SpectrumFile.prepareOutputFileStream(context, context.getString(R.string.file_atomspectra_spectrum_prefix), 0, spectrumName, ".txt", "text/plain", false);
        OutputStreamWriter docStream = streamInfo.first;
        String spectrumFileName = streamInfo.second.getPath();

        SpectrumFileAS saveFile = new SpectrumFileAS();
        saveFile.addSpectrum(spectrumToSave)
                .setChannelCompression(1);
        saveFile.saveSpectrumAndCloseStream(docStream, context);

        return spectrumFileName;
    }

    public void saveDeltaSpectrumAndCloseStream(@NonNull OutputStreamWriter docStream) throws IOException {
        try (OutputStreamWriter fw = docStream) {
            validateSaveState();

            Spectrum spectrum = spectrumList.get(0);
            long[] tmp = spectrum.getDataArray();
            double time = StrictMath.max(spectrum.getRealSpectrumTime(), 1.0);

            if (spectrum.getSpectrumDate() == 0) {
                fw.append(String.format(Locale.US, "%d\n", new Date().getTime()));
            } else {
                fw.append(String.format(Locale.US, "%s\n", spectrum.getSpectrumDate()));
            }

            fw.append(String.format(Locale.US, "%s\n", spectrum.getLatitude()));
            fw.append(String.format(Locale.US, "%s\n", spectrum.getLongitude()));
            fw.append(String.format(Locale.US, "%f\n", time));

            for (long l : tmp) {
                fw.append(String.format(Locale.US, "%d\t", l));
            }
            fw.append("\n");
        }
    }

    private SpectrumDelta readNextDelta(BufferedReader buffer, int channelBinning) throws InvalidParameterException, IOException {
        String dateStr = buffer.readLine();
        if (dateStr == null || dateStr.isEmpty()) {
            // EOF
            return null;
        }

        long date;
        try {
            date = Long.parseLong(dateStr);
        } catch (Exception e) {
            throw new InvalidParameterException(String.format("Unable to parse delta date: %s", dateStr));
        }

        // skip lat/lon as those values not used at the time (slightly speeds up parsing)
        // double latitude = Double.parseDouble(fr.readLine());
        // double longitude = Double.parseDouble(fr.readLine());
        buffer.readLine();
        buffer.readLine();

        String durationStr = buffer.readLine();
        double duration;
        try {
            duration = Double.parseDouble(durationStr);
        } catch (Exception e) {
            throw new InvalidParameterException(String.format("Unable to parse delta duration: %s", durationStr));
        }

        String channelStr = buffer.readLine();
        if (channelStr == null || channelStr.isEmpty()) {
            throw new InvalidParameterException(String.format("Unable to parse delta channels: %s", channelStr));
        }

        String[] channelsStr = channelStr.split("\t");
        if (channelsStr.length != Constants.NUM_HIST_POINTS) {
            throw new InvalidParameterException(String.format("Unsupported delta channels count: %d", channelsStr.length));
        }

        int channelCount = Constants.NUM_HIST_POINTS / channelBinning;
        long[] channels = new long[channelCount];
        for (int i = 0; i < Constants.NUM_HIST_POINTS; i += channelBinning) {
            long summ = 0;
            for (int j = 0; j < channelBinning && (i + j) < channelsStr.length; j++) {
                try {
                    summ += Long.parseLong(channelsStr[i + j]);
                } catch (Exception e) {
                    throw new InvalidParameterException(String.format("Unable to parse channel value: %s", channelsStr[i + j]));
                }
            }

            channels[i / channelBinning] = summ;
        }

        return new SpectrumDelta(duration, channels, date);
    }

    private void skipNextDelta(BufferedReader buffer) throws IOException {
        buffer.readLine(); // date
        buffer.readLine(); // lat
        buffer.readLine(); // lon
        buffer.readLine(); // duration
        buffer.readLine(); // channels
    }

    private void validateSaveState() throws IllegalStateException {
        if (this.spectrumList.isEmpty()) {
            throw new IllegalStateException("No spectrum to save.");
        }

        if (this.spectrumList.size() > 1) {
            throw new IllegalStateException("Only single spectrum is supported by AtomSpectra file format.");
        }

        if (this.backgroundSpectrum != null) {
            throw new IllegalStateException("Background spectrum is not supported by AtomSpectra file format.");
        }
    }

    private void validateLoadState() throws IllegalStateException {
        if (!this.spectrumList.isEmpty()) {
            throw new IllegalStateException("Spectrum is already loaded.");
        }
    }

    private static class SpectrumDelta {
        public final double duration; // s
        public final long[] channels; // spectrum channels

        public final long date; // date

        private SpectrumDelta(
                double duration,
                long[] channels,
                long date) {
            this.duration = duration;
            this.channels = channels;
            this.date = date;
        }
    }
}

package org.fe57.atomspectra.data;

import android.location.Location;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/**
 * @author S. Epiphanov.
 * @version 1.0
 * This class contains information about spectrum itself. To load or store use a SpectrumFile class.
 */
public class Spectrum {
    private @NonNull SpectrumData DataArray;                                              //spectrum data
    private long SpectrumTime;                                                            //total amount of time collected
    private long SpectrumDate;                                                            //last spectrum update date
    private @NonNull Calibration SpectrumCalibration = Calibration.defaultCalibration();  //spectrum calibration
    private @NonNull String Comments = "";                                                //full comment string to save if exists
    private @NonNull String Suffix = "";                                                  //suffix to added to comment
    private long GPSDate;                                                                 //last GPS update time
    private double Latitude;
    private double Longitude;
    private String DetectedIsotopes;              //plan to store detected isotopes
    private boolean Changed;                      // functions ...Only don't change this state

    /**
     * Default constructor
     */
    public Spectrum () {
        DataArray = new long[1];
        initSpectrumData(Constants.NUM_HIST_POINTS);
    }

    /**
     * Default constructor
     */
    public Spectrum (int numChannels) {
        DataArray = new long[1];
        //BackupArray = new long[1];
        initSpectrumData(numChannels);
    }

    /**
     * @param data initial spectrum data
     * @param time initial read time
     * @param calibration initial calibration data
     */
    public Spectrum(@NonNull long[] data, long time, @NonNull Calibration calibration) {
        initSpectrumData(data.length);
        DataArray = Arrays.copyOf(data, data.length);
        //BackupArray = new long[DataArray.length];
        //BackupSpectrumTime = 0;
        if (calibration.isCorrect())
            SpectrumCalibration = new Calibration(calibration);
        SpectrumTime = time;
        Changed = false;
    }

    public Spectrum(Spectrum other) {
        DataArray = Arrays.copyOf(other.DataArray, other.DataArray.length);
        //BackupArray = Arrays.copyOf(other.BackupArray, other.BackupArray.length);
        SpectrumCalibration = new Calibration(other.SpectrumCalibration);
        SpectrumTime = other.SpectrumTime;
        //BackupSpectrumTime = other.BackupSpectrumTime;
        Comments = other.Comments;
        Suffix = other.Suffix;
        SpectrumDate = other.SpectrumDate;
        GPSDate = other.GPSDate;
        Latitude = other.Latitude;
        Longitude = other.Longitude;
        DetectedIsotopes = other.DetectedIsotopes;
        Changed = other.Changed;
    }

    public Spectrum Clone(Spectrum other) {
        this.DataArray = Arrays.copyOf(other.DataArray, other.DataArray.length);
        //this.BackupArray = Arrays.copyOf(other.BackupArray, other.BackupArray.length);
        SpectrumCalibration = new Calibration(other.SpectrumCalibration);
        SpectrumTime = other.SpectrumTime;
        //BackupSpectrumTime = other.BackupSpectrumTime;
        Comments = other.Comments;
        Suffix = other.Suffix;
        SpectrumDate = other.SpectrumDate;
        GPSDate = other.GPSDate;
        Latitude = other.Latitude;
        Longitude = other.Longitude;
        DetectedIsotopes = other.DetectedIsotopes;
        Changed = other.Changed;
        return this;
    }

    public Calibration getSpectrumCalibration() {
        return SpectrumCalibration;
    }

    public Spectrum setSpectrumCalibration(@NonNull Calibration calibration) {
        if (calibration.isCorrect())
            SpectrumCalibration = new Calibration(calibration);
        else
            SpectrumCalibration = Calibration.defaultCalibration();
        return this;
    }

    //return reference to array
    public long[] getSpectrum() {
        return Arrays.copyOf(DataArray, DataArray.length);
    }

    public long[] getDataArray() {
        return DataArray;
    }

//    public long[] getBackupArray() {
//        return BackupArray;
//    }

    //get number of counts
    public long getTotalCounts() {
        long result = 0;
        for (long l : DataArray) {
            result += l;
        }
//        for (long l : BackupArray) {
//            result += l;
//        }
        return result;
    }

    //update
    public Spectrum setSpectrum(long[] data) {
        DataArray = Arrays.copyOf(data, data.length);
//        if (DataArray.length != BackupArray.length) {
//            BackupArray = new long[DataArray.length];
//            BackupSpectrumTime = 0;
//        }
        SpectrumDate = System.currentTimeMillis();
        Changed = true;
        return this;
    }

    public Spectrum setSpectrumOnly(long[] data) {
        DataArray = Arrays.copyOf(data, data.length);
//        if (DataArray.length != BackupArray.length) {
//            BackupArray = new long[DataArray.length];
//            BackupSpectrumTime = 0;
//        }
        return this;
    }

//    public Spectrum setBackup(long[] data) {
//        BackupArray = Arrays.copyOf(data, data.length);
//        if (DataArray.length != BackupArray.length) {
//            DataArray = new long[BackupArray.length];
//            SpectrumTime = 0;
//        }
//        SpectrumDate = System.currentTimeMillis();
//        Changed = true;
//        return this;
//    }

//    public Spectrum setBackupOnly(long[] data) {
//        BackupArray = Arrays.copyOf(data, data.length);
//        if (DataArray.length != BackupArray.length) {
//            DataArray = new long[BackupArray.length];
//            SpectrumTime = 0;
//        }
//        return this;
//    }

//    public Spectrum moveToBackup() {
//        BackupArray = DataArray;
//        BackupSpectrumTime = SpectrumTime;
//        DataArray = new long[BackupArray.length];
//        SpectrumTime = 0;
//        return this;
//    }

    //add data from another spectrum to current
    public @Nullable Spectrum addSpectrum(long[] data) {
        if (DataArray.length != data.length)
            return null;
        for(int i = 0; i < DataArray.length; i++)
            DataArray[i] += data[i];
        SpectrumDate = System.currentTimeMillis();
        Changed = true;
        return this;
    }

    public @Nullable Spectrum addSpectrum(Spectrum spectrum) {
        if (DataArray.length != spectrum.DataArray.length)
            return null;
        for(int i = 0; i < DataArray.length; i++)
            DataArray[i] += spectrum.DataArray[i];
        SpectrumTime += spectrum.SpectrumTime;
        SpectrumDate = System.currentTimeMillis();
        Changed = true;
        return this;
    }

    //add data from another spectrum to current without data update
    public @Nullable Spectrum addSpectrumOnly(long[] data) {
        if (DataArray.length != data.length)
            return null;
        for(int i = 0; i < DataArray.length; i++)
            DataArray[i] += data[i];
        return this;
    }

    //add data from another spectrum to current without data update
    public @Nullable Spectrum addSpectrumOnly(Spectrum spectrum) {
        if (DataArray.length != spectrum.DataArray.length)
            return null;
        for(int i = 0; i < DataArray.length; i++)
            DataArray[i] += spectrum.DataArray[i];
        return this;
    }

    /**
     * Changes spectrum as setSpectrum but do not update if data has a different length.
     * Updates date of change.
     * @param data new spectrum data with the same size
     * @return true if update is successful
     */
    public boolean updateSpectrum(long[] data) {
        if (DataArray.length != data.length)
            return false;
        System.arraycopy(data, 0, DataArray, 0, data.length);
        SpectrumDate = System.currentTimeMillis();
        Changed = true;
        return true;
    }

    /**
     * Changes spectrum as setSpectrum but do not update if data has a different length.
     * Do not update date of change.
     * @param data new spectrum data with the same size
     * @return true if update is successful
     */
    public boolean updateSpectrumOnly(long[] data) {
        if (DataArray.length != data.length)
            return false;
        System.arraycopy(data, 0, DataArray, 0, data.length);
        return true;
    }

    /**
     * Replaces one value in spectrum. Updates date of change on success.
     * @param channel number of channel, 0..max-1
     * @param data new value
     * @return true if update is successful
     */
    public boolean setSpectrumValue(int channel, long data) {
        if (channel < 0 || channel >= DataArray.length)
            return false;
        DataArray[channel] = data;
        SpectrumDate = System.currentTimeMillis();
        Changed = true;
        return true;
    }

    /**
     * Replaces one value in spectrum. Do not update date of change.
     * @param channel number of channel, 0..max-1
     * @param data new value
     * @return true if update is successful
     */
    public boolean setSpectrumValueOnly(int channel, long data) {
        if (channel < 0 || channel >= DataArray.length)
            return false;
        DataArray[channel] = data;
        return true;
    }

    /**
     * Increases channel value by 1 and updates date of change on success.
     * @param channel number of channel, 0..max-1
     * @return true if increment is successful
     */
    public boolean incSpectrumValue(int channel) {
        if (channel < 0 || channel >= DataArray.length)
            return false;
        DataArray[channel]++;
        SpectrumDate = System.currentTimeMillis();
        Changed = true;
        return true;
    }

    /**
     * Increases channel value by 1 on success. Does not update date of change.
     * @param channel number of channel, 0..max-1
     * @return true if increment is successful
     */
    public boolean incSpectrumValueOnly(int channel) {
        if (channel < 0 || channel >= DataArray.length)
            return false;
        DataArray[channel]++;
        return true;
    }

    /**
     * @return true if spectrum is changes since date of last set to false.
     */
    public boolean isChanged() {
        return Changed;
    }

    /**
     * Sets Change flag to the desired value on demand.
     * @param changed spectrum status
     * @return current instance
     */
    public Spectrum setChanged(boolean changed) {
        Changed = changed;
        return this;
    }

    /**
     * Returns status of spectrum
     * @return true if none of data is collected
     */
    public boolean isEmpty() {
        return SpectrumTime == 0;
    }

    /**
     * Returns full time of collecting the spectrum
     * @return spectrum collect time
     */
    public long getSpectrumTime() {
        return SpectrumTime;
    }

    public Spectrum setSpectrumTime(long spectrumTime) {
        SpectrumTime = spectrumTime;
        return this;
    }

//    public Spectrum setBackupSpectrumTime(long spectrumTime) {
//        BackupSpectrumTime = spectrumTime;
//        return this;
//    }

    public double getRealSpectrumTime() {
        return SpectrumTime * Constants.UPDATE_PERIOD / 1000.0;
    }

    public Spectrum setRealSpectrumTime(double spectrumTime) {
        SpectrumTime = (long)StrictMath.rint(spectrumTime * 1000.0 / Constants.UPDATE_PERIOD);
        return this;
    }

//    public Spectrum setRealBackupSpectrumTime(double spectrumTime) {
//        BackupSpectrumTime = (long)StrictMath.rint(spectrumTime * 1000.0 / Constants.UPDATE_PERIOD);
//        return this;
//    }

    public Spectrum setLocation(Location l) {
        if ( l != null) {
            Latitude = l.getLatitude();
            Longitude = l.getLongitude();
            GPSDate = l.getTime();
        } else {
            Latitude = 0;
            Longitude = 0;
            GPSDate = 0;
        }
        Changed = true;
        return this;
    }

    public Spectrum setLocationOnly(Location l) {
        if ( l != null) {
            Latitude = l.getLatitude();
            Longitude = l.getLongitude();
            GPSDate = l.getTime();
        } else {
            Latitude = 0;
            Longitude = 0;
            GPSDate = 0;
        }
        return this;
    }

    public Spectrum setLocation(double latitude, double longitude, long timMS) {
        if (timMS > 0) {
            Latitude = latitude;
            Longitude = longitude;
            GPSDate = timMS;
        } else {
            Latitude = 0;
            Longitude = 0;
            GPSDate = 0;
        }
        Changed = true;
        return this;
    }

    public Spectrum setLocationOnly(double latitude, double longitude, long timMS) {
        if (timMS > 0) {
            Latitude = latitude;
            Longitude = longitude;
            GPSDate = timMS;
        } else {
            Latitude = 0;
            Longitude = 0;
            GPSDate = 0;
        }
        return this;
    }

    public double getLatitude() {
        return Latitude;
    }

    public double getLongitude() {
        return Longitude;
    }

    public long getGPSDate() {
        return GPSDate;
    }

    public Spectrum setSpectrumDate(long spectrumDate) {
        SpectrumDate = spectrumDate;
        return this;
    }

    public long getSpectrumDate() {
        return SpectrumDate;
    }

    public String getComments() {
        return Comments;
    }

    /**
     * Set a new non-default comment string
     * @param comments new comments string
     * @return current spectrum
     */
    public Spectrum setComments(@NonNull String comments) {
        Comments = comments;
        return this;
    }

    /**
     * Set a default comment string according to the spectrum data
     * @return current spectrum
     */
    public Spectrum updateComments() {
        long counts = getTotalCounts();
        double realTime = getRealSpectrumTime();
        if (SpectrumTime > 1) {
            Comments = prepareCommentString(new Date(SpectrumDate), counts, counts / realTime, realTime, GPSDate, Latitude, Longitude);
        } else {
            Comments = prepareCommentString(new Date(SpectrumDate), counts, 0.0, realTime, GPSDate, Latitude, Longitude);
        }
        return this;
    }

    /**
     * @param id update string of detected isotopes
     * @return current spectrum
     */
    public Spectrum setDetectedIsotopes(@NonNull String id) {
        DetectedIsotopes = id;
        return this;
    }

    public String getDetectedIsotopes() {
        return DetectedIsotopes;
    }

    /**
     * Unified format to create text fields
     */
    private final SimpleDateFormat dateZoneFormat = new SimpleDateFormat("yyyy.MM.dd HH:mm:ss Z", Locale.US);

    /**
     * Prepares a comment string to write into files
     * @param date date of spectrum
     * @param counts total number of counts
     * @param cps counted cps
     * @param time time of capture in seconds
     * @param gpsTime time in UNIX-format of last GPS update
     * @param latitude last latitude
     * @param longitude last longitude
     * @return prepared string
     */
    private String prepareCommentString(@NonNull Date date, long counts, double cps, double time, long gpsTime, double latitude, double longitude) {
        String res = "";
        if (date != null)
            res += dateZoneFormat.format(date) + " ";
        else
            res += dateZoneFormat.format(new Date()) + " ";
        if (gpsTime > 0)
            res += String.format(Locale.US, "Counts: %d, ~cps: %.3f, Time: %.2f s, Coord: %s %s at %s", counts, cps, time, GPSLocator.getFormattedLatitude(latitude), GPSLocator.getFormattedLongitude(longitude), dateZoneFormat.format(new Date(gpsTime)));
        else
            res += String.format(Locale.US, "Counts: %d, ~cps: %.3f, Time: %.2f s", counts, cps, time);
        return res;
    }

    /**
     * setSuffix is used set a suffix for files and a comment for comment line inside file
     * @param suffix a non-null String
     * @return spectrum itself
     */
    public Spectrum setSuffix(@NonNull String suffix) {
        Suffix = suffix;
        return this;
    }

    /**
     * @return suffix
     */
    @NonNull
    public String getSuffix() {
        return Suffix;
    }

    /**
     * initSpectrumData() clears all internal structures.
     * @param numChannels Number of channels in new spectrum
     * @return current spectrum instance
     */
    public Spectrum initSpectrumData(int numChannels) {
        DataArray = new long[numChannels];
        //BackupArray = new long[numChannels];
        SpectrumCalibration = Calibration.defaultCalibration(numChannels);
        SpectrumTime = 0;
        //BackupSpectrumTime = 0;
        Comments = "";
        SpectrumDate = 0;
        GPSDate = 0;
        Latitude = 0;
        Longitude = 0;
        Suffix = "";
        DetectedIsotopes = "";
        Changed = false;
        return this;
    }

    /**
     * clearDescription() leaves spectrum data and spectrum time and clears all other fields
     * @return current spectrum
     */
    public Spectrum clearDescription() {
        Comments = "";
        SpectrumDate = 0;
        GPSDate = 0;
        Latitude = 0;
        Longitude = 0;
        Suffix = "";
        DetectedIsotopes = "";
        return this;
    }
}

package org.fe57.atomspectra;

import android.location.Location;

import androidx.annotation.NonNull;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

//This class contains information about spectrum itself. To load or store use a SpectrumFile class.
public class Spectrum {
//    private final static String TAG = Spectrum.class.getSimpleName();
    private long[] DataArray;                     //spectrum data
    private long SpectrumTime;                    //total amount of time collected
    private long SpectrumDate;                    //last spectrum update date
    private Calibration SpectrumCalibration;      //spectrum calibration
    private String Comments;                      //full comment string to save if exists
    private @NonNull String Suffix = "";          //suffix to added to comment
    private long GPSDate;                         //last GPS update time
    private double Latitude;
    private double Longitude;
    private String DetectedIsotopes;              //plan to store detected isotopes
    private boolean Changed;                      // functions ...Only don't change this state

    public Spectrum () {
        initSpectrumData();
    }

    public Spectrum(long[] data, long time, Calibration calibration) {
        initSpectrumData();
        DataArray = Arrays.copyOf(data, data.length);
        SpectrumCalibration = (calibration != null && calibration.isCorrect()) ? new Calibration(calibration) : Calibration.defaultCalibration();
        SpectrumTime = time;
        Changed = false;
    }

    public Spectrum(Spectrum other) {
        this.DataArray = Arrays.copyOf(other.DataArray, other.DataArray.length);
        SpectrumCalibration = new Calibration(other.SpectrumCalibration);
        SpectrumTime = other.SpectrumTime;
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
        SpectrumCalibration = new Calibration(other.SpectrumCalibration);
        SpectrumTime = other.SpectrumTime;
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

    public Spectrum setSpectrumCalibration(Calibration calibration) {
        if (calibration != null && calibration.isCorrect())
            SpectrumCalibration = new Calibration(calibration);
        else
            SpectrumCalibration = Calibration.defaultCalibration();
        return this;
    }

    //return reference to array
    public long[] getDataArray() {
        return DataArray;
    }

    //get number of counts
    public long getTotalCounts() {
        long result = 0;
        for (long l : DataArray) {
            result += l;
        }
        return result;
    }

    //update
    public Spectrum setSpectrum(long[] data) {
        DataArray = Arrays.copyOf(data, data.length);
        SpectrumDate = System.currentTimeMillis();
        Changed = true;
        return this;
    }

    public Spectrum setSpectrumOnly(long[] data) {
        DataArray = Arrays.copyOf(data, data.length);
        return this;
    }

    //add data from another spectrum to current
    public Spectrum addSpectrum(long[] data) {
        if (DataArray.length != data.length)
            return null;
        for(int i = 0; i < DataArray.length; i++)
            DataArray[i] += data[i];
        SpectrumDate = System.currentTimeMillis();
        Changed = true;
        return this;
    }

    public Spectrum addSpectrum(Spectrum spectrum) {
        if (DataArray.length != spectrum.DataArray.length)
            return null;
        for(int i = 0; i < DataArray.length; i++)
            DataArray[i] += spectrum.DataArray[i];
        SpectrumTime += spectrum.SpectrumTime;
        SpectrumDate = System.currentTimeMillis();
        Changed = true;
        return this;
    }

    public Spectrum subtractSpectrum(Spectrum spectrum) {
        if (DataArray.length != spectrum.DataArray.length)
            return null;
        for(int i = 0; i < DataArray.length; i++)
            DataArray[i] -= spectrum.DataArray[i];
        SpectrumTime -= spectrum.SpectrumTime;
        SpectrumDate = System.currentTimeMillis();
        Changed = true;
        return this;
    }

    //add data from another spectrum to current without data update
    public Spectrum addSpectrumOnly(long[] data) {
        if (DataArray.length != data.length)
            return null;
        for(int i = 0; i < DataArray.length; i++)
            DataArray[i] += data[i];
        return this;
    }

    //add data from another spectrum to current without data update
    public Spectrum addSpectrumOnly(Spectrum spectrum) {
        if (DataArray.length != spectrum.DataArray.length)
            return null;
        for(int i = 0; i < DataArray.length; i++)
            DataArray[i] += spectrum.DataArray[i];
        return this;
    }

    //just update spectrum with the array of same size
    public boolean updateSpectrum(long[] data) {
        if (DataArray.length != data.length)
            return false;
        System.arraycopy(data, 0, DataArray, 0, data.length);
        SpectrumDate = System.currentTimeMillis();
        Changed = true;
        return true;
    }

    //just update spectrum with the array of same size
    public boolean updateSpectrumOnly(long[] data) {
        if (DataArray.length != data.length)
            return false;
        System.arraycopy(data, 0, DataArray, 0, data.length);
        return true;
    }

    public boolean setSpectrumValue(int channel, long data) {
        if (channel < 0 || channel >= DataArray.length)
            return false;
        DataArray[channel] = data;
        SpectrumDate = System.currentTimeMillis();
        Changed = true;
        return true;
    }

    public boolean setSpectrumValueOnly(int channel, long data) {
        if (channel < 0 || channel >= DataArray.length)
            return false;
        DataArray[channel] = data;
        return true;
    }

    public boolean incSpectrumValue(int channel) {
        if (channel < 0 || channel >= DataArray.length)
            return false;
        DataArray[channel]++;
        SpectrumDate = System.currentTimeMillis();
        Changed = true;
        return true;
    }

    public boolean incSpectrumValueOnly(int channel) {
        if (channel < 0 || channel >= DataArray.length)
            return false;
        DataArray[channel]++;
        return true;
    }

    public boolean isChanged() {
        return Changed;
    }

    public Spectrum setChanged(boolean changed) {
        Changed = changed;
        return this;
    }

    public boolean isEmpty() {
        return SpectrumTime == 0;
    }

    public long getSpectrumTime() {
        return SpectrumTime;
    }

    public Spectrum setSpectrumTime(long spectrumTime) {
        SpectrumTime = spectrumTime;
        return this;
    }

    public double getRealSpectrumTime() {
        return SpectrumTime * Constants.UPDATE_PERIOD / 1000.0;
    }

    public Spectrum setRealSpectrumTime(double spectrumTime) {
        SpectrumTime = (long)StrictMath.rint(spectrumTime * 1000.0 / Constants.UPDATE_PERIOD);
        return this;
    }

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

    public Spectrum setComments(@NonNull String comments) {
        Comments = comments;
        return this;
    }

    public Spectrum updateComments() {
        long counts = getTotalCounts();
        double realTime = getRealSpectrumTime();
        if (SpectrumTime > 1) {
            Comments = prepareCommentString(new Date(SpectrumDate), counts, counts / realTime, realTime, GPSDate, Latitude, Longitude);
        } else {
            Comments = prepareCommentString(new Date(SpectrumDate), counts, 0.0, realTime, GPSDate, Latitude, Longitude);
        }
//        Changed = true;
        return this;
    }

    public Spectrum setDetectedIsotopes(String id) {
        DetectedIsotopes = id;
        return this;
    }

    public String getDetectedIsotopes() {
        return DetectedIsotopes;
    }

    private final SimpleDateFormat dateZoneFormat = new SimpleDateFormat("yyyy.MM.dd HH:mm:ss Z", Locale.US);

    private String prepareCommentString(Date date, long counts, double cps, double time, long gpsTime, double latitude, double longitude) {
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

    public Spectrum setSuffix(@NonNull String suffix) {
        Suffix = suffix;
        return this;
    }

    @NonNull
    public String getSuffix() {
        return Suffix;
    }

    public Spectrum initSpectrumData() {
        DataArray = new long[Constants.NUM_HIST_POINTS];
        SpectrumCalibration = Calibration.defaultCalibration();
        SpectrumTime = 0;
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

package org.fe57.atomspectra.data;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Arrays;

/**
 * This class is used to save spectrum slices into a whole spectrum data.
 * All data must be filled at a construction time.
 * Also it doesn't contain a calibration information.
 */
public class SpectrumRecord implements Parcelable {
    private final long[] Data;
    /**
     * Value shows a time stamp at the end of the spectrum to be received.
     */
    private final long Timestamp;
    /**
     * Duration of spectrum capturing.
     */
    private final long Interval;

    /**
     * Latitude of this spectrum.
     */
    private final double Latitude;

    /**
     * Longitude of this spectrum.
     */
    private final double Longitude;

    /**
     * This constructor creates new spectrum record without GPS coordinates
     * @param data - spectrum data
     * @param timestamp - timestamp of spectrum to be saved
     * @param interval - interval of time collection of the spectrum
     */
    public SpectrumRecord(@NonNull long[] data, long timestamp, long interval, double latitude, double longitude) {
        Data = Arrays.copyOf(data, data.length);
        Timestamp = timestamp;
        Interval = interval;
        Latitude = latitude;
        Longitude = longitude;
    }

    protected SpectrumRecord(Parcel in) {
        Data = in.createLongArray();
        Timestamp = in.readLong();
        Interval = in.readLong();
        Latitude = in.readDouble();
        Longitude = in.readDouble();
    }

    public static final Creator<SpectrumRecord> CREATOR = new Creator<SpectrumRecord>() {
        @Override
        public SpectrumRecord createFromParcel(Parcel in) {
            return new SpectrumRecord(in);
        }

        @Override
        public SpectrumRecord[] newArray(int size) {
            return new SpectrumRecord[size];
        }
    };

    public long[] getData() {
        return Data;
    }

    public long getTimestamp() {
        return Timestamp;
    }

    public long getInterval() {
        return Interval;
    }

    public double getLatitude() {
        return Latitude;
    }

    public double getLongitude() {
        return Longitude;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int i) {
        parcel.writeLongArray(Data);
        parcel.writeLong(Timestamp);
        parcel.writeLong(Interval);
        parcel.writeDouble(Latitude);
        parcel.writeDouble(Longitude);
    }

    /**
     * This function creates a new spectrum as a sum of itself and another spectrum.
     * If data lengths differs, it returns null.
     * @param record - another record to be added to this one
     * @return newly created record
     */
    public @Nullable SpectrumRecord plus(@NonNull SpectrumRecord record) {
        //All records must have identical number of channels to add or return Null as an error
        if (this.Data.length != record.Data.length)
            return null;
        long[] newData = new long[Data.length];
        for (int i = 0; i < newData.length; i++) {
            newData[i] = Data[i] + record.Data[i];
        }
        return new SpectrumRecord(newData, StrictMath.max(this.Timestamp, record.Timestamp), this.Interval + record.Interval, this.Latitude, this.Longitude);
    }

    /**
     * This function creates a new spectrum as a subtraction another spectrum from this one
     * @param record removing spectrum record
     * @return newly created record
     */
    public @Nullable SpectrumRecord minus(@NonNull SpectrumRecord record) {
        //All records must have identical number of channels to add or return Null as an error
        if (this.Data.length != record.Data.length)
            return null;
        long newInterval = this.Interval - record.Interval;
        long[] newData = new long[Data.length];
        if (newInterval > 0) {
            for (int i = 0; i < newData.length; i++) {
                newData[i] = Data[i] - record.Data[i];
                if (newData[i] < 0)
                    newData[i] = 0;
            }
        }
        return new SpectrumRecord(newData, StrictMath.max(this.Timestamp, record.Timestamp), this.Interval - record.Interval, this.Latitude, this.Longitude);
    }

    /**
     * This function creates a new spectrum as a sum of itself and another spectrum.
     * If data lengths differs, it sets the length of output record to maximum of the two lengths.
     * Also it sums intervals and sets time stamp to the latest one
     * @param record - another record to be added to this one
     * @return newly created record
     */
    public @NonNull SpectrumRecord addRelaxed(@NonNull SpectrumRecord record) {
        //All records must have identical number of channels to add
        long[] newData = new long[StrictMath.max(Data.length, record.Data.length)];
        int minSize = StrictMath.min(Data.length, record.Data.length);
        for (int i = 0; i < minSize; i++) {
            newData[i] = Data[i] + record.Data[i];
        }
        if (minSize < newData.length) {
            if (Data.length == newData.length) {
                System.arraycopy(Data, minSize, newData, minSize, Data.length - minSize);
            } else {
                System.arraycopy(record.Data, minSize, newData, minSize, record.Data.length - minSize);
            }
        }
        return new SpectrumRecord(newData, StrictMath.max(this.Timestamp, record.Timestamp), this.Interval + record.Interval, this.Latitude, this.Longitude);
    }

}

package org.fe57.atomspectra.data;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * This class contains all sub-spectra data in one place
 */
public class SpectrumData extends ArrayList<SpectrumRecord> {
    private long[] Data = new long[1];
    private long maxDelta = 1000; //ms
    private long Interval = 0;
    private final Integer syncData = 1;

    protected SpectrumData() {
        super();
    }

    public SpectrumData(int numChannels) {
        super();
        Data = new long[Math.max(numChannels, 1)];
    }

    public SpectrumData(int numChannels, long maxDelta) {
        super();
        Data = new long[Math.max(numChannels, 1)];
        this.maxDelta = maxDelta;
    }

    public long[] getSpectrum() {
        return Data;
    }

    public long getInterval() {
        return Interval;
    }

    @Override
    public SpectrumRecord set(int index, SpectrumRecord element) {
        removeSpectrum(index);
        SpectrumRecord record = super.set(index, element);
        addSpectrum(index);
        return record;
    }

    @Override
    public boolean add(SpectrumRecord spectrumRecord) {
        boolean result = super.add(spectrumRecord);
        addSpectrum(size() - 1);
        return result;
    }

    @Override
    public void clear() {
        super.clear();
        Interval = 0;
        Arrays.fill(Data, 0);
    }

    @Override
    public void add(int index, SpectrumRecord element) {
        super.add(index, element);
        addSpectrum(index);
    }

    public boolean append(SpectrumRecord record) {
        synchronized (syncData) {
            if (isEmpty() || ((get(size() - 1).getTimestamp() - get(size() - 1).getInterval() + maxDelta) > record.getTimestamp())) {
                SpectrumRecord newRecord = get(size() - 1).plus(record);
                if (newRecord == null) {
                    return false;
                }
                set(size() - 1, newRecord);
                return true;
            } else
                return add(record);
        }
    }

    public void compress(long newDelta) {
        if (isEmpty())
            return;
        synchronized (syncData) {
            int i = 0;
            while (i < (size() - 1)) {
                if (get(i).getTimestamp() - get(i).getInterval() + newDelta > get(i + 1).getTimestamp()) {
                    set(i, get(i).plus(get(i + 1)));
                    remove(i + 1);
                }
                else
                    i++;
            }
        }
    }

    public boolean isEmpty() {
        return Data.length < 10 || super.isEmpty();
    }

    /**
     * This function adds a given spectrum to the intermediate array.
     * @param index index of spectrum to be added to the whole spectrum
     */
    protected void addSpectrum (int index)  throws IndexOutOfBoundsException {
        if (index < 0 || index >= size())
            throw new IndexOutOfBoundsException();
        long[] record = get(index).getData();
        for (int i = 0; i < Data.length; i++) {
            Data[i] += record[i];
        }
        Interval += get(index).getInterval();
    }

    /**
     * This function removes a given spectrum from the intermediate array.
     * @param index index of spectrum to be removed from the whole spectrum
     */
    protected void removeSpectrum (int index) throws IndexOutOfBoundsException {
        if (index < 0 || index >= size())
            throw new IndexOutOfBoundsException();
        long[] record = get(index).getData();
        for (int i = 0; i < Data.length; i++) {
            Data[i] -= record[i];
        }
        Interval -= get(index).getInterval();
    }

    @Override
    protected void removeRange(int fromIndex, int toIndex) {
        if  (fromIndex < 0 || toIndex > size() || toIndex < fromIndex)
            throw new IndexOutOfBoundsException();
        for (int i = fromIndex; i < toIndex; i++) {
            removeSpectrum(i);
        }
        super.removeRange(fromIndex, toIndex);
    }
}

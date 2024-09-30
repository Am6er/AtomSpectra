package org.fe57.atomspectra.data;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collection;

public class SpectrumList extends ArrayList<Spectrum> {
    private ArrayList<Boolean> visibleSpectrum = new ArrayList<>();
    @Override
    public boolean add(Spectrum spectrum) {
        visibleSpectrum.add(false);
        return super.add(spectrum);
    }

    @Override
    public void add(int index, Spectrum element) {
        visibleSpectrum.add(index, false);
        super.add(index, element);
    }

    @Override
    public boolean addAll(@NonNull Collection<? extends Spectrum> c) {
        for (int i = 0; i < c.size(); i++)
            visibleSpectrum.add(false);
        return super.addAll(c);
    }

    @Override
    public boolean addAll(int index, @NonNull Collection<? extends Spectrum> c) {
        for (int i = 0; i < c.size(); i++)
            visibleSpectrum.add(index, false);
        return super.addAll(index, c);
    }

    public boolean isVisibleSpectrum (int index) {
        return visibleSpectrum.get(index);
    }

    public void setVisibleSpectrum (int index, boolean set) {
        visibleSpectrum.set(index, set);
    }

    @Override
    public Spectrum remove(int index) {
        visibleSpectrum.remove(index);
        return super.remove(index);
    }

    @Override
    public boolean removeAll(@NonNull Collection<?> c) {
        return super.removeAll(c);
    }

    @Override
    protected void removeRange(int fromIndex, int toIndex) {
        if (toIndex > fromIndex) {
            visibleSpectrum.subList(fromIndex, toIndex).clear();
        }
        super.removeRange(fromIndex, toIndex);
    }

    @Override
    public void clear() {
        visibleSpectrum.clear();
        super.clear();
    }

    @NonNull
    @Override
    public Object clone() {
        SpectrumList list = (SpectrumList) super.clone();
        list.visibleSpectrum = (ArrayList<Boolean>) visibleSpectrum.clone();
        return list;
    }

}

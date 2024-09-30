package org.fe57.atomspectra.data;

import androidx.annotation.NonNull;

import java.util.ArrayList;

/**
 * Created by S. Epiphanov.
 */
public class IsotopeList {
    private final ArrayList<Isotope> isotopeArrayList = new ArrayList<>();

    public IsotopeList() {
        //nothing to do
    }

    //add in sorted order, add energy if already exists
    public IsotopeList add(@NonNull Isotope isotope) {
        for (Isotope i: isotopeArrayList) {
            if (isotope.equals(i)) {
                i.addEnergy(isotope);
                return this;
            }
        }
        int pos = isotopeArrayList.size();
        for (int i = 0; i < isotopeArrayList.size(); i++) {
            if (isotope.compareTo(isotopeArrayList.get(i)) < 0) {
                pos = i;
                break;
            }
        }
        isotopeArrayList.add(pos, isotope);
        return this;
    }

    public ArrayList<Isotope> getNamesList() {
        ArrayList<Isotope> newList = new ArrayList<>();
        for (Isotope i: isotopeArrayList) {
            if (i.getLinesNumber() > 0)
                newList.add(new Isotope(i.getName(), i.getHalfLife()));
        }
        return newList;
    }

    public ArrayList<Isotope> getLinesList() {
        ArrayList<Isotope> newList = new ArrayList<>();
        int pos;
        for (Isotope i: isotopeArrayList) {
            for (int j = 0; j < i.getLinesNumber(); j++) {
                pos = newList.size();
                for (int k = 0; k < newList.size(); k++) {
                    if (newList.get(k).getEnergy(0) > i.getEnergy(j)) {
                        pos = k;
                        break;
                    }
                }
                newList.add(pos, new Isotope(i.getName(), i.getHalfLife()).addEnergy(i.getEnergy(j), i.getIntensity(j)).setMaxIntensity(i.getMaxIntensity()));
            }
        }
        return newList;
    }

    public Isotope get(int i) {
        if (i >= 0 && i < isotopeArrayList.size())
            return new Isotope(isotopeArrayList.get(i));
        return new Isotope(Isotope.NonElement, Isotope.Stable);
    }

    public Isotope getChain(int i) {
        return new Isotope(isotopeArrayList.get(i).getName(), Isotope.ChainSign);
    }

    public Isotope find(@NonNull String name) {
        for(Isotope isotope: isotopeArrayList) {
            if(isotope.getName().equals(name)) {
                return new Isotope(isotope);
            }
        }
        return null;
    }
}

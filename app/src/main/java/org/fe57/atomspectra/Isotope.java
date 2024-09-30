package org.fe57.atomspectra;

import android.content.res.Resources;
import android.graphics.RectF;

import androidx.annotation.NonNull;
import androidx.core.util.Pair;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Locale;

public class Isotope implements Comparable<Isotope>{
    public static final String NonElement = "ND-0";
    public static final double Stable = -0.5;
    public static final double ChainSign = -1.0;
    public static final double seconds = 1.0;
    public static final double minutes = 60;
    public static final double hours = minutes * 60;
    public static final double days = hours * 24;
    public static final double years = days * 365.25;
    private static final double MIN_INTENSITY = 1.0;
    private static final double MAX_INTENSITY = 100.0;
    private static final String[] Names = {
            "H",  "He",
            "Li", "Be", "B",  "C",  "N",  "O",  "F",  "Ne",
            "Na", "Mg", "Al", "Si", "P",  "S",  "Cl", "Ar",
            "K",  "Ca", "Sc", "Ti", "V",  "Cr", "Mn", "Fe", "Co", "Ni", "Cu", "Zn", "Ga", "Ge", "As", "Se", "Br", "Kr",
            "Rb", "Sr", "Y",  "Zr", "Nb", "Mo", "Tc", "Ru", "Rh", "Pd", "Ag", "Cd", "In", "Sn", "Sb", "Te", "I",  "Xe",
            "Cs", "Ba", "La", "Ce", "Pr", "Nd", "Pm", "Sm", "Eu", "Gd", "Tb", "Dy", "Ho", "Er", "Tm", "Yb", "Lu", "Hf", "Ta", "W",  "Re", "Os", "Ir", "Pt", "Au", "Hg", "Tl", "Pb", "Bi", "Po", "At", "Rn",
            "Fr", "Ra", "Ac", "Th", "Pa", "U",  "Np", "Pu", "Am", "Cm", "Bk", "Cf", "Es", "Fm", "Me", "No", "Lr", "Rf", "Db", "Sg", "Bh", "Hs", "Mt", "Ds", "Rg", "Cn", "Nh", "Fl", "Mc", "Lv", "Ts", "Og"
    };
    private static final int ColorIsotope = 0xFFB9DDDD;  //0xFFC8FDC4
    private static final int[] ColorImportances = new int[]{
            0xFF79B174,  //0xFFB9DDDD,
            0xFFC8FDC4,  //0xFFB9DDDD,
            0xFF9F9116,
            0xFFDFCB1E,
            0xFFFF5656,
            0xFFFF5656      //for 100% strict
    };
    private static final int ColorChain = 0xFFB9DDDD; //0xFFC8FDC4;
    private static final int ColorFound = 0xFFFFA3C5;

    private final String Name;     //Name of isotope
    private final int Weight;      //Weight of isotope
    private String Addon;    //Addon after weight of isotope
    private int Index;       //Index in Names array
    private final double HalfLife;    //half-life of the isotope
    private double maxIntensity;
    public LinkedList<String> Chain;               //Used to show radioactive chains
    public LinkedList<Double> EnergyChain;         //List of energies os isotope
    public LinkedList<Double> IntensityChain;      //List of photon count per 100 decays
    private final LinkedList<Pair<String, Double>> ChildrenList;
    private RectF Coord = new RectF(0, 0, 0, 0);

    //copy constructor
    public Isotope (Isotope i) {
        Name = i.Name;
        Weight = i.Weight;
        Addon = i.Addon;
        Index = i.Index;
        Chain = new LinkedList<>();
        for (String s: i.Chain)
            Chain.addLast(s);
        Coord = new RectF(i.Coord);
        HalfLife = i.HalfLife;
        EnergyChain = new LinkedList<>();
        IntensityChain = new LinkedList<>();
        maxIntensity = i.maxIntensity;
        ChildrenList = new LinkedList<>();
        for (int k = 0; k < i.getLinesNumber(); k++) {
            addEnergy(i.getEnergy(k), i.getIntensity(k));
        }
        ChildrenList.addAll(i.ChildrenList);
    }

//Example: Isotope("Ra-226", 223.10, 100.0)
public Isotope(@NonNull String name, double halfLife){
    int idx = name.indexOf("-");
    if ((idx > 0) && idx < (name.length() - 1)) {
        Name = name.substring(0, name.indexOf("-"));
        String s = "";
        Addon = "";
        for (int i  = idx + 1; i < name.length(); i++)
            if ((name.substring(i, i + 1).compareTo("0")) >=0 && (name.substring(i, i + 1).compareTo("9") <=0))
                s += name.substring(i, i + 1);
            else {
                Addon = name.substring(i);
                break;
            }
        if (s.length() > 0)
            Weight = Integer.parseInt(s);
        else
            Weight = 0;
    } else {
        Name = name;
        Weight = 0;
        Addon = "";
    }
    HalfLife = halfLife;
    Index = -1;
    for (int i = 0; i < Names.length; i++) {
        if (Names[i].equals(Name))
            Index = i;
    }
    Chain = new LinkedList<>();
    if (halfLife == ChainSign)
        Chain.add(name);
    EnergyChain = new LinkedList<>();
    IntensityChain = new LinkedList<>();
    maxIntensity = MIN_INTENSITY;
    ChildrenList = new LinkedList<>();
}

    //Example: Isotope("Ra", 226, "", 223.10, 100.0)
    public Isotope(@NonNull String name, int weight, @NonNull String addon, double halfLife){
        Name = name;
        Weight = weight;
        Addon = addon;
        Index = -1;
        HalfLife = halfLife;
        for (int i = 0; i < Names.length; i++) {
            if (Names[i].equals(Name))
                Index = i;
        }
        Chain = new LinkedList<>();
        if (halfLife == ChainSign)
            Chain.addLast(name);
        EnergyChain = new LinkedList<>();
        IntensityChain = new LinkedList<>();
        maxIntensity = MIN_INTENSITY;
        ChildrenList = new LinkedList<>();
    }

    public int getLinesNumber() {
        return EnergyChain.size();
    }

    double getHalfLife() {
        return HalfLife;
    }

    public double getEnergy(int line) {
        if (line < 0 || line >= EnergyChain.size())
            return -1;
        return EnergyChain.get(line);
    }

    public double getIntensity(int line) {
        if (line < 0 || line >= EnergyChain.size())
            return -1;
        return IntensityChain.get(line);
    }

    //returns in range [0.0 ... 1.0]
    public double getRelativeIntensity(int line) {
        if (line < 0 || line >= EnergyChain.size())
            return -1;
        return IntensityChain.get(line) / maxIntensity;
    }

    public double getMaxIntensity() {
        return maxIntensity;
    }

    public Isotope setMaxIntensity(double max) {
        maxIntensity = Constants.MinMax(max,MIN_INTENSITY,MAX_INTENSITY);
        for (int j = 0; j < IntensityChain.size(); j++) {
            maxIntensity = StrictMath.max(maxIntensity, IntensityChain.get(j));
        }
        return this;
    }

    public void addChild(@NonNull String name, double variability) {
        boolean add = true;
        for(Pair<String, Double> p: ChildrenList) {
            if (name.equals(p.first)) {
                add = false;
                break;
            }
        }
        if(add)
            ChildrenList.add(new Pair<>(name, variability));
    }

    Isotope getChain() {
        Isotope i = new Isotope(getName(), Isotope.ChainSign);
        for(Pair<String, Double> p: ChildrenList) {
            i.addToChain(p.first);
        }
        return i;
    }

    public ArrayList<Pair<Double, Double>> getLines() {
        ArrayList<Pair<Double, Double>> newList = new ArrayList<>();
        for (int i = 0; i < EnergyChain.size(); i++) {
            newList.add(new Pair<>(EnergyChain.get(i), IntensityChain.get(i)));
        }
        return  newList;
    }

    public String getName() {
        return String.format(Locale.US, "%s-%d%s", Name, Weight, Addon);
    }

    public String getFullName(Resources res) {
        double half;
        String halfDimension;
        if (HalfLife > years) {
            half = HalfLife / years;
            halfDimension = res.getString(R.string.period_year);
        } else if (HalfLife > days) {
            half = HalfLife / days;
            halfDimension = res.getString(R.string.period_day);
        } else if (HalfLife > hours) {
            half = HalfLife / hours;
            halfDimension = res.getString(R.string.period_hour);
        } else if (HalfLife > minutes) {
            half = HalfLife / minutes;
            halfDimension = res.getString(R.string.period_minute);
        } else {
            half = HalfLife / seconds;
            halfDimension = res.getString(R.string.period_second);
        }

        if (HalfLife > 0) {
            return res.getString(R.string.isotope_show_name_halflife, Name, Weight, Addon, half, halfDimension);
        } else if (HalfLife == Stable) {
            return res.getString(R.string.isotope_show_name_stable, Name, Weight, Addon);
        } else
            return res.getString(R.string.isotope_show_decay_chain, Name, Weight, Addon);
    }

    public String getHalfLifeName(Resources res) {
        double half;
        String halfDimension;
        if (HalfLife > years) {
            half = HalfLife / years;
            halfDimension = res.getString(R.string.period_year);
        } else if (HalfLife > days) {
            half = HalfLife / days;
            halfDimension = res.getString(R.string.period_day);
        } else if (HalfLife > hours) {
            half = HalfLife / hours;
            halfDimension = res.getString(R.string.period_hour);
        } else if (HalfLife > minutes) {
            half = HalfLife / minutes;
            halfDimension = res.getString(R.string.period_minute);
        } else {
            half = HalfLife / seconds;
            halfDimension = res.getString(R.string.period_second);
        }

        if (HalfLife > 0) {
            return String.format(Locale.getDefault(), "%.4g %s", half, halfDimension);
        } else {
            return "-";
        }
    }

    public int getColor() {
        if (!Chain.isEmpty())
            return ColorChain;
        if (maxIntensity < MIN_INTENSITY || ColorImportances.length < 2)
            return ColorIsotope;
        //use square to lower the function
        double temp = 1.0 * IntensityChain.getFirst() / maxIntensity;
        return ColorImportances[(int)(temp*temp*(ColorImportances.length - 1))];
    }

    public int getColor(int line) {
        if (!Chain.isEmpty())
            return ColorChain;
        if (maxIntensity < MIN_INTENSITY || line < 0 || line >= IntensityChain.size() || ColorImportances.length < 2)
            return ColorIsotope;
        double temp = 1.0 * IntensityChain.get(line) / maxIntensity;
        return ColorImportances[(int)(temp*temp*(ColorImportances.length - 1))];
    }

    public static int getColorForIsotope() {
        return ColorIsotope;
    }

    public static int getColorForChain() {
        return ColorChain;
    }

    public static int getColorForFound() {
        return ColorFound;
    }

    public void clearChain() {
        Chain.clear();
    }

    public Isotope addToChain(@NonNull String s) {
        if(!Chain.contains(s))
            Chain.addLast(s);
        return this;
    }

    public boolean isInChain(@NonNull String s) {
        if (Chain.isEmpty())
            return false;
        for (String isotope: Chain)
            if (s.equals(isotope))
                return true;
        return false;
    }

    //remove all energies except the first one
    public void clearLines() {
        EnergyChain.clear();
        IntensityChain.clear();
        maxIntensity = MIN_INTENSITY;
    }

    //add energy line with
    public Isotope addEnergy(double energy) {
        return addEnergy(energy, 100.0);
    }

    //add energy from another isotope
    public Isotope addEnergy(double energy, double intensity) {
        if (EnergyChain.contains(energy))
            return this;
        int pos = EnergyChain.size();
        for (int i = 0; i < EnergyChain.size(); i++) {
            if (EnergyChain.get(i) > energy) {
                pos = i;
                break;
            }
        }
        EnergyChain.add(pos, energy);
        IntensityChain.add(pos, intensity);
        maxIntensity = StrictMath.max(maxIntensity, Constants.MinMax(intensity,MIN_INTENSITY,MAX_INTENSITY));
        return this;
    }

    public Isotope addEnergy(@NonNull Isotope i) {
        for (int j = 0; j < i.EnergyChain.size(); j++) {
            if (!EnergyChain.contains(i.EnergyChain.get(j))) {
                addEnergy(i.EnergyChain.get(j), i.IntensityChain.get(j));
                maxIntensity = StrictMath.max(maxIntensity, i.IntensityChain.get(j));
            }
        }
        return this;
    }

    @NonNull
    @Override
    public String toString() {
        double half;
        String halfDimension;
        if (HalfLife > years) {
            half = HalfLife / years;
            halfDimension = "y";
        } else if (HalfLife > days) {
            half = HalfLife / days;
            halfDimension = "d";
        } else if (HalfLife > hours) {
            half = HalfLife / hours;
            halfDimension = "h";
        } else if (HalfLife > minutes) {
            half = HalfLife / minutes;
            halfDimension = "m";
        } else {
            half = HalfLife / seconds;
            halfDimension = "s";
        }

        if (HalfLife > 0) {
            if(EnergyChain.size() < 1) {
                return String.format(Locale.US, "%s-%d%s (%.4g %s)", Name, Weight, Addon, half, halfDimension);
            }
            StringBuilder s = new StringBuilder(String.format(Locale.US, "%s-%d%s (%.4g %s) (%.2f", Name, Weight, Addon, half, halfDimension, EnergyChain.getFirst()));
            for (int i = 1; i < EnergyChain.size(); i++)
                s.append(String.format(Locale.US, ", %.2f", EnergyChain.get(i)));
            return String.format(Locale.US, "%s  keV)", s.toString());
        } else if (HalfLife == Stable) {
            return String.format(Locale.US, "%s-%d%s (stable)", Name, Weight, Addon);
        } else
            return String.format(Locale.US, "%s-%d%s decay chain", Name, Weight, Addon);
    }

    @NonNull
    public String toString(Resources res) {
        double half;
        String halfDimension;
        if (HalfLife > years) {
            half = HalfLife / years;
            halfDimension = res.getString(R.string.period_year);
        } else if (HalfLife > days) {
            half = HalfLife / days;
            halfDimension = res.getString(R.string.period_day);
        } else if (HalfLife > hours) {
            half = HalfLife / hours;
            halfDimension = res.getString(R.string.period_hour);
        } else if (HalfLife > minutes) {
            half = HalfLife / minutes;
            halfDimension = res.getString(R.string.period_minute);
        } else {
            half = HalfLife / seconds;
            halfDimension = res.getString(R.string.period_second);
        }

        if (HalfLife > 0) {
            if(EnergyChain.size() < 1) {
                return res.getString(R.string.isotope_show_name_halflife, Name, Weight, Addon, half, halfDimension);
//                return String.format(Locale.US, "%s-%d%s", Name, Weight, Addon);
            }
            StringBuilder s = new StringBuilder(res.getString(R.string.isotope_show_line, Name, Weight, Addon, half, halfDimension, EnergyChain.getFirst()));
            for (int i = 1; i < EnergyChain.size(); i++)
                s.append(res.getString(R.string.isotope_show_next_line, EnergyChain.get(i)));
            return res.getString(R.string.isotope_show_last_line, s.toString());
        } else if (HalfLife == Stable) {
            return res.getString(R.string.isotope_show_name_stable, Name, Weight, Addon);
        } else
            return res.getString(R.string.isotope_show_decay_chain, Name, Weight, Addon);
    }

    //Returns +1 if this is to the right of i
    //Returns 0 if they are equal
    //Returns -1 if this is to the left of i
    @Override
    public int compareTo(Isotope i) {
        if (Index < i.Index)
            return -1;
        if (Index > i.Index)
            return 1;
        int res = Integer.compare(Weight, i.Weight);
        if (res != 0)
            return res;
        return Addon.compareTo(i.Addon);
    }

    public boolean equals(@NonNull Isotope i) {
        return compareTo(i) == 0;
    }

    public void setCoord(RectF rect) {
        if (rect == null)
            Coord.set(0, 0, 0, 0);
        else
            Coord.set(rect);
    }

    public void setCoord(float x_left, float y_top, float x_right, float y_bottom) {
        if (x_left == x_right || y_top == y_bottom)
            Coord.set(0, 0, 0, 0);
        else
            Coord = new RectF(x_left, y_top, x_right, y_bottom);
    }

    //compare and show how other isotope is close to this one
    public double isCompatible(Isotope other) {
        if (EnergyChain.size() == 0 && other.EnergyChain.size() != 0)
            return 0.0;
        if (EnergyChain.size() != 0 && other.EnergyChain.size() == 0)
            return 0.0;

        double diff = 0.0;
        double weight = 1.0 / StrictMath.max(EnergyChain.size(), other.EnergyChain.size());
//        boolean[] touched = new boolean[EnergyChain.size()];
//
//        for (int i = 0; i < EnergyChain.size(); i++) {
//            touched [i] = false;
//        }

        diff = diff * weight;

        return diff;
    }

    public RectF getCoord() {
        return new RectF(Coord);
    }

    //convert halflife from seconds to seconds
    public static double Seconds(double s) {
        return s * seconds;
    }

    //convert halflife from minutes to seconds
    public static double Minutes(double m) {
        return m * minutes;
    }

    //convert halflife from hours to seconds
    public static double Hours(double h) {
        return h * hours;
    }

    //convert halflife from days to seconds
    public static double Days(double d) {
        return d * days;
    }

    //convert halflife from years to seconds
    public static double Years(double y) {
        return y * years;
    }
}

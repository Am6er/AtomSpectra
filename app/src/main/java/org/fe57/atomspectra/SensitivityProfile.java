package org.fe57.atomspectra;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * A dose-rate sensitivity profile.
 * <p>
 * Sensitivities are stored as absolute {@code pSv/count}:
 * <ul>
 *     <li>{@link #compPsvPerCount} - per energy-bin curve used for the compensated dose rate.</li>
 *     <li>{@link #nonCompPsvPerCount} - single scalar used for the non-compensated dose rate.</li>
 * </ul>
 * A profile also carries independent fast/medium/slow search count targets for the compensated and
 * non-compensated dose rates (the active fast/medium/slow mode is a shared, device-wide selector).
 * <p>
 * {@link #binEdges} are the ascending upper bounds (keV) of contiguous bands starting at 0; the top
 * band covers everything above the last edge. {@code binEdges} and {@code compPsvPerCount} always
 * have the same length.
 * <p>
 * Built-in profiles are {@link #readOnly}. The architecture allows multiple custom profiles; only one
 * is currently surfaced in the UI.
 */
public class SensitivityProfile {
    // pSv/count -> uSv/h: dr = sum(counts * pSv) / time_s * PSV_PER_COUNT_TO_USV_H (3600 s/h / 1e6 pSv per uSv)
    public static final double PSV_PER_COUNT_TO_USV_H = 3.6e-3;
    // Fixed energy-bin array capacity for the service scratch buffers; profiles may use fewer bands.
    public static final int MAX_BINS = 30;

    public static final String ID_NANO3 = "nano-3";
    public static final String ID_NANO8 = "nano-8";
    public static final String ID_NANO15 = "nano-15";
    public static final String ID_CUSTOM = "custom";
    public static final String ID_DEFAULT = ID_NANO8;

    // Built-in compensated curves: ascending upper bin edges (keV) with matching pSv/count values.
    // Each detector keeps independent arrays.
    private static final float[] NANO3_BIN_EDGES = {
            40f, 70f, 100f, 150f, 200f, 250f, 300f, 350f, 400f, 475f,
            550f, 675f, 950f, 1100f, 1300f, 1550f, 1800f, 2100f, 2500f, 3000f};
    private static final double[] NANO3_COMP_PSV = {
            0.2425, 0.1714, 0.1907, 0.2718, 0.4904, 0.9122, 1.5839, 2.5588, 3.8370, 5.7151,
            8.3845, 11.1065, 13.6272, 16.5697, 21.0799, 26.4308, 31.2061, 37.4386, 47.4881, 54.6509};

    private static final float[] NANO8_BIN_EDGES = {
            30f, 50f, 100f, 150f, 200f, 250f, 300f, 350f, 425f, 500f,
            600f, 775f, 1050f, 1225f, 1400f, 1650f, 2075f, 2350f, 2650f, 3000f};
    private static final double[] NANO8_COMP_PSV = {
            0.1283, 0.0934, 0.0864, 0.1289, 0.2114, 0.3676, 0.6056, 0.936, 1.5024, 2.3051,
            3.3009, 4.5283, 5.8401, 7.1727, 9.006, 11.206, 13.3376, 15.8468, 19.2487, 23.0672};

    private static final float[] NANO15_BIN_EDGES = {
            30f, 50f, 100f, 150f, 200f, 250f, 300f, 350f, 425f, 500f,
            600f, 725f, 950f, 1150f, 1400f, 1675f, 1925f, 2225f, 2550f, 3000f};
    private static final double[] NANO15_COMP_PSV = {
            0.0802, 0.0581, 0.0543, 0.0797, 0.1257, 0.2068, 0.3249, 0.4983, 0.7728, 1.1601,
            1.6711, 2.2826, 2.8847, 3.6245, 4.48, 5.5077, 6.8053, 8.2966, 10.0712, 12.0495};

    // Non-compensated pSv/count scalar, per detector.
    private static final double NANO3_NONCOMP_PSV = 1.634;
    private static final double NANO8_NONCOMP_PSV = 0.725;
    private static final double NANO15_NONCOMP_PSV = 0.447;

    public static final SensitivityProfile NANO3 = builtIn(ID_NANO3, NANO3_BIN_EDGES, NANO3_COMP_PSV, NANO3_NONCOMP_PSV,
            256, 625, 1600, 36, 100, 256);
    public static final SensitivityProfile NANO8 = builtIn(ID_NANO8, NANO8_BIN_EDGES, NANO8_COMP_PSV, NANO8_NONCOMP_PSV,
            324, 900, 2500, 49, 121, 324);
    public static final SensitivityProfile NANO15 = builtIn(ID_NANO15, NANO15_BIN_EDGES, NANO15_COMP_PSV, NANO15_NONCOMP_PSV,
            625, 1600, 3600, 64, 256, 625);
    public static final SensitivityProfile[] BUILTINS = {NANO3, NANO8, NANO15};

    private static SensitivityProfile builtIn(String id, float[] binEdges, double[] compPsvPerCount, double nonCompPsvPerCount,
                                              int compFast, int compMedium, int compSlow,
                                              int nonCompFast, int nonCompMedium, int nonCompSlow) {
        return new SensitivityProfile(id, true,
                binEdges, compPsvPerCount, nonCompPsvPerCount,
                compFast, compMedium, compSlow,
                nonCompFast, nonCompMedium, nonCompSlow);
    }

    public static boolean isBuiltIn(String id) {
        for (SensitivityProfile p : BUILTINS) {
            if (p.name.equals(id)) {
                return true;
            }
        }
        return false;
    }

    /** Returns a deep copy of the built-in with the given id, or a copy of the default when unknown. */
    public static SensitivityProfile builtInById(String id) {
        for (SensitivityProfile p : BUILTINS) {
            if (p.name.equals(id)) {
                return p.copy();
            }
        }
        return NANO8.copy();
    }

    public String name;
    public boolean readOnly;

    public float[] binEdges;
    public double[] compPsvPerCount;
    public double nonCompPsvPerCount;

    public int compFast;
    public int compMedium;
    public int compSlow;

    public int nonCompFast;
    public int nonCompMedium;
    public int nonCompSlow;

    public SensitivityProfile() {
        this.name = "";
        this.readOnly = false;
        this.binEdges = new float[0];
        this.compPsvPerCount = new double[0];
        this.nonCompPsvPerCount = 0;
        this.compFast = Constants.SEARCH_FAST_DEFAULT;
        this.compMedium = Constants.SEARCH_MEDIUM_DEFAULT;
        this.compSlow = Constants.SEARCH_SLOW_DEFAULT;
        this.nonCompFast = Constants.SEARCH_FAST_DEFAULT;
        this.nonCompMedium = Constants.SEARCH_MEDIUM_DEFAULT;
        this.nonCompSlow = Constants.SEARCH_SLOW_DEFAULT;
    }

    public SensitivityProfile(String name, boolean readOnly,
                             float[] binEdges, double[] compPsvPerCount, double nonCompPsvPerCount,
                             int compFast, int compMedium, int compSlow,
                             int nonCompFast, int nonCompMedium, int nonCompSlow) {
        this.name = name;
        this.readOnly = readOnly;
        this.binEdges = binEdges.clone();
        this.compPsvPerCount = compPsvPerCount.clone();
        this.nonCompPsvPerCount = nonCompPsvPerCount;
        this.compFast = compFast;
        this.compMedium = compMedium;
        this.compSlow = compSlow;
        this.nonCompFast = nonCompFast;
        this.nonCompMedium = nonCompMedium;
        this.nonCompSlow = nonCompSlow;
    }

    /** Deep copy preserving all fields, including {@link #readOnly}. */
    public SensitivityProfile copy() {
        return new SensitivityProfile(name, readOnly,
                binEdges, compPsvPerCount, nonCompPsvPerCount,
                compFast, compMedium, compSlow,
                nonCompFast, nonCompMedium, nonCompSlow);
    }

    /** Deep, editable copy. The copy is never read-only regardless of the source. */
    public SensitivityProfile editableCopy(String newName) {
        return new SensitivityProfile(newName, false,
                binEdges, compPsvPerCount, nonCompPsvPerCount,
                compFast, compMedium, compSlow,
                nonCompFast, nonCompMedium, nonCompSlow);
    }

    /** Compensated curve as an ordered {@code edge -> pSv/count} map (for the table editor). */
    public TreeMap<Float, Double> compCurveAsMap() {
        TreeMap<Float, Double> map = new TreeMap<>();
        for (int i = 0; i < binEdges.length; i++) {
            map.put(binEdges[i], compPsvPerCount[i]);
        }
        return map;
    }

    /** Replace the compensated curve from an ordered {@code edge -> pSv/count} map. */
    public void setCompCurveFromMap(@NonNull TreeMap<Float, Double> map) {
        List<Float> edges = new ArrayList<>(map.keySet());
        binEdges = new float[edges.size()];
        compPsvPerCount = new double[edges.size()];
        for (int i = 0; i < edges.size(); i++) {
            binEdges[i] = edges.get(i);
            compPsvPerCount[i] = map.get(edges.get(i));
        }
    }

    public int searchTargetComp(int fsmMode) {
        switch (fsmMode) {
            case 1: return compMedium;
            case 2: return compSlow;
            default: return compFast;
        }
    }

    public int searchTargetNonComp(int fsmMode) {
        switch (fsmMode) {
            case 1: return nonCompMedium;
            case 2: return nonCompSlow;
            default: return nonCompFast;
        }
    }
}

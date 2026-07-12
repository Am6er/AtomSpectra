package org.fe57.atomspectra;

import androidx.annotation.NonNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

/**
 * Reader/writer for the sensitivity-profile file format (see the plan, §9).
 * <p>
 * One profile per file. Human-readable, line-based {@code key: value}:
 * <pre>
 * ATOMSPECTRA SENSITIVITY PROFILE
 * version: 1
 * name: My CsI custom
 * sensitivity_non_compensated: 1.852
 * non_compensated_fast: 100
 * non_compensated_medium: 500
 * non_compensated_slow: 2000
 * compensated_fast: 100
 * compensated_medium: 500
 * compensated_slow: 2000
 * bin: 100 1.151
 * bin: 200 1.341
 * ...
 * </pre>
 * The reader validates strictly and imports all-or-nothing: any violation throws
 * {@link FormatException} and nothing is loaded. Numbers always use {@code '.'} as the decimal
 * separator ({@link Locale#US}), regardless of the app locale.
 */
public final class SensitivityProfileFile {

    public static final String SIGNATURE = "ATOMSPECTRA SENSITIVITY PROFILE";
    public static final int VERSION = 1;

    private static final String KEY_VERSION = "version";
    private static final String KEY_NAME = "name";
    private static final String KEY_NONCOMP = "sensitivity_non_compensated";
    private static final String KEY_NONCOMP_FAST = "non_compensated_fast";
    private static final String KEY_NONCOMP_MEDIUM = "non_compensated_medium";
    private static final String KEY_NONCOMP_SLOW = "non_compensated_slow";
    private static final String KEY_COMP_FAST = "compensated_fast";
    private static final String KEY_COMP_MEDIUM = "compensated_medium";
    private static final String KEY_COMP_SLOW = "compensated_slow";
    private static final String KEY_BIN = "bin";

    private static final String DEFAULT_NAME = "Custom";

    private SensitivityProfileFile() {
    }

    /** Thrown when a profile file is missing its signature, has a bad version, or fails validation. */
    public static class FormatException extends IOException {
        public FormatException(String message) {
            super(message);
        }
    }

    /** Writes {@code profile} in the v1 format. Bins are emitted sorted ascending by edge. */
    public static void write(@NonNull Writer writer, @NonNull SensitivityProfile profile) throws IOException {
        writer.write(SIGNATURE + "\n");
        writer.write(KEY_VERSION + ": " + VERSION + "\n");
        writer.write(KEY_NAME + ": " + profile.name + "\n");
        writer.write(KEY_NONCOMP + ": " + doubleStr(profile.nonCompPsvPerCount) + "\n");
        writer.write(KEY_NONCOMP_FAST + ": " + profile.nonCompFast + "\n");
        writer.write(KEY_NONCOMP_MEDIUM + ": " + profile.nonCompMedium + "\n");
        writer.write(KEY_NONCOMP_SLOW + ": " + profile.nonCompSlow + "\n");
        writer.write(KEY_COMP_FAST + ": " + profile.compFast + "\n");
        writer.write(KEY_COMP_MEDIUM + ": " + profile.compMedium + "\n");
        writer.write(KEY_COMP_SLOW + ": " + profile.compSlow + "\n");

        TreeMap<Float, Double> curve = profile.compCurveAsMap();
        for (java.util.Map.Entry<Float, Double> entry : curve.entrySet()) {
            writer.write(KEY_BIN + ": " + edgeStr(entry.getKey()) + " " + doubleStr(entry.getValue()) + "\n");
        }
    }

    /**
     * Parses a profile from {@code reader}, validating the whole file before returning. The returned
     * profile is always editable ({@code readOnly == false}).
     *
     * @throws FormatException on any structural or validation error (nothing is partially applied).
     */
    public static SensitivityProfile read(@NonNull BufferedReader reader) throws IOException {
        String first = reader.readLine();
        if (first == null || !SIGNATURE.equals(first.trim())) {
            throw new FormatException("Not a sensitivity profile file (missing signature)");
        }

        // Optional values; presence tracked to enforce required keys.
        String name = null;
        Double nonComp = null;
        Integer nonCompFast = null, nonCompMedium = null, nonCompSlow = null;
        Integer compFast = null, compMedium = null, compSlow = null;
        boolean versionSeen = false;
        TreeMap<Float, Double> curve = new TreeMap<>();

        String line;
        while ((line = reader.readLine()) != null) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon < 0) {
                throw new FormatException("Malformed line (expected \"key: value\"): " + trimmed);
            }
            String key = trimmed.substring(0, colon).trim().toLowerCase(Locale.US);
            String value = trimmed.substring(colon + 1).trim();

            switch (key) {
                case KEY_VERSION:
                    int version = parseInt(value, KEY_VERSION);
                    if (version != VERSION) {
                        throw new FormatException("Unsupported profile version: " + version);
                    }
                    versionSeen = true;
                    break;
                case KEY_NAME:
                    name = value.isEmpty() ? DEFAULT_NAME : value;
                    break;
                case KEY_NONCOMP:
                    nonComp = parseDouble(value, KEY_NONCOMP);
                    break;
                case KEY_NONCOMP_FAST:
                    nonCompFast = parseInt(value, KEY_NONCOMP_FAST);
                    break;
                case KEY_NONCOMP_MEDIUM:
                    nonCompMedium = parseInt(value, KEY_NONCOMP_MEDIUM);
                    break;
                case KEY_NONCOMP_SLOW:
                    nonCompSlow = parseInt(value, KEY_NONCOMP_SLOW);
                    break;
                case KEY_COMP_FAST:
                    compFast = parseInt(value, KEY_COMP_FAST);
                    break;
                case KEY_COMP_MEDIUM:
                    compMedium = parseInt(value, KEY_COMP_MEDIUM);
                    break;
                case KEY_COMP_SLOW:
                    compSlow = parseInt(value, KEY_COMP_SLOW);
                    break;
                case KEY_BIN: {
                    String[] parts = value.split("\\s+");
                    if (parts.length != 2) {
                        throw new FormatException("Malformed bin (expected \"bin: <edge> <pSv/count>\"): " + trimmed);
                    }
                    float edge = (float) parseDouble(parts[0], "bin edge");
                    double psv = parseDouble(parts[1], "bin value");
                    if (edge <= 0) {
                        throw new FormatException("Bin edge must be positive: " + edge);
                    }
                    if (psv < 0) {
                        throw new FormatException("Bin value must be non-negative: " + psv);
                    }
                    if (curve.containsKey(edge)) {
                        throw new FormatException("Duplicate bin edge: " + edge);
                    }
                    curve.put(edge, psv);
                    break;
                }
                default:
                    // Unknown keys are ignored (forward-compatible).
                    break;
            }
        }

        if (!versionSeen) {
            throw new FormatException("Missing version");
        }
        requirePresent(nonComp, KEY_NONCOMP);
        requirePresent(nonCompFast, KEY_NONCOMP_FAST);
        requirePresent(nonCompMedium, KEY_NONCOMP_MEDIUM);
        requirePresent(nonCompSlow, KEY_NONCOMP_SLOW);
        requirePresent(compFast, KEY_COMP_FAST);
        requirePresent(compMedium, KEY_COMP_MEDIUM);
        requirePresent(compSlow, KEY_COMP_SLOW);
        if (curve.isEmpty()) {
            throw new FormatException("Profile has no bins");
        }
        if (curve.size() > SensitivityProfile.MAX_BINS) {
            throw new FormatException("Too many bins (max " + SensitivityProfile.MAX_BINS + "): " + curve.size());
        }
        if (nonComp <= 0) {
            throw new FormatException("Non-compensated sensitivity must be positive");
        }
        requirePositive(nonCompFast, KEY_NONCOMP_FAST);
        requirePositive(nonCompMedium, KEY_NONCOMP_MEDIUM);
        requirePositive(nonCompSlow, KEY_NONCOMP_SLOW);
        requirePositive(compFast, KEY_COMP_FAST);
        requirePositive(compMedium, KEY_COMP_MEDIUM);
        requirePositive(compSlow, KEY_COMP_SLOW);

        List<Float> edges = new ArrayList<>(curve.keySet());
        float[] binEdges = new float[edges.size()];
        double[] compPsv = new double[edges.size()];
        for (int i = 0; i < edges.size(); i++) {
            binEdges[i] = edges.get(i);
            compPsv[i] = curve.get(edges.get(i));
        }

        return new SensitivityProfile(name == null ? DEFAULT_NAME : name, false,
                binEdges, compPsv, nonComp,
                compFast, compMedium, compSlow,
                nonCompFast, nonCompMedium, nonCompSlow);
    }

    private static void requirePresent(Object value, String key) throws FormatException {
        if (value == null) {
            throw new FormatException("Missing required key: " + key);
        }
    }

    private static void requirePositive(int value, String key) throws FormatException {
        if (value <= 0) {
            throw new FormatException(key + " must be a positive integer");
        }
    }

    private static int parseInt(String value, String key) throws FormatException {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new FormatException("Invalid integer for " + key + ": " + value);
        }
    }

    private static double parseDouble(String value, String key) throws FormatException {
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            throw new FormatException("Invalid number for " + key + ": " + value);
        }
    }

    /** Edge as a clean integer when whole (e.g. {@code 3000}), otherwise a locale-independent float. */
    private static String edgeStr(float edge) {
        if (edge == Math.rint(edge)) {
            return Long.toString((long) edge);
        }
        return Float.toString(edge);
    }

    /** Locale-independent, round-trippable representation of a pSv/count value. */
    private static String doubleStr(double value) {
        return Double.toString(value);
    }
}

package org.fe57.atomspectra;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Locale;

/**
 * One editable device setting. Values are Integer (INT, SELECT: option index), Double (DECIMAL),
 * Boolean (BOOL) and String (TEXT). Built once with the fluent methods, then read-only.
 */
public final class SettingDescriptor {
    public enum Type {INT, DECIMAL, BOOL, SELECT, TEXT}

    public enum Level {BASIC, EXPERT}

    public enum Scope {HARDWARE, SOFTWARE}

    public final String key;
    public final Type type;
    @StringRes
    public final int labelRes;
    @StringRes
    private int helpRes = 0;
    @StringRes
    private int unitRes = 0;
    private double min = -Double.MAX_VALUE;
    private double max = Double.MAX_VALUE;
    private int[] optionRes = new int[0];
    private Object defaultValue = null;
    private int digits = 0;
    private Level level = Level.BASIC;
    private Scope scope = Scope.HARDWARE;
    private boolean destructive = false;

    private SettingDescriptor(String key, Type type, int labelRes) {
        this.key = key;
        this.type = type;
        this.labelRes = labelRes;
    }

    public static SettingDescriptor integer(String key, @StringRes int labelRes, int min, int max) {
        SettingDescriptor descriptor = new SettingDescriptor(key, Type.INT, labelRes);
        descriptor.min = min;
        descriptor.max = max;
        return descriptor;
    }

    public static SettingDescriptor decimal(String key, @StringRes int labelRes) {
        return new SettingDescriptor(key, Type.DECIMAL, labelRes);
    }

    public static SettingDescriptor bool(String key, @StringRes int labelRes) {
        return new SettingDescriptor(key, Type.BOOL, labelRes);
    }

    /**
     * The value is the index of the chosen option.
     */
    public static SettingDescriptor select(String key, @StringRes int labelRes, @StringRes int... optionRes) {
        SettingDescriptor descriptor = new SettingDescriptor(key, Type.SELECT, labelRes);
        descriptor.optionRes = optionRes.clone();
        return descriptor;
    }

    public SettingDescriptor help(@StringRes int helpRes) {
        this.helpRes = helpRes;
        return this;
    }

    public SettingDescriptor unit(@StringRes int unitRes) {
        this.unitRes = unitRes;
        return this;
    }

    public SettingDescriptor range(double min, double max) {
        this.min = min;
        this.max = max;
        return this;
    }

    /**
     * Significant digits shown for a DECIMAL value; 0 shows the shortest exact form.
     */
    public SettingDescriptor digits(int digits) {
        this.digits = digits;
        return this;
    }

    public SettingDescriptor defaultValue(Object defaultValue) {
        this.defaultValue = defaultValue;
        return this;
    }

    public SettingDescriptor expert() {
        this.level = Level.EXPERT;
        return this;
    }

    public SettingDescriptor software() {
        this.scope = Scope.SOFTWARE;
        return this;
    }

    /**
     * Saving a change to this setting wipes data on the device.
     */
    public SettingDescriptor destructive() {
        this.destructive = true;
        return this;
    }

    @StringRes
    public int helpRes() {
        return helpRes;
    }

    @StringRes
    public int unitRes() {
        return unitRes;
    }

    public double min() {
        return min;
    }

    public double max() {
        return max;
    }

    public int[] optionRes() {
        return optionRes.clone();
    }

    @Nullable
    public Object defaultValue() {
        return defaultValue;
    }

    public Level level() {
        return level;
    }

    public Scope scope() {
        return scope;
    }

    public boolean isDestructive() {
        return destructive;
    }

    /**
     * Text for the value in an edit field.
     */
    @NonNull
    public String format(@Nullable Object value) {
        if (value == null) return "";
        if (type == Type.DECIMAL && value instanceof Double) {
            double number = (Double) value;
            if (!Double.isFinite(number)) return Double.toString(number);
            // plain notation: the number keyboard of the edit field has no exponent key
            BigDecimal exact = digits <= 0 ? new BigDecimal(Double.toString(number)) : new BigDecimal(number).round(new MathContext(digits));
            return exact.compareTo(BigDecimal.ZERO) == 0 ? "0" : exact.stripTrailingZeros().toPlainString();
        }
        return String.valueOf(value);
    }

    /**
     * The value typed into an edit field, null when the text is not a valid value in range.
     */
    @Nullable
    public Object parse(@NonNull String text) {
        String trimmed = text.trim();
        try {
            switch (type) {
                case INT:
                    long integer = Long.parseLong(trimmed);
                    return integer < min || integer > max ? null : (Object) (int) integer;
                case DECIMAL:
                    double number = Double.parseDouble(trimmed.replace(',', '.').toLowerCase(Locale.ROOT));
                    return !Double.isFinite(number) || number < min || number > max ? null : (Object) number;
                case TEXT:
                    return text;
                default:
                    return null;
            }
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

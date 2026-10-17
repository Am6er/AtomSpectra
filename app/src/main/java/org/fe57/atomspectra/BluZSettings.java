package org.fe57.atomspectra;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * The BluZ device settings: their schema, and how they are read from and compared with the 100-byte frame header.
 * Writing them is {@link BluZCommandCodec#settings4096}.
 */
final class BluZSettings {
    static final String[] CALIBRATION = {"cal0", "cal1", "cal2", "cal3", "cal4"};
    static final String PULSE_LED = "pulse_led";
    static final String PULSE_SOUND = "pulse_sound";
    static final String SOUND_DIV10 = "sound_div10";
    static final String LED_DIV10 = "led_div10";
    static final String AUTOSTART = "autostart";
    static final String ADC_SAMPLE = "adc_sample";
    static final String HV = "hv";
    static final String COMPARATOR = "comparator";

    // header offsets of the calibration floats {E0, D, C, B, A} in the 4096 mode, see docs/bluz-device-protocol.md
    private static final int[] CALIBRATION_OFFSETS = {70, 66, 82, 78, 74};
    private static final int HV_OFFSET = 50;
    private static final int COMPARATOR_OFFSET = 52;
    private static final int LED_SOUND_FLAGS = 60;
    private static final int AUTOSTART_FLAGS = 61;
    private static final int ADC_FLAGS = 97;

    static final SettingsSchema SCHEMA = SettingsSchema.builder()
            .category(R.string.bluz_settings_calibration,
                    calibration(0, R.string.bluz_setting_cal0).help(R.string.bluz_setting_cal_help),
                    calibration(1, R.string.bluz_setting_cal1),
                    calibration(2, R.string.bluz_setting_cal2),
                    calibration(3, R.string.bluz_setting_cal3),
                    calibration(4, R.string.bluz_setting_cal4))
            .category(R.string.bluz_settings_indication,
                    SettingDescriptor.bool(PULSE_LED, R.string.bluz_setting_pulse_led),
                    SettingDescriptor.bool(PULSE_SOUND, R.string.bluz_setting_pulse_sound),
                    SettingDescriptor.bool(SOUND_DIV10, R.string.bluz_setting_sound_div10),
                    SettingDescriptor.bool(LED_DIV10, R.string.bluz_setting_led_div10))
            .category(R.string.bluz_settings_spectrometer,
                    SettingDescriptor.bool(AUTOSTART, R.string.bluz_setting_autostart),
                    SettingDescriptor.integer(ADC_SAMPLE, R.string.bluz_setting_adc_sample, 0, 7)
                            .expert().destructive().help(R.string.bluz_setting_adc_sample_help),
                    SettingDescriptor.integer(HV, R.string.bluz_setting_hv, 0, 1023)
                            .expert().help(R.string.bluz_setting_hv_help),
                    SettingDescriptor.integer(COMPARATOR, R.string.bluz_setting_comparator, 0, 1023)
                            .expert().help(R.string.bluz_setting_comparator_help))
            .build();

    private BluZSettings() {
    }

    private static SettingDescriptor calibration(int index, int labelRes) {
        // nine significant digits write a float32 back unchanged
        return SettingDescriptor.decimal(CALIBRATION[index], labelRes).digits(9);
    }

    /**
     * All settings held by a frame header, by schema key; an immutable snapshot.
     */
    static Map<String, Object> parse(byte[] header) {
        Map<String, Object> values = new HashMap<>();
        ByteBuffer bytes = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        for (int index = 0; index < CALIBRATION.length; index++) {
            values.put(CALIBRATION[index], (double) bytes.getFloat(CALIBRATION_OFFSETS[index]));
        }
        int ledSound = header[LED_SOUND_FLAGS];
        int autostart = header[AUTOSTART_FLAGS];
        values.put(PULSE_LED, (ledSound & 1) != 0);
        values.put(PULSE_SOUND, (ledSound & 2) != 0);
        values.put(AUTOSTART, (autostart & 1) != 0);
        values.put(SOUND_DIV10, (autostart & 2) != 0);
        values.put(LED_DIV10, (autostart & 4) != 0);
        values.put(ADC_SAMPLE, header[ADC_FLAGS] & 7);
        values.put(HV, bytes.getShort(HV_OFFSET) & 1023);
        values.put(COMPARATOR, bytes.getShort(COMPARATOR_OFFSET) & 1023);
        return Collections.unmodifiableMap(values);
    }

    /**
     * The values as the device will report them back: calibration is stored as float32.
     */
    static Map<String, Object> normalize(Map<String, Object> changes) {
        Map<String, Object> normalized = new HashMap<>();
        for (Map.Entry<String, Object> change : changes.entrySet()) {
            Object value = change.getValue();
            normalized.put(change.getKey(), isCalibration(change.getKey()) ? (Object) (double) (float) number(value) : value);
        }
        return normalized;
    }

    /**
     * The header already holds every expected value.
     */
    static boolean matches(Map<String, Object> expected, byte[] header) {
        Map<String, Object> actual = parse(header);
        for (Map.Entry<String, Object> entry : expected.entrySet()) {
            Object value = actual.get(entry.getKey());
            if (isCalibration(entry.getKey())) {
                if (value == null || Double.compare((Double) value, number(entry.getValue())) != 0) return false;
            } else if (!entry.getValue().equals(value)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Five coefficients {E0, D, C, B, A} as settings changes.
     */
    static Map<String, Object> calibrationChanges(double[] coefficients) {
        if (coefficients == null || coefficients.length != CALIBRATION.length) {
            throw new IllegalArgumentException("BluZ calibration requires five coefficients");
        }
        Map<String, Object> changes = new HashMap<>();
        for (int index = 0; index < CALIBRATION.length; index++) changes.put(CALIBRATION[index], coefficients[index]);
        return changes;
    }

    static boolean isCalibration(String key) {
        for (String name : CALIBRATION) {
            if (name.equals(key)) return true;
        }
        return false;
    }

    static int calibrationIndex(String key) {
        for (int index = 0; index < CALIBRATION.length; index++) {
            if (CALIBRATION[index].equals(key)) return index;
        }
        return -1;
    }

    static double number(Object value) {
        if (!(value instanceof Number)) throw new IllegalArgumentException("BluZ setting is not a number");
        return ((Number) value).doubleValue();
    }
}

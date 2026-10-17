package org.fe57.atomspectra;

import androidx.annotation.Nullable;

import java.util.Map;

/**
 * Where the values of one scope of settings live.
 */
public interface SettingsStore {
    /**
     * Current values by key; null while they are not known yet.
     */
    @Nullable
    Map<String, Object> values();

    /**
     * Writes only the changed keys. Returns true when they are applied already, false when the
     * result arrives later (for a device: {@link SpectrumSource#ACTION_SOURCE_SETTINGS_SAVED} or an error reply).
     */
    boolean write(Map<String, Object> changes);
}

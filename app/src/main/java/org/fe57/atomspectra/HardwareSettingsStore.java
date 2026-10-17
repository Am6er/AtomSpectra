package org.fe57.atomspectra;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Map;

/**
 * Settings held by the device, reached through the service and its active source.
 */
final class HardwareSettingsStore implements SettingsStore {
    private final AtomSpectraService service;

    HardwareSettingsStore(@NonNull AtomSpectraService service) {
        this.service = service;
    }

    @Nullable
    @Override
    public Map<String, Object> values() {
        return service.deviceSettings();
    }

    @Override
    public boolean write(Map<String, Object> changes) {
        service.saveDeviceSettings(changes);
        return false;
    }
}

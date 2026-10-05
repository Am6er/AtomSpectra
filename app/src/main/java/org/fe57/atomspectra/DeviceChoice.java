package org.fe57.atomspectra;

/** What the user chose last and the next launch restores: nothing, files only, or a specific device. */
final class DeviceChoice {
    static final int MODE_NONE = 0;
    static final int MODE_OFFLINE = 1;
    static final int MODE_DEVICE = 2;

    final int mode;
    final int type;         // SpectrumSource.TYPE_*, MODE_DEVICE only
    final String identity;  // see DeviceIdentity, MODE_DEVICE only
    final String name;      // display name, MODE_DEVICE only

    private DeviceChoice(int mode, int type, String identity, String name) {
        this.mode = mode;
        this.type = type;
        this.identity = identity;
        this.name = name;
    }

    static DeviceChoice none() {
        return new DeviceChoice(MODE_NONE, SpectrumSource.TYPE_NONE, null, null);
    }

    static DeviceChoice offline() {
        return new DeviceChoice(MODE_OFFLINE, SpectrumSource.TYPE_NONE, null, null);
    }

    static DeviceChoice device(int type, String identity, String name) {
        return new DeviceChoice(MODE_DEVICE, type, identity, name);
    }
}

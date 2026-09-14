package org.fe57.atomspectra;

/** One selectable device as listed by {@link DeviceScanner}; the only thing handed to the service to connect. */
final class DeviceDescriptor {
    final int type;                 // SpectrumSource.TYPE_*
    final String identity;          // see DeviceIdentity
    final String displayName;
    final boolean permissionGranted;
    // capabilities of the source this device would create, known before the source exists
    final boolean supportsInitialHistogram;
    final int channelCount;
    final Object token;             // AudioDeviceInfo or UsbDevice, null for the pre-M default microphone

    DeviceDescriptor(int type, String identity, String displayName, boolean permissionGranted,
                     boolean supportsInitialHistogram, int channelCount, Object token) {
        this.type = type;
        this.identity = identity;
        this.displayName = displayName;
        this.permissionGranted = permissionGranted;
        this.supportsInitialHistogram = supportsInitialHistogram;
        this.channelCount = channelCount;
        this.token = token;
    }
}

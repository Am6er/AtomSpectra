package org.fe57.atomspectra;

/** One selectable device as listed by {@link DeviceScanner}; the only thing the selection screen hands to the service to connect. */
final class DeviceDescriptor {
    final int type;                 // SpectrumSource.TYPE_*
    final String identity;          // see DeviceIdentity
    final String displayName;
    final boolean permissionGranted;
    final Object token;             // AudioDeviceInfo or UsbDevice, null for the pre-M default microphone

    DeviceDescriptor(int type, String identity, String displayName, boolean permissionGranted, Object token) {
        this.type = type;
        this.identity = identity;
        this.displayName = displayName;
        this.permissionGranted = permissionGranted;
        this.token = token;
    }
}

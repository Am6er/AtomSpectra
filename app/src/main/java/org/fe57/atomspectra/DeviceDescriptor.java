package org.fe57.atomspectra;

/**
 * One selectable device as listed by {@link DeviceScanner}; the only thing the selection screen hands to the service to connect.
 */
final class DeviceDescriptor {
    final int type;                 // SpectrumSource.TYPE_*
    final String identity;          // see DeviceIdentity
    final String displayName;
    final boolean permissionGranted;
    final boolean available;
    final Object token;             // AudioDeviceInfo or UsbDevice, null for the pre-M default microphone

    DeviceDescriptor(int type, String identity, String displayName, boolean permissionGranted, Object token) {
        this(type, identity, displayName, permissionGranted, token, true);
    }

    DeviceDescriptor(int type, String identity, String displayName, boolean permissionGranted, Object token,
                     boolean available) {
        this.type = type;
        this.identity = identity;
        this.displayName = displayName;
        this.permissionGranted = permissionGranted;
        this.token = token;
        this.available = available;
    }

    boolean matches(int type, String identity) {
        return this.type == type && this.identity.equals(identity);
    }
}

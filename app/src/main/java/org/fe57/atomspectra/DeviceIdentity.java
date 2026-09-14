package org.fe57.atomspectra;

import android.hardware.usb.UsbDevice;
import android.media.AudioDeviceInfo;
import android.os.Build;

/** Best-effort device identity strings, shared by the scanner and the sources so they always agree. */
final class DeviceIdentity {
    private DeviceIdentity() {
    }

    /** Pre-M there is a single default microphone. */
    static final String AUDIO_DEFAULT = "audio:default";

    static String audio(AudioDeviceInfo device) {
        if (device == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return AUDIO_DEFAULT;
        }
        return "audio:" + device.getType() + ":" + device.getProductName();
    }

    static String usb(UsbDevice device) {
        return "usb:" + Integer.toHexString(device.getVendorId()) + ":" + Integer.toHexString(device.getProductId());
    }
}

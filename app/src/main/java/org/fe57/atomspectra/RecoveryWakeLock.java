package org.fe57.atomspectra;

import android.content.Context;
import android.os.PowerManager;

// Keeps the CPU awake during a source recovery so its handler timers are not stalled by deep sleep.
final class RecoveryWakeLock {
    private final PowerManager.WakeLock wakeLock;

    RecoveryWakeLock(Context context, String tag) {
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        wakeLock = power == null ? null : power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AtomSpectra:" + tag);
        if (wakeLock != null) wakeLock.setReferenceCounted(false);
    }

    // Repeated calls restart the timeout; the lock expires on its own after timeoutMs in case it is not released.
    void acquire(long timeoutMs) {
        if (wakeLock != null) wakeLock.acquire(timeoutMs);
    }

    void release() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }
}

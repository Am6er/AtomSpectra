package org.fe57.atomspectra;


import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.PermissionChecker;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Centralized runtime-permission handling for the app.
 */
public final class AppPermissions {

    public enum Capability {MIC, LOCATION, STORAGE, NOTIFICATIONS}

    // ---- static capability checks (usable from Activity, Service, anywhere) ----

    /** @return true when the capability is usable right now on this SDK. */
    public static boolean isGranted(Context ctx, Capability cap) {
        switch (cap) {
            case MIC:
                return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                        || checkSelf(ctx, Manifest.permission.RECORD_AUDIO);
            case LOCATION:
                return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                        || checkSelf(ctx, Manifest.permission.ACCESS_FINE_LOCATION);
            case NOTIFICATIONS:
                return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                        || checkSelf(ctx, Manifest.permission.POST_NOTIFICATIONS);
            case STORAGE:
                // SAF/content-URI writes on API 26+ need no runtime permission;
                // direct File I/O needs READ/WRITE only on API 23-25.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) return true;
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
                return checkSelf(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        && checkSelf(ctx, Manifest.permission.READ_EXTERNAL_STORAGE);
            default:
                return false;
        }
    }

    private static boolean checkSelf(Context ctx, String permission) {
        return PermissionChecker.checkSelfPermission(ctx, permission) == PermissionChecker.PERMISSION_GRANTED;
    }

    public static boolean isMicGranted(Context c) {
        return isGranted(c, Capability.MIC);
    }

    public static boolean isLocationGranted(Context c) {
        return isGranted(c, Capability.LOCATION);
    }

    public static boolean isStorageAllowed(Context c) {
        return isGranted(c, Capability.STORAGE);
    }

    public static boolean areNotificationsAllowed(Context c) {
        return isGranted(c, Capability.NOTIFICATIONS);
    }

    /** Raw permission strings actually requestable on this SDK for the given capabilities. */
    public static String[] requestablePermissions(Context ctx, Capability... caps) {
        List<String> perms = new ArrayList<>();
        for (Capability cap : caps) {
            switch (cap) {
                case MIC:
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !isMicGranted(ctx))
                        perms.add(Manifest.permission.RECORD_AUDIO);
                    break;
                case LOCATION:
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !isLocationGranted(ctx)) {
                        perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
                        perms.add(Manifest.permission.ACCESS_COARSE_LOCATION);
                    }
                    break;
                case NOTIFICATIONS:
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !areNotificationsAllowed(ctx))
                        perms.add(Manifest.permission.POST_NOTIFICATIONS);
                    break;
                case STORAGE:
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                            && Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                            && !isStorageAllowed(ctx)) {
                        perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
                        perms.add(Manifest.permission.READ_EXTERNAL_STORAGE);
                    }
                    break;
            }
        }
        return perms.toArray(new String[0]);
    }

    /**
     * Foreground-service type bitmask derived from currently granted permissions
     */
    public static int foregroundServiceType(Context ctx) {
        // base type: the only one whose Android 14+ prerequisites the app can always satisfy
        int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
        if (isMicGranted(ctx)) {
            type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        }
        if (isLocationGranted(ctx)) {
            type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
        }
        return type;
    }

    // ---- Activity-owned requester (modern ActivityResult API) ----

    public interface ResultListener {
        void onResult(Map<Capability, Boolean> granted);
    }

    private final ComponentActivity activity;
    private final ActivityResultLauncher<String[]> launcher;
    private final ResultListener defaultListener;

    // state of the request currently in flight (permission dialogs are sequential/modal)
    private ResultListener pendingListener;
    private Capability[] pendingCaps;

    /**
     * Must be constructed while the activity is being created (e.g. as a field initializer or in
     * {@code onCreate}), before it is STARTED — the modern API requires registration up front.
     *
     * @param act             the owning activity
     * @param startupListener listener invoked by {@link #request}; may be {@code null}
     */
    public AppPermissions(ComponentActivity act, ResultListener startupListener) {
        this.activity = act;
        this.defaultListener = startupListener;
        this.launcher = act.registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                this::handleSystemResult);
    }

    /**
     * Request the given capabilities (missing ones only) and report the resulting per-capability
     * state to the constructor-provided listener. If nothing is requestable on this SDK the
     * listener is invoked immediately with the current state.
     */
    public void request(Capability... caps) {
        launchOrReport(caps, defaultListener);
    }

    /**
     * Run {@code onGranted} once the capability is allowed; if it is not yet granted, request it
     * first and then run {@code onGranted} on grant or {@code onDenied} on denial.
     */
    public void ensure(Capability cap, Runnable onGranted, Runnable onDenied) {
        if (isGranted(activity, cap)) {
            if (onGranted != null) onGranted.run();
            return;
        }
        launchOrReport(new Capability[]{cap}, granted -> {
            if (Boolean.TRUE.equals(granted.get(cap))) {
                if (onGranted != null) onGranted.run();
            } else {
                if (onDenied != null) onDenied.run();
            }
        });
    }

    private void launchOrReport(Capability[] caps, ResultListener listener) {
        this.pendingCaps = caps;
        this.pendingListener = listener;
        String[] toRequest = requestablePermissions(activity, caps);
        if (toRequest.length == 0) {
            // nothing to ask on this SDK (or already granted) -> report current state immediately
            deliverResult();
        } else {
            launcher.launch(toRequest);
        }
    }

    private void handleSystemResult(Map<String, Boolean> rawResult) {
        // Re-derive from isGranted rather than trusting the raw map: it collapses the
        // fine/coarse location pair and matches exactly how the rest of the app checks.
        deliverResult();
    }

    private void deliverResult() {
        Map<Capability, Boolean> result = new EnumMap<>(Capability.class);
        if (pendingCaps != null) {
            for (Capability cap : pendingCaps) {
                result.put(cap, isGranted(activity, cap));
            }
        }
        ResultListener listener = pendingListener;
        pendingListener = null;
        pendingCaps = null;
        if (listener != null) listener.onResult(result);
    }
}

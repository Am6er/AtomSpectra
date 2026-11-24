package org.fe57.atomspectra;

final class DeviceConnectionStatus {
    private DeviceConnectionStatus() {
    }

    static int textResource(int type, AtomSpectraService.DeviceState state,
                            SpectrumSource.ConnectionPhase phase) {
        if (state == AtomSpectraService.DeviceState.NONE) return 0;
        if (state == AtomSpectraService.DeviceState.ERROR) return R.string.device_error_notification;
        if (state == AtomSpectraService.DeviceState.RECOVERING) return R.string.device_select_reconnecting;
        switch (phase) {
            case SEARCHING:
                return R.string.device_select_searching;
            case CONNECTING:
                return R.string.device_select_connecting;
            case WAITING_FOR_DATA:
                return R.string.device_select_waiting_data;
            default:
                break;
        }
        switch (state) {
            case WAITING:
                return type == SpectrumSource.TYPE_BLUZ ? R.string.device_select_unavailable
                        : R.string.device_select_connect_device;
            case IDLE:
                return R.string.device_select_connected;
            case RECORDING:
                return R.string.device_select_recording;
            case BUSY:
                return R.string.device_select_busy;
            default:
                return 0;
        }
    }
}
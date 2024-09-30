package org.fe57.atomspectra.ui.Spectrum;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import org.fe57.atomspectra.data.Calibration;
import org.fe57.atomspectra.data.Spectrum;

public class SpectrumViewModel extends ViewModel {
    private final MutableLiveData<Spectrum> mainSpectrum = new MutableLiveData<>();
    private final MutableLiveData<Spectrum> backSpectrum = new MutableLiveData<>();
    private final MutableLiveData<Calibration> newCalibration = new MutableLiveData<>();

    private final MutableLiveData<Boolean> clearSpectrum = new MutableLiveData<>();

    public boolean isPinchMode = false;
    public boolean isPinchModeFinished = false;
    public float zoom_factor = 1;
    public int cursor_x = -1;
    public boolean XCalibrated = true;
    public boolean logScale = false;
    public boolean barMode = false;

    public boolean showPlusMinusButtons = false;
    public boolean showScaleLabel = true;
    public long dateScaleChanged = 0;
    public long dateChannelChanged = 0;

    public void putMainSpectrum(Spectrum spectrum) {
        mainSpectrum.setValue(spectrum);
    }

    public LiveData<Spectrum> getMainSpectrum() {
        return mainSpectrum;
    }

    public void putBackSpectrum(Spectrum spectrum) {
        backSpectrum.setValue(spectrum);
    }

    public LiveData<Spectrum> getBackSpectrum() {
        return backSpectrum;
    }

    public void putNewCalibration(Calibration calibration) {
        newCalibration.setValue(calibration);
    }

    public LiveData<Calibration> getNewCalibration() {
        return newCalibration;
    }

    //signal to clear spectrum data
    public void setClearSpectrum() {
        clearSpectrum.setValue(true);
    }

    //way to react for spectrum clearance
    public LiveData<Boolean> getClearSpectrum() {
        return clearSpectrum;
    }
}
package org.fe57.atomspectra.ui.Spectrum;

import static android.content.Context.MODE_PRIVATE;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.text.method.NumberKeyListener;
import android.util.Log;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.MenuHost;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ViewModelProvider;

import org.fe57.atomspectra.AtomSpectraIsotopes;
import org.fe57.atomspectra.AtomSpectraService;
import org.fe57.atomspectra.R;
import org.fe57.atomspectra.data.Constants;
import org.fe57.atomspectra.data.Isotope;
import org.fe57.atomspectra.data.SwipeDetector;
import org.fe57.atomspectra.databinding.FragmentSpectrumBinding;

import java.util.Date;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Created by S. Epiphanov.
 * This class is used to show main spectrum and its data.
 */
public class SpectrumFragment extends Fragment implements MenuProvider {
    private final String TAG = SpectrumFragment.class.getSimpleName();

    private SpectrumViewModel mViewModel;
    private FragmentSpectrumBinding binding;

    private GestureDetector gestureDetector = null;
    private ScaleGestureDetector gestureScaleDetector = null;

    private SharedPreferences sharedPreferences = null;
    private Menu spectrumMenu = null;
    private boolean background_subtract = false;          //Subtract background from main histogram

    public static SpectrumFragment newInstance() {
        return new SpectrumFragment();
    }


    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        mViewModel = new ViewModelProvider(requireActivity()).get(SpectrumViewModel.class);
        binding = FragmentSpectrumBinding.inflate(inflater, container, false);
        binding.shapeArea.setModel(mViewModel);
        binding.addPointButton.setOnClickListener(this::onAddCalibrationPoint);
        binding.calibrateButton.setOnClickListener(this::onCalibrateButton);
        binding.removeCalibrationButton.setOnClickListener(this::onClearCalibrationButton);
        binding.clearSpectrumButton.setOnClickListener(this::onClickDeleteSpc);
        mViewModel.getClearSpectrum().observe(getViewLifecycleOwner(), (s) -> {});
        Context context = getContext();
        if (context != null) {
            sharedPreferences = context.getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
            initializeGestures();
        }
        return binding.getRoot();
    }

    /**
     * @param view an instance of the view created before in onCreateView
     * @param savedInstanceState saved instance state to be restored
     */
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        createMenu();
    }

    /**
     * Adds menu to be shown only if this fragment is active, shown and resumed
     */
    private void createMenu() {
        ((MenuHost)requireActivity()).addMenuProvider(this, getViewLifecycleOwner(), Lifecycle.State.RESUMED);
    }

    private void updateCalibrationMenu() {
        if (spectrumMenu != null) {
            double[] coeffs = AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().getCoeffArray(5);
            spectrumMenu.findItem(R.id.action_cal_point_1).setTitle(String.format(Locale.getDefault(), "c0: %.12g", coeffs[0]));
            spectrumMenu.findItem(R.id.action_cal_point_2).setTitle(String.format(Locale.getDefault(), "c1: %.12g", coeffs[1]));
            spectrumMenu.findItem(R.id.action_cal_point_3).setTitle(String.format(Locale.getDefault(), "c2: %.12g", coeffs[2]));
            spectrumMenu.findItem(R.id.action_cal_point_4).setTitle(String.format(Locale.getDefault(), "c3: %.12g", coeffs[3]));
            spectrumMenu.findItem(R.id.action_cal_point_5).setTitle(String.format(Locale.getDefault(), "c4: %.12g", coeffs[4]));

            if (AtomSpectraService.newCalibration.getLines() >= Constants.MAX_CALIBRATION_POINTS) {
                binding.addPointButton.setText("-");
                binding.addPointButton.setEnabled(false);
                spectrumMenu.findItem(R.id.action_cal_add_point).setEnabled(false);
            } else {
                binding.addPointButton.setText(String.format(Locale.US, "%d", AtomSpectraService.newCalibration.getLines() + 1));
                spectrumMenu.findItem(R.id.action_cal_add_point).setEnabled(true);
            }
            spectrumMenu.findItem(R.id.action_cal_calc).setEnabled(AtomSpectraService.newCalibration.getLines() > 1);
            binding.calibrateButton.setEnabled(AtomSpectraService.newCalibration.getLines() > 1);
            if (AtomSpectraService.newCalibration.getLines() < 2) {
                spectrumMenu.findItem(R.id.action_cal_draw_function).setChecked(false).setEnabled(false);
                AtomSpectraService.showCalibrationFunction = false;
            }
            spectrumMenu.findItem(R.id.action_cal_new_point1).
                    setTitle(getString(R.string.cal_show_line, AtomSpectraService.newCalibration.getChannel(0), AtomSpectraService.newCalibration.getEnergy(0))).
                    setVisible(AtomSpectraService.newCalibration.getLines() > 0);
            spectrumMenu.findItem(R.id.action_cal_new_point2).
                    setTitle(getString(R.string.cal_show_line, AtomSpectraService.newCalibration.getChannel(1), AtomSpectraService.newCalibration.getEnergy(1))).
                    setVisible(AtomSpectraService.newCalibration.getLines() > 1);
            spectrumMenu.findItem(R.id.action_cal_new_point3).
                    setTitle(getString(R.string.cal_show_line, AtomSpectraService.newCalibration.getChannel(2), AtomSpectraService.newCalibration.getEnergy(2))).
                    setVisible(AtomSpectraService.newCalibration.getLines() > 2);
            spectrumMenu.findItem(R.id.action_cal_new_point4).
                    setTitle(getString(R.string.cal_show_line, AtomSpectraService.newCalibration.getChannel(3), AtomSpectraService.newCalibration.getEnergy(3))).
                    setVisible(AtomSpectraService.newCalibration.getLines() > 3);
            spectrumMenu.findItem(R.id.action_cal_new_point5).
                    setTitle(getString(R.string.cal_show_line, AtomSpectraService.newCalibration.getChannel(4), AtomSpectraService.newCalibration.getEnergy(4))).
                    setVisible(AtomSpectraService.newCalibration.getLines() > 4);
            spectrumMenu.findItem(R.id.action_cal_new_point6).
                    setTitle(getString(R.string.cal_show_line, AtomSpectraService.newCalibration.getChannel(5), AtomSpectraService.newCalibration.getEnergy(5))).
                    setVisible(AtomSpectraService.newCalibration.getLines() > 5);
            spectrumMenu.findItem(R.id.action_cal_new_point7).
                    setTitle(getString(R.string.cal_show_line, AtomSpectraService.newCalibration.getChannel(6), AtomSpectraService.newCalibration.getEnergy(6))).
                    setVisible(AtomSpectraService.newCalibration.getLines() > 6);
            spectrumMenu.findItem(R.id.action_cal_new_point8).
                    setTitle(getString(R.string.cal_show_line, AtomSpectraService.newCalibration.getChannel(7), AtomSpectraService.newCalibration.getEnergy(7))).
                    setVisible(AtomSpectraService.newCalibration.getLines() > 7);
            spectrumMenu.findItem(R.id.action_cal_new_point9).
                    setTitle(getString(R.string.cal_show_line, AtomSpectraService.newCalibration.getChannel(8), AtomSpectraService.newCalibration.getEnergy(8))).
                    setVisible(AtomSpectraService.newCalibration.getLines() > 8);
            spectrumMenu.findItem(R.id.action_cal_new_point10).
                    setTitle(getString(R.string.cal_show_line, AtomSpectraService.newCalibration.getChannel(9), AtomSpectraService.newCalibration.getEnergy(9))).
                    setVisible(AtomSpectraService.newCalibration.getLines() > 9);
        }
    }

    @Override
    public void onStart() {
        super.onStart();
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public void onPause() {
        super.onPause();
//        binding.shapeArea.setOnClickListener((view) -> {});
//        binding.shapeArea.setOnTouchListener((v, event) -> false);
//        sharedPreferences = null;
//        gestureDetector = null;
//        gestureScaleDetector = null;
    }

    /**
     * Create gestures to interact with the user
     */
    @SuppressLint("ClickableViewAccessibility")
    private void initializeGestures() {
        gestureDetector = initGestureDetector();
        gestureScaleDetector = initScaleGestureDetector();

        binding.shapeArea.setOnClickListener(arg0 -> {
        });

        binding.shapeArea.setOnTouchListener((v, event) -> {
            boolean retVal;
            //retVal = gestureDetector.onTouchEvent(event);
            retVal = gestureScaleDetector.onTouchEvent(event);
            retVal = gestureDetector.onTouchEvent(event) || retVal;
            if(getActivity() != null)
                retVal = retVal || getActivity().onTouchEvent(event);
            //if (retVal) v.performClick();
            return retVal;
        });
    }


    /**
     * Creates a touch detector and fills with data
     * @return a newly created detector
     */
    private GestureDetector initGestureDetector() {
        return new GestureDetector(getContext(), new GestureDetector.SimpleOnGestureListener() {

            private final SwipeDetector detector = new SwipeDetector();

            @SuppressLint("ApplySharedPref")
            @Override
            public boolean onFling(@NonNull MotionEvent e1, @NonNull MotionEvent e2, float velocityX,
                                   float velocityY) {
                if (mViewModel.isPinchMode) return true;
                if (AtomSpectraService.getScaleFactor() == Constants.SCALE_DOSE_MODE)
                    return true;
                if (mViewModel.isPinchModeFinished) {
                    mViewModel.isPinchModeFinished = false;
                    return true;
                }
                int new_channel;

                Log.d(TAG, "FLING!  ");
                try {
                    if (detector.isSwipeLeft(e1, e2, velocityX)) {
                        new_channel = AtomSpectraService.getFirstChannel() + Constants.WINDOW_OUTPUT_SIZE / 4 * (1 << (Constants.SCALE_MAX - AtomSpectraService.getScaleFactor()));

                        if (new_channel + Constants.WINDOW_OUTPUT_SIZE * (1 << (Constants.SCALE_MAX - AtomSpectraService.getScaleFactor())) > Constants.NUM_HIST_POINTS) {
                            new_channel = Constants.NUM_HIST_POINTS - Constants.WINDOW_OUTPUT_SIZE * (1 << (Constants.SCALE_MAX - AtomSpectraService.getScaleFactor()));
                        }

                        AtomSpectraService.setFirstChannel(new_channel);
                        SharedPreferences.Editor prefEditor = sharedPreferences.edit();
                        prefEditor.putInt(Constants.CONFIG.CONF_FIRST_CHANNEL, AtomSpectraService.getFirstChannel());
                        prefEditor.commit();
                        AtomSpectraService.requestUpdateGraph();
                        binding.shapeArea.performClick();
                    } else if (detector.isSwipeRight(e1, e2, velocityX)) {
                        new_channel = AtomSpectraService.getFirstChannel() - Constants.WINDOW_OUTPUT_SIZE / 4 * (1 << (Constants.SCALE_MAX - AtomSpectraService.getScaleFactor()));

                        if (new_channel < 0) {
                            new_channel = 0;
                        }

                        AtomSpectraService.setFirstChannel(new_channel);
                        SharedPreferences.Editor prefEditor = sharedPreferences.edit();
                        prefEditor.putInt(Constants.CONFIG.CONF_FIRST_CHANNEL, AtomSpectraService.getFirstChannel());
                        prefEditor.commit();
                        AtomSpectraService.requestUpdateGraph();
                        binding.shapeArea.performClick();
                    } else if (detector.isSwipeDown(e1, e2, velocityY)) {
                        if (SpectrumShapeView.isotopeFound >= 0 && mViewModel.cursor_x > 0) {
                            CheckBox isotopeData = getActivity().findViewById(Constants.GROUPS.BUTTON_ID_ALIGN + SpectrumShapeView.isotopeFound);
                            AtomSpectraIsotopes.checkedIsotopeLine[SpectrumShapeView.isotopeFound] = !AtomSpectraIsotopes.checkedIsotopeLine[SpectrumShapeView.isotopeFound];
                            isotopeData.toggle();
                        }
                        AtomSpectraService.requestUpdateGraph();
                        binding.shapeArea.performClick();
                    }
                } catch (Exception ignored) {
                } //for now, ignore
                return false;

            }

            @Override
            public boolean onDoubleTap(@NonNull MotionEvent e1) {
                if (AtomSpectraService.getScaleFactor() == Constants.SCALE_DOSE_MODE)
                    return true;
                mViewModel.logScale = !mViewModel.logScale;
                mViewModel.dateScaleChanged = new Date().getTime();
                mViewModel.showScaleLabel = true;
                AtomSpectraService.requestUpdateGraph();
                if (mViewModel.logScale) showToast(getString(R.string.graph_log_info));
                else showToast(getString(R.string.graph_linear_info));
                binding.shapeArea.performClick();
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(@NonNull MotionEvent e1) {
                if (AtomSpectraService.showCalibrationFunction) {
                    showCursorInfo();
                    mViewModel.dateChannelChanged = 0;
                    binding.seekChannel.setVisibility(SeekBar.INVISIBLE);
                    mViewModel.showPlusMinusButtons = false;
                    return true;
                }
                if (AtomSpectraService.getScaleFactor() == Constants.SCALE_DOSE_MODE) {
                    return true;
                }
                boolean getInside = false;
                if (SpectrumShapeView.isOutOfFrame(e1.getX())) {
                    mViewModel.cursor_x = -1;
                    mViewModel.dateChannelChanged = 0;
                    binding.seekChannel.setVisibility(SeekBar.INVISIBLE);
                    mViewModel.showPlusMinusButtons = false;
                } else {
                    if ((AtomSpectraService.newCalibration.getLines() < Constants.MAX_CALIBRATION_POINTS)) {
                        for (Isotope i : AtomSpectraIsotopes.foundList) {
                            if (i.getCoord().contains(e1.getX(), e1.getY())) {
                                getInside = true;
                                final Context id = getContext();
                                final int channel_x = AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().toChannel(i.getEnergy(0));
                                final AlertDialog.Builder alert = new AlertDialog.Builder(id)
                                        .setTitle(getString(R.string.calibration_add_nuclid_title))
                                        .setMessage(getString(R.string.calibration_add_nuclid2_text, channel_x, i.getName(), i.getEnergy(0)));
                                final EditText input = new EditText(id);
                                input.setKeyListener(new NumberKeyListener() {
                                    @NonNull
                                    @Override
                                    protected char[] getAcceptedChars() {
                                        return new char[]{'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '.', ','};
                                    }

                                    @Override
                                    public int getInputType() {
                                        return InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_VARIATION_NORMAL;
                                    }
                                });
                                input.setImeOptions(EditorInfo.IME_ACTION_DONE);
                                alert.setView(input);
                                alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                                            float fValue;// = value.valueOf(value);
                                            try {
                                                fValue = Float.parseFloat(input.getText().toString().replaceAll(",", "."));
                                            } catch (Exception nfe) {
                                                Toast.makeText(getContext(), getString(R.string.cal_error_number), Toast.LENGTH_SHORT).show();
                                                return;
                                            }
                                            AtomSpectraService.newCalibration.addLine(channel_x, fValue);
                                            if (AtomSpectraService.newCalibration.getLines() > 1) {
                                                //app_menu.findItem(R.id.action_cal_draw_function).setEnabled(true);
                                                //TODO: update menu if desired
                                                AtomSpectraService.newCalibration.Calculate(sharedPreferences.getInt(Constants.CONFIG.CONF_MAX_POLI_FACTOR, Constants.DEFAULT_POLI_FACTOR));
                                                if (!AtomSpectraService.newCalibration.isCorrect()) {
                                                    Toast.makeText(getContext(), getString(R.string.cal_maybe_wrong, channel_x, fValue), Toast.LENGTH_LONG).show();
                                                }
                                            }
                                            //updateCalibrationMenu();
                                            //TODO: update calibration info if desired
                                            showCursorInfo();
                                        })
                                        .setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
                                        });
                                alert.show();
                            }
                        }
                    }
                    if ((AtomSpectraService.newCalibration.getLines() < Constants.MAX_CALIBRATION_POINTS) && (mViewModel.cursor_x != -1)) {
                        for (Isotope i : AtomSpectraIsotopes.isotopeLineArray) {
                            if (i.getCoord().contains(e1.getX(), e1.getY())) {
                                getInside = true;
                                final Context id = getContext();
                                final AlertDialog.Builder alert = new AlertDialog.Builder(id)
                                        .setTitle(getString(R.string.calibration_add_nuclid_title))
                                        .setMessage(getString(R.string.calibration_add_nuclid_text, mViewModel.cursor_x, i.getName(), i.getEnergy(0)))
                                        .setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                                            AtomSpectraService.newCalibration.addLine(mViewModel.cursor_x, i.getEnergy(0));
                                            if (AtomSpectraService.newCalibration.getLines() > 1) {
                                                AtomSpectraService.newCalibration.Calculate(sharedPreferences.getInt(Constants.CONFIG.CONF_MAX_POLI_FACTOR, Constants.DEFAULT_POLI_FACTOR));
                                                //app_menu.findItem(R.id.action_cal_draw_function).setEnabled(true);
                                                //TODO: update menu if desired
                                                if (!AtomSpectraService.newCalibration.isCorrect()) {
                                                    Toast.makeText(getContext(), getString(R.string.cal_maybe_wrong, mViewModel.cursor_x, i.getEnergy(0)), Toast.LENGTH_LONG).show();
                                                }
                                            }
                                            //updateCalibrationMenu();
                                            //TODO: update calibration info if desired
                                            showCursorInfo();
                                        })
                                        .setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
                                        });
                                alert.show();
                            }
                        }
                    }
                    if (!getInside) {
                        mViewModel.dateChannelChanged = System.currentTimeMillis();
//						Button button = findViewById(R.id.channelMinusButton);
//						button.setVisibility(Button.VISIBLE);
//						button = findViewById(R.id.channelPlusButton);
//						button.setVisibility(Button.VISIBLE);
                        binding.seekChannel.setVisibility(SeekBar.VISIBLE);
                        if (mViewModel.showPlusMinusButtons || (mViewModel.cursor_x < AtomSpectraService.getFirstChannel())) {
                            if (mViewModel.XCalibrated) {
                                mViewModel.cursor_x = AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().toChannel(SpectrumShapeView.X2scale(e1.getX()));
                            } else {
                                mViewModel.cursor_x = (int) StrictMath.rint(SpectrumShapeView.X2scale(e1.getX()));
                            }
                        }
                        mViewModel.showPlusMinusButtons = true;
                    }
                }
                showCursorInfo();
                binding.shapeArea.performClick();
                return true;
            }

            @SuppressLint("ApplySharedPref")
            @Override
            public void onLongPress(MotionEvent e1) {
                mViewModel.barMode = !mViewModel.barMode;
                SharedPreferences.Editor prefEditor = sharedPreferences.edit();
                prefEditor.putBoolean(Constants.CONFIG.CONF_BAR_MODE, mViewModel.barMode);
                prefEditor.commit();
                AtomSpectraService.requestUpdateGraph();
                binding.shapeArea.performClick();
            }

            private void showToast(String phrase) {
                Toast.makeText(getContext(), phrase, Toast.LENGTH_SHORT).show();
            }
        });
    }


    /**
     * Creates and initialized multitouch detector.
     * @return a newly created detector
     */
    private ScaleGestureDetector initScaleGestureDetector() {
        return new ScaleGestureDetector(getContext(), new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            private float SpanXInit = 1;
            private float SpanYInit = 1;
//			private float FocusXInit = 0;
//			private float FocusYInit = 0;

            @Override
            public boolean onScaleBegin(@NonNull ScaleGestureDetector scaleGestureDetector) {
                SpanXInit = scaleGestureDetector.getCurrentSpanX();
                SpanYInit = scaleGestureDetector.getCurrentSpanY();
//				FocusXInit = scaleGestureDetector.getFocusX();
//				FocusYInit = scaleGestureDetector.getFocusY();
                mViewModel.isPinchMode = true;
                return true;
            }

            @Override
            public boolean onScale(@NonNull ScaleGestureDetector scaleGestureDetector) {
                return true;
            }

            @SuppressLint("ApplySharedPref")
            @Override
            public void onScaleEnd(@NonNull ScaleGestureDetector scaleGestureDetector) {
                float mScaleFactor;
                float mSpanX, mSpanY;
//				float mFocusX, mFocusY;
                int new_channel;
                mViewModel.isPinchMode = false;
                mViewModel.isPinchModeFinished = true;
                mSpanX = scaleGestureDetector.getCurrentSpanX() - SpanXInit;
                mSpanY = scaleGestureDetector.getCurrentSpanY() - SpanYInit;
                mScaleFactor = ((mSpanX) * (mSpanX) + (mSpanY) * (mSpanY));
//				mFocusX = scaleGestureDetector.getFocusX();
//				mFocusY = scaleGestureDetector.getFocusY();

                if (mScaleFactor > 22500) {
                    boolean spanXPrefer = StrictMath.abs(mSpanX * 1.5) > StrictMath.abs(mSpanY);
                    boolean spanYPrefer = StrictMath.abs(mSpanX) < StrictMath.abs(mSpanY * 1.5);
                    if ((mSpanX > 100) && spanXPrefer && (AtomSpectraService.getScaleFactor() <= Constants.SCALE_MAX)) {
                        if (AtomSpectraService.getScaleFactor() < Constants.SCALE_MAX) {
                            AtomSpectraService.setScaleFactor(AtomSpectraService.getScaleFactor() + 1);
                            AtomSpectraService.requestUpdateGraph();
                        } else
                            showToast(getString(R.string.graph_max_gain));
                        SharedPreferences.Editor prefEditor = sharedPreferences.edit();
                        prefEditor.putInt(Constants.CONFIG.CONF_SCALE_FACTOR, AtomSpectraService.getScaleFactor());
                        prefEditor.commit();
                    }

                    if ((mSpanY > 100) && spanYPrefer) {
                        //showToast("Zoom out Y");
                        if (mViewModel.zoom_factor < 40) {
                            if (mViewModel.zoom_factor >= 1)
                                mViewModel.zoom_factor *= 2;
                            else
                                mViewModel.zoom_factor += 0.25;
                            AtomSpectraService.requestUpdateGraph();
                        } else {
                            mViewModel.zoom_factor = 64;
                            showToast(getString(R.string.graph_max_zoom));
                        }
                    }

                    if ((mSpanX < -100) && spanXPrefer && (AtomSpectraService.getScaleFactor() <= Constants.SCALE_MAX)) {
                        //showToast("Zoom in X");
                        if (AtomSpectraService.getScaleFactor() > Constants.SCALE_MIN) {

                            if (AtomSpectraService.getFirstChannel() + Constants.WINDOW_OUTPUT_SIZE / 2 * (1 << (1 + Constants.SCALE_MAX - AtomSpectraService.getScaleFactor())) > Constants.NUM_HIST_POINTS) {
                                new_channel = Constants.NUM_HIST_POINTS - Constants.WINDOW_OUTPUT_SIZE / 2 * (1 << (1 + Constants.SCALE_MAX - AtomSpectraService.getScaleFactor()));

                                if (new_channel < 0) {
                                    new_channel = 0;
                                }

                                AtomSpectraService.setFirstChannel(new_channel);
                                SharedPreferences.Editor prefEditor = sharedPreferences.edit();
                                prefEditor.putInt(Constants.CONFIG.CONF_FIRST_CHANNEL, AtomSpectraService.getFirstChannel());
                                prefEditor.commit();
                            }

                            AtomSpectraService.setScaleFactor(AtomSpectraService.getScaleFactor() - 1);
                            AtomSpectraService.requestUpdateGraph();
                        } else
                            showToast(getString(R.string.graph_min_gain));
                        SharedPreferences.Editor prefEditor = sharedPreferences.edit();
                        prefEditor.putInt(Constants.CONFIG.CONF_SCALE_FACTOR, AtomSpectraService.getScaleFactor());
                        prefEditor.commit();
                    }

                    if ((mSpanY < -100) && spanYPrefer) {
                        if (mViewModel.zoom_factor > 0.4) {
                            if (mViewModel.zoom_factor > 1)
                                mViewModel.zoom_factor /= 2;
                            else
                                mViewModel.zoom_factor -= 0.25;
                            AtomSpectraService.requestUpdateGraph();
                        } else {
                            mViewModel.zoom_factor = 0.25f;
                            showToast(getString(R.string.graph_min_zoom));
                        }
                    }

                }

                //return false;
                binding.shapeArea.performClick();
            }

            private void showToast(String phrase) {
                Toast.makeText(getContext(), phrase, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private final Timer buttonsTimer = new Timer();
    private final TimerTask buttonsTask = new TimerTask() {
        @Override
        public void run() {
            if (mViewModel.showPlusMinusButtons && ((System.currentTimeMillis() - mViewModel.dateChannelChanged) > Constants.CURSOR_TIMEOUT)) {
                mViewModel.showPlusMinusButtons = false;
                binding.seekChannel.post(() -> binding.seekChannel.setVisibility(SeekBar.INVISIBLE));
            }
            if (mViewModel.showScaleLabel && ((new Date().getTime() - mViewModel.dateScaleChanged) > Constants.LABEL_TIMEOUT)) {
                mViewModel.showScaleLabel = false;
            }
        }
    };

    private void showCursorInfo() {
//		TextView mTextView = findViewById(R.id.cursorView);
        if (mViewModel.cursor_x >= 0 && mViewModel.cursor_x >= AtomSpectraService.getFirstChannel() / Constants.NUM_HIST_POINTS * AtomSpectraService.lastCalibrationChannel && !AtomSpectraService.showCalibrationFunction) {
            binding.cursorView.setText(getString(R.string.cursor_format, mViewModel.cursor_x, AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().toEnergy(mViewModel.cursor_x), AtomSpectraService.ForegroundSpectrum.getSpectrum()[mViewModel.cursor_x]));
            binding.cursorView.setVisibility(TextView.VISIBLE);
            binding.suffixLayout.setVisibility(LinearLayout.VISIBLE);
        } else {
            binding.cursorView.setVisibility(TextView.INVISIBLE);
            binding.suffixLayout.setVisibility(LinearLayout.INVISIBLE);
        }
        AtomSpectraService.requestUpdateGraph();
        Log.d(TAG, "showCursorInfo: " + mViewModel.cursor_x);
    }

    public void onAddCalibrationPoint(View view) {
//		final Button button = (Button) view;
        if (AtomSpectraService.newCalibration.getLines() >= Constants.MAX_CALIBRATION_POINTS) {
            Toast.makeText(getContext(), getString(R.string.cal_no_more), Toast.LENGTH_SHORT).show();
            return;
        }
        if (AtomSpectraService.newCalibration.containsChannel(mViewModel.cursor_x)) {
            Toast.makeText(getContext(), getString(R.string.cal_have_channel), Toast.LENGTH_SHORT).show();
            return;
        }
        if ((mViewModel.cursor_x >= 0) && (mViewModel.cursor_x < Constants.NUM_HIST_POINTS)) {
            final AlertDialog.Builder alert = new AlertDialog.Builder(getContext());
            alert.setTitle(getString(R.string.cal_point_title, mViewModel.cursor_x));
            alert.setMessage(getString(R.string.ask_point_in_kev));

            final EditText input = new EditText(getContext());
            input.setKeyListener(new NumberKeyListener() {
                @NonNull
                @Override
                protected char[] getAcceptedChars() {
                    return new char[]{'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '.', ','};
                }

                @Override
                public int getInputType() {
                    return InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL;
                }
            });
            input.setImeOptions(EditorInfo.IME_ACTION_DONE);
            alert.setView(input);
            final Context context = getContext();
            alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
                float fValue;// = value.valueOf(value);
                try {
                    fValue = Float.parseFloat(input.getText().toString().replaceAll(",", "."));
                } catch (Exception nfe) {
                    Toast.makeText(context, getString(R.string.cal_error_number), Toast.LENGTH_SHORT).show();
                    return;
                }
                AtomSpectraService.newCalibration.addLine(mViewModel.cursor_x, fValue);
                if (AtomSpectraService.newCalibration.getLines() > 1) {
                    AtomSpectraService.newCalibration.Calculate(sharedPreferences.getInt(Constants.CONFIG.CONF_MAX_POLI_FACTOR, Constants.DEFAULT_POLI_FACTOR));
                    //app_menu.findItem(R.id.action_cal_draw_function).setEnabled(true);
                    //TODO: update menu
                    if (!AtomSpectraService.newCalibration.isCorrect()) {
                        Toast.makeText(getContext(), getString(R.string.cal_maybe_wrong, mViewModel.cursor_x, fValue), Toast.LENGTH_LONG).show();
                    }
                }
                //updateCalibrationMenu();
                //TODO: update calibration
                showCursorInfo();
            });
            alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
            });
            alert.show();
        } else
            Toast.makeText(getContext(), getString(R.string.put_cursor_first), Toast.LENGTH_SHORT).show();
    }

    public void onCalibrateButton(View view) {
        if (AtomSpectraService.newCalibration.getLines() < 2) {
            Toast.makeText(getContext(), getString(R.string.cal_no_enough_data), Toast.LENGTH_LONG).show();
            return;
        }
        AtomSpectraService.newCalibration.Calculate(sharedPreferences.getInt(Constants.CONFIG.CONF_MAX_POLI_FACTOR, Constants.DEFAULT_POLI_FACTOR));
        if (AtomSpectraService.newCalibration.isCorrect()) {
            AtomSpectraService.showCalibrationFunction = false;
            //app_menu.findItem(R.id.action_cal_draw_function).setChecked(false);
            //app_menu.findItem(R.id.action_cal_draw_function).setEnabled(false);
            //TODO: update menu
            AtomSpectraService.ForegroundSpectrum.setSpectrumCalibration(AtomSpectraService.newCalibration);
            AtomSpectraService.newCalibration.clear();
            //updateCalibrationMenu();
            //TODO: update calibration
            binding.addPointButton.setText("1");
            binding.addPointButton.setEnabled(true);
            Toast.makeText(getContext(), getString(R.string.cal_applied), Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(getContext(), getString(R.string.cal_load_error), Toast.LENGTH_LONG).show();
        }
    }

    public void onClearCalibrationButton(View view) {
        AtomSpectraService.showCalibrationFunction = false;
        //app_menu.findItem(R.id.action_cal_draw_function).setChecked(false);
        //app_menu.findItem(R.id.action_cal_draw_function).setEnabled(false);
        //TODO: update menu
        AtomSpectraService.newCalibration.clear();
        //updateCalibrationMenu();
        //TODO: update calibration
    }

    public void onClickDeleteSpc(View v) {
        final AlertDialog.Builder alert = new AlertDialog.Builder(getContext())
                .setTitle(getString(R.string.hist_ask_delete_title))
                .setMessage(getString(R.string.hist_ask_delete_text))
                .setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
//					app_menu.findItem(R.id.action_hist_freeze).setIcon(R.drawable.menu_block);
//					app_menu.findItem(R.id.action_hist_freeze).setTitle(R.string.hist_freeze_update);
                    //AtomSpectraService.freeze_update_data = false;
                    AtomSpectraIsotopes.showFoundIsotopes = false;
                    AtomSpectraIsotopes.foundList.clear();
//					sendBroadcast(new Intent(Constants.ACTION.ACTION_FREEZE_DATA).putExtra(AtomSpectraSerial.EXTRA_DATA_TYPE, true));
//                    sendBroadcast(new Intent(Constants.ACTION.ACTION_CLEAR_SPECTRUM));
                    mViewModel.setClearSpectrum();
//					AtomSpectraService.ForegroundSpectrum.setLocation(null).setComments(null).setSpectrumDate(0);
                    binding.suffixView.setText(getString(R.string.hist_suffix));
//					getCalibrationSettings(false, false);
//					updateCalibrationMenu();
                    //sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_NOTIFICATION));
                })
                .setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
                });
        alert.show();
    }

    @Override
    public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.atom_spectrum, menu);
        spectrumMenu = menu;
        boolean enable_back = !AtomSpectraService.BackgroundSpectrum.isEmpty();
        menu.findItem(R.id.action_cal_channel).setTitle(getString(R.string.calibration_channel_format, AtomSpectraService.lastCalibrationChannel));

        menu.findItem(R.id.action_background_show).setChecked(AtomSpectraService.background_show);
        menu.findItem(R.id.action_background_show).setEnabled(enable_back);
        menu.findItem(R.id.action_background_suffix).setEnabled(enable_back);
        menu.findItem(R.id.action_background_subtract).setEnabled(AtomSpectraService.background_show);
        menu.findItem(R.id.action_background_subtract).setChecked(background_subtract);
        menu.findItem(R.id.action_background_clear).setEnabled(enable_back);
        menu.findItem(R.id.action_background_save).setEnabled(enable_back);
        menu.findItem(R.id.action_cal_draw_function).setChecked(AtomSpectraService.showCalibrationFunction);
        menu.findItem(R.id.action_cal_draw_function).setEnabled(AtomSpectraService.newCalibration != null && AtomSpectraService.newCalibration.getLines() > 1);

        menu.findItem(R.id.action_hist_smooth).setTitle(AtomSpectraService.setSmooth ? getString(R.string.hist_unsmooth) : getString(R.string.hist_smooth));
        updateCalibrationMenu();

        if (AtomSpectraService.getFreeze()) {
            menu.findItem(R.id.action_hist_freeze).setIcon(R.drawable.record);
            menu.findItem(R.id.action_hist_freeze).setTitle(R.string.hist_continue_update);
        } else {
            menu.findItem(R.id.action_hist_freeze).setIcon(R.drawable.menu_block);
            menu.findItem(R.id.action_hist_freeze).setTitle(R.string.hist_freeze_update);
        }
    }

    @Override
    public boolean onMenuItemSelected(@NonNull MenuItem menuItem) {
        return false;
    }
}
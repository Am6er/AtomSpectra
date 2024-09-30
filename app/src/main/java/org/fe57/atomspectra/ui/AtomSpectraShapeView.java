package org.fe57.atomspectra.ui;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.Locale;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Paint.Align;
import android.graphics.Paint.Style;
import android.graphics.Rect;
import android.graphics.Shader.TileMode;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import org.fe57.atomspectra.AtomSpectraIsotopes;
import org.fe57.atomspectra.AtomSpectraService;
import org.fe57.atomspectra.R;
import org.fe57.atomspectra.data.Constants;
import org.fe57.atomspectra.data.Isotope;

@SuppressLint({ "DefaultLocale", "DrawAllocation" })
public class AtomSpectraShapeView extends View {
	private final Paint squareColor = new Paint();
	private final Paint textColor = new Paint();
	private final Rect rect = new Rect();

	private boolean circleMode = false;
	private boolean barMode = false;
	private boolean no_y_mode = false;
	private boolean dose_mode = false;
	private boolean m_dose_mode = false;
	private boolean logScale = false;
	private boolean isCalibrated = true;
	private boolean calibrationScale = false;

	private static final float scaleText = 1.61803398875f * 1.1f;                                                                //scale text on graph

	private static int margin_left = 0;
	private static int margin_right = 0;
	private static int margin_top = 0;
	private static int margin_bottom = 0;
	private static int height = 1;
	private static int width = 1;
	private int frontCountsMin = 4, frontCountsMax = 8;

	private static final int[] X = new int[2048];
	private static final int[] Y = new int[2048];
	private static final double[] yf = new double[2048];
	private static final double[] tmp = new double[2048];
	private static final int[] back_Y = new int[2048];
	private static final double[] back_yf = new double[2048];
	private static final double[] back_tmp = new double[2048];
	private static boolean background_show = false;
	private static boolean background_delta = false;

	private String measureUnits = "";
	private float zoom = 1.0f;
	private int xScale = 1;

	private static float x_max_value = 1.0f;
	private static float x_min_value = 0.0f;

	private float cursor_X = -1;
	public static int isotopeFound = -1;
	private String isotopeLabel = null;

	private double max, min;

	private int N = 0;
	private static final double CURSOR_INEQUALITY = 0.03;
	private static final double[] logLines = {StrictMath.log10(2), StrictMath.log10(3), StrictMath.log10(4),
			StrictMath.log10(5), StrictMath.log10(6), StrictMath.log10(7), StrictMath.log10(8), StrictMath.log10(9)};


	public AtomSpectraShapeView(Context context) {
		super(context);
	}

	public AtomSpectraShapeView(Context context, AttributeSet attrs) {
		super(context, attrs);
	}

	public AtomSpectraShapeView(Context context, AttributeSet attrs, int defStyle) {
		super(context, attrs, defStyle);
	}


	public static double X2scale(double X)
	{
		return (x_max_value - x_min_value) * (X - margin_left) / width + x_min_value;
	}

	public static boolean isOutOfFrame(float x) {
		return (x < margin_left) || (x > (margin_left + width));
	}

	@SuppressLint("DefaultLocale")
	@Override
	protected void onDraw(Canvas canvas) {
		Resources res = getResources();
		canvas.drawColor(Color.BLACK);

		int viewWidth = getWidth();
		int viewHeight = getHeight();
		margin_left = viewWidth / 7;
		margin_right = viewWidth / 40;

		int ht = 12;
		//int wt=12;
		float ht_px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, ht, getResources().getDisplayMetrics());
		//float wt_px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, wt, getResources().getDisplayMetrics());
		margin_top = margin_bottom = (int) (2 * ht_px);

		// Typeface tf = Typeface.createFromAsset(  getContext().getAssets() , "courier.ttf");
		//   textColor.setTypeface(tf);

		//Typeface tf = Typeface.create("Courier",Typeface.BOLD);
		//textColor.setTypeface(tf);
		margin_left = margin_right = (int) (2 * ht_px);

		width = viewWidth - margin_left - margin_right;
		height = viewHeight - margin_top - margin_bottom;
		int nx;
		int ny;
		if (no_y_mode) {
			nx = 4;
			ny = 4;
		} else {
			float dpSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_PX, width > height ? viewWidth : viewHeight, getResources().getDisplayMetrics());
			if (width > height) {
				if (dpSize > 800)
					nx = 12;
				else if (dpSize > 400)
					nx = 8;
				else
					nx = 4;
				ny = 4;
			} else {
				nx = 4;
				if (dpSize > 800)
					ny = 12;
				else if (dpSize > 400)
					ny = 8;
				else
					ny = 4;
			}
		}

		float dwx = (float) width / nx;
		float dwy = (float) height / ny;
		long decValue;
		{
			int shift = 0;
			decValue = (long)(max / zoom);
			while (decValue > 10) {
				decValue /= 10;
				shift++;
			}
			decValue = 1;
			while (shift > 0) {
				decValue *= 10;
				shift--;
			}
		}

		squareColor.setColor(getResources().getColor(R.color.colorStrokes));
		squareColor.setStrokeWidth(2);
		textColor.setColor(getResources().getColor(R.color.colorStrokes2));
		textColor.setStrokeWidth(2);
		if (!no_y_mode) {
			for (int i = 0; i < nx + 1; i++)
				canvas.drawLine(margin_left + i * dwx, margin_top,
						margin_left + i * dwx, viewHeight - margin_bottom, squareColor);
			canvas.drawLine(margin_left, margin_top,
					margin_left + width, margin_top, squareColor);
			canvas.drawLine(margin_left, margin_top + height,
					margin_left + width, margin_top + height, squareColor);
			if (logScale) {
				float line_val = 0.0f;
				while (line_val <= max / zoom) {
					canvas.drawLine(margin_left, (float) (margin_top + height * (1.0f - (min + line_val / (max - min) * zoom))),
							margin_left + width, (float) (margin_top + height * (1.0f - (min + line_val / (max - min) * zoom))), textColor);
					for (double logLine : logLines)
						if (line_val + logLine <= max / zoom)
							canvas.drawLine(margin_left, (float) (margin_top + height * (1.0f - (min + (line_val + logLine) / (max - min)) * zoom)),
									margin_left + width, (float) (margin_top + height * (1.0f - (min + (line_val + logLine) / (max - min)) * zoom)), squareColor);
					line_val += 1.0;
				}
			} else if (dose_mode) {
				for (int i = 0; i < nx + 1; i++)
					canvas.drawLine(margin_left + i * dwx, margin_top,
							margin_left + i * dwx, viewHeight - margin_bottom, squareColor);
				for (int i = 0; i < ny + 1; i++)
					canvas.drawLine(margin_left, margin_top + i * dwy,
							margin_left + width, margin_top + i * dwy, squareColor);
			} else {
				long line_val = 0;
				long inc_line_val = decValue;
				long small_line_val;
				if ((max / zoom / decValue) <= 2 && (decValue >= 10)) {
					small_line_val = 1;
				} else if ((max / zoom / decValue) <= 4 && (decValue >= 10)) {
					small_line_val = 2;
				} else if ((max / zoom / decValue) <= 6 && (decValue >= 10)) {
					small_line_val = 5;
				} else if (decValue >= 10) {
					small_line_val = 10;
					inc_line_val = 2 * decValue;
				} else if ((max / zoom ) > 6) {
					small_line_val = 10;
					inc_line_val = 2 * decValue;
				} else {
					small_line_val = 10;
				}
				while (line_val < max / zoom) {
					canvas.drawLine(margin_left, (float) (margin_top + height * (1.0f - (min + line_val / (max - min) * zoom))),
							margin_left + width, (float) (margin_top + height * (1.0f - (min + line_val / (max - min) * zoom))), textColor);
					for (int i = 1; i * small_line_val / 10 < inc_line_val; i++) {
						double show_line = line_val + decValue * i * small_line_val / 10.0;
						if (show_line < max / zoom)
							canvas.drawLine(margin_left, (float) (margin_top + height * (1.0f - (min + show_line / (max - min)) * zoom)),
									margin_left + width, (float) (margin_top + height * (1.0f - (min + show_line / (max - min)) * zoom)), squareColor);
					}
					line_val += inc_line_val;
				}
			}
		} else {
			for (int i = 0; i < nx + 1; i++)
				canvas.drawLine(margin_left + i * dwx, margin_top,
						margin_left + i * dwx, viewHeight - margin_bottom, squareColor);
			for (int i = 0; i < ny + 1; i++)
				canvas.drawLine(margin_left, margin_top + i * dwy,
						margin_left + width, margin_top + i * dwy, squareColor);
		}

		String sZoom;
		if (no_y_mode) {
			if (zoom < 0.9)
				sZoom = String.format("%1.2f", zoom);
			else
				sZoom = String.format("%1.0f", zoom);
			if (Math.abs(zoom - 1) > 0.01) sZoom = ", \u00D7" + sZoom;
			else sZoom = "";
		} else {
			if (logScale) {
				sZoom = ", " + (AtomSpectraService.showCalibrationFunction ? res.getString(R.string.graph_show_kev) : res.getString(R.string.graph_show_cnt));
			} else if (dose_mode) {
				sZoom = ", " + (m_dose_mode ? res.getString(R.string.graph_show_mSv) : res.getString(R.string.graph_show_mkSv));
			} else {
				sZoom = ", " + (AtomSpectraService.showCalibrationFunction ? res.getString(R.string.graph_show_kev) : res.getString(R.string.graph_show_cnt));
			}
		}

		if (isInEditMode())
			return;

		if (!no_y_mode && !calibrationScale && AtomSpectraService.leftChannelInterval > 0 && AtomSpectraService.rightChannelInterval < Constants.NUM_HIST_POINTS - 1) {
			if (isCalibrated &&
					AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().toEnergy(AtomSpectraService.leftChannelInterval) < x_max_value  &&
					AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().toEnergy(AtomSpectraService.rightChannelInterval) > x_min_value) {
				squareColor.setColor(getResources().getColor(R.color.colorIntervalBackground));
				squareColor.setStyle(Style.FILL);
				canvas.drawRect(margin_left + (float)StrictMath.max(0, (AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().toEnergy(AtomSpectraService.leftChannelInterval) - x_min_value)/(x_max_value - x_min_value) * width), margin_top, margin_left + (float)StrictMath.min(width, (AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().toEnergy(AtomSpectraService.rightChannelInterval) - x_min_value)/(x_max_value - x_min_value) * width), margin_top + height - 1, squareColor);
			}
			if (!isCalibrated &&
					AtomSpectraService.leftChannelInterval < x_max_value  &&
					AtomSpectraService.rightChannelInterval > x_min_value) {
				squareColor.setColor(getResources().getColor(R.color.colorIntervalBackground));
				squareColor.setStyle(Style.FILL);
				canvas.drawRect(margin_left + StrictMath.max(0, (AtomSpectraService.leftChannelInterval - x_min_value)/(x_max_value - x_min_value) * width), margin_top, margin_left + StrictMath.min(width, (AtomSpectraService.rightChannelInterval - x_min_value)/(x_max_value - x_min_value) * width), margin_top + height - 1, squareColor);
			}
		}

		textColor.setColor(Color.WHITE);
		textColor.setTextSize(ht_px);

		if (N > 0) {
			if (!no_y_mode) {
				textColor.setTextAlign(Align.RIGHT);
				canvas.save();
				canvas.rotate(270);
				if (logScale) {
					float line_val = 0.0f;
					float delta_val = 1.0f + (int)(max / zoom / 6.0f);
					while (line_val <= max / zoom) {
						if (line_val < 6.1)
							canvas.drawText(String.format(Locale.getDefault(), "%.0f%s", StrictMath.pow(10, line_val), (line_val + 1 < max / zoom) ? "" : (sZoom)), (float) (margin_top - height * (1.0f - (min + line_val / (max - min)) * zoom) - 6 * ht_px / 2), margin_top - ht_px / 2, textColor);
						else
							canvas.drawText(String.format(Locale.getDefault(), "%.0e%s", StrictMath.pow(10, line_val), (line_val + 1 < max / zoom) ? "" : (sZoom)), (float) (margin_top - height * (1.0f - (min + line_val / (max - min)) * zoom) - 6 * ht_px / 2), margin_top - ht_px / 2, textColor);
						line_val += delta_val;
					}
				} else if (dose_mode) {
					for (int i = 0; i <= ny; i += 2) {
						if (i == 0) {
							if (min == 0) continue;
							textColor.setTextAlign(Align.LEFT);
						} else {
							textColor.setTextAlign(Align.RIGHT);
						}
						canvas.drawText(String.format(Locale.getDefault(), "%1.3f%s", (min + (max - min) * i / ny) / zoom, (i < ny) ? "" : (sZoom)), margin_top - height + i * dwy - 6 * ht_px / 2, margin_top - ht_px / 2, textColor);
					}
				} else {
					long line_val = 0;
					boolean big_ones = (max / zoom / decValue) > 6;
					if (big_ones)
						decValue *= 2;
					while (line_val <= max / zoom) {
						if (line_val < 1e6)
							canvas.drawText(String.format(Locale.getDefault(), "%.0f%s", (float)line_val, (line_val + decValue < max / zoom) ? "" : (sZoom)), (float) (margin_top - height * (1.0f - (min + line_val / (max - min)) * zoom) - 6 * ht_px / 2), margin_top - ht_px / 2, textColor);
						else
							canvas.drawText(String.format(Locale.getDefault(), "%.0e%s", (float)line_val, (line_val + decValue < max / zoom) ? "" : (sZoom)), (float) (margin_top - height * (1.0f - (min + line_val / (max - min)) * zoom) - 6 * ht_px / 2), margin_top - ht_px / 2, textColor);
						line_val += decValue;
					}
				}
				canvas.restore();
			}
			textColor.setTextAlign(Align.CENTER);
			for (int i = 0; i < nx + 1; i += 2) {
				if (i == nx) textColor.setTextAlign(Align.RIGHT);
				canvas.drawText(String.format(Locale.getDefault(), "%d%s", (int) ((x_max_value - x_min_value) * i / nx + x_min_value), (i < nx) ? "" : (measureUnits)), margin_left + i * dwx + (i == nx ? margin_right : 0), viewHeight - ht_px / 4, textColor);
			}

			//squareColor.setColor(Color.YELLOW);
			if (!no_y_mode && !dose_mode && !calibrationScale) { //draw isotope lines on main window
				float x_pos;
				for (int i = 0; i < AtomSpectraIsotopes.checkedIsotopeLine.length; i++) {
					if (AtomSpectraIsotopes.checkedIsotopeLine[i]) {
						if (isCalibrated) {
							x_pos = (float) AtomSpectraIsotopes.isotopeLineArray.get(i).getEnergy(0);
						} else { //in "ch."
							x_pos = (float) AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().toChannel(AtomSpectraIsotopes.isotopeLineArray.get(i).getEnergy(0));
						}
						if ((x_pos >= x_min_value) && (x_pos <= x_max_value )) {
							squareColor.setColor(AtomSpectraIsotopes.isotopeLineArray.get(i).getColor());
							canvas.drawLine(margin_left + ((x_pos - x_min_value) / (x_max_value - x_min_value)) * width, margin_top,
									margin_left + ((x_pos - x_min_value) / (x_max_value - x_min_value)) * width, margin_top + height, squareColor);
						}
					}
				}
				squareColor.setColor(getResources().getColor(R.color.colorFound));
				if (AtomSpectraIsotopes.showFoundIsotopes) {
					for (int i = 0; i < AtomSpectraIsotopes.foundList.size(); i++) {
						if (isCalibrated) {
							x_pos = (float) AtomSpectraIsotopes.foundList.get(i).getEnergy(0);
						} else { //in "ch."
							x_pos = (float) AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().toChannel(AtomSpectraIsotopes.foundList.get(i).getEnergy(0));
						}
						if ((x_pos >= x_min_value) && (x_pos <= x_max_value)) {
							//squareColor.setColor(Color.WHITE);
							canvas.drawLine(margin_left + ((x_pos - x_min_value) / (x_max_value - x_min_value)) * width, margin_top,
									margin_left + ((x_pos - x_min_value) / (x_max_value - x_min_value)) * width, margin_top + height, squareColor);
						}
					}
				}
			} // end of isotope lines


			if (!barMode) {
				if (calibrationScale) {
					squareColor.setColor(Color.WHITE);
					for (int i = 2; i < N; i++)
						if (X[i - 1] >= margin_left)
							canvas.drawLine(X[i - 1], Y[i - 1], X[i], Y[i], squareColor);
				} else if (AtomSpectraService.showDelta) {
					squareColor.setColor(Color.YELLOW);
					for (int i = 2; i < N; i++)
						if (X[i - 1] >= margin_left)
							canvas.drawLine(X[i - 1], Y[i - 1], X[i], Y[i], squareColor);
				}else if (background_delta && background_show) {
					squareColor.setColor(Color.CYAN);
					for (int i = 2; i < N; i++)
						if (X[i - 1] >= margin_left)
							canvas.drawLine(X[i - 1], Y[i - 1], X[i], Y[i], squareColor);
				} else {
					squareColor.setColor(Color.WHITE);
					for (int i = 2; i < N; i++)
						if (X[i - 1] >= margin_left)
							canvas.drawLine(X[i - 1], Y[i - 1], X[i], Y[i], squareColor);
					if (background_show) {
						squareColor.setColor(Color.GREEN);
						for (int i = 2; i < N; i++)
							if (X[i - 1] >= margin_left)
								canvas.drawLine(X[i - 1], back_Y[i - 1], X[i], back_Y[i], squareColor);
					}
				}
			} else {
				if (calibrationScale) {
					squareColor.setStyle(Style.FILL);
					int colorFrom = getResources().getColor(R.color.colorGradientMainFrom);//Color.YELLOW;
					int colorTo = getResources().getColor(R.color.colorGradientMainTo);
					LinearGradient linearGradientShader = new LinearGradient(margin_left, margin_top, margin_left + width, margin_top, colorFrom, colorTo, TileMode.CLAMP);
					squareColor.setShader(linearGradientShader);
					for (int i = 2; i < N; i++)
						if (X[i - 1] >= margin_left) {
							canvas.drawRect(X[i], Y[i - 1], X[i - 1], margin_top + height, squareColor);
						}
					squareColor.setShader(null);
				} else if (AtomSpectraService.showDelta) {
					squareColor.setStyle(Style.FILL);
					int colorFrom = getResources().getColor(R.color.colorGradientMainFrom);//Color.YELLOW;
					int colorTo = getResources().getColor(R.color.colorGradientMainTo);
					LinearGradient linearGradientShader = new LinearGradient(margin_left, margin_top, margin_left + width, margin_top, colorFrom, colorTo, TileMode.CLAMP);
					squareColor.setShader(linearGradientShader);
					for (int i = 2; i < N; i++)
						if (X[i - 1] >= margin_left) {
							canvas.drawRect(X[i], Y[i - 1], X[i - 1], margin_top + height, squareColor);
						}
					squareColor.setShader(null);
				}else if (background_delta && background_show) {
					squareColor.setStyle(Style.FILL);
					int colorFrom = getResources().getColor(R.color.colorGradientDeltaFrom);//Color.YELLOW;
					int colorTo = getResources().getColor(R.color.colorGradientDeltaTo);
					LinearGradient linearGradientShader = new LinearGradient(margin_left, margin_top, margin_left + width, margin_top, colorFrom, colorTo, TileMode.CLAMP);
					squareColor.setShader(linearGradientShader);
					for (int i = 2; i < N; i++)
						if (X[i - 1] >= margin_left) {
							canvas.drawRect(X[i], Y[i - 1], X[i - 1], margin_top + height, squareColor);
						}
					squareColor.setShader(null);
				} else {
					squareColor.setStyle(Style.FILL);
					int colorFrom = getResources().getColor(R.color.colorGradientMainFrom);//Color.YELLOW;
					int colorTo = getResources().getColor(R.color.colorGradientMainTo);
					LinearGradient linearGradientShader = new LinearGradient(margin_left, margin_top, margin_left + width, margin_top, colorFrom, colorTo, TileMode.CLAMP);
					squareColor.setShader(linearGradientShader);
					for (int i = 2; i < N; i++)
						if (X[i - 1] >= margin_left) {
							canvas.drawRect(X[i], Y[i - 1], X[i - 1], margin_top + height, squareColor);
						}
					squareColor.setShader(null);
					if (background_show) {
						squareColor.setStyle(Style.FILL);
						colorFrom = getResources().getColor(R.color.colorGradientBackFrom);//Color.YELLOW;
						colorTo = getResources().getColor(R.color.colorGradientBackTo);
						linearGradientShader = new LinearGradient(margin_left, margin_top, margin_left + width, margin_top, colorFrom, colorTo, TileMode.CLAMP);
						squareColor.setShader(linearGradientShader);
						for (int i = 2; i < N; i++)
							if (X[i - 1] >= margin_left) {
								canvas.drawRect(X[i], back_Y[i - 1], X[i - 1], margin_top + height, squareColor);
							}
						squareColor.setShader(null);
					}
				}
			}

			LinkedList<Float> lastX = new LinkedList<>();
			LinkedList<Float> lastXEnd = new LinkedList<>();
			LinkedList<Float> lastY = new LinkedList<>();
			LinkedList<Float> lastYEnd = new LinkedList<>();
			textColor.setTextSize(scaleText * ht_px);
			textColor.getTextBounds(sZoom, 0, sZoom.length(), rect);
			textColor.setTextSize(ht_px);
//			if (AtomSpectra.showScaleLabel && !calibrationScale) {
//				lastX.add(margin_left + 1.0f);
//				lastXEnd.add(margin_left + rect.width() + ht_px + 1);
//				lastY.add(margin_top + 1 + ht_px * 2);
//				lastYEnd.add(margin_top + 2 * rect.height() + 1 + ht_px * 2);
//			} else {
				lastX.add(-1.0f);
				lastXEnd.add(-1.0f);
				lastY.add(-1.0f);
				lastYEnd.add(-1.0f);
//			}
			float posYMax = margin_top;
			textColor.getTextBounds("Cg-888", 0, 6, rect);
			float rectHeight = rect.height() + 2;
			if (!no_y_mode && !dose_mode && !calibrationScale) { //draw isotope labels on main window
				float x_pos;
				String isotopeLabel;
				textColor.setTextAlign(Align.LEFT);
				//textColor.setColor(Color.YELLOW);
				for (int i = 0; i < AtomSpectraIsotopes.checkedIsotopeLine.length; i++) {
					if (AtomSpectraIsotopes.checkedIsotopeLine[i]) {
						if (isCalibrated) {
							x_pos = (float) AtomSpectraIsotopes.isotopeLineArray.get(i).getEnergy(0);
							isotopeLabel = String.format("%.2f", x_pos);
						} else { //in "ch."
							x_pos = (float) AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().toChannel(AtomSpectraIsotopes.isotopeLineArray.get(i).getEnergy(0));
							isotopeLabel = AtomSpectraIsotopes.isotopeLineArray.get(i).getName();
						}
						textColor.setColor(AtomSpectraIsotopes.isotopeLineArray.get(i).getColor());
						textColor.getTextBounds(isotopeLabel, 0, isotopeLabel.length(), rect);
						float rectWidth = rect.width() + 2;
						if ((x_pos >= x_min_value) && (x_pos <= x_max_value)) {
							float posX = margin_left + ((x_pos - x_min_value) / (x_max_value - x_min_value)) * width;
							float posY = margin_top;
							if (x_pos > ((x_max_value + x_min_value) / 2)) {
								posX = posX - rectWidth;
							}
							for (int posI = 0; posI < lastX.size(); posI++) {
								float tempX = lastX.get(posI);
								float tempXEnd = lastXEnd.get(posI);
								float tempY = lastY.get(posI);
								float tempYEnd = lastYEnd.get(posI);
								if (tempXEnd < posX)
									continue;
								if (tempX > (posX + rectWidth))
									continue;
								if (((tempY <= posY && tempYEnd >= posY) || (tempY <= (posY + rectHeight) && tempYEnd >= (posY + rectHeight))) &&
										((tempX <= posX && tempXEnd >= posX) || (tempX <= (posX + rectWidth) && tempXEnd >= (posX + rectWidth)) ||
												(tempX >= posX && tempXEnd <= (posX + rectWidth)))) {
									posY = posY + rectHeight + 1;
									if (posYMax <= (posY + rectHeight)) posYMax = posY + rectHeight + 1;
									posI = -1;
								}
								if (posY > (viewHeight / 2.0))
									break;
							}
							if (posYMax <= (posY + rectHeight)) posYMax = posY + rectHeight + 1;
							lastX.add(posX);
							lastXEnd.add(posX + rectWidth);
							lastY.add(posY);
							lastYEnd.add(posY + rectHeight);
							AtomSpectraIsotopes.isotopeLineArray.get(i).setCoord(posX, posY, posX + rectWidth, posY + rectHeight);
							squareColor.setColor(Color.BLACK);
							squareColor.setStyle(Style.FILL);
							canvas.drawRect(posX, posY,
									posX + rectWidth, posY + rectHeight, squareColor);
							squareColor.setColor(Color.GRAY);
							squareColor.setStyle(Style.STROKE);
							canvas.drawRect(posX, posY,
									posX + rectWidth, posY + rectHeight, squareColor);
							canvas.drawText(isotopeLabel, posX + 1, posY + (int) (ht_px / 1.4) + 3, textColor);
						} else {
							AtomSpectraIsotopes.isotopeLineArray.get(i).setCoord(null);
						}
					} else {
						AtomSpectraIsotopes.isotopeLineArray.get(i).setCoord(null);
					}
				}
				if (AtomSpectraIsotopes.showFoundIsotopes) {
					textColor.setColor(Isotope.getColorForFound());
					for (int i = 0; i < AtomSpectraIsotopes.foundList.size(); i++) {
						if (isCalibrated) {
							x_pos = (float) AtomSpectraIsotopes.foundList.get(i).getEnergy(0);
							isotopeLabel = String.format("%.2f", x_pos);
						} else { //in "ch."
							x_pos = (float) AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().toChannel(AtomSpectraIsotopes.foundList.get(i).getEnergy(0));
							isotopeLabel = AtomSpectraIsotopes.foundList.get(i).getName();
						}
						textColor.getTextBounds(isotopeLabel, 0, isotopeLabel.length(), rect);
						float rectWidth = rect.width() + 2;
						if ((x_pos >= x_min_value) && (x_pos <= x_max_value) && !calibrationScale) {
							float posX = margin_left + ((x_pos - x_min_value) / (x_max_value - x_min_value)) * width;
							float posY = margin_top;
							if (x_pos > ((x_max_value + x_min_value) / 2)) {
								posX = posX - rectWidth;
							}
							for (int posI = 0; posI < lastX.size(); posI++) {
								float tempX = lastX.get(posI);
								float tempXEnd = lastXEnd.get(posI);
								float tempY = lastY.get(posI);
								float tempYEnd = lastYEnd.get(posI);
								if (tempXEnd < posX)
									continue;
								if (tempX > (posX + rectWidth))
									continue;
								if (((tempY <= posY && tempYEnd >= posY) || (tempY <= (posY + rectHeight) && tempYEnd >= (posY + rectHeight))) &&
										((tempX <= posX && tempXEnd >= posX) || (tempX <= (posX + rectWidth) && tempXEnd >= (posX + rectWidth)) ||
												(tempX >= posX && tempXEnd <= (posX + rectWidth)))) {
									posY = posY + rectHeight + 1;
									if (posYMax <= (posY + rectHeight)) posYMax = posY + rectHeight + 1;
									posI = -1;
								}
								if (posY > (viewHeight / 2.0))
									break;
							}
							if (posYMax <= (posY + rectHeight)) posYMax = posY + rectHeight + 1;
							lastX.add(posX);
							lastXEnd.add(posX + rectWidth);
							lastY.add(posY);
							lastYEnd.add(posY + rectHeight);
							AtomSpectraIsotopes.foundList.get(i).setCoord(posX, posY, posX + rectWidth, posY + rectHeight);
							squareColor.setColor(Color.BLACK);
							squareColor.setStyle(Style.FILL);
							canvas.drawRect(posX, posY,
									posX + rectWidth, posY + rectHeight, squareColor);
							squareColor.setColor(Color.GRAY);
							squareColor.setStyle(Style.STROKE);
							canvas.drawRect(posX, posY,
									posX + rectWidth, posY + rectHeight, squareColor);
							canvas.drawText(isotopeLabel, posX + 1, posY + (int) (ht_px / 1.4) + 3, textColor);
						} else {
							AtomSpectraIsotopes.foundList.get(i).setCoord(null);
						}
					}
				}
			} else {
				for (Isotope isotope: AtomSpectraIsotopes.isotopeLineArray) {
					isotope.setCoord(null);
				}
				for (Isotope isotope: AtomSpectraIsotopes.foundList) {
					isotope.setCoord(null);
				}
			}
			//end of isotope labels

			squareColor.setColor(Color.GREEN);
			if ((cursor_X < x_max_value) && (cursor_X > x_min_value)) {
				canvas.drawLine(margin_left + ((cursor_X - x_min_value) / (x_max_value - x_min_value)) * width, margin_top,
						margin_left + ((cursor_X - x_min_value) / (x_max_value - x_min_value)) * width, margin_top + height, squareColor);
				if (isotopeFound >= 0) {
					textColor.getTextBounds(isotopeLabel, 0, isotopeLabel.length(), rect);
					textColor.setColor(Color.GREEN);
					textColor.setTextAlign(Align.LEFT);
					if ((posYMax >= lastY.get(0) && posYMax <= lastYEnd.get(0)) || ((posYMax + rectHeight) >= lastY.get(0) && (posYMax + rectHeight) <= lastYEnd.get(0))) {
						posYMax = lastYEnd.get(0) + 1;
					}
					if (cursor_X > ((x_max_value + x_min_value) / 2)) {
						squareColor.setColor(Color.BLACK);
						squareColor.setStyle(Style.FILL);
						canvas.drawRect(margin_left + ((cursor_X - x_min_value) / (x_max_value - x_min_value)) * width - rect.width() - 2, posYMax,
								margin_left + ((cursor_X - x_min_value) / (x_max_value - x_min_value)) * width, posYMax + rectHeight, squareColor);
						squareColor.setColor(Color.GREEN);
						squareColor.setStyle(Style.STROKE);
						canvas.drawRect(margin_left + ((cursor_X - x_min_value) / (x_max_value - x_min_value)) * width - rect.width() - 2, posYMax,
								margin_left + ((cursor_X - x_min_value) / (x_max_value - x_min_value)) * width, posYMax + rectHeight, squareColor);
						canvas.drawText(isotopeLabel, margin_left + ((cursor_X - x_min_value) / (x_max_value - x_min_value)) * width - rect.width() - 1, posYMax + (int) (ht_px / 1.4) + 3, textColor);
					} else {
						squareColor.setColor(Color.BLACK);
						squareColor.setStyle(Style.FILL);
						canvas.drawRect(margin_left + ((cursor_X - x_min_value) / (x_max_value - x_min_value)) * width, posYMax,
								margin_left + ((cursor_X - x_min_value) / (x_max_value - x_min_value)) * width + rect.width() + 2, posYMax + rectHeight, squareColor);
						squareColor.setColor(Color.GREEN);
						squareColor.setStyle(Style.STROKE);
						canvas.drawRect(margin_left + ((cursor_X - x_min_value) / (x_max_value - x_min_value)) * width, posYMax,
								margin_left + ((cursor_X - x_min_value) / (x_max_value - x_min_value)) * width + rect.width() + 2, posYMax + rectHeight, squareColor);
						canvas.drawText(isotopeLabel, margin_left + ((cursor_X - x_min_value) / (x_max_value - x_min_value)) * width + 1, posYMax + (int) (ht_px / 1.4) + 3, textColor);
					}
				}
			}

			if (calibrationScale) {
				squareColor.setColor(Color.GREEN);
				squareColor.setStyle(Style.FILL);
				float radius = (float)StrictMath.min(width, height) / 75.0f;
				for (int i = 0; i < AtomSpectraService.newCalibration.getLines(); i++) {
					float y = (float) (margin_top + (1 - StrictMath.min(1, (AtomSpectraService.newCalibration.getEnergy(i) - min) / (max - min) * zoom)) * height);
					float x = margin_left + ((AtomSpectraService.newCalibration.getChannel(i) - x_min_value) / (x_max_value - x_min_value)) * width;
					if (x >= margin_left && x <= (margin_left + width) && y >= margin_top && y <= (margin_top + height))
						canvas.drawCircle(x, y, radius, squareColor);
				}
			}

			if (circleMode) {
				squareColor.setStyle(Style.FILL);
				for (int i = 0; i < N; i++) {
					squareColor.setColor(Color.RED);
					if ((i > 127) && (i <= 127 + frontCountsMin)) {
						squareColor.setColor(Color.YELLOW);
						canvas.drawCircle(X[i], Y[i], (float)width / 100, squareColor);
					}
					if ((i > 127 + frontCountsMin) && (i <= 127 + frontCountsMax)) {
						squareColor.setColor(Color.GREEN);
						canvas.drawCircle(X[i], Y[i], (float)width / 100, squareColor);
					}
					//canvas.drawCircle(X[i], Y[i], height/100, squareColor);
				}
			}
		}
	}

	public void showShape(
			double[] y, // array to draw
			double[] back,  //background drawing
			boolean show_back, //show background
			boolean show_delta, //subtract background from main hist
			boolean calibrated, //if show energies
			int size, // size of the array to draw
			int xSize, // number of abscissa point to be drawn
			boolean logarithmic, // Y-scale type, linear or logarithmic
			boolean bar_mode, // how to draw, lines or bars
			float xMin, // minimum value for X-scale
			float xMax, // maximum value for X-scale
			String units, // measurement units, "ch." or "keV", also probably "eV", "MeV"
			float zoom_factor,
			int xZoom_factor,
			boolean no_y,
			float cursor_positionX, // xMin..xMax, disabled when outside this interval
			int front_min,
			int front_max
	) {
		if (size <= 0) return;
		cursor_X = cursor_positionX;
		x_max_value = xMax;
		x_min_value = xMin;
		no_y_mode = no_y;
		logScale = logarithmic;
		barMode = bar_mode;
		background_show = show_back;
		background_delta = show_delta;
		isCalibrated = calibrated;
		frontCountsMin = front_min;
		frontCountsMax = front_max;
		calibrationScale = AtomSpectraService.showCalibrationFunction && xZoom_factor < Constants.SCALE_COUNT_MODE;
		if (xZoom_factor == Constants.SCALE_DOSE_MODE) {
			for (int i = 0; i < size; i++) {
				yf[i] = y[i] * Constants.DOSE_SCALE;
			}
		} else {
			System.arraycopy(y, 0, yf, 0, size);
		}
		if (show_back) {
			if (show_delta) {   //remove background from main hist
				for (int i = 0; i < size; i++) {
					back_yf[i] = 0.0;
					yf[i] = Math.max(0.0, yf[i] - back[i]);
				}
			} else {
				System.arraycopy(back, 0, back_yf, 0, size);
			}
		}
		if (xZoom_factor == Constants.SCALE_COUNT_MODE) {
			xSize = size;
		}

		int step = size / xSize;
		if ((step * xSize) != size) return;
		measureUnits = ", " + units;
		zoom = zoom_factor;
		xScale = xZoom_factor;

		double xZoom;
		if (xZoom_factor < Constants.SCALE_MAX) xZoom = (1 << xZoom_factor) / 2.0;
		else if (xZoom_factor == Constants.SCALE_MAX) xZoom = (1 << 6) / 2.0;
		else xZoom = 1;

		if (xZoom_factor == Constants.SCALE_IMPULSE_MODE) {
			dose_mode = false;
			circleMode = true;
			max = 1;
			min = 0;//(long)(alarm_level*1.2);
			//max=min=y[0]-32768;max++;
			for (int i = 0; i < 256; i++) {
				double r;
				//for (int j=0; j<step;j++)  r += y[i*step+j];
				r = yf[i];//-32768;
				if (yf[i] > max) max = r;
				if (yf[i] < min) min = r;
				if (i > 0) tmp[256 - i] = r;

			}
			tmp[0] = tmp[1];
		} else {
			circleMode = false;
			if (xZoom_factor == Constants.SCALE_DOSE_MODE) {
				dose_mode = true;
				max = Constants.DOSE_SCALE * 100 / Constants.DOSE_OVERHEAD;
			} else {
				dose_mode = false;
				if (logScale)
					max = 0;
				else
					max = 1;
			}
			min = 0;
			for (int i = 0; i < xSize; i++) {
				double r = 0;
				double back_r = 0;
				for (int j = 0; j < step; j++) /*if ((i*step+j)>=0) if ((i*step+j)<1024) */
					r += yf[i * step + j];
				if (dose_mode)
					r /= step;
				else if (step > 1 && xZoom_factor == Constants.SCALE_MAX)
					r /= 2;
				if (show_back)
					for (int j = 0; j < step; j++) /*if ((i*step+j)>=0) if ((i*step+j)<1024) */
						back_r += back_yf[i * step + j];
				//if (r>max) max=r;
				if (xZoom_factor >= Constants.SCALE_COUNT_MODE) r -= (Constants.NUM_HIST_POINTS / 2.0);
				if (logScale) {
					if (r > 1) tmp[xSize - 1 - i] = Math.log10(r);
					else tmp[xSize - 1 - i] = 0;
				} else tmp[xSize - 1 - i] = r;
				if (show_back) if (logScale) {
					if (back_r > 1) back_tmp[xSize - 1 - i] = Math.log10(back_r);
					else back_tmp[xSize - 1 - i] = 0;
				} else back_tmp[xSize - 1 - i] = back_r;
			}
			for (int i = 0; i < xSize; i++) {
				if (tmp[i] > max) max = tmp[i];
				if (tmp[i] < min || (i ==0 && calibrationScale)) min = tmp[i];
			}
			if (calibrationScale) {
				for (int i = 0; i < AtomSpectraService.newCalibration.getLines(); i++) {
					min = StrictMath.min(min, AtomSpectraService.newCalibration.getEnergy(i));
					max = StrictMath.max(max, AtomSpectraService.newCalibration.getEnergy(i));
				}
				max = StrictMath.max(max, min + 1);
			}
			if (dose_mode) {
				max *= Constants.DOSE_OVERHEAD;
				if (max > 1000) {
					m_dose_mode = true;
					max /= 1000;
					for (int i = 0; i < xSize; i++) {
						tmp[i] /= 1000.0;
					}
				} else
					m_dose_mode = false;
			}
			if (show_back)
				for (int i = 0; i < xSize; i++)
					if (back_tmp[i] > max) max = back_tmp[i];  //use one scale for both histograms
		}

		if (xZoom_factor == Constants.SCALE_COUNT_MODE) {
			max = Constants.NUM_HIST_POINTS / 2.0 - 1;
			min = -(Constants.NUM_HIST_POINTS / 2.0);
		}

		if (xZoom_factor == Constants.SCALE_IMPULSE_MODE) {
			for (int i = 0; i < 256; i++) {
				double rr = (tmp[i] - min) * zoom_factor / (max - min);
				if (rr > 1) rr = 1;
				Y[i] = margin_top + height - (int) ((height - 2) * rr) - 1;
				X[i] = (int) (margin_left + width - (4.0 * (width - 2) * (i) / (size)) - 1);
			}
			N = 256;
		} else {
			for (int i = 0; i < xSize; i++) {
				double rr;
				rr = (tmp[i] - min) * zoom_factor / (max - min);

				if (rr > 1) rr = 1;
				Y[i] = margin_top + height - (int) ((height - 2) * rr) - 1;
				if (show_back) {
					rr = (back_tmp[i] - min) * zoom_factor / (max - min);
					if (rr > 1) rr = 1;
					back_Y[i] = margin_top + height - (int) ((height - 2) * rr) - 1;
				}

				if (xZoom_factor == Constants.SCALE_COUNT_MODE) {
					if (rr > 0.5) rr = 0.5;
					Y[i] = margin_top + height / 2 - (int) ((height - 2) * rr) - 1;
				}

				X[i] = margin_left + width - (int)((double)step * (width - 2) * (i) / (size)) - 1;
			}
			N = xSize;
		}
		isotopeFound = -1;
		double cursor_X_Energy; //use energy to search the nearest isotope
		if (isCalibrated)
			cursor_X_Energy = cursor_X;
		else
			cursor_X_Energy = AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().toEnergy((int) cursor_X);
		double deltaEnergy = cursor_X_Energy;
//		int importance = 0;
		for (int i = 0; i < AtomSpectraIsotopes.isotopeLineArray.size(); i++) {
			if (AtomSpectraIsotopes.isotopeLibrary > 0 && !AtomSpectraIsotopes.IAEAList[AtomSpectraIsotopes.isotopeLibrary - 1].isInChain(AtomSpectraIsotopes.isotopeLineArray.get(i).getName()))
				continue;
			if (Math.abs(AtomSpectraIsotopes.isotopeLineArray.get(i).getEnergy(0) - cursor_X_Energy) < (CURSOR_INEQUALITY * (5000.0 + (AtomSpectraIsotopes.isotopeLibrary > 1? 2000.0:0.0)) / xZoom) /*cursor_X_Energy*/) {
//			if (Math.abs(AtomSpectraIsotopes.isotopeLineArray[i].getEnergy() - cursor_X_Energy) < CURSOR_INEQUALITY * cursor_X_Energy) {
//				if (importance < AtomSpectraIsotopes.isotopeLineArray[i].getImportance()) {
//					importance = AtomSpectraIsotopes.isotopeLineArray[i].getImportance();
//					deltaEnergy = (float) Math.abs(AtomSpectraIsotopes.isotopeLineArray[i].getEnergy() - cursor_X_Energy);
//					isotopeFound = i;
//					isotopeLabel = AtomSpectraIsotopes.isotopeLineArray[i].getName();
//				} else if (importance == AtomSpectraIsotopes.isotopeLineArray[i].getImportance()) {
					if (Math.abs(AtomSpectraIsotopes.isotopeLineArray.get(i).getEnergy(0) - cursor_X_Energy) < deltaEnergy) {
//						importance = AtomSpectraIsotopes.isotopeLineArray[i].getImportance();
						deltaEnergy = (float) Math.abs(AtomSpectraIsotopes.isotopeLineArray.get(i).getEnergy(0) - cursor_X_Energy);
						isotopeFound = i;
						isotopeLabel = AtomSpectraIsotopes.isotopeLineArray.get(i).getName();
					}
//				}
			}
		}
		invalidate();
	}

	/**
	 * Return date in specified format.
	 *
	 * @param milliSeconds Date in milliseconds
	 * @param dateFormat   Date format
	 * @return String representing date in specified format
	 */
	@SuppressLint("SimpleDateFormat")
	public static String getDate(long milliSeconds, String dateFormat) {
		// Create a DateFormatter object for displaying date in specified format.
		SimpleDateFormat formatter = new SimpleDateFormat(dateFormat);

		// Create a calendar object that will convert the date and time value in milliseconds to date.
		Calendar calendar = Calendar.getInstance();
		calendar.setTimeInMillis(milliSeconds);
		return formatter.format(calendar.getTime());
	}

}

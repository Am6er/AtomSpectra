package org.fe57.atomspectra;

import java.util.ArrayList;
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
import android.os.Build;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.NonNull;

@SuppressLint({ "DefaultLocale", "DrawAllocation" })
public class AtomSpectraShapeView extends View {
	private final Object renderSync = new Object();
	private final int RENDER_MODE_SPECTRUM = 0;
	private final int RENDER_MODE_SEARCH = 1;
	private final int RENDER_MODE_CALIBRATION = 2;
	private final int RENDER_MODE_OSCILLOSCOPE = 3;
	private final int RENDER_MODE_REFERENCE_PULSE = 4;

	private int render_mode = RENDER_MODE_SPECTRUM;
	private Shape[] shapes = new Shape[0];

	private static int margin_top;
	private static int margin_bottom;
	private static int margin_left;
	private static int margin_right;
	private static int width;
	private static int height;

	private final Paint squareColor = new Paint();
	private final Paint textColor = new Paint();
	private final Rect rect = new Rect();

	private boolean m_dose_mode = false;
	private boolean is_interval_search = false;

	private boolean logScale = false;
	private final double minLogValue = 0.9;

	private static final float scaleText = 1.61803398875f * 1.1f;                                                                //scale text on graph

	private int frontCountsMin = 4, frontCountsMax = 8;

	private float y_zoom = 1.0f;

	private String x_units = "";
	private boolean x_is_calibrated = true;
	private static float x_max_value = 1.0f;
	private static float x_min_value = 0.0f;

	private float cursor_X = -1;
	public static int isotopeFound = -1;
	private String isotopeLabel = null;

	private double y_max, y_min;

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

	public static double X2scale(double X) {
		return (x_max_value - x_min_value) * (X - margin_left) / width + x_min_value;
	}

	public static boolean isOutOfFrame(float x) {
		return (x < margin_left) || (x > (margin_left + width));
	}

	@SuppressLint("DefaultLocale")
	@Override
	protected void onDraw(Canvas canvas) {
		Resources res = getResources();
		synchronized (renderSync) {
			canvas.drawColor(Color.BLACK);

			int viewWidth = getWidth();
			int viewHeight = getHeight();

			int ht = 12;
			float ht_px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, ht, getResources().getDisplayMetrics());
			margin_top = (int) (2 * ht_px);
			margin_bottom = (int) (2 * ht_px);
			margin_left = (int) (2 * ht_px);
			margin_right = (int) (2 * ht_px);
			width = viewWidth - margin_left - margin_right;
			height = viewHeight - margin_top - margin_bottom;
			int nx;
			int ny;

			// grid lines count
			if (this.render_mode == RENDER_MODE_OSCILLOSCOPE || this.render_mode == RENDER_MODE_REFERENCE_PULSE) {
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
			int decPower = 0;
			decValue = (long) (y_max / y_zoom);
			while (decValue > 10) {
				decValue /= 10;
				decPower++;
			}
			decValue = 1;
			int shift = decPower;
			while (shift > 0) {
				decValue *= 10;
				shift--;
			}

			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
				squareColor.setColor(getResources().getColor(R.color.colorStrokes, null));
			} else {
				squareColor.setColor(getResources().getColor(R.color.colorStrokes));
			}
			squareColor.setStrokeWidth(2);
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
				textColor.setColor(getResources().getColor(R.color.colorStrokes2, null));
			} else {
				textColor.setColor(getResources().getColor(R.color.colorStrokes2));
			}
			textColor.setStrokeWidth(2);

			// select units
			String yUnits = "";
			if (this.render_mode == RENDER_MODE_OSCILLOSCOPE || this.render_mode == RENDER_MODE_REFERENCE_PULSE) {
				if (y_zoom < 0.9)
					yUnits = String.format("%1.2f", y_zoom);
				else
					yUnits = String.format("%1.0f", y_zoom);
				if (Math.abs(y_zoom - 1) > 0.01) yUnits = ", \u00D7" + yUnits;
				else yUnits = "";
			}
			if (this.render_mode == RENDER_MODE_SPECTRUM) {
				yUnits = ", " + res.getString(R.string.graph_show_cnt);
			}
			if (this.render_mode == RENDER_MODE_CALIBRATION) {
				yUnits = ", " + res.getString(R.string.graph_show_kev);
			}
			if (this.render_mode == RENDER_MODE_SEARCH) {
				String unit;
				if (m_dose_mode) {
					if (is_interval_search) {
						unit = res.getString(R.string.graph_show_kcps);
					} else {
						unit = res.getString(R.string.graph_show_mSv);
					}
				} else {
					if (is_interval_search) {
						unit = res.getString(R.string.graph_show_cps);
					} else {
						unit = res.getString(R.string.graph_show_mkSv);
					}
				}
				yUnits = ", " + unit;
			}

			// draw grid lines
			// bounds(?)
			canvas.drawLine(margin_left, margin_top,
					margin_left + width, margin_top, squareColor);
			canvas.drawLine(margin_left, margin_top + height,
					margin_left + width, margin_top + height, squareColor);

			// vertical(?) lines for everything
			for (int i = 0; i < nx + 1; i++)
				canvas.drawLine(margin_left + i * dwx, margin_top,
						margin_left + i * dwx, viewHeight - margin_bottom, squareColor);

			// horizontal lines
			if (this.render_mode == RENDER_MODE_SPECTRUM) {
				// spectrum
				if (logScale) {
					float line_val = 0.0f;
					while (line_val <= y_max / y_zoom) {
						canvas.drawLine(margin_left, (float) (margin_top + height * (1.0f - ((line_val - y_min) / (y_max - y_min) * y_zoom))),
								margin_left + width, (float) (margin_top + height * (1.0f - ((line_val - y_min) / (y_max - y_min) * y_zoom))), textColor);
						for (double logLine : logLines)
							if (line_val + logLine <= y_max / y_zoom)
								canvas.drawLine(margin_left, (float) (margin_top + height * (1.0f - ((line_val - y_min + logLine) / (y_max - y_min)) * y_zoom)),
										margin_left + width, (float) (margin_top + height * (1.0f - ((line_val - y_min + logLine) / (y_max - y_min)) * y_zoom)), squareColor);
						line_val += 1.0f;
					}
				} else {
					long line_val = 0;
					long inc_line_val = decValue;
					long small_line_val;
					if ((y_max / y_zoom / decValue) <= 2 && (decValue >= 10)) {
						small_line_val = 1;
					} else if ((y_max / y_zoom / decValue) <= 4 && (decValue >= 10)) {
						small_line_val = 2;
					} else if ((y_max / y_zoom / decValue) <= 6 && (decValue >= 10)) {
						small_line_val = 5;
					} else if (decValue >= 10) {
						small_line_val = 10;
						inc_line_val = 2 * decValue;
					} else if ((y_max / y_zoom) > 6) {
						small_line_val = 10;
						inc_line_val = 2 * decValue;
					} else {
						small_line_val = 10;
					}
					while (line_val < y_max / y_zoom) {
						canvas.drawLine(margin_left, (float) (margin_top + height * (1.0f - (y_min + line_val / (y_max - y_min) * y_zoom))),
								margin_left + width, (float) (margin_top + height * (1.0f - (y_min + line_val / (y_max - y_min) * y_zoom))), textColor);
						for (int i = 1; i * small_line_val / 10 < inc_line_val; i++) {
							double show_line = line_val + decValue * i * small_line_val / 10.0;
							if (show_line < y_max / y_zoom)
								canvas.drawLine(margin_left, (float) (margin_top + height * (1.0f - (y_min + show_line / (y_max - y_min)) * y_zoom)),
										margin_left + width, (float) (margin_top + height * (1.0f - (y_min + show_line / (y_max - y_min)) * y_zoom)), squareColor);
						}
						line_val += inc_line_val;
					}
				}
			} else {
				// everything except spectrum
				for (int i = 0; i < ny + 1; i++)
					canvas.drawLine(margin_left, margin_top + i * dwy,
							margin_left + width, margin_top + i * dwy, squareColor);
			}

			// render selected interval
			if (this.render_mode == RENDER_MODE_SPECTRUM && AtomSpectraService.leftChannelInterval > 0 && AtomSpectraService.rightChannelInterval < Constants.NUM_HIST_POINTS - 1) {
				if (x_is_calibrated &&
						AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().toEnergy(AtomSpectraService.leftChannelInterval) < x_max_value &&
						AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().toEnergy(AtomSpectraService.rightChannelInterval) > x_min_value) {
					if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
						squareColor.setColor(getResources().getColor(R.color.colorIntervalBackground, null));
					} else {
						squareColor.setColor(getResources().getColor(R.color.colorIntervalBackground));
					}
					squareColor.setStyle(Style.FILL);
					canvas.drawRect(margin_left + (float) StrictMath.max(0, (AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().toEnergy(AtomSpectraService.leftChannelInterval) - x_min_value) / (x_max_value - x_min_value) * width), margin_top, margin_left + (float) StrictMath.min(width, (AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().toEnergy(AtomSpectraService.rightChannelInterval) - x_min_value) / (x_max_value - x_min_value) * width), margin_top + height - 1, squareColor);
				}
				if (!x_is_calibrated &&
						AtomSpectraService.leftChannelInterval < x_max_value &&
						AtomSpectraService.rightChannelInterval > x_min_value) {
					if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
						squareColor.setColor(getResources().getColor(R.color.colorIntervalBackground, null));
					} else {
						squareColor.setColor(getResources().getColor(R.color.colorIntervalBackground));
					}
					squareColor.setStyle(Style.FILL);
					canvas.drawRect(margin_left + StrictMath.max(0, (AtomSpectraService.leftChannelInterval - x_min_value) / (x_max_value - x_min_value) * width), margin_top, margin_left + StrictMath.min(width, (AtomSpectraService.rightChannelInterval - x_min_value) / (x_max_value - x_min_value) * width), margin_top + height - 1, squareColor);
				}
			}

			textColor.setColor(Color.WHITE);
			textColor.setTextSize(ht_px);

			if (this.shapes.length > 0) {
				canvas.save();
				canvas.rotate(270);
				// y axis labels for spectrum
				if (this.render_mode == RENDER_MODE_SPECTRUM) {
					textColor.setTextAlign(Align.RIGHT);
					if (logScale) {
						float line_val = 0.0f;
						float delta_val = 1.0f + (int) (y_max / y_zoom / 6.0f);
						while (line_val <= y_max / y_zoom) {
							canvas.drawText(String.format(Locale.getDefault(), "1·10%s%s", Constants.getPower(StrictMath.round(line_val)), (line_val + 1 < y_max / y_zoom) ? "" : (yUnits)), (float) (margin_top - height * (1.0f - ((line_val - y_min) / (y_max - y_min)) * y_zoom) - 6 * ht_px / 2), margin_top - ht_px / 2, textColor);
							line_val += delta_val;
						}
					} else {
						long line_val = 0;
						boolean big_ones = (y_max / y_zoom / decValue) > 6;
						if (big_ones) {
							decValue *= 2;
						}
						while (line_val <= y_max / y_zoom) {
							canvas.drawText(String.format(Locale.getDefault(), "%s%s", Constants.numberToPower(line_val), (line_val + decValue < y_max / y_zoom) ? "" : (yUnits)), (float) (margin_top - height * (1.0f - (y_min + line_val / (y_max - y_min)) * y_zoom) - 6 * ht_px / 2), margin_top - ht_px / 2, textColor);
							line_val += decValue;
						}
					}
				}

				// search and calibration y axis labels
				if (this.render_mode == RENDER_MODE_SEARCH || this.render_mode == RENDER_MODE_CALIBRATION) {
					for (int i = 0; i <= ny; i += 2) {
						if (i == 0) {
							if (y_min == 0) continue;
							textColor.setTextAlign(Align.LEFT);
						} else {
							textColor.setTextAlign(Align.RIGHT);
						}
						double value = (y_min + (y_max - y_min) * i / ny) / y_zoom;
						String format;
						if (value < 10) {
							format = "%1.3f%s";
						} else if (value < 100) {
							format = "%1.2f%s";
						} else if (value < 1000) {
							format = "%1.1f%s";
						} else {
							format = "%1.0f%s";
						}
						canvas.drawText(String.format(Locale.getDefault(), format, value, (i < ny) ? "" : (yUnits)), margin_top - height + i * dwy - 6 * ht_px / 2, margin_top - ht_px / 2, textColor);
					}
				}

				canvas.restore();

				// x axis labels
				textColor.setTextAlign(Align.CENTER);
				for (int i = 0; i < nx + 1; i += 2) {
					if (i == nx) textColor.setTextAlign(Align.RIGHT);
					canvas.drawText(String.format(Locale.getDefault(), "%d%s", (int) ((x_max_value - x_min_value) * i / nx + x_min_value), (i < nx) ? "" : (x_units)), margin_left + i * dwx + (i == nx ? margin_right : 0), viewHeight - ht_px / 4, textColor);
				}

				// draw isotope lines on main window
				if (this.render_mode == RENDER_MODE_SPECTRUM) {
					float x_pos;
					for (int i = 0; i < AtomSpectraIsotopes.checkedIsotopeLine.length; i++) {
						if (AtomSpectraIsotopes.checkedIsotopeLine[i]) {
							if (x_is_calibrated) {
								x_pos = (float) AtomSpectraIsotopes.isotopeLineArray.get(i).getEnergy(0);
							} else { //in "ch."
								x_pos = (float) AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().toChannel(AtomSpectraIsotopes.isotopeLineArray.get(i).getEnergy(0));
							}
							if ((x_pos >= x_min_value) && (x_pos <= x_max_value)) {
								squareColor.setColor(AtomSpectraIsotopes.isotopeLineArray.get(i).getColor());
								canvas.drawLine(margin_left + ((x_pos - x_min_value) / (x_max_value - x_min_value)) * width, margin_top,
										margin_left + ((x_pos - x_min_value) / (x_max_value - x_min_value)) * width, margin_top + height, squareColor);
							}
						}
					}
					if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
						squareColor.setColor(getResources().getColor(R.color.colorFound, null));
					} else {
						squareColor.setColor(getResources().getColor(R.color.colorFound));
					}
					if (AtomSpectraIsotopes.showFoundIsotopes) {
						for (int i = 0; i < AtomSpectraIsotopes.foundList.size(); i++) {
							if (x_is_calibrated) {
								x_pos = (float) AtomSpectraIsotopes.foundList.get(i).getEnergy(0);
							} else { //in "ch."
								x_pos = (float) AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().toChannel(AtomSpectraIsotopes.foundList.get(i).getEnergy(0));
							}
							if ((x_pos >= x_min_value) && (x_pos <= x_max_value)) {
								canvas.drawLine(margin_left + ((x_pos - x_min_value) / (x_max_value - x_min_value)) * width, margin_top,
										margin_left + ((x_pos - x_min_value) / (x_max_value - x_min_value)) * width, margin_top + height, squareColor);
							}
						}
					}
				} // end of isotope lines

				// render shapes
				for (Shape shape : shapes) {
					if (shape.style == Shape.STYLE_LINE || shape.style == Shape.STYLE_DASH) {
						squareColor.setColor(shape.colorFrom);

						for (int i = 1; i < shape.X.length; i++)
							if (shape.X[i - 1] >= margin_left)
								canvas.drawLine(shape.X[i - 1], shape.Y[i - 1], shape.X[i], shape.Y[i], squareColor);

					}

					if (shape.style == Shape.STYLE_BAR) {
						squareColor.setStyle(Style.FILL);
						int colorFrom = shape.colorFrom;
						int colorTo = shape.colorTo;
						LinearGradient linearGradientShader = new LinearGradient(margin_left, margin_top, margin_left + width, margin_top, colorFrom, colorTo, TileMode.CLAMP);
						squareColor.setShader(linearGradientShader);
						for (int i = 1; i < shape.X.length; i++)
							if (shape.X[i - 1] >= margin_left) {
								canvas.drawRect(shape.X[i], shape.Y[i - 1], shape.X[i - 1], margin_top + height, squareColor);
							}
						squareColor.setShader(null);
					}
				}

				LinkedList<Float> lastX = new LinkedList<>();
				LinkedList<Float> lastXEnd = new LinkedList<>();
				LinkedList<Float> lastY = new LinkedList<>();
				LinkedList<Float> lastYEnd = new LinkedList<>();
				textColor.setTextSize(scaleText * ht_px);
				textColor.getTextBounds(yUnits, 0, yUnits.length(), rect);
				textColor.setTextSize(ht_px);
				lastX.add(-1.0f);
				lastXEnd.add(-1.0f);
				lastY.add(-1.0f);
				lastYEnd.add(-1.0f);

				float posYMax = margin_top;
				textColor.getTextBounds("Cg-888", 0, 6, rect);
				float rectHeight = rect.height() + 2;

				// draw isotope labels on main window
				if (this.render_mode == RENDER_MODE_SPECTRUM) {
					float x_pos;
					String isotopeLabel;
					textColor.setTextAlign(Align.LEFT);
					for (int i = 0; i < AtomSpectraIsotopes.checkedIsotopeLine.length; i++) {
						if (AtomSpectraIsotopes.checkedIsotopeLine[i]) {
							if (x_is_calibrated) {
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
										if (posYMax <= (posY + rectHeight))
											posYMax = posY + rectHeight + 1;
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
							if (x_is_calibrated) {
								x_pos = (float) AtomSpectraIsotopes.foundList.get(i).getEnergy(0);
								isotopeLabel = String.format("%.2f", x_pos);
							} else { //in "ch."
								x_pos = (float) AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().toChannel(AtomSpectraIsotopes.foundList.get(i).getEnergy(0));
								isotopeLabel = AtomSpectraIsotopes.foundList.get(i).getName();
							}
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
										if (posYMax <= (posY + rectHeight))
											posYMax = posY + rectHeight + 1;
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
					for (Isotope isotope : AtomSpectraIsotopes.isotopeLineArray) {
						isotope.setCoord(null);
					}
					for (Isotope isotope : AtomSpectraIsotopes.foundList) {
						isotope.setCoord(null);
					}
				}
				//end of isotope labels

				// cursor on spectrum view
				squareColor.setColor(Color.GREEN);
				if (this.render_mode == RENDER_MODE_SPECTRUM && (cursor_X < x_max_value) && (cursor_X > x_min_value)) {
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

				// calibration circle points
				if (this.render_mode == RENDER_MODE_CALIBRATION) {
					squareColor.setColor(Color.GREEN);
					squareColor.setStyle(Style.FILL);
					float radius = (float) StrictMath.min(width, height) / 75.0f;
					for (int i = 0; i < AtomSpectraService.newCalibration.getPointsCount(); i++) {
						float y = (float) (margin_top + (1 - StrictMath.min(1, (AtomSpectraService.newCalibration.getPointEnergy(i) - y_min) / (y_max - y_min) * y_zoom)) * height);
						float x = margin_left + ((AtomSpectraService.newCalibration.getPointChannel(i) - x_min_value) / (x_max_value - x_min_value)) * width;
						if (x >= margin_left && x <= (margin_left + width) && y >= margin_top && y <= (margin_top + height))
							canvas.drawCircle(x, y, radius, squareColor);
					}
				}

				// reference pulse circles
				if (this.render_mode == RENDER_MODE_REFERENCE_PULSE) {
					Shape shape = this.shapes[0];
					squareColor.setStyle(Style.FILL);
					for (int i = 0; i < shape.X.length; i++) {
						squareColor.setColor(Color.RED);
						if ((i > 127) && (i <= 127 + frontCountsMin)) {
							squareColor.setColor(Color.YELLOW);
							canvas.drawCircle(shape.X[i], shape.Y[i], (float) width / 100, squareColor);
						}
						if ((i > 127 + frontCountsMin) && (i <= 127 + frontCountsMax)) {
							squareColor.setColor(Color.GREEN);
							canvas.drawCircle(shape.X[i], shape.Y[i], (float) width / 100, squareColor);
						}
					}
				}
			}
		}
	}

	public void showSpectrum(
			double[] fg, // foreground drawing
			double[] back,  // background drawing
			boolean show_back, // show background
			boolean subtract_back, // subtract background from main hist
			boolean calibrated, // if show energies
			int x_size, // number of abscissa point to be drawn
			boolean logarithmic, // Y-scale type, linear or logarithmic
			boolean bar_mode, // how to draw, lines or bars
			float x_min, // minimum value for X-scale
			float x_max, // maximum value for X-scale
			String x_units, // measurement units, "ch." or "keV", also probably "eV", "MeV"
			float y_zoom_factor,
			int x_zoom_factor,
			float cursor_position_x // xMin..xMax, disabled when outside this interval
	) {
		synchronized (renderSync) {
			int size = fg.length;
			if (size == 0) {
				String message = "Empty array provided to render spectrum plot";
				AtomSpectraLog.addMessage(this.getContext(), message);
				return;
			}

			int step = size / x_size;
			if ((step * x_size) != size) {
				String message = "Invalid size/x_size provided to render spectrum plot: " + size + "/" + x_size;
				AtomSpectraLog.addMessage(this.getContext(), message);
				return;
			}

			this.render_mode = RENDER_MODE_SPECTRUM;
			cursor_X = cursor_position_x;
			x_max_value = x_max;
			x_min_value = x_min;
			logScale = logarithmic;
			x_is_calibrated = calibrated;
			this.x_units = ", " + x_units;

			double[] fg_yf = new double[size];
			double[] back_yf = new double[size];
			double[] fg_reduced_reversed = new double[x_size];
			double[] bg_reduced_reversed = new double[x_size];
			System.arraycopy(fg, 0, fg_yf, 0, size);
			if (show_back) {
				if (subtract_back) { // remove background from main hist
					for (int i = 0; i < size; i++) {
						back_yf[i] = 0.0;
						fg_yf[i] = Math.max(0.0, fg_yf[i] - back[i]);
					}
				} else {
					System.arraycopy(back, 0, back_yf, 0, size);
				}
			}

			y_zoom = y_zoom_factor;
			if (logScale)
				y_max = 0;
			else
				y_max = 1;
			y_min = logScale ? Math.log10(minLogValue) : 0;
			for (int i = 0; i < x_size; i++) {
				double r = 0;
				double back_r = 0;
				for (int j = 0; j < step; j++)
					r += fg_yf[i * step + j];
				if (step > 1 && x_zoom_factor == Constants.SCALE_MAX)
					r /= 2;
				if (show_back)
					for (int j = 0; j < step; j++)
						back_r += back_yf[i * step + j];
				if (logScale) {
					if (r > minLogValue) fg_reduced_reversed[x_size - 1 - i] = Math.log10(r);
					else fg_reduced_reversed[x_size - 1 - i] = Math.log10(minLogValue);
				} else fg_reduced_reversed[x_size - 1 - i] = r;
				if (show_back) if (logScale) {
					if (back_r > minLogValue)
						bg_reduced_reversed[x_size - 1 - i] = Math.log10(back_r);
					else bg_reduced_reversed[x_size - 1 - i] = Math.log10(minLogValue);
				} else bg_reduced_reversed[x_size - 1 - i] = back_r;
			}
			for (int i = 0; i < x_size; i++) {
				if (fg_reduced_reversed[i] > y_max) y_max = fg_reduced_reversed[i];
				if (fg_reduced_reversed[i] < y_min) y_min = fg_reduced_reversed[i];
			}
			if (show_back)
				for (int i = 0; i < x_size; i++)
					if (bg_reduced_reversed[i] > y_max)
						y_max = bg_reduced_reversed[i];  // use one scale for both histograms

			double x_zoom;
			if (x_zoom_factor < Constants.SCALE_MAX)
				x_zoom = (1 << x_zoom_factor) / 2.0;
			else if (x_zoom_factor == Constants.SCALE_MAX)
				x_zoom = (1 << 6) / 2.0;
			else
				x_zoom = 1;
			isotopeFound = -1;
			double cursor_X_Energy; //use energy to search the nearest isotope
			if (x_is_calibrated)
				cursor_X_Energy = cursor_X;
			else
				cursor_X_Energy = AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().toEnergy((int) cursor_X);
			double deltaEnergy = cursor_X_Energy;
			for (int i = 0; i < AtomSpectraIsotopes.isotopeLineArray.size(); i++) {
				if (AtomSpectraIsotopes.isotopeLibrary > 0 && !AtomSpectraIsotopes.IAEAList[AtomSpectraIsotopes.isotopeLibrary - 1].isInChain(AtomSpectraIsotopes.isotopeLineArray.get(i).getName()))
					continue;
				if (Math.abs(AtomSpectraIsotopes.isotopeLineArray.get(i).getEnergy(0) - cursor_X_Energy) < (CURSOR_INEQUALITY * (5000.0 + (AtomSpectraIsotopes.isotopeLibrary > 1 ? 2000.0 : 0.0)) / x_zoom) /*cursor_X_Energy*/) {
					if (Math.abs(AtomSpectraIsotopes.isotopeLineArray.get(i).getEnergy(0) - cursor_X_Energy) < deltaEnergy) {
						deltaEnergy = (float) Math.abs(AtomSpectraIsotopes.isotopeLineArray.get(i).getEnergy(0) - cursor_X_Energy);
						isotopeFound = i;
						isotopeLabel = AtomSpectraIsotopes.isotopeLineArray.get(i).getName();
					}
				}
			}

			int style = bar_mode ? Shape.STYLE_BAR : Shape.STYLE_LINE;
			int fg_color_from = Color.WHITE;
			int fg_color_to = Color.WHITE;
			int bg_color_from = Color.GREEN;
			int bg_color_to = Color.GREEN;
			if (show_back) {
				if (subtract_back) {
					fg_color_from = Color.CYAN;
					fg_color_to = Color.CYAN;
					if (bar_mode) {
						fg_color_from = 0xA0FF8888;
						fg_color_to = 0xA0FFFF88;
					}

					Shape subtract_shape = getShape(fg_reduced_reversed, y_zoom, step, y_max, 1.0, y_min, style, fg_color_from, fg_color_to);
					this.shapes = new Shape[]{subtract_shape};
				} else {
					if (bar_mode) {
						fg_color_from = 0xA08888FF;
						fg_color_to = 0xA08888FF;
						bg_color_from = 0x8888FF88;
						bg_color_to = 0x8888FFFF;
					}

					if (AtomSpectraService.showSpectrumChange) {
						fg_color_from = 0xFFFF00FF;
						fg_color_to = 0xFFFF00FF;
					}

					Shape fg_shape = getShape(fg_reduced_reversed, y_zoom, step, y_max, 1.0, y_min, style, fg_color_from, fg_color_to);
					Shape bg_shape = getShape(bg_reduced_reversed, y_zoom, step, y_max, 1.0, y_min, style, bg_color_from, bg_color_to);
					this.shapes = new Shape[]{fg_shape, bg_shape};
				}
			} else {
				if (bar_mode) {
					fg_color_from = 0xA08888FF;
					fg_color_to = 0xA08888FF;
				}
				Shape fg_shape = getShape(fg_reduced_reversed, y_zoom, step, y_max, 1.0, y_min, style, fg_color_from, fg_color_to);
				this.shapes = new Shape[]{fg_shape};
			}
		}
		invalidate();
	}

	public void showSearch(
			double[] search_values, // array to draw
			double[] alarm_high,
			double[] alarm_low,
			double[] baseline,
			boolean is_interval_search,
			boolean show_alarm_level,
			float y_zoom_factor
	) {
		synchronized (renderSync) {
			int size = search_values.length;
			if (size == 0) {
				String message = "Empty array provided to render search plot";
				AtomSpectraLog.addMessage(this.getContext(), message);
				return;
			}

			this.render_mode = RENDER_MODE_SEARCH;
			this.is_interval_search = is_interval_search;
			x_max_value = size;
			x_min_value = 0;
			x_units = ", " + getResources().getString(R.string.graph_show_points);
			y_zoom = y_zoom_factor;
			y_max = Constants.DOSE_SCALE * Constants.DOSE_OVERHEAD;
			y_min = 0;
			double[] search_values_reversed = getReversed(search_values);
			double[] alarm_high_reversed = getReversed(alarm_high);
			double[] alarm_low_reversed = getReversed(alarm_low);
			double[] baseline_reversed = getReversed(baseline);

			for (int i = 0; i < size; i++) {
				// main plot range
				if (search_values_reversed[i] > y_max) {
					y_max = search_values_reversed[i];
				}

				// alarm range
				if (show_alarm_level && (alarm_high_reversed[i] > y_max)) {
					y_max = alarm_high_reversed[i];
				}
			}

			y_max *= Constants.DOSE_OVERHEAD;
			if (y_max > 1000) {
				m_dose_mode = true;
				y_max /= 1000;
				for (int i = 0; i < size; i++) {
					search_values_reversed[i] /= 1000.0;
					alarm_high_reversed[i] /= 1000.0;
					alarm_low_reversed[i] /= 1000.0;
					baseline_reversed[i] /= 1000.0;
				}
			} else {
				m_dose_mode = false;
			}

			Shape search_shape = getShape(search_values_reversed, y_zoom_factor, 1, y_max, 1.0, y_min, Shape.STYLE_LINE, Color.WHITE, Color.WHITE);
			if (show_alarm_level) {
				Shape[] alarm_high_shape = getNonZeroShapes(alarm_high_reversed, y_zoom_factor, 1, y_max, 1.0, y_min, Shape.STYLE_LINE, Color.RED, Color.RED);
				Shape[] alarm_low_shape = getNonZeroShapes(alarm_low_reversed, y_zoom_factor, 1, y_max, 1.0, y_min, Shape.STYLE_LINE, Color.RED, Color.RED);
				Shape[] baseline_shape = getNonZeroShapes(baseline_reversed, y_zoom_factor, 1, y_max, 1.0, y_min, Shape.STYLE_DASH, Color.GREEN, Color.GREEN);
				this.shapes = new Shape[alarm_high_shape.length + alarm_low_shape.length + baseline_shape.length + 1];
				int index = 0;
				this.shapes[index++] = search_shape;
				for (Shape shape : alarm_high_shape) {
					this.shapes[index++] = shape;
				}
				for (Shape shape : alarm_low_shape) {
					this.shapes[index++] = shape;
				}
				for (Shape shape : baseline_shape) {
					this.shapes[index++] = shape;
				}
			} else {
				this.shapes = new Shape[]{search_shape};
			}
		}
		invalidate();
	}

	public void showCalibration(
			double[] calibration_values, // array to draw
			int x_size, // number of abscissa point to be drawn
			float x_min, // minimum value for X-scale
			float x_max, // maximum value for X-scale
			float y_zoom_factor,
			int x_zoom_factor
	) {
		synchronized (renderSync) {
			int size = calibration_values.length;
			if (size == 0) {
				String message = "Empty array provided to render calibration plot";
				AtomSpectraLog.addMessage(this.getContext(), message);
				return;
			}

			int step = size / x_size;
			if ((step * x_size) != size) {
				String message = "Invalid size/x_size provided to render calibration plot: " + size + "/" + x_size;
				AtomSpectraLog.addMessage(this.getContext(), message);
				return;
			}

			this.render_mode = RENDER_MODE_CALIBRATION;

			x_max_value = x_max;
			x_min_value = x_min;
			x_units = ", " + getResources().getString(R.string.graph_show_channel);

			double[] cal_yf = new double[size];
			double[] cal_reduced_reversed = new double[x_size];
			System.arraycopy(calibration_values, 0, cal_yf, 0, size);

			y_zoom = y_zoom_factor;
			y_max = 1;
			y_min = 0;
			for (int i = 0; i < x_size; i++) {
				double r = 0;
				for (int j = 0; j < step; j++)
					r += cal_yf[i * step + j];
				r /= step;
				cal_reduced_reversed[x_size - 1 - i] = r;
			}
			for (int i = 0; i < x_size; i++) {
				if (cal_reduced_reversed[i] > y_max) y_max = cal_reduced_reversed[i];
				if (cal_reduced_reversed[i] < y_min) y_min = cal_reduced_reversed[i];
			}
			for (int i = 0; i < AtomSpectraService.newCalibration.getPointsCount(); i++) {
				y_min = StrictMath.min(y_min, AtomSpectraService.newCalibration.getPointEnergy(i));
				y_max = StrictMath.max(y_max, AtomSpectraService.newCalibration.getPointEnergy(i));
			}
			y_max = StrictMath.max(y_max, y_min + 1);

			Shape calibration_shape = getShape(cal_reduced_reversed, y_zoom, step, y_max, 1.0, y_min, Shape.STYLE_LINE, Color.WHITE, Color.WHITE);
			this.shapes = new Shape[]{calibration_shape};
		}
		invalidate();
	}

	public void showOscilloscope(
			double[] audio_data, // array to draw
			float y_zoom_factor
	) {
		synchronized (renderSync) {
			int size = audio_data.length;
			if (size == 0) {
				String message = "Empty array provided to render oscilloscope plot";
				AtomSpectraLog.addMessage(this.getContext(), message);
				return;
			}

			this.render_mode = RENDER_MODE_OSCILLOSCOPE;
			x_max_value = size;
			x_min_value = 0;
			x_units = ", " + getResources().getString(R.string.graph_show_points);
			y_zoom = y_zoom_factor;
			y_max = Constants.NUM_HIST_POINTS / 2.0 - 1;
			y_min = -(Constants.NUM_HIST_POINTS / 2.0);

			double[] y_values = new double[size];
			for (int i = 0; i < size; i++) {
				y_values[i] = audio_data[i] - Constants.NUM_HIST_POINTS / 2.0;
			}

			Shape audio_shape = getShape(y_values, y_zoom, 1, y_max, 0.5, y_min, Shape.STYLE_LINE, Color.WHITE, Color.WHITE);
			this.shapes = new Shape[]{audio_shape};
		}
		invalidate();
	}

	public void showReferencePulse(
			double[] pulse_data, // array to draw
			int front_min,
			int front_max
	) {
		synchronized (renderSync) {
			int size = pulse_data.length;
			if (size == 0) {
				String message = "Empty array provided to render pulse shape plot";
				AtomSpectraLog.addMessage(this.getContext(), message);
				return;
			}

			this.render_mode = RENDER_MODE_REFERENCE_PULSE;

			x_max_value = size;
			x_min_value = 0;
			frontCountsMin = front_min;
			frontCountsMax = front_max;
			y_zoom = 1;
			y_max = 1;
			y_min = 0;

			double[] reversed = new double[size];
			for (int i = 0; i < size; i++) {
				double r = pulse_data[i];
				if (reversed[i] > y_max) y_max = r;
				if (reversed[i] < y_min) y_min = r;
				reversed[(size - 1) - i] = r;
			}

			Shape pulse_shape = getShape(reversed, 1.0, 1, y_max, 1.0, y_min, Shape.STYLE_LINE, Color.WHITE, Color.WHITE);
			this.shapes = new Shape[]{pulse_shape};
		}
		invalidate();
	}

	private double[] getReversed(double[] values) {
		int size = values.length;
		double[] reversed = new double[size];
		for (int i = 0; i < size; i++) {
			reversed[i] = values[size - 1 - i];
		}
		return reversed;
	}

	private @NonNull Shape getShape(double[] y_values, double y_zoom, int x_step, double y_max, double rr_y_max, double y_min, int style, int color_from, int color_to) {
		int size = y_values.length;
		int[] X_array = new int[size];
		int[] Y_array = new int[size];
		for (int i = 0; i < size; i++) {
			Y_array[i] = getY(y_values[i], y_zoom, y_max, rr_y_max, y_min);
			X_array[i] = getX(i, size, x_step);
		}

		return new Shape(X_array, Y_array, style, color_from, color_to);
	}

	private @NonNull Shape[] getNonZeroShapes(double[] y_values, double y_zoom, int x_step, double y_max, double rr_y_max, double y_min, int style, int color_from, int color_to) {
		int size = y_values.length;
		ArrayList<Shape> shapes = new ArrayList<>();
		ArrayList<Integer> X_list = new ArrayList<>(size);
		ArrayList<Integer> Y_list = new ArrayList<>(size);
		for (int i = 0; i <= size; i++) {
			if (i == size || y_values[i] == 0) {
				if (!X_list.isEmpty()) {
					int[] X_array = new int[X_list.size()];
					int[] Y_array = new int[Y_list.size()];
					for (int j = 0; j < X_array.length; j++) {
						X_array[j] = X_list.get(j);
						Y_array[j] = Y_list.get(j);
					}
					shapes.add(new Shape(X_array, Y_array, style, color_from, color_to));
					X_list.clear();
					Y_list.clear();
				}

				continue;
			}

			Y_list.add(getY(y_values[i], y_zoom, y_max, rr_y_max, y_min));
			X_list.add(getX(i, size, x_step));
		}

		Shape[] shapes_array = new Shape[shapes.size()];
		for (int i = 0; i < shapes_array.length; i++) {
			shapes_array[i] = shapes.get(i);
		}

		return shapes_array;
	}

	private int getX(int i, int size, int x_step) {
		return margin_left + width - (int) ((double) x_step * (width - 2) * (i) / (size * x_step)) - 1;
	}

	private int getY(double y_value, double y_zoom, double y_max, double rr_y_max, double y_min) {
		double rr = (y_value - y_min) * y_zoom / (y_max - y_min);

		if (rr > rr_y_max) rr = rr_y_max;

		return margin_top + (int) (height * rr_y_max) - (int) ((height - 2) * rr) - 1;
	}

	private class Shape {
		public final static int STYLE_BAR = 0;
		public final static int STYLE_LINE = 1;
		public final static int STYLE_DASH = 2;

		public final int[] X;
		public final int[] Y;
		public final int style;
		public final int colorFrom;
		public final int colorTo;

		public Shape(int[] X, int[] Y, int style, int colorFrom, int colorTo) {
			this.X = X;
			this.Y = Y;
			this.style = style;
			this.colorFrom = colorFrom;
			this.colorTo = colorTo;
		}
	}
}

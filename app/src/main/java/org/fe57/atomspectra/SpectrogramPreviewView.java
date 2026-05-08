package org.fe57.atomspectra;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import java.util.Locale;

/**
 * Lightweight spectrum preview rendering two CPS line plots overlayed on the same axes.
 * Used by {@link AtomSpectraSpectrogram} to compare a "background" region (green) against
 * a "source" region (white), both averaged over a row range from the spectrogram.
 *
 * <p>The X axis can be clipped to a sub-range of channels via
 * {@link #setVisibleChannelRange(int, int)} so it stays in lockstep with the spectrogram's
 * horizontal pan. Y is auto-scaled within the visible window using the current
 * lin/sqrt/log scale.
 */
@SuppressLint({ "DefaultLocale", "DrawAllocation" })
public class SpectrogramPreviewView extends View {
    private static final int COLOR_BACKGROUND = 0xFF44E044; // green
    private static final int COLOR_SOURCE = Color.WHITE;
    private static final int COLOR_GRID = 0xFF303030;
    private static final int COLOR_AXIS = 0xFF707070;
    private static final int COLOR_LABEL = 0xFFB0B0B0;

    private static final float TEXT_FONT_SIZE_DP = 10f;
    private static final float STROKE_WIDTH_DP = 1.2f;
    private static final float MARGIN_LEFT_DP = 28f;
    private static final float MARGIN_RIGHT_DP = 6f;
    private static final float MARGIN_TOP_DP = 8f;
    private static final float MARGIN_BOTTOM_DP = 16f;

    private double[] background;
    private double[] foreground;
    private double[] energies;
    private String scale = AtomSpectraSpectrogramView.SCALE_SQRT;
    private int visibleStartChannel = -1;
    private int visibleEndChannel = -1;

    private final Paint paintLine = new Paint();
    private final Paint paintGrid = new Paint();
    private final Paint paintAxis = new Paint();
    private final Paint paintText = new Paint();

    public SpectrogramPreviewView(Context context) {
        super(context);
        init();
    }

    public SpectrogramPreviewView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public SpectrogramPreviewView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        paintLine.setAntiAlias(true);
        paintLine.setStyle(Paint.Style.STROKE);
        paintLine.setStrokeWidth(dpToPx(STROKE_WIDTH_DP));

        paintGrid.setAntiAlias(false);
        paintGrid.setColor(COLOR_GRID);
        paintGrid.setStrokeWidth(1);

        paintAxis.setAntiAlias(false);
        paintAxis.setColor(COLOR_AXIS);
        paintAxis.setStrokeWidth(1);

        paintText.setAntiAlias(true);
        paintText.setColor(COLOR_LABEL);
        paintText.setTextSize(dpToPx(TEXT_FONT_SIZE_DP));
    }

    public void setSpectra(double[] background, double[] foreground, double[] energies) {
        this.background = background;
        this.foreground = foreground;
        this.energies = energies;
        invalidate();
    }

    public void setScale(String scale) {
        this.scale = scale;
        invalidate();
    }

    /**
     * Restricts the X axis to channels in [startChannel, endChannel] (inclusive) in the
     * unbinned channel space. Pass -1, -1 to show the full range.
     */
    public void setVisibleChannelRange(int startChannel, int endChannel) {
        this.visibleStartChannel = startChannel;
        this.visibleEndChannel = endChannel;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(Color.BLACK);

        int viewWidth = getWidth();
        int viewHeight = getHeight();
        if (viewWidth <= 0 || viewHeight <= 0) {
            return;
        }

        int marginLeft = dpToPx(MARGIN_LEFT_DP);
        int marginRight = dpToPx(MARGIN_RIGHT_DP);
        int marginTop = dpToPx(MARGIN_TOP_DP);
        int marginBottom = dpToPx(MARGIN_BOTTOM_DP);

        int plotLeft = marginLeft;
        int plotTop = marginTop;
        int plotRight = viewWidth - marginRight;
        int plotBottom = viewHeight - marginBottom;
        int plotWidth = plotRight - plotLeft;
        int plotHeight = plotBottom - plotTop;
        if (plotWidth <= 0 || plotHeight <= 0) {
            return;
        }

        int channelCount = AtomSpectraSpectrogramData.CHANNEL_COUNT;
        int startCh = (visibleStartChannel < 0) ? 0 : Math.max(0, visibleStartChannel);
        int endCh = (visibleEndChannel < 0) ? (channelCount - 1) : Math.min(channelCount - 1, visibleEndChannel);
        if (endCh <= startCh) {
            endCh = Math.min(channelCount - 1, startCh + 1);
        }
        int chSpan = endCh - startCh;

        // grid + axes
        int hLines = 4;
        for (int i = 0; i <= hLines; i++) {
            float y = plotTop + plotHeight * i / (float) hLines;
            canvas.drawLine(plotLeft, y, plotRight, y, paintGrid);
        }
        int vLines = 4;
        for (int i = 0; i <= vLines; i++) {
            float x = plotLeft + plotWidth * i / (float) vLines;
            canvas.drawLine(x, plotTop, x, plotBottom, paintGrid);
        }
        canvas.drawLine(plotLeft, plotTop, plotLeft, plotBottom, paintAxis);
        canvas.drawLine(plotLeft, plotBottom, plotRight, plotBottom, paintAxis);

        if (background == null && foreground == null) {
            return;
        }

        double maxCpsValue = 0;
        double minNonZeroCpsValue = Double.MAX_VALUE;
        if (background != null) {
            for (int i = startCh; i <= endCh && i < background.length; i++) {
                double val = background[i];
                if (val > maxCpsValue) {
                    maxCpsValue = val;
                }
                if (val < minNonZeroCpsValue && val > 0) {
                    minNonZeroCpsValue = val;
                }
            }
        }
        if (foreground != null) {
            for (int i = startCh; i <= endCh && i < foreground.length; i++) {
                double val = foreground[i];
                if (val > maxCpsValue) {
                    maxCpsValue = val;
                }
                if (val < minNonZeroCpsValue && val > 0) {
                    minNonZeroCpsValue = val;
                }
            }
        }
        if (maxCpsValue <= 0) {
            maxCpsValue = 1;
        }
        if (minNonZeroCpsValue > maxCpsValue) {
            minNonZeroCpsValue = maxCpsValue;
        }

        // X labels
        // left bound
        drawXLabel(canvas, startCh, plotLeft, plotBottom + paintText.getTextSize() + dpToPx(2), Paint.Align.LEFT);
        // middle
        drawXLabel(canvas, (startCh + endCh) / 2,
                (plotLeft + plotRight) / 2f,
                plotBottom + paintText.getTextSize() + dpToPx(2),
                Paint.Align.CENTER);
        // right bound
        drawXLabel(canvas, endCh, plotRight, plotBottom + paintText.getTextSize() + dpToPx(2), Paint.Align.RIGHT);

        // Y label - max CPS
        paintText.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(formatCps(maxCpsValue), plotLeft - dpToPx(2), plotTop + paintText.getTextSize(), paintText);
        canvas.drawText("cps", plotLeft - dpToPx(2), plotTop + paintText.getTextSize() * 2, paintText);
        canvas.drawText("0", plotLeft - dpToPx(2), plotBottom, paintText);

        if (background != null) {
            paintLine.setColor(COLOR_BACKGROUND);
            drawSpectrum(canvas, background, startCh, endCh, chSpan, plotLeft, plotTop, plotWidth, plotHeight, maxCpsValue, minNonZeroCpsValue);
        }
        if (foreground != null) {
            paintLine.setColor(COLOR_SOURCE);
            drawSpectrum(canvas, foreground, startCh, endCh, chSpan, plotLeft, plotTop, plotWidth, plotHeight, maxCpsValue, minNonZeroCpsValue);
        }
    }

    private void drawXLabel(Canvas canvas, int channel, float x, float y, Paint.Align align) {
        paintText.setTextAlign(align);
        String label;
        if (energies != null && channel >= 0 && channel < energies.length && energies[channel] > 0) {
            label = String.format(Locale.US, "%.0f keV", energies[channel]);
        } else {
            label = String.format(Locale.US, "ch %d", channel);
        }
        canvas.drawText(label, x, y, paintText);
    }

    private void drawSpectrum(Canvas canvas, double[] values, int startCh, int endCh, int chSpan,
                              int plotLeft, int plotTop, int plotWidth, int plotHeight,
                              double maxValue, double minNonZeroValue) {
        if (values.length == 0 || chSpan <= 0) {
            return;
        }

        float prevX = 0, prevY = 0;
        boolean hasPrev = false;
        for (int ch = startCh; ch <= endCh && ch < values.length; ch++) {
            double v = values[ch];
            float x = plotLeft + plotWidth * (ch - startCh) / (float) chSpan;
            float y = plotTop + plotHeight * (1f - (float) scaledRatio(v, maxValue, minNonZeroValue));
            if (hasPrev) {
                canvas.drawLine(prevX, prevY, x, y, paintLine);
            }
            prevX = x;
            prevY = y;
            hasPrev = true;
        }
    }

    private double scaledRatio(double value, double max, double minNonZero) {
        if (value <= 0 || max <= 0) {
            return 0;
        }

        double ratio = 0;
        switch (this.scale) {
            case AtomSpectraSpectrogramView.SCALE_LOG:
                if (max <= minNonZero) {
                    ratio = 1;
                } else {
                    ratio = Math.log(value / minNonZero + 1) / Math.log(max / minNonZero + 1);
                }
                break;
            case AtomSpectraSpectrogramView.SCALE_SQRT:
                ratio = Math.sqrt(value) / Math.sqrt(max);
                break;
            default:
                ratio = value / max;
                break;

        }

        if (ratio < 0) ratio = 0;
        if (ratio > 1) ratio = 1;

        return ratio;
    }

    private static String formatCps(double v) {
        if (v >= 1000) {
            return String.format(Locale.US, "%.0f", v);
        } else if (v >= 10) {
            return String.format(Locale.US, "%.1f", v);
        } else if (v >= 1) {
            return String.format(Locale.US, "%.2f", v);
        }
        return String.format(Locale.US, "%.3f", v);
    }

    private int dpToPx(float dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                Resources.getSystem().getDisplayMetrics());
    }
}

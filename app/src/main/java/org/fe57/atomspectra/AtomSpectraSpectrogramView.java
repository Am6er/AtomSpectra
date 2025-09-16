package org.fe57.atomspectra;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.MotionEvent;
import android.view.ViewGroup;

import java.util.ArrayList;

@SuppressLint({ "DefaultLocale", "DrawAllocation" })
public class AtomSpectraSpectrogramView extends View {
	private static final int[] IRON_PALETTE = new int[] {
		/*0xFF00000A, 0xFF000014, 0xFF00001E, 0xFF000025, 0xFF00002A,*/ 0xFF00002E, 0xFF000032, 0xFF000036, 0xFF00003A, 0xFF00003E, 0xFF000042, 0xFF000046, 0xFF00004A, 0xFF00004F, 0xFF000052, 0xFF010055,
		0xFF010057, 0xFF020059, 0xFF02005C, 0xFF03005E, 0xFF040061, 0xFF040063, 0xFF050065, 0xFF060067, 0xFF070069, 0xFF08006B, 0xFF09006E, 0xFF0A0070, 0xFF0B0073, 0xFF0C0074, 0xFF0D0075, 0xFF0D0076,
		0xFF0E0077, 0xFF100078, 0xFF120079, 0xFF13007B, 0xFF15007C, 0xFF17007D, 0xFF19007E, 0xFF1B0080, 0xFF1C0081, 0xFF1E0083, 0xFF200084, 0xFF220085, 0xFF240086, 0xFF260087, 0xFF280089, 0xFF2A0089,
		0xFF2C008A, 0xFF2E008B, 0xFF30008C, 0xFF32008D, 0xFF34008E, 0xFF36008E, 0xFF38008F, 0xFF390090, 0xFF3B0091, 0xFF3C0092, 0xFF3E0093, 0xFF3F0093, 0xFF410094, 0xFF420095, 0xFF440095, 0xFF450096,
		0xFF470096, 0xFF490096, 0xFF4A0096, 0xFF4C0097, 0xFF4E0097, 0xFF4F0097, 0xFF510097, 0xFF520098, 0xFF540098, 0xFF560098, 0xFF580099, 0xFF5A0099, 0xFF5C0099, 0xFF5D009A, 0xFF5F009A, 0xFF61009B,
		0xFF63009B, 0xFF64009B, 0xFF66009B, 0xFF68009B, 0xFF6A009B, 0xFF6C009C, 0xFF6D009C, 0xFF6F009C, 0xFF70009C, 0xFF71009D, 0xFF73009D, 0xFF75009D, 0xFF77009D, 0xFF78009D, 0xFF7A009D, 0xFF7C009D,
		0xFF7E009D, 0xFF7F009D, 0xFF81009D, 0xFF83009D, 0xFF84009D, 0xFF86009D, 0xFF87009D, 0xFF89009D, 0xFF8A009D, 0xFF8B009D, 0xFF8D009D, 0xFF8F009C, 0xFF91009C, 0xFF93009C, 0xFF95009C, 0xFF96009B,
		0xFF98009B, 0xFF99009B, 0xFF9B009B, 0xFF9C009B, 0xFF9D009B, 0xFF9F009B, 0xFFA0009B, 0xFFA2009B, 0xFFA3009B, 0xFFA4009B, 0xFFA6009A, 0xFFA7009A, 0xFFA8009A, 0xFFA90099, 0xFFAA0099, 0xFFAB0099,
		0xFFAD0099, 0xFFAE0198, 0xFFAF0198, 0xFFB00198, 0xFFB00198, 0xFFB10197, 0xFFB20197, 0xFFB30196, 0xFFB40296, 0xFFB50295, 0xFFB60295, 0xFFB70395, 0xFFB80395, 0xFFB90495, 0xFFBA0495, 0xFFBA0494,
		0xFFBB0593, 0xFFBC0593, 0xFFBD0593, 0xFFBE0692, 0xFFBF0692, 0xFFBF0692, 0xFFC00791, 0xFFC00791, 0xFFC10890, 0xFFC10990, 0xFFC20A8F, 0xFFC30A8E, 0xFFC30B8E, 0xFFC40C8D, 0xFFC50C8C, 0xFFC60D8B,
		0xFFC60E8A, 0xFFC70F89, 0xFFC81088, 0xFFC91187, 0xFFCA1286, 0xFFCA1385, 0xFFCB1385, 0xFFCB1484, 0xFFCC1582, 0xFFCD1681, 0xFFCE1780, 0xFFCE187E, 0xFFCF187C, 0xFFCF197B, 0xFFD01A79, 0xFFD11B78,
		0xFFD11C76, 0xFFD21C75, 0xFFD21D74, 0xFFD31E72, 0xFFD32071, 0xFFD4216F, 0xFFD4226E, 0xFFD5236B, 0xFFD52469, 0xFFD62567, 0xFFD72665, 0xFFD82764, 0xFFD82862, 0xFFD92A60, 0xFFDA2B5E, 0xFFDA2C5C,
		0xFFDB2E5A, 0xFFDB2F57, 0xFFDC2F54, 0xFFDD3051, 0xFFDD314E, 0xFFDE324A, 0xFFDE3347, 0xFFDF3444, 0xFFDF3541, 0xFFDF363D, 0xFFE0373A, 0xFFE03837, 0xFFE03933, 0xFFE13A30, 0xFFE23B2D, 0xFFE23C2A,
		0xFFE33D26, 0xFFE33E23, 0xFFE43F20, 0xFFE4411D, 0xFFE4421C, 0xFFE5431B, 0xFFE54419, 0xFFE54518, 0xFFE64616, 0xFFE74715, 0xFFE74814, 0xFFE74913, 0xFFE84A12, 0xFFE84C10, 0xFFE84C0F, 0xFFE94D0E,
		0xFFE94D0D, 0xFFEA4E0C, 0xFFEA4F0C, 0xFFEB500B, 0xFFEB510A, 0xFFEB520A, 0xFFEB5309, 0xFFEC5409, 0xFFEC5608, 0xFFEC5708, 0xFFEC5808, 0xFFED5907, 0xFFED5A07, 0xFFED5B06, 0xFFEE5C06, 0xFFEE5C05,
		0xFFEE5D05, 0xFFEE5E05, 0xFFEF5F04, 0xFFEF6004, 0xFFEF6104, 0xFFEF6204, 0xFFF06303, 0xFFF06403, 0xFFF06503, 0xFFF16603, 0xFFF16603, 0xFFF16703, 0xFFF16803, 0xFFF16902, 0xFFF16A02, 0xFFF16B02,
		0xFFF16B02, 0xFFF26C01, 0xFFF26D01, 0xFFF26E01, 0xFFF36F01, 0xFFF37001, 0xFFF37101, 0xFFF37201, 0xFFF47300, 0xFFF47400, 0xFFF47500, 0xFFF47600, 0xFFF47700, 0xFFF47800, 0xFFF47A00, 0xFFF57B00,
		0xFFF57C00, 0xFFF57E00, 0xFFF57F00, 0xFFF68000, 0xFFF68100, 0xFFF68200, 0xFFF78300, 0xFFF78400, 0xFFF78500, 0xFFF78600, 0xFFF88700, 0xFFF88800, 0xFFF88800, 0xFFF88900, 0xFFF88A00, 0xFFF88B00,
		0xFFF88C00, 0xFFF98D00, 0xFFF98D00, 0xFFF98E00, 0xFFF98F00, 0xFFF99000, 0xFFF99100, 0xFFF99200, 0xFFF99300, 0xFFFA9400, 0xFFFA9500, 0xFFFA9600, 0xFFFB9800, 0xFFFB9900, 0xFFFB9A00, 0xFFFB9C00,
		0xFFFC9D00, 0xFFFC9F00, 0xFFFCA000, 0xFFFCA100, 0xFFFDA200, 0xFFFDA300, 0xFFFDA400, 0xFFFDA600, 0xFFFDA700, 0xFFFDA800, 0xFFFDAA00, 0xFFFDAB00, 0xFFFDAC00, 0xFFFDAD00, 0xFFFDAE00, 0xFFFEAF00,
		0xFFFEB000, 0xFFFEB100, 0xFFFEB200, 0xFFFEB300, 0xFFFEB400, 0xFFFEB500, 0xFFFEB600, 0xFFFEB800, 0xFFFEB900, 0xFFFEB900, 0xFFFEBA00, 0xFFFEBB00, 0xFFFEBC00, 0xFFFEBD00, 0xFFFEBE00, 0xFFFEC000,
		0xFFFEC100, 0xFFFEC200, 0xFFFEC300, 0xFFFEC400, 0xFFFEC500, 0xFFFEC600, 0xFFFEC700, 0xFFFEC800, 0xFFFEC901, 0xFFFECA01, 0xFFFECA01, 0xFFFECB01, 0xFFFECC02, 0xFFFECD02, 0xFFFECE03, 0xFFFECF04,
		0xFFFECF04, 0xFFFED005, 0xFFFED106, 0xFFFED308, 0xFFFED409, 0xFFFED50A, 0xFFFED60A, 0xFFFED70B, 0xFFFED80C, 0xFFFED90D, 0xFFFFDA0E, 0xFFFFDA0E, 0xFFFFDB10, 0xFFFFDC12, 0xFFFFDC14, 0xFFFFDD16,
		0xFFFFDE19, 0xFFFFDE1B, 0xFFFFDF1E, 0xFFFFE020, 0xFFFFE122, 0xFFFFE224, 0xFFFFE226, 0xFFFFE328, 0xFFFFE42B, 0xFFFFE42E, 0xFFFFE531, 0xFFFFE635, 0xFFFFE638, 0xFFFFE73C, 0xFFFFE83F, 0xFFFFE943,
		0xFFFFEA46, 0xFFFFEB49, 0xFFFFEB4D, 0xFFFFEC50, 0xFFFFED54, 0xFFFFEE57, 0xFFFFEE5B, 0xFFFFEE5F, 0xFFFFEF63, 0xFFFFEF67, 0xFFFFF06A, 0xFFFFF06E, 0xFFFFF172, 0xFFFFF177, 0xFFFFF17B, 0xFFFFF280,
		0xFFFFF285, 0xFFFFF28A, 0xFFFFF38E, 0xFFFFF492, 0xFFFFF496, 0xFFFFF49A, 0xFFFFF59E, 0xFFFFF5A2, 0xFFFFF5A6, 0xFFFFF6AA, 0xFFFFF6AF, 0xFFFFF7B3, 0xFFFFF7B6, 0xFFFFF8BA, 0xFFFFF8BD, 0xFFFFF8C1,
		0xFFFFF8C4, 0xFFFFF9C7, 0xFFFFF9CA, 0xFFFFF9CD, 0xFFFFFAD1, 0xFFFFFAD4, 0xFFFFFBD8, 0xFFFFFCDB, 0xFFFFFCDF, 0xFFFFFDE2, 0xFFFFFDE5, 0xFFFFFDE8, 0xFFFFFEEB, 0xFFFFFEEE, 0xFFFFFEF1, 0xFFFFFEF4
	};
	private final int POINT_SIZE_PX = 2;
	private final int TIME_AXIS_WIDTH_PX = 120;
	private final int CHANNEL_AXIS_HEIGHT_PX = 40;

	// cps data
	private ArrayList<double[]> spectrogramData = null;
	private double maxValue = 0;
	private double minValue = 0;

	// vertical/horizontal drag
	private int verticalOffsetPx = 0;
	private int horizontalOffsetPx = 0;
	private float lastTouchY;
	private float lastTouchX;
	private boolean isDragging;

	private Bitmap spectrogramBitmap = null;
	private boolean autoScroll = true;

	public AtomSpectraSpectrogramView(Context context) {
		super(context);
	}

	public AtomSpectraSpectrogramView(Context context, AttributeSet attrs) {
		super(context, attrs);
	}

	public AtomSpectraSpectrogramView(Context context, AttributeSet attrs, int defStyle) {
		super(context, attrs, defStyle);
	}

	@Override
	protected void onDraw(Canvas canvas) {
		if (this.spectrogramBitmap != null) {
			Paint paint = new Paint();
			canvas.drawBitmap(this.spectrogramBitmap, 0, 0, paint);
		}
	}

	@Override
	protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
		super.onLayout(changed, left, top, right, bottom);

		this.renderSpectrogramToBitmap();
	}

	public void renderSpectrogram(ArrayList<double[]> spectrogram, boolean scrollToBottom) {
		this.spectrogramData = spectrogram;
		this.maxValue = 0;
		for (double[] deltas : spectrogram) {
			for (double value : deltas) {
				if (value > this.maxValue) {
					this.maxValue = value;
				}
			}
		}
	
		if (scrollToBottom) {
			verticalOffsetPx = this.spectrogramData.size() * POINT_SIZE_PX;
		}

		renderSpectrogramToBitmap();
		this.invalidate();
	}

	@Override
	public boolean onTouchEvent(MotionEvent event) {
		if (spectrogramData == null || spectrogramData.isEmpty()) {
			return super.onTouchEvent(event);
		}

		switch (event.getActionMasked()) {
			case MotionEvent.ACTION_DOWN:
				lastTouchY = event.getY();
				lastTouchX = event.getX();
				isDragging = true;
				return true;
			case MotionEvent.ACTION_MOVE:
				if (isDragging) {
					float currentY = event.getY();
					float currentX = event.getX();
					int dy = Math.round(lastTouchY - currentY);
					int dx = Math.round(lastTouchX - currentX);
					if (dy != 0 || dx != 0) {
						verticalOffsetPx += dy;
						horizontalOffsetPx += dx;
						lastTouchY = currentY;
						lastTouchX = currentX;

						renderSpectrogramToBitmap();
						invalidate();
					}
				}
				return true;
			case MotionEvent.ACTION_UP:
			case MotionEvent.ACTION_CANCEL:
				isDragging = false;
				return true;
		}
		return super.onTouchEvent(event);
	}

	private void renderSpectrogramToBitmap() {
		int viewWidth = getWidth();
		int viewHeight = getHeight();
		if (viewWidth <= 0 || viewHeight <= 0) {
			this.spectrogramBitmap = null;
			return;
		}

		Bitmap bitmap = Bitmap.createBitmap(viewWidth, viewHeight, Bitmap.Config.ARGB_8888);
		if (!spectrogramData.isEmpty()) {
			int spgViewWidth = viewWidth - TIME_AXIS_WIDTH_PX;
			int spgViewHeight = viewHeight - CHANNEL_AXIS_HEIGHT_PX;

			int rowCount = spectrogramData.size();
			int colCount = spectrogramData.get(0).length;

			int rowHeightPx = POINT_SIZE_PX;
			int colWidthPx = POINT_SIZE_PX;
			int maxRowsInView = spgViewHeight / rowHeightPx;
			int maxColsInView = spgViewWidth / colWidthPx;

			// validate offset values
			int totalSpgHeight = rowCount * rowHeightPx;
			if (totalSpgHeight < spgViewHeight) {
				verticalOffsetPx = 0;
			} else {
				if (verticalOffsetPx < 0) {
					verticalOffsetPx = 0;
				}

				int maxOffset = totalSpgHeight - maxRowsInView * rowHeightPx;
				if (verticalOffsetPx >= maxOffset) {
					verticalOffsetPx = maxOffset;
					this.autoScroll = true;
				} else {
					this.autoScroll = false;
				}
			}

			int totalSpgWidth = colCount * colWidthPx;
			if (totalSpgWidth < spgViewWidth) {
				horizontalOffsetPx = 0;
			} else {
				if (horizontalOffsetPx < 0) {
					horizontalOffsetPx = 0;
				}

				int maxOffset = totalSpgWidth - maxColsInView * colWidthPx;
				if (horizontalOffsetPx >= maxOffset) {
					horizontalOffsetPx = maxOffset;
				}
			}

			// render visible area
			int startRow = Math.max(0, verticalOffsetPx / rowHeightPx);
			int endRow = Math.min(rowCount - 1, startRow + maxRowsInView - 1);
			int rowsToRender = endRow - startRow + 1;
			int rowsToRenderHeightPx = rowsToRender * rowHeightPx;

			int startCol = Math.max(0, horizontalOffsetPx / colWidthPx);
			int endCol = Math.min(colCount - 1, startCol + maxColsInView - 1);
			int colsToRender = endCol - startCol + 1;
			int colsToRenderWidthPx = colsToRender * colWidthPx;

			int totalPixels = rowsToRender * rowHeightPx * colsToRenderWidthPx;
			int[] spgPixels = new int[totalPixels];
			for (int row = startRow; row <= endRow; row++) {
				for (int col = startCol; col <= endCol; col++) {
					double value = spectrogramData.get(row)[col];
					int color = mapValueToColor(value);
					int pxTopLeftIndex = (row - startRow) * colsToRenderWidthPx * rowHeightPx + (col - startCol) * colWidthPx;
					for (int i = 0; i < POINT_SIZE_PX; i++) {
						for (int j = 0; j < POINT_SIZE_PX; j++) {
							spgPixels[pxTopLeftIndex + i * colsToRenderWidthPx + j] = color;
						}
					}
				}
			}

			bitmap.setPixels(spgPixels, 0, colsToRenderWidthPx, TIME_AXIS_WIDTH_PX, 0, colsToRenderWidthPx, rowsToRenderHeightPx);

			if (this.autoScroll) {
				verticalOffsetPx += rowHeightPx;
			}
		}

		this.spectrogramBitmap = bitmap;
	}

	private int mapValueToColor(double value) {
		int[] palette = IRON_PALETTE;
		String scale = "sqrt";

		double lowerBound = this.minValue;
		double upperBound = this.maxValue;
		value -= lowerBound;
		if (value < 0) {
			value = 0;
		}

    	double ratio = value / (upperBound - lowerBound);
		long colorIndex = Math.round(ratio * (palette.length - 1));
		switch (scale) {
			case "log":
				colorIndex = Math.round((Math.log(colorIndex + 1) / Math.log(palette.length)) * (palette.length - 1));
				break;
			case "sqrt":
				colorIndex = Math.round((Math.sqrt(colorIndex) / Math.sqrt(palette.length)) * (palette.length - 1));
				break;
			default:
				break;
		}

		if (colorIndex > palette.length - 1) {
			colorIndex = palette.length - 1;
		}

		return palette[(int)colorIndex];
	}
}

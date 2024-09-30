package org.fe57.atomspectra;


import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.gesture.GestureOverlayView;
import android.gesture.GestureOverlayView.OnGestureListener;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.text.method.NumberKeyListener;
import android.util.Log;
import android.view.GestureDetector;
import android.view.GestureDetector.SimpleOnGestureListener;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.ScaleGestureDetector.SimpleOnScaleGestureListener;
import android.view.View;
import android.view.View.OnLongClickListener;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.app.ActivityCompat.OnRequestPermissionsResultCallback;
import androidx.core.content.PermissionChecker;
import androidx.core.util.Pair;
import androidx.documentfile.provider.DocumentFile;

import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

public class AtomSpectra extends Activity implements OnGestureListener, OnRequestPermissionsResultCallback, OnLongClickListener {

	private final static String TAG = AtomSpectra.class.getSimpleName();
//	private static String PACKAGE_NAME;

	public static final int REQUEST_AUDIO = 0;
	public static final int REQUEST_READ_HIST = 1;
	public static final int REQUEST_READ_BACK = 2;
	public static final int REQUEST_READ_CAL = 3;
	public static final int REQUEST_WRITE_HIST = 4;
	public static final int REQUEST_WRITE_BACK = 5;
	public static final int REQUEST_READ_BACK_FROM = 6;
	public static final int REQUEST_EXPORT = 7;
	public static final int REQUEST_EXPORT_E = 8;
	public static final int REQUEST_SHARE = 9;
	public static final int REQUEST_EXPORT_BQMONI = 10;
	public static final int REQUEST_EXPORT_SPE = 11;
	public static final int REQUEST_EXPORT_N42 = 12;
	public static final int REQUEST_FINE_GPS = 13;
	public static final int REQUEST_READ_DEVICE = 14;
	public static final int REQUEST_WRITE_DEVICE = 15;
	public static final int REQUEST_ADD_HIST = 16;

	private static final String ATOM_STATE_AVERAGE = "Atom average";
	private static final String ATOM_STATE_LOG = "Atom Log";
	private static final String ATOM_STATE_BAR = "Atom Bar";
	private static final String ATOM_STATE_SCALE = "Atom scale";
//	private static final String ATOM_STATE_BACKGROUND_SHOW = "Atom background";
	private static final String ATOM_STATE_BACKGROUND_SUBTRACT = "Atom subtract";

    private static final String ATOM_STATE_CURSOR_X = "Atom cursor";
	private static final String ATOM_STATE_CURSOR_BUTTONS = "Atom buttons";

	private static final String SEND_CALIBRATION = "Send Calibration";
	private static final String GET_CALIBRATION = "Get calibration";
	private final String[] calibrationAnswers = new String[11];
	private int gotAnswers = 0;

	private AtomSpectraService mAtomSpectraService = null;
	private static Context AppContext;

	public static boolean active = false;
	private AtomSpectraShapeView mAtomSpectraShapeView = null;
	private SeekBar seekChannel = null;

	private GestureDetector gestureDetector = null;
	private ScaleGestureDetector gestureScaleDetector = null;

	@SuppressLint("StaticFieldLeak")
	private TextView mTextView = null;
	private LinearLayout mLayoutView = null;

	private boolean logScale = false;
	private boolean barMode = false;

	//for main spectrum
	private float zoom_factor = 1;
	private int cursor_x = -1;
	public static boolean XCalibrated;

	//for background
	public static boolean background_subtract = false;          //Subtract background from main histogram
	private static Menu app_menu = null;
	private final int LOAD_HIST_CODE = 301;
	private final int LOAD_CALIBRATION_CODE = 302;
	private final int SHARE_FILE_CODE = 303;
	private final int LOAD_BACK_CODE = 304;
	private final int SELECT_SAVE_HIST_DIR_CODE = 305;
	private final int SELECT_SAVE_BACK_DIR_CODE = 306;
	private final int SELECT_SAVE_EXPORT_DIR_CODE = 307;
	private final int SELECT_SAVE_EXPORT_E_DIR_CODE = 308;
	private final int SELECT_SAVE_EXPORT_BQ_DIR_CODE = 309;
	private final int SELECT_SAVE_EXPORT_SPE_DIR_CODE = 310;
	private final int SELECT_SAVE_EXPORT_N42_DIR_CODE = 311;
	private final int SELECT_LOAD_BACK_DIR_CODE = 312;
	private final int SELECT_LOAD_DEVICE_CODE = 313;
	private final int SELECT_SAVE_DEVICE_DIR_CODE = 314;
	private final int ADD_HIST_CODE = 315;
	private boolean isPinchMode = false;
	private boolean isPinchModeFinished = false;
	private boolean hasFeatureGPS = false;                    //GPS coordinates
	private boolean hasFeatureNetwork = false;              //Network coordinates
	//private GPSLocator Locator = null;
	private boolean addGPS = false;
	private Intent inputServiceIntent = null;
    private SharedPreferences sharedPreferences = null;

	private final static int SHOW_CPS = 0;
	private final static int SHOW_AVERAGE = 1;
	private final static int SHOW_BASE = 2;
	private final static int SHOW_COORD = 3;
	private final static int[] showModes = {SHOW_AVERAGE, SHOW_BASE, SHOW_COORD, SHOW_CPS};

	public static int reducedTo;
	public static int saveChannels;
	public static int loadChannels;
	public static int channelCompression;

	private TextView cpsView, doseRateText;
	private Button fmsButton;
	private final int[] modes = {1, 2, 0};
	private final String[] modeNames = {"F", "M", "S"};
	private int show_average_cps = SHOW_CPS;
	private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
	private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH-mm-ss", Locale.US);
//	private final SimpleDateFormat dateTimeFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US);
	private final SimpleDateFormat dateZoneFormat = new SimpleDateFormat("yyyy.MM.dd HH:mm:ss Z", Locale.US);
	private final int mutabilityFlag = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ? PendingIntent.FLAG_IMMUTABLE : 0;

	//template function to check permissions and ask for them if needed
	protected boolean checkPermissions(final String[] permission, final String title, final String message, final int request) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
			final Activity id = this;
			if (PermissionChecker.checkSelfPermission(getApplicationContext(), permission[0]) != PermissionChecker.PERMISSION_GRANTED) {
				// Permission is not granted
				//When permission is not granted by user, show them message why this permission is needed.
				if (ActivityCompat.shouldShowRequestPermissionRationale(this, permission[0])) {
					final AlertDialog.Builder alert = new AlertDialog.Builder(this)
							.setTitle(title)
							.setMessage(message)
							.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
								//Give user option to still opt-in the permissions
								ActivityCompat.requestPermissions(id, permission, request);
							});
					alert.show();
				} else {
					ActivityCompat.requestPermissions(id, permission, request);
				}
				return false;
			} else {
				return true;
			}
		} else
			return true;
	}

	public static Context getContext() {
		return AppContext;
	}

	@Override
	protected void attachBaseContext(Context newBase) {
		SharedPreferences sharedPreferences = newBase.getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
		int r = sharedPreferences.getInt(Constants.CONFIG.CONF_LOCALE_ID, 0);
		r = r < Constants.LOCALES_ID.length ? r : (Constants.LOCALES_ID.length - 1);
		String lang = Locale.getDefault().getLanguage();
		if (r > 0) {
			lang = Constants.LOCALES_ID[r];
		}
		super.attachBaseContext(MyContextWrapper.wrap(newBase, lang));
//		super.attachBaseContext(MyContextWrapper.wrap(newBase, "en"));
	}

	@SuppressLint({"ApplySharedPref", "UnspecifiedRegisterReceiverFlag"})
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		sharedPreferences = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE);
//		if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
//			Locale appLocale;
//			int r = sharedPreferences.getInt(Constants.CONFIG.CONF_LOCALE_ID, 0);
//			r = r < Constants.LOCALES_ID.length ? r : (Constants.LOCALES_ID.length - 1);
//			if (r > 0) {
//				appLocale = new Locale(Constants.LOCALES_ID[r]);
//			} else {
//				appLocale = Locale.getDefault();
//			}
//			Locale.setDefault(appLocale);
//			Configuration config = new Configuration();
//			config.setLocale(appLocale);
//			getBaseContext().getResources().updateConfiguration(config,
//					getBaseContext().getResources().getDisplayMetrics());
//		}
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, () -> {});
		}
		super.onCreate(savedInstanceState);
		AppContext = getApplicationContext();
		Thread.setDefaultUncaughtExceptionHandler(new TopExceptionHandler(this));
		setContentView(R.layout.activity_atom_spectra);
		SharedPreferences.Editor prefEditor = sharedPreferences.edit();
		mTextView = findViewById(R.id.cursorView);
		mTextView.setVisibility(TextView.INVISIBLE);
		mLayoutView = findViewById(R.id.suffixLayout);
		mLayoutView.setVisibility(LinearLayout.INVISIBLE);
		seekChannel = findViewById(R.id.seekChannel);
		((TextView)findViewById(R.id.backgroundSuffixView)).setText(AtomSpectraService.BackgroundSpectrum.getSuffix());
		((TextView) findViewById(R.id.suffixView)).setText(AtomSpectraService.ForegroundSpectrum.getSuffix());
		AtomSpectraIsotopes.autoUpdateIsotopes = sharedPreferences.getBoolean(Constants.CONFIG.CONF_AUTO_UPDATE_ISOTOPES, false);

		if (savedInstanceState != null) {
			show_average_cps = savedInstanceState.getInt(ATOM_STATE_AVERAGE, 0);
			logScale = savedInstanceState.getBoolean(ATOM_STATE_LOG, false);
			barMode = savedInstanceState.getBoolean(ATOM_STATE_BAR, false);
//			AtomSpectraService.inputLastState = savedInstanceState.getInt(ATOM_STATE_INPUT, AtomSpectraService.INPUT_NONE);
			zoom_factor = savedInstanceState.getFloat(ATOM_STATE_SCALE, 1);
//			AtomSpectraService.background_show = savedInstanceState.getBoolean(ATOM_STATE_BACKGROUND_SHOW, false) && !AtomSpectraService.BackgroundSpectrum.isEmpty();
			background_subtract = savedInstanceState.getBoolean(ATOM_STATE_BACKGROUND_SUBTRACT, false) && AtomSpectraService.background_show;
			AtomSpectraService.setScaleFactor(savedInstanceState.getInt(Constants.SCALE_FACTOR, Constants.SCALE_DEFAULT));
			cursor_x = savedInstanceState.getInt(ATOM_STATE_CURSOR_X, -1);
			showPlusMinusButtons = savedInstanceState.getBoolean(ATOM_STATE_CURSOR_BUTTONS, false);
			if (showPlusMinusButtons) {
				seekChannel.setVisibility(SeekBar.VISIBLE);
				dateChannelChanged = System.currentTimeMillis();
			}
		} else {
			if (!AtomSpectraService.isStarted || !AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().isCorrect())
				getCalibrationSettings(false,true);
			AtomSpectraService.setScaleFactor(sharedPreferences.getInt(Constants.CONFIG.CONF_SCALE_FACTOR, Constants.SCALE_DEFAULT));
			dateScaleChanged = new Date().getTime();
			showScaleLabel = true;
		}

		fmsButton = findViewById(R.id.fmsButton);
		cpsView = findViewById(R.id.channelText);
		cpsView.setOnLongClickListener(this);
		mAtomSpectraShapeView = findViewById(R.id.shape_area);
//		seekChannel.setLayoutParams(new LinearLayout.LayoutParams(mAtomSpectraShapeView.getLayoutParams().width - 100, seekChannel.getHeight()));
		int coeff = StrictMath.max(seekChannel.getWidth() / 200, 1);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			seekChannel.setProgress(0);
			seekChannel.setMin(-50 * coeff);
			seekChannel.setMax(50 + coeff);
			seekChannel.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
				int init_cursor;

				@Override
				public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
					if (fromUser) {
						int shift_cursor = init_cursor + (1 << StrictMath.max(0, (Constants.SCALE_MAX - AtomSpectraService.getScaleFactor() - 1))) * progress;
						cursor_x = Constants.MinMax(shift_cursor, 0, Constants.NUM_HIST_POINTS - 1);
						showCursorInfo();
						dateChannelChanged = System.currentTimeMillis();
					}
				}

				@Override
				public void onStartTrackingTouch(SeekBar seekBar) {
					init_cursor = cursor_x;
				}

				@Override
				public void onStopTrackingTouch(SeekBar seekBar) {
					seekBar.setProgress(0);
				}
			});
		} else {
			seekChannel.setProgress(50 * coeff);
			seekChannel.setMax(100 * coeff);
			seekChannel.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
				int init_cursor;
				final int scale = coeff;

				@Override
				public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
					if (fromUser) {
						int shift_cursor = init_cursor + (1 << StrictMath.max(0, (Constants.SCALE_MAX - AtomSpectraService.getScaleFactor() - 1))) * (progress - 50 * scale);
						cursor_x = Constants.MinMax(shift_cursor, 0, Constants.NUM_HIST_POINTS - 1);
						showCursorInfo();
						dateChannelChanged = new Date().getTime();
					}
				}

				@Override
				public void onStartTrackingTouch(SeekBar seekBar) {
					init_cursor = cursor_x;
				}

				@Override
				public void onStopTrackingTouch(SeekBar seekBar) {
					seekBar.setProgress(0);
				}
			});
		}

		doseRateText = findViewById(R.id.doserateText);
		doseRateText.setOnLongClickListener(this);

		Button outputSound = findViewById(R.id.nbrButton);
		outputSound.setVisibility((Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) && sharedPreferences.getBoolean(Constants.CONFIG.CONF_OUTPUT_SOUND, false) ? Button.VISIBLE : Button.INVISIBLE);
		initializeGestures();
		buttonsTimer.scheduleAtFixedRate(buttonsTask, 0, 1000);

		hasFeatureGPS = getPackageManager().hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS);
		hasFeatureNetwork = getPackageManager().hasSystemFeature(PackageManager.FEATURE_LOCATION_NETWORK);
		addGPS = sharedPreferences.getBoolean(Constants.CONFIG.CONF_ADD_GPS_TO_FILES, false);

//		if (Locator == null)
//			Locator = new GPSLocator(getApplicationContext());

		if ((hasFeatureGPS || hasFeatureNetwork) && addGPS) {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
				final Activity id = this;
				if (PermissionChecker.checkSelfPermission(id, Manifest.permission.ACCESS_FINE_LOCATION) != PermissionChecker.PERMISSION_GRANTED) {
					// Permission is not granted
					//When permission is not granted by user, show them message why this permission is needed.
					addGPS = false;
					SharedPreferences.Editor edit = sharedPreferences.edit();
					edit.putBoolean(Constants.CONFIG.CONF_ADD_GPS_TO_FILES, false);
					edit.apply();
					final AlertDialog.Builder alert = new AlertDialog.Builder(id)
							.setTitle(getString(R.string.perm_ask_fine_gps_title))
							.setMessage(getString(R.string.perm_ask_fine_gps_text))
							.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> ActivityCompat.requestPermissions(id, new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST_FINE_GPS));
					alert.show();
				}
			}
		}

		//prepare directory to work on Android under 7.0
		//on Android 8.0 and above system picker will be used
		updateDestinationDirectory();

		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

		reducedTo = sharedPreferences.getInt(Constants.CONFIG.CONF_REDUCED_TO, Constants.VIEW_CHANNELS_DEFAULT);
		saveChannels = Constants.MinMax(sharedPreferences.getInt(Constants.CONFIG.CONF_SAVE_CHANNELS, Constants.EXPORT_CHANNELS_DEFAULT), 1024, Constants.NUM_HIST_POINTS);
		loadChannels = Constants.MinMax(sharedPreferences.getInt(Constants.CONFIG.CONF_LOAD_CHANNELS, Constants.LOAD_CHANNELS_DEFAULT), 1024, Constants.NUM_HIST_POINTS);
		channelCompression = sharedPreferences.getInt(Constants.CONFIG.CONF_COMPRESSION, Constants.EXPORT_COMPRESSION_DEFAULT);
		modeNames[0] = getString(R.string.mode_fast_button);
		modeNames[1] = getString(R.string.mode_medium_button);
		modeNames[2] = getString(R.string.mode_slow_button);

		AtomSpectraService.setFirstChannel(sharedPreferences.getInt(Constants.CONFIG.CONF_FIRST_CHANNEL, 0));
		boolean doPowerCheck = sharedPreferences.getBoolean(Constants.CONFIG.CONF_CHECK_POWER, true);

		if (doPowerCheck) {
			final AlertDialog.Builder alert = new AlertDialog.Builder(this)
					.setTitle(getString(R.string.perm_ask_power_title))
					.setMessage(getString(R.string.perm_ask_power_text))
					.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
					});
			alert.show();
		}

		prefEditor.putBoolean(Constants.CONFIG.CONF_CHECK_POWER, false);
		prefEditor.commit();

		barMode = sharedPreferences.getBoolean(Constants.CONFIG.CONF_BAR_MODE, true);

		XCalibrated = sharedPreferences.getBoolean(Constants.CONFIG.CONF_CALIBRATED, true);
		if (XCalibrated) {
			((Button) findViewById(R.id.doseButton)).setText(getText(R.string.mode_compensated_button));
		} else {
			((Button) findViewById(R.id.doseButton)).setText(getText(R.string.mode_uncompensated_button));
		}
		showCursorInfo();
		Intent intent = getIntent();
		inputServiceIntent = new Intent(this, AtomSpectraService.class);
		if (intent != null) {
			UsbDevice device;
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
				device = getIntent().getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
			} else {
				device = getIntent().getParcelableExtra(UsbManager.EXTRA_DEVICE);
			}
			if (device != null)
				inputServiceIntent.putExtra(Constants.USB_DEVICE, device);
			else {
				UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
				if (manager != null) {
					HashMap<String, UsbDevice> deviceList = manager.getDeviceList();
					for (UsbDevice dev : deviceList.values()) {
						if (dev.getVendorId() != 1027)
							continue;
						if (dev.getProductId() == 24577 || dev.getProductId() == 1002) {
							if (manager.hasPermission(dev)) {
								inputServiceIntent.putExtra(Constants.USB_DEVICE, dev);
							} else {
								PendingIntent pi = PendingIntent.getBroadcast(this, 0, new Intent(Constants.ACTION.ACTION_GET_USB_PERMISSION), mutabilityFlag);
								manager.requestPermission(dev, pi);
							}
							break;
						}
					}
				}
			}
		}
		if (intent != null) {
			if (Intent.ACTION_SEND.equals(intent.getAction()) && (intent.getType() != null)) {
				if (intent.getType().equals("text/plain")) {
					Uri file;
					if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
						file = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
					} else {
						file = intent.getParcelableExtra(Intent.EXTRA_STREAM);
					}
					if (file != null) {
						loadHist(file, true, savedInstanceState == null);
						AtomSpectraService.isStarted = true;
						setIntent(new Intent());
					}
				}
			} else if (Intent.ACTION_VIEW.equals(intent.getAction()) && (intent.getType() != null)) {
				if (intent.getType().equals("text/plain")) {
					Uri file = intent.getData();
					if (file != null) {
						loadHist(file, true, savedInstanceState == null);
						AtomSpectraService.isStarted = true;
						setIntent(new Intent());
					}
				}
			}
		}

		inputServiceIntent.setAction(Constants.ACTION.ACTION_START_FOREGROUND);

//		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//			getApplicationContext().startForegroundService(inputServiceIntent);
//		} else {
//			getApplicationContext().startService(inputServiceIntent);
//		}
//		getApplicationContext().bindService(inputServiceIntent, mServiceConnection, BIND_IMPORTANT);

//		if (savedInstanceState != null) {
//			sendBroadcast(new Intent(Constants.ACTION.ACTION_FREEZE_DATA).putExtra(AtomSpectraSerial.EXTRA_DATA_TYPE, savedInstanceState.getBoolean(Constants.FREEZE_STATE, true)));
//		}

		//check permissions
		if (checkPermissions(new String[]{Manifest.permission.RECORD_AUDIO},
				getString(R.string.perm_ask_audio_title),
				getString(R.string.perm_ask_audio_text),
				REQUEST_AUDIO)) {
			AtomSpectraService.canOpenAudio = true;
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				getApplicationContext().startForegroundService(inputServiceIntent);
			} else {
				getApplicationContext().startService(inputServiceIntent);
			}
			getApplicationContext().bindService(inputServiceIntent, mServiceConnection, BIND_IMPORTANT);
			inputServiceIntent = null;
		} else {
			if (sharedPreferences.getBoolean(Constants.CONFIG.CONF_CHECK_AUDIO, true)) {
				Toast.makeText(this, getText(R.string.perm_ask_audio_text), Toast.LENGTH_LONG).show();
				prefEditor.putBoolean(Constants.CONFIG.CONF_CHECK_AUDIO, false);
				prefEditor.commit();
			}
		}

		fmsButton.setText(modeNames[sharedPreferences.getInt(Constants.CONFIG.CONF_SEARCH_MODE, 0)]);

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			registerReceiver(mDataUpdateReceiver, makeAtomSpectraUpdateIntentFilter(), Context.RECEIVER_NOT_EXPORTED);
		} else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			registerReceiver(mDataUpdateReceiver, makeAtomSpectraUpdateIntentFilter(), 0);
		} else {
			registerReceiver(mDataUpdateReceiver, makeAtomSpectraUpdateIntentFilter());
		}
	}

	@Override
	protected void onNewIntent(Intent intent) {
		if (intent != null) {
			setIntent(intent);
			if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
				UsbDevice device = null;
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
					device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
				} else {
					device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
				}
				if ((device != null)) {
					Context context = getContext();
					if ((device.getVendorId() == 1027) && (device.getDeviceId() == 1002 || device.getDeviceId() == 24577)) {
						UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
						if (!usbManager.hasPermission(device)) {
							PendingIntent pi = PendingIntent.getBroadcast(context, 0, new Intent(Constants.ACTION.ACTION_GET_USB_PERMISSION), mutabilityFlag);
							usbManager.requestPermission(device, pi);
						} else {
							Intent intentAttached = new Intent(Constants.ACTION.ACTION_SOURCE_CHANGED).setPackage(Constants.PACKAGE_NAME);
							intentAttached.putExtra(AtomSpectraService.EXTRA_SOURCE, AtomSpectraService.EXTRA_SOURCE_USB);
							intentAttached.putExtra(Constants.USB_DEVICE, device);
							context.sendBroadcast(intentAttached);
							context.sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_SETTINGS).setPackage(Constants.PACKAGE_NAME));
						}
					}
				}
			} else if (Intent.ACTION_SEND.equals(intent.getAction()) && (intent.getType() != null)) {
				if (intent.getType().equals("text/plain")) {
					Uri file;
					if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
						file = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
					} else {
						file = intent.getParcelableExtra(Intent.EXTRA_STREAM);
					}
					if (file != null) {
						loadHist(file, true, true);
						AtomSpectraService.isStarted = true;
						setIntent(new Intent());
					}
				}
			} else if (Intent.ACTION_VIEW.equals(intent.getAction()) && (intent.getType() != null)) {
				if (intent.getType().equals("text/plain")) {
					Uri file = intent.getData();
					if (file != null) {
						loadHist(file, true, true);
						AtomSpectraService.isStarted = true;
						setIntent(new Intent());
					}
				}
			}
		}
		super.onNewIntent(intent);
	}

	@SuppressLint("ApplySharedPref")
	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
		switch (requestCode) {
			case REQUEST_AUDIO:
				if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
					AtomSpectraService.canOpenAudio = true;
					if (inputServiceIntent != null) {
						if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
							getApplicationContext().startForegroundService(inputServiceIntent);
						} else {
							getApplicationContext().startService(inputServiceIntent);
						}
						getApplicationContext().bindService(inputServiceIntent, mServiceConnection, BIND_IMPORTANT);
						inputServiceIntent = null;
					}
				} else {
					Toast.makeText(this, getString(R.string.perm_no_audio), Toast.LENGTH_LONG).show();
					if (AtomSpectraService.isStarted) {
						sendBroadcast(new Intent(Constants.ACTION.ACTION_STOP_FOREGROUND).setComponent(getComponentName()).setPackage(Constants.PACKAGE_NAME));
					}
					inputServiceIntent = null;
				}
				break;
			case REQUEST_READ_HIST:
				if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
					Intent loadIntent = new Intent()
							.setType("*/*")
							.setAction(Intent.ACTION_GET_CONTENT);
					startActivityForResult(Intent.createChooser(loadIntent, getString(R.string.ask_select_histogram)), LOAD_HIST_CODE);
				} else
					Toast.makeText(this, getString(R.string.perm_no_read_histogram), Toast.LENGTH_LONG).show();
				break;
			case REQUEST_ADD_HIST:
				if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
					Intent loadIntent = new Intent()
							.setType("*/*")
							.setAction(Intent.ACTION_GET_CONTENT);
					startActivityForResult(Intent.createChooser(loadIntent, getString(R.string.ask_select_histogram)), ADD_HIST_CODE);
				} else
					Toast.makeText(this, getString(R.string.perm_no_read_histogram), Toast.LENGTH_LONG).show();
				break;
			case REQUEST_READ_BACK:
				if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
					loadBackground(null);
				} else
					Toast.makeText(this, getString(R.string.perm_no_read_background), Toast.LENGTH_LONG).show();
				break;
			case REQUEST_READ_BACK_FROM:
				if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
					Intent loadIntent = new Intent()
							.setType("*/*")
							.setAction(Intent.ACTION_GET_CONTENT);
					startActivityForResult(Intent.createChooser(loadIntent, getString(R.string.ask_select_background)), LOAD_BACK_CODE);
				} else
					Toast.makeText(this, getString(R.string.perm_no_read_background), Toast.LENGTH_LONG).show();
				break;
			case REQUEST_READ_CAL:
				if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
					Intent calibrationIntent = new Intent()
							.setType("*/*")
							.setAction(Intent.ACTION_GET_CONTENT);
					startActivityForResult(Intent.createChooser(calibrationIntent, getString(R.string.ask_select_calibration)), LOAD_CALIBRATION_CODE);
				} else
					Toast.makeText(this, getString(R.string.perm_no_read_calibration), Toast.LENGTH_LONG).show();
				break;
			case REQUEST_WRITE_HIST:
				if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
					try {
						final AlertDialog.Builder alert = new AlertDialog.Builder(this);
						alert.setTitle(getString(R.string.ask_spectrum_suffix));
						alert.setMessage(getString(R.string.ask_suffix_text));

						final EditText input = new EditText(this);
						input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
						input.setImeOptions(EditorInfo.IME_ACTION_DONE);
						input.setText(AtomSpectraService.ForegroundSpectrum.getSuffix(), TextView.BufferType.EDITABLE);
						alert.setView(input);
						alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
							AtomSpectraService.ForegroundSpectrum.setSuffix(input.getText().toString());
							TextView text = findViewById(R.id.suffixView);
							text.setText(AtomSpectraService.ForegroundSpectrum.getSuffix());
							saveHist(AtomSpectraService.ForegroundSpectrum.getSuffix());
						});
						alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
						});
						alert.show();
					} catch (Exception e) {
						Log.d(TAG, "saving spectrum file FAIL");
					}
				} else
					Toast.makeText(this, getString(R.string.perm_no_write_histogram), Toast.LENGTH_LONG).show();
				break;
			case REQUEST_WRITE_BACK:
				if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
					saveBackground();
				} else
					Toast.makeText(this, getString(R.string.perm_no_write_background), Toast.LENGTH_LONG).show();
				break;
			case REQUEST_EXPORT:
				if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
					try {
						final AlertDialog.Builder alert = new AlertDialog.Builder(this);
						alert.setTitle(getString(R.string.ask_export_suffix));
						alert.setMessage(getString(R.string.ask_suffix_text));

						final EditText input = new EditText(this);
						input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
						input.setImeOptions(EditorInfo.IME_ACTION_DONE);
						input.setText(AtomSpectraService.ForegroundSpectrum.getSuffix(), TextView.BufferType.EDITABLE);
						alert.setView(input);
						alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
							AtomSpectraService.ForegroundSpectrum.setSuffix(input.getText().toString());
							TextView text = findViewById(R.id.suffixView);
							text.setText(AtomSpectraService.ForegroundSpectrum.getSuffix());
							saveCSV(AtomSpectraService.ForegroundSpectrum.getSuffix(), false);
						});
						alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
						});
						alert.show();
					} catch (Exception e) {
						Log.d(TAG, "exporting file FAIL");
					}
				} else
					Toast.makeText(this, getString(R.string.perm_no_write_export), Toast.LENGTH_LONG).show();
				break;
			case REQUEST_EXPORT_E:
				if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
					try {
						final AlertDialog.Builder alert = new AlertDialog.Builder(this);
						alert.setTitle(getString(R.string.ask_export_suffix));
						alert.setMessage(getString(R.string.ask_suffix_text));

						final EditText input = new EditText(this);
						input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
						input.setImeOptions(EditorInfo.IME_ACTION_DONE);
						input.setText(AtomSpectraService.ForegroundSpectrum.getSuffix(), TextView.BufferType.EDITABLE);
						alert.setView(input);
						alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
							AtomSpectraService.ForegroundSpectrum.setSuffix(input.getText().toString());
							TextView text = findViewById(R.id.suffixView);
							text.setText(AtomSpectraService.ForegroundSpectrum.getSuffix());
							saveCSV(AtomSpectraService.ForegroundSpectrum.getSuffix(), true);
						});
						alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
						});
						alert.show();
					} catch (Exception e) {
						Log.d(TAG, "exporting with energy file FAIL");
					}
				} else
					Toast.makeText(this, getString(R.string.perm_no_write_export_energy), Toast.LENGTH_LONG).show();
				break;
			case REQUEST_EXPORT_BQMONI:
				if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
					try {
						final AlertDialog.Builder alert = new AlertDialog.Builder(this);
						alert.setTitle(getString(R.string.ask_export_bqmoni));
						alert.setMessage(getString(R.string.ask_suffix_text));

						final EditText input = new EditText(this);
						input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
						input.setImeOptions(EditorInfo.IME_ACTION_DONE);
						input.setText(AtomSpectraService.ForegroundSpectrum.getSuffix(), TextView.BufferType.EDITABLE);
						alert.setView(input);
						alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
							AtomSpectraService.ForegroundSpectrum.setSuffix(input.getText().toString());
							TextView text = findViewById(R.id.suffixView);
							text.setText(AtomSpectraService.ForegroundSpectrum.getSuffix());
							saveBqMoni(AtomSpectraService.ForegroundSpectrum.getSuffix());
						});
						alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
						});
						alert.show();
					} catch (Exception e) {
						Log.d(TAG, "exporting file FAIL");
					}
				} else
					Toast.makeText(this, getString(R.string.perm_no_write_bqmoni), Toast.LENGTH_LONG).show();
				break;
			case REQUEST_EXPORT_SPE:
				if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
					try {
						final AlertDialog.Builder alert = new AlertDialog.Builder(this);
						alert.setTitle(getString(R.string.ask_export_spe));
						alert.setMessage(getString(R.string.ask_suffix_text));

						final EditText input = new EditText(this);
						input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
						input.setImeOptions(EditorInfo.IME_ACTION_DONE);
						input.setText(AtomSpectraService.ForegroundSpectrum.getSuffix(), TextView.BufferType.EDITABLE);
						alert.setView(input);
						alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
							AtomSpectraService.ForegroundSpectrum.setSuffix(input.getText().toString());
							TextView text = findViewById(R.id.suffixView);
							text.setText(AtomSpectraService.ForegroundSpectrum.getSuffix());
							saveSPE(AtomSpectraService.ForegroundSpectrum.getSuffix());
						});
						alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
						});
						alert.show();
					} catch (Exception e) {
						Log.d(TAG, "exporting file FAIL");
					}
				} else
					Toast.makeText(this, getString(R.string.perm_no_write_spe), Toast.LENGTH_LONG).show();
				break;
			case REQUEST_EXPORT_N42:
				if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
					try {
						final AlertDialog.Builder alert = new AlertDialog.Builder(this);
						alert.setTitle(getString(R.string.ask_export_N42));
						alert.setMessage(getString(R.string.ask_suffix_text));

						final EditText input = new EditText(this);
						input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
						input.setImeOptions(EditorInfo.IME_ACTION_DONE);
						input.setText(AtomSpectraService.ForegroundSpectrum.getSuffix(), TextView.BufferType.EDITABLE);
						alert.setView(input);
						alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
							AtomSpectraService.ForegroundSpectrum.setSuffix(input.getText().toString());
							TextView text = findViewById(R.id.suffixView);
							text.setText(AtomSpectraService.ForegroundSpectrum.getSuffix());
							saveN42(AtomSpectraService.ForegroundSpectrum.getSuffix());
						});
						alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
						});
						alert.show();
					} catch (Exception e) {
						Log.d(TAG, "exporting file FAIL");
					}
				} else
					Toast.makeText(this, getString(R.string.perm_no_write_N42), Toast.LENGTH_LONG).show();
				break;
			case REQUEST_SHARE:
				if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
					Intent loadIntent = new Intent()
							.setType("*/*")
							.setAction(Intent.ACTION_GET_CONTENT);
					startActivityForResult(Intent.createChooser(loadIntent, getString(R.string.ask_share)), SHARE_FILE_CODE);
				} else
					Toast.makeText(this, getString(R.string.perm_no_share), Toast.LENGTH_LONG).show();
				break;
			case REQUEST_FINE_GPS:
				if (grantResults.length > 1 && grantResults[0] != PackageManager.PERMISSION_GRANTED && grantResults[1] != PackageManager.PERMISSION_GRANTED) {
//					AtomSpectraService.Locator.startUsingGPS();
//					if (!AtomSpectraService.Locator.hasGPS) {
//						AtomSpectraService.Locator.stopUsingGPS();
//						final AlertDialog.Builder alert = new AlertDialog.Builder(this)
//								.setTitle(getString(R.string.perm_ask_gps_off_title))
//								.setMessage(getString(R.string.perm_ask_gps_off_text))
//								.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
//								});
//						alert.show();
//						SharedPreferences.Editor editor = sharedPreferences.edit();
//						editor.putBoolean(Constants.CONFIG.CONF_ADD_GPS_TO_FILES, false);
//						editor.commit();
//						sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_SETTINGS));
//					}
//				} else {
					SharedPreferences.Editor editor = sharedPreferences.edit();
					editor.putBoolean(Constants.CONFIG.CONF_ADD_GPS_TO_FILES, false);
					editor.commit();
					sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_SETTINGS).setPackage(Constants.PACKAGE_NAME));
					Toast.makeText(this, getString(R.string.perm_no_gps), Toast.LENGTH_LONG).show();
				}
				sendBroadcast(new Intent(Constants.ACTION.ACTION_CHECK_GPS_AVAILABILITY).setPackage(Constants.PACKAGE_NAME));
				break;
			case REQUEST_READ_DEVICE:
				if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
					Intent loadIntent = new Intent()
							.setType("*/*")
							.setAction(Intent.ACTION_GET_CONTENT);
					startActivityForResult(Intent.createChooser(loadIntent, getString(R.string.ask_select_histogram)), SELECT_LOAD_DEVICE_CODE);
				} else
					Toast.makeText(this, getString(R.string.perm_no_read_device), Toast.LENGTH_LONG).show();
				break;
			case REQUEST_WRITE_DEVICE:
				if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
					try {
						final AlertDialog.Builder alert = new AlertDialog.Builder(this);
						alert.setTitle(getString(R.string.ask_device_suffix));
						alert.setMessage(getString(R.string.ask_suffix_text));

						final EditText input = new EditText(this);
						input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
						input.setImeOptions(EditorInfo.IME_ACTION_DONE);
						alert.setView(input);
						alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
							String value = input.getText().toString();
							saveDevice(value);
						});
						alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
						});
						alert.show();
					} catch (Exception e) {
						Log.d(TAG, "saving spectrum file FAIL");
					}
				} else
					Toast.makeText(this, getString(R.string.perm_no_write_device), Toast.LENGTH_LONG).show();
				break;
			default:
				super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		}
	}

	@Override
	public void onStart() {
		super.onStart();
		active = true;
		Log.d(TAG, "-XxX-  onStart");
	}

	//Update destination directory on Android 7.0
	private void updateDestinationDirectory() {
		//prepare directory to work on Android under 7.0
		//on Android 8.0 and above system picker will be used
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
			SharedPreferences.Editor prefEditor = sharedPreferences.edit();
			boolean directoryFound = false;
			try {
				String state = Environment.getExternalStorageState();
				if (Environment.MEDIA_MOUNTED.equals(state)) {
// get path
					File folder = new File(Environment.getExternalStorageDirectory() + "/AtomSpectra");
// create folder if not exist
					if (!folder.exists()) {
						if (folder.mkdir()) {
							prefEditor.putString(Constants.CONFIG.CONF_DIRECTORY_SELECTED, folder.toString());
							directoryFound = true;
						} else {
							folder = getApplicationContext().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
							if (folder != null && folder.exists()) {
								prefEditor.putString(Constants.CONFIG.CONF_DIRECTORY_SELECTED, folder.toString());
								directoryFound = true;
							}
						}
					} else {
						if (folder.isDirectory() && folder.canRead() && folder.canWrite()) {
							prefEditor.putString(Constants.CONFIG.CONF_DIRECTORY_SELECTED, folder.toString());
							directoryFound = true;
						}
					}
				}
				if (!directoryFound) {
					File folder = getApplicationContext().getFilesDir();
					prefEditor.putString(Constants.CONFIG.CONF_DIRECTORY_SELECTED, folder.toString());
				}
			} catch (Exception e) {
				prefEditor.remove(Constants.CONFIG.CONF_DIRECTORY_SELECTED);
				Toast.makeText(this, getString(R.string.storage_required), Toast.LENGTH_LONG).show();
			}
			prefEditor.apply();
		}
	}

	private void updateCalibrationMenu() {
		if (app_menu != null) {
			double[] coeffs = AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().getCoeffArray(5);
			app_menu.findItem(R.id.action_cal_point_1).setTitle(String.format(Locale.getDefault(), "c0: %.12g", coeffs[0]));
			app_menu.findItem(R.id.action_cal_point_2).setTitle(String.format(Locale.getDefault(), "c1: %.12g", coeffs[1]));
			app_menu.findItem(R.id.action_cal_point_3).setTitle(String.format(Locale.getDefault(), "c2: %.12g", coeffs[2]));
			app_menu.findItem(R.id.action_cal_point_4).setTitle(String.format(Locale.getDefault(), "c3: %.12g", coeffs[3]));
			app_menu.findItem(R.id.action_cal_point_5).setTitle(String.format(Locale.getDefault(), "c4: %.12g", coeffs[4]));

			Button button = findViewById(R.id.addPointButton);
			if (AtomSpectraService.newCalibration.getLines() >= Constants.MAX_CALIBRATION_POINTS) {
				button.setText("-");
				button.setEnabled(false);
				app_menu.findItem(R.id.action_cal_add_point).setEnabled(false);
			} else {
				button.setText(String.format(Locale.US, "%d", AtomSpectraService.newCalibration.getLines() + 1));
				app_menu.findItem(R.id.action_cal_add_point).setEnabled(true);
			}
			app_menu.findItem(R.id.action_cal_calc).setEnabled(AtomSpectraService.newCalibration.getLines() > 1);
			findViewById(R.id.calibrateButton).setEnabled(AtomSpectraService.newCalibration.getLines() > 1);
			if (AtomSpectraService.newCalibration.getLines() < 2) {
				app_menu.findItem(R.id.action_cal_draw_function).setChecked(false).setEnabled(false);
				AtomSpectraService.showCalibrationFunction = false;
			}
			app_menu.findItem(R.id.action_cal_new_point1).
					setTitle(getString(R.string.cal_show_line, AtomSpectraService.newCalibration.getChannel(0), AtomSpectraService.newCalibration.getEnergy(0))).
					setVisible(AtomSpectraService.newCalibration.getLines() > 0);
			app_menu.findItem(R.id.action_cal_new_point2).
					setTitle(getString(R.string.cal_show_line, AtomSpectraService.newCalibration.getChannel(1), AtomSpectraService.newCalibration.getEnergy(1))).
					setVisible(AtomSpectraService.newCalibration.getLines() > 1);
			app_menu.findItem(R.id.action_cal_new_point3).
					setTitle(getString(R.string.cal_show_line, AtomSpectraService.newCalibration.getChannel(2), AtomSpectraService.newCalibration.getEnergy(2))).
					setVisible(AtomSpectraService.newCalibration.getLines() > 2);
			app_menu.findItem(R.id.action_cal_new_point4).
					setTitle(getString(R.string.cal_show_line, AtomSpectraService.newCalibration.getChannel(3), AtomSpectraService.newCalibration.getEnergy(3))).
					setVisible(AtomSpectraService.newCalibration.getLines() > 3);
			app_menu.findItem(R.id.action_cal_new_point5).
					setTitle(getString(R.string.cal_show_line, AtomSpectraService.newCalibration.getChannel(4), AtomSpectraService.newCalibration.getEnergy(4))).
					setVisible(AtomSpectraService.newCalibration.getLines() > 4);
			app_menu.findItem(R.id.action_cal_new_point6).
					setTitle(getString(R.string.cal_show_line, AtomSpectraService.newCalibration.getChannel(5), AtomSpectraService.newCalibration.getEnergy(5))).
					setVisible(AtomSpectraService.newCalibration.getLines() > 5);
			app_menu.findItem(R.id.action_cal_new_point7).
					setTitle(getString(R.string.cal_show_line, AtomSpectraService.newCalibration.getChannel(6), AtomSpectraService.newCalibration.getEnergy(6))).
					setVisible(AtomSpectraService.newCalibration.getLines() > 6);
			app_menu.findItem(R.id.action_cal_new_point8).
					setTitle(getString(R.string.cal_show_line, AtomSpectraService.newCalibration.getChannel(7), AtomSpectraService.newCalibration.getEnergy(7))).
					setVisible(AtomSpectraService.newCalibration.getLines() > 7);
			app_menu.findItem(R.id.action_cal_new_point9).
					setTitle(getString(R.string.cal_show_line, AtomSpectraService.newCalibration.getChannel(8), AtomSpectraService.newCalibration.getEnergy(8))).
					setVisible(AtomSpectraService.newCalibration.getLines() > 8);
			app_menu.findItem(R.id.action_cal_new_point10).
					setTitle(getString(R.string.cal_show_line, AtomSpectraService.newCalibration.getChannel(9), AtomSpectraService.newCalibration.getEnergy(9))).
					setVisible(AtomSpectraService.newCalibration.getLines() > 9);
		}
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.atom_spectra, menu);
		app_menu = menu;
		boolean enable_back = !AtomSpectraService.BackgroundSpectrum.isEmpty();
		menu.findItem(R.id.action_cal_channel).setTitle(getString(R.string.calibration_channel_format, AtomSpectraService.lastCalibrationChannel));

		menu.findItem(R.id.action_background_show).setChecked(AtomSpectraService.background_show);
		menu.findItem(R.id.action_background_show).setEnabled(enable_back);
		app_menu.findItem(R.id.action_background_suffix).setEnabled(enable_back);
		menu.findItem(R.id.action_background_subtract).setEnabled(AtomSpectraService.background_show);
		menu.findItem(R.id.action_background_subtract).setChecked(background_subtract);
		menu.findItem(R.id.action_background_clear).setEnabled(enable_back);
		menu.findItem(R.id.action_background_save).setEnabled(enable_back);
		menu.findItem(R.id.action_cal_draw_function).setChecked(AtomSpectraService.showCalibrationFunction);
		menu.findItem(R.id.action_cal_draw_function).setEnabled(AtomSpectraService.newCalibration != null && AtomSpectraService.newCalibration.getLines() > 1);

		menu.findItem(R.id.action_hist_smooth).setTitle(AtomSpectraService.setSmooth ? getString(R.string.hist_unsmooth) : getString(R.string.hist_smooth));
		updateCalibrationMenu();
		TextView view = findViewById(R.id.backgroundSuffixView);
		view.setVisibility(enable_back ? TextView.VISIBLE : TextView.INVISIBLE);

		if (AtomSpectraService.getFreeze()) {
			menu.findItem(R.id.action_hist_freeze).setIcon(R.drawable.record);
			menu.findItem(R.id.action_hist_freeze).setTitle(R.string.hist_continue_update);
		} else {
			menu.findItem(R.id.action_hist_freeze).setIcon(R.drawable.menu_block);
			menu.findItem(R.id.action_hist_freeze).setTitle(R.string.hist_freeze_update);
		}

		return true;
	}


	private static IntentFilter makeAtomSpectraUpdateIntentFilter() {
		final IntentFilter intentFilter = new IntentFilter();
		intentFilter.addAction(AtomSpectraService.ACTION_DATA_AVAILABLE);
		intentFilter.addAction(Constants.ACTION.ACTION_UPDATE_GPS);
		intentFilter.addAction(Constants.ACTION.ACTION_CLOSE_APP);
		intentFilter.addAction(Constants.ACTION.ACTION_AUDIO_CHANGED);
		intentFilter.addAction(Constants.ACTION.ACTION_UPDATE_MENU);
		intentFilter.addAction(Constants.ACTION.ACTION_GET_USB_PERMISSION);
		intentFilter.addAction(Constants.ACTION.ACTION_HAS_ANSWER);
		intentFilter.addAction(Constants.ACTION.ACTION_UPDATE_CALIBRATION);
//		intentFilter.addAction(Constants.ACTION.ACTION_CHECK_GPS_AVAILABILITY);
		return intentFilter;
	}

	private final BroadcastReceiver mDataUpdateReceiver = new BroadcastReceiver() {
		@SuppressLint("ApplySharedPref")
		@Override
		public void onReceive(Context context, Intent intent) {
			final String action = intent.getAction();
			if (AtomSpectraService.ACTION_DATA_AVAILABLE.equals(action)) {
				Bundle mBundle = intent.getExtras();
				if (mBundle != null) {
					double[] histogram = mBundle.getDoubleArray(AtomSpectraService.EXTRA_DATA_ARRAY_LONG_COUNTS);
					double[] hist_back = mBundle.getDoubleArray(AtomSpectraService.EXTRA_DATA_ARRAY_BACK_COUNTS);
					boolean show_back = mBundle.getBoolean(AtomSpectraService.EXTRA_DATA_SHOW_BACK_COUNTS);

					int cps = mBundle.getInt(AtomSpectraService.EXTRA_DATA_INT_CPS);
					int cps_interval = mBundle.getInt(AtomSpectraService.EXTRA_DATA_INT_CPS_INTERVAL);

					double doserate = mBundle.getDouble(AtomSpectraService.EXTRA_DATA_DOSERATE_SEARCH);
					double total_time = mBundle.getDouble(AtomSpectraService.EXTRA_DATA_TOTAL_TIME);

					switch (show_average_cps) {
						case SHOW_AVERAGE:
							cpsView.setText(getString(R.string.cps_average_show, total_time > 1 ? mBundle.getLong(AtomSpectraService.EXTRA_DATA_LONG_COUNTS) / total_time : 0));
							doseRateText.setText(getString(R.string.total_time_format, total_time));
							break;
						case SHOW_CPS:
							cpsView.setText(getString(R.string.cps_show, cps, cps_interval));
							if (doserate > 1000)
								doseRateText.setText(getString(R.string.dose_rate_mSv_format, doserate / 1000.0));
							else
								doseRateText.setText(getString(R.string.dose_rate_format, doserate));
							break;
						case SHOW_BASE:
							cpsView.setText(getString(R.string.cps_base_show, (int) AtomSpectraService.getCpsBaseLevel()));
							doseRateText.setText(getString(R.string.cps_signal_show, (int) AtomSpectraService.getCpsBaseSignal()));
							break;
						case SHOW_COORD:
							if (AtomSpectraService.ForegroundSpectrum.getGPSDate() == 0) {
								cpsView.setText("──────");
								doseRateText.setText("──────");
							} else {
								cpsView.setText(GPSLocator.getFormattedLatitude(AtomSpectraService.ForegroundSpectrum.getLatitude()));
								doseRateText.setText(GPSLocator.getFormattedLongitude(AtomSpectraService.ForegroundSpectrum.getLongitude()));
							}
					}

					showCursorInfo();

					//mAtomSpectraShapeView.showShape(counts_array,array_length);
					int num_first_channel = AtomSpectraService.getFirstChannel();
					int num_scale_factor = AtomSpectraService.getScaleFactor();
					if (AtomSpectraService.showCalibrationFunction && num_scale_factor < Constants.SCALE_COUNT_MODE) {
						if (AtomSpectraService.getScaleFactor() > Constants.SCALE_MAX) {
							AtomSpectraService.restoreScaleFactor();
							num_scale_factor = AtomSpectraService.getScaleFactor();
						}
						mAtomSpectraShapeView.showShape(
								histogram,
								hist_back,
								false,
								false,
								false,
								1024,
								1024,
								false,
								false,
								num_first_channel,
								num_first_channel + (Constants.WINDOW_OUTPUT_SIZE << (Constants.SCALE_MAX - num_scale_factor)),
								getString(R.string.graph_show_channel),
								zoom_factor,
								num_scale_factor,
								false,
								-1,
								sharedPreferences.getInt(Constants.CONFIG.CONF_MIN_POINTS, Constants.MIN_FRONT_POINTS_DEFAULT),
								sharedPreferences.getInt(Constants.CONFIG.CONF_MAX_POINTS, Constants.MAX_FRONT_POINTS_DEFAULT));
					} else {
						if (XCalibrated)
							if (num_scale_factor <= Constants.SCALE_MAX) {
								mAtomSpectraShapeView.showShape(
										histogram,
										hist_back,
										show_back,
										background_subtract,
										true,
										1024,
										reducedTo,
										logScale,
										barMode,
										(float) AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().getEnergyFromEnergyChannel(num_first_channel, AtomSpectraService.lastCalibrationChannel),
										(float) AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().getEnergyFromEnergyChannel(num_first_channel + (Constants.WINDOW_OUTPUT_SIZE << (Constants.SCALE_MAX - num_scale_factor)), AtomSpectraService.lastCalibrationChannel),
										getString(R.string.graph_show_kev),
										zoom_factor,
										num_scale_factor,
										false,
										(float) AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().toEnergy(cursor_x),
										sharedPreferences.getInt(Constants.CONFIG.CONF_MIN_POINTS, Constants.MIN_FRONT_POINTS_DEFAULT),
										sharedPreferences.getInt(Constants.CONFIG.CONF_MAX_POINTS, Constants.MAX_FRONT_POINTS_DEFAULT));
							} else {
								mAtomSpectraShapeView.showShape(
										histogram,
										hist_back,
										false,
										false,
										false,
										1024,
										reducedTo,
										false,
										barMode,
										0,
										1024,
										getString(R.string.graph_show_points),
										zoom_factor,
										num_scale_factor,
										false,
										-1,
										sharedPreferences.getInt(Constants.CONFIG.CONF_MIN_POINTS, Constants.MIN_FRONT_POINTS_DEFAULT),
										sharedPreferences.getInt(Constants.CONFIG.CONF_MAX_POINTS, Constants.MAX_FRONT_POINTS_DEFAULT));
							}
						else if (num_scale_factor <= Constants.SCALE_MAX) {
							mAtomSpectraShapeView.showShape(
									histogram,
									hist_back,
									show_back,
									background_subtract,
									false,
									1024,
									reducedTo,
									logScale,
									barMode,
									num_first_channel,
									num_first_channel + (Constants.WINDOW_OUTPUT_SIZE << (Constants.SCALE_MAX - num_scale_factor)),
									getString(R.string.graph_show_channel),
									zoom_factor,
									num_scale_factor,
									false,
									(float) cursor_x,
									sharedPreferences.getInt(Constants.CONFIG.CONF_MIN_POINTS, Constants.MIN_FRONT_POINTS_DEFAULT),
									sharedPreferences.getInt(Constants.CONFIG.CONF_MAX_POINTS, Constants.MAX_FRONT_POINTS_DEFAULT));
						} else {
							mAtomSpectraShapeView.showShape(
									histogram,
									hist_back,
									false,
									false,
									false,
									1024,
									reducedTo,
									false,
									barMode,
									0,
									1024,
									getString(R.string.graph_show_points),
									zoom_factor,
									num_scale_factor,
									false,
									-1,
									sharedPreferences.getInt(Constants.CONFIG.CONF_MIN_POINTS, Constants.MIN_FRONT_POINTS_DEFAULT),
									sharedPreferences.getInt(Constants.CONFIG.CONF_MAX_POINTS, Constants.MAX_FRONT_POINTS_DEFAULT));
						}
					}
				}
			}
			if (Constants.ACTION.ACTION_UPDATE_GPS.equals(action)) {
//				if (AtomSpectraService.isStarted && !AtomSpectraService.getFreeze()) {
//					AtomSpectraService.ForegroundSpectrum.setLocation(Locator.getLocation()).updateComments();
//				}
			}
			if (Constants.ACTION.ACTION_CLOSE_APP.equals(action)) {
				finishAndRemoveTask();
			}
			if (Constants.ACTION.ACTION_AUDIO_CHANGED.equals(action)) {
				if (!AtomSpectraService.getFreeze() && AtomSpectraService.inputType == AtomSpectraService.INPUT_AUDIO) {
					app_menu.findItem(R.id.action_hist_freeze).setIcon(R.drawable.record);
					app_menu.findItem(R.id.action_hist_freeze).setTitle(R.string.hist_continue_update);
					sendBroadcast(new Intent(Constants.ACTION.ACTION_FREEZE_DATA).putExtra(AtomSpectraSerial.EXTRA_DATA_TYPE, true).setPackage(Constants.PACKAGE_NAME));
					Toast.makeText(context, getString(R.string.hist_stop_record), Toast.LENGTH_LONG).show();
				}
			}
			if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
				UsbDevice device;
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
					device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
				} else {
					device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
				}
				if ((device != null)) {
					if ((device.getVendorId() == 1027) && (device.getDeviceId() == 1002 || device.getDeviceId() == 24577)) {
						UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
						if (!usbManager.hasPermission(device)) {
							PendingIntent pi = PendingIntent.getBroadcast(context, 0, new Intent(Constants.ACTION.ACTION_GET_USB_PERMISSION), mutabilityFlag);
							usbManager.requestPermission(device, pi);
						} else {
							Intent intentAttached = new Intent(Constants.ACTION.ACTION_SOURCE_CHANGED).setPackage(Constants.PACKAGE_NAME);
							intentAttached.putExtra(AtomSpectraService.EXTRA_SOURCE, AtomSpectraService.EXTRA_SOURCE_USB);
							intentAttached.putExtra(Constants.USB_DEVICE, device);
							context.sendBroadcast(intentAttached);
						}
					}
				}
			}
			if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
				if (app_menu != null) {
					app_menu.findItem(R.id.action_hist_freeze).setIcon(R.drawable.record);
					app_menu.findItem(R.id.action_hist_freeze).setTitle(R.string.hist_continue_update);
				}
				Intent intentDetached = new Intent(Constants.ACTION.ACTION_SOURCE_CHANGED).setPackage(Constants.PACKAGE_NAME);
				sendBroadcast(new Intent(Constants.ACTION.ACTION_FREEZE_DATA).putExtra(AtomSpectraSerial.EXTRA_DATA_TYPE, true).setPackage(Constants.PACKAGE_NAME));
				intentDetached.putExtra(AtomSpectraService.EXTRA_SOURCE, AtomSpectraService.EXTRA_SOURCE_AUDIO);
				context.sendBroadcast(intentDetached);
			}
			if (Constants.ACTION.ACTION_GET_USB_PERMISSION.equals(action)) {
				synchronized (this) {
					if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
						UsbDevice device;
						if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
							device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
						} else {
							device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
						}
						Intent intentAttached = new Intent(Constants.ACTION.ACTION_SOURCE_CHANGED).setPackage(Constants.PACKAGE_NAME);

						intentAttached.putExtra(AtomSpectraService.EXTRA_SOURCE, AtomSpectraService.EXTRA_SOURCE_USB);
						intentAttached.putExtra(Constants.USB_DEVICE, device);
						context.sendBroadcast(intentAttached);
					}
				}
			}
			if (Constants.ACTION.ACTION_HAS_ANSWER.equals(action)) {
				String id = intent.getStringExtra(AtomSpectraSerial.EXTRA_ID);
				String data = intent.getStringExtra(AtomSpectraSerial.EXTRA_RESULT);
				if (data != null) {
					if (GET_CALIBRATION.equals(id)) {
						if (!AtomSpectraSerial.COMMAND_RESULT_TIMEOUT.equals(data)) {
							final String[] dataArray = data.split("\\s+");
							StringBuilder combine = new StringBuilder();
							final double[] coeffs = new double[5];
							for (int i = 0; i < 5; i++) {
								coeffs[i] = Double.longBitsToDouble(Long.parseUnsignedLong(dataArray[2 * i] + dataArray[2 * i + 1], 16));
								combine.append(dataArray[2 * i]).append(dataArray[2 * i + 1]);
							}
							long crc = AtomSpectraSerial.crc32(combine.toString().getBytes());
							if (crc == Long.parseUnsignedLong(dataArray[10], 16)) {
								Calibration newCalibration = new Calibration();
								newCalibration.Calculate(coeffs);
								if (newCalibration.isCorrect()) {
									AtomSpectraService.ForegroundSpectrum.setSpectrumCalibration(newCalibration);
									Toast.makeText(context, getString(R.string.cal_apply_usb), Toast.LENGTH_SHORT).show();
									updateCalibrationMenu();
								} else {
									Toast.makeText(context, getString(R.string.cal_wrong_usb), Toast.LENGTH_SHORT).show();
									getCalibrationSettings(false, true);
								}
							} else {
								final AlertDialog.Builder alert = new AlertDialog.Builder(context)
										.setTitle(getString(R.string.cal_wrong_checksum))
										.setMessage(getString(R.string.cal_load_anyway))
										.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
											for (int i = 0; i < 5; i++) {
												coeffs[i] = Double.longBitsToDouble(Long.parseUnsignedLong(dataArray[2 * i] + dataArray[2 * i + 1], 16));
												combine.append(dataArray[2 * i]).append(dataArray[2 * i + 1]);
											}
											Calibration newCalibration = new Calibration();
											newCalibration.Calculate(coeffs);
											if (newCalibration.isCorrect()) {
												AtomSpectraService.ForegroundSpectrum.setSpectrumCalibration(newCalibration);
												Toast.makeText(context, getString(R.string.cal_apply_usb), Toast.LENGTH_SHORT).show();
												updateCalibrationMenu();
											} else {
												Toast.makeText(context, getString(R.string.cal_wrong_usb), Toast.LENGTH_SHORT).show();
												getCalibrationSettings(false, true);
											}
										})
										.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
											Toast.makeText(context, getString(R.string.cal_wrong_checksum), Toast.LENGTH_SHORT).show();
											getCalibrationSettings(false, true);
										});
								alert.show();
							}
						} else {
							Toast.makeText(context, getString(R.string.cal_timeout), Toast.LENGTH_SHORT).show();
							getCalibrationSettings(false,true);
						}
					} else if (SEND_CALIBRATION.equals(id)) {
						String command = intent.getStringExtra(AtomSpectraSerial.EXTRA_COMMAND);
						gotAnswers = gotAnswers > 0 ? gotAnswers - 1 : 0;
						boolean onlyZeros = true;
						StringBuilder res_all = new StringBuilder();
						if (AtomSpectraSerial.COMMAND_RESULT_OK.equals(data)) {
							for (int i = 0; i < 11; i++) {
								if (calibrationAnswers[i].equals(command))
									calibrationAnswers[i] = "";
								if (gotAnswers == 0 && !("".equals(calibrationAnswers[i])))
									onlyZeros = false;
								res_all.append(" : ").append(calibrationAnswers[i]);
							}
						} else {
							onlyZeros = false;
						}
						if (gotAnswers == 0) {
							if (onlyZeros)
								Toast.makeText(context, getString(R.string.cal_store_usb), Toast.LENGTH_SHORT).show();
							else {
								Toast.makeText(context, getString(R.string.cal_wrong_store_usb), Toast.LENGTH_SHORT).show();
								setCalibrationSettings(true);
							}
						}
					}
				}
			}
			if (Constants.ACTION.ACTION_UPDATE_MENU.equals(action)) {
				if (app_menu != null) {
					if (AtomSpectraService.getFreeze()) {
						app_menu.findItem(R.id.action_hist_freeze).setIcon(R.drawable.record);
						app_menu.findItem(R.id.action_hist_freeze).setTitle(R.string.hist_continue_update);
					} else {
						app_menu.findItem(R.id.action_hist_freeze).setIcon(R.drawable.menu_block);
						app_menu.findItem(R.id.action_hist_freeze).setTitle(R.string.hist_freeze_update);
					}
				}
				Button outputSound = findViewById(R.id.nbrButton);
				outputSound.setVisibility((Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) && sharedPreferences.getBoolean(Constants.CONFIG.CONF_OUTPUT_SOUND, false) ? Button.VISIBLE : Button.INVISIBLE);
				TextView view;
				view = findViewById(R.id.suffixView);
				if (view != null)
					view.setText(AtomSpectraService.ForegroundSpectrum.getSuffix());
				view = findViewById(R.id.backgroundSuffixView);
				if (view != null)
					view.setText(AtomSpectraService.BackgroundSpectrum.getSuffix());
			}
			if (Constants.ACTION.ACTION_UPDATE_CALIBRATION.equals(action)) {
				getCalibrationSettings(intent.getBooleanExtra(Constants.ACTION_PARAMETERS.UPDATE_USB_CALIBRATION, false), false);
			}
//			if (Constants.ACTION.ACTION_CHECK_GPS_AVAILABILITY.equals(action)) {
//				checkGPS();
//			}
		}

	};

	@Override
	protected void onResume() {
		super.onResume();
//		checkGPS();

		reducedTo = sharedPreferences.getInt(Constants.CONFIG.CONF_REDUCED_TO, Constants.VIEW_CHANNELS_DEFAULT);
		if (mAtomSpectraService != null && AtomSpectraService.getScaleFactor() > Constants.SCALE_DOSE_MODE)
			AtomSpectraService.restoreScaleFactor();
	}

	@Override
	protected void onPause() {
		super.onPause();
//		Locator.stopUsingGPS();
		Log.d(TAG, "-XxX-  pause");
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		buttonsTimer.cancel();
		unregisterReceiver(mDataUpdateReceiver);

		getApplicationContext().unbindService(mServiceConnection);
		background_subtract = false;
		if (app_menu != null) {
			app_menu.findItem(R.id.action_background_show).setChecked(false);
			app_menu.findItem(R.id.action_background_subtract).setChecked(false);
			app_menu.findItem(R.id.action_background_save).setEnabled(false);
			app_menu.findItem(R.id.action_background_suffix).setEnabled(false);
		}
		cursor_x = -1;
		mTextView.setVisibility(View.INVISIBLE);
		mLayoutView.setVisibility(LinearLayout.INVISIBLE);
		mTextView = null;
		mLayoutView = null;
		Log.d(TAG, "-XxX-");
	}

	@Override
	protected void onStop() {
		super.onStop();
		active = false;
		Log.d(TAG, "-XxX-  stop");
	}

//	private boolean checkGPS() {
//		boolean hasFeatureGPS = getPackageManager().hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS);
//		boolean hasFeatureNetwork = getPackageManager().hasSystemFeature(PackageManager.FEATURE_LOCATION_NETWORK);
//		addGPS = sharedPreferences.getBoolean(Constants.CONFIG.CONF_ADD_GPS_TO_FILES, false);
//
//		if ((hasFeatureGPS || hasFeatureNetwork) && addGPS) {
//			if (PermissionChecker.checkSelfPermission(getApplicationContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PermissionChecker.PERMISSION_GRANTED) {
//				Locator.startUsingGPS();
//				if (!Locator.hasGPS) {
//					SharedPreferences.Editor editor = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE).edit();
//					editor.putBoolean(Constants.CONFIG.CONF_ADD_GPS_TO_FILES, false);
//					editor.apply();
//					sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_SETTINGS).setPackage(Constants.PACKAGE_NAME));
//					return false;
//				}
//			} else {
//				Locator.stopUsingGPS();
//				SharedPreferences.Editor editor = getSharedPreferences(Constants.ATOMSPECTRA_PREFERENCES, MODE_PRIVATE).edit();
//				editor.putBoolean(Constants.CONFIG.CONF_ADD_GPS_TO_FILES, false);
//				editor.apply();
//				sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_SETTINGS).setPackage(Constants.PACKAGE_NAME));
//				return false;
//			}
//		} else {
//			Locator.stopUsingGPS();
//			return false;
//		}
//		return true;
//	}

	// Code to manage Service lifecycle.
	private final ServiceConnection mServiceConnection = new ServiceConnection() {

		@Override
		public void onServiceConnected(ComponentName componentName, IBinder service) {
			mAtomSpectraService = ((AtomSpectraService.LocalBinder) service).getService();
		}

		@Override
		public void onServiceDisconnected(ComponentName componentName) {
			mAtomSpectraService = null;
		}
	};

	public void onClickShape(View v) {
	}

	public void onClickDeleteSpc(View v) {
		final AlertDialog.Builder alert = new AlertDialog.Builder(this)
				.setTitle(getString(R.string.hist_ask_delete_title))
				.setMessage(getString(R.string.hist_ask_delete_text))
				.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
//					app_menu.findItem(R.id.action_hist_freeze).setIcon(R.drawable.menu_block);
//					app_menu.findItem(R.id.action_hist_freeze).setTitle(R.string.hist_freeze_update);
					//AtomSpectraService.freeze_update_data = false;
					AtomSpectraIsotopes.showFoundIsotopes = false;
					AtomSpectraIsotopes.foundList.clear();
//					sendBroadcast(new Intent(Constants.ACTION.ACTION_FREEZE_DATA).putExtra(AtomSpectraSerial.EXTRA_DATA_TYPE, true));
					sendBroadcast(new Intent(Constants.ACTION.ACTION_CLEAR_SPECTRUM).setPackage(Constants.PACKAGE_NAME));
//					AtomSpectraService.ForegroundSpectrum.setLocation(null).setComments(null).setSpectrumDate(0);
					((TextView)findViewById(R.id.suffixView)).setText(getString(R.string.hist_suffix));
//					getCalibrationSettings(false, false);
//					updateCalibrationMenu();
					//sendBroadcast(new Intent(Constants.ACTION.ACTION_UPDATE_NOTIFICATION));
				})
				.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
				});
		alert.show();
	}

	@SuppressLint("ApplySharedPref")
	public void onClick_UpDown(View v) {
		XCalibrated = !XCalibrated;
		if (XCalibrated) {
			((Button) findViewById(R.id.doseButton)).setText(getText(R.string.mode_compensated_button));
		} else {
			((Button) findViewById(R.id.doseButton)).setText(getText(R.string.mode_uncompensated_button));
		}
		SharedPreferences.Editor prefEditor = sharedPreferences.edit();
		prefEditor.putBoolean(Constants.CONFIG.CONF_CALIBRATED, XCalibrated);
		prefEditor.commit();
		dateScaleChanged = new Date().getTime();
		showScaleLabel = true;
		AtomSpectraService.requestUpdateGraph();
	}

	@SuppressLint("ApplySharedPref")
	public void onClick_fms(View v) {
		int mode = modes[sharedPreferences.getInt(Constants.CONFIG.CONF_SEARCH_MODE, 0)];
		SharedPreferences.Editor prefEditor = sharedPreferences.edit();
		prefEditor.putInt(Constants.CONFIG.CONF_SEARCH_MODE, mode);
		prefEditor.commit();
		fmsButton.setText(modeNames[mode]);
		AtomSpectraService.requestUpdateGraph();
	}

	public void onClick_Dose(View v) {
		if (AtomSpectraService.getScaleFactor() <= Constants.SCALE_MAX) {
			if (!AtomSpectraService.showCalibrationFunction) {
				AtomSpectraService.saveScaleFactor();
				AtomSpectraService.setScaleFactor(Constants.SCALE_DOSE_MODE);
				cursor_x = -1;
				showCursorInfo();
			}
		} else {
			AtomSpectraService.restoreScaleFactor();
		}
		AtomSpectraService.requestUpdateGraph();
	}

	public void onClick_Sound(View v) {
		AtomSpectraService.reloadBaseLevel();
	}

	public void onClick_Channel(View v) {
		final AlertDialog.Builder alert = new AlertDialog.Builder(this);

		alert.setTitle(getString(R.string.ask_channel_title));
		alert.setMessage(getString(R.string.ask_channel_text, cursor_x));

		final EditText input = new EditText(this);
		input.setInputType(InputType.TYPE_CLASS_NUMBER);
		input.setImeOptions(EditorInfo.IME_ACTION_DONE);
		alert.setView(input);

		alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
			String value = input.getText().toString();
			int intValue = 0;
			try {
				intValue = Integer.parseInt(value);
			} catch (NumberFormatException nfe) {
				//System.out.println("Could not parse " + nfe);
			}
			intValue = StrictMath.max(0, StrictMath.min(Constants.NUM_HIST_POINTS - 1, intValue));
			cursor_x = intValue;
			showCursorInfo();
		});
		alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
			// nothing.
		});
		alert.show();
	}

	public void onClick_Coord(View v) {
		if (v.getId() == R.id.channelText || v.getId() == R.id.doserateText) {
			show_average_cps = showModes[show_average_cps];
		}
	}

	@Override
	public void onGesture(GestureOverlayView overlay, MotionEvent event) {
		// TODO Auto-generated method stub

	}

	@Override
	public void onGestureCancelled(GestureOverlayView overlay, MotionEvent event) {
		// TODO Auto-generated method stub

	}

	@Override
	public void onGestureEnded(GestureOverlayView overlay, MotionEvent event) {
		// TODO Auto-generated method stub

	}

	@Override
	public void onGestureStarted(GestureOverlayView overlay, MotionEvent event) {
		// TODO Auto-generated method stub

	}

	private void getCalibrationSettings(boolean forceFromUSB, boolean forceFromMemory) {
		if (!forceFromUSB && (AtomSpectraService.inputType != AtomSpectraService.INPUT_SERIAL || forceFromMemory)) {
			int poliSize = sharedPreferences.getInt(Constants.CONFIG.CONF_POLI_SIZE, -1);
			Calibration newHistCalibration = new Calibration();
			if (poliSize == -1) {
				//old calibration is found or nothing, use old style loading
				int RightCal = Constants.NUM_HIST_POINTS - 1;
				double RightCalE = 3000.0;
				int LeftCal = 0;
				double LeftCalE = (float) 0.0;
				newHistCalibration.addLine(LeftCal, LeftCalE);
				newHistCalibration.addLine(RightCal, RightCalE);
				newHistCalibration.Calculate();
			} else {
				double x = sharedPreferences.getFloat(Constants.configCoefficient(0), -1000);
				int Cal = sharedPreferences.getInt(Constants.configChannel(1), -1);
				if (x != -1000 || Cal == -1) {
					double[] coeffs = new double[poliSize + 1];
					for (int i = 0; i <= poliSize; i++)
						coeffs[i] = sharedPreferences.getFloat(Constants.configCoefficient(i), 1);
					newHistCalibration.Calculate(coeffs);
				} else {
					double CalE;
					for (int i = 1; i <= poliSize + 1; i++) {
						Cal = sharedPreferences.getInt(Constants.configChannel(i), (Constants.NUM_HIST_POINTS - 1) * (i - 1) / poliSize);
						CalE = sharedPreferences.getFloat(Constants.configEnergy(i), (float) 3000.0 * (i - 1) / poliSize);
						newHistCalibration.addLine(Cal, CalE);
					}
					newHistCalibration.Calculate();
					double[] coeffs = newHistCalibration.getCoeffArray();
					SharedPreferences.Editor editor = sharedPreferences.edit();
					for (int i = 0; i <= newHistCalibration.getFactor(); i++) {
						editor.putFloat(Constants.configCoefficient(i), (float) coeffs[i]);
						editor.remove(Constants.configChannel(i + 1));
						editor.remove(Constants.configEnergy(i));
					}
					editor.apply();
				}
			}
			AtomSpectraService.ForegroundSpectrum.setSpectrumCalibration(newHistCalibration);
			AtomSpectraService.recalculateInterval();
			AtomSpectraService.lastCalibrationChannel = sharedPreferences.getInt(Constants.CONFIG.CONF_LAST_CHANNEL, Constants.NUM_HIST_POINTS);
			updateCalibrationMenu();
		} else {
			sendBroadcast(new Intent(Constants.ACTION.ACTION_SEND_USB_COMMAND).
					putExtra(Constants.ACTION_PARAMETERS.USB_COMMAND_ID, GET_CALIBRATION).
					putExtra(Constants.ACTION_PARAMETERS.USB_COMMAND_DATA, "-cal").setPackage(Constants.PACKAGE_NAME));
		}
	}

	@SuppressLint("ApplySharedPref")
	private void setCalibrationSettings(boolean forceToMemory) {
		if (AtomSpectraService.inputType != AtomSpectraService.INPUT_SERIAL || forceToMemory) {
			SharedPreferences.Editor prefEditor = sharedPreferences.edit();
			int poliSize = AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().getFactor();
			prefEditor.putInt(Constants.CONFIG.CONF_POLI_SIZE, poliSize);
			double[] coeffs = AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().getCoeffArray(5);
			for (int i = 0; i <= poliSize; i++)
				prefEditor.putFloat(Constants.configCoefficient(i), (float) coeffs[i]);
			prefEditor.putInt(Constants.CONFIG.CONF_LAST_CHANNEL, AtomSpectraService.lastCalibrationChannel);
			prefEditor.commit();
		} else {
			Toast.makeText(getApplicationContext(), getString(R.string.cal_store_usb_wait), Toast.LENGTH_LONG).show();
			double[] coeffs = AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().getCoeffArray(5);
			StringBuilder val;
			StringBuilder allCalibration = new StringBuilder();
			for (int i = 0; i < 5; i++) {
				val = new StringBuilder(Long.toHexString(Double.doubleToRawLongBits(coeffs[i])));
				while (val.length() < 16) {
					val.insert(0, "0");
				}
				allCalibration.append(val.substring(0, 8).toUpperCase(Locale.US)).append(val.substring(8, 16).toUpperCase(Locale.US));
				calibrationAnswers[2 * i] = String.format(Locale.US, "-cal %d %s", 2 * i, val.substring(0, 8));
				calibrationAnswers[2 * i + 1] = String.format(Locale.US, "-cal %d %s", 2 * i + 1, val.substring(8, 16));
			}
			//Toast.makeText(getApplicationContext(), allCalibration, Toast.LENGTH_LONG).show();
			long crc = AtomSpectraSerial.crc32(allCalibration.toString().getBytes());
			val = new StringBuilder(Long.toHexString(crc));
			while (val.length() < 8) {
				val.insert(0, "0");
			}
			calibrationAnswers[10] = String.format(Locale.US, "-cal 10 %s", val);
			gotAnswers = 11;
			//send data after all preparations
			for (int i = 0; i < 11; i++) {
				sendBroadcast(new Intent(Constants.ACTION.ACTION_SEND_USB_COMMAND).
						putExtra(Constants.ACTION_PARAMETERS.USB_COMMAND_ID, SEND_CALIBRATION).
						putExtra(Constants.ACTION_PARAMETERS.USB_COMMAND_DATA, calibrationAnswers[i]).setPackage(Constants.PACKAGE_NAME));
			}
		}
	}

	@SuppressLint("ClickableViewAccessibility")
	private void initializeGestures() {
		gestureDetector = initGestureDetector();
		gestureScaleDetector = initScaleGestureDetector();

		View view = findViewById(R.id.shape_area);

		view.setOnClickListener(arg0 -> {
		});

		view.setOnTouchListener((v, event) -> {
			boolean retVal;
			//retVal = gestureDetector.onTouchEvent(event);
			retVal = gestureScaleDetector.onTouchEvent(event);
			retVal = gestureDetector.onTouchEvent(event) || retVal;
			retVal = retVal || AtomSpectra.super.onTouchEvent(event);
			//if (retVal) v.performClick();
			return retVal;
		});
	}


	private GestureDetector initGestureDetector() {
		return new GestureDetector(getBaseContext(), new SimpleOnGestureListener() {

			private final SwipeDetector detector = new SwipeDetector();

			@SuppressLint("ApplySharedPref")
			@Override
			public boolean onFling(MotionEvent e1, @NotNull MotionEvent e2, float velocityX,
								   float velocityY) {
				if (isPinchMode) return true;
				if (AtomSpectraService.getScaleFactor() == Constants.SCALE_DOSE_MODE)
					return true;
				if (isPinchModeFinished) {
					isPinchModeFinished = false;
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
						findViewById(R.id.shape_area).performClick();
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
						findViewById(R.id.shape_area).performClick();
					} else if (detector.isSwipeDown(e1, e2, velocityY)) {
						if (AtomSpectraShapeView.isotopeFound >= 0 && cursor_x > 0) {
							CheckBox isotopeData = findViewById(Constants.GROUPS.BUTTON_ID_ALIGN + AtomSpectraShapeView.isotopeFound);
							AtomSpectraIsotopes.checkedIsotopeLine[AtomSpectraShapeView.isotopeFound] = !AtomSpectraIsotopes.checkedIsotopeLine[AtomSpectraShapeView.isotopeFound];
							isotopeData.toggle();
						}
						AtomSpectraService.requestUpdateGraph();
						findViewById(R.id.shape_area).performClick();
					}
				} catch (Exception ignored) {
				} //for now, ignore
				return false;

			}

			@Override
			public boolean onDoubleTap(@NotNull MotionEvent e1) {
				if (AtomSpectraService.getScaleFactor() == Constants.SCALE_DOSE_MODE)
					return true;
				logScale = !logScale;
				dateScaleChanged = new Date().getTime();
				showScaleLabel = true;
				AtomSpectraService.requestUpdateGraph();
				if (logScale) showToast(getString(R.string.graph_log_info));
				else showToast(getString(R.string.graph_linear_info));
				findViewById(R.id.shape_area).performClick();
				return true;
			}

			@Override
			public boolean onSingleTapConfirmed(@NotNull MotionEvent e1) {
				if (AtomSpectraService.showCalibrationFunction) {
					showCursorInfo();
					dateChannelChanged = 0;
//					Button button = findViewById(R.id.channelMinusButton);
//					button.setVisibility(Button.INVISIBLE);
//					button = findViewById(R.id.channelPlusButton);
//					button.setVisibility(Button.INVISIBLE);
					seekChannel.setVisibility(SeekBar.INVISIBLE);
					showPlusMinusButtons = false;
					return true;
				}
				if (AtomSpectraService.getScaleFactor() == Constants.SCALE_DOSE_MODE) {
					return true;
				}
				boolean getInside = false;
				if (AtomSpectraShapeView.isOutOfFrame(e1.getX())) {
					cursor_x = -1;
					dateChannelChanged = 0;
//					Button button = findViewById(R.id.channelMinusButton);
//					button.setVisibility(Button.INVISIBLE);
//					button = findViewById(R.id.channelPlusButton);
//					button.setVisibility(Button.INVISIBLE);
					seekChannel.setVisibility(SeekBar.INVISIBLE);
					showPlusMinusButtons = false;
				} else {
					if ((AtomSpectraService.newCalibration.getLines() < Constants.MAX_CALIBRATION_POINTS)) {
						for (Isotope i : AtomSpectraIsotopes.foundList) {
							if (i.getCoord().contains(e1.getX(), e1.getY())) {
								getInside = true;
								final Context id = AtomSpectra.this;
								final int channel_x = AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().toChannel(i.getEnergy(0));
								final AlertDialog.Builder alert = new AlertDialog.Builder(id)
										.setTitle(getString(R.string.calibration_add_nuclid_title))
										.setMessage(getString(R.string.calibration_add_nuclid2_text, channel_x, i.getName(), i.getEnergy(0)));
								final EditText input = new EditText(AtomSpectra.this);
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
										Toast.makeText(AtomSpectra.this, getString(R.string.cal_error_number), Toast.LENGTH_SHORT).show();
										return;
									}
									AtomSpectraService.newCalibration.addLine(channel_x, fValue);
									if (AtomSpectraService.newCalibration.getLines() > 1) {
										app_menu.findItem(R.id.action_cal_draw_function).setEnabled(true);
										AtomSpectraService.newCalibration.Calculate(sharedPreferences.getInt(Constants.CONFIG.CONF_MAX_POLI_FACTOR, Constants.DEFAULT_POLI_FACTOR));
										if (!AtomSpectraService.newCalibration.isCorrect()) {
											Toast.makeText(AtomSpectra.this, getString(R.string.cal_maybe_wrong, channel_x, fValue), Toast.LENGTH_LONG).show();
										}
									}
									updateCalibrationMenu();
									showCursorInfo();
								})
										.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
										});
								alert.show();
							}
						}
					}
					if ((AtomSpectraService.newCalibration.getLines() < Constants.MAX_CALIBRATION_POINTS) && (cursor_x != -1)) {
						for (Isotope i : AtomSpectraIsotopes.isotopeLineArray) {
							if (i.getCoord().contains(e1.getX(), e1.getY())) {
								getInside = true;
								final Context id = AtomSpectra.this;
								final AlertDialog.Builder alert = new AlertDialog.Builder(id)
										.setTitle(getString(R.string.calibration_add_nuclid_title))
										.setMessage(getString(R.string.calibration_add_nuclid_text, cursor_x, i.getName(), i.getEnergy(0)))
										.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
											AtomSpectraService.newCalibration.addLine(cursor_x, i.getEnergy(0));
											if (AtomSpectraService.newCalibration.getLines() > 1) {
												AtomSpectraService.newCalibration.Calculate(sharedPreferences.getInt(Constants.CONFIG.CONF_MAX_POLI_FACTOR, Constants.DEFAULT_POLI_FACTOR));
												app_menu.findItem(R.id.action_cal_draw_function).setEnabled(true);
												if (!AtomSpectraService.newCalibration.isCorrect()) {
													Toast.makeText(AtomSpectra.this, getString(R.string.cal_maybe_wrong, cursor_x, i.getEnergy(0)), Toast.LENGTH_LONG).show();
												}
											}
											updateCalibrationMenu();
											showCursorInfo();
										})
										.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
										});
								alert.show();
							}
						}
					}
					if (!getInside) {
						dateChannelChanged = System.currentTimeMillis();
//						Button button = findViewById(R.id.channelMinusButton);
//						button.setVisibility(Button.VISIBLE);
//						button = findViewById(R.id.channelPlusButton);
//						button.setVisibility(Button.VISIBLE);
						seekChannel.setVisibility(SeekBar.VISIBLE);
						if (showPlusMinusButtons || (cursor_x < AtomSpectraService.getFirstChannel())) {
							if (XCalibrated) {
								cursor_x = AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().toChannel(AtomSpectraShapeView.X2scale(e1.getX()));
							} else {
								cursor_x = (int) StrictMath.rint(AtomSpectraShapeView.X2scale(e1.getX()));
							}
						}
						showPlusMinusButtons = true;
					}
				}
				showCursorInfo();
				findViewById(R.id.shape_area).performClick();
				return true;
			}

			@SuppressLint("ApplySharedPref")
			@Override
			public void onLongPress(@NotNull MotionEvent e1) {
				barMode = !barMode;
				SharedPreferences.Editor prefEditor = sharedPreferences.edit();
				prefEditor.putBoolean(Constants.CONFIG.CONF_BAR_MODE, barMode);
				prefEditor.commit();
				AtomSpectraService.requestUpdateGraph();
				findViewById(R.id.shape_area).performClick();
			}

			private void showToast(String phrase) {
				Toast.makeText(getApplicationContext(), phrase, Toast.LENGTH_SHORT).show();
			}
		});
	}


	private ScaleGestureDetector initScaleGestureDetector() {
		return new ScaleGestureDetector(getBaseContext(), new SimpleOnScaleGestureListener() {
			private float SpanXInit = 1;
			private float SpanYInit = 1;
//			private float FocusXInit = 0;
//			private float FocusYInit = 0;

			@Override
			public boolean onScaleBegin(@NotNull ScaleGestureDetector scaleGestureDetector) {
				SpanXInit = scaleGestureDetector.getCurrentSpanX();
				SpanYInit = scaleGestureDetector.getCurrentSpanY();
//				FocusXInit = scaleGestureDetector.getFocusX();
//				FocusYInit = scaleGestureDetector.getFocusY();
				isPinchMode = true;
				return true;
			}

			@Override
			public boolean onScale(@NotNull ScaleGestureDetector scaleGestureDetector) {
				return true;
			}

			@SuppressLint("ApplySharedPref")
			@Override
			public void onScaleEnd(@NotNull ScaleGestureDetector scaleGestureDetector) {
				float mScaleFactor;
				float mSpanX, mSpanY;
//				float mFocusX, mFocusY;
				int new_channel;
				isPinchMode = false;
				isPinchModeFinished = true;
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
						if (zoom_factor < 40) {
							if (zoom_factor >= 1)
								zoom_factor *= 2;
							else
								zoom_factor += 0.25f;
							AtomSpectraService.requestUpdateGraph();
						} else {
							zoom_factor = 64;
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
						if (zoom_factor > 0.4) {
							if (zoom_factor > 1)
								zoom_factor /= 2;
							else
								zoom_factor -= 0.25f;
							AtomSpectraService.requestUpdateGraph();
						} else {
							zoom_factor = 0.25f;
							showToast(getString(R.string.graph_min_zoom));
						}
					}

				}/* else {
					//showToast(String.valueOf(mFocusX - FocusXInit));

					if(mAtomSpectraService.getScaleFactor() <= 6) {

						if ((mFocusX - FocusXInit) > 100) {
							new_channel = AtomSpectraService.getFirstChannel() + 256 * (1 << (Constants.SCALE_MAX - AtomSpectraService.getScaleFactor()));

							if (new_channel + 1024 * (1 << (6 - AtomSpectraService.getScaleFactor())) > Constants.NUM_HIST_POINTS) {
								new_channel = Constants.NUM_HIST_POINTS - 1024 * (1 << (Constants.SCALE_MAX - AtomSpectraService.getScaleFactor()));
							}

							AtomSpectraService.first_channel = new_channel;
						} else if ((mFocusX - FocusXInit) < -100) {
							new_channel = AtomSpectraService.getFirstChannel() - 256 * (1 << (Constants.SCALE_MAX - mAtomSpectraService.getScaleFactor()));

							if (new_channel < 0) {
								new_channel = 0;
							}

							AtomSpectraService.first_channel = new_channel;
						}

					}

				}*/

				//return false;
				findViewById(R.id.shape_area).performClick();
			}

			private void showToast(String phrase) {
				Toast.makeText(getApplicationContext(), phrase, Toast.LENGTH_SHORT).show();
			}
		});
	}

	private final Timer buttonsTimer = new Timer();
	private boolean showPlusMinusButtons = false;
	public static boolean showScaleLabel = true;
	public static long dateScaleChanged = 0;
	private long dateChannelChanged = 0;
	private final TimerTask buttonsTask = new TimerTask() {
		@Override
		public void run() {
			if (showPlusMinusButtons && ((System.currentTimeMillis() - dateChannelChanged) > Constants.CURSOR_TIMEOUT)) {
				showPlusMinusButtons = false;
//				final Button button1 = findViewById(R.id.channelMinusButton);
//				button1.post(() -> button1.setVisibility(Button.INVISIBLE));
//				final Button button2 = findViewById(R.id.channelPlusButton);
//				button2.post(() -> button2.setVisibility(Button.INVISIBLE));
				final SeekBar seekChannel = findViewById(R.id.seekChannel);
				seekChannel.post(() -> seekChannel.setVisibility(SeekBar.INVISIBLE));
			}
			if (showScaleLabel && ((new Date().getTime() - dateScaleChanged) > Constants.LABEL_TIMEOUT)) {
				showScaleLabel = false;
			}
		}
	};

/*	public void onClickPlusPressed(View v) {
		int shift_cursor = 1 << StrictMath.max(0, (Constants.SCALE_MAX - AtomSpectraService.getScaleFactor() - 1));
		cursor_x = cursor_x < (Constants.NUM_HIST_POINTS - shift_cursor) ? cursor_x + shift_cursor : (Constants.NUM_HIST_POINTS - 1);
		showCursorInfo();
		dateChannelChanged = System.currentTimeMillis();
	}

	public void onClickMinusPressed(View v) {
		int shift_cursor = 1 << StrictMath.max(0, (Constants.SCALE_MAX - AtomSpectraService.getScaleFactor() - 1));
		cursor_x = cursor_x > shift_cursor ? cursor_x - shift_cursor : 0;
		showCursorInfo();
		dateChannelChanged = System.currentTimeMillis();
	} */

	private void showCursorInfo() {
//		TextView mTextView = findViewById(R.id.cursorView);
		if (cursor_x >= 0 && cursor_x >= AtomSpectraService.getFirstChannel() / Constants.NUM_HIST_POINTS * AtomSpectraService.lastCalibrationChannel && !AtomSpectraService.showCalibrationFunction) {
			mTextView.setText(getString(R.string.cursor_format, cursor_x, AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().toEnergy(cursor_x), AtomSpectraService.ForegroundSpectrum.getDataArray()[cursor_x]));
			mTextView.setVisibility(TextView.VISIBLE);
			mLayoutView.setVisibility(LinearLayout.VISIBLE);
		} else {
			mTextView.setVisibility(TextView.INVISIBLE);
			mLayoutView.setVisibility(LinearLayout.INVISIBLE);
		}
		AtomSpectraService.requestUpdateGraph();
		Log.d(TAG, "showCursorInfo: " + cursor_x);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == R.id.action_background_save) {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				String dirName = sharedPreferences.getString(Constants.CONFIG.CONF_DIRECTORY_SELECTED, null);
				if (dirName == null) {
					Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
					intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
					startActivityForResult(intent, SELECT_SAVE_BACK_DIR_CODE);
				} else {
					final DocumentFile dir = DocumentFile.fromTreeUri(this, Uri.parse(dirName));
					if ((dir != null) && dir.isDirectory()) {
						saveBackground();
					} else {
						Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
						intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
						startActivityForResult(intent, SELECT_SAVE_BACK_DIR_CODE);
					}
				}
			} else {
				if (checkPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, getString(R.string.perm_ask_write_title), getString(R.string.perm_ask_write_text), REQUEST_WRITE_BACK)) {
					saveBackground();
				}
			}
			return true;
		} else if (item.getItemId() == R.id.action_background_load) {
			Log.d(TAG, "loading hist file");
			if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
				if (checkPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, getString(R.string.perm_ask_read_title), getString(R.string.perm_ask_read_text), REQUEST_READ_HIST)) {
					Intent loadIntent = new Intent()
							.setType("*/*")
							.setAction(Intent.ACTION_GET_CONTENT);
					startActivityForResult(Intent.createChooser(loadIntent, getString(R.string.ask_select_histogram)), LOAD_HIST_CODE);
				}
			} else {
				Intent loadIntent = new Intent()
						.setType("*/*")
						.setAction(Intent.ACTION_GET_CONTENT);
				startActivityForResult(Intent.createChooser(loadIntent, getString(R.string.ask_select_histogram)), LOAD_HIST_CODE);
			}
			//return true;







			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				String dirName = sharedPreferences.getString(Constants.CONFIG.CONF_DIRECTORY_SELECTED, null);
				if (dirName == null) {
					Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
					intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
					startActivityForResult(intent, SELECT_LOAD_BACK_DIR_CODE);
				} else {
					final DocumentFile dir = DocumentFile.fromTreeUri(this, Uri.parse(dirName));
					if ((dir != null) && dir.isDirectory()) {
						loadBackground(null);
					} else {
						Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
						intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
						startActivityForResult(intent, SELECT_LOAD_BACK_DIR_CODE);
					}
				}
			} else {
				if (checkPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, getString(R.string.perm_ask_read_title), getString(R.string.perm_ask_read_text), REQUEST_READ_BACK)) {
					loadBackground(null);
				}
			}
			return true;
		} else if (item.getItemId() == R.id.action_background_load_from) {
			Log.d(TAG, "loading background file from...");
			if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
				if (checkPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, getString(R.string.perm_ask_read_title), getString(R.string.perm_ask_read_text), REQUEST_READ_BACK_FROM)) {
					Intent loadIntent = new Intent()
							.setType("*/*")
							.setAction(Intent.ACTION_GET_CONTENT);
					startActivityForResult(Intent.createChooser(loadIntent, getString(R.string.ask_select_histogram)), LOAD_BACK_CODE);
				}
			} else {
				Intent loadIntent = new Intent()
						.setType("*/*")
						.setAction(Intent.ACTION_GET_CONTENT);
				startActivityForResult(Intent.createChooser(loadIntent, getString(R.string.ask_select_histogram)), LOAD_BACK_CODE);
			}
			return true;
		} else if (item.getItemId() == R.id.action_background_copy) {
			moveToBackground();
			return true;
		} else if (item.getItemId() == R.id.action_hist_suffix) {
			final AlertDialog.Builder alert = new AlertDialog.Builder(this);
			alert.setTitle(getString(R.string.ask_spectrum_suffix));
			alert.setMessage(getString(R.string.ask_suffix_text));

			final EditText input = new EditText(this);
			input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
			input.setImeOptions(EditorInfo.IME_ACTION_DONE);
			input.setText(AtomSpectraService.ForegroundSpectrum.getSuffix(), TextView.BufferType.EDITABLE);
			alert.setView(input);
			alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
				AtomSpectraService.ForegroundSpectrum.setSuffix(input.getText().toString());
				TextView text = findViewById(R.id.suffixView);
				text.setText(AtomSpectraService.ForegroundSpectrum.getSuffix());
			});
			alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
			});
			alert.show();
			return true;
		} else if (item.getItemId() == R.id.action_background_suffix) {
			final AlertDialog.Builder alert = new AlertDialog.Builder(this);
			alert.setTitle(getString(R.string.ask_spectrum_suffix));
			alert.setMessage(getString(R.string.ask_suffix_text));

			final EditText input = new EditText(this);
			input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
			input.setImeOptions(EditorInfo.IME_ACTION_DONE);
			input.setText(AtomSpectraService.BackgroundSpectrum.getSuffix(), TextView.BufferType.EDITABLE);
			alert.setView(input);
			alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
				AtomSpectraService.BackgroundSpectrum.setSuffix(input.getText().toString());
				TextView text = findViewById(R.id.backgroundSuffixView);
				text.setText(AtomSpectraService.BackgroundSpectrum.getSuffix());
			});
			alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
			});
			alert.show();
			return true;
		} else if (item.getItemId() == R.id.action_background_clear) {
			AtomSpectraService.background_show = false;
			background_subtract = false;
			app_menu.findItem(R.id.action_background_show).setChecked(false);
			app_menu.findItem(R.id.action_background_show).setEnabled(false);
			app_menu.findItem(R.id.action_background_subtract).setChecked(false);
			app_menu.findItem(R.id.action_background_subtract).setEnabled(false);
			app_menu.findItem(R.id.action_background_save).setEnabled(false);
			app_menu.findItem(R.id.action_background_clear).setEnabled(false);
			app_menu.findItem(R.id.action_background_suffix).setEnabled(false);
			app_menu.findItem(R.id.action_background_suffix).setEnabled(false);
			AtomSpectraService.BackgroundSpectrum.initSpectrumData().setSuffix(getString(R.string.background_suffix));
			TextView view = findViewById(R.id.backgroundSuffixView);
			view.setText(getResources().getText(R.string.background));
			view.setVisibility(TextView.INVISIBLE);
			return true;
		} else if (item.getItemId() == R.id.action_background_subtract) {
			if (item.isChecked()) {
				item.setChecked(false);
				background_subtract = false;
			} else {
				if (!AtomSpectraService.BackgroundSpectrum.isEmpty()) {
					background_subtract = true;
					item.setChecked(true);
				}
			}
			return true;
		} else if (item.getItemId() == R.id.action_background_show) {
			if (item.isChecked()) {
				item.setChecked(false);
				AtomSpectraService.background_show = false;
				background_subtract = false;
				app_menu.findItem(R.id.action_background_subtract).setChecked(false);
				app_menu.findItem(R.id.action_background_subtract).setEnabled(false);
			} else {
				if (!AtomSpectraService.BackgroundSpectrum.isEmpty()) {
					AtomSpectraService.background_show = true;
					item.setChecked(true);
					app_menu.findItem(R.id.action_background_subtract).setEnabled(true);
				}
			}
			return true;
		} else if (item.getItemId() == R.id.action_isotopes) {
			Intent intent_isotopes = new Intent(this, AtomSpectraIsotopes.class);
			startActivity(intent_isotopes);
			return true;
		} else if (item.getItemId() == R.id.action_help) {
			Intent intent_help = new Intent(this, AtomSpectraHelp.class);
			startActivity(intent_help);
			return true;
		} else if (item.getItemId() == R.id.action_hist_to_file) {
			Log.d(TAG, "saving file");
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				String dirName = sharedPreferences.getString(Constants.CONFIG.CONF_DIRECTORY_SELECTED, null);
				if (dirName == null) {
					Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
					intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
					startActivityForResult(intent, SELECT_SAVE_HIST_DIR_CODE);
				} else {
					final DocumentFile dir = DocumentFile.fromTreeUri(this, Uri.parse(dirName));
					if ((dir != null) && dir.isDirectory()) {
						try {
							final AlertDialog.Builder alert = new AlertDialog.Builder(this);
							alert.setTitle(getString(R.string.ask_spectrum_suffix));
							alert.setMessage(getString(R.string.ask_suffix_text));

							final EditText input = new EditText(this);
							input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
							input.setImeOptions(EditorInfo.IME_ACTION_DONE);
							input.setText(AtomSpectraService.ForegroundSpectrum.getSuffix(), TextView.BufferType.EDITABLE);
							alert.setView(input);
							alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
								AtomSpectraService.ForegroundSpectrum.setSuffix(input.getText().toString());
								TextView text = findViewById(R.id.suffixView);
								text.setText(AtomSpectraService.ForegroundSpectrum.getSuffix());
								saveHist(AtomSpectraService.ForegroundSpectrum.getSuffix());
							});
							alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
							});
							alert.show();
						} catch (Exception e) {
							Log.d(TAG, "saving file FAIL");
						}
					} else {
						Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
						intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
						startActivityForResult(intent, SELECT_SAVE_HIST_DIR_CODE);
					}
				}
			} else {
				if (checkPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, getString(R.string.perm_ask_write_title), getString(R.string.perm_ask_write_text), REQUEST_WRITE_HIST)) {
					try {
						final AlertDialog.Builder alert = new AlertDialog.Builder(this);
						alert.setTitle(getString(R.string.ask_spectrum_suffix));
						alert.setMessage(getString(R.string.ask_suffix_text));

						final EditText input = new EditText(this);
						input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
						input.setImeOptions(EditorInfo.IME_ACTION_DONE);
						input.setText(AtomSpectraService.ForegroundSpectrum.getSuffix(), TextView.BufferType.EDITABLE);
						alert.setView(input);
						alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
							AtomSpectraService.ForegroundSpectrum.setSuffix(input.getText().toString());
							TextView text = findViewById(R.id.suffixView);
							text.setText(AtomSpectraService.ForegroundSpectrum.getSuffix());
							saveHist(AtomSpectraService.ForegroundSpectrum.getSuffix());
						});
						alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
						});
						alert.show();
					} catch (Exception e) {
						Log.d(TAG, "saving file FAIL");
					}
				}
			}
			return true;
		} else if (item.getItemId() == R.id.action_hist_from_file) {
			Log.d(TAG, "loading hist file");
			if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
				if (checkPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, getString(R.string.perm_ask_read_title), getString(R.string.perm_ask_read_text), REQUEST_READ_HIST)) {
					Intent loadIntent = new Intent()
							.setType("*/*")
							.setAction(Intent.ACTION_GET_CONTENT);
					startActivityForResult(Intent.createChooser(loadIntent, getString(R.string.ask_select_histogram)), LOAD_HIST_CODE);
				}
			} else {
				Intent loadIntent = new Intent()
						.setType("*/*")
						.setAction(Intent.ACTION_GET_CONTENT);
				startActivityForResult(Intent.createChooser(loadIntent, getString(R.string.ask_select_histogram)), LOAD_HIST_CODE);
			}
			return true;
		} else if (item.getItemId() == R.id.action_hist_add_from_file) {
			Log.d(TAG, "adding hist file");
			if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
				if (checkPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, getString(R.string.perm_ask_read_title), getString(R.string.perm_ask_read_text), REQUEST_READ_HIST)) {
					Intent loadIntent = new Intent()
							.setType("*/*")
							.setAction(Intent.ACTION_GET_CONTENT);
					startActivityForResult(Intent.createChooser(loadIntent, getString(R.string.ask_select_histogram)), ADD_HIST_CODE);
				}
			} else {
				Intent loadIntent = new Intent()
						.setType("*/*")
						.setAction(Intent.ACTION_GET_CONTENT);
				startActivityForResult(Intent.createChooser(loadIntent, getString(R.string.ask_select_histogram)), ADD_HIST_CODE);
			}
			return true;
		} else if (item.getItemId() == R.id.action_cal_load) {
			Log.d(TAG, "loading calibration from file");
			if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
				if (checkPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, getString(R.string.perm_ask_read_title), getString(R.string.perm_ask_read_text), REQUEST_READ_CAL)) {
					Intent calibIntent = new Intent()
							.setType("*/*")
							.setAction(Intent.ACTION_GET_CONTENT);
					startActivityForResult(Intent.createChooser(calibIntent, getString(R.string.ask_select_histogram)), LOAD_CALIBRATION_CODE);
				}
			} else {
				Intent calibIntent = new Intent()
						.setType("*/*")
						.setAction(Intent.ACTION_GET_CONTENT);
				startActivityForResult(Intent.createChooser(calibIntent, getString(R.string.ask_select_calibration)), LOAD_CALIBRATION_CODE);
			}
			return true;
		} else if (item.getItemId() == R.id.action_cal_store) {
			Log.d(TAG, "storing calibration to program");
			setCalibrationSettings(false);
			return true;
		} else if (item.getItemId() == R.id.action_cal_store_memory) {
			Log.d(TAG, "storing calibration to program");
			setCalibrationSettings(true);
			return true;
		} else if (item.getItemId() == R.id.action_cal_retrieve) {
			Log.d(TAG, "retrieving calibration from program");
			getCalibrationSettings(false, false);
//			updateCalibrationMenu();
			return true;
		} else if (item.getItemId() == R.id.action_cal_retrieve_memory) {
			Log.d(TAG, "retrieving calibration from program");
			getCalibrationSettings(false, true);
//			updateCalibrationMenu();
			return true;
		} else if (item.getItemId() == R.id.action_export) {
			Log.d(TAG, "exporting file");
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				String dirName = sharedPreferences.getString(Constants.CONFIG.CONF_DIRECTORY_SELECTED, null);
				if (dirName == null) {
					Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
					intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
					startActivityForResult(intent, SELECT_SAVE_EXPORT_DIR_CODE);
				} else {
					final DocumentFile dir = DocumentFile.fromTreeUri(this, Uri.parse(dirName));
					if ((dir != null) && dir.isDirectory()) {
						try {
							final AlertDialog.Builder alert = new AlertDialog.Builder(this);
							alert.setTitle(getString(R.string.ask_export_suffix));
							alert.setMessage(getString(R.string.ask_suffix_text));

							final EditText input = new EditText(this);
							input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
							input.setImeOptions(EditorInfo.IME_ACTION_DONE);
							input.setText(AtomSpectraService.ForegroundSpectrum.getSuffix(), TextView.BufferType.EDITABLE);
							alert.setView(input);
							alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
								AtomSpectraService.ForegroundSpectrum.setSuffix(input.getText().toString());
								TextView text = findViewById(R.id.suffixView);
								text.setText(AtomSpectraService.ForegroundSpectrum.getSuffix());
								saveCSV(AtomSpectraService.ForegroundSpectrum.getSuffix(), false);
							});
							alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
							});
							alert.show();
						} catch (Exception e) {
							Log.d(TAG, "saving file FAIL");
						}
					} else {
						Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
						intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
						startActivityForResult(intent, SELECT_SAVE_EXPORT_DIR_CODE);
					}
				}
			} else {
				if (checkPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, getString(R.string.perm_ask_write_title), getString(R.string.perm_ask_write_text), REQUEST_EXPORT)) {
					try {
						final AlertDialog.Builder alert = new AlertDialog.Builder(this);
						alert.setTitle(getString(R.string.ask_export_suffix));
						alert.setMessage(getString(R.string.ask_suffix_text));
						final EditText input = new EditText(this);
						input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
						input.setImeOptions(EditorInfo.IME_ACTION_DONE);
						input.setText(AtomSpectraService.ForegroundSpectrum.getSuffix(), TextView.BufferType.EDITABLE);
						alert.setView(input);
						alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
							AtomSpectraService.ForegroundSpectrum.setSuffix(input.getText().toString());
							TextView text = findViewById(R.id.suffixView);
							text.setText(AtomSpectraService.ForegroundSpectrum.getSuffix());
							saveCSV(AtomSpectraService.ForegroundSpectrum.getSuffix(), false);
						});
						alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
						});
						alert.show();
					} catch (Exception e) {
						Log.d(TAG, "saving file FAIL");
					}
				}
			}
			return true;
		} else if (item.getItemId() == R.id.action_export_with_energy) {
			Log.d(TAG, "exporting file with energy");
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				String dirName = sharedPreferences.getString(Constants.CONFIG.CONF_DIRECTORY_SELECTED, null);
				if (dirName == null) {
					Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
					intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
					startActivityForResult(intent, SELECT_SAVE_EXPORT_E_DIR_CODE);
				} else {
					final DocumentFile dir = DocumentFile.fromTreeUri(this, Uri.parse(dirName));
					if ((dir != null) && dir.isDirectory()) {
						try {
							final AlertDialog.Builder alert = new AlertDialog.Builder(this);
							alert.setTitle(getString(R.string.ask_export_suffix));
							alert.setMessage(getString(R.string.ask_suffix_text));

							final EditText input = new EditText(this);
							input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
							input.setImeOptions(EditorInfo.IME_ACTION_DONE);
							input.setText(AtomSpectraService.ForegroundSpectrum.getSuffix(), TextView.BufferType.EDITABLE);
							alert.setView(input);
							alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
								AtomSpectraService.ForegroundSpectrum.setSuffix(input.getText().toString());
								TextView text = findViewById(R.id.suffixView);
								text.setText(AtomSpectraService.ForegroundSpectrum.getSuffix());
								saveCSV(AtomSpectraService.ForegroundSpectrum.getSuffix(), true);
							});
							alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
							});
							alert.show();
						} catch (Exception e) {
							Log.d(TAG, "saving file FAIL");
						}
					} else {
						Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
						intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
						startActivityForResult(intent, SELECT_SAVE_EXPORT_E_DIR_CODE);
					}
				}
			} else {
				if (checkPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, getString(R.string.perm_ask_write_title), getString(R.string.perm_ask_write_text), REQUEST_EXPORT_E)) {
					try {
						final AlertDialog.Builder alert = new AlertDialog.Builder(this);
						alert.setTitle(getString(R.string.ask_export_suffix));
						alert.setMessage(getString(R.string.ask_suffix_text));
						final EditText input = new EditText(this);
						input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
						input.setImeOptions(EditorInfo.IME_ACTION_DONE);
						input.setText(AtomSpectraService.ForegroundSpectrum.getSuffix(), TextView.BufferType.EDITABLE);
						alert.setView(input);
						alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
							AtomSpectraService.ForegroundSpectrum.setSuffix(input.getText().toString());
							TextView text = findViewById(R.id.suffixView);
							text.setText(AtomSpectraService.ForegroundSpectrum.getSuffix());
							saveCSV(AtomSpectraService.ForegroundSpectrum.getSuffix(), true);
						});
						alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
						});
						alert.show();
					} catch (Exception e) {
						Log.d(TAG, "saving file FAIL");
					}
				}
			}
			return true;
		} else if (item.getItemId() == R.id.action_export_to_BqMoni) {
			Log.d(TAG, "exporting file to Becquerel Monitor");
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				String dirName = sharedPreferences.getString(Constants.CONFIG.CONF_DIRECTORY_SELECTED, null);
				if (dirName == null) {
					Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
					intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
					startActivityForResult(intent, SELECT_SAVE_EXPORT_BQ_DIR_CODE);
				} else {
					final DocumentFile dir = DocumentFile.fromTreeUri(this, Uri.parse(dirName));
					if ((dir != null) && dir.isDirectory()) {
						try {
							final AlertDialog.Builder alert = new AlertDialog.Builder(this);
							alert.setTitle(getString(R.string.ask_export_bqmoni));
							alert.setMessage(getString(R.string.ask_suffix_text));

							final EditText input = new EditText(this);
							input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
							input.setImeOptions(EditorInfo.IME_ACTION_DONE);
							input.setText(AtomSpectraService.ForegroundSpectrum.getSuffix(), TextView.BufferType.EDITABLE);
							alert.setView(input);
							alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
								AtomSpectraService.ForegroundSpectrum.setSuffix(input.getText().toString());
								TextView text = findViewById(R.id.suffixView);
								text.setText(AtomSpectraService.ForegroundSpectrum.getSuffix());
								saveBqMoni(AtomSpectraService.ForegroundSpectrum.getSuffix());
							});
							alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
							});
							alert.show();
						} catch (Exception e) {
							Log.d(TAG, "saving file FAIL");
						}
					} else {
						Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
						intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
						startActivityForResult(intent, SELECT_SAVE_EXPORT_BQ_DIR_CODE);
					}
				}
			} else {
				if (checkPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, getString(R.string.perm_ask_write_title), getString(R.string.perm_ask_write_text), REQUEST_EXPORT_BQMONI)) {
					try {
						final AlertDialog.Builder alert = new AlertDialog.Builder(this);
						alert.setTitle(getString(R.string.ask_export_bqmoni));
						alert.setMessage(getString(R.string.ask_suffix_text));
						final EditText input = new EditText(this);
						input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
						input.setImeOptions(EditorInfo.IME_ACTION_DONE);
						input.setText(AtomSpectraService.ForegroundSpectrum.getSuffix(), TextView.BufferType.EDITABLE);
						alert.setView(input);
						alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
							AtomSpectraService.ForegroundSpectrum.setSuffix(input.getText().toString());
							TextView text = findViewById(R.id.suffixView);
							text.setText(AtomSpectraService.ForegroundSpectrum.getSuffix());
							saveBqMoni(AtomSpectraService.ForegroundSpectrum.getSuffix());
						});
						alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
						});
						alert.show();
					} catch (Exception e) {
						Log.d(TAG, "saving file FAIL");
					}
				}
			}
			return true;
		} else if (item.getItemId() == R.id.action_export_to_SPE) {
			Log.d(TAG, "exporting file to SPE");
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				String dirName = sharedPreferences.getString(Constants.CONFIG.CONF_DIRECTORY_SELECTED, null);
				if (dirName == null) {
					Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
					intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
					startActivityForResult(intent, SELECT_SAVE_EXPORT_SPE_DIR_CODE);
				} else {
					final DocumentFile dir = DocumentFile.fromTreeUri(this, Uri.parse(dirName));
					if ((dir != null) && dir.isDirectory()) {
						try {
							final AlertDialog.Builder alert = new AlertDialog.Builder(this);
							alert.setTitle(getString(R.string.ask_export_spe));
							alert.setMessage(getString(R.string.ask_suffix_text));

							final EditText input = new EditText(this);
							input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
							input.setImeOptions(EditorInfo.IME_ACTION_DONE);
							input.setText(AtomSpectraService.ForegroundSpectrum.getSuffix(), TextView.BufferType.EDITABLE);
							alert.setView(input);
							alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
								AtomSpectraService.ForegroundSpectrum.setSuffix(input.getText().toString());
								TextView text = findViewById(R.id.suffixView);
								text.setText(AtomSpectraService.ForegroundSpectrum.getSuffix());
								saveSPE(AtomSpectraService.ForegroundSpectrum.getSuffix());
							});
							alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
							});
							alert.show();
						} catch (Exception e) {
							Log.d(TAG, "saving file FAIL");
						}
					} else {
						Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
						intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
						startActivityForResult(intent, SELECT_SAVE_EXPORT_SPE_DIR_CODE);
					}
				}
			} else {
				if (checkPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, getString(R.string.perm_ask_write_title), getString(R.string.perm_ask_write_text), REQUEST_EXPORT_SPE)) {
					try {
						final AlertDialog.Builder alert = new AlertDialog.Builder(this);
						alert.setTitle(getString(R.string.ask_export_spe));
						alert.setMessage(getString(R.string.ask_suffix_text));
						final EditText input = new EditText(this);
						input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
						input.setImeOptions(EditorInfo.IME_ACTION_DONE);
						input.setText(AtomSpectraService.ForegroundSpectrum.getSuffix(), TextView.BufferType.EDITABLE);
						alert.setView(input);
						alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
							AtomSpectraService.ForegroundSpectrum.setSuffix(input.getText().toString());
							TextView text = findViewById(R.id.suffixView);
							text.setText(AtomSpectraService.ForegroundSpectrum.getSuffix());
							saveSPE(AtomSpectraService.ForegroundSpectrum.getSuffix());
						});
						alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
						});
						alert.show();
					} catch (Exception e) {
						Log.d(TAG, "saving file FAIL");
					}
				}
			}
			return true;
		} else if (item.getItemId() == R.id.action_export_to_N42) {
			Log.d(TAG, "exporting file to SPE");
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				String dirName = sharedPreferences.getString(Constants.CONFIG.CONF_DIRECTORY_SELECTED, null);
				if (dirName == null) {
					Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
					intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
					startActivityForResult(intent, SELECT_SAVE_EXPORT_N42_DIR_CODE);
				} else {
					final DocumentFile dir = DocumentFile.fromTreeUri(this, Uri.parse(dirName));
					if ((dir != null) && dir.isDirectory()) {
						try {
							final AlertDialog.Builder alert = new AlertDialog.Builder(this);
							alert.setTitle(getString(R.string.ask_export_N42));
							alert.setMessage(getString(R.string.ask_suffix_text));

							final EditText input = new EditText(this);
							input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
							input.setImeOptions(EditorInfo.IME_ACTION_DONE);
							input.setText(AtomSpectraService.ForegroundSpectrum.getSuffix(), TextView.BufferType.EDITABLE);
							alert.setView(input);
							alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
								AtomSpectraService.ForegroundSpectrum.setSuffix(input.getText().toString());
								TextView text = findViewById(R.id.suffixView);
								text.setText(AtomSpectraService.ForegroundSpectrum.getSuffix());
								saveN42(AtomSpectraService.ForegroundSpectrum.getSuffix());
							});
							alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
							});
							alert.show();
						} catch (Exception e) {
							Log.d(TAG, "saving file FAIL");
						}
					} else {
						Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
						intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
						startActivityForResult(intent, SELECT_SAVE_EXPORT_N42_DIR_CODE);
					}
				}
			} else {
				if (checkPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, getString(R.string.perm_ask_write_title), getString(R.string.perm_ask_write_text), REQUEST_EXPORT_N42)) {
					try {
						final AlertDialog.Builder alert = new AlertDialog.Builder(this);
						alert.setTitle(getString(R.string.ask_export_N42));
						alert.setMessage(getString(R.string.ask_suffix_text));
						final EditText input = new EditText(this);
						input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
						input.setImeOptions(EditorInfo.IME_ACTION_DONE);
						input.setText(AtomSpectraService.ForegroundSpectrum.getSuffix(), TextView.BufferType.EDITABLE);
						alert.setView(input);
						alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
							AtomSpectraService.ForegroundSpectrum.setSuffix(input.getText().toString());
							TextView text = findViewById(R.id.suffixView);
							text.setText(AtomSpectraService.ForegroundSpectrum.getSuffix());
							saveN42(AtomSpectraService.ForegroundSpectrum.getSuffix());
						});
						alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
						});
						alert.show();
					} catch (Exception e) {
						Log.d(TAG, "saving file FAIL");
					}
				}
			}
			return true;
		} else if (item.getItemId() == R.id.action_save_device) {
			Log.d(TAG, "saving device file");
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				String dirName = sharedPreferences.getString(Constants.CONFIG.CONF_DIRECTORY_SELECTED, null);
				if (dirName == null) {
					Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
					intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
					startActivityForResult(intent, SELECT_SAVE_HIST_DIR_CODE);
				} else {
					final DocumentFile dir = DocumentFile.fromTreeUri(this, Uri.parse(dirName));
					if ((dir != null) && dir.isDirectory()) {
						try {
							final AlertDialog.Builder alert = new AlertDialog.Builder(this);
							alert.setTitle(getString(R.string.ask_device_suffix));
							alert.setMessage(getString(R.string.ask_suffix_text));

							final EditText input = new EditText(this);
							input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
							input.setImeOptions(EditorInfo.IME_ACTION_DONE);
							alert.setView(input);
							alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
								String value = input.getText().toString();
								saveDevice(value);
							});
							alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
							});
							alert.show();
						} catch (Exception e) {
							Log.d(TAG, "saving file FAIL");
						}
					} else {
						Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
						intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
						startActivityForResult(intent, SELECT_SAVE_DEVICE_DIR_CODE);
					}
				}
			} else {
				if (checkPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, getString(R.string.perm_ask_write_title), getString(R.string.perm_ask_write_text), REQUEST_WRITE_HIST)) {
					try {
						final AlertDialog.Builder alert = new AlertDialog.Builder(this);
						alert.setTitle(getString(R.string.ask_device_suffix));
						alert.setMessage(getString(R.string.ask_suffix_text));

						final EditText input = new EditText(this);
						input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
						input.setImeOptions(EditorInfo.IME_ACTION_DONE);
						alert.setView(input);
						alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
							String value = input.getText().toString();
							saveDevice(value);
						});
						alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
						});
						alert.show();
					} catch (Exception e) {
						Log.d(TAG, "saving file FAIL");
					}
				}
			}
			return true;
		} else if (item.getItemId() == R.id.action_load_device) {
			Log.d(TAG, "loading device file");
			if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
				if (checkPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, getString(R.string.perm_ask_read_title), getString(R.string.perm_ask_read_text), REQUEST_READ_HIST)) {
					Intent loadIntent = new Intent()
							.setType("*/*")
							.setAction(Intent.ACTION_GET_CONTENT);
					startActivityForResult(Intent.createChooser(loadIntent, getString(R.string.ask_select_device_file)), SELECT_LOAD_DEVICE_CODE);
				}
			} else {
				Intent loadIntent = new Intent()
						.setType("*/*")
						.setAction(Intent.ACTION_GET_CONTENT);
				startActivityForResult(Intent.createChooser(loadIntent, getString(R.string.ask_select_device_file)), SELECT_LOAD_DEVICE_CODE);
			}
			return true;
		} else if (item.getItemId() == R.id.action_clear_spectrum) {
			onClickDeleteSpc(findViewById(R.id.clearSpectrumButton));
			return true;
		} else if (item.getItemId() == R.id.action_hist_smooth) {
			AtomSpectraService.setSmooth = !AtomSpectraService.setSmooth;
			item.setTitle(AtomSpectraService.setSmooth ? getString(R.string.hist_unsmooth) : getString(R.string.hist_smooth));
			return true;
		} else if (item.getItemId() == R.id.action_hist_delta) {
			if (item.isChecked()) {
				item.setChecked(false);
				AtomSpectraService.showDelta = false;
			} else {
				item.setChecked(true);
				AtomSpectraService.showDelta = true;
			}
			return true;
		} else if (item.getItemId() == R.id.action_hist_freeze) {
			if (AtomSpectraService.getFreeze()) {
				item.setIcon(R.drawable.menu_block);
				item.setTitle(R.string.hist_freeze_update);
				sendBroadcast(new Intent(Constants.ACTION.ACTION_FREEZE_DATA).putExtra(AtomSpectraSerial.EXTRA_DATA_TYPE, false).setPackage(Constants.PACKAGE_NAME));
				((TextView)findViewById(R.id.suffixView)).setText(AtomSpectraService.ForegroundSpectrum.getSuffix());
			} else {
				item.setIcon(R.drawable.record);
				item.setTitle(R.string.hist_continue_update);
				sendBroadcast(new Intent(Constants.ACTION.ACTION_FREEZE_DATA).putExtra(AtomSpectraSerial.EXTRA_DATA_TYPE, true).setPackage(Constants.PACKAGE_NAME));
			}
			return true;
		} else if (item.getItemId() == R.id.action_share_export) {
			Log.d(TAG, "sharing file");
			if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
				if (checkPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, getString(R.string.perm_ask_read_title), getString(R.string.perm_ask_share_text), REQUEST_SHARE)) {
					try {
						//do not delete, may be good
						Intent loadIntent = new Intent()
								.setType("*/*")
								.setAction(Intent.ACTION_GET_CONTENT);
						startActivityForResult(Intent.createChooser(loadIntent, getString(R.string.ask_share)), SHARE_FILE_CODE);
					} catch (Exception e) {
						Log.d(TAG, "Sharing a file FAIL");
					}
				}
			} else {
				Intent loadIntent = new Intent()
						.setType("*/*")
						.setAction(Intent.ACTION_GET_CONTENT);
				startActivityForResult(Intent.createChooser(loadIntent, getString(R.string.ask_select_device_file)), SHARE_FILE_CODE);
			}


			return true;
		} else if (item.getItemId() == R.id.action_find_isotopes) {
			Intent intent_find_isotopes = new Intent(this, AtomSpectraFindIsotope.class);
			startActivity(intent_find_isotopes);
			return true;
		} else if (item.getItemId() == R.id.action_edit_sensivity) {
			Intent intent_sensitivity = new Intent(this, AtomSpectraSensitivity.class);
			startActivity(intent_sensitivity);
			return true;
		} else if (item.getItemId() == R.id.action_cal_add_point) {
			onAddCalibrationPoint(findViewById(R.id.addPointButton));
			return true;
		} else if (item.getItemId() == R.id.action_cal_clear_point) {
			onClearCalibrationButton(findViewById(R.id.removeCalibrationButton));
			return true;
		} else if (item.getItemId() == R.id.action_cal_calc) {
			onCalibrateButton(findViewById(R.id.calibrateButton));
			return true;
		} else if (item.getItemId() == R.id.action_cal_point_1 ||
				item.getItemId() == R.id.action_cal_point_2 ||
				item.getItemId() == R.id.action_cal_point_3 ||
				item.getItemId() == R.id.action_cal_point_4 ||
				item.getItemId() == R.id.action_cal_point_5) {
			int number = 0;
			if (item.getItemId() == R.id.action_cal_point_2)
				number = 1;
			if (item.getItemId() == R.id.action_cal_point_3)
				number = 2;
			if (item.getItemId() == R.id.action_cal_point_4)
				number = 3;
			if (item.getItemId() == R.id.action_cal_point_5)
				number = 4;
			//TODO:
			final MenuItem itemMenu = item;
			final AlertDialog.Builder alert = new AlertDialog.Builder(this);
			alert.setTitle(getString(R.string.cal_coefficient_title, number));
			alert.setMessage(getString(R.string.cal_coefficient_text));

			final EditText input = new EditText(this);
			input.setText(String.format(Locale.getDefault(), "%.12g", AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().getCoeffArray(5)[number]));
			input.setKeyListener(new NumberKeyListener() {
				@NonNull
				@Override
				protected char[] getAcceptedChars() {
					return new char[]{'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '.', ',', 'e', 'E', '+', '-'};
				}

				@Override
				public int getInputType() {
					return InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED | InputType.TYPE_NUMBER_VARIATION_NORMAL;
				}
			});
			input.setImeOptions(EditorInfo.IME_ACTION_DONE);
			alert.setView(input);
			final Context context = this;
			int finalNumber = number;
			alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
				float fValue;
				try {
					fValue = Float.parseFloat(input.getText().toString().replaceAll(",", "."));
				} catch (Exception nfe) {
					Toast.makeText(context, getString(R.string.cal_error_number), Toast.LENGTH_SHORT).show();
					return;
				}
				Calibration new_cal = new Calibration();
				double[] coeffs = AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().getCoeffArray(5);
				coeffs[finalNumber] = fValue;
				new_cal.Calculate(coeffs);
				if (!new_cal.isCorrect()) {
					Toast.makeText(context, getString(R.string.cal_error_number), Toast.LENGTH_SHORT).show();
					return;
				}
				itemMenu.setTitle(String.format(Locale.getDefault(), "c%d: %.12g", finalNumber, fValue));
				AtomSpectraService.ForegroundSpectrum.setSpectrumCalibration(new_cal);
			});
			alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
			});
			alert.show();
			return true;
		} else if (item.getItemId() == R.id.action_cal_new_point1 ||
				item.getItemId() == R.id.action_cal_new_point2 ||
				item.getItemId() == R.id.action_cal_new_point3 ||
				item.getItemId() == R.id.action_cal_new_point4 ||
				item.getItemId() == R.id.action_cal_new_point5 ||
				item.getItemId() == R.id.action_cal_new_point6 ||
				item.getItemId() == R.id.action_cal_new_point7 ||
				item.getItemId() == R.id.action_cal_new_point8 ||
				item.getItemId() == R.id.action_cal_new_point9 ||
				item.getItemId() == R.id.action_cal_new_point10) {
			int number = 0;
			if (item.getItemId() == R.id.action_cal_new_point2)
				number = 1;
			if (item.getItemId() == R.id.action_cal_new_point3)
				number = 2;
			if (item.getItemId() == R.id.action_cal_new_point4)
				number = 3;
			if (item.getItemId() == R.id.action_cal_new_point5)
				number = 4;
			if (item.getItemId() == R.id.action_cal_new_point6)
				number = 5;
			if (item.getItemId() == R.id.action_cal_new_point7)
				number = 6;
			if (item.getItemId() == R.id.action_cal_new_point8)
				number = 7;
			if (item.getItemId() == R.id.action_cal_new_point9)
				number = 8;
			if (item.getItemId() == R.id.action_cal_new_point10)
				number = 9;
			int finalNumber = number;
			final AlertDialog.Builder alert = new AlertDialog.Builder(this)
					.setTitle(getString(R.string.ask_delete_calibration_line_title))
					.setMessage(getString(R.string.ask_delete_calibration_line_text, AtomSpectraService.newCalibration.getChannel(finalNumber), AtomSpectraService.newCalibration.getEnergy(finalNumber)))
					.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
						int channel_x = AtomSpectraService.newCalibration.getChannel(finalNumber);
						double fValue = AtomSpectraService.newCalibration.getEnergy(finalNumber);
						AtomSpectraService.newCalibration.removeLine(finalNumber);
						if (AtomSpectraService.newCalibration.getLines() > 1) {
							AtomSpectraService.newCalibration.Calculate(sharedPreferences.getInt(Constants.CONFIG.CONF_MAX_POLI_FACTOR, Constants.DEFAULT_POLI_FACTOR));
							if (!AtomSpectraService.newCalibration.isCorrect()) {
								Toast.makeText(AtomSpectra.this, getString(R.string.cal_maybe_wrong, channel_x, fValue), Toast.LENGTH_LONG).show();
							}
						} else {
							AtomSpectraService.showCalibrationFunction = false;
							app_menu.findItem(R.id.action_cal_draw_function).setChecked(false);
							app_menu.findItem(R.id.action_cal_draw_function).setEnabled(false);
							showCursorInfo();
						}
						updateCalibrationMenu();
						showCursorInfo();
					})
					.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
					});
			alert.show();
			return true;
		} else if (item.getItemId() == R.id.action_cal_function) {
			try {
				final AlertDialog.Builder alert = new AlertDialog.Builder(this);
				alert.setTitle(getString(R.string.cal_function_title));
				alert.setMessage(getString(R.string.cal_function_message));

				final TextView output = new TextView(this);
				output.setText(AtomSpectraService.ForegroundSpectrum.getSpectrumCalibration().getFunction());
				output.setTextSize(18);
				alert.setView(output);
				alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
				});
				alert.show();
				return true;
			} catch (Exception e) {
				//..
			}
		} else if (item.getItemId() == R.id.action_cal_draw_function) {
			if (AtomSpectraService.newCalibration.getLines() > 1) {
				item.setChecked(!AtomSpectraService.showCalibrationFunction);
				AtomSpectraService.showCalibrationFunction = !AtomSpectraService.showCalibrationFunction;
			} else {
				AtomSpectraService.showCalibrationFunction = false;
				item.setChecked(false);
			}
			showCursorInfo();
		} else if (item.getItemId() == R.id.action_cal_channel) {
			final MenuItem menuVal = item;
			final AlertDialog.Builder alert = new AlertDialog.Builder(this);
			alert.setTitle(getString(R.string.ask_last_channel_title));
			alert.setMessage(getString(R.string.ask_last_channel_text, Constants.NUM_HIST_POINTS));

			final EditText input = new EditText(this);
			input.setKeyListener(new NumberKeyListener() {
				@NonNull
				@Override
				protected char[] getAcceptedChars() {
					return new char[]{'0', '1', '2', '3', '4', '5', '6', '7', '8', '9'};
				}

				@Override
				public int getInputType() {
					return InputType.TYPE_CLASS_NUMBER;
				}
			});
			input.setImeOptions(EditorInfo.IME_ACTION_DONE);
			alert.setView(input);
			final Context context = this;
			alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
				int iValue;
				try {
					iValue = Integer.parseInt(input.getText().toString().replaceAll(",", "."));
				} catch (Exception nfe) {
					Toast.makeText(context, getString(R.string.cal_error_number), Toast.LENGTH_SHORT).show();
					return;
				}
				if (iValue < 1024)
					iValue = 1024;
				if (iValue > Constants.NUM_HIST_POINTS)
					iValue = Constants.NUM_HIST_POINTS;
				AtomSpectraService.lastCalibrationChannel = iValue;
				sharedPreferences.edit().putInt(Constants.CONFIG.CONF_LAST_CHANNEL, AtomSpectraService.lastCalibrationChannel).apply();
				menuVal.setTitle(getString(R.string.calibration_channel_format, iValue));
			});
			alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {

			});
			alert.show();
		} else if (item.getItemId() == R.id.action_settings) {
			Intent intent = new Intent(this, AtomSpectraSettings.class);
			startActivity(intent);
			return true;
		} else if (item.getItemId() == R.id.action_buy) {
			Intent buyIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://kbradar.org"));
			if (buyIntent.resolveActivity(getApplicationContext().getPackageManager()) != null)
				startActivity(buyIntent);
			return true;
		} else if (item.getItemId() == R.id.action_exit) {
			AtomSpectraIsotopes.foundList.clear();
			AtomSpectraIsotopes.showFoundIsotopes = false;
			sendBroadcast(new Intent(Constants.ACTION.ACTION_STOP_FOREGROUND).setPackage(Constants.PACKAGE_NAME));
			//finish();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	@SuppressLint({"ApplySharedPref", "WrongConstant"})
	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		Uri selectedFile;
		if (requestCode == LOAD_HIST_CODE && resultCode == RESULT_OK) {
			selectedFile = data.getData(); //The uri with the location of the file
			if (selectedFile != null)
				loadHist(selectedFile, true, true);
		} else if (requestCode == ADD_HIST_CODE && resultCode == RESULT_OK) {
			selectedFile = data.getData(); //The uri with the location of the file
			if (selectedFile != null)
				loadHist(selectedFile, true, true);
		} else if (requestCode == LOAD_BACK_CODE && resultCode == RESULT_OK) {
			selectedFile = data.getData(); //The uri with the location of the file
			if (selectedFile != null)
				loadBackground(selectedFile);
		} else if (requestCode == LOAD_CALIBRATION_CODE && resultCode == RESULT_OK) {
			selectedFile = data.getData(); //The uri with the location of the file
			if (selectedFile != null)
				loadHist(selectedFile, false, true);
		} else if (requestCode == SELECT_LOAD_DEVICE_CODE && resultCode == RESULT_OK) {
			selectedFile = data.getData(); //The uri with the location of the file
			if (selectedFile != null)
				loadDevice(selectedFile);
		} else if (requestCode == SHARE_FILE_CODE && resultCode == RESULT_OK) {
			selectedFile = data.getData();
			if (selectedFile != null)
				shareFile(selectedFile);
		} else if (requestCode == SELECT_SAVE_HIST_DIR_CODE && resultCode == RESULT_OK && (data != null)) {
			try {
				final Uri dirUri = data.getData();
				if (dirUri != null) {
					final DocumentFile dir = DocumentFile.fromTreeUri(this, dirUri);
					if ((dir != null) && dir.isDirectory()) {
						SharedPreferences.Editor editor = sharedPreferences.edit();
						Uri uri = data.getData();
						if (uri != null) {
							editor.putString(Constants.CONFIG.CONF_DIRECTORY_SELECTED, dirUri.toString());
							final int takeFlags = data.getFlags()
									& (Intent.FLAG_GRANT_READ_URI_PERMISSION
									| Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
// Check for the freshest data.
							getContentResolver().takePersistableUriPermission(uri, takeFlags);
						}
						editor.commit();
						final AlertDialog.Builder alert = new AlertDialog.Builder(this);
						alert.setTitle(getString(R.string.ask_spectrum_suffix));
						alert.setMessage(getString(R.string.ask_suffix_text));

						final EditText input = new EditText(this);
						input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
						input.setImeOptions(EditorInfo.IME_ACTION_DONE);
						input.setText(AtomSpectraService.ForegroundSpectrum.getSuffix(), TextView.BufferType.EDITABLE);
						alert.setView(input);
						alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
							AtomSpectraService.ForegroundSpectrum.setSuffix(input.getText().toString());
							TextView text = findViewById(R.id.suffixView);
							text.setText(AtomSpectraService.ForegroundSpectrum.getSuffix());
							saveHist(AtomSpectraService.ForegroundSpectrum.getSuffix());
						});
						alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
						});
						alert.show();
					} else {
						Toast.makeText(this, getString(R.string.perm_no_write_histogram), Toast.LENGTH_LONG).show();
					}
				} else {
					Toast.makeText(this, getString(R.string.perm_no_write_histogram), Toast.LENGTH_LONG).show();
				}
			} catch (Exception e) {
				Log.d(TAG, "saving file FAIL");
			}
		} else if (requestCode == SELECT_SAVE_BACK_DIR_CODE && resultCode == RESULT_OK && (data != null)) {
			try {
				final Uri dirUri = data.getData();
				if (dirUri != null) {
					final DocumentFile dir = DocumentFile.fromTreeUri(this, dirUri);
					if ((dir != null) && dir.isDirectory()) {
						SharedPreferences.Editor editor = sharedPreferences.edit();
						Uri uri = data.getData();
						if (uri != null) {
							editor.putString(Constants.CONFIG.CONF_DIRECTORY_SELECTED, dirUri.toString());
							final int takeFlags = data.getFlags()
									& (Intent.FLAG_GRANT_READ_URI_PERMISSION
									| Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
// Check for the freshest data.
							getContentResolver().takePersistableUriPermission(uri, takeFlags);
						}
						editor.commit();
						saveBackground();
					} else {
						Toast.makeText(this, getString(R.string.perm_no_write_background), Toast.LENGTH_LONG).show();
					}
				} else {
					Toast.makeText(this, getString(R.string.perm_no_write_background), Toast.LENGTH_LONG).show();
				}
			} catch (Exception e) {
				Log.d(TAG, "saving file FAIL");
			}
		} else if (requestCode == SELECT_LOAD_BACK_DIR_CODE && resultCode == RESULT_OK && (data != null)) {
			try {
				final Uri dirUri = data.getData();
				if (dirUri != null) {
					final DocumentFile dir = DocumentFile.fromTreeUri(this, dirUri);
					if ((dir != null) && dir.isDirectory()) {
						SharedPreferences.Editor editor = sharedPreferences.edit();
						Uri uri = data.getData();
						if (uri != null) {
							editor.putString(Constants.CONFIG.CONF_DIRECTORY_SELECTED, dirUri.toString());
							final int takeFlags = data.getFlags()
									& (Intent.FLAG_GRANT_READ_URI_PERMISSION
									| Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
// Check for the freshest data.
							getContentResolver().takePersistableUriPermission(uri, takeFlags);
						}
						editor.commit();
						loadBackground(null);
					} else {
						Toast.makeText(this, getString(R.string.perm_no_read_background), Toast.LENGTH_LONG).show();
					}
				} else {
					Toast.makeText(this, getString(R.string.perm_no_read_background), Toast.LENGTH_LONG).show();
				}
			} catch (Exception e) {
				Log.d(TAG, "saving file FAIL");
			}
		} else if (requestCode == SELECT_SAVE_EXPORT_DIR_CODE && resultCode == RESULT_OK && (data != null)) {
			try {
				final Uri dirUri = data.getData();
				if (dirUri != null) {
					final DocumentFile dir = DocumentFile.fromTreeUri(this, dirUri);
					if ((dir != null) && dir.isDirectory()) {
						SharedPreferences.Editor editor = sharedPreferences.edit();
						Uri uri = data.getData();
						if (uri != null) {
							editor.putString(Constants.CONFIG.CONF_DIRECTORY_SELECTED, dirUri.toString());
							final int takeFlags = data.getFlags()
									& (Intent.FLAG_GRANT_READ_URI_PERMISSION
									| Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
// Check for the freshest data.
							getContentResolver().takePersistableUriPermission(uri, takeFlags);
						}
						editor.commit();
						final AlertDialog.Builder alert = new AlertDialog.Builder(this);
						alert.setTitle(getString(R.string.ask_spectrum_suffix));
						alert.setMessage(getString(R.string.ask_suffix_text));

						final EditText input = new EditText(this);
						input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
						input.setImeOptions(EditorInfo.IME_ACTION_DONE);
						input.setText(AtomSpectraService.ForegroundSpectrum.getSuffix(), TextView.BufferType.EDITABLE);
						alert.setView(input);
						alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
							AtomSpectraService.ForegroundSpectrum.setSuffix(input.getText().toString());
							TextView text = findViewById(R.id.suffixView);
							text.setText(AtomSpectraService.ForegroundSpectrum.getSuffix());
							saveCSV(AtomSpectraService.ForegroundSpectrum.getSuffix(), false);
						});
						alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
						});
						alert.show();
					} else {
						Toast.makeText(this, getString(R.string.perm_no_write_export), Toast.LENGTH_LONG).show();
					}
				} else {
					Toast.makeText(this, getString(R.string.perm_no_write_export), Toast.LENGTH_LONG).show();
				}
			} catch (Exception e) {
				Log.d(TAG, "saving file FAIL");
			}
		} else if (requestCode == SELECT_SAVE_EXPORT_E_DIR_CODE && resultCode == RESULT_OK && (data != null)) {
			try {
				final Uri dirUri = data.getData();
				if (dirUri != null) {
					final DocumentFile dir = DocumentFile.fromTreeUri(this, dirUri);
					if ((dir != null) && dir.isDirectory()) {
						SharedPreferences.Editor editor = sharedPreferences.edit();
						Uri uri = data.getData();
						if (uri != null) {
							editor.putString(Constants.CONFIG.CONF_DIRECTORY_SELECTED, dirUri.toString());
							final int takeFlags = data.getFlags()
									& (Intent.FLAG_GRANT_READ_URI_PERMISSION
									| Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
// Check for the freshest data.
							getContentResolver().takePersistableUriPermission(uri, takeFlags);
						}
						editor.commit();
						final AlertDialog.Builder alert = new AlertDialog.Builder(this);
						alert.setTitle(getString(R.string.ask_spectrum_suffix));
						alert.setMessage(getString(R.string.ask_suffix_text));

						final EditText input = new EditText(this);
						input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
						input.setImeOptions(EditorInfo.IME_ACTION_DONE);
						input.setText(AtomSpectraService.ForegroundSpectrum.getSuffix(), TextView.BufferType.EDITABLE);
						alert.setView(input);
						alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
							AtomSpectraService.ForegroundSpectrum.setSuffix(input.getText().toString());
							TextView text = findViewById(R.id.suffixView);
							text.setText(AtomSpectraService.ForegroundSpectrum.getSuffix());
							saveCSV(AtomSpectraService.ForegroundSpectrum.getSuffix(), true);
						});
						alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
						});
						alert.show();
					} else {
						Toast.makeText(this, getString(R.string.perm_no_write_export_energy), Toast.LENGTH_LONG).show();
					}
				} else {
					Toast.makeText(this, getString(R.string.perm_no_write_export_energy), Toast.LENGTH_LONG).show();
				}
			} catch (Exception e) {
				Log.d(TAG, "saving file FAIL");
			}
		} else if (requestCode == SELECT_SAVE_EXPORT_BQ_DIR_CODE && resultCode == RESULT_OK && (data != null)) {
			try {
				final Uri dirUri = data.getData();
				if (dirUri != null) {
					final DocumentFile dir = DocumentFile.fromTreeUri(this, dirUri);
					if ((dir != null) && dir.isDirectory()) {
						SharedPreferences.Editor editor = sharedPreferences.edit();
						Uri uri = data.getData();
						if (uri != null) {
							editor.putString(Constants.CONFIG.CONF_DIRECTORY_SELECTED, dirUri.toString());
							final int takeFlags = data.getFlags()
									& (Intent.FLAG_GRANT_READ_URI_PERMISSION
									| Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
// Check for the freshest data.
							getContentResolver().takePersistableUriPermission(uri, takeFlags);
						}
						editor.commit();
						final AlertDialog.Builder alert = new AlertDialog.Builder(this);
						alert.setTitle(getString(R.string.ask_spectrum_suffix));
						alert.setMessage(getString(R.string.ask_suffix_text));

						final EditText input = new EditText(this);
						input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
						input.setImeOptions(EditorInfo.IME_ACTION_DONE);
						input.setText(AtomSpectraService.ForegroundSpectrum.getSuffix(), TextView.BufferType.EDITABLE);
						alert.setView(input);
						alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
							AtomSpectraService.ForegroundSpectrum.setSuffix(input.getText().toString());
							TextView text = findViewById(R.id.suffixView);
							text.setText(AtomSpectraService.ForegroundSpectrum.getSuffix());
							saveBqMoni(AtomSpectraService.ForegroundSpectrum.getSuffix());
						});
						alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
						});
						alert.show();
					} else {
						Toast.makeText(this, getString(R.string.perm_no_write_bqmoni), Toast.LENGTH_LONG).show();
					}
				} else {
					Toast.makeText(this, getString(R.string.perm_no_write_bqmoni), Toast.LENGTH_LONG).show();
				}
			} catch (Exception e) {
				Log.d(TAG, "saving file FAIL");
			}
		} else if (requestCode == SELECT_SAVE_EXPORT_SPE_DIR_CODE && resultCode == RESULT_OK && (data != null)) {
			try {
				final Uri dirUri = data.getData();
				if (dirUri != null) {
					final DocumentFile dir = DocumentFile.fromTreeUri(this, dirUri);
					if ((dir != null) && dir.isDirectory()) {
						SharedPreferences.Editor editor = sharedPreferences.edit();
						Uri uri = data.getData();
						if (uri != null) {
							editor.putString(Constants.CONFIG.CONF_DIRECTORY_SELECTED, dirUri.toString());
							final int takeFlags = data.getFlags()
									& (Intent.FLAG_GRANT_READ_URI_PERMISSION
									| Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
// Check for the freshest data.
							getContentResolver().takePersistableUriPermission(uri, takeFlags);
						}
						editor.commit();
						final AlertDialog.Builder alert = new AlertDialog.Builder(this);
						alert.setTitle(getString(R.string.ask_export_spe));
						alert.setMessage(getString(R.string.ask_suffix_text));

						final EditText input = new EditText(this);
						input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
						input.setText(AtomSpectraService.ForegroundSpectrum.getSuffix(), TextView.BufferType.EDITABLE);
						input.setImeOptions(EditorInfo.IME_ACTION_DONE);
						alert.setView(input);
						alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
							AtomSpectraService.ForegroundSpectrum.setSuffix(input.getText().toString());
							TextView text = findViewById(R.id.suffixView);
							text.setText(AtomSpectraService.ForegroundSpectrum.getSuffix());
							saveSPE(AtomSpectraService.ForegroundSpectrum.getSuffix());
						});
						alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
						});
						alert.show();
					} else {
						Toast.makeText(this, getString(R.string.perm_no_write_spe), Toast.LENGTH_LONG).show();
					}
				} else {
					Toast.makeText(this, getString(R.string.perm_no_write_spe), Toast.LENGTH_LONG).show();
				}
			} catch (Exception e) {
				Log.d(TAG, "saving file FAIL");
			}
		} else if (requestCode == SELECT_SAVE_EXPORT_N42_DIR_CODE && resultCode == RESULT_OK && (data != null)) {
			try {
				final Uri dirUri = data.getData();
				if (dirUri != null) {
					final DocumentFile dir = DocumentFile.fromTreeUri(this, dirUri);
					if ((dir != null) && dir.isDirectory()) {
						SharedPreferences.Editor editor = sharedPreferences.edit();
						Uri uri = data.getData();
						if (uri != null) {
							editor.putString(Constants.CONFIG.CONF_DIRECTORY_SELECTED, dirUri.toString());
							final int takeFlags = data.getFlags()
									& (Intent.FLAG_GRANT_READ_URI_PERMISSION
									| Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
// Check for the freshest data.
							getContentResolver().takePersistableUriPermission(uri, takeFlags);
						}
						editor.commit();
						final AlertDialog.Builder alert = new AlertDialog.Builder(this);
						alert.setTitle(getString(R.string.ask_export_N42));
						alert.setMessage(getString(R.string.ask_suffix_text));

						final EditText input = new EditText(this);
						input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
						input.setImeOptions(EditorInfo.IME_ACTION_DONE);
						input.setText(AtomSpectraService.ForegroundSpectrum.getSuffix(), TextView.BufferType.EDITABLE);
						alert.setView(input);
						alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
							AtomSpectraService.ForegroundSpectrum.setSuffix(input.getText().toString());
							TextView text = findViewById(R.id.suffixView);
							text.setText(AtomSpectraService.ForegroundSpectrum.getSuffix());
							saveN42(AtomSpectraService.ForegroundSpectrum.getSuffix());
						});
						alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
						});
						alert.show();
					} else {
						Toast.makeText(this, getString(R.string.perm_no_write_N42), Toast.LENGTH_LONG).show();
					}
				} else {
					Toast.makeText(this, getString(R.string.perm_no_write_N42), Toast.LENGTH_LONG).show();
				}
			} catch (Exception e) {
				Log.d(TAG, "saving file FAIL");
			}
		} else if (requestCode == SELECT_SAVE_DEVICE_DIR_CODE && resultCode == RESULT_OK && (data != null)) {
			try {
				final Uri dirUri = data.getData();
				if (dirUri != null) {
					final DocumentFile dir = DocumentFile.fromTreeUri(this, dirUri);
					if ((dir != null) && dir.isDirectory()) {
						SharedPreferences.Editor editor = sharedPreferences.edit();
						Uri uri = data.getData();
						if (uri != null) {
							editor.putString(Constants.CONFIG.CONF_DIRECTORY_SELECTED, dirUri.toString());
							final int takeFlags = data.getFlags()
									& (Intent.FLAG_GRANT_READ_URI_PERMISSION
									| Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
// Check for the freshest data.
							getContentResolver().takePersistableUriPermission(uri, takeFlags);
						}
						editor.commit();
						final AlertDialog.Builder alert = new AlertDialog.Builder(this);
						alert.setTitle(getString(R.string.ask_device_suffix));
						alert.setMessage(getString(R.string.ask_suffix_text));

						final EditText input = new EditText(this);
						input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
						input.setImeOptions(EditorInfo.IME_ACTION_DONE);
						alert.setView(input);
						alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
							String value = input.getText().toString();
							saveDevice(value);
						});
						alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
						});
						alert.show();
					} else {
						Toast.makeText(this, getString(R.string.perm_no_write_device), Toast.LENGTH_LONG).show();
					}
				} else {
					Toast.makeText(this, getString(R.string.perm_no_write_device), Toast.LENGTH_LONG).show();
				}
			} catch (Exception e) {
				Log.d(TAG, "saving file FAIL");
			}
		} else
			super.onActivityResult(requestCode, resultCode, data);
	}

	//Load a ful spectrum or just a calibration data
	private void loadHist(Uri histFile, boolean setData, boolean showMessage) {
		final String filename = histFile.getPath();
		if (filename == null) {
			Log.d(TAG, "Null filename");
			Toast.makeText(this, getString(R.string.strange_file_name), Toast.LENGTH_LONG).show();
			return;
		}
		Log.d(TAG, filename);

		SpectrumFileAS spectrumFile = new SpectrumFileAS();
		spectrumFile.setChannels(Constants.NUM_HIST_POINTS);
		try {
			InputStream inputFile = getContentResolver().openInputStream(histFile);
			if (inputFile == null) {
				throw new Exception();
			}
			if (spectrumFile.loadSpectrum(inputFile, this)) {
				Spectrum spectrum = spectrumFile.getSpectrum(0);
				if (spectrum == null) {
					throw new Exception("");
				}
				if (setData) {
					AtomSpectraService.freeze(true);
					sendBroadcast(new Intent(Constants.ACTION.ACTION_FREEZE_DATA).putExtra(AtomSpectraSerial.EXTRA_DATA_TYPE, true).setPackage(Constants.PACKAGE_NAME));
					AtomSpectraService.ForegroundSpectrum.Clone(spectrum);
					AtomSpectraService.ForegroundSaveSpectrum.initSpectrumData();
					((TextView) findViewById(R.id.suffixView)).setText(spectrum.getSuffix());
				} else {
					AtomSpectraService.ForegroundSpectrum.setSpectrumCalibration(spectrum.getSpectrumCalibration());
				}
				AtomSpectraService.recalculateInterval();
				updateCalibrationMenu();

				if (setData) {
//					AtomSpectraService.time_counter = spectrum.getSpectrumTime();
					AtomSpectraService.total_pulses = spectrum.getTotalCounts();
					if (app_menu != null) {
						app_menu.findItem(R.id.action_hist_freeze).setIcon(R.drawable.record);
						app_menu.findItem(R.id.action_hist_freeze).setTitle(R.string.hist_continue_update);
					}
					AtomSpectraIsotopes.showFoundIsotopes = false;
					AtomSpectraIsotopes.foundList.clear();
					AtomSpectraService.requestUpdateGraph();
					Log.d(TAG, "Histogram is loaded successfully");
					if (showMessage)
						Toast.makeText(this, getString(R.string.hist_load_success), Toast.LENGTH_SHORT).show();
				} else {
					Log.d(TAG, "Calibration is loaded successfully");
					Toast.makeText(this, getString(R.string.cal_load_success), Toast.LENGTH_SHORT).show();
				}
			} else {
				throw new Exception(""); //to show error result in one place
			}
		} catch (Exception e) {
			if (setData) {
				if (showMessage)
					Toast.makeText(this, getString(R.string.hist_load_error), Toast.LENGTH_LONG).show();
			} else {
				Toast.makeText(this, getString(R.string.cal_load_error), Toast.LENGTH_LONG).show();
			}
			//Toast.makeText(this, e.toString(), Toast.LENGTH_LONG).show();
		}
	}

	private void saveHist(String suffix) {
		boolean fileNamePrefix = sharedPreferences.getBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_PREFIX, Constants.OUTPUT_FILE_NAME_PREFIX_DEFAULT);
		boolean fileNameDate = sharedPreferences.getBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_DATE, Constants.OUTPUT_FILE_NAME_DATE_DEFAULT);
		boolean fileNameTime = sharedPreferences.getBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_TIME, Constants.OUTPUT_FILE_NAME_TIME_DEFAULT);

		Spectrum spectrum = new Spectrum(AtomSpectraService.ForegroundSpectrum);
		Spectrum backSpectrum = new Spectrum(AtomSpectraService.BackgroundSpectrum);

		if (!sharedPreferences.getBoolean(Constants.CONFIG.CONF_ADD_GPS_TO_FILES, false)) {
			spectrum.setLocation(null).updateComments();
			backSpectrum.setLocation(null).updateComments();
		}

		Pair<OutputStreamWriter, Uri> returnPair = SpectrumFile.prepareOutputStream(this, sharedPreferences.getString(Constants.CONFIG.CONF_DIRECTORY_SELECTED, null), spectrum.getSpectrumDate(), "Spectrum", fileNamePrefix, suffix, ".txt", "text/plain", fileNameDate, fileNameTime, false);
		if (returnPair == null) {
			Toast.makeText(this, getString(R.string.perm_no_write_histogram), Toast.LENGTH_LONG).show();
			return;
		}

		OutputStreamWriter docStream = returnPair.first;
		String spectrumFileName = returnPair.second.getPath();

		SpectrumFileAS saveFile = new SpectrumFileAS();
		saveFile.
				addSpectrum(spectrum).
				setChannels(spectrum.getDataArray().length).
				setChannelCompression(1);
		if (saveFile.saveSpectrum(docStream, this)) {
			AtomSpectraService.ForegroundSpectrum.setChanged(false);
			Toast.makeText(this, getString(R.string.hist_save_success, spectrumFileName), Toast.LENGTH_SHORT).show();
		} else {
			Toast.makeText(this, getString(R.string.hist_save_error, spectrumFileName), Toast.LENGTH_LONG).show();
		}
	}

	private void moveToBackground() {
		AtomSpectraService.BackgroundSpectrum.Clone(AtomSpectraService.ForegroundSpectrum);
		boolean show_back = !AtomSpectraService.BackgroundSpectrum.isEmpty();
		AtomSpectraService.background_show = show_back;
		app_menu.findItem(R.id.action_background_show).setChecked(show_back);
		app_menu.findItem(R.id.action_background_save).setEnabled(show_back);
		app_menu.findItem(R.id.action_background_show).setEnabled(show_back);
		app_menu.findItem(R.id.action_background_subtract).setEnabled(show_back);
		app_menu.findItem(R.id.action_background_clear).setEnabled(show_back);
		app_menu.findItem(R.id.action_background_suffix).setEnabled(show_back);
		TextView view = findViewById(R.id.backgroundSuffixView);
		view.setVisibility(show_back ? TextView.VISIBLE : TextView.INVISIBLE);
		view.setText(AtomSpectraService.BackgroundSpectrum.getSuffix());
	}

	private void loadBackground(Uri file) {
		InputStream docStream;
		//String backgroundFileName;
		String folder = sharedPreferences.getString(Constants.CONFIG.CONF_DIRECTORY_SELECTED, null);
		Uri folderUri = Uri.parse(folder);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			if (file == null) {
				if (folder == null) {
					Toast.makeText(this, getString(R.string.background_load_error), Toast.LENGTH_LONG).show();
					return;
				}
				Uri dir = Uri.parse(folder);
				if (dir == null) {
					Toast.makeText(this, getString(R.string.background_load_error), Toast.LENGTH_LONG).show();
					return;
				}
				DocumentFile dirFile = DocumentFile.fromTreeUri(this, dir);
				if ((dirFile == null) || !dirFile.isDirectory()) {
					Toast.makeText(this, getString(R.string.background_load_error), Toast.LENGTH_LONG).show();
					return;
				}
				DocumentFile backgroundFile = DocumentFile.fromSingleUri(this, Uri.parse(folder + "/document/" + Uri.encode(DocumentsContract.getTreeDocumentId(folderUri) + "/Background")));
				if ((backgroundFile == null) || !backgroundFile.isFile()) {
					Toast.makeText(this, getString(R.string.background_load_error), Toast.LENGTH_LONG).show();
					return;
				}
				try {
					docStream = getContentResolver().openInputStream(backgroundFile.getUri());
				} catch (Exception e) {
					Toast.makeText(this, getString(R.string.background_load_error), Toast.LENGTH_LONG).show();
					return;
				}
				//backgroundFileName = backgroundFile.getUri().getPath();
				Log.d(TAG, "Background");
			} else {
				//backgroundFileName = file.getPath();
				Log.d(TAG, file.toString());
				try {
					docStream = getContentResolver().openInputStream(file);
				} catch (Exception e) {
					Toast.makeText(this, getString(R.string.background_load_error), Toast.LENGTH_LONG).show();
					return;
				}
			}
		} else {
			if (file == null) {
				if (folder == null) {
					Toast.makeText(this, getString(R.string.background_load_error), Toast.LENGTH_LONG).show();
					return;
				}
				//backgroundFileName = "/AtomSpectra/Background";
				try {
					docStream = new FileInputStream(folder + "/Background");
				} catch (Exception e) {
					Toast.makeText(this, getString(R.string.background_load_error), Toast.LENGTH_LONG).show();
					return;
				}
			} else {
				//backgroundFileName = file.getPath();
				try {
					docStream = new FileInputStream(file.getPath());
				} catch (Exception e) {
					Toast.makeText(this, getString(R.string.background_load_error), Toast.LENGTH_LONG).show();
					return;
				}
//				if (backgroundFileName == null) {
//					Log.d(TAG, "Null filename");
//					Toast.makeText(this, getString(R.string.strange_file_name), Toast.LENGTH_LONG).show();
//					return;
//				}
			}
		}
		SpectrumFileAS spectrumFile = new SpectrumFileAS();
		try {
			if (docStream == null) {
				throw new Exception();
			}
			if (spectrumFile.loadSpectrum(docStream, this)) {
				Spectrum spectrum = spectrumFile.getSpectrum(0);
				if (spectrum == null) {
					throw new Exception("");
				}
				AtomSpectraService.BackgroundSpectrum.Clone(spectrum);
				background_subtract = false;
				boolean show_back = !AtomSpectraService.BackgroundSpectrum.isEmpty();
				AtomSpectraService.background_show = show_back;
				app_menu.findItem(R.id.action_background_save).setEnabled(show_back);
				app_menu.findItem(R.id.action_background_show).setChecked(show_back);
				app_menu.findItem(R.id.action_background_show).setEnabled(show_back);
				app_menu.findItem(R.id.action_background_subtract).setEnabled(show_back);
				app_menu.findItem(R.id.action_background_subtract).setChecked(false);
				app_menu.findItem(R.id.action_background_clear).setEnabled(show_back);
				app_menu.findItem(R.id.action_background_suffix).setEnabled(show_back);
				TextView view = findViewById(R.id.backgroundSuffixView);
				view.setText(AtomSpectraService.BackgroundSpectrum.getSuffix());
				view.setVisibility(show_back ? TextView.VISIBLE : TextView.INVISIBLE);
				AtomSpectraService.requestUpdateGraph();
				Log.d(TAG, "Background is loaded successfully");
				Toast.makeText(this, getString(R.string.background_load_success), Toast.LENGTH_SHORT).show();
			} else {
				throw new Exception(""); //to show error result in one place
			}
		} catch (Exception e) {
			Toast.makeText(this, getString(R.string.background_load_error), Toast.LENGTH_LONG).show();
		}
	}

	private void saveBackground() {
		if (AtomSpectraService.BackgroundSpectrum.isEmpty()) {
			Toast.makeText(this, getString(R.string.background_no_data), Toast.LENGTH_LONG).show();
			return;
		}

		Spectrum spectrum = new Spectrum(AtomSpectraService.BackgroundSpectrum);

		if (!sharedPreferences.getBoolean(Constants.CONFIG.CONF_ADD_GPS_TO_FILES, false)) {
			spectrum.setLocation(null).updateComments();
		}

		Pair<OutputStreamWriter, Uri> returnPair = SpectrumFile.prepareOutputStream(this, sharedPreferences.getString(Constants.CONFIG.CONF_DIRECTORY_SELECTED, null), spectrum.getSpectrumDate(), "Background", true, "", "", "application/octet-stream", false, false, true);
		if (returnPair == null) {
			Toast.makeText(this, getString(R.string.background_save_error), Toast.LENGTH_LONG).show();
			return;
		}

		OutputStreamWriter docStream = returnPair.first;
		//String backgroundFileName = returnPair.second;

		SpectrumFileAS saveFile = new SpectrumFileAS();
		saveFile.
				addSpectrum(spectrum).
				setChannels(spectrum.getDataArray().length).
				setChannelCompression(1);
		if (saveFile.saveSpectrum(docStream, this)) {
			Toast.makeText(this, getString(R.string.background_save_success), Toast.LENGTH_SHORT).show();
		} else {
			Toast.makeText(this, getString(R.string.background_save_error), Toast.LENGTH_LONG).show();
		}
	}

	private void saveCSV(String suffix, boolean with_energy) {
		boolean fileNamePrefix = sharedPreferences.getBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_PREFIX, Constants.OUTPUT_FILE_NAME_PREFIX_DEFAULT);
		boolean fileNameDate = sharedPreferences.getBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_DATE, Constants.OUTPUT_FILE_NAME_DATE_DEFAULT);
		boolean fileNameTime = sharedPreferences.getBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_TIME, Constants.OUTPUT_FILE_NAME_TIME_DEFAULT);

		Spectrum spectrum = new Spectrum(AtomSpectraService.ForegroundSpectrum);
		Spectrum backSpectrum = new Spectrum(AtomSpectraService.BackgroundSpectrum);

		if (!sharedPreferences.getBoolean(Constants.CONFIG.CONF_ADD_GPS_TO_FILES, false)) {
			spectrum.setLocation(null).updateComments();
			backSpectrum.setLocation(null).updateComments();
		}

		Pair<OutputStreamWriter, Uri> returnPair = SpectrumFile.prepareOutputStream(this, sharedPreferences.getString(Constants.CONFIG.CONF_DIRECTORY_SELECTED, null), spectrum.getSpectrumDate(), "Export", fileNamePrefix, suffix, ".csv", "text/csv", fileNameDate, fileNameTime, false);
		if (returnPair == null) {
			Toast.makeText(this, getString(R.string.perm_no_write_export), Toast.LENGTH_LONG).show();
			return;
		}

		OutputStreamWriter docStream = returnPair.first;
		String spectrumFileName = returnPair.second.getPath();

		SpectrumFileCSV saveFile = new SpectrumFileCSV();
		saveFile.
				addSpectrum(spectrum).
				setChannels(saveChannels).
				setChannelCompression(channelCompression);
		saveFile.setAddEnergy(with_energy);
		if (saveFile.saveSpectrum(docStream, this)) {
			Toast.makeText(this, getString(R.string.export_save_success, spectrumFileName), Toast.LENGTH_SHORT).show();
		} else {
			Toast.makeText(this, getString(R.string.export_save_error, spectrumFileName), Toast.LENGTH_LONG).show();
		}
	}

	private void saveBqMoni(String suffix) {
		boolean fileNamePrefix = sharedPreferences.getBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_PREFIX, Constants.OUTPUT_FILE_NAME_PREFIX_DEFAULT);
		boolean fileNameDate = sharedPreferences.getBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_DATE, Constants.OUTPUT_FILE_NAME_DATE_DEFAULT);
		boolean fileNameTime = sharedPreferences.getBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_TIME, Constants.OUTPUT_FILE_NAME_TIME_DEFAULT);

		Spectrum spectrum = new Spectrum(AtomSpectraService.ForegroundSpectrum);
		Spectrum backSpectrum = new Spectrum(AtomSpectraService.BackgroundSpectrum);

		if (!sharedPreferences.getBoolean(Constants.CONFIG.CONF_ADD_GPS_TO_FILES, false)) {
			spectrum.setLocation(null).updateComments();
			backSpectrum.setLocation(null).updateComments();
		}

		Pair<OutputStreamWriter, Uri> returnPair = SpectrumFile.prepareOutputStream(this, sharedPreferences.getString(Constants.CONFIG.CONF_DIRECTORY_SELECTED, null), spectrum.getSpectrumDate(), "Bq", fileNamePrefix, suffix, ".xml", "text/xml", fileNameDate, fileNameTime, false);
		if (returnPair == null) {
			Toast.makeText(this, getString(R.string.perm_no_write_bqmoni), Toast.LENGTH_LONG).show();
			return;
		}

		OutputStreamWriter docStream = returnPair.first;
		String spectrumFileName = returnPair.second.getPath();

		SpectrumFileBqMoni saveFile = new SpectrumFileBqMoni();
		saveFile.
				addSpectrum(spectrum).
				addBackgroundSpectrum(backSpectrum).
				setChannels(saveChannels).
				setChannelCompression(channelCompression);
		if (saveFile.saveSpectrum(docStream, this)) {
			Toast.makeText(this, getString(R.string.export_save_success, spectrumFileName), Toast.LENGTH_SHORT).show();
		} else {
			Toast.makeText(this, getString(R.string.export_save_error, spectrumFileName), Toast.LENGTH_LONG).show();
		}
	}

	private void saveSPE(String suffix) {
		boolean fileNamePrefix = sharedPreferences.getBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_PREFIX, Constants.OUTPUT_FILE_NAME_PREFIX_DEFAULT);
		boolean fileNameDate = sharedPreferences.getBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_DATE, Constants.OUTPUT_FILE_NAME_DATE_DEFAULT);
		boolean fileNameTime = sharedPreferences.getBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_TIME, Constants.OUTPUT_FILE_NAME_TIME_DEFAULT);

		Spectrum spectrum = new Spectrum(AtomSpectraService.ForegroundSpectrum);

		if (!sharedPreferences.getBoolean(Constants.CONFIG.CONF_ADD_GPS_TO_FILES, false)) {
			spectrum.setLocation(null).updateComments();
		}

		Pair<OutputStreamWriter, Uri> returnPair = SpectrumFile.prepareOutputStream(this, sharedPreferences.getString(Constants.CONFIG.CONF_DIRECTORY_SELECTED, null), spectrum.getSpectrumDate(), "MCA", fileNamePrefix, suffix, ".spe", "application/octet-stream", fileNameDate, fileNameTime, false);
		if (returnPair == null) {
			Toast.makeText(this, getString(R.string.perm_no_write_spe), Toast.LENGTH_LONG).show();
			return;
		}

		OutputStreamWriter docStream = returnPair.first;
		String spectrumFileName = returnPair.second.getPath();

		SpectrumFileSPE saveFile = new SpectrumFileSPE();
		saveFile.
				addSpectrum(spectrum).
				setChannels(saveChannels).
				setChannelCompression(channelCompression);
		if (saveFile.saveSpectrum(docStream, this)) {
			Toast.makeText(this, getString(R.string.export_save_success, spectrumFileName), Toast.LENGTH_SHORT).show();
		} else {
			Toast.makeText(this, getString(R.string.export_save_error, spectrumFileName), Toast.LENGTH_LONG).show();
		}
	}

	private void saveN42(String suffix) {
		boolean fileNamePrefix = sharedPreferences.getBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_PREFIX, Constants.OUTPUT_FILE_NAME_PREFIX_DEFAULT);
		boolean fileNameDate = sharedPreferences.getBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_DATE, Constants.OUTPUT_FILE_NAME_DATE_DEFAULT);
		boolean fileNameTime = sharedPreferences.getBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_TIME, Constants.OUTPUT_FILE_NAME_TIME_DEFAULT);

		Spectrum spectrum = new Spectrum(AtomSpectraService.ForegroundSpectrum);
		Spectrum backSpectrum = new Spectrum(AtomSpectraService.BackgroundSpectrum);

		if (!sharedPreferences.getBoolean(Constants.CONFIG.CONF_ADD_GPS_TO_FILES, false)) {
			spectrum.setLocation(null).updateComments();
			backSpectrum.setLocation(null).updateComments();
		}

		Pair<OutputStreamWriter, Uri> returnPair = SpectrumFile.prepareOutputStream(this, sharedPreferences.getString(Constants.CONFIG.CONF_DIRECTORY_SELECTED, null), spectrum.getSpectrumDate(), "MCA", fileNamePrefix, suffix, ".N42", "application/octet-stream", fileNameDate, fileNameTime, false);
		if (returnPair == null) {
			Toast.makeText(this, getString(R.string.perm_no_write_N42), Toast.LENGTH_LONG).show();
			return;
		}

		OutputStreamWriter docStream = returnPair.first;
		String spectrumFileName = returnPair.second.getPath();

		SpectrumFileN42 saveFile = new SpectrumFileN42();
		saveFile.
				addSpectrum(spectrum).
				addBackgroundSpectrum(backSpectrum).
				setChannels(saveChannels).
				setChannelCompression(channelCompression);
		if (saveFile.saveSpectrum(docStream, this)) {
			Toast.makeText(this, getString(R.string.export_save_success, spectrumFileName), Toast.LENGTH_SHORT).show();
		} else {
			Toast.makeText(this, getString(R.string.export_save_error, spectrumFileName), Toast.LENGTH_LONG).show();
		}
	}

	public void shareFile(Uri file) {
//        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US);
		final String filename = file.getPath();
		String onlyName, onlyLoName;
		if (filename == null) {
			Toast.makeText(this, getString(R.string.strange_file_name), Toast.LENGTH_LONG).show();
			return;
		}
		if (filename.lastIndexOf('/') != -1) {
			onlyName = filename.substring(filename.lastIndexOf('/') + 1);
		} else {
			onlyName = filename;
		}
		Log.d(TAG, filename);
		Intent shareIntent = new Intent();
		shareIntent.setAction(Intent.ACTION_SEND);
		shareIntent.putExtra(Intent.EXTRA_SUBJECT, onlyName);
		shareIntent.putExtra(Intent.EXTRA_TEXT, getString(R.string.ask_share_text, onlyName));
		try {
			shareIntent.putExtra(Intent.EXTRA_STREAM, file);
		} catch (Exception e) {
			Toast.makeText(this, getString(R.string.perm_no_outside), Toast.LENGTH_LONG).show();
			return;
		}
		shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

		onlyLoName = onlyName.toLowerCase(Locale.ROOT);

		if (!onlyLoName.equals(".csv") && onlyLoName.endsWith(".csv")) {
			shareIntent.setType("text/csv");
		} else if (!onlyLoName.equals(".txt") && onlyLoName.endsWith(".txt")) {
			shareIntent.setType("text/plain");
		} else if (!onlyLoName.equals(".n42") && onlyLoName.endsWith(".n42")) {
			shareIntent.setType("text/plain");
		} else if (!onlyLoName.equals(".spe") && onlyLoName.endsWith(".spe")) {
			shareIntent.setType("text/plain");
		} else if (onlyName.equals("Background")) {
			shareIntent.setType("text/plain");
		} else
			shareIntent.setType("*/*");

		try {
			if (shareIntent.resolveActivity(getPackageManager()) != null)
				startActivity(Intent.createChooser(shareIntent, null));
		} catch (Exception e) {
			Toast.makeText(this, getString(R.string.something_wrong), Toast.LENGTH_LONG).show();
		}
	}

	//Save device data
	private void saveDevice(String suffix) {
		boolean fileNamePrefix = sharedPreferences.getBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_PREFIX, Constants.OUTPUT_FILE_NAME_PREFIX_DEFAULT);
		boolean fileNameDate = sharedPreferences.getBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_DATE, Constants.OUTPUT_FILE_NAME_DATE_DEFAULT);
		boolean fileNameTime = sharedPreferences.getBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_TIME, Constants.OUTPUT_FILE_NAME_TIME_DEFAULT);
		Pair<OutputStreamWriter, Uri> returnPair = SpectrumFile.prepareOutputStream(this, sharedPreferences.getString(Constants.CONFIG.CONF_DIRECTORY_SELECTED, null), 0, "Device", fileNamePrefix, suffix, ".txt", "text/plain", fileNameDate, fileNameTime, false);
		if (returnPair == null) {
			Toast.makeText(this, getString(R.string.perm_no_write_device), Toast.LENGTH_LONG).show();
			return;
		}

		OutputStreamWriter docStream = returnPair.first;
		String deviceFileName = returnPair.second.getPath();

		try {
			OutputStreamWriter fw = docStream;
			fw.append("DEVFORMAT: 1\n");      //Type of file
			fw.append(String.format(Locale.US, "%d\n", sharedPreferences.getInt(Constants.CONFIG.CONF_SENSG, Constants.SENSG_DEFAULT)));                               //Sensivity
			fw.append(String.format(Locale.US, "%d\n", sharedPreferences.getInt(Constants.CONFIG.CONF_BACKGROUND, Constants.BACKGND_CNT_DEFAULT)));                    //Background
			fw.append(String.format(Locale.US, "%d\n", sharedPreferences.getInt(Constants.CONFIG.CONF_SEARCH_FAST, Constants.SEARCH_FAST_DEFAULT)));                   //fast counts
			fw.append(String.format(Locale.US, "%d\n", sharedPreferences.getInt(Constants.CONFIG.CONF_SEARCH_MEDIUM, Constants.SEARCH_MEDIUM_DEFAULT)));               //medium counts
			fw.append(String.format(Locale.US, "%d\n", sharedPreferences.getInt(Constants.CONFIG.CONF_SEARCH_SLOW, Constants.SEARCH_SLOW_DEFAULT)));                   //slow counts
			fw.append(String.format(Locale.US, "%d\n", sharedPreferences.getInt(Constants.CONFIG.CONF_MIN_POINTS, Constants.MIN_FRONT_POINTS_DEFAULT)));               //minimum front points
			fw.append(String.format(Locale.US, "%d\n", sharedPreferences.getInt(Constants.CONFIG.CONF_MAX_POINTS, Constants.MAX_FRONT_POINTS_DEFAULT)));               //maximum front points
			fw.append(String.format(Locale.US, "%d\n", sharedPreferences.getInt(Constants.CONFIG.CONF_NOISE, Constants.NOISE_DISCRIMINATOR_DEFAULT)));                 //noise discriminator
			fw.append(String.format(Locale.US, "%d\n", sharedPreferences.getInt(Constants.CONFIG.CONF_ROUNDED, Constants.ADC_DEFAULT)));                               //ADC bits
			fw.append(String.format(Locale.US, "%d\n", sharedPreferences.getInt(Constants.CONFIG.CONF_SAVE_CHANNELS, Constants.EXPORT_CHANNELS_DEFAULT)));             //save channels
			fw.append(String.format(Locale.US, "%d\n", sharedPreferences.getInt(Constants.CONFIG.CONF_LOAD_CHANNELS, Constants.LOAD_CHANNELS_DEFAULT)));               //load channels
			fw.append(String.format(Locale.US, "%d\n", sharedPreferences.getInt(Constants.CONFIG.CONF_COMPRESSION, Constants.EXPORT_COMPRESSION_DEFAULT)));            //channel compression level
			fw.append(String.format(Locale.US, "%d\n", sharedPreferences.getInt(Constants.SEARCH.PREF_WINDOW_SIZE, Constants.WINDOW_SEARCH_DEFAULT)));                 //windows search size
			fw.append(String.format(Locale.US, "%f\n", sharedPreferences.getFloat(Constants.SEARCH.PREF_THRESHOLD, Constants.THRESHOLD_DEFAULT)));                     //threshold
			fw.append(String.format(Locale.US, "%f\n", sharedPreferences.getFloat(Constants.SEARCH.PREF_TOLERANCE, Constants.TOLERANCE_DEFAULT)));                     //tolerance
			fw.append(String.format(Locale.US, "%d\n", sharedPreferences.getInt(Constants.SEARCH.PREF_ORDER, Constants.ORDER_DEFAULT)));                               //order size
			fw.append(String.format(Locale.US, "%d\n", sharedPreferences.getInt(Constants.CONFIG.CONF_E_TO_MSV_COUNT, AtomSpectraService.ETomSvDefault.length)));      //Calibration array size
			for (int i = 0; i < sharedPreferences.getInt(Constants.CONFIG.CONF_E_TO_MSV_COUNT, AtomSpectraService.ETomSvDefault.length); i++) {
				fw.append(String.format(Locale.US, "%f\n", sharedPreferences.getFloat(Constants.configCalibrationEnergy(i), i * 100.0f)));
			}
			for (int i = 0; i < sharedPreferences.getInt(Constants.CONFIG.CONF_E_TO_MSV_COUNT, AtomSpectraService.ETomSvDefault.length); i++) {
				fw.append(String.format(Locale.US, "%.14e\n", Double.longBitsToDouble(sharedPreferences.getLong(Constants.configCalibration(i), Double.doubleToRawLongBits(AtomSpectraService.ETomSvDefault[i])))));
			}
			fw.close();
			Log.d(TAG, deviceFileName + " saved successfully");
			Toast.makeText(this, getString(R.string.device_save_success, deviceFileName), Toast.LENGTH_SHORT).show();
		} catch (Exception e) {
			Toast.makeText(this, getString(R.string.device_save_error, deviceFileName), Toast.LENGTH_LONG).show();
		}
	}

	//Load device data
	@SuppressLint("ApplySharedPref")
	private void loadDevice(Uri devFile) {
		final String filename = devFile.getPath();
		if (filename == null) {
			Log.d(TAG, "Null filename");
			Toast.makeText(this, getString(R.string.strange_file_name), Toast.LENGTH_LONG).show();
			return;
		}
		Log.d(TAG, filename);

		try {
			InputStream inputFile = getContentResolver().openInputStream(devFile);
			if (inputFile == null) {
				throw new Exception();
			}
			BufferedReader fr = new BufferedReader(new InputStreamReader(inputFile));
			Log.d(TAG, filename + " loading started...");
			String ident = fr.readLine();
			if (!ident.matches("^DEVFORMAT: 1$")) {
				Toast.makeText(this, getString(R.string.device_load_error), Toast.LENGTH_LONG).show();
				return;
			}
			int tempSensG = Constants.MinMax(Integer.parseInt(fr.readLine()), 0, 100000);
			int tempBack = Constants.MinMax(Integer.parseInt(fr.readLine()), 0, 100000);
			int tempFast = Constants.MinMax(Integer.parseInt(fr.readLine()), 10, 100000);
			int tempMedium = Constants.MinMax(Integer.parseInt(fr.readLine()), 10, 100000);
			int tempSlow = Constants.MinMax(Integer.parseInt(fr.readLine()), 10, 100000);
			int tempMin = Constants.MinMax(Integer.parseInt(fr.readLine()), 2, 50);
			int tempMax = Constants.MinMax(Integer.parseInt(fr.readLine()), 2, 50);
			int tempNoise = Constants.MinMax(Integer.parseInt(fr.readLine()), 0, Constants.NUM_HIST_POINTS - 1);
			int tempBits = Constants.MinMax(Integer.parseInt(fr.readLine()), Constants.ADC_MIN, Constants.ADC_MAX);
			int tempSave = Constants.MinMax(Integer.parseInt(fr.readLine()), 1024, Constants.NUM_HIST_POINTS);
			int tempLoad = Constants.MinMax(Integer.parseInt(fr.readLine()), 0, 65536);
			int tempCompression = Constants.MinMax(Integer.parseInt(fr.readLine()), 1, 64);
			int tempWindow = Constants.MinMax(Integer.parseInt(fr.readLine()), 1, 200);                 //windows search size
			float tempThreshold = Float.parseFloat(fr.readLine());
			tempThreshold = (float) Math.rint(Constants.MinMax(tempThreshold * 100, 0, 1000000)) / 100.0f;
			float tempTolerance = Float.parseFloat(fr.readLine());
			tempTolerance = (float) Math.rint(Constants.MinMax(tempTolerance * 100, 1, 5000)) / 100.0f;                     //tolerance
			int tempOrder = Constants.MinMax(Integer.parseInt(fr.readLine()), 2, Constants.ORDER_MAX);
			int tempSize = Integer.parseInt(fr.readLine());
			double[] tempArray;
			float[] tempEArray;
			if (tempSize == AtomSpectraService.ETomSvDefault.length) {
				tempArray = new double[tempSize];
				tempEArray = new float[tempSize];
				for (int i = 0; i < tempSize; i++) {
					tempEArray[i] = Float.parseFloat(fr.readLine());
					if (tempEArray[i] < 0 || tempEArray[i] > 5000) {
						Toast.makeText(this, getString(R.string.device_load_error), Toast.LENGTH_LONG).show();
						return;
					}
					if ((i == 0) && (tempEArray[i] != 0.0)) {
						Toast.makeText(this, getString(R.string.device_load_error), Toast.LENGTH_LONG).show();
						return;
					}
				}
				for (int i = 0; i < tempSize; i++) {
					tempArray[i] = Double.parseDouble(fr.readLine());
					if (tempArray[i] < 0 || tempArray[i] > 100) {
						Toast.makeText(this, getString(R.string.device_load_error), Toast.LENGTH_LONG).show();
						return;
					}
					if ((i == 0) && (tempArray[i] != 0.0)) {
						Toast.makeText(this, getString(R.string.device_load_error), Toast.LENGTH_LONG).show();
						return;
					}
				}
			} else {
				Toast.makeText(this, getString(R.string.device_load_error), Toast.LENGTH_LONG).show();
				return;
			}
			SharedPreferences.Editor editor = sharedPreferences.edit();
			editor.putInt(Constants.CONFIG.CONF_SENSG, tempSensG);                        //Sensitivity
			editor.putInt(Constants.CONFIG.CONF_BACKGROUND, tempBack);                    //Background
			editor.putInt(Constants.CONFIG.CONF_SEARCH_FAST, tempFast);                   //fast counts
			editor.putInt(Constants.CONFIG.CONF_SEARCH_MEDIUM, tempMedium);               //medium counts
			editor.putInt(Constants.CONFIG.CONF_SEARCH_SLOW, tempSlow);                   //slow counts
			editor.putInt(Constants.CONFIG.CONF_MIN_POINTS, tempMin);                     //minimum front points
			editor.putInt(Constants.CONFIG.CONF_MAX_POINTS, tempMax);                     //maximum front points
			editor.putInt(Constants.CONFIG.CONF_NOISE, tempNoise);                        //noise discriminator
			editor.putInt(Constants.CONFIG.CONF_ROUNDED, tempBits);                       //ADC bits
			editor.putInt(Constants.CONFIG.CONF_SAVE_CHANNELS, tempSave);                 //save channels
			editor.putInt(Constants.CONFIG.CONF_LOAD_CHANNELS, tempLoad);                 //load channels
			editor.putInt(Constants.CONFIG.CONF_COMPRESSION, tempCompression);            //channel compression level
			editor.putInt(Constants.SEARCH.PREF_WINDOW_SIZE, tempWindow);                 //windows search size
			editor.putFloat(Constants.SEARCH.PREF_THRESHOLD, tempThreshold);              //threshold
			editor.putFloat(Constants.SEARCH.PREF_TOLERANCE, tempTolerance);              //tolerance
			editor.putInt(Constants.SEARCH.PREF_ORDER, tempOrder);                        //order size
			editor.putInt(Constants.CONFIG.CONF_E_TO_MSV_COUNT, tempSize);                //calibration size
			AtomSpectraService.requestUpdateGraph();
			for (int i = 0; i < tempSize; i++) {
				editor.putFloat(Constants.configCalibrationEnergy(i), tempEArray[i]);
				editor.putLong(Constants.configCalibration(i), Double.doubleToRawLongBits(tempArray[i]));
			}
			editor.commit();
			Toast.makeText(this, getString(R.string.device_load_success), Toast.LENGTH_SHORT).show();
		} catch (Exception e) {
			Toast.makeText(this, getString(R.string.device_load_error), Toast.LENGTH_LONG).show();
		}
	}

	public void onAddCalibrationPoint(View view) {
//		final Button button = (Button) view;
		if (AtomSpectraService.newCalibration.getLines() >= Constants.MAX_CALIBRATION_POINTS) {
			Toast.makeText(this, getString(R.string.cal_no_more), Toast.LENGTH_SHORT).show();
			return;
		}
		if (AtomSpectraService.newCalibration.containsChannel(cursor_x)) {
			Toast.makeText(this, getString(R.string.cal_have_channel), Toast.LENGTH_SHORT).show();
			return;
		}
		if ((cursor_x >= 0) && (cursor_x < Constants.NUM_HIST_POINTS)) {
			final AlertDialog.Builder alert = new AlertDialog.Builder(this);
			alert.setTitle(getString(R.string.cal_point_title, cursor_x));
			alert.setMessage(getString(R.string.ask_point_in_kev));

			final EditText input = new EditText(this);
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
			final Context context = this;
			alert.setPositiveButton(android.R.string.ok, (dialog, whichButton) -> {
				float fValue;// = value.valueOf(value);
				try {
					fValue = Float.parseFloat(input.getText().toString().replaceAll(",", "."));
				} catch (Exception nfe) {
					Toast.makeText(context, getString(R.string.cal_error_number), Toast.LENGTH_SHORT).show();
					return;
				}
				AtomSpectraService.newCalibration.addLine(cursor_x, fValue);
				if (AtomSpectraService.newCalibration.getLines() > 1) {
					AtomSpectraService.newCalibration.Calculate(sharedPreferences.getInt(Constants.CONFIG.CONF_MAX_POLI_FACTOR, Constants.DEFAULT_POLI_FACTOR));
					app_menu.findItem(R.id.action_cal_draw_function).setEnabled(true);
					if (!AtomSpectraService.newCalibration.isCorrect()) {
						Toast.makeText(this, getString(R.string.cal_maybe_wrong, cursor_x, fValue), Toast.LENGTH_LONG).show();
					}
				}
				updateCalibrationMenu();
				showCursorInfo();
			});
			alert.setNegativeButton(android.R.string.cancel, (dialog, whichButton) -> {
			});
			alert.show();
		} else
			Toast.makeText(this, getString(R.string.put_cursor_first), Toast.LENGTH_SHORT).show();
	}

	public void onCalibrateButton(View view) {
		if (AtomSpectraService.newCalibration.getLines() < 2) {
			Toast.makeText(this, getString(R.string.cal_no_enough_data), Toast.LENGTH_LONG).show();
			return;
		}
		AtomSpectraService.newCalibration.Calculate(sharedPreferences.getInt(Constants.CONFIG.CONF_MAX_POLI_FACTOR, Constants.DEFAULT_POLI_FACTOR));
		if (AtomSpectraService.newCalibration.isCorrect()) {
			AtomSpectraService.showCalibrationFunction = false;
			app_menu.findItem(R.id.action_cal_draw_function).setChecked(false);
			app_menu.findItem(R.id.action_cal_draw_function).setEnabled(false);
			AtomSpectraService.ForegroundSpectrum.setSpectrumCalibration(AtomSpectraService.newCalibration);
			AtomSpectraService.newCalibration.clear();
			updateCalibrationMenu();
			Button button = findViewById(R.id.addPointButton);
			button.setText("1");
			button.setEnabled(true);
			Toast.makeText(this, getString(R.string.cal_applied), Toast.LENGTH_SHORT).show();
		} else {
			Toast.makeText(this, getString(R.string.cal_load_error), Toast.LENGTH_LONG).show();
		}
	}

	public void onClearCalibrationButton(View view) {
		AtomSpectraService.showCalibrationFunction = false;
		app_menu.findItem(R.id.action_cal_draw_function).setChecked(false);
		app_menu.findItem(R.id.action_cal_draw_function).setEnabled(false);
		AtomSpectraService.newCalibration.clear();
		updateCalibrationMenu();
	}

	@Override
	public boolean onLongClick(View v) {
		if (show_average_cps == SHOW_COORD) {
			if (AtomSpectraService.ForegroundSpectrum.getGPSDate() != 0) {
				Intent geo = new Intent(Intent.ACTION_VIEW, Uri.parse(String.format(Locale.US, "geo:%f,%f?z=15", AtomSpectraService.ForegroundSpectrum.getLatitude(), AtomSpectraService.ForegroundSpectrum.getLongitude())));
				if (geo.resolveActivity(getPackageManager()) != null) {
					startActivity(geo);
					return true;
				}
			}
		}
		return false;
	}

	@Override
	public void onBackPressed() {}

	@Override
	protected void onSaveInstanceState(@NonNull Bundle outState) {
		super.onSaveInstanceState(outState);
		outState.putInt(ATOM_STATE_AVERAGE, show_average_cps);
		outState.putBoolean(ATOM_STATE_LOG, logScale);
		outState.putBoolean(ATOM_STATE_BAR, barMode);
//		outState.putBoolean(Constants.FREEZE_STATE, AtomSpectraService.freeze_update_data);
		outState.putInt(Constants.SCALE_FACTOR, AtomSpectraService.getScaleFactor() > Constants.SCALE_DOSE_MODE ? AtomSpectraService.getSavedScaleFactor() : AtomSpectraService.getScaleFactor());
//		outState.putInt(ATOM_STATE_INPUT, AtomSpectraService.inputType);
		outState.putFloat(ATOM_STATE_SCALE, zoom_factor);
//		outState.putBoolean(ATOM_STATE_BACKGROUND_SHOW, AtomSpectraService.background_show);
		outState.putBoolean(ATOM_STATE_BACKGROUND_SUBTRACT, background_subtract);
		outState.putInt(ATOM_STATE_CURSOR_X, cursor_x);
		outState.putBoolean(ATOM_STATE_CURSOR_BUTTONS, showPlusMinusButtons);
	}
}

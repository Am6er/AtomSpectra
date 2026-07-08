package org.fe57.atomspectra;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.util.Pair;
import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.security.InvalidParameterException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

//This class helps read and write spectra in different formats
//To use it create a class with appropriate load/save methods.
// To save first add necessary spectra then save them.
// To load first load spectra them extract them from this class
public abstract class SpectrumFile {
    protected final ArrayList<Spectrum> spectrumList = new ArrayList<>();
    protected Spectrum backgroundSpectrum = null;
    protected int Channels = Constants.NUM_HIST_POINTS; // how many channels need to save or load
    protected int channelCompression = 1;
    private static final String TAG = SpectrumFile.class.getSimpleName();

    public final SpectrumFile addSpectrum(@NonNull Spectrum spectrum) {
        spectrumList.add(spectrum);
        return this;
    }

    public final SpectrumFile setBackgroundSpectrum(@NonNull Spectrum spectrum) {
        backgroundSpectrum = spectrum;
        return this;
    }

    public final int spectrumCount() {
        return spectrumList.size();
    }

    public final SpectrumFile setChannels(int channels) {
        Channels = channels;
        return this;
    }

    public final int getChannels() {
        return Channels;
    }

    public final SpectrumFile setChannelCompression(int channelCompression) {
        this.channelCompression = channelCompression;
        return this;
    }

    public final Spectrum getSpectrum(int id) {
        if (id < 0 || id >= spectrumList.size())
            return null;
        return spectrumList.get(id);
    }

    public static Pair<OutputStreamWriter, Uri> prepareOutputFileStream(@NonNull Context context, String fileNamePrefix, long fileNameDate, String fileNameSuffix, @NonNull String extension, @NonNull String mimeType, boolean removeExistingFile) throws FileNotFoundException {
        SharedPreferences sharedPreferences = PrefHelper.getASSharedPreferences(context);
        boolean addPrefixToFileName = sharedPreferences.getBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_ADD_PREFIX, Constants.OUTPUT_FILE_NAME_USE_PREFIX_DEFAULT);
        boolean addDateToFileName = sharedPreferences.getBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_ADD_DATE, Constants.OUTPUT_FILE_NAME_ADD_DATE_DEFAULT);
        boolean addTimeToFileName = sharedPreferences.getBoolean(Constants.CONFIG.CONF_OUTPUT_FILE_NAME_ADD_TIME, Constants.OUTPUT_FILE_NAME_ADD_TIME_DEFAULT);

        return prepareOutputFileStream(context, fileNamePrefix, fileNameDate, fileNameSuffix, extension, mimeType, addPrefixToFileName, addDateToFileName, addTimeToFileName, removeExistingFile);
    }

    public static Pair<OutputStreamWriter, Uri> prepareOutputFileStream(@NonNull Context context, @NonNull String fileNamePrefix, long fileNameDate, String fileNameSuffix, @NonNull String extension, @NonNull String mimeType, boolean addPrefixToFileName, boolean addDateToFileName, boolean addTimeToFileName, boolean removeExistingFile) throws FileNotFoundException {
        SharedPreferences sharedPreferences = PrefHelper.getASSharedPreferences(context);
        String workingDir = sharedPreferences.getString(Constants.CONFIG.CONF_DIRECTORY_SELECTED, null);
        if (workingDir == null) {
            throw new IllegalStateException(context.getString(R.string.error_working_dir_not_set));
        }

        return prepareOutputFileStream(context, workingDir, fileNamePrefix, fileNameDate, fileNameSuffix, extension, mimeType, addPrefixToFileName, addDateToFileName, addTimeToFileName, removeExistingFile);
    }

    public static Pair<OutputStreamWriter, Uri> prepareOutputFileStream(@NonNull Context context, @NonNull String workingDir, @NonNull String fileNamePrefix, long fileNameDate, String fileNameSuffix, @NonNull String extension, @NonNull String mimeType, boolean addPrefixToFileName, boolean addDateToFileName, boolean addTimeToFileName, boolean removeExistingFile) throws FileNotFoundException {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        SimpleDateFormat timeFormat = new SimpleDateFormat("HH-mm-ss", Locale.US);
        // make file name from current date-time
        String fileName;
        boolean addSuffix = (fileNameSuffix != null) && !fileNameSuffix.isEmpty();
        // Without fileNamePrefix, fileNameDate and fileNameSuffix add fileNamePrefix by default
        if (!addPrefixToFileName && !addDateToFileName && !addSuffix) {
            addPrefixToFileName = true;
        }

        fileName = addPrefixToFileName ? fileNamePrefix : "";
        if (addDateToFileName) {
            if (!fileName.isEmpty())
                fileName = fileName + "-";
            if (fileNameDate == 0) {
                fileName = fileName + dateFormat.format(new Date());
            } else {
                fileName = fileName + dateFormat.format(new Date(fileNameDate));
            }
        }
        if (addTimeToFileName) {
            if (addDateToFileName) {
                fileName += "_";
            } else {
                if (!fileName.isEmpty()) {
                    fileName = fileName + "-";
                }
            }
            if (fileNameDate == 0) {
                fileName = fileName + timeFormat.format(new Date());
            } else {
                fileName = fileName + timeFormat.format(new Date(fileNameDate));
            }
        }
        if (addSuffix) {
            if (!fileName.isEmpty()) {
                fileName = fileName + "-";
            }

            fileName += fileNameSuffix;
        }
        fileName = fileName + (mimeType.equals("application/octet-stream") ? extension : "");
        OutputStreamWriter docStream;
        String spectrumFileName;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Uri dir = Uri.parse(workingDir);
            if (dir == null) {
                throw new IllegalStateException(context.getString(R.string.error_working_dir_not_valid, workingDir));
            }
            DocumentFile dirFile = DocumentFile.fromTreeUri(context, dir);
            if ((dirFile == null) || !dirFile.isDirectory()) {
                throw new IllegalStateException(context.getString(R.string.error_working_dir_not_valid, workingDir));
            }
            if (removeExistingFile) {
                DocumentFile file = dirFile.findFile(fileName);
                if ((file != null) && file.isFile()) {
                    if (!file.delete()) {
                        throw new IllegalStateException(context.getString(R.string.error_unable_to_delete_existing_file, fileName));
                    }
                }
            }
            DocumentFile spectrumFile = dirFile.createFile(mimeType, fileName);
            if (spectrumFile == null) {
                throw new IllegalStateException(context.getString(R.string.error_unable_to_create_file, fileName));
            }
            OutputStream out = context.getContentResolver().openOutputStream(spectrumFile.getUri());
            if (out == null) {
                throw new IllegalStateException(context.getString(R.string.error_unable_to_create_file_ex, fileName, "null output stream"));
            }
            try {
                docStream = new OutputStreamWriter(out);
            } catch (Exception e) {
                throw new IllegalStateException(context.getString(R.string.error_unable_to_create_file_ex, fileName, e.getMessage()));
            }
            spectrumFileName = spectrumFile.getUri().getPath();
            spectrumFileName = spectrumFileName == null ? "" : spectrumFileName;
            Log.d(TAG, spectrumFileName);
            return new Pair<>(docStream, spectrumFile.getUri());
        } else {
            final String filename = workingDir + "/" + fileName + extension;
            spectrumFileName = filename;
            try {
                docStream = new OutputStreamWriter(new FileOutputStream(filename));
            } catch (Exception e) {
                throw new IllegalStateException(context.getString(R.string.error_unable_to_create_file_ex, fileName, e.getMessage()));
            }
            Log.d(TAG, spectrumFileName);
        }
        return new Pair<>(docStream, Uri.fromFile(new File(spectrumFileName)));
    }

    protected static String xmlEscape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    //abstract methods

    //load spectrum from external source
    abstract public void loadSpectrum(@NonNull Uri spectrumFilePath, Context context) throws InvalidParameterException, IOException;

    //save spectrum to external source
    abstract public void saveSpectrumAndCloseStream(@NonNull OutputStreamWriter docStream, Context context) throws IOException, PackageManager.NameNotFoundException;


}

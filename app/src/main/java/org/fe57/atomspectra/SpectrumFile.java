package org.fe57.atomspectra;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.util.Pair;
import androidx.documentfile.provider.DocumentFile;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
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
    protected int Channels = Constants.NUM_HIST_POINTS; //how many channels need to save or load
    protected int channelCompression = 1;
    private static final String TAG = SpectrumFile.class.getSimpleName();

    public final SpectrumFile addSpectrum(@NonNull Spectrum spectrum) {
        spectrumList.add(new Spectrum(spectrum));
        return this;
    }

    public final SpectrumFile addBackgroundSpectrum(@NonNull Spectrum spectrum) {
        backgroundSpectrum = new Spectrum(spectrum);
        return this;
    }

    public final SpectrumFile clearSpectra() {
        backgroundSpectrum = null;
        spectrumList.clear();
        Channels = Constants.NUM_HIST_POINTS;
        channelCompression = 1;
        return this;
    }

    public final boolean hasBackground() {
        return backgroundSpectrum != null;
    }

    public final int spectrumNumber() {
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

    public final int getChannelCompression() {
        return channelCompression;
    }

    public final Spectrum getSpectrum(int id) {
        if (id < 0 || id >= spectrumList.size())
            return null;
        return new Spectrum(spectrumList.get(id));
    }

    public final Spectrum getBackgroundSpectrum() {
        if (backgroundSpectrum == null)
            return null;
        return new Spectrum(backgroundSpectrum);
    }

    public static Pair<OutputStreamWriter, Uri> prepareOutputStream(@NonNull Context context, String folder, long date, @NonNull String prefix, boolean addPrefix, String suffix, @NonNull String extension, @NonNull String mimeType, boolean addDate, boolean addTime, boolean removeFirst) {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        SimpleDateFormat timeFormat = new SimpleDateFormat("HH-mm-ss", Locale.US);
        // make file name from current date-time
        String fileName;
        boolean addSuffix = (suffix != null) && !suffix.isEmpty();
        //Without prefix, date and suffix add prefix by default
        if (!addPrefix && !addDate && !addSuffix)
            addPrefix = true;

        fileName = addPrefix ? prefix : "";
        if (addDate) {
            if (!fileName.isEmpty())
                fileName = fileName + "-";
            if (date == 0) {
                fileName = fileName + dateFormat.format(new Date());
            } else {
                fileName = fileName + dateFormat.format(new Date(date));
            }
        }
        if (addTime) {
            if (addDate) {
                fileName += "_";
            } else {
                if (!fileName.isEmpty())
                    fileName = fileName + "-";
            }
            if (date == 0) {
                fileName = fileName + timeFormat.format(new Date());
            } else {
                fileName = fileName + timeFormat.format(new Date(date));
            }
        }
        if (addSuffix) {
            if (!fileName.isEmpty())
                fileName = fileName + "-";
            fileName += suffix;
        }
        fileName = fileName + (mimeType.equals("application/octet-stream") ? extension : "");
        OutputStreamWriter docStream;
        String spectrumFileName;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (folder == null) {
                return null;
            }
            Uri dir = Uri.parse(folder);
            if (dir == null) {
                return null;
            }
            DocumentFile dirFile = DocumentFile.fromTreeUri(context, dir);
            if ((dirFile == null) || !dirFile.isDirectory()) {
                return null;
            }
            if (removeFirst) {
                DocumentFile file = dirFile.findFile(fileName);
                if ((file != null) && file.isFile()) {
                    if (!file.delete()) {
                        return null;
                    }
                }
            }
            DocumentFile spectrumFile = dirFile.createFile(mimeType, fileName);
            if (spectrumFile == null) {
                return null;
            }
            try {
                docStream = new OutputStreamWriter(context.getContentResolver().openOutputStream(spectrumFile.getUri()));
            } catch (Exception e) {
                return null;
            }
            spectrumFileName = spectrumFile.getUri().getPath();
            spectrumFileName = spectrumFileName == null ? "" : spectrumFileName;
            Log.d(TAG, spectrumFileName);
            return new Pair<>(docStream, spectrumFile.getUri());
        } else {
            if (folder == null) {
                return null;
            }
            final String filename = folder + "/" + fileName + extension;
            spectrumFileName = filename;
            try {
                docStream = new OutputStreamWriter(new FileOutputStream(filename));//new FileOutputStream(filename);
            } catch (Exception e) {
                return null;
            }
            Log.d(TAG, spectrumFileName);
        }
        return new Pair<>(docStream, Uri.fromFile(new File(spectrumFileName)));
    }

    //abstract methods

    //load spectrum from external source
    abstract public boolean loadSpectrum(@NonNull InputStream histFile, Context context);

    //save spectrum to external source
    abstract public boolean saveSpectrum(@NonNull OutputStreamWriter docStream, Context context);

    //save incremental spectrum to external source
    abstract public boolean saveIncrementalSpectrum(@NonNull OutputStreamWriter docStream, Context context);
}

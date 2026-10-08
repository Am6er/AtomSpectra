package org.fe57.atomspectra;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

public class AtomSpectraApplication extends Application implements Application.ActivityLifecycleCallbacks {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<Activity> externalUi = Collections.newSetFromMap(new IdentityHashMap<>());
    private int startedActivities;
    private boolean foreground;
    private final Runnable backgroundCheck = () -> {
        if (startedActivities == 0 && externalUi.isEmpty() && foreground) {
            foreground = false;
            AtomSpectraLog.action(this, "App moved to background");
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(this);
        AtomSpectraLog.event(this, "App started: " + BuildConfig.VERSION_NAME);
    }

    static void externalUiStarted(Activity activity) {
        Application application = activity.getApplication();
        if (application instanceof AtomSpectraApplication) {
            ((AtomSpectraApplication) application).externalUi.add(activity);
        }
    }

    static void externalUiFinished(Activity activity) {
        Application application = activity.getApplication();
        if (application instanceof AtomSpectraApplication) {
            ((AtomSpectraApplication) application).externalUi.remove(activity);
        }
    }

    @Override
    public void onActivityStarted(Activity activity) {
        startedActivities++;
        handler.removeCallbacks(backgroundCheck);
        if (!foreground) {
            foreground = true;
            AtomSpectraLog.action(this, "App moved to foreground");
        }
    }

    @Override
    public void onActivityStopped(Activity activity) {
        startedActivities--;
        if (startedActivities == 0 && !activity.isChangingConfigurations()) {
            handler.postDelayed(backgroundCheck, 700);
        }
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
        externalUi.remove(activity);
    }

    @Override public void onActivityCreated(Activity activity, Bundle state) { }
    @Override public void onActivityResumed(Activity activity) { }
    @Override public void onActivityPaused(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
}
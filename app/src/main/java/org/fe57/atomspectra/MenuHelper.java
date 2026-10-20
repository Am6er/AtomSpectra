package org.fe57.atomspectra;

import android.view.Menu;

import androidx.annotation.NonNull;

import java.lang.reflect.Method;

public class MenuHelper {
    // the platform overflow list hides icons of top-level items; the hidden setter turns them on and is silently skipped if blocked
    public static void showOverflowIcons(@NonNull Menu menu) {
        if (!"MenuBuilder".equals(menu.getClass().getSimpleName())) return;
        try {
            Method setter = menu.getClass().getDeclaredMethod("setOptionalIconsVisible", boolean.class);
            setter.setAccessible(true);
            setter.invoke(menu, true);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
    }
}

package org.fe57.atomspectra;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.IntentSanitizer;

/**
 * Created by S. Epiphanov.
 * This class is used to instantiate a main activity class appropriately.
 */
public class AtomSpectraStart extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
//        Intent notificationIntent = getIntent();
        Intent notificationIntent = new IntentSanitizer.Builder()
                .allowType("text/plain")
                .build()
                .sanitizeByFiltering(getIntent());
        notificationIntent.setClass(this, AtomSpectra.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(notificationIntent);
        finish();
    }
}
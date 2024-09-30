package org.fe57.atomspectra.ui.Configuration;

import androidx.lifecycle.ViewModelProvider;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import org.fe57.atomspectra.R;
import org.fe57.atomspectra.databinding.FragmentConfigurationBinding;
import org.fe57.atomspectra.databinding.FragmentDoseBinding;
import org.fe57.atomspectra.ui.Dose.DoseViewModel;

/**
 * Created by S. Epiphanov.
 * This class is used to configure the program appropriately.
 */
public class ConfigurationFragment extends Fragment {

    private ConfigurationViewModel mViewModel;
    private FragmentConfigurationBinding binding;

    public static ConfigurationFragment newInstance() {
        return new ConfigurationFragment();
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
//        return inflater.inflate(R.layout.fragment_configuration, container, false);
        mViewModel = new ViewModelProvider(this).get(ConfigurationViewModel.class);
        binding = FragmentConfigurationBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

}
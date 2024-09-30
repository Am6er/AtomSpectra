package org.fe57.atomspectra.ui.Dose;

import androidx.lifecycle.ViewModelProvider;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import org.fe57.atomspectra.R;
import org.fe57.atomspectra.databinding.FragmentDoseBinding;

/**
 * Created by S. Epiphanov.
 * This class is used to give the dose rate ability to be shown.
 */
public class DoseFragment extends Fragment {

    private DoseViewModel mViewModel;
    private FragmentDoseBinding binding;

    public static DoseFragment newInstance() {
        return new DoseFragment();
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        mViewModel = new ViewModelProvider(this).get(DoseViewModel.class);
//        View view = inflater.inflate(R.layout.fragment_dose, container, false);
        binding = FragmentDoseBinding.inflate(inflater, container, false);
        View view = binding.getRoot();
        return view;
    }

}
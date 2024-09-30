package org.fe57.atomspectra.ui.Isotope;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import org.fe57.atomspectra.R;
import org.fe57.atomspectra.databinding.FragmentFunctionBinding;
import org.fe57.atomspectra.databinding.FragmentIsotopeBinding;
import org.fe57.atomspectra.ui.Function.FunctionViewModel;

/**
 * Created by S. Epiphanov.
 * This class is used to select and update isotopes and isotope lists.
 */
public class IsotopeFragment extends Fragment {

    private IsotopeViewModel mViewModel;
    private FragmentIsotopeBinding binding;

    public static IsotopeFragment newInstance() {
        return new IsotopeFragment();
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
//        return inflater.inflate(R.layout.fragment_isotope, container, false);
        mViewModel = new ViewModelProvider(this).get(IsotopeViewModel.class);
        binding = FragmentIsotopeBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

}
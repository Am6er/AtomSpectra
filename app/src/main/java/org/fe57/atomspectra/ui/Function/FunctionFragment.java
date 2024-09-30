package org.fe57.atomspectra.ui.Function;

import androidx.lifecycle.ViewModelProvider;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import org.fe57.atomspectra.R;
import org.fe57.atomspectra.databinding.FragmentFunctionBinding;

/**
 * Created by S. Epiphanov.
 * This class is used to show spectrum configuration function.
 */
public class FunctionFragment extends Fragment {

    private FunctionViewModel mViewModel;
    private FragmentFunctionBinding binding;

    public static FunctionFragment newInstance() {
        return new FunctionFragment();
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        mViewModel = new ViewModelProvider(this).get(FunctionViewModel.class);
        binding = FragmentFunctionBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

}
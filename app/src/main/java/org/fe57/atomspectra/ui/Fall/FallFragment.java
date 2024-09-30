package org.fe57.atomspectra.ui.Fall;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import org.fe57.atomspectra.R;
import org.fe57.atomspectra.databinding.FragmentDoseBinding;
import org.fe57.atomspectra.databinding.FragmentFallBinding;
import org.fe57.atomspectra.ui.Dose.DoseViewModel;

/**
 * Created by S. Epiphanov.
 * This class is used to show data in a fall.
 */
public class FallFragment extends Fragment {

    private FallViewModel mViewModel;
    private FragmentFallBinding binding;

    public static FallFragment newInstance() {
        return new FallFragment();
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
//        return inflater.inflate(R.layout.fragment_fall, container, false);
        mViewModel = new ViewModelProvider(this).get(FallViewModel.class);
        binding = FragmentFallBinding.inflate(inflater, container, false);
        View view = binding.getRoot();
        return view;
    }

}
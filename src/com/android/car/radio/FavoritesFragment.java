/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.car.radio;

import android.hardware.radio.ProgramSelector;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.car.broadcastradio.support.Program;
import com.android.car.radio.util.Log;

import java.util.List;

/**
 * Favorites screen: a grid of saved stations. Tapping a card tunes it; the star removes it.
 */
public class FavoritesFragment extends Fragment {
    private static final String TAG = "BcRadioApp.FavFrg";
    private static final int COLUMNS = 4;

    private RadioController mController;
    private FavoritesAdapter mAdapter;
    private RecyclerView mList;
    private View mEmpty;

    static FavoritesFragment newInstance(RadioController controller) {
        FavoritesFragment f = new FavoritesFragment();
        f.mController = controller;
        return f;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle saved) {
        return inflater.inflate(R.layout.favorites_fragment, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, Bundle saved) {
        mList = v.findViewById(R.id.favorites_list);
        mEmpty = v.findViewById(R.id.favorites_empty);

        mAdapter = new FavoritesAdapter(this::onClick, this::onRemove);
        mList.setLayoutManager(new GridLayoutManager(getContext(), COLUMNS));
        mList.setAdapter(mAdapter);

        mController.getFavorites().observe(getViewLifecycleOwner(), favs -> {
            List<Program> list = favs;
            mAdapter.setFavorites(list);
            boolean empty = list == null || list.isEmpty();
            mEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
            mList.setVisibility(empty ? View.GONE : View.VISIBLE);
        });
        mController.getCurrentProgram().observe(getViewLifecycleOwner(),
                info -> mAdapter.setCurrent(info == null ? null : info.getSelector()));

        try {
            mController.setSkipMode(SkipMode.FAVORITES);
        } catch (IllegalStateException e) {
            Log.e(TAG, "Can't set skip mode", e);
        }
    }

    private void onClick(@NonNull ProgramSelector sel) {
        mController.tune(sel);
        if (getActivity() instanceof RadioActivity) {
            ((RadioActivity) getActivity()).showNow();
        }
    }

    private void onRemove(@NonNull Program program) {
        mController.setFavorite(program, false);
    }
}

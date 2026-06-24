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

import android.hardware.radio.RadioManager.ProgramInfo;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.car.radio.bands.ProgramType;
import com.android.car.radio.util.Log;
import com.android.car.radio.widget.FmDialView;
import com.android.car.radio.widget.SignalMeter;

import java.util.ArrayList;
import java.util.List;

/**
 * Browse screen: the live program list rendered as DAB service rows (under the current ensemble
 * header) or FM station rows (under the tuning dial), depending on the active band.
 */
public class BrowseFragment extends Fragment {
    private static final String TAG = "BcRadioApp.BrwFrg";

    private RadioController mController;
    private StationAdapter mAdapter;

    private TextView mSubtitle;
    private View mFmSection;
    private TextView mFreq;
    private FmDialView mDial;
    private View mDabHeader;
    private TextView mEnsemble;
    private SignalMeter mEnsSignal;
    private View mScan;
    private TextView mScanLabel;

    private List<Station> mStations = new ArrayList<>();
    @Nullable private ProgramInfo mCurrent;

    static BrowseFragment newInstance(RadioController controller) {
        BrowseFragment f = new BrowseFragment();
        f.mController = controller;
        return f;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle saved) {
        return inflater.inflate(R.layout.browse_fragment, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, Bundle saved) {
        mSubtitle = v.findViewById(R.id.browse_subtitle);
        mFmSection = v.findViewById(R.id.browse_fm_section);
        mFreq = v.findViewById(R.id.browse_freq);
        mDial = v.findViewById(R.id.browse_dial);
        mDabHeader = v.findViewById(R.id.browse_dab_header);
        mEnsemble = v.findViewById(R.id.browse_ensemble);
        mEnsSignal = v.findViewById(R.id.browse_ens_signal);
        mScan = v.findViewById(R.id.browse_scan);
        mScanLabel = v.findViewById(R.id.browse_scan_label);

        mAdapter = new StationAdapter(this::onStationClicked, mController::setFavorite);
        RecyclerView list = v.findViewById(R.id.browse_list);
        list.setLayoutManager(new LinearLayoutManager(getContext()));
        list.setAdapter(mAdapter);

        mScan.setOnClickListener(x -> mController.seek(true));

        mController.getStations().observe(getViewLifecycleOwner(), stations -> {
            mStations = stations != null ? stations : new ArrayList<>();
            mAdapter.setStations(mStations);
            refresh();
        });
        mController.getCurrentProgram().observe(getViewLifecycleOwner(), info -> {
            mCurrent = info;
            mAdapter.setCurrent(info == null ? null : info.getSelector());
            refresh();
        });
        mController.getFavorites().observe(getViewLifecycleOwner(), favs -> {
            if (favs != null) mAdapter.setFavorites(favs);
        });
        mController.getPlaybackState().observe(getViewLifecycleOwner(), state -> {
            boolean seeking = state != null
                    && (state == android.support.v4.media.session.PlaybackStateCompat.STATE_CONNECTING
                        || state == android.support.v4.media.session.PlaybackStateCompat
                                .STATE_SKIPPING_TO_NEXT
                        || state == android.support.v4.media.session.PlaybackStateCompat
                                .STATE_SKIPPING_TO_PREVIOUS);
            mScanLabel.setText(seeking ? R.string.action_scanning : R.string.action_scan_band);
        });

        try {
            mController.setSkipMode(SkipMode.BROWSE);
        } catch (IllegalStateException e) {
            Log.e(TAG, "Can't set skip mode", e);
        }
    }

    private void onStationClicked(@NonNull android.hardware.radio.ProgramSelector sel) {
        mController.tune(sel);
        if (getActivity() instanceof RadioActivity) {
            ((RadioActivity) getActivity()).showNow();
        }
    }

    private ProgramType band() {
        if (getActivity() instanceof RadioActivity) {
            ProgramType pt = ((RadioActivity) getActivity()).getCurrentBand();
            if (pt != null) return pt;
        }
        return mCurrent != null ? ProgramType.fromSelector(mCurrent.getSelector()) : ProgramType.DAB;
    }

    private void refresh() {
        ProgramType pt = band();
        boolean isFm = pt == ProgramType.FM;
        int accent = UiUtils.accentFor(pt);
        mAdapter.setShowFrequency(isFm);
        mAdapter.setAccent(accent);

        mFmSection.setVisibility(isFm ? View.VISIBLE : View.GONE);
        mDabHeader.setVisibility(isFm ? View.GONE : View.VISIBLE);

        if (isFm) {
            mSubtitle.setText(getString(R.string.browse_subtitle_fm));
            float needle = 98f;
            if (mCurrent != null) {
                String f = UiUtils.frequencyShort(mCurrent.getSelector());
                if (!f.isEmpty()) {
                    try {
                        needle = Float.parseFloat(f);
                    } catch (NumberFormatException ignore) { }
                }
            }
            mFreq.setText(String.format(java.util.Locale.US, "%.1f", needle));
            List<float[]> marks = new ArrayList<>();
            List<Integer> colors = new ArrayList<>();
            for (Station p : mStations) {
                String f = UiUtils.frequencyShort(p.selector);
                if (f.isEmpty()) continue;
                try {
                    marks.add(new float[]{Float.parseFloat(f)});
                    colors.add(UiUtils.logoColor(p.name));
                } catch (NumberFormatException ignore) { }
            }
            mDial.setMarkers(marks, colors);
            mDial.setCurrent(needle, accent);
        } else {
            int n = mStations.size();
            mSubtitle.setText(n + " " + getString(R.string.browse_subtitle_dab));
            String ens = mCurrent != null ? UiUtils.subtitle(mCurrent) : "DAB+";
            mEnsemble.setText(ens);
            mEnsSignal.setLevel(UiUtils.signalLevel(mCurrent), accent);
        }
    }
}

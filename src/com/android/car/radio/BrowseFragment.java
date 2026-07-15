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

import android.app.AlertDialog;
import android.content.Context;
import android.hardware.radio.RadioManager.ProgramInfo;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
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
    private View mStepDown;
    private View mStepUp;
    private View mKeypadBtn;
    private float mFmFreqMhz = 98f;
    // Window after a manual FM action during which refresh() must not overwrite the readout
    // with the (briefly stale) current-program echo.
    private static final long FM_MANUAL_HOLD_MS = 2500;
    private long mFmManualMs;

    // Full union list from the controller (both bands) and the band-filtered subset shown.
    private List<Station> mAllStations = new ArrayList<>();
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

        mScanLabel.setText(R.string.action_scan);
        mScan.setOnClickListener(x -> {
            if (getActivity() instanceof RadioActivity) {
                ((RadioActivity) getActivity()).showScanWizard();
            }
        });
        UiUtils.addRipple(mScan, UiUtils.ACCENT_DAB, dp(14));

        // FM manual tuning: draggable dial + ∓ step buttons + a numpad.
        mStepDown = v.findViewById(R.id.fm_step_down);
        mStepUp = v.findViewById(R.id.fm_step_up);
        mKeypadBtn = v.findViewById(R.id.fm_keypad);
        mStepDown.setOnClickListener(x -> tuneFm(snapFm(mFmFreqMhz - 0.1f)));
        mStepUp.setOnClickListener(x -> tuneFm(snapFm(mFmFreqMhz + 0.1f)));
        mKeypadBtn.setOnClickListener(x -> showKeypad());
        UiUtils.addRippleOval(mStepDown, UiUtils.ACCENT_FM);
        UiUtils.addRippleOval(mStepUp, UiUtils.ACCENT_FM);
        UiUtils.addRipple(mKeypadBtn, UiUtils.ACCENT_FM, dp(14));
        mDial.setOnTuneListener(new FmDialView.OnTuneListener() {
            @Override public void onScrub(float f) {
                mFmFreqMhz = f;
                mFreq.setText(fmtFm(f));
                mDial.setCurrent(f, UiUtils.ACCENT_FM);
            }
            @Override public void onCommit(float f) {
                tuneFm(f);
            }
        });

        mController.getStations().observe(getViewLifecycleOwner(), stations -> {
            mAllStations = stations != null ? stations : new ArrayList<>();
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

        // The cache holds both bands; show only the stations of the active band.
        mStations = new ArrayList<>();
        for (Station s : mAllStations) {
            if (ProgramType.fromSelector(s.selector) == pt) mStations.add(s);
        }
        mAdapter.setStations(mStations);

        mAdapter.setShowFrequency(isFm);
        mAdapter.setAccent(accent);

        mFmSection.setVisibility(isFm ? View.VISIBLE : View.GONE);
        mDabHeader.setVisibility(isFm ? View.GONE : View.VISIBLE);

        if (isFm) {
            mSubtitle.setText(getString(R.string.browse_subtitle_fm));
            // Keep the last manual value as the baseline (never silently snap back to a default);
            // sync to the current program only outside the manual-hold window and only when its
            // frequency actually parses.
            float needle = mFmFreqMhz;
            boolean manualHold = System.currentTimeMillis() - mFmManualMs < FM_MANUAL_HOLD_MS;
            if (!manualHold && mCurrent != null) {
                String f = UiUtils.frequencyShort(mCurrent.getSelector());
                if (!f.isEmpty()) {
                    try {
                        needle = Float.parseFloat(f);
                    } catch (NumberFormatException ignore) { }
                }
            }
            mFmFreqMhz = needle;
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

    // ---- FM manual tuning --------------------------------------------------------------------

    private void tuneFm(float mhz) {
        mFmFreqMhz = mhz;
        mFmManualMs = System.currentTimeMillis();
        mFreq.setText(fmtFm(mhz));
        mDial.setCurrent(mhz, UiUtils.ACCENT_FM);
        mController.tuneFmFrequency(Math.round(mhz * 1000f));
    }

    private static float snapFm(float mhz) {
        float v = Math.round(mhz * 10f) / 10f;
        if (v < 88.0f) v = 88.0f;
        if (v > 108.0f) v = 108.0f;
        return v;
    }

    private static String fmtFm(float mhz) {
        return String.format(java.util.Locale.US, "%.1f", mhz);
    }

    private float dp(float d) {
        return d * getResources().getDisplayMetrics().density;
    }

    /** Numeric keypad to enter an exact FM frequency (hidden behind the 123 button). */
    private void showKeypad() {
        final Context ctx = getContext();
        if (ctx == null) return;
        View root = LayoutInflater.from(ctx).inflate(R.layout.fm_keypad, null);
        final TextView display = root.findViewById(R.id.keypad_display);
        GridLayout grid = root.findViewById(R.id.keypad_grid);
        final StringBuilder sb = new StringBuilder();

        final String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9",
                ".", "0", getString(R.string.fm_keypad_del)};
        int cell = Math.round(dp(108));
        int margin = Math.round(dp(5));
        for (final String k : keys) {
            TextView b = new TextView(ctx);
            b.setText(k);
            b.setGravity(android.view.Gravity.CENTER);
            b.setTextColor(0xFFFFFFFF);
            b.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(30));
            b.setBackground(UiUtils.roundedRect(0xFF1A1D21, dp(10), dp(1), 0xFF2A2F36));
            UiUtils.addRipple(b, UiUtils.ACCENT_FM, dp(10));
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = cell;
            lp.height = cell;
            lp.setMargins(margin, margin, margin, margin);
            b.setLayoutParams(lp);
            b.setOnClickListener(x -> {
                if (k.equals(getString(R.string.fm_keypad_del))) {
                    if (sb.length() > 0) sb.deleteCharAt(sb.length() - 1);
                } else if (k.equals(".")) {
                    if (sb.indexOf(".") < 0 && sb.length() > 0) sb.append('.');
                } else {
                    sb.append(k);
                }
                display.setText(sb.length() == 0 ? "—" : sb.toString());
            });
            grid.addView(b);
        }

        new AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setView(root)
                .setPositiveButton(R.string.fm_keypad_tune, (d, w) -> {
                    try {
                        tuneFm(snapFm(Float.parseFloat(sb.toString())));
                    } catch (NumberFormatException ignore) { }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}

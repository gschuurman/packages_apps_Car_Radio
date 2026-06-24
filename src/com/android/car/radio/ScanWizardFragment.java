/*
 * Copyright (C) 2026 The LineageOS Project
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

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.android.car.radio.widget.EqualizerView;

/**
 * Full-screen wizard overlay driving a full-band ({@link ScanController}) scan. Renders three
 * states off the single {@link RadioController#getScanState()} LiveData: an intro screen with a
 * Start button, a live-progress screen (animated indicator + FM/DAB found counts) with a Stop
 * button, and a summary screen with a Done button. Hosted by {@link RadioActivity}.
 */
public class ScanWizardFragment extends Fragment {

    private RadioController mController;

    private TextView mHeading;
    private EqualizerView mEq;
    private TextView mBody;
    private TextView mSub;
    private TextView mCounts;
    private TextView mPrimary;
    private TextView mSecondary;

    static ScanWizardFragment newInstance(RadioController controller) {
        ScanWizardFragment f = new ScanWizardFragment();
        f.mController = controller;
        return f;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle saved) {
        return inflater.inflate(R.layout.scan_wizard, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, Bundle saved) {
        mHeading = v.findViewById(R.id.scan_heading);
        mEq = v.findViewById(R.id.scan_eq);
        mBody = v.findViewById(R.id.scan_body);
        mSub = v.findViewById(R.id.scan_sub);
        mCounts = v.findViewById(R.id.scan_counts);
        mPrimary = v.findViewById(R.id.scan_btn_primary);
        mSecondary = v.findViewById(R.id.scan_btn_secondary);

        float r = dp(12);
        mPrimary.setBackground(UiUtils.roundedRect(UiUtils.ACCENT_DAB, r, 0, 0));
        mSecondary.setBackground(UiUtils.roundedRect(0xFF1B1E22, r, dp(1), 0xFF262A30));

        // Reopening after a previous run: snap back to the intro (no-op if a scan is running).
        mController.resetScan();
        mController.getScanState().observe(getViewLifecycleOwner(), this::render);
    }

    private void render(ScanState s) {
        if (s == null) return;
        switch (s.phase) {
            case INTRO:
                mHeading.setText(R.string.scan_title);
                mEq.setPlaying(false);
                mEq.setVisibility(View.GONE);
                mBody.setText(R.string.scan_intro);
                mSub.setVisibility(View.VISIBLE);
                mSub.setText(R.string.scan_intro_warning);
                mCounts.setVisibility(View.GONE);
                mPrimary.setVisibility(View.VISIBLE);
                mPrimary.setText(R.string.scan_start);
                mPrimary.setOnClickListener(v -> mController.startScan());
                mSecondary.setVisibility(View.VISIBLE);
                mSecondary.setText(R.string.scan_cancel);
                mSecondary.setOnClickListener(v -> dismiss());
                break;
            case FM:
            case DAB:
                boolean fm = s.phase == ScanState.Phase.FM;
                int accent = fm ? UiUtils.ACCENT_FM : UiUtils.ACCENT_DAB;
                mHeading.setText(R.string.scan_title);
                mEq.setVisibility(View.VISIBLE);
                mEq.setAccent(accent);
                mEq.setPlaying(true);
                mBody.setText(fm ? R.string.scan_phase_fm : R.string.scan_phase_dab);
                mSub.setVisibility(View.VISIBLE);
                mSub.setText(s.label == null ? "" : s.label);
                mCounts.setVisibility(View.VISIBLE);
                mCounts.setText(getString(R.string.scan_counts, s.fmCount, s.dabCount));
                mCounts.setTextColor(accent);
                mPrimary.setVisibility(View.GONE);
                mSecondary.setVisibility(View.VISIBLE);
                mSecondary.setText(R.string.action_stop);
                mSecondary.setOnClickListener(v -> mController.cancelScan());
                break;
            case DONE:
                mHeading.setText(R.string.scan_done_title);
                mEq.setPlaying(false);
                mEq.setVisibility(View.GONE);
                mBody.setText(getString(R.string.scan_done_summary, s.fmCount, s.dabCount));
                mSub.setVisibility(View.GONE);
                mCounts.setVisibility(View.VISIBLE);
                mCounts.setText(getString(R.string.scan_counts, s.fmCount, s.dabCount));
                mCounts.setTextColor(UiUtils.ACCENT_DAB);
                mPrimary.setVisibility(View.VISIBLE);
                mPrimary.setText(R.string.scan_done);
                mPrimary.setOnClickListener(v -> {
                    dismiss();
                    if (getActivity() instanceof RadioActivity) {
                        ((RadioActivity) getActivity()).showBrowse();
                    }
                });
                mSecondary.setVisibility(View.GONE);
                break;
            case CANCELED:
            default:
                dismiss();
                break;
        }
    }

    private void dismiss() {
        if (getActivity() instanceof RadioActivity) {
            ((RadioActivity) getActivity()).hideScanWizard();
        }
    }

    private float dp(float d) {
        return d * getResources().getDisplayMetrics().density;
    }
}

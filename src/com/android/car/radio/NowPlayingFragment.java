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

import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.hardware.radio.ProgramSelector;
import android.hardware.radio.RadioManager.ProgramInfo;
import android.os.Bundle;
import android.support.v4.media.session.PlaybackStateCompat;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.android.car.broadcastradio.support.Program;
import com.android.car.radio.bands.ProgramType;
import com.android.car.radio.widget.EqualizerView;
import com.android.car.radio.widget.SignalMeter;

import java.util.List;

/**
 * The Now Playing screen: large station art/logo, presets, station metadata, the DLS card and
 * the primary transport controls. Bound entirely to {@link RadioController} LiveData.
 */
public class NowPlayingFragment extends Fragment {

    private static final int[] PRESET_IDS = {
        R.id.preset_0, R.id.preset_1, R.id.preset_2,
        R.id.preset_3, R.id.preset_4, R.id.preset_5,
    };

    private RadioController mController;

    private View mArtTile;
    private ImageView mArt;
    private TextView mLogo;
    private TextView mBand;
    private TextView mSub;
    private SignalMeter mSignal;
    private TextView mName;
    private TextView mGenre;
    private TextView mDls;
    private EqualizerView mEq;
    private ImageView mPrev;
    private ImageView mPlay;
    private ImageView mNext;
    private ImageView mFav;
    private View mScan;
    private TextView mScanLabel;
    private ImageView mScanIcon;

    @Nullable private ProgramInfo mCurrent;
    private List<Program> mFavorites;
    private boolean mPlaying = true;
    private boolean mSeeking = false;

    static NowPlayingFragment newInstance(RadioController controller) {
        NowPlayingFragment f = new NowPlayingFragment();
        f.mController = controller;
        return f;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle saved) {
        return inflater.inflate(R.layout.now_playing, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, Bundle saved) {
        mArtTile = v.findViewById(R.id.now_art_tile);
        mArt = v.findViewById(R.id.now_art);
        mLogo = v.findViewById(R.id.now_logo);
        mBand = v.findViewById(R.id.now_band);
        mSub = v.findViewById(R.id.now_sub);
        mSignal = v.findViewById(R.id.now_signal);
        mName = v.findViewById(R.id.now_name);
        mGenre = v.findViewById(R.id.now_genre);
        mDls = v.findViewById(R.id.now_dls);
        mEq = v.findViewById(R.id.now_eq);
        mPrev = v.findViewById(R.id.now_prev);
        mPlay = v.findViewById(R.id.now_play);
        mNext = v.findViewById(R.id.now_next);
        mFav = v.findViewById(R.id.now_fav);
        mScan = v.findViewById(R.id.now_scan);
        mScanLabel = v.findViewById(R.id.now_scan_label);
        mScanIcon = v.findViewById(R.id.now_scan_icon);

        mPrev.setOnClickListener(x -> mController.skip(false));
        mNext.setOnClickListener(x -> mController.skip(true));
        mPlay.setOnClickListener(x -> mController.setMuted(mPlaying));
        mFav.setOnClickListener(x -> mController.toggleCurrentFavorite());
        mScan.setOnClickListener(x -> mController.seek(true));

        mController.getCurrentProgram().observe(getViewLifecycleOwner(), info -> {
            mCurrent = info;
            bind();
        });
        mController.getPlaybackState().observe(getViewLifecycleOwner(), state -> {
            mPlaying = state == null || state != PlaybackStateCompat.STATE_PAUSED
                    && state != PlaybackStateCompat.STATE_STOPPED;
            mSeeking = state != null
                    && (state == PlaybackStateCompat.STATE_CONNECTING
                        || state == PlaybackStateCompat.STATE_SKIPPING_TO_NEXT
                        || state == PlaybackStateCompat.STATE_SKIPPING_TO_PREVIOUS);
            bindPlay();
            bindScan();
        });
        mController.getStationArt().observe(getViewLifecycleOwner(), this::bindArt);
        mController.getFavorites().observe(getViewLifecycleOwner(), favs -> {
            mFavorites = favs;
            bindPresets();
        });
    }

    private int accent() {
        return UiUtils.accentFor(mCurrent);
    }

    private void bind() {
        int accent = accent();
        if (mCurrent == null) {
            mName.setText("");
            mSub.setText("");
            mGenre.setText("");
            mDls.setText("");
            mLogo.setText("");
            mSignal.setLevel(0, accent);
        } else {
            String name = UiUtils.stationName(mCurrent);
            mName.setText(name);
            mSub.setText(UiUtils.subtitle(mCurrent));
            String dls = UiUtils.dls(mCurrent);
            mDls.setText(TextUtils.isEmpty(dls) ? UiUtils.subtitle(mCurrent) : dls);
            mGenre.setText("");
            mLogo.setText(UiUtils.monogram(name));
            mArtTile.setBackground(UiUtils.gradientTile(UiUtils.logoColor(name), dp(26)));
            mSignal.setLevel(UiUtils.signalLevel(mCurrent), accent);
        }

        ProgramType pt = mCurrent == null ? null : ProgramType.fromSelector(mCurrent.getSelector());
        mBand.setText(pt == ProgramType.FM ? R.string.band_fm : R.string.band_dab);
        mBand.setTextColor(accent);
        mBand.setBackground(UiUtils.roundedRect(UiUtils.alpha(accent, 0.18f), dp(8), 0, 0));
        mEq.setAccent(accent);

        boolean fav = mCurrent != null && mController.isFavorite(mCurrent.getSelector());
        mFav.setImageResource(fav ? R.drawable.ic_star_filled : R.drawable.ic_star_empty);
        mFav.setColorFilter(fav ? accent : 0xFF8A9099);

        bindPlay();
        bindScan();
        bindPresets();
    }

    private void bindPlay() {
        int accent = accent();
        mPlay.setBackground(UiUtils.oval(accent));
        mPlay.setImageResource(mPlaying ? R.drawable.ic_pause : R.drawable.ic_play_arrow);
        mPlay.setColorFilter(0xFF0A1418);
        // Animate the DLS equalizer only while actually playing a station.
        mEq.setPlaying(mCurrent != null && mPlaying && !mSeeking);
    }

    /** Reflects an in-progress seek/scan in the Scan button (label + accent). */
    private void bindScan() {
        int accent = accent();
        mScanLabel.setText(mSeeking ? R.string.action_scanning : R.string.action_scan);
        mScanLabel.setTextColor(accent);
        mScanIcon.setColorFilter(accent);
        mScan.setBackground(UiUtils.roundedRect(
                UiUtils.alpha(accent, mSeeking ? 0.22f : 0.12f), dp(31),
                dp(1), UiUtils.alpha(accent, mSeeking ? 0.9f : 0.55f)));
    }

    private void bindArt(@Nullable Bitmap art) {
        if (art != null) {
            mArt.setImageBitmap(art);
            mArt.setVisibility(View.VISIBLE);
            mLogo.setVisibility(View.GONE);
        } else {
            mArt.setImageDrawable(null);
            mArt.setVisibility(View.GONE);
            mLogo.setVisibility(View.VISIBLE);
        }
    }

    private void bindPresets() {
        View root = getView();
        if (root == null) return;
        int accent = accent();
        ProgramSelector cur = mCurrent == null ? null : mCurrent.getSelector();

        for (int i = 0; i < PRESET_IDS.length; i++) {
            View slot = root.findViewById(PRESET_IDS[i]);
            TextView logo = slot.findViewById(R.id.preset_logo);
            TextView label = slot.findViewById(R.id.preset_name);

            Program p = (mFavorites != null && i < mFavorites.size()) ? mFavorites.get(i) : null;
            if (p == null) {
                logo.setVisibility(View.GONE);
                label.setText(getString(R.string.preset_empty, i + 1));
                label.setTextColor(0xFF565B61);
                slot.setBackground(UiUtils.roundedRect(0x00000000, dp(12), dp(1), 0xFF33383F));
                slot.setOnClickListener(x -> showBrowse());
            } else {
                String name = p.getName();
                if (TextUtils.isEmpty(name)) name = UiUtils.frequencyShort(p.getSelector());
                boolean active = cur != null && p.getSelector().equals(cur);
                logo.setVisibility(View.VISIBLE);
                logo.setText(UiUtils.monogram(name));
                logo.setBackground(UiUtils.roundedRect(UiUtils.logoColor(name), dp(8), 0, 0));
                label.setText(name);
                label.setTextColor(active ? accent : 0xFFD6D9DD);
                slot.setBackground(active
                        ? UiUtils.roundedRect(UiUtils.alpha(accent, 0.12f), dp(12), dp(1), accent)
                        : UiUtils.roundedRect(0xFF1A1D21, dp(12), dp(1), 0xFF262A30));
                final ProgramSelector sel = p.getSelector();
                slot.setOnClickListener(x -> mController.tune(sel));
            }
        }
    }

    private void showBrowse() {
        if (getActivity() instanceof RadioActivity) {
            ((RadioActivity) getActivity()).showBrowse();
        }
    }

    private float dp(float d) {
        return d * getResources().getDisplayMetrics().density;
    }
}

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

import android.graphics.drawable.GradientDrawable;
import android.hardware.radio.ProgramSelector;
import android.hardware.radio.RadioManager.ProgramInfo;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.android.car.broadcastradio.support.Program;
import com.android.car.radio.bands.ProgramType;
import com.android.car.radio.widget.EqualizerView;
import com.android.car.radio.widget.SignalMeter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * RecyclerView adapter for the Browse station list (DAB services / FM stations). Renders the
 * modernized rows from {@code R.layout.station_row} and drives selection + favorite toggles.
 */
public class StationAdapter extends RecyclerView.Adapter<StationAdapter.Holder> {

    /** Tune to a station. */
    public interface OnStationClick {
        void onStation(@NonNull ProgramSelector sel);
    }

    /** Toggle favorite for a station. */
    public interface OnStationFavorite {
        void onFavorite(@NonNull Program program, boolean add);
    }

    private final List<Station> mItems = new ArrayList<>();
    private final Set<ProgramSelector.Identifier> mFavIds = new HashSet<>();
    @Nullable private ProgramSelector mCurrent;
    private boolean mShowFreq;
    private int mAccent = UiUtils.ACCENT_DAB;

    private final OnStationClick mClick;
    private final OnStationFavorite mFav;

    public StationAdapter(@NonNull OnStationClick click, @NonNull OnStationFavorite fav) {
        mClick = click;
        mFav = fav;
    }

    public void setShowFrequency(boolean show) {
        mShowFreq = show;
    }

    public void setAccent(int accent) {
        mAccent = accent;
    }

    public void setStations(@NonNull List<Station> stations) {
        mItems.clear();
        mItems.addAll(stations);
        notifyDataSetChanged();
    }

    public void setCurrent(@Nullable ProgramSelector current) {
        mCurrent = current;
        notifyDataSetChanged();
    }

    public void setFavorites(@NonNull List<Program> favorites) {
        mFavIds.clear();
        for (Program p : favorites) mFavIds.add(p.getSelector().getPrimaryId());
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.station_row, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Station station = mItems.get(position);
        ProgramInfo info = station.info; // null for cached (offline) rows
        ProgramSelector sel = station.selector;
        String name = station.name;
        int color = UiUtils.logoColor(name);

        boolean active = mCurrent != null && sel.equals(mCurrent);
        boolean fav = mFavIds.contains(sel.getPrimaryId());

        if (mShowFreq) {
            String freq = UiUtils.frequencyShort(sel);
            h.freq.setVisibility(TextUtils.isEmpty(freq) ? View.GONE : View.VISIBLE);
            h.freq.setText(freq);
        } else {
            h.freq.setVisibility(View.GONE);
        }

        GradientDrawable logoBg = UiUtils.roundedRect(color, dp(h.logo, 11), 0, 0);
        h.logo.setBackground(logoBg);
        h.logo.setText(UiUtils.monogram(name));

        h.name.setText(name);
        h.name.setTextColor(active ? mAccent : 0xFFEAEBEE);
        h.active.setVisibility(active ? View.VISIBLE : View.GONE);
        h.active.setAccent(mAccent);
        h.active.setPlaying(active);

        // DLS / signal are only available from live tuner info; cached rows show neither.
        String dls = info != null ? UiUtils.dls(info) : "";
        if (TextUtils.isEmpty(dls) && info != null) dls = UiUtils.subtitle(info);
        h.dls.setText(dls);

        h.signal.setLevel(info != null ? UiUtils.signalLevel(info) : 0, mAccent);

        h.star.setImageResource(fav ? R.drawable.ic_star_filled : R.drawable.ic_star_empty);
        h.star.setColorFilter(fav ? mAccent : 0xFF6E7378);

        h.root.setBackgroundColor(active ? UiUtils.alpha(mAccent, 0.10f) : 0x00000000);

        h.root.setOnClickListener(v -> mClick.onStation(sel));
        h.star.setOnClickListener(v -> mFav.onFavorite(station.toProgram(), !fav));
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    private static float dp(View v, float d) {
        return d * v.getResources().getDisplayMetrics().density;
    }

    static class Holder extends RecyclerView.ViewHolder {
        final View root;
        final TextView freq;
        final TextView logo;
        final TextView name;
        final EqualizerView active;
        final TextView dls;
        final SignalMeter signal;
        final ImageView star;

        Holder(@NonNull View v) {
            super(v);
            root = v.findViewById(R.id.row_root);
            freq = v.findViewById(R.id.row_freq);
            logo = v.findViewById(R.id.row_logo);
            name = v.findViewById(R.id.row_name);
            active = v.findViewById(R.id.row_active);
            dls = v.findViewById(R.id.row_dls);
            signal = v.findViewById(R.id.row_signal);
            star = v.findViewById(R.id.row_star);
        }
    }
}

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

import java.util.ArrayList;
import java.util.List;

/**
 * Grid adapter for the Favorites screen. Each card tunes its station on tap and removes the
 * favorite via the star.
 */
public class FavoritesAdapter extends RecyclerView.Adapter<FavoritesAdapter.Holder> {

    public interface OnFavoriteClick {
        void onClick(@NonNull ProgramSelector sel);
    }

    public interface OnFavoriteRemove {
        void onRemove(@NonNull Program program);
    }

    private final List<Program> mItems = new ArrayList<>();
    @Nullable private ProgramSelector mCurrent;

    private final OnFavoriteClick mClick;
    private final OnFavoriteRemove mRemove;

    public FavoritesAdapter(@NonNull OnFavoriteClick click, @NonNull OnFavoriteRemove remove) {
        mClick = click;
        mRemove = remove;
    }

    public void setFavorites(@NonNull List<Program> favorites) {
        mItems.clear();
        mItems.addAll(favorites);
        notifyDataSetChanged();
    }

    public void setCurrent(@Nullable ProgramSelector current) {
        mCurrent = current;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.favorite_card, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Program p = mItems.get(position);
        ProgramSelector sel = p.getSelector();
        ProgramType pt = ProgramType.fromSelector(sel);
        int accent = UiUtils.accentFor(pt);
        String name = p.getName();
        if (name == null || name.isEmpty()) name = UiUtils.frequencyShort(sel);
        int color = UiUtils.logoColor(name);
        boolean active = mCurrent != null && sel.equals(mCurrent);

        GradientDrawable logoBg = UiUtils.roundedRect(color, dp(h.logo, 13), 0, 0);
        h.logo.setBackground(logoBg);
        h.logo.setText(UiUtils.monogram(name));

        h.name.setText(name);
        h.name.setTextColor(active ? accent : 0xFFEAEBEE);

        h.band.setText(pt == ProgramType.DAB ? R.string.band_dab : R.string.band_fm);
        h.band.setTextColor(accent);
        h.band.setBackground(UiUtils.roundedRect(UiUtils.alpha(accent, 0.18f), dp(h.band, 6), 0, 0));

        h.sub.setText(pt == ProgramType.DAB ? "DAB+" : UiUtils.frequencyShort(sel));

        h.star.setColorFilter(accent);

        GradientDrawable cardBg = UiUtils.roundedRect(0xFF1B1E22, dp(h.root, 18), dp(h.root, 1),
                active ? accent : 0xFF262A30);
        h.root.setBackground(cardBg);

        final Program prog = p;
        h.root.setOnClickListener(v -> mClick.onClick(sel));
        h.star.setOnClickListener(v -> mRemove.onRemove(prog));
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    private static int dp(View v, float d) {
        return Math.round(d * v.getResources().getDisplayMetrics().density);
    }

    static class Holder extends RecyclerView.ViewHolder {
        final View root;
        final TextView logo;
        final TextView name;
        final TextView band;
        final TextView sub;
        final ImageView star;

        Holder(@NonNull View v) {
            super(v);
            root = v.findViewById(R.id.fav_root);
            logo = v.findViewById(R.id.fav_logo);
            name = v.findViewById(R.id.fav_name);
            band = v.findViewById(R.id.fav_band);
            sub = v.findViewById(R.id.fav_sub);
            star = v.findViewById(R.id.fav_star);
        }
    }
}

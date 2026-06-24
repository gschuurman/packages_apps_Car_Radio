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

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

/**
 * Settings screen: a list of toggle rows persisted to {@link RadioPrefs}. The "slideshow" toggle
 * gates the now-playing artwork; the others are remembered UI preferences without a HAL backend
 * on this device. A static region/tuner info row closes the list.
 */
public class SettingsFragment extends Fragment {

    private RadioController mController;
    private RadioPrefs mPrefs;

    private static final class Item {
        final String key;
        final int title;
        final int desc;
        final boolean def;
        Item(String key, int title, int desc, boolean def) {
            this.key = key;
            this.title = title;
            this.desc = desc;
            this.def = def;
        }
    }

    private static final Item[] ITEMS = {
        new Item(RadioPrefs.KEY_SLIDESHOW, R.string.setting_slideshow_title,
                R.string.setting_slideshow_desc, true),
        new Item(RadioPrefs.KEY_FOLLOWING, R.string.setting_following_title,
                R.string.setting_following_desc, true),
        new Item(RadioPrefs.KEY_RDS, R.string.setting_rds_title,
                R.string.setting_rds_desc, true),
        new Item(RadioPrefs.KEY_TRAFFIC, R.string.setting_traffic_title,
                R.string.setting_traffic_desc, false),
        new Item(RadioPrefs.KEY_DLS_LOCK, R.string.setting_dls_title,
                R.string.setting_dls_desc, true),
    };

    static SettingsFragment newInstance(RadioController controller) {
        SettingsFragment f = new SettingsFragment();
        f.mController = controller;
        return f;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle saved) {
        return inflater.inflate(R.layout.settings_fragment, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, Bundle saved) {
        mPrefs = mController.getPrefs();
        LinearLayout container = v.findViewById(R.id.settings_container);
        LayoutInflater inflater = LayoutInflater.from(getContext());
        ColorStateList accent = ColorStateList.valueOf(UiUtils.ACCENT_DAB);
        ColorStateList off = ColorStateList.valueOf(0xFF3A3E44);

        for (Item item : ITEMS) {
            View row = inflater.inflate(R.layout.settings_row, container, false);
            TextView title = row.findViewById(R.id.setting_title);
            TextView desc = row.findViewById(R.id.setting_desc);
            Switch sw = row.findViewById(R.id.setting_switch);
            title.setText(item.title);
            desc.setText(item.desc);
            boolean on = mPrefs.get(item.key, item.def);
            sw.setChecked(on);
            sw.setThumbTintList(ColorStateList.valueOf(0xFFFFFFFF));
            sw.setTrackTintList(on ? accent : off);
            row.setOnClickListener(x -> {
                boolean next = !sw.isChecked();
                sw.setChecked(next);
                sw.setTrackTintList(next ? accent : off);
                mPrefs.set(item.key, next);
            });
            container.addView(row);
        }

        // Static region/tuner info row.
        View region = inflater.inflate(R.layout.settings_row, container, false);
        ((TextView) region.findViewById(R.id.setting_title))
                .setText(R.string.settings_region_title);
        ((TextView) region.findViewById(R.id.setting_desc))
                .setText(R.string.settings_region_desc);
        region.findViewById(R.id.setting_switch).setVisibility(View.GONE);
        container.addView(region);
    }
}

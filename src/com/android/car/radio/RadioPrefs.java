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

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Persisted UI preferences for the radio app (the Settings screen toggles).
 *
 * <p>Most toggles are UI preferences without a HAL backend on this device and are simply
 * remembered here; {@link #KEY_SLIDESHOW} is the one with real effect — when off, the
 * now-playing artwork (DAB MOT slideshow) is suppressed (see {@code RadioController}).
 */
public final class RadioPrefs {
    private static final String PREFS = "radio_settings";

    public static final String KEY_SLIDESHOW = "slideshow";
    public static final String KEY_FOLLOWING = "following";
    public static final String KEY_RDS = "rds";
    public static final String KEY_TRAFFIC = "traffic";
    public static final String KEY_DLS_LOCK = "dls_lock";

    private final SharedPreferences mPrefs;

    public RadioPrefs(Context context) {
        mPrefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean get(String key, boolean def) {
        return mPrefs.getBoolean(key, def);
    }

    public void set(String key, boolean value) {
        mPrefs.edit().putBoolean(key, value).apply();
    }

    public void registerListener(SharedPreferences.OnSharedPreferenceChangeListener l) {
        mPrefs.registerOnSharedPreferenceChangeListener(l);
    }

    public void unregisterListener(SharedPreferences.OnSharedPreferenceChangeListener l) {
        mPrefs.unregisterOnSharedPreferenceChangeListener(l);
    }
}

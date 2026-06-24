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

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.hardware.radio.ProgramSelector;
import android.hardware.radio.RadioManager.ProgramInfo;
import android.hardware.radio.RadioMetadata;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.car.broadcastradio.support.platform.ProgramInfoExt;
import com.android.car.broadcastradio.support.platform.ProgramSelectorExt;
import com.android.car.radio.bands.ProgramType;
import com.android.car.radio.media.TunerSession;

/**
 * Small presentation helpers shared by the modernized radio UI: band accent colors, station
 * logo monograms/colors, signal-bar levels, and human-readable name/subtitle/DLS derived from
 * a {@link ProgramInfo}. Keeps the view layer free of metadata plumbing.
 */
public final class UiUtils {
    public static final int ACCENT_DAB = 0xFF3DD2E6;
    public static final int ACCENT_FM = 0xFFFFB454;

    // Palette for station logo tiles (picked deterministically from the station name).
    private static final int[] LOGO_COLORS = {
        0xFFE8344E, 0xFF1E9BD7, 0xFF159A82, 0xFFE67E22, 0xFF8E44AD, 0xFFE6007E,
        0xFF2C5F8A, 0xFFD81B60, 0xFF5B2C83, 0xFF0B7A75, 0xFFB23B2E, 0xFF3F7D20,
    };

    private UiUtils() {}

    /** Accent color for the given band (amber for FM, cyan for DAB and the default). */
    public static int accentFor(@Nullable ProgramType pt) {
        return (pt == ProgramType.FM) ? ACCENT_FM : ACCENT_DAB;
    }

    public static int accentFor(@Nullable ProgramSelector sel) {
        return accentFor(ProgramType.fromSelector(sel));
    }

    public static int accentFor(@Nullable ProgramInfo info) {
        return accentFor(info == null ? null : info.getSelector());
    }

    /** Number of filled signal bars (0..4) for a 0..100 signal strength. */
    public static int signalLevel(int strength) {
        if (strength <= 0) return 0;
        int lvl = Math.round(strength / 25f);
        if (lvl < 1) lvl = 1;
        if (lvl > 4) lvl = 4;
        return lvl;
    }

    public static int signalLevel(@Nullable ProgramInfo info) {
        return info == null ? 0 : signalLevel(info.getSignalStrength());
    }

    /** Deterministic tile color for a station name. */
    public static int logoColor(@Nullable String name) {
        if (TextUtils.isEmpty(name)) return 0xFF333A42;
        int h = 0;
        for (int i = 0; i < name.length(); i++) h = h * 31 + name.charAt(i);
        return LOGO_COLORS[Math.floorMod(h, LOGO_COLORS.length)];
    }

    /** Up-to-2-char monogram for a station name (initials of the first words, else first chars). */
    @NonNull
    public static String monogram(@Nullable String name) {
        if (TextUtils.isEmpty(name)) return "•";
        String[] parts = name.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            char c = p.charAt(0);
            if (Character.isLetterOrDigit(c)) sb.append(Character.toUpperCase(c));
            if (sb.length() == 2) break;
        }
        if (sb.length() == 0) sb.append(Character.toUpperCase(name.charAt(0)));
        if (sb.length() == 1 && name.length() > 1) {
            char c = name.charAt(1);
            if (Character.isLetterOrDigit(c)) sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    /** Display name of the station (e.g. "BBC Radio 2" or "98.0 FM"). */
    @NonNull
    public static String stationName(@NonNull ProgramInfo info) {
        ProgramSelector sel = info.getSelector();
        // Prefer the metadata-derived program name (real DAB service / FM RDS name); fall back
        // to the selector's generic display name (e.g. "98.0 FM" / "DAB").
        String name = ProgramInfoExt.getProgramName(info, /* flags= */ 0,
                TunerSession.PROGRAM_NAME_ORDER);
        if (TextUtils.isEmpty(name)) {
            name = ProgramSelectorExt.getDisplayName(sel, 0);
        }
        return name == null ? "" : name.trim();
    }

    /** Secondary line: ensemble name for DAB, frequency for FM/AM. */
    @NonNull
    public static String subtitle(@NonNull ProgramInfo info) {
        ProgramSelector sel = info.getSelector();
        ProgramType pt = ProgramType.fromSelector(sel);
        if (pt == ProgramType.DAB) {
            RadioMetadata meta = ProgramInfoExt.getMetadata(info);
            String ens = meta == null ? null
                    : meta.getString(RadioMetadata.METADATA_KEY_DAB_ENSEMBLE_NAME);
            return TextUtils.isEmpty(ens) ? "DAB+" : ens.trim();
        }
        if (ProgramSelectorExt.isAmFmProgram(sel)
                && ProgramSelectorExt.hasId(sel, ProgramSelector.IDENTIFIER_TYPE_AMFM_FREQUENCY)) {
            return ProgramSelectorExt.formatAmFmFrequency((int) ProgramSelectorExt.getFrequency(sel),
                    0) + " MHz";
        }
        return "";
    }

    /** Frequency-only string for FM rows (e.g. "98.0"); empty when not AM/FM. */
    @NonNull
    public static String frequencyShort(@NonNull ProgramSelector sel) {
        if (ProgramSelectorExt.isAmFmProgram(sel)
                && ProgramSelectorExt.hasId(sel, ProgramSelector.IDENTIFIER_TYPE_AMFM_FREQUENCY)) {
            return ProgramSelectorExt.formatAmFmFrequency(
                    (int) ProgramSelectorExt.getFrequency(sel), 0);
        }
        return "";
    }

    /** Now-playing radio text (DLS): "Artist – Title" / one of them / empty. */
    @NonNull
    public static String dls(@NonNull ProgramInfo info) {
        RadioMetadata meta = ProgramInfoExt.getMetadata(info);
        if (meta == null) return "";
        String title = meta.getString(RadioMetadata.METADATA_KEY_TITLE);
        String artist = meta.getString(RadioMetadata.METADATA_KEY_ARTIST);
        if (!TextUtils.isEmpty(title)) title = title.trim();
        else title = null;
        if (!TextUtils.isEmpty(artist)) artist = artist.trim();
        else artist = null;
        if (title == null && artist == null) return "";
        if (title == null) return artist;
        if (artist == null) return title;
        return artist + " – " + title;
    }

    /** A rounded-rect background drawable (solid fill, optional stroke), built in code so the
     *  accent tint can vary at runtime. */
    public static GradientDrawable roundedRect(int fill, float radiusPx, float strokePx,
            int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(radiusPx);
        d.setColor(fill);
        if (strokePx > 0) d.setStroke(Math.round(strokePx), strokeColor);
        return d;
    }

    /** A solid oval/circle drawable in the given color (e.g. the play button). */
    public static GradientDrawable oval(int fill) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(fill);
        return d;
    }

    /** A diagonal two-stop gradient tile (light→dark of {@code base}); used for art placeholders. */
    public static GradientDrawable gradientTile(int base, float radiusPx) {
        int light = blend(base, Color.WHITE, 0.10f);
        int dark = blend(base, Color.BLACK, 0.40f);
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{light, dark});
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(radiusPx);
        return d;
    }

    /** Mix {@code a} toward {@code b} by {@code t} (0..1). */
    public static int blend(int a, int b, float t) {
        float inv = 1f - t;
        int r = Math.round(Color.red(a) * inv + Color.red(b) * t);
        int g = Math.round(Color.green(a) * inv + Color.green(b) * t);
        int bl = Math.round(Color.blue(a) * inv + Color.blue(b) * t);
        return Color.rgb(r, g, bl);
    }

    /** Translucent version of a color at the given alpha fraction (0..1). */
    public static int alpha(int color, float a) {
        return (color & 0x00FFFFFF) | (Math.round(a * 255f) << 24);
    }
}

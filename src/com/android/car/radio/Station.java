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

import android.hardware.radio.ProgramSelector;
import android.hardware.radio.RadioManager.ProgramInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.car.broadcastradio.support.Program;

import java.util.Objects;

/**
 * A single Browse-list entry. Backs both the live program list (carrying a {@link ProgramInfo}
 * with real signal/DLS/metadata) and the persisted station cache (a name + selector only,
 * {@link #info} {@code null}). The adapter renders extras (DLS, signal bars) only when live.
 */
public final class Station {
    @NonNull public final ProgramSelector selector;
    @NonNull public final String name;
    /** Live tuner info, or {@code null} when this station came from the persisted cache. */
    @Nullable public final ProgramInfo info;

    private Station(@NonNull ProgramSelector selector, @NonNull String name,
            @Nullable ProgramInfo info) {
        this.selector = Objects.requireNonNull(selector);
        this.name = Objects.requireNonNull(name);
        this.info = info;
    }

    /** A live station from the tuner's current program list. */
    @NonNull
    public static Station fromInfo(@NonNull ProgramInfo info) {
        return new Station(info.getSelector(), UiUtils.stationName(info), info);
    }

    /** A cached station restored from persistent storage (no live signal/metadata). */
    @NonNull
    public static Station fromProgram(@NonNull Program program) {
        return new Station(program.getSelector(), program.getName(), null);
    }

    /** Whether this entry is backed by live tuner data (vs. the persisted cache). */
    public boolean isLive() {
        return info != null;
    }

    @NonNull
    public Program toProgram() {
        return new Program(selector, name);
    }
}

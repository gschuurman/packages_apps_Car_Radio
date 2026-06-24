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

package com.android.car.radio.storage;

import android.hardware.radio.ProgramSelector;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import com.android.car.broadcastradio.support.Program;

import java.util.Objects;

/**
 * A persisted entry of the last-seen Browse station list, so the list can be shown immediately
 * on cold start (before the tuner reconnects and re-scans). The {@link ProgramSelector} (stored
 * as its URI via {@link ProgramSelectorConverter}) is the primary key; {@link #sortIndex}
 * preserves the order the stations were discovered in.
 */
@Entity
class CachedStation {
    @PrimaryKey
    @NonNull
    public final ProgramSelector selector;

    @NonNull
    public final String name;

    public final int sortIndex;

    CachedStation(@NonNull ProgramSelector selector, @NonNull String name, int sortIndex) {
        this.selector = Objects.requireNonNull(selector);
        this.name = Objects.requireNonNull(name);
        this.sortIndex = sortIndex;
    }

    CachedStation(@NonNull Program program, int sortIndex) {
        this(program.getSelector(), program.getName(), sortIndex);
    }

    @NonNull
    public Program toProgram() {
        return new Program(selector, name);
    }
}

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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Immutable snapshot of an in-progress (or finished) full-band scan, published by
 * {@link ScanController} and rendered by the scan wizard.
 */
public final class ScanState {
    /** Where the scan currently is. */
    public enum Phase {
        /** Not started yet; the wizard shows its intro screen. */
        INTRO,
        /** Sweeping the FM band via repeated seek. */
        FM,
        /** Sweeping all DAB+ ensembles via the HAL Band III scan. */
        DAB,
        /** Finished; results saved. */
        DONE,
        /** Aborted by the user; the previous cache is left untouched. */
        CANCELED
    }

    @NonNull public final Phase phase;
    /** Short human-readable progress label (current station / ensemble count), may be null. */
    @Nullable public final String label;
    public final int fmCount;
    public final int dabCount;

    public ScanState(@NonNull Phase phase, @Nullable String label, int fmCount, int dabCount) {
        this.phase = phase;
        this.label = label;
        this.fmCount = fmCount;
        this.dabCount = dabCount;
    }

    public int total() {
        return fmCount + dabCount;
    }

    public boolean isScanning() {
        return phase == Phase.FM || phase == Phase.DAB;
    }
}

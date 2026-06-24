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
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.android.car.broadcastradio.support.Program;
import com.android.car.radio.bands.ProgramType;
import com.android.car.radio.service.RadioAppServiceWrapper;
import com.android.car.radio.service.RadioAppServiceWrapper.ProgramListCompleteListener;
import com.android.car.radio.storage.RadioStorage;
import com.android.car.radio.util.Log;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * Drives a full-band station scan (FM + all DAB+ ensembles) and persists the result.
 *
 * <p>The scan runs entirely on the main thread as a small async state machine over the tuner
 * primitives the {@link RadioAppServiceWrapper} exposes:
 * <ol>
 *   <li><b>FM phase</b> — {@code switchBand(FM)} then repeated {@code seek(true)}; each landing's
 *       station is recorded (after a short dwell for RDS PS). Stops on wrap-around, an unsuccessful
 *       seek, a per-seek timeout, or an iteration guard.</li>
 *   <li><b>DAB phase</b> — {@code startDabScan()} reopens the dynamic program list to kick the
 *       HAL's full Band III ensemble sweep (auto-tune suppressed by the service). All discovered
 *       services are harvested on {@code onProgramListComplete} (or a generous timeout).</li>
 * </ol>
 * Audio is muted throughout; the previously tuned station is restored afterward. A completed scan
 * <b>replaces</b> the persisted Browse cache; a cancel leaves it untouched.
 */
public class ScanController {
    private static final String TAG = "BcRadioApp.scan";

    private static final int FM_MAX_STEPS = 60;
    private static final long FM_BAND_SETTLE_MS = 600;
    private static final long FM_DWELL_MS = 600;
    private static final long FM_SEEK_TIMEOUT_MS = 8_000;
    private static final long DAB_POLL_MS = 1_000;
    // The HAL sweeps Band III one block at a time, dwelling up to ~12 s on a synced ensemble
    // (plus autogain), so new services can be ~25 s apart between consecutive populated blocks.
    // Treat the sweep as finished only after the discovered list has been stable for this long
    // (shorter once the HAL has signalled its terminal complete chunk), with a hard cap.
    private static final long DAB_QUIET_MS = 30_000;
    private static final long DAB_QUIET_AFTER_COMPLETE_MS = 5_000;
    private static final long DAB_TIMEOUT_MS = 6 * 60 * 1_000;

    private final RadioAppServiceWrapper mService;
    private final RadioStorage mStorage;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final MutableLiveData<ScanState> mState = new MutableLiveData<>();

    // Discovered stations, keyed by primary-id (type:value) so FM and DAB entries can't collide
    // and re-discovery is deduped. Insertion order is preserved for display.
    private final LinkedHashMap<String, Program> mFound = new LinkedHashMap<>();
    private final Set<Long> mFmSeen = new HashSet<>();

    private boolean mRunning;
    // Bumped on every start/finish so stale delayed callbacks from a previous run are ignored.
    private int mGeneration;
    private int mFmSteps;
    private int mFmCount;
    private int mDabCount;
    private boolean mSeekReturned;
    // DAB-phase progress tracking for quiescence-based completion.
    private long mDabStartMs;
    private long mDabLastGrowthMs;
    private boolean mHalReportedComplete;
    @Nullable private ProgramSelector mRestoreSel;
    @Nullable private ProgramListCompleteListener mCompleteListener;

    public ScanController(@NonNull RadioAppServiceWrapper service, @NonNull RadioStorage storage) {
        mService = service;
        mStorage = storage;
        mState.setValue(new ScanState(ScanState.Phase.INTRO, null, 0, 0));
    }

    @NonNull
    public LiveData<ScanState> getState() {
        return mState;
    }

    public boolean isRunning() {
        return mRunning;
    }

    /** Resets the wizard to its intro state (e.g. when reopened after a previous run). */
    @MainThread
    public void reset() {
        if (mRunning) return;
        mState.setValue(new ScanState(ScanState.Phase.INTRO, null, 0, 0));
    }

    /** Begins a full-band scan. No-op if one is already running. */
    @MainThread
    public void start() {
        if (mRunning) return;
        mRunning = true;
        mGeneration++;
        mFound.clear();
        mFmSeen.clear();
        mFmSteps = 0;
        mFmCount = 0;
        mDabCount = 0;
        ProgramInfo cur = mService.getCurrentProgram().getValue();
        mRestoreSel = cur != null ? cur.getSelector() : null;
        Log.i(TAG, "Scan start; restore selector = " + mRestoreSel);
        startFmPhase();
    }

    /** Aborts the scan, restores the previous station, and leaves the cache untouched. */
    @MainThread
    public void cancel() {
        if (!mRunning) return;
        Log.i(TAG, "Scan canceled by user");
        finish(/* canceled= */ true);
    }

    // ---- FM phase ----------------------------------------------------------------------------

    private void startFmPhase() {
        emit(ScanState.Phase.FM, "Starting FM…");
        mService.setMuted(true);
        mService.switchBand(ProgramType.FM);
        final int gen = mGeneration;
        mHandler.postDelayed(() -> {
            if (gen != mGeneration || !mRunning) return;
            mService.setMuted(true);
            fmSeekNext();
        }, FM_BAND_SETTLE_MS);
    }

    private void fmSeekNext() {
        if (!mRunning) return;
        if (mFmSteps >= FM_MAX_STEPS) {
            Log.i(TAG, "FM phase: iteration guard reached");
            startDabPhase();
            return;
        }
        mFmSteps++;
        final int gen = mGeneration;
        mSeekReturned = false;
        mHandler.postDelayed(() -> {
            if (gen != mGeneration || !mRunning || mSeekReturned) return;
            Log.w(TAG, "FM seek timed out; ending FM phase");
            startDabPhase();
        }, FM_SEEK_TIMEOUT_MS);
        mService.seek(true, succeeded -> {
            if (gen != mGeneration || !mRunning) return;
            mSeekReturned = true;
            if (!succeeded) {
                Log.i(TAG, "FM seek unsuccessful; ending FM phase");
                startDabPhase();
                return;
            }
            mHandler.postDelayed(() -> onFmLanded(gen), FM_DWELL_MS);
        });
    }

    private void onFmLanded(int gen) {
        if (gen != mGeneration || !mRunning) return;
        // The seek re-acquired audio focus (and unmuted); keep the scan silent.
        mService.setMuted(true);
        ProgramInfo info = mService.getCurrentProgram().getValue();
        if (info == null) {
            fmSeekNext();
            return;
        }
        ProgramSelector sel = info.getSelector();
        long freq = sel.getPrimaryId().getValue();
        if (mFmSeen.contains(freq)) {
            Log.i(TAG, "FM wrapped around at " + freq + "; ending FM phase");
            startDabPhase();
            return;
        }
        mFmSeen.add(freq);
        Station st = Station.fromInfo(info);
        mFound.put(keyOf(sel), st.toProgram());
        mFmCount++;
        emit(ScanState.Phase.FM, st.name);
        fmSeekNext();
    }

    // ---- DAB phase ---------------------------------------------------------------------------

    private void startDabPhase() {
        if (!mRunning) return;
        mFmSeen.clear();
        long now = System.currentTimeMillis();
        mDabStartMs = now;
        mDabLastGrowthMs = now;
        mHalReportedComplete = false;
        emit(ScanState.Phase.DAB, "Scanning DAB+ ensembles…");
        mService.setMuted(true);
        // The HAL's terminal complete chunk is a hint that the sweep is done; it is not relied on
        // by itself (a stale/early complete from a prior list mustn't cut the sweep short), only
        // to shorten the quiescence wait once the discovered list has also gone quiet.
        mCompleteListener = () -> mHandler.post(() -> {
            if (mRunning) mHalReportedComplete = true;
        });
        mService.addProgramListCompleteListener(mCompleteListener);
        mService.startDabScan();
        pollDab(mGeneration);
    }

    /**
     * Periodically harvests the growing DAB program list. Every discovered service is accumulated
     * immediately (so nothing is lost even if the sweep is cut short), and the phase ends once the
     * list has been stable long enough — i.e. the HAL has worked through the remaining blocks — or
     * the overall budget is exhausted.
     */
    private void pollDab(int gen) {
        if (gen != mGeneration || !mRunning) return;
        long now = System.currentTimeMillis();

        List<ProgramInfo> list = mService.getProgramList().getValue();
        boolean grew = false;
        if (list != null) {
            for (ProgramInfo pi : list) {
                if (ProgramType.fromSelector(pi.getSelector()) != ProgramType.DAB) continue;
                if (mFound.put(keyOf(pi.getSelector()), Station.fromInfo(pi).toProgram()) == null) {
                    grew = true;
                }
            }
        }
        if (grew) mDabLastGrowthMs = now;
        mDabCount = countDab(mFound.values());
        emit(ScanState.Phase.DAB, mDabCount + " services found…");

        long quiet = now - mDabLastGrowthMs;
        long elapsed = now - mDabStartMs;
        boolean settled = mDabCount > 0
                && quiet >= (mHalReportedComplete ? DAB_QUIET_AFTER_COMPLETE_MS : DAB_QUIET_MS);
        if (settled || elapsed >= DAB_TIMEOUT_MS) {
            Log.i(TAG, "DAB phase done (found=" + mFound.size() + " quietMs=" + quiet
                    + " elapsedMs=" + elapsed + " halComplete=" + mHalReportedComplete + ")");
            finish(/* canceled= */ false);
            return;
        }
        mHandler.postDelayed(() -> pollDab(gen), DAB_POLL_MS);
    }

    // ---- finish ------------------------------------------------------------------------------

    private void finish(boolean canceled) {
        if (!mRunning) return;
        mRunning = false;
        mGeneration++;  // invalidate any pending delayed callbacks
        if (mCompleteListener != null) {
            mService.removeProgramListCompleteListener(mCompleteListener);
            mCompleteListener = null;
        }
        mService.endScan();

        if (!canceled) {
            List<Program> all = new ArrayList<>(mFound.values());
            int fm = 0;
            int dab = 0;
            for (Program p : all) {
                if (ProgramType.fromSelector(p.getSelector()) == ProgramType.DAB) {
                    dab++;
                } else {
                    fm++;
                }
            }
            mFmCount = fm;
            mDabCount = dab;
            mStorage.saveStations(all);
            Log.i(TAG, "Saved " + all.size() + " stations (" + fm + " FM + " + dab + " DAB)");
        }

        // Restore the previously tuned station (this re-acquires focus and unmutes).
        if (mRestoreSel != null) {
            mService.tune(mRestoreSel);
        } else {
            mService.setMuted(false);
        }
        emit(canceled ? ScanState.Phase.CANCELED : ScanState.Phase.DONE, null);
    }

    // ---- helpers -----------------------------------------------------------------------------

    private static int countDab(@NonNull java.util.Collection<Program> programs) {
        int n = 0;
        for (Program p : programs) {
            if (ProgramType.fromSelector(p.getSelector()) == ProgramType.DAB) n++;
        }
        return n;
    }

    @NonNull
    private static String keyOf(@NonNull ProgramSelector sel) {
        ProgramSelector.Identifier id = sel.getPrimaryId();
        return id.getType() + ":" + id.getValue();
    }

    private void emit(@NonNull ScanState.Phase phase, @Nullable String label) {
        mState.setValue(new ScanState(phase, label, mFmCount, mDabCount));
    }
}

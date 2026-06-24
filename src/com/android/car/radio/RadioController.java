/*
 * Copyright (C) 2016 The Android Open Source Project
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
import android.hardware.radio.ProgramSelector;
import android.hardware.radio.RadioManager.ProgramInfo;
import android.hardware.radio.RadioMetadata;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;

import com.android.car.broadcastradio.support.Program;
import com.android.car.broadcastradio.support.platform.ProgramInfoExt;
import com.android.car.radio.bands.ProgramType;
import com.android.car.radio.bands.RegionConfig;
import com.android.car.radio.platform.RadioManagerExt;
import com.android.car.radio.service.RadioAppService;
import com.android.car.radio.service.RadioAppServiceWrapper;
import com.android.car.radio.service.RadioAppServiceWrapper.ConnectionState;
import com.android.car.radio.storage.RadioStorage;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The main controller of the radio app: the single binding point between the modernized UI
 * (the nav-rail activity and its Now/Browse/Favorites/Settings screens) and the
 * {@link RadioAppService}. It exposes the live radio state as {@link LiveData} and forwards
 * user actions to the service. The view layer reads names/metadata via {@link UiUtils}.
 */
public class RadioController {
    private static final String TAG = "BcRadioApp.controller";

    private final Object mLock = new Object();
    private final RadioActivity mActivity;

    private final RadioAppServiceWrapper mAppService = new RadioAppServiceWrapper();
    private final RadioStorage mRadioStorage;
    private final RadioPrefs mPrefs;

    @Nullable private ProgramInfo mCurrentProgram;

    // Current station artwork (DAB MOT slideshow), resolved off the main thread. Null = none.
    private final MutableLiveData<Bitmap> mStationArt = new MutableLiveData<>();
    private final ExecutorService mArtExecutor = Executors.newSingleThreadExecutor();
    private int mLastArtId = 0;

    // Browse station list: the live program list when the tuner has one, otherwise the persisted
    // cache so the list is shown immediately on cold start. The live list is written through to
    // storage as it arrives. mLastSavedUris dedupes those writes.
    private final MediatorLiveData<List<Station>> mStations = new MediatorLiveData<>();
    private Set<String> mLastSavedUris = new LinkedHashSet<>();

    public RadioController(@NonNull RadioActivity activity) {
        mActivity = Objects.requireNonNull(activity);
        mRadioStorage = RadioStorage.getInstance(activity);
        mPrefs = new RadioPrefs(activity);

        mAppService.getCurrentProgram().observe(activity, this::onCurrentProgramChanged);
        mAppService.getConnectionState().observe(activity, this::onConnectionStateChanged);

        mStations.addSource(mAppService.getProgramList(),
                live -> recomputeStations(live, mRadioStorage.getStations().getValue()));
        mStations.addSource(mRadioStorage.getStations(),
                cached -> recomputeStations(mAppService.getProgramList().getValue(), cached));
    }

    private void onConnectionStateChanged(@ConnectionState int state) {
        if (state == RadioAppServiceWrapper.STATE_CONNECTED) {
            mActivity.setProgramListSupported(mAppService.isProgramListSupported());
            mActivity.setSupportedProgramTypes(getRegionConfig().getSupportedProgramTypes());
        }
    }

    /** Starts the controller and establishes connection with {@link RadioAppService}. */
    public void start() {
        mAppService.bind(mActivity);
    }

    /** Closes {@link RadioAppService} connection and cleans up resources. */
    public void shutdown() {
        mAppService.unbind();
        mArtExecutor.shutdown();
    }

    @NonNull
    public LiveData<Integer> getConnectionState() {
        return mAppService.getConnectionState();
    }

    @NonNull
    public LiveData<Integer> getPlaybackState() {
        return mAppService.getPlaybackState();
    }

    @NonNull
    public LiveData<ProgramInfo> getCurrentProgram() {
        return mAppService.getCurrentProgram();
    }

    @NonNull
    public LiveData<List<ProgramInfo>> getProgramList() {
        return mAppService.getProgramList();
    }

    /**
     * Browse station list for the UI: the tuner's live program list when available (also
     * persisted as it arrives), falling back to the last list saved to storage so the Browse
     * screen is populated immediately on cold start, before the tuner reconnects.
     */
    @NonNull
    public LiveData<List<Station>> getStations() {
        return mStations;
    }

    /** Current station artwork (DAB MOT slideshow). Null when none / suppressed by prefs. */
    @NonNull
    public LiveData<Bitmap> getStationArt() {
        return mStationArt;
    }

    /** Favorites list (also used as the source for presets). */
    @NonNull
    public LiveData<List<Program>> getFavorites() {
        return mRadioStorage.getFavorites();
    }

    public boolean isFavorite(@NonNull ProgramSelector sel) {
        return mRadioStorage.isFavorite(sel);
    }

    public void setFavorite(@NonNull Program program, boolean favorite) {
        if (favorite) {
            mRadioStorage.addFavorite(program);
        } else {
            mRadioStorage.removeFavorite(program.getSelector());
        }
    }

    /** Toggles favorite status of the currently tuned program. */
    public void toggleCurrentFavorite() {
        synchronized (mLock) {
            if (mCurrentProgram == null) return;
            boolean isFav = mRadioStorage.isFavorite(mCurrentProgram.getSelector());
            setFavorite(Program.fromProgramInfo(mCurrentProgram), !isFav);
        }
    }

    @Nullable
    public ProgramInfo getCurrentProgramInfo() {
        synchronized (mLock) {
            return mCurrentProgram;
        }
    }

    public RadioPrefs getPrefs() {
        return mPrefs;
    }

    public void tune(ProgramSelector sel) {
        mAppService.tune(sel);
    }

    public void step(boolean forward) {
        mAppService.step(forward);
    }

    public void seek(boolean forward) {
        mAppService.seek(forward);
    }

    public void skip(boolean forward) {
        mAppService.skip(forward);
    }

    public void setMuted(boolean muted) {
        mAppService.setMuted(muted);
    }

    public void switchBand(@NonNull ProgramType pt) {
        mAppService.switchBand(pt);
    }

    @NonNull
    public RegionConfig getRegionConfig() {
        return mAppService.getRegionConfig();
    }

    public void setSkipMode(@NonNull SkipMode mode) {
        mAppService.setSkipMode(mode);
    }

    /**
     * Rebuilds {@link #mStations}: prefer the live program list (and write it through to
     * storage), otherwise show the persisted cache. Empty when neither is available yet.
     */
    private void recomputeStations(@Nullable List<ProgramInfo> live,
            @Nullable List<Program> cached) {
        if (live != null && !live.isEmpty()) {
            List<Station> out = new ArrayList<>(live.size());
            for (ProgramInfo info : live) out.add(Station.fromInfo(info));
            mStations.setValue(out);
            persistStations(out);
        } else if (cached != null && !cached.isEmpty()) {
            List<Station> out = new ArrayList<>(cached.size());
            for (Program p : cached) out.add(Station.fromProgram(p));
            mStations.setValue(out);
        } else {
            mStations.setValue(new ArrayList<>());
        }
    }

    /** Persists the live station list to storage, skipping the write when it is unchanged. */
    private void persistStations(@NonNull List<Station> stations) {
        Set<String> uris = new LinkedHashSet<>();
        List<Program> programs = new ArrayList<>(stations.size());
        for (Station s : stations) {
            programs.add(s.toProgram());
            uris.add(s.selector.toString());
        }
        if (uris.equals(mLastSavedUris)) return;
        mLastSavedUris = uris;
        mRadioStorage.saveStations(programs);
    }

    private void onCurrentProgramChanged(@NonNull ProgramInfo info) {
        synchronized (mLock) {
            mCurrentProgram = Objects.requireNonNull(info);
            updateStationArt(ProgramInfoExt.getMetadata(info));
        }
    }

    /**
     * Resolves and publishes the station artwork (DAB MOT slideshow) advertised in the
     * metadata. The art id changes whenever the image changes (0 = none); resolution is a
     * binder call so it runs off the main thread, with the result posted to {@link #mStationArt}.
     * Honors the "slideshow" preference: when off, art is suppressed.
     */
    private void updateStationArt(@Nullable RadioMetadata meta) {
        if (!mPrefs.get(RadioPrefs.KEY_SLIDESHOW, true)) {
            mLastArtId = 0;
            mStationArt.postValue(null);
            return;
        }
        int artId = (meta != null && meta.containsKey(RadioMetadata.METADATA_KEY_ART))
                ? meta.getBitmapId(RadioMetadata.METADATA_KEY_ART) : 0;
        if (artId == mLastArtId) return;
        mLastArtId = artId;
        if (artId == 0) {
            mStationArt.postValue(null);
            return;
        }
        final int requestedId = artId;
        mArtExecutor.execute(() -> {
            Bitmap art = RadioManagerExt.resolveMetadataImage(requestedId);
            // Only publish if still current.
            if (requestedId == mLastArtId) mStationArt.postValue(art);
        });
    }
}

/**
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.car.radio.service;

import static com.android.car.radio.util.Remote.tryExec;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.hardware.radio.ProgramList;
import android.hardware.radio.ProgramSelector;
import android.hardware.radio.RadioManager.ProgramInfo;
import android.hardware.radio.RadioTuner;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.service.media.MediaBrowserService;
import android.support.v4.media.MediaBrowserCompat.MediaItem;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;
import androidx.lifecycle.LiveData;
import androidx.media.MediaBrowserServiceCompat;

import com.android.car.broadcastradio.support.Program;
import com.android.car.broadcastradio.support.media.BrowseTree;
import com.android.car.radio.SkipMode;
import com.android.car.radio.audio.AudioStreamController;
import com.android.car.radio.bands.ProgramType;
import com.android.car.radio.bands.RegionConfig;
import com.android.car.radio.media.TunerSession;
import com.android.car.radio.platform.ImageMemoryCache;
import com.android.car.radio.platform.RadioManagerExt;
import com.android.car.radio.platform.RadioTunerExt;
import com.android.car.radio.platform.RadioTunerExt.TuneCallback;
import com.android.car.radio.storage.RadioStorage;
import com.android.car.radio.util.IndentingPrintWriter;
import com.android.car.radio.util.Log;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A service handling hardware tuner session and audio streaming.
 */
public class RadioAppService extends MediaBrowserServiceCompat implements LifecycleOwner {
    private static final String TAG = "BcRadioApp.service";

    public static String ACTION_APP_SERVICE = "com.android.car.radio.ACTION_APP_SERVICE";
    private static final long PROGRAM_LIST_RATE_LIMITING = 1000;

    /** Returns the {@link ComponentName} that represents this {@link MediaBrowserService}. */
    public static @NonNull ComponentName getMediaSourceComp(Context context) {
        return new ComponentName(context, RadioAppService.class);
    }

    private final Object mLock = new Object();
    private final LifecycleRegistry mLifecycleRegistry = new LifecycleRegistry(this);
    private final List<IRadioAppCallback> mRadioAppCallbacks = new ArrayList<>();
    private RadioAppServiceWrapper mWrapper;

    private RadioManagerExt mRadioManager;
    @Nullable private RadioTunerExt mRadioTuner;
    @Nullable private ProgramList mProgramList;

    private RadioStorage mRadioStorage;
    private ImageMemoryCache mImageCache;
    @Nullable private AudioStreamController mAudioStreamController;

    private BrowseTree mBrowseTree;
    private TunerSession mMediaSession;

    // current observables state for newly bound IRadioAppCallbacks
    @GuardedBy("mLock")
    private ProgramInfo mCurrentProgram = null;
    @GuardedBy("mLock")
    private int mCurrentPlaybackState = PlaybackStateCompat.STATE_NONE;
    @GuardedBy("mLock")
    private long mLastProgramListPush;
    @GuardedBy("mLock")
    private RegionConfig mRegionConfigCache;
    @GuardedBy("mLock")
    private boolean mCanUpdateCurrentProgram;
    // DAB has no fixed default channel; switching to DAB defers tuning until the
    // ensemble scan populates the program list, then tunes to the first service.
    @GuardedBy("mLock")
    private boolean mPendingDabAutoTune;
    // While a full-band scan is in progress, the auto-tune-to-first-DAB-service is suppressed so
    // the HAL's ensemble sweep is allowed to run to completion across all blocks.
    @GuardedBy("mLock")
    private boolean mScanning;

    private SkipController mSkipController;

    @Override
    public void onCreate() {
        super.onCreate();

        Log.i(TAG, "Starting RadioAppService...");

        mWrapper = new RadioAppServiceWrapper(mLocalService);
        mRadioManager = new RadioManagerExt(this);
        mRadioStorage = RadioStorage.getInstance(this);
        mImageCache = new ImageMemoryCache(mRadioManager, 1000);
        mRadioTuner = mRadioManager.openSession(mHardwareCallback, null);
        if (mRadioTuner == null) {
            Log.e(TAG, "Couldn't open tuner session");
            return;
        }

        mAudioStreamController = new AudioStreamController(this, mRadioTuner,
                this::onPlaybackStateChanged);
        mBrowseTree = new BrowseTree(this, mImageCache);
        mMediaSession = new TunerSession(this, mBrowseTree, mWrapper, mImageCache);
        setSessionToken(mMediaSession.getSessionToken());
        mBrowseTree.setAmFmRegionConfig(mRadioManager.getAmFmRegionConfig());
        LiveData<List<Program>> favorites = mRadioStorage.getFavorites();
        SkipMode skipMode = mRadioStorage.getSkipMode();
        mSkipController = new SkipController(mLocalService, favorites, skipMode);
        favorites.observe(this, favs -> mBrowseTree.setFavorites(new HashSet<>(favs)));

        setupProgramList(/* filter= */ null);

        mLifecycleRegistry.markState(Lifecycle.State.CREATED);
    }

    /**
     * (Re)opens the dynamic program list with the given filter and rewires it to the browse tree
     * and the app's program-list observers.
     *
     * <p>The single dongle can only decode one band at a time, so the program list is band-scoped:
     * switching to DAB reopens it with a DAB identifier filter, which makes the HAL run its DAB
     * ensemble scan and stream the discovered services back here. A {@code null} filter (AM/FM)
     * yields the default list (the HAL reports an empty FM list; FM uses seek, not a scan list).
     */
    private void setupProgramList(@Nullable ProgramList.Filter filter) {
        synchronized (mLock) {
            if (mRadioTuner == null) return;
            if (mProgramList != null) {
                ProgramList oldList = mProgramList;
                mProgramList = null;
                oldList.close();
            }
            mProgramList = mRadioTuner.getDynamicProgramList(filter);
            if (mProgramList != null) {
                mBrowseTree.setProgramList(mProgramList);
                mProgramList.registerListCallback(new ProgramList.ListCallback() {
                    @Override
                    public void onItemChanged(@NonNull ProgramSelector.Identifier id) {
                        onProgramListChanged();
                    }
                });
                mProgramList.addOnCompleteListener(() -> {
                    pushProgramListUpdate();
                    onProgramListComplete();
                });
            }
        }
    }

    private void onProgramListComplete() {
        synchronized (mLock) {
            for (IRadioAppCallback callback : mRadioAppCallbacks) {
                tryExec(() -> callback.onProgramListComplete());
            }
        }
    }

    // Identifier types that select DAB programs; used to scope the dynamic program list to a DAB
    // ensemble scan when the DAB band is active.
    private static ProgramList.Filter dabProgramListFilter() {
        // The HAL emits DAB programs with a 64-bit SId, which the framework reports as
        // IDENTIFIER_TYPE_DAB_DMB_SID_EXT (14), not the deprecated 32-bit
        // IDENTIFIER_TYPE_DAB_SID_EXT (5). Include both so the program-list filter
        // doesn't reject every discovered service.
        Set<Integer> idTypes = new HashSet<>(Arrays.asList(
                ProgramSelector.IDENTIFIER_TYPE_DAB_SID_EXT,
                ProgramSelector.IDENTIFIER_TYPE_DAB_DMB_SID_EXT,
                ProgramSelector.IDENTIFIER_TYPE_DAB_ENSEMBLE,
                ProgramSelector.IDENTIFIER_TYPE_DAB_FREQUENCY));
        return new ProgramList.Filter(idTypes, new HashSet<>(),
                /* includeCategories= */ true, /* excludeModifications= */ false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand intent [%s] flags[%d] startId[%d]",
                intent.toString(), flags, startId);
        mLifecycleRegistry.markState(Lifecycle.State.STARTED);
        if (BrowseTree.ACTION_PLAY_BROADCASTRADIO.equals(intent.getAction())) {
            Log.i(TAG, "Executing general play radio intent");
            mMediaSession.getController().getTransportControls().playFromMediaId(
                    mBrowseTree.getRoot().getRootId(), null);
            return START_NOT_STICKY;
        }

        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.i(TAG, "onBind intent[" + intent + "]");
        mLifecycleRegistry.markState(Lifecycle.State.STARTED);
        if (mRadioTuner == null) return null;
        if (ACTION_APP_SERVICE.equals(intent.getAction())) {
            return mLocalBinder;
        }
        return super.onBind(intent);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        mLifecycleRegistry.markState(Lifecycle.State.CREATED);
        return false;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "Shutting down RadioAppService...");

        mLifecycleRegistry.markState(Lifecycle.State.DESTROYED);

        if (mMediaSession != null) mMediaSession.release();
        close();

        super.onDestroy();
    }

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return mLifecycleRegistry;
    }

    @GuardedBy("mLock")
    private void setCanUpdateCurrentProgramLocked() {
        if (mCanUpdateCurrentProgram) {
            return;
        }
        mCanUpdateCurrentProgram = true;
    }

    @GuardedBy("mLock")
    private boolean canUpdateCurrentProgramLocked() {
        return mCanUpdateCurrentProgram;
    }

    private void onPlaybackStateChanged(int newState) {
        Log.d(TAG, "onPlaybackStateChanged new state [%d]", newState);
        synchronized (mLock) {
            mCurrentPlaybackState = newState;
            for (IRadioAppCallback callback : mRadioAppCallbacks) {
                tryExec(() -> callback.onPlaybackStateChanged(newState));
            }
        }
    }

    private void onProgramListChanged() {
        if (mProgramList == null) return;
        synchronized (mLock) {
            maybeAutoTuneDabLocked();
            if (SystemClock.elapsedRealtime() - mLastProgramListPush > PROGRAM_LIST_RATE_LIMITING) {
                pushProgramListUpdate();
            }
        }
    }

    // If a DAB band switch is awaiting a station, tune to the first DAB service that
    // the ensemble scan has discovered. No-op until one is available.
    @GuardedBy("mLock")
    private void maybeAutoTuneDabLocked() {
        if (mScanning) return;
        if (!mPendingDabAutoTune || mProgramList == null || mRadioTuner == null) return;
        ProgramSelector dabSel = null;
        for (ProgramInfo pi : mProgramList.toList()) {
            if (ProgramType.fromSelector(pi.getSelector()) == ProgramType.DAB) {
                dabSel = pi.getSelector();
                break;
            }
        }
        if (dabSel == null) return;
        TuneCallback tuneCb =
                mAudioStreamController.preparePlayback(AudioStreamController.OPERATION_TUNE);
        if (tuneCb == null) return;
        mPendingDabAutoTune = false;
        Log.i(TAG, "Auto-tuning to first DAB service: " + dabSel);
        try {
            mRadioTuner.tune(dabSel, tuneCb);
            setCanUpdateCurrentProgramLocked();
        } catch (IllegalArgumentException | UnsupportedOperationException e) {
            Log.e(TAG, "DAB auto-tune failed for " + dabSel, e);
        }
    }

    private void pushProgramListUpdate() {
        if (mProgramList == null) return;
        List<ProgramInfo> plist = mProgramList.toList();

        synchronized (mLock) {
            mLastProgramListPush = SystemClock.elapsedRealtime();
            for (IRadioAppCallback callback : mRadioAppCallbacks) {
                tryExec(() -> callback.onProgramListChanged(plist));
            }
        }
    }

    private void tuneToDefault(@Nullable ProgramType pt) {
        synchronized (mLock) {
            if (mRadioTuner == null) throw new IllegalStateException("Tuner session is closed");

            mPendingDabAutoTune = false;
            ProgramSelector recent = mRadioStorage.getRecentlySelected(pt);
            if (recent == null && pt == ProgramType.DAB) {
                // No fixed DAB default and no recent selection: wait for the ensemble
                // scan to deliver a service, then tune to the first one.
                Log.i(TAG, "Deferring DAB default tune until scan yields a service");
                mPendingDabAutoTune = true;
                maybeAutoTuneDabLocked();
                return;
            }

            TuneCallback tuneCb = mAudioStreamController.preparePlayback(
                    AudioStreamController.OPERATION_TUNE);
            if (tuneCb == null) return;

            ProgramSelector sel = recent;
            if (sel != null) {
                Log.i(TAG, "Restoring recently selected program: " + sel);
                try {
                    mRadioTuner.tune(sel, tuneCb);
                } catch (IllegalArgumentException | UnsupportedOperationException e) {
                    Log.e(TAG, "Can't restore recently selected program: " + sel, e);
                }
                setCanUpdateCurrentProgramLocked();
                return;
            }

            if (pt == null) pt = ProgramType.FM;
            Log.i(TAG, "No recently selected program set, selecting default channel for " + pt);
            pt.tuneToDefault(mRadioTuner, mLocalService.getRegionConfig(), tuneCb);
        }
    }

    private void close() {
        synchronized (mLock) {
            if (mAudioStreamController != null) {
                mAudioStreamController.requestMuted(true);
                mAudioStreamController = null;
            }
            if (mProgramList != null) {
                ProgramList oldList = mProgramList;
                mProgramList = null;
                oldList.close();
            }
            if (mRadioTuner != null) {
                mRadioTuner.close();
                mRadioTuner = null;
            }
        }
    }

    @Override
    public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints) {
        /* Radio application may restrict who can read its MediaBrowser tree.
         * Our implementation doesn't.
         */
        return mBrowseTree.getRoot();
    }

    @Override
    public void onLoadChildren(final String parentMediaId, final Result<List<MediaItem>> result) {
        mBrowseTree.loadChildren(parentMediaId, result);
    }

    private void onHardwareError() {
        close();
        stopSelf();
        synchronized (mLock) {
            for (IRadioAppCallback callback : mRadioAppCallbacks) {
                tryExec(() -> callback.onHardwareError());
            }
        }
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        try (IndentingPrintWriter writer = new IndentingPrintWriter(pw)) {
            pw.println("RadioAppService:");
            writer.increaseIndent();
            if (mSkipController != null) {
                writer.increaseIndent();
                mSkipController.dump(writer);
                writer.decreaseIndent();
            } else {
                pw.println("No SkipController");
            }

            if (mAudioStreamController != null) {
                writer.increaseIndent();
                mAudioStreamController.dump(writer);
                writer.decreaseIndent();
            } else {
                pw.println("No AudioStreamController");
            }
            writer.decreaseIndent();
        }
    }

    private final IBinder mLocalBinder = new LocalBinder();

    public class LocalBinder extends Binder {
        IRadioAppService getLocalService() {
            return mLocalService;
        }
    }

    private final IRadioAppService mLocalService = new IRadioAppService() {
        @Override
        public void addCallback(IRadioAppCallback callback) throws RemoteException {
            synchronized (mLock) {
                if (mCurrentProgram != null) callback.onCurrentProgramChanged(mCurrentProgram);
                callback.onPlaybackStateChanged(mCurrentPlaybackState);
                if (mProgramList != null) callback.onProgramListChanged(mProgramList.toList());
                mRadioAppCallbacks.add(callback);
            }
        }

        @Override
        public void removeCallback(IRadioAppCallback callback) {
            synchronized (mLock) {
                mRadioAppCallbacks.remove(callback);
            }
        }

        @Override
        public void tune(ProgramSelector sel, ITuneCallback callback) {
            Objects.requireNonNull(callback);
            synchronized (mLock) {
                if (mRadioTuner == null) throw new IllegalStateException("Tuner session is closed");
                TuneCallback tuneCb = mAudioStreamController.preparePlayback(
                        AudioStreamController.OPERATION_TUNE);
                if (tuneCb == null) return;
                mRadioTuner.tune(sel, tuneCb.alsoCall(
                        succ -> tryExec(() -> callback.onFinished(succ))));
                setCanUpdateCurrentProgramLocked();
            }
        }

        @Override
        public void seek(boolean forward, ITuneCallback callback) {
            Objects.requireNonNull(callback);
            synchronized (mLock) {
                if (mRadioTuner == null) throw new IllegalStateException("Tuner session is closed");
                TuneCallback tuneCb = mAudioStreamController.preparePlayback(forward
                        ? AudioStreamController.OPERATION_SEEK_FWD
                        : AudioStreamController.OPERATION_SEEK_BKW);
                if (tuneCb == null) return;
                mRadioTuner.seek(forward, tuneCb.alsoCall(
                        succ -> tryExec(() -> callback.onFinished(succ))));
                setCanUpdateCurrentProgramLocked();
            }
        }

        @Override
        public void skip(boolean forward, ITuneCallback callback) throws RemoteException {
            Objects.requireNonNull(callback);
            mSkipController.skip(forward, callback);
        }

        @Override
        public void setSkipMode(int mode) {
            SkipMode newMode = SkipMode.valueOf(mode);
            if (newMode == null) {
                Log.e(TAG, "setSkipMode(): invalid mode " + mode);
                return;
            }
            mSkipController.setSkipMode(newMode);
            mRadioStorage.setSkipMode(newMode);
        }

        @Override
        public void step(boolean forward, ITuneCallback callback) {
            Objects.requireNonNull(callback);
            synchronized (mLock) {
                if (mRadioTuner == null) throw new IllegalStateException("Tuner session is closed");
                TuneCallback tuneCb = mAudioStreamController.preparePlayback(forward
                        ? AudioStreamController.OPERATION_STEP_FWD
                        : AudioStreamController.OPERATION_STEP_BKW);
                if (tuneCb == null) return;
                mRadioTuner.step(forward, tuneCb.alsoCall(
                        succ -> tryExec(() -> callback.onFinished(succ))));
                setCanUpdateCurrentProgramLocked();
            }
        }

        @Override
        public void setMuted(boolean muted) {
            if (mAudioStreamController == null) return;
            if (muted) mRadioTuner.cancel();
            mAudioStreamController.requestMuted(muted);
        }

        @Override
        public void tuneToDefaultIfNeeded() {
            synchronized (mLock) {
                if (mRadioTuner == null) {
                    throw new IllegalStateException("Tuner session is closed");
                }

                if (mCurrentPlaybackState != PlaybackStateCompat.STATE_NONE) {
                    return;
                }
            }

            tuneToDefault(null);
        }

        @Override
        public void switchBand(ProgramType band) {
            // Scope the program list to the band: DAB triggers the HAL ensemble scan, AM/FM gets
            // the default (empty) list. Done before tuning so the dongle is committed to the band.
            setupProgramList(band == ProgramType.DAB ? dabProgramListFilter() : null);
            tuneToDefault(band);
        }

        @Override
        public void startDabScan() {
            synchronized (mLock) {
                mScanning = true;
                mPendingDabAutoTune = false;
            }
            // Reopen the dynamic program list with the DAB filter to kick a fresh HAL ensemble
            // sweep; mScanning keeps maybeAutoTuneDabLocked from cancelling it.
            setupProgramList(dabProgramListFilter());
        }

        @Override
        public void endScan() {
            synchronized (mLock) {
                mScanning = false;
            }
        }

        @Override
        public boolean isProgramListSupported() {
            return mProgramList != null;
        }

        @Override
        public RegionConfig getRegionConfig() {
            synchronized (mLock) {
                if (mRegionConfigCache == null) {
                    mRegionConfigCache = new RegionConfig(mRadioManager.getAmFmRegionConfig(),
                            mRadioManager.isDabSupported());
                }
                return mRegionConfigCache;
            }
        }
    };

    private RadioTuner.Callback mHardwareCallback = new RadioTuner.Callback() {
        @Override
        public void onProgramInfoChanged(ProgramInfo info) {
            Objects.requireNonNull(info);

            Log.d(TAG, "Program info changed: %s", info);

            synchronized (mLock) {
                if (!canUpdateCurrentProgramLocked()) {
                    return;
                }

                mCurrentProgram = info;

                /* Storing recently selected program might be limited to explicit tune calls only
                 * (including next/prev seek), but the implementation would be nontrivial with the
                 * current API. For now, let's make it simple and make it react to all program
                 * selector changes. */
                mRadioStorage.setRecentlySelected(info.getSelector());
                for (IRadioAppCallback callback : mRadioAppCallbacks) {
                    tryExec(() -> callback.onCurrentProgramChanged(info));
                }
            }
        }

        @Override
        public void onError(int status) {
            switch (status) {
                case RadioTuner.ERROR_HARDWARE_FAILURE:
                case RadioTuner.ERROR_SERVER_DIED:
                    Log.e(TAG, "Fatal hardware error: " + status);
                    onHardwareError();
                    break;
                default:
                    Log.w(TAG, "Hardware error: " + status);
            }
        }

        @Override
        public void onControlChanged(boolean control) {
            if (!control) onHardwareError();
        }
    };
}

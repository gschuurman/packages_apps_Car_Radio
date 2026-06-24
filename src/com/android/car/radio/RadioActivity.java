/*
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

package com.android.car.radio;

import android.content.Intent;
import android.graphics.Bitmap;
import android.hardware.radio.RadioManager.ProgramInfo;
import android.os.Bundle;
import android.support.v4.media.session.PlaybackStateCompat;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.android.car.media.common.source.MediaTrampolineHelper;
import com.android.car.radio.bands.ProgramType;
import com.android.car.radio.service.RadioAppService;
import com.android.car.radio.service.RadioAppServiceWrapper;
import com.android.car.radio.util.Log;
import com.android.car.radio.widget.SignalMeter;

import java.util.List;

/**
 * The main activity for the radio app. It draws the modernized shell (left nav rail, top band
 * tabs + signal, a swappable content area and the persistent mini player) and hosts the
 * Now / Browse / Favorites / Settings fragments. All radio state flows through
 * {@link RadioController}.
 */
public class RadioActivity extends FragmentActivity {
    private static final String TAG = "BcRadioApp.activity";

    private static final String ACTION_RADIO_APP_STATE_CHANGE =
            "android.intent.action.RADIO_APP_STATE_CHANGE";
    private static final String EXTRA_RADIO_APP_FOREGROUND =
            "android.intent.action.RADIO_APP_STATE";

    /** The four destinations of the nav rail. */
    public enum Screen { NOW, BROWSE, FAVORITES, SETTINGS }

    private boolean mIsConnected;
    private RadioController mRadioController;
    private MediaTrampolineHelper mMediaTrampoline;

    private Screen mScreen = Screen.NOW;
    @Nullable private ProgramType mCurrentBand;
    private boolean mPlaying = true;
    @Nullable private ProgramInfo mCurrentProgram;
    @Nullable private Bitmap mArt;

    // Shell views.
    private TextView mTitle;
    private SignalMeter mTopSignal;
    private View mBandTabs;
    private TextView mTabDab;
    private TextView mTabFm;
    private View mMiniPlayer;
    private TextView mMiniLogo;
    private TextView mMiniBand;
    private TextView mMiniName;
    private TextView mMiniDls;
    private ImageView mMiniPlay;
    private View mStatus;
    private TextView mStatusMsg;

    // Cached fragments.
    private NowPlayingFragment mNow;
    private BrowseFragment mBrowse;
    private FavoritesFragment mFavorites;
    private SettingsFragment mSettings;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "Radio app main activity created");

        setContentView(R.layout.radio_activity);
        mMediaTrampoline = new MediaTrampolineHelper(this);

        mRadioController = new RadioController(this);

        mTitle = findViewById(R.id.screen_title);
        mTopSignal = findViewById(R.id.top_signal);
        mBandTabs = findViewById(R.id.band_tabs);
        mTabDab = findViewById(R.id.band_tab_dab);
        mTabFm = findViewById(R.id.band_tab_fm);
        mMiniPlayer = findViewById(R.id.mini_player);
        mMiniLogo = mMiniPlayer.findViewById(R.id.mini_logo);
        mMiniBand = mMiniPlayer.findViewById(R.id.mini_band);
        mMiniName = mMiniPlayer.findViewById(R.id.mini_name);
        mMiniDls = mMiniPlayer.findViewById(R.id.mini_dls);
        mMiniPlay = mMiniPlayer.findViewById(R.id.mini_play);
        mStatus = findViewById(R.id.status_message);
        mStatusMsg = findViewById(R.id.status_message);

        setupNav(R.id.nav_now, R.drawable.ic_nav_now, R.string.nav_now, Screen.NOW);
        setupNav(R.id.nav_browse, R.drawable.ic_list, R.string.nav_browse, Screen.BROWSE);
        setupNav(R.id.nav_favorites, R.drawable.ic_star_filled, R.string.nav_favorites,
                Screen.FAVORITES);
        setupNav(R.id.nav_settings, R.drawable.ic_settings_gear, R.string.nav_settings,
                Screen.SETTINGS);

        mTabDab.setText(R.string.band_dab);
        mTabFm.setText(R.string.band_fm);
        mBandTabs.setBackground(UiUtils.roundedRect(0xFF1A1D21, dp(13), (int) dp(1), 0xFF262A30));
        mTabDab.setOnClickListener(v -> selectBand(ProgramType.DAB));
        mTabFm.setOnClickListener(v -> selectBand(ProgramType.FM));

        mMiniPlayer.findViewById(R.id.mini_expand).setOnClickListener(v -> showNow());
        mMiniPlayer.findViewById(R.id.mini_prev)
                .setOnClickListener(v -> mRadioController.skip(false));
        mMiniPlayer.findViewById(R.id.mini_next)
                .setOnClickListener(v -> mRadioController.skip(true));
        mMiniPlay.setOnClickListener(v -> mRadioController.setMuted(mPlaying));

        mRadioController.getConnectionState().observe(this, this::onConnectionStateChanged);
        mRadioController.getCurrentProgram().observe(this, this::onCurrentProgramChanged);
        mRadioController.getPlaybackState().observe(this, this::onPlaybackStateChanged);
        mRadioController.getStationArt().observe(this, art -> {
            mArt = art;
            updateMini();
        });

        updateBandTabs();
        updateNavSelection();
        showScreen(Screen.NOW);
    }

    private void setupNav(int containerId, int iconRes, int labelRes, Screen screen) {
        View c = findViewById(containerId);
        ((ImageView) c.findViewById(R.id.nav_icon)).setImageResource(iconRes);
        ((TextView) c.findViewById(R.id.nav_label)).setText(labelRes);
        c.setOnClickListener(v -> showScreen(screen));
    }

    // ---- public navigation API used by fragments ----

    public RadioController getRadioController() {
        return mRadioController;
    }

    @Nullable
    public ProgramType getCurrentBand() {
        return mCurrentBand;
    }

    public void showNow() {
        showScreen(Screen.NOW);
    }

    public void showBrowse() {
        showScreen(Screen.BROWSE);
    }

    public void showFavorites() {
        showScreen(Screen.FAVORITES);
    }

    public void showSettings() {
        showScreen(Screen.SETTINGS);
    }

    /** Shows the full-band scan wizard as a full-screen overlay over the shell. */
    public void showScanWizard() {
        if (!mIsConnected) return;
        View overlay = findViewById(R.id.scan_overlay);
        overlay.setVisibility(View.VISIBLE);
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.scan_overlay, ScanWizardFragment.newInstance(mRadioController))
                .commit();
    }

    /** Dismisses the scan wizard overlay. */
    public void hideScanWizard() {
        View overlay = findViewById(R.id.scan_overlay);
        Fragment f = getSupportFragmentManager().findFragmentById(R.id.scan_overlay);
        if (f != null) {
            getSupportFragmentManager().beginTransaction().remove(f).commit();
        }
        overlay.setVisibility(View.GONE);
    }

    private void showScreen(Screen screen) {
        mScreen = screen;
        Fragment f;
        int title;
        switch (screen) {
            case BROWSE:
                if (mBrowse == null) mBrowse = BrowseFragment.newInstance(mRadioController);
                f = mBrowse;
                title = R.string.screen_browse;
                break;
            case FAVORITES:
                if (mFavorites == null) {
                    mFavorites = FavoritesFragment.newInstance(mRadioController);
                }
                f = mFavorites;
                title = R.string.screen_favorites;
                break;
            case SETTINGS:
                if (mSettings == null) mSettings = SettingsFragment.newInstance(mRadioController);
                f = mSettings;
                title = R.string.screen_settings;
                break;
            case NOW:
            default:
                if (mNow == null) mNow = NowPlayingFragment.newInstance(mRadioController);
                f = mNow;
                title = R.string.screen_now_playing;
                break;
        }
        mTitle.setText(title);
        if (mIsConnected) {
            getSupportFragmentManager().beginTransaction().replace(R.id.content, f).commit();
        }
        // The mini player duplicates the Now screen, so hide it there.
        mMiniPlayer.setVisibility(screen == Screen.NOW ? View.GONE : View.VISIBLE);
        updateNavSelection();
    }

    private void updateNavSelection() {
        styleNav(R.id.nav_now, mScreen == Screen.NOW);
        styleNav(R.id.nav_browse, mScreen == Screen.BROWSE);
        styleNav(R.id.nav_favorites, mScreen == Screen.FAVORITES);
        styleNav(R.id.nav_settings, mScreen == Screen.SETTINGS);
    }

    private void styleNav(int containerId, boolean selected) {
        View c = findViewById(containerId);
        int accent = UiUtils.accentFor(mCurrentBand);
        int color = selected ? accent : 0xFF8A9099;
        ((ImageView) c.findViewById(R.id.nav_icon)).setColorFilter(color);
        ((TextView) c.findViewById(R.id.nav_label)).setTextColor(color);
        c.setBackground(selected
                ? UiUtils.roundedRect(UiUtils.alpha(accent, 0.16f), dp(18), 0, 0)
                : null);
    }

    private void selectBand(@NonNull ProgramType band) {
        if (band == mCurrentBand) return;
        mCurrentBand = band;
        mRadioController.switchBand(band);
        updateBandTabs();
        updateNavSelection();
        if (mBrowse != null && mScreen == Screen.BROWSE) {
            // Re-show browse so it re-reads the band immediately.
            showScreen(Screen.BROWSE);
        }
    }

    private void updateBandTabs() {
        boolean dab = mCurrentBand != ProgramType.FM;
        styleTab(mTabDab, dab, UiUtils.ACCENT_DAB, 0xFF08222A);
        styleTab(mTabFm, !dab, UiUtils.ACCENT_FM, 0xFF2A1A00);
    }

    private void styleTab(TextView tab, boolean active, int accent, int activeText) {
        if (active) {
            tab.setBackground(UiUtils.roundedRect(accent, dp(10), 0, 0));
            tab.setTextColor(activeText);
        } else {
            tab.setBackground(null);
            tab.setTextColor(0xFF9BA0A6);
        }
    }

    private void onConnectionStateChanged(@RadioAppServiceWrapper.ConnectionState int state) {
        mIsConnected = state == RadioAppServiceWrapper.STATE_CONNECTED;
        Log.i(TAG, "onConnectionStateChanged connected: " + mIsConnected);
        if (mIsConnected) {
            mStatus.setVisibility(View.GONE);
            showScreen(mScreen);
        } else {
            mStatusMsg.setText(state == RadioAppServiceWrapper.STATE_NOT_SUPPORTED
                    ? R.string.radio_not_supported_text : R.string.radio_failure_text);
            mStatus.setVisibility(state == RadioAppServiceWrapper.STATE_CONNECTING
                    ? View.GONE : View.VISIBLE);
        }
    }

    private void onCurrentProgramChanged(@NonNull ProgramInfo info) {
        mCurrentProgram = info;
        ProgramType pt = ProgramType.fromSelector(info.getSelector());
        if (pt != null && pt != mCurrentBand) {
            mCurrentBand = pt;
            updateBandTabs();
            updateNavSelection();
        }
        mTopSignal.setLevel(UiUtils.signalLevel(info), UiUtils.accentFor(info));
        updateMini();
    }

    private void onPlaybackStateChanged(@PlaybackStateCompat.State int state) {
        mPlaying = state != PlaybackStateCompat.STATE_PAUSED
                && state != PlaybackStateCompat.STATE_STOPPED;
        updateMini();
    }

    private void updateMini() {
        int accent = UiUtils.accentFor(mCurrentProgram);
        if (mCurrentProgram != null) {
            String name = UiUtils.stationName(mCurrentProgram);
            mMiniName.setText(name);
            String dls = UiUtils.dls(mCurrentProgram);
            mMiniDls.setText(TextUtils.isEmpty(dls) ? UiUtils.subtitle(mCurrentProgram) : dls);
            mMiniLogo.setText(UiUtils.monogram(name));
            mMiniLogo.setBackground(UiUtils.gradientTile(UiUtils.logoColor(name), dp(13)));
            ProgramType pt = ProgramType.fromSelector(mCurrentProgram.getSelector());
            mMiniBand.setText(pt == ProgramType.FM ? R.string.band_fm : R.string.band_dab);
            mMiniBand.setTextColor(accent);
            mMiniBand.setBackground(UiUtils.roundedRect(UiUtils.alpha(accent, 0.18f), dp(7), 0, 0));
        }
        mMiniPlay.setBackground(UiUtils.oval(accent));
        mMiniPlay.setImageResource(mPlaying ? R.drawable.ic_pause : R.drawable.ic_play_arrow);
        mMiniPlay.setColorFilter(0xFF0A1418);
    }

    /** Called by the controller when the program-list support is known. */
    public void setProgramListSupported(boolean supported) { }

    /** Called by the controller once the supported bands are known. */
    public void setSupportedProgramTypes(@NonNull List<ProgramType> supported) {
        if (mCurrentBand == null && !supported.isEmpty()) {
            mCurrentBand = supported.contains(ProgramType.DAB) ? ProgramType.DAB : supported.get(0);
            updateBandTabs();
            updateNavSelection();
        }
        // Only show a band tab if its band is supported.
        mTabDab.setVisibility(supported.contains(ProgramType.DAB) ? View.VISIBLE : View.GONE);
        mTabFm.setVisibility(supported.contains(ProgramType.FM) ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void onStart() {
        super.onStart();
        mRadioController.start();
        Intent broadcast = new Intent(ACTION_RADIO_APP_STATE_CHANGE);
        broadcast.putExtra(EXTRA_RADIO_APP_FOREGROUND, true);
        sendBroadcast(broadcast);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Intent intent = getIntent();
        if (intent != null) {
            mMediaTrampoline.setLaunchedMediaSource(RadioAppService.getMediaSourceComp(this));
            setIntent(null);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        Intent broadcast = new Intent(ACTION_RADIO_APP_STATE_CHANGE);
        broadcast.putExtra(EXTRA_RADIO_APP_FOREGROUND, false);
        sendBroadcast(broadcast);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mRadioController.shutdown();
        Log.d(TAG, "Radio app main activity destroyed");
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_STEP_FORWARD:
                mRadioController.step(/* forward= */ true);
                return true;
            case KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD:
                mRadioController.step(/* forward= */ false);
                return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                mRadioController.seek(/* forward= */ true);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                mRadioController.seek(/* forward= */ false);
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    private float dp(float d) {
        return d * getResources().getDisplayMetrics().density;
    }
}

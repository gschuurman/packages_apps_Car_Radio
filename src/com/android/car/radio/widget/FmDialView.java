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

package com.android.car.radio.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * A horizontal FM tuning dial: a base line with 88–108 MHz ticks (labels every 4 MHz), a dot
 * marker for every scanned station, and an accent needle at the current frequency. Draggable:
 * touch/drag scrubs the needle (snapped to 0.1 MHz) and reports the chosen frequency on release.
 */
public class FmDialView extends View {
    private static final float LOW = 87.5f;
    private static final float HIGH = 108.0f;
    private static final float SPAN = HIGH - LOW;

    /** Reports user scrubbing on the dial. */
    public interface OnTuneListener {
        /** Continuous feedback while dragging (update the readout, do not yet tune). */
        void onScrub(float freqMhz);
        /** Final frequency chosen on release — tune to it. */
        void onCommit(float freqMhz);
    }

    private OnTuneListener mListener;
    private float mLastPadL;
    private float mLastUsableW;

    private final Paint mLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLabel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mMarker = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mNeedle = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final List<float[]> mMarkers = new ArrayList<>();   // {freq, colorAsFloatBits-unused}
    private final List<Integer> mMarkerColors = new ArrayList<>();
    private float mCurrentFreq = 98f;
    private int mAccent = 0xFFFFB454;
    private final float mDensity;

    public FmDialView(Context context) {
        this(context, null);
    }

    public FmDialView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDensity = getResources().getDisplayMetrics().density;
        mLine.setColor(0xFF2E333A);
        mTick.setColor(0xFF3A3F46);
        mLabel.setColor(0xFF6E7378);
        mLabel.setTextAlign(Paint.Align.CENTER);
        mLabel.setTextSize(11 * mDensity);
    }

    /** @param freq needle position in MHz; @param accent needle/glow color. */
    public void setCurrent(float freq, int accent) {
        mCurrentFreq = freq;
        mAccent = accent;
        invalidate();
    }

    public void setOnTuneListener(OnTuneListener l) {
        mListener = l;
        setClickable(l != null);
    }

    /** Snap a raw MHz value to the 0.1 MHz FM grid, clamped to the band. */
    private static float snap(float mhz) {
        float v = Math.round(mhz * 10f) / 10f;
        if (v < 88.0f) v = 88.0f;
        if (v > 108.0f) v = 108.0f;
        return v;
    }

    private float freqForX(float x) {
        if (mLastUsableW <= 0) return mCurrentFreq;
        float t = (x - mLastPadL) / mLastUsableW;
        if (t < 0) t = 0;
        if (t > 1) t = 1;
        return snap(LOW + t * SPAN);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (mListener == null) return super.onTouchEvent(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE: {
                getParent().requestDisallowInterceptTouchEvent(true);
                float f = freqForX(ev.getX());
                mCurrentFreq = f;
                invalidate();
                mListener.onScrub(f);
                return true;
            }
            case MotionEvent.ACTION_UP: {
                float f = freqForX(ev.getX());
                mCurrentFreq = f;
                invalidate();
                mListener.onCommit(f);
                performClick();
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default:
                return super.onTouchEvent(ev);
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    /** Replace the station markers (frequency in MHz + dot color). */
    public void setMarkers(List<float[]> freqs, List<Integer> colors) {
        mMarkers.clear();
        mMarkerColors.clear();
        if (freqs != null) mMarkers.addAll(freqs);
        if (colors != null) mMarkerColors.addAll(colors);
        invalidate();
    }

    private float xFor(float freq, float usableW, float padL) {
        float t = (freq - LOW) / SPAN;
        if (t < 0) t = 0;
        if (t > 1) t = 1;
        return padL + t * usableW;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;

        float padL = 10 * mDensity;
        float usableW = w - 2 * padL;
        float axisY = h * 0.52f;
        mLastPadL = padL;
        mLastUsableW = usableW;

        // base line
        mLine.setStrokeWidth(2 * mDensity);
        canvas.drawLine(padL, axisY, padL + usableW, axisY, mLine);

        // ticks + labels (integer MHz; major every 4)
        for (int f = 88; f <= 108; f++) {
            boolean major = (f % 4 == 0);
            float x = xFor(f, usableW, padL);
            float th = (major ? 18 : 10) * mDensity;
            mTick.setStrokeWidth((major ? 1.6f : 1f) * mDensity);
            canvas.drawLine(x, axisY - th / 2, x, axisY + th / 2, mTick);
            if (major) {
                canvas.drawText(String.valueOf(f), x, axisY + th / 2 + 16 * mDensity, mLabel);
            }
        }

        // station markers
        float dotR = 4 * mDensity;
        for (int i = 0; i < mMarkers.size(); i++) {
            float x = xFor(mMarkers.get(i)[0], usableW, padL);
            int color = i < mMarkerColors.size() ? mMarkerColors.get(i) : 0xFF8A9099;
            mMarker.setColor(color);
            canvas.drawCircle(x, axisY - 14 * mDensity, dotR, mMarker);
        }

        // needle
        float nx = xFor(mCurrentFreq, usableW, padL);
        mNeedle.setColor(mAccent);
        mNeedle.setStrokeWidth(2.5f * mDensity);
        canvas.drawLine(nx, 6 * mDensity, nx, h - 6 * mDensity, mNeedle);
        canvas.drawCircle(nx, 6 * mDensity, 6 * mDensity, mNeedle);
        // soft glow dot
        mNeedle.setColor(UiAlpha(mAccent, 0.25f));
        canvas.drawCircle(nx, 6 * mDensity, 11 * mDensity, mNeedle);
    }

    private static int UiAlpha(int color, float a) {
        return (color & 0x00FFFFFF) | (Math.round(a * 255f) << 24);
    }
}

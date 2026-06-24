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
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * A small "now playing" equalizer indicator: a row of vertical bars whose heights gently
 * oscillate while {@link #setPlaying(boolean) playing}, and rest low when paused. Purely
 * decorative — it is not driven by real audio levels, just animated so the active station reads
 * as live. Used in the Now Playing DLS card and the active Browse row.
 */
public class EqualizerView extends View {
    private static final int BARS = 4;
    private static final long PERIOD_MS = 900L;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    private final float[] mPhase = new float[BARS];

    private int mAccent = 0xFF3DD2E6;
    private boolean mPlaying;

    public EqualizerView(Context context) {
        this(context, null);
    }

    public EqualizerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mPaint.setStyle(Paint.Style.FILL);
        // Spread the bars' phases so they don't move in lockstep.
        for (int i = 0; i < BARS; i++) {
            mPhase[i] = (float) (i * Math.PI / 2.3);
        }
    }

    /** Accent color of the bars. */
    public void setAccent(int color) {
        if (mAccent == color) return;
        mAccent = color;
        invalidate();
    }

    /** Start (true) or stop (false) the animation. When stopped the bars sit at a low rest. */
    public void setPlaying(boolean playing) {
        if (mPlaying == playing) return;
        mPlaying = playing;
        if (mPlaying) {
            postInvalidateOnAnimation();
        } else {
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        float gap = w * 0.12f;
        float barW = (w - gap * (BARS - 1)) / BARS;
        float radius = barW * 0.45f;
        // Animate from a wall-clock phase so heights move smoothly without an Animator.
        float t = (System.currentTimeMillis() % PERIOD_MS) / (float) PERIOD_MS * 2f
                * (float) Math.PI;

        mPaint.setColor(mAccent);
        for (int i = 0; i < BARS; i++) {
            float frac;
            if (mPlaying) {
                frac = 0.35f + 0.65f * (0.5f + 0.5f * (float) Math.sin(t + mPhase[i]));
            } else {
                frac = 0.30f; // resting bars
            }
            float barH = Math.max(radius * 2f, h * frac);
            float left = i * (barW + gap);
            float top = h - barH;
            mRect.set(left, top, left + barW, h);
            canvas.drawRoundRect(mRect, radius, radius, mPaint);
        }

        if (mPlaying && isAttachedToWindow() && getVisibility() == VISIBLE) {
            postInvalidateOnAnimation();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mPlaying) postInvalidateOnAnimation();
    }
}

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
 * A compact 4-bar signal-strength meter. Filled bars are drawn in the band accent color, the
 * remaining bars in a dim "off" color. Heights step up left-to-right.
 */
public class SignalMeter extends View {
    private static final int OFF_COLOR = 0xFF363B41;
    private static final float[] HEIGHTS = {0.40f, 0.58f, 0.78f, 1.00f};

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();

    private int mLevel = 0;          // 0..4
    private int mAccent = 0xFF3DD2E6;

    public SignalMeter(Context context) {
        this(context, null);
    }

    public SignalMeter(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /** @param level filled bars (0..4); @param accent color for filled bars. */
    public void setLevel(int level, int accent) {
        mLevel = Math.max(0, Math.min(4, level));
        mAccent = accent;
        invalidate();
    }

    public void setAccent(int accent) {
        mAccent = accent;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final int n = HEIGHTS.length;
        final float w = getWidth();
        final float h = getHeight();
        if (w <= 0 || h <= 0) return;

        final float gap = w * 0.18f / (n - 1);
        final float barW = (w - gap * (n - 1)) / n;
        final float radius = barW * 0.35f;

        for (int i = 0; i < n; i++) {
            float bh = h * HEIGHTS[i];
            float left = i * (barW + gap);
            float top = h - bh;
            mRect.set(left, top, left + barW, h);
            mPaint.setColor(i < mLevel ? mAccent : OFF_COLOR);
            canvas.drawRoundRect(mRect, radius, radius, mPaint);
        }
    }
}

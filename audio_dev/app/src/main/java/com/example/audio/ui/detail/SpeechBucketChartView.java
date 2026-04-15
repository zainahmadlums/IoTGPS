package com.example.audio.ui.detail;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import com.example.audio.R;
import com.example.audio.data.SessionSpeechInterval;

import java.util.ArrayList;
import java.util.List;

public final class SpeechBucketChartView extends View {

    private static final int DEFAULT_BUCKET_COUNT = 16;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final List<Float> bucketValues = new ArrayList<>();

    public SpeechBucketChartView(Context context) {
        super(context);
        init();
    }

    public SpeechBucketChartView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public SpeechBucketChartView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        trackPaint.setColor(getContext().getColor(R.color.dt_surface_soft));
        barPaint.setColor(getContext().getColor(R.color.dt_emerald));
    }

    public void setData(List<SessionSpeechInterval> intervals, long durationMillis) {
        bucketValues.clear();
        for (int index = 0; index < DEFAULT_BUCKET_COUNT; index++) {
            bucketValues.add(0.0f);
        }

        long safeDuration = Math.max(1L, durationMillis);
        float bucketWidthMillis = safeDuration / (float) DEFAULT_BUCKET_COUNT;
        for (SessionSpeechInterval interval : intervals) {
            for (int bucketIndex = 0; bucketIndex < DEFAULT_BUCKET_COUNT; bucketIndex++) {
                float bucketStart = bucketIndex * bucketWidthMillis;
                float bucketEnd = bucketStart + bucketWidthMillis;
                float overlapStart = Math.max(bucketStart, interval.getStartOffsetMillis());
                float overlapEnd = Math.min(bucketEnd, interval.getEndOffsetMillis());
                float overlap = Math.max(0f, overlapEnd - overlapStart);
                if (overlap > 0f) {
                    float bucketRatio = Math.min(1.0f, bucketValues.get(bucketIndex) + (overlap / bucketWidthMillis));
                    bucketValues.set(bucketIndex, bucketRatio);
                }
            }
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bucketValues.isEmpty()) {
            return;
        }

        float width = getWidth();
        float height = getHeight();
        float gap = getResources().getDisplayMetrics().density * 6f;
        float barWidth = (width - (gap * (bucketValues.size() - 1))) / bucketValues.size();

        for (int index = 0; index < bucketValues.size(); index++) {
            float left = index * (barWidth + gap);
            rect.set(left, 0f, left + barWidth, height);
            canvas.drawRoundRect(rect, barWidth / 2f, barWidth / 2f, trackPaint);

            float barHeight = Math.max(barWidth, bucketValues.get(index) * height);
            rect.set(left, height - barHeight, left + barWidth, height);
            canvas.drawRoundRect(rect, barWidth / 2f, barWidth / 2f, barPaint);
        }
    }
}

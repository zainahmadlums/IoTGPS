package com.example.audio.ui.detail;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import com.example.audio.R;
import com.example.audio.data.SessionRoleInterval;
import com.example.audio.data.SessionSpeechInterval;
import com.example.audio.data.SpeakerRole;

import java.util.ArrayList;
import java.util.List;

public final class SpeechTimelineView extends View {

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint speechPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint instructorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint studentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bothPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final List<SessionSpeechInterval> intervals = new ArrayList<>();
    private final List<SessionRoleInterval> roleIntervals = new ArrayList<>();
    private long durationMillis = 1L;

    public SpeechTimelineView(Context context) {
        super(context);
        init();
    }

    public SpeechTimelineView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public SpeechTimelineView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        trackPaint.setColor(getContext().getColor(R.color.dt_surface_soft));
        speechPaint.setColor(getContext().getColor(R.color.dt_cyan));
        instructorPaint.setColor(getContext().getColor(R.color.dt_cyan));
        studentPaint.setColor(getContext().getColor(R.color.dt_emerald));
        bothPaint.setColor(getContext().getColor(R.color.dt_warning));
    }

    public void setData(List<SessionSpeechInterval> speechIntervals, long durationMillis) {
        intervals.clear();
        if (speechIntervals != null) {
            intervals.addAll(speechIntervals);
        }
        roleIntervals.clear();
        this.durationMillis = Math.max(1L, durationMillis);
        invalidate();
    }

    public void setRoleData(List<SessionRoleInterval> sessionRoleIntervals, long durationMillis) {
        roleIntervals.clear();
        if (sessionRoleIntervals != null) {
            roleIntervals.addAll(sessionRoleIntervals);
        }
        intervals.clear();
        this.durationMillis = Math.max(1L, durationMillis);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        float radius = height / 2f;

        rect.set(0f, 0f, width, height);
        canvas.drawRoundRect(rect, radius, radius, trackPaint);

        if (!roleIntervals.isEmpty()) {
            for (SessionRoleInterval interval : roleIntervals) {
                if (interval.getRole() == SpeakerRole.SILENCE) {
                    continue;
                }
                drawInterval(canvas, interval.getStartOffsetMillis(), interval.getEndOffsetMillis(), paintForRole(interval.getRole()), width, height, radius);
            }
            return;
        }

        for (SessionSpeechInterval interval : intervals) {
            drawInterval(canvas, interval.getStartOffsetMillis(), interval.getEndOffsetMillis(), speechPaint, width, height, radius);
        }
    }

    private void drawInterval(
            Canvas canvas,
            long startOffsetMillis,
            long endOffsetMillis,
            Paint paint,
            float width,
            float height,
            float radius
    ) {
        float left = (startOffsetMillis / (float) durationMillis) * width;
        float right = (endOffsetMillis / (float) durationMillis) * width;
        rect.set(left, 0f, Math.max(left + 2f, right), height);
        canvas.drawRoundRect(rect, radius, radius, paint);
    }

    private Paint paintForRole(SpeakerRole speakerRole) {
        if (speakerRole == SpeakerRole.INSTRUCTOR) {
            return instructorPaint;
        }
        if (speakerRole == SpeakerRole.BOTH) {
            return bothPaint;
        }
        return studentPaint;
    }
}

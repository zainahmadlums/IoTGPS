package com.example.iotgps;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.location.Location;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.LinkedList;

public class IndoorMapCanvas extends View {

    private Paint gridPaint, bluePaint, greyPaint, darkBluePaint, redPaint;
    private LinkedList<Location> locations = new LinkedList<>();
    private Location originLocation = null;

    private float scaleFactor = 1.0f;
    private float translateX = 0f;
    private float translateY = 0f;
    private final float pixelsPerMeter = 50f;

    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;

    public IndoorMapCanvas(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        gridPaint = new Paint();
        gridPaint.setColor(Color.LTGRAY);
        gridPaint.setStrokeWidth(2f);

        bluePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bluePaint.setColor(Color.BLUE);

        greyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        greyPaint.setColor(Color.parseColor("#80808080"));

        darkBluePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        darkBluePaint.setColor(Color.parseColor("#00008B"));

        redPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        redPaint.setColor(Color.RED);

        scaleDetector = new ScaleGestureDetector(getContext(), new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                scaleFactor *= detector.getScaleFactor();
                scaleFactor = Math.max(0.1f, Math.min(scaleFactor, 10.0f));
                invalidate();
                return true;
            }
        });

        gestureDetector = new GestureDetector(getContext(), new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                translateX -= distanceX;
                translateY -= distanceY;
                invalidate();
                return true;
            }
        });
    }

    public void setLocations(LinkedList<Location> newLocations) {
        this.locations = new LinkedList<>(newLocations);
        invalidate();
    }

    public void setOriginAndCenter(Location loc) {
        this.originLocation = loc;
        translateX = 0;
        translateY = 0;
        scaleFactor = 1.0f;
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float centerX = getWidth() / 2f;
        float centerY = getHeight() / 2f;

        canvas.save();
        canvas.translate(centerX + translateX, centerY + translateY);
        canvas.scale(scaleFactor, scaleFactor);

        drawGrid(canvas);

        if (originLocation != null) {
            canvas.drawCircle(0, 0, 8, redPaint);
        }

        if (locations.isEmpty() || originLocation == null) {
            canvas.restore();
            return;
        }

        double sumLat = 0, sumLon = 0;
        int count = 0;

        for (int i = 0; i < locations.size(); i++) {
            Location loc = locations.get(i);

            float[] results = new float[1];
            Location.distanceBetween(originLocation.getLatitude(), originLocation.getLongitude(), originLocation.getLatitude(), loc.getLongitude(), results);
            float xOffset = results[0] * (loc.getLongitude() > originLocation.getLongitude() ? 1 : -1) * pixelsPerMeter;

            Location.distanceBetween(originLocation.getLatitude(), originLocation.getLongitude(), loc.getLatitude(), originLocation.getLongitude(), results);
            float yOffset = results[0] * (loc.getLatitude() > originLocation.getLatitude() ? -1 : 1) * pixelsPerMeter;

            if (i == locations.size() - 1) {
                canvas.drawCircle(xOffset, yOffset, 12, bluePaint);
            } else {
                canvas.drawCircle(xOffset, yOffset, 10, greyPaint);
            }

            sumLat += loc.getLatitude();
            sumLon += loc.getLongitude();
            count++;
        }

        double avgLat = sumLat / count;
        double avgLon = sumLon / count;

        float[] avgRes = new float[1];
        Location.distanceBetween(originLocation.getLatitude(), originLocation.getLongitude(), originLocation.getLatitude(), avgLon, avgRes);
        float avgX = avgRes[0] * (avgLon > originLocation.getLongitude() ? 1 : -1) * pixelsPerMeter;

        Location.distanceBetween(originLocation.getLatitude(), originLocation.getLongitude(), avgLat, originLocation.getLongitude(), avgRes);
        float avgY = avgRes[0] * (avgLat > originLocation.getLatitude() ? -1 : 1) * pixelsPerMeter;

        canvas.drawCircle(avgX, avgY, 14, darkBluePaint);

        canvas.restore();
    }

    private void drawGrid(Canvas canvas) {
        int gridRange = 2000;
        for (int i = -gridRange; i <= gridRange; i += pixelsPerMeter) {
            canvas.drawLine(i, -gridRange, i, gridRange, gridPaint);
            canvas.drawLine(-gridRange, i, gridRange, i, gridPaint);
        }
    }
}
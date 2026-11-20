package app.workusage.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

import java.util.List;
import java.util.concurrent.TimeUnit;

final class UsageHistoryChart extends View {
    private static final long MAX_CONNECTED_GAP = TimeUnit.HOURS.toMillis(2);
    private final List<UsageHistory.Point> points;
    private final long start;
    private final long end;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private final int mint;
    private final int blue;
    private final int grid;
    private final int label;

    UsageHistoryChart(Context context, List<UsageHistory.Point> points, long now) {
        super(context);
        this.points = points;
        start = Math.min(points.get(0).time, now - TimeUnit.HOURS.toMillis(1));
        end = now;
        density = getResources().getDisplayMetrics().density;
        mint = context.getColor(R.color.mint);
        blue = context.getColor(R.color.blue);
        grid = context.getColor(R.color.track);
        label = context.getColor(R.color.muted);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float left = 36 * density;
        float right = getWidth() - 8 * density;
        float top = 10 * density;
        float bottom = getHeight() - 25 * density;
        paint.setStrokeWidth(density);
        paint.setTextSize(10 * density);
        paint.setTextAlign(Paint.Align.LEFT);
        for (int percentage = 0; percentage <= 100; percentage += 50) {
            float y = plotY(percentage, top, bottom);
            paint.setColor(grid);
            canvas.drawLine(left, y, right, y, paint);
            paint.setColor(label);
            canvas.drawText(percentage + "%", 0, y + 3 * density, paint);
        }

        drawReadings(canvas, false, left, right, top, bottom, mint);
        drawReadings(canvas, true, left, right, top, bottom, blue);
        paint.setColor(label);
        float textY = getHeight() - 5 * density;
        canvas.drawText(UsageSnapshot.timeLabel(getContext(), start, end, true), left, textY, paint);
        paint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(getContext().getString(R.string.history_now), right, textY, paint);
    }

    private void drawReadings(Canvas canvas, boolean weekly, float left, float right,
                              float top, float bottom, int color) {
        long previousTime = 0;
        float previousX = 0;
        float previousY = 0;
        paint.setColor(color);
        paint.setStrokeWidth(2 * density);
        paint.setStrokeCap(Paint.Cap.ROUND);
        for (UsageHistory.Point point : points) {
            Double value = weekly ? point.week : point.fiveHours;
            if (value == null) {
                previousTime = 0;
                continue;
            }
            float x = left + (right - left) * (point.time - start) / (end - start);
            float y = plotY(value, top, bottom);
            if (previousTime != 0 && point.time - previousTime <= MAX_CONNECTED_GAP) {
                canvas.drawLine(previousX, previousY, x, y, paint);
            }
            canvas.drawCircle(x, y, 2 * density, paint);
            previousTime = point.time;
            previousX = x;
            previousY = y;
        }
    }

    private float plotY(double percentage, float top, float bottom) {
        return bottom - (float) (percentage / 100) * (bottom - top);
    }
}

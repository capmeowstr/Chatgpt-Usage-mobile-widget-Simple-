package app.workusage.widget;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class UsageHistory {
    static final long RETENTION_MS = TimeUnit.DAYS.toMillis(7);
    private static final long BUCKET_MS = TimeUnit.MINUTES.toMillis(15);
    private static final String POINTS = "readings";

    static synchronized void record(Context context, UsageSnapshot snapshot) {
        long time = snapshot.fetchedAt;
        Double fiveHours = remaining(snapshot.fiveHours, time);
        Double week = remaining(snapshot.week, time);
        if (fiveHours == null && week == null) {
            return;
        }
        List<Point> points = recent(context, time);
        int last = points.size() - 1;
        if (last >= 0 && points.get(last).time / BUCKET_MS == time / BUCKET_MS) {
            points.remove(last);
        }
        points.add(new Point(time, fiveHours, week, snapshot.manuallyEntered));
        save(context, points);
    }

    static synchronized List<Point> recent(Context context, long now) {
        List<Point> points = new ArrayList<>();
        String saved = preferences(context).getString(POINTS, null);
        if (saved == null) {
            return points;
        }
        try {
            JSONArray entries = new JSONArray(saved);
            for (int i = 0; i < entries.length(); i++) {
                JSONArray entry = entries.optJSONArray(i);
                if (entry == null) {
                    continue;
                }
                long time = entry.optLong(0, 0);
                if (time > 0 && time >= now - RETENTION_MS && time <= now) {
                    points.add(new Point(time, percentage(entry.optDouble(1)),
                            percentage(entry.optDouble(2)), entry.optBoolean(3)));
                }
            }
            if (points.size() != entries.length()) {
                save(context, points);
            }
        } catch (JSONException ignored) {
            // A damaged history should never prevent usage sync.
        }
        return points;
    }

    static synchronized void clearSynced(Context context) {
        List<Point> points = recent(context, System.currentTimeMillis());
        points.removeIf(point -> !point.manual);
        save(context, points);
    }

    static synchronized void clear(Context context) {
        preferences(context).edit().remove(POINTS).apply();
    }

    private static Double remaining(UsageSnapshot.Window window, long time) {
        return window == null || window.expired(time) ? null : percentage(window.remaining);
    }

    private static Double percentage(double value) {
        return Double.isFinite(value) && value >= 0 && value <= 100 ? value : null;
    }

    private static void save(Context context, List<Point> points) {
        JSONArray entries = new JSONArray();
        for (Point point : points) {
            entries.put(new JSONArray().put(point.time)
                    .put(point.fiveHours == null ? JSONObject.NULL : point.fiveHours)
                    .put(point.week == null ? JSONObject.NULL : point.week)
                    .put(point.manual));
        }
        preferences(context).edit().putString(POINTS, entries.toString()).apply();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("usage_history", Context.MODE_PRIVATE);
    }

    static final class Point {
        final long time;
        final Double fiveHours;
        final Double week;
        final boolean manual;

        Point(long time, Double fiveHours, Double week, boolean manual) {
            this.time = time;
            this.fiveHours = fiveHours;
            this.week = week;
            this.manual = manual;
        }
    }
}

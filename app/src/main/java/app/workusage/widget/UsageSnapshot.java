package app.workusage.widget;

import android.content.Context;
import android.text.format.DateFormat;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

final class UsageSnapshot {
    private static final long FIVE_HOURS_SECONDS = 5 * 60 * 60;
    private static final long WEEK_SECONDS = 7 * 24 * 60 * 60;
    final Window fiveHours;
    final Window week;
    final Integer earnedResets;
    final long fetchedAt;
    final boolean manuallyEntered;

    UsageSnapshot(Window fiveHours, Window week, Integer earnedResets, long fetchedAt) {
        this(fiveHours, week, earnedResets, fetchedAt, false);
    }

    private UsageSnapshot(Window fiveHours, Window week, Integer earnedResets, long fetchedAt,
                          boolean manuallyEntered) {
        this.fiveHours = fiveHours;
        this.week = week;
        this.earnedResets = earnedResets;
        this.fetchedAt = fetchedAt;
        this.manuallyEntered = manuallyEntered;
    }

    static UsageSnapshot manual(Double fiveHours, Double week, Integer resets, long fetchedAt) {
        return new UsageSnapshot(fiveHours == null ? null : new Window(fiveHours, 0),
                week == null ? null : new Window(week, 0), resets, fetchedAt, true);
    }

    static UsageSnapshot fromUsage(JSONObject payload, long fetchedAt) throws JSONException {
        if (!payload.has("rate_limit") && !payload.has("plan_type")) {
            throw new JSONException("Missing usage data");
        }
        JSONObject meter = payload.optJSONObject("rate_limit");
        Window fiveHours = findWindow(meter, FIVE_HOURS_SECONDS, fetchedAt);
        Window week = findWindow(meter, WEEK_SECONDS, fetchedAt);
        Integer earnedResets = readResets(payload.optJSONObject("rate_limit_reset_credits"));
        return new UsageSnapshot(fiveHours, week, earnedResets, fetchedAt);
    }

    private static Window findWindow(JSONObject meter, long duration, long fetchedAt) {
        if (meter == null) {
            return null;
        }
        for (String key : new String[]{"primary_window", "secondary_window"}) {
            JSONObject window = meter.optJSONObject(key);
            if (window == null || window.optLong("limit_window_seconds") != duration) {
                continue;
            }
            double used = window.optDouble("used_percent", Double.NaN);
            if (!Double.isFinite(used) || used < 0) {
                continue;
            }
            long resetAtSeconds = window.optLong("reset_at", -1);
            long resetInSeconds = window.optLong("reset_after_seconds", -1);
            long resetsAt = 0;
            if (resetAtSeconds > 0 && resetAtSeconds < Long.MAX_VALUE / 1000) {
                resetsAt = resetAtSeconds * 1000;
            } else if (resetInSeconds > 0 && resetInSeconds <= duration) {
                resetsAt = fetchedAt + resetInSeconds * 1000;
            }
            return new Window(Math.max(0, 100 - used), resetsAt);
        }
        return null;
    }

    static Integer readResets(JSONObject credits) {
        if (credits == null) {
            return null;
        }
        long count = credits.optLong("available_count", -1);
        return count >= 0 && count <= Integer.MAX_VALUE ? (int) count : null;
    }

    UsageSnapshot withResets(Integer count) {
        return new UsageSnapshot(fiveHours, week, count, fetchedAt, manuallyEntered);
    }

    JSONObject toJson() throws JSONException {
        return new JSONObject()
                .put("fiveHours", fiveHours == null ? JSONObject.NULL : fiveHours.toJson())
                .put("week", week == null ? JSONObject.NULL : week.toJson())
                .put("earnedResets", earnedResets == null ? JSONObject.NULL : earnedResets)
                .put("manual", manuallyEntered)
                .put("fetchedAt", fetchedAt);
    }

    static UsageSnapshot fromCache(String stored) {
        if (stored == null) {
            return null;
        }
        try {
            JSONObject data = new JSONObject(stored);
            Integer count = data.has("earnedResets") && !data.isNull("earnedResets")
                    ? data.getInt("earnedResets") : null;
            return new UsageSnapshot(Window.fromCache(data.optJSONObject("fiveHours")),
                    Window.fromCache(data.optJSONObject("week")), count, data.getLong("fetchedAt"),
                    data.optBoolean("manual"));
        } catch (JSONException e) {
            return null;
        }
    }

    static String percentage(double remaining) {
        return remaining > 0 && remaining < 1 ? "<1%" : (int) Math.floor(remaining) + "%";
    }

    static String timeLabel(Context context, long when, long now, boolean compact) {
        Calendar date = Calendar.getInstance();
        date.setTimeInMillis(when);
        Calendar today = Calendar.getInstance();
        today.setTimeInMillis(now);
        Date instant = date.getTime();
        String time = DateFormat.getTimeFormat(context).format(instant);
        if (date.get(Calendar.YEAR) == today.get(Calendar.YEAR)
                && date.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)) {
            return time;
        }
        return new SimpleDateFormat(compact ? "EEE" : "EEE, d MMM", Locale.getDefault())
                .format(instant) + " " + time;
    }

    static final class Window {
        final double remaining;
        final long resetsAt;

        Window(double remaining, long resetsAt) {
            this.remaining = remaining;
            this.resetsAt = resetsAt;
        }

        boolean expired(long now) {
            return resetsAt > 0 && resetsAt <= now;
        }

        String percent(long now) {
            if (expired(now)) {
                return "—";
            }
            return percentage(remaining);
        }

        int progress(long now) {
            return expired(now) ? 0 : (int) Math.floor(remaining);
        }

        String detail(Context context, long now, boolean compact) {
            if (expired(now)) {
                return compact ? "Refresh needed" : "Refresh for new limit";
            }
            if (resetsAt == 0) {
                return compact ? "Time unavailable" : "Reset time unavailable";
            }
            String time = timeLabel(context, resetsAt, now, compact);
            return compact ? "↻ " + time : "Resets " + time;
        }

        String timeToReset(long now) {
            if (resetsAt <= now) {
                return "";
            }
            long minutes = (resetsAt - now - 1) / 60_000 + 1;
            long hours = minutes / 60;
            if (hours >= 24) {
                return (hours / 24) + "d " + (hours % 24) + "h";
            }
            return hours == 0 ? minutes + "m" : hours + "h " + (minutes % 60) + "m";
        }

        private JSONObject toJson() throws JSONException {
            return new JSONObject().put("remaining", remaining).put("resetsAt", resetsAt);
        }

        private static Window fromCache(JSONObject value) throws JSONException {
            return value == null ? null : new Window(value.getDouble("remaining"),
                    value.getLong("resetsAt"));
        }
    }
}

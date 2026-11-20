package app.workusage.widget;

import android.content.Context;
import android.content.SharedPreferences;

final class SessionPlan {
    private static final long FRESH_FOR_MS = 30 * 60_000L;
    private static final String DURATION = "duration_minutes";
    private static final String RESERVE = "reserve_percent";

    static final int[] DURATIONS = {30, 60, 90, 120};
    static final int[] RESERVES = {10, 20, 30};

    enum Source { EMPTY, SYNCED, SAVED, MANUAL }
    enum Timing { UNKNOWN, START, INSIDE, END, AFTER }

    final Source source;
    final UsageSnapshot.Window fiveHours;
    final UsageSnapshot.Window week;
    final long nextReset;
    final long start;
    final long end;

    SessionPlan(UsageSnapshot snapshot, boolean live, long now, int duration, boolean atReset) {
        source = sourceOf(snapshot, live, now);
        fiveHours = available(snapshot == null ? null : snapshot.fiveHours, now);
        week = available(snapshot == null ? null : snapshot.week, now);
        long fiveReset = reset(fiveHours);
        long weekReset = reset(week);
        nextReset = fiveReset == 0 ? weekReset : weekReset == 0 ? fiveReset : Math.min(fiveReset, weekReset);
        start = atReset && nextReset > 0 ? nextReset : now;
        long length = duration * 60_000L;
        end = Math.min(start, Long.MAX_VALUE - length) + length;
    }

    private static Source sourceOf(UsageSnapshot snapshot, boolean live, long now) {
        if (snapshot == null) {
            return Source.EMPTY;
        }
        if (snapshot.manuallyEntered) {
            return Source.MANUAL;
        }
        return live && snapshot.fetchedAt > 0 && snapshot.fetchedAt <= now
                && now - snapshot.fetchedAt <= FRESH_FOR_MS ? Source.SYNCED : Source.SAVED;
    }

    private static UsageSnapshot.Window available(UsageSnapshot.Window window, long now) {
        if (window == null || window.expired(now) || !Double.isFinite(window.remaining)
                || window.remaining < 0 || window.remaining > 100) {
            return null;
        }
        return window;
    }

    long reset(UsageSnapshot.Window window) {
        return source == Source.SYNCED && window != null && window.resetsAt > 0 ? window.resetsAt : 0;
    }

    Timing timing(UsageSnapshot.Window window) {
        long reset = reset(window);
        if (reset == 0) {
            return Timing.UNKNOWN;
        }
        if (reset == start) {
            return Timing.START;
        }
        if (reset < end) {
            return Timing.INSIDE;
        }
        return reset == end ? Timing.END : Timing.AFTER;
    }

    UsageSnapshot.Window lowerAllowance() {
        if (fiveHours == null) {
            return week;
        }
        if (week == null || fiveHours.remaining < week.remaining) {
            return fiveHours;
        }
        if (week.remaining < fiveHours.remaining) {
            return week;
        }
        return reset(week) > 0 && (reset(fiveHours) == 0 || reset(week) < reset(fiveHours))
                ? week : fiveHours;
    }

    static int duration(Context context) {
        return option(preferences(context).getInt(DURATION, 60), DURATIONS, 60);
    }

    static int reserve(Context context) {
        return option(preferences(context).getInt(RESERVE, 20), RESERVES, 20);
    }

    static void save(Context context, int duration, int reserve) {
        preferences(context).edit().putInt(DURATION, duration).putInt(RESERVE, reserve).apply();
        UsageWidget.renderAll(context);
    }

    private static int option(int value, int[] options, int fallback) {
        for (int option : options) {
            if (option == value) {
                return value;
            }
        }
        return fallback;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("session_plan", Context.MODE_PRIVATE);
    }
}

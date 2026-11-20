package app.workusage.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.widget.RemoteViews;

import java.util.Date;

public class UsageWidget extends AppWidgetProvider {
    private static final String REFRESH = "app.workusage.widget.REFRESH";
    private static final int FLAGS = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;

    enum Variant {
        FULL(UsageWidget.class, R.string.widget_pick_full),
        FIVE_HOUR(FiveHour.class, R.string.widget_pick_five_hour),
        WEEKLY(Weekly.class, R.string.widget_pick_weekly),
        SESSION(Session.class, R.string.widget_pick_session);

        private final Class<? extends UsageWidget> provider;
        final int pickerLabel;

        Variant(Class<? extends UsageWidget> provider, int pickerLabel) {
            this.provider = provider;
            this.pickerLabel = pickerLabel;
        }

        ComponentName component(Context context) {
            return new ComponentName(context, provider);
        }
    }

    Variant variant() {
        return Variant.FULL;
    }

    public static final class FiveHour extends UsageWidget {
        @Override
        Variant variant() {
            return Variant.FIVE_HOUR;
        }
    }

    public static final class Weekly extends UsageWidget {
        @Override
        Variant variant() {
            return Variant.WEEKLY;
        }
    }

    public static final class Session extends UsageWidget {
        @Override
        Variant variant() {
            return Variant.SESSION;
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (REFRESH.equals(intent.getAction())) {
            if (UsageRepository.get(context).connected()) {
                SyncJob.requestFromWidget(context);
            }
            return;
        }
        super.onReceive(context, intent);
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        manager.updateAppWidget(ids, remoteViews(context, variant()));
        if (UsageRepository.get(context).connected()) {
            SyncJob.schedulePeriodic(context);
            SyncJob.requestFromWidget(context);
        }
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager,
                                          int widgetId, Bundle newOptions) {
        manager.updateAppWidget(widgetId, remoteViews(context, variant()));
    }

    @Override
    public void onDisabled(Context context) {
        if (!hasWidgets(context)) {
            SyncJob.cancel(context);
        }
    }

    static boolean hasWidgets(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        for (Variant variant : Variant.values()) {
            if (manager.getAppWidgetIds(variant.component(context)).length > 0) {
                return true;
            }
        }
        return false;
    }

    static void renderAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        for (Variant variant : Variant.values()) {
            int[] ids = manager.getAppWidgetIds(variant.component(context));
            if (ids.length > 0) {
                manager.updateAppWidget(ids, remoteViews(context, variant));
            }
        }
    }

    private static RemoteViews remoteViews(Context context, Variant variant) {
        UsageRepository repository = UsageRepository.get(context);
        UsageSnapshot snapshot = repository.snapshot();
        long now = System.currentTimeMillis();
        boolean connected = repository.connected();
        RemoteViews views = display(context, WidgetAppearance.load(context), variant, snapshot, now,
                connected && repository.error() == null);

        Intent appIntent = new Intent(context, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openApp = PendingIntent.getActivity(context, 201, appIntent, FLAGS);
        Intent refreshIntent = new Intent(context, UsageWidget.class).setAction(REFRESH);
        PendingIntent refresh = PendingIntent.getBroadcast(context, 202, refreshIntent, FLAGS);
        if (variant == Variant.FULL) {
            views.setTextViewText(R.id.widget_sync, footer(context, repository, snapshot, now));
            views.setOnClickPendingIntent(R.id.widget_open, openApp);
            views.setOnClickPendingIntent(R.id.widget_usage, openApp);
            views.setOnClickPendingIntent(R.id.widget_footer, openApp);
        } else if (variant == Variant.SESSION) {
            Intent planIntent = new Intent(context, SessionPlannerActivity.class)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent planner = PendingIntent.getActivity(context, 203, planIntent, FLAGS);
            views.setOnClickPendingIntent(R.id.widget_compact_body, planner);
        } else {
            views.setOnClickPendingIntent(R.id.widget_compact_body, openApp);
        }
        views.setOnClickPendingIntent(R.id.widget_refresh, connected ? refresh : openApp);
        views.setContentDescription(R.id.widget_refresh,
                context.getString(connected ? R.string.refresh_usage : R.string.open_work_usage));
        return views;
    }

    static RemoteViews preview(Context context, WidgetAppearance appearance, Variant variant) {
        long now = System.currentTimeMillis();
        UsageSnapshot sample = new UsageSnapshot(
                new UsageSnapshot.Window(72, now + 2 * 60 * 60_000L),
                new UsageSnapshot.Window(38, now + 3 * 24 * 60 * 60_000L), 2, now);
        if (variant == Variant.SESSION) {
            sample = new UsageSnapshot(new UsageSnapshot.Window(12, now + 40 * 60_000L),
                    new UsageSnapshot.Window(62, now + 2 * 24 * 60 * 60_000L), 2, now);
        }
        RemoteViews views = display(context, appearance, variant, sample, now, true);
        if (variant == Variant.FULL) {
            views.setTextViewText(R.id.widget_sync, context.getString(R.string.widget_style_sample));
        }
        views.setContentDescription(R.id.widget_refresh, null);
        return views;
    }

    private static RemoteViews display(Context context, WidgetAppearance appearance, Variant variant,
                                       UsageSnapshot snapshot, long now, boolean live) {
        if (variant == Variant.SESSION) {
            return displaySession(context, appearance, snapshot, now, live);
        }
        if (variant != Variant.FULL) {
            boolean weekly = variant == Variant.WEEKLY;
            UsageSnapshot.Window window = snapshot == null ? null : (weekly ? snapshot.week : snapshot.fiveHours);
            return displayCompact(context, appearance, window, now, weekly);
        }
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.usage_widget);
        appearance.apply(views);
        bindWindow(context, views, snapshot == null ? null : snapshot.fiveHours,
                R.id.widget_short_percent, R.id.widget_short_bar, R.id.widget_short_reset, now);
        bindWindow(context, views, snapshot == null ? null : snapshot.week,
                R.id.widget_week_percent, R.id.widget_week_bar, R.id.widget_week_reset, now);
        views.setTextViewText(R.id.widget_credits, "Resets left: "
                + (snapshot == null || snapshot.earnedResets == null ? "—" : snapshot.earnedResets));
        return views;
    }

    private static RemoteViews displayCompact(Context context, WidgetAppearance appearance,
                                              UsageSnapshot.Window window, long now, boolean weekly) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.usage_widget_compact);
        appearance.applyCompact(views, weekly);
        views.setTextViewText(R.id.widget_compact_label, context.getString(
                weekly ? R.string.widget_compact_weekly : R.string.widget_compact_five_hour));

        boolean showValue = window != null && !window.expired(now);
        String value = showValue ? window.percent(now) : context.getString(R.string.unknown);
        views.setTextViewText(R.id.widget_compact_value, value);
        views.setInt(R.id.widget_compact_bar, "setImageLevel", showValue ? window.progress(now) * 100 : 0);
        String limit = context.getString(weekly ? R.string.weekly : R.string.five_hour);
        views.setContentDescription(R.id.widget_compact_body, showValue
                ? context.getString(R.string.widget_compact_summary, limit, value)
                : context.getString(R.string.widget_compact_unavailable, limit));
        return views;
    }

    private static RemoteViews displaySession(Context context, WidgetAppearance appearance,
                                              UsageSnapshot snapshot, long now, boolean live) {
        int duration = SessionPlan.duration(context);
        SessionPlan plan = new SessionPlan(snapshot, live, now, duration, false);
        UsageSnapshot.Window focus = plan.lowerAllowance();
        boolean weekly = focus != null && focus == plan.week;
        boolean belowReserve = focus != null && focus.remaining < SessionPlan.reserve(context);
        RemoteViews views = displayCompact(context, appearance, focus, now, weekly);
        if (belowReserve) {
            appearance.highlightBelowReserve(views);
        }
        String label;
        if (focus == null) {
            label = context.getString(R.string.widget_session_empty);
        } else {
            String period = context.getString(weekly ? R.string.session_short_week : R.string.session_short_five);
            SessionPlan.Timing timing = plan.timing(focus);
            if (timing == SessionPlan.Timing.INSIDE || timing == SessionPlan.Timing.END) {
                label = context.getString(R.string.widget_session_reset, period,
                        DateFormat.getTimeFormat(context).format(new Date(plan.reset(focus))));
            } else if (plan.source == SessionPlan.Source.SAVED || plan.source == SessionPlan.Source.MANUAL) {
                label = context.getString(R.string.widget_session_saved, period);
            } else if (belowReserve) {
                label = context.getString(R.string.widget_session_low, period);
            } else {
                label = context.getString(weekly ? R.string.widget_compact_weekly : R.string.widget_compact_five_hour);
            }
        }
        views.setTextViewText(R.id.widget_compact_label, label);
        String description = focus == null ? context.getString(R.string.widget_session_no_allowance)
                : context.getString(belowReserve ? R.string.widget_session_accessibility_low
                        : R.string.widget_session_accessibility, label, focus.percent(now));
        views.setContentDescription(R.id.widget_compact_body, description);
        return views;
    }

    private static void bindWindow(Context context, RemoteViews views, UsageSnapshot.Window window,
                                   int value, int bar, int reset, long now) {
        boolean showValue = window != null && !window.expired(now);
        String percent = showValue ? window.percent(now) + " left" : "—";
        String resetText = window == null ? "—" : window.detail(context, now, true);
        views.setTextViewText(value, percent);
        views.setInt(bar, "setImageLevel", showValue ? window.progress(now) * 100 : 0);
        views.setTextViewText(reset, resetText);
        views.setContentDescription(reset, showValue && window.resetsAt > 0
                ? window.detail(context, now, false) : null);
    }

    private static String footer(Context context, UsageRepository repository, UsageSnapshot snapshot,
                                 long now) {
        if (repository.syncing()) {
            return "Syncing…";
        }
        if (snapshot != null && snapshot.manuallyEntered) {
            return "Entered " + UsageSnapshot.timeLabel(context, snapshot.fetchedAt, now, true);
        }
        if (!repository.connected()) {
            return snapshot == null ? "Tap to connect" : "Reconnect to sync";
        }
        if (snapshot == null) {
            return repository.error() == null ? "Waiting for sync" : "Tap to retry";
        }
        String prefix = repository.error() == null ? "Synced " : "Saved ";
        return prefix + UsageSnapshot.timeLabel(context, snapshot.fetchedAt, now, true);
    }
}

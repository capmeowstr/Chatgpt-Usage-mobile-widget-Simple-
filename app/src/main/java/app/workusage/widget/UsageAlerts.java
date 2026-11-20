package app.workusage.widget;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

final class UsageAlerts {
    private static final String CHANNEL = "usage_limits";
    private static final String THRESHOLD = "threshold";
    private static final String FIVE_HOUR = "five_hour_alerted_reset";
    private static final String WEEKLY = "weekly_alerted_reset";
    private static final int FIVE_HOUR_NOTIFICATION = 520;
    private static final int WEEKLY_NOTIFICATION = 521;

    static int threshold(Context context) {
        return preferences(context).getInt(THRESHOLD, 0);
    }

    static synchronized void saveThreshold(Context context, int threshold) {
        if (threshold(context) == threshold) {
            return;
        }
        preferences(context).edit().putInt(THRESHOLD, threshold).apply();
        clearPending(context);
    }

    static boolean needsPermission(Context context) {
        return Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED;
    }

    static boolean allowed(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
        return !needsPermission(context) && manager.areNotificationsEnabled()
                && (channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE);
    }

    static synchronized void check(Context context, UsageSnapshot snapshot) {
        int threshold = threshold(context);
        if (threshold == 0 || snapshot.manuallyEntered || !allowed(context)) {
            return;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL,
                context.getString(R.string.usage_alerts), NotificationManager.IMPORTANCE_DEFAULT);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        manager.createNotificationChannel(channel);

        checkWindow(context, manager, snapshot.fiveHours, snapshot.fetchedAt, threshold,
                FIVE_HOUR, FIVE_HOUR_NOTIFICATION, R.string.five_hour);
        checkWindow(context, manager, snapshot.week, snapshot.fetchedAt, threshold,
                WEEKLY, WEEKLY_NOTIFICATION, R.string.weekly);
    }

    static synchronized void clearPending(Context context) {
        preferences(context).edit().remove(FIVE_HOUR).remove(WEEKLY).apply();
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.cancel(FIVE_HOUR_NOTIFICATION);
        manager.cancel(WEEKLY_NOTIFICATION);
    }

    private static void checkWindow(Context context, NotificationManager manager,
                                    UsageSnapshot.Window window, long now, int threshold,
                                    String key, int notificationId, int label) {
        if (window == null || window.expired(now)) {
            return;
        }
        SharedPreferences preferences = preferences(context);
        long alertedReset = preferences.getLong(key, -1);
        if (window.remaining > threshold) {
            if (alertedReset != -1) {
                preferences.edit().remove(key).apply();
                manager.cancel(notificationId);
            }
            return;
        }
        if (alertedReset != -1 && (alertedReset == 0 || alertedReset > now || window.resetsAt == 0)) {
            return;
        }

        Intent launch = new Intent(context, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent tap = PendingIntent.getActivity(context, 520, launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notice = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_usage_alert)
                .setContentTitle(context.getString(label))
                .setContentText(context.getString(R.string.usage_alert_message, window.percent(now)))
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build();
        try {
            manager.notify(notificationId, notice);
            preferences.edit().putLong(key, window.resetsAt).apply();
        } catch (SecurityException ignored) {
            // Permission may be revoked between checking and posting.
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("usage_alerts", Context.MODE_PRIVATE);
    }
}

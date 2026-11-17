package app.workusage.widget;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class SyncJob extends JobService {
    private static final int PERIODIC_JOB = 151;
    private static final int MANUAL_JOB = 152;
    private final Map<Integer, JobParameters> running = new HashMap<>();

    static void schedulePeriodic(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        int[] widgets = AppWidgetManager.getInstance(context).getAppWidgetIds(UsageWidget.component(context));
        if (widgets.length == 0 || !UsageRepository.get(context).connected()
                || scheduler.getPendingJob(PERIODIC_JOB) != null) {
            return;
        }
        JobInfo job = new JobInfo.Builder(PERIODIC_JOB, new ComponentName(context, SyncJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(TimeUnit.MINUTES.toMillis(15))
                .setPersisted(true)
                .build();
        scheduler.schedule(job);
    }

    static void requestFromWidget(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler.getPendingJob(MANUAL_JOB) != null) {
            return;
        }
        ComponentName service = new ComponentName(context, SyncJob.class);
        if (Build.VERSION.SDK_INT >= 31) {
            JobInfo expedited = new JobInfo.Builder(MANUAL_JOB, service)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setExpedited(true)
                    .build();
            if (scheduler.schedule(expedited) == JobScheduler.RESULT_SUCCESS) {
                return;
            }
        }
        scheduler.schedule(new JobInfo.Builder(MANUAL_JOB, service)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build());
    }

    static void cancel(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        scheduler.cancel(PERIODIC_JOB);
        scheduler.cancel(MANUAL_JOB);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        UsageRepository repository = UsageRepository.get(this);
        if (!repository.connected()) {
            return false;
        }
        int id = params.getJobId();
        running.put(id, params);
        repository.refresh(id == MANUAL_JOB, () -> {
            if (running.get(id) == params) {
                running.remove(id);
                jobFinished(params, false);
            }
        });
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        running.remove(params.getJobId());
        return false;
    }
}

package com.cleanlook.continuewatching;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/** The system's three-day, unmetered-network wake-up for the Top Shelf photos; {@link PhotoFeed} decides what is due. */
public final class PhotoJobService extends JobService {
    private static final int JOB_ID = 4201;
    private static final long PERIOD_MS = PhotoPolicy.REFRESH_EVERY_MS;

    /** Schedules the periodic job once (it is kept while the app stays installed, until a reboot). */
    static void schedule(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null || js.getPendingJob(JOB_ID) != null) return;
        js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, PhotoJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .setPeriodic(PERIOD_MS)
                .build());
    }

    @Override public boolean onStartJob(final JobParameters params) {
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                PhotoFeed.refresh(app, false);
                jobFinished(params, false);
            }
        }, "cw-photos-job").start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        return false;
    }
}

package io.github.sumyabazar1.weather;

import android.app.job.JobParameters;
import android.app.job.JobService;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Runs {@link Refresher#refreshBlocking} off the main thread for the periodic and one-shot jobs. */
public final class RefreshJobService extends JobService {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    @Override
    public boolean onStartJob(JobParameters params) {
        IO.execute(() -> {
            boolean ok;
            try {
                ok = Refresher.refreshBlocking(getApplicationContext());
            } catch (RuntimeException e) {
                ok = false;
            }
            jobFinished(params, !ok);   // a failed read is retried with the system's backoff
        });
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}

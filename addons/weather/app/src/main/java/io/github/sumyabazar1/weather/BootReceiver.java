package io.github.sumyabazar1.weather;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Puts the periodic refresh back after a restart or an update of this app. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            Refresher.ensurePeriodic(context);
        }
    }
}

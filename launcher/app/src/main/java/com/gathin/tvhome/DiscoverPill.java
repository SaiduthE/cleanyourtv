package com.gathin.tvhome;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.List;
import java.util.function.Consumer;

/**
 * The round compass pill in the top bar: OK opens the hub of the Continue watching add-on. It is a top-bar pill like
 * the others (same style, focus colours and D-pad rules: it joins the list the bar's key listener is set on), and it
 * is hidden, with no error, while the add-on is not installed.
 */
final class DiscoverPill {

    static final String PACKAGE = "com.cleanlook.continuewatching";
    static final String HUB = PACKAGE + ".HubActivity";

    private final Activity activity;
    private final View button;

    private DiscoverPill(Activity activity, View button) {
        this.activity = activity;
        this.button = button;
    }

    /** The hub, by explicit component. */
    static Intent hubIntent() {
        return new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(PACKAGE, HUB))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /**
     * Adds the pill just before {@code beforeView} in the top bar and to {@code barButtons} (the list MainActivity
     * gives its key listener and focus effects). Starts hidden until {@link #resume()} finds the add-on.
     */
    static DiscoverPill create(Activity activity, ViewGroup bar, View beforeView, List<View> barButtons,
                               Consumer<Intent> opener) {
        TextView button = (TextView) activity.getLayoutInflater().inflate(R.layout.top_app_button, bar, false);
        button.setId(View.generateViewId());   // pillKeys finds the bar's ends by id
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_discover, 0, 0, 0);
        button.setContentDescription(activity.getString(R.string.discover));
        button.setVisibility(View.GONE);
        button.setOnClickListener(v -> opener.accept(hubIntent()));
        bar.addView(button, Math.max(0, bar.indexOfChild(beforeView)));
        barButtons.add(button);
        return new DiscoverPill(activity, button);
    }

    /** Home is in front: show the pill only while the hub can be started. */
    void resume() {
        button.setVisibility(installed() ? View.VISIBLE : View.GONE);
    }

    private boolean installed() {
        try {
            return activity.getPackageManager().getActivityInfo(new ComponentName(PACKAGE, HUB), 0).enabled;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return false;
        }
    }
}

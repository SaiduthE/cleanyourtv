package com.gathin.tvhome;

import android.app.Activity;

/** What a widget's factory is given: where it docks, the layout hook for that slot, and the owner's choices. */
final class WidgetContext {
    final Activity activity;
    final WidgetDef def;
    final WidgetSlot slot;
    final WidgetMount mount;
    final boolean animations;
    final boolean reducedMotion;
    final HomeWidgets host;

    WidgetContext(Activity activity, WidgetDef def, WidgetSlot slot, WidgetMount mount, boolean animations,
                  boolean reducedMotion, HomeWidgets host) {
        this.activity = activity;
        this.def = def;
        this.slot = slot;
        this.mount = mount;
        this.animations = animations;
        this.reducedMotion = reducedMotion;
        this.host = host;
    }

    /** The current value of one of this widget's options. */
    String option(WidgetOption option) {
        return host.store().option(def, option);
    }

    boolean isOn(WidgetOption toggle) {
        return WidgetOption.ON.equals(option(toggle));
    }
}

package com.gathin.tvhome;

import android.content.Context;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Everything the Widgets manager needs to know about one widget: its id, name, icon, the slots it can dock in, the
 * check that its source is installed, its settings, and the factory for its view. Register one in
 * {@link WidgetRegistry} and it appears in the manager.
 */
final class WidgetDef {

    /** Whether the widget's source (an add-on) is installed and usable right now. */
    interface Availability {
        boolean available(Context ctx);
    }

    /** Builds the widget in the slot of the context, or returns null when it cannot sit there. */
    interface Factory {
        HomeWidget create(WidgetContext context);
    }

    /** Runs one of the widget's action options (an option made with {@link WidgetOption#action}). */
    interface Actions {
        void run(Context ctx, String optionKey);
    }

    final String id;
    final int labelRes;
    final int iconRes;
    /** Shown on the tile, and as a message, when the widget's source is missing. */
    final int unavailableRes;
    final Set<WidgetSlot> slots;
    /** The slot it docks in until the owner chooses, or null for none. */
    final WidgetSlot defaultSlot;
    final Availability availability;
    final List<WidgetOption> options;
    final Actions actions;
    final Factory factory;

    WidgetDef(String id, int labelRes, int iconRes, int unavailableRes, Set<WidgetSlot> slots, WidgetSlot defaultSlot,
              Availability availability, List<WidgetOption> options, Actions actions, Factory factory) {
        this.id = id;
        this.labelRes = labelRes;
        this.iconRes = iconRes;
        this.unavailableRes = unavailableRes;
        this.slots = Collections.unmodifiableSet(EnumSet.copyOf(slots));
        this.defaultSlot = defaultSlot;
        this.availability = availability;
        this.options = Collections.unmodifiableList(options);
        this.actions = actions;
        this.factory = factory;
    }

    boolean supports(WidgetSlot slot) {
        return slots.contains(slot);
    }
}

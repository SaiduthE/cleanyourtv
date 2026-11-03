package com.gathin.tvhome;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Which widget sits in which slot. One widget per slot, and a widget in no slot is simply not shown. No Android in
 * it: the text form below is what Config's launcher_settings preferences keep (one string, so a write is atomic).
 *
 * Text form: {@code weather=clock_side;brightness=none}. A widget that is not listed has its default slot; "none"
 * is a widget the owner took off Home. Anything unreadable is ignored, and if two widgets claim one slot the first
 * one keeps it.
 */
final class WidgetPlacements {

    static final String NONE = "none";

    /** What a slot can host; the registry answers this for the real widgets. */
    interface SlotRules {
        boolean supports(String widgetId, WidgetSlot slot);
    }

    /** What a move did, or would do. */
    enum Result {
        /** The widget now sits in the slot (it was in none, or in another one that is now free). */
        PLACED,
        /** The slot's widget took the mover's old slot. */
        SWAPPED,
        /** The widget was already there. */
        UNCHANGED,
        /** The slot is taken and its widget cannot move to the mover's old slot (or the mover had none): refused. */
        OCCUPIED,
        /** This widget cannot dock in that slot. */
        NOT_SUPPORTED
    }

    /** widget id to slot; null for a widget the owner took off Home. */
    private final Map<String, WidgetSlot> placed = new LinkedHashMap<>();

    /** Placements from the stored text; {@code defaults} are the slots of widgets not mentioned in it. */
    static WidgetPlacements parse(String text, Map<String, WidgetSlot> defaults) {
        return parse(text, defaults, null);
    }

    /**
     * As above, and a stored claim that can never be shown is dropped so it cannot hold a slot for good: a widget the
     * app no longer lists (when {@code defaults} names the widgets there are) or a slot the widget no longer docks in
     * (when {@code rules} says so). Left in, such a ghost keeps its slot "taken" and nothing can be placed there.
     */
    static WidgetPlacements parse(String text, Map<String, WidgetSlot> defaults, SlotRules rules) {
        WidgetPlacements out = new WidgetPlacements();
        Map<String, WidgetSlot> stored = new LinkedHashMap<>();
        if (text != null) {
            for (String part : text.split(";")) {
                int eq = part.indexOf('=');
                if (eq <= 0) continue;
                String id = part.substring(0, eq).trim();
                String value = part.substring(eq + 1).trim();
                if (id.isEmpty() || stored.containsKey(id)) continue;
                if (NONE.equals(value)) stored.put(id, null);
                else if (WidgetSlot.fromKey(value) != null) stored.put(id, WidgetSlot.fromKey(value));
            }
        }
        if (defaults != null) {
            for (Map.Entry<String, WidgetSlot> d : defaults.entrySet()) {
                if (!stored.containsKey(d.getKey())) stored.put(d.getKey(), d.getValue());
            }
        }
        for (Map.Entry<String, WidgetSlot> e : stored.entrySet()) {
            if (defaults != null && !defaults.isEmpty() && !defaults.containsKey(e.getKey())) continue;
            WidgetSlot slot = e.getValue();
            if (slot != null && rules != null && !rules.supports(e.getKey(), slot)) slot = null;
            // A second claim on one slot loses it: one widget per slot, whatever the stored text says.
            if (slot != null && out.widgetIn(slot) != null) slot = null;
            out.placed.put(e.getKey(), slot);
        }
        return out;
    }

    /** The text form; ids sorted so the same placements always give the same text. */
    String serialize() {
        StringBuilder s = new StringBuilder();
        for (Map.Entry<String, WidgetSlot> e : new TreeMap<>(placed).entrySet()) {
            if (s.length() > 0) s.append(';');
            s.append(e.getKey()).append('=').append(e.getValue() == null ? NONE : e.getValue().key);
        }
        return s.toString();
    }

    /** The slot the widget is docked in, or null when it is not shown. */
    WidgetSlot slotOf(String widgetId) {
        return placed.get(widgetId);
    }

    /** The widget docked in the slot, or null. */
    String widgetIn(WidgetSlot slot) {
        for (Map.Entry<String, WidgetSlot> e : placed.entrySet()) if (e.getValue() == slot) return e.getKey();
        return null;
    }

    /** What moving {@code widgetId} to {@code slot} would do, without doing it. */
    Result check(String widgetId, WidgetSlot slot, SlotRules rules) {
        if (slot == null || !rules.supports(widgetId, slot)) return Result.NOT_SUPPORTED;
        WidgetSlot current = placed.get(widgetId);
        if (current == slot) return Result.UNCHANGED;
        String occupant = widgetIn(slot);
        if (occupant == null) return Result.PLACED;
        // Taken: swap only when the occupant can live in the place this widget leaves; otherwise say no.
        if (current != null && rules.supports(occupant, current)) return Result.SWAPPED;
        return Result.OCCUPIED;
    }

    /** Moves the widget to the slot when {@link #check} allows it; the result says what happened. */
    Result place(String widgetId, WidgetSlot slot, SlotRules rules) {
        Result result = check(widgetId, slot, rules);
        if (result == Result.SWAPPED) placed.put(widgetIn(slot), placed.get(widgetId));
        if (result == Result.PLACED || result == Result.SWAPPED) placed.put(widgetId, slot);
        return result;
    }

    /** Takes the widget off Home. */
    void remove(String widgetId) {
        placed.put(widgetId, null);
    }
}

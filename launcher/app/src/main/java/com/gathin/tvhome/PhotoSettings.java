package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What the Continue watching add-on last said about the Top Shelf photos (switch, categories, status), or why it
 * said nothing. A plain value with no Android in it, so the JVM tests cover the rules: the at-least-one category
 * rule and the text each state shows on its row. Built by {@link PhotoSettingsClient} from the add-on's answers.
 */
final class PhotoSettings {

    /** Why there is no answer. READY means there is one. */
    enum State { LOADING, READY, NOT_INSTALLED, OUTDATED, DENIED, NO_ANSWER }

    static final String NOT_INSTALLED_TEXT = "Add-on not installed";
    static final String OUTDATED_TEXT = "Update the add-on";
    static final String DENIED_TEXT = "Not allowed";
    static final String NO_ANSWER_TEXT = "No answer";
    static final String LOADING_TEXT = "Loading…";
    static final String REFRESHING_TEXT = "Refreshing…";

    /** The error code the add-on gives when the last category would be switched off. */
    static final String ERR_AT_LEAST_ONE = "at_least_one";

    final State state;
    final boolean enabled;
    /** Every category's key and name, in the add-on's order. */
    final List<String> keys;
    final List<String> labels;
    /** The keys that are on. */
    final Set<String> on;
    final int count;
    final boolean refreshing;
    /** The add-on's own status line ("12 photos, updated today", "No network"); may be empty. */
    final String status;
    /** The add-on's error code for the last call (empty when it went through). */
    final String error;

    private PhotoSettings(State state, boolean enabled, List<String> keys, List<String> labels, Set<String> on,
                          int count, boolean refreshing, String status, String error) {
        this.state = state;
        this.enabled = enabled;
        this.keys = Collections.unmodifiableList(new ArrayList<>(keys));
        this.labels = Collections.unmodifiableList(new ArrayList<>(labels));
        this.on = Collections.unmodifiableSet(new LinkedHashSet<>(on));
        this.count = count;
        this.refreshing = refreshing;
        this.status = status == null ? "" : status;
        this.error = error == null ? "" : error;
    }

    static PhotoSettings unavailable(State why) {
        return new PhotoSettings(why, false, Collections.<String>emptyList(), Collections.<String>emptyList(),
                Collections.<String>emptySet(), 0, false, "", "");
    }

    static PhotoSettings ready(boolean enabled, List<String> keys, List<String> labels, Set<String> on, int count,
                               boolean refreshing, String status, String error) {
        return new PhotoSettings(State.READY, enabled, keys, labels, on, count, refreshing, status, error);
    }

    boolean available() {
        return state == State.READY;
    }

    // ---- what the rows show -----------------------------------------------------------------------------------

    /** The Photos row: On or Off; the reason when the add-on cannot be asked. */
    String photosValue() {
        return available() ? (enabled ? "On" : "Off") : unavailableText();
    }

    /** The Photo themes row: "All", or how many of them are on. */
    String themesValue() {
        if (!available()) return unavailableText();
        if (keys.isEmpty() || on.size() >= keys.size()) return "All";
        return on.size() + " of " + keys.size();
    }

    /**
     * The Refresh photos row: the add-on's plain status ("12 photos, updated today", the last failure such as
     * "No network"), "Refreshing…" while a refresh runs, "Off" with photos off, or why the add-on cannot be asked.
     */
    String refreshValue() {
        if (!available()) return unavailableText();
        if (refreshing) return REFRESHING_TEXT;
        if (!enabled) return "Off";
        if (!status.isEmpty()) return status;
        return count == 0 ? "No photos yet" : count + (count == 1 ? " photo" : " photos");
    }

    private String unavailableText() {
        switch (state) {
            case NOT_INSTALLED: return NOT_INSTALLED_TEXT;
            case OUTDATED: return OUTDATED_TEXT;
            case DENIED: return DENIED_TEXT;
            case NO_ANSWER: return NO_ANSWER_TEXT;
            default: return LOADING_TEXT;
        }
    }

    // ---- changes ----------------------------------------------------------------------------------------------

    /**
     * The keys that are on after the owner presses {@code key}; null when that would switch off the last one (at
     * least one stays on) or the key is not one of the categories.
     */
    static Set<String> toggled(List<String> keys, Set<String> on, String key) {
        if (!keys.contains(key)) return null;
        Set<String> next = new LinkedHashSet<>();
        for (String k : keys) if (on.contains(k) && !k.equals(key)) next.add(k);
        boolean turningOn = !on.contains(key);
        if (turningOn) next.add(key);
        // keep the add-on's order
        Set<String> ordered = new LinkedHashSet<>();
        for (String k : keys) if (next.contains(k)) ordered.add(k);
        return ordered.isEmpty() ? null : ordered;
    }

    /** The same answer with the switch changed (shown at once while the add-on is asked). */
    PhotoSettings withEnabled(boolean value) {
        return new PhotoSettings(state, value, keys, labels, on, count, refreshing, status, "");
    }

    PhotoSettings withOn(Set<String> value) {
        return new PhotoSettings(state, enabled, keys, labels, value, count, refreshing, status, "");
    }

    PhotoSettings withRefreshing() {
        return new PhotoSettings(state, enabled, keys, labels, on, count, true, status, "");
    }
}

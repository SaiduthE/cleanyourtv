package com.gathin.tvhome;

import java.util.Locale;

/** What the pill at the top of Home says in reorder and edit-dock mode. Pure text logic, so JVM tests cover it. */
final class ReorderPill {

    /** The step the mode is at; each has its own title and key hint (strings_shelf.xml). */
    enum Stage {
        /** Reorder mode, nothing picked up yet. */
        BROWSE,
        /** An app is picked up: "Moving Plex". */
        CARRY,
        /** Edit dock, choosing the dock place. */
        DOCK_PLACE,
        /** Edit dock, the place is chosen and the app for it is being chosen. */
        DOCK_APP
    }

    /** The longest app name the pill shows; longer ones end in an ellipsis so the pill stays a pill. */
    static final int MAX_NAME = 24;

    private ReorderPill() { }

    static Stage stage(boolean dockEdit, boolean carrying, boolean slotChosen) {
        if (dockEdit) return slotChosen ? Stage.DOCK_APP : Stage.DOCK_PLACE;
        return carrying ? Stage.CARRY : Stage.BROWSE;
    }

    /** The app's name for the pill: trimmed, white space folded, cut at {@link #MAX_NAME} characters. */
    static String name(String label) {
        if (label == null) return "";
        String s = label.trim().replaceAll("\\s+", " ");
        if (s.length() <= MAX_NAME) return s;
        return s.substring(0, MAX_NAME - 1).trim() + "…";
    }

    /** {@code format} is a "Moving %s" string. */
    static String carryTitle(String format, String label) {
        return String.format(Locale.getDefault(), format, name(label));
    }
}

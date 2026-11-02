package com.gathin.tvhome;

import android.app.Activity;

/**
 * Home's on-screen cue while apps are being reordered or the dock is being edited. MainActivity reports each step
 * here; the visuals come later, so every method does nothing yet. It must never take focus: whatever it shows is
 * not focusable, so the remote keeps working on the tiles.
 */
final class ReorderIndicator {

    private final Activity host;

    ReorderIndicator(Activity host) {
        this.host = host;
    }

    /** Reorder mode started; dockEdit is true for "Edit dock" (choose a place, then an app). */
    void onEnter(boolean dockEdit) { }

    /** The mode ended, by Back, a finished edit, or implicitly (Home paused, apps changed). */
    void onExit() { }

    /** The tile for key at adapter position pos was picked up. */
    void onCarry(String key, int pos) { }

    /** The carried tile was put down; the mode stays on. */
    void onDrop() { }

    /** The carried tile moved to adapter position pos. */
    void onMoved(int pos) { }

    /** Edit dock: the dock place slot was chosen. */
    void onDockSlotChosen(int slot) { }
}

package com.gathin.tvhome;

import android.view.View;

/** A widget docked on Home: what {@link HomeWidgets} keeps for each slot that has one. */
interface HomeWidget {

    /** Its view when it is on screen, else null. */
    View shownView();

    /** Focus its view; false when it is not shown. */
    boolean focus();

    /** Home is in front: read the source and start watching it. */
    void resume();

    /** Home left the screen: stop watching. */
    void pause();

    /** An option changed in the widget's settings: show it now. */
    void refresh();

    /** Take the widget's views off Home. */
    void detach();
}

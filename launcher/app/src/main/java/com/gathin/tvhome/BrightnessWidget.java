package com.gathin.tvhome;

import android.app.Activity;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;

/**
 * The Brightness chip: a top-bar style pill showing the level the Brightness add-on dims to. OK opens a small quick
 * control over Home ({@link WidgetOverlays.BrightnessQuick}); the add-on does the dimming through its quick provider
 * ({@link BrightnessSource}). Hidden while the add-on is not installed.
 */
final class BrightnessWidget implements HomeWidget {

    private final WidgetContext wc;
    private final Activity activity;
    private final TextView chip;
    private final BrightnessSource source;
    private boolean addOn;
    private boolean shown;
    private BrightnessSource.Level level;

    private BrightnessWidget(WidgetContext wc) {
        this.wc = wc;
        this.activity = wc.activity;
        // The same pill as the top bar's buttons (style TopButton), whichever slot it is docked in.
        chip = new TextView(activity, null, 0, R.style.TopButton);
        chip.setSingleLine(true);
        chip.setVisibility(View.GONE);
        chip.setOnClickListener(v -> wc.host.openBrightnessControl(this));
        chip.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    wc.host.nav().focusGrid();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    return wc.host.neighbour(wc.slot, -1);
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    return wc.host.neighbour(wc.slot, 1);
                default:
                    return false;
            }
        });
        wc.mount.add(chip, 42, 8);
        source = new BrightnessSource(activity);
    }

    static HomeWidget create(WidgetContext wc) {
        try {
            return new BrightnessWidget(wc);
        } catch (RuntimeException e) {
            Log.w(BrightnessSource.TAG, "brightness chip not created", e);
            return null;
        }
    }

    BrightnessSource source() {
        return source;
    }

    /** The owner's step choice, as the size the provider takes. */
    int stepSize() {
        return BrightnessSource.stepSize(wc.option(WidgetRegistry.BRIGHTNESS_STEP));
    }

    /** A new reading from the quick control, so the chip is right when it closes. */
    void setLevel(BrightnessSource.Level reading) {
        level = reading;
        apply();
    }

    // ---- lifecycle -----------------------------------------------------------------------------------------------

    @Override
    public void resume() {
        addOn = BrightnessSource.available(activity);
        if (!addOn) {
            hide();
            return;
        }
        source.read(reading -> {
            level = reading;
            apply();
        });
    }

    @Override
    public void pause() { }

    @Override
    public boolean focus() {
        return shown && chip.requestFocus();
    }

    @Override
    public View shownView() {
        return shown ? chip : null;
    }

    @Override
    public void refresh() {
        apply();
    }

    @Override
    public void detach() {
        shown = false;
        wc.mount.remove();
    }

    // ---- showing -------------------------------------------------------------------------------------------------

    private void apply() {
        if (!addOn) {
            hide();
            return;
        }
        String style = wc.option(WidgetRegistry.BRIGHTNESS_STYLE);
        boolean icon = !"level".equals(style);
        boolean text = !"icon".equals(style);
        // Unknown until the add-on answers (or when it cannot be read): "--" says so instead of a made-up level.
        String value = level == null ? "--%" : level.level + "%";
        chip.setText(text ? value : "");
        chip.setCompoundDrawablesRelativeWithIntrinsicBounds(icon ? R.drawable.ic_widget_brightness : 0, 0, 0, 0);
        chip.setCompoundDrawablePadding(text && icon ? PanelRows.dp(activity, 9) : 0);
        chip.setContentDescription(activity.getString(R.string.widget_brightness_description, value));
        if (!text) {
            // Icon only: a round pill like the other icon buttons.
            chip.setPaddingRelative(PanelRows.dp(activity, 10), 0, PanelRows.dp(activity, 10), 0);
        } else {
            chip.setPaddingRelative(PanelRows.dp(activity, 16), 0, PanelRows.dp(activity, 20), 0);
        }
        if (!shown) {
            shown = true;
            wc.mount.setShown(true);
            chip.setVisibility(View.VISIBLE);
        }
    }

    private void hide() {
        if (!shown) return;
        shown = false;
        if (chip.hasFocus()) wc.host.nav().focusFirstButton();
        chip.setVisibility(View.GONE);
        wc.mount.setShown(false);
    }
}

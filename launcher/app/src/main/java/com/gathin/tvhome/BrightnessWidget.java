package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.drawable.Drawable;
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
    /** icon_style rounded: the level the sun is filled to (0 to 10000), or -1 before the first reading, and its animation. */
    private int fillLevel = -1;
    private ValueAnimator fill;

    private BrightnessWidget(WidgetContext wc) {
        this.wc = wc;
        this.activity = wc.activity;
        // The same pill as the top bar's buttons (style TopButton), whichever slot it is docked in.
        chip = new TextView(activity, null, 0, R.style.TopButton);
        GlassLook.decorate(chip);   // built after Home's own decorate pass: the frosted glass is put on here
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
        if (fill != null) fill.cancel();
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
        chip.setContentDescription(activity.getString(R.string.widget_brightness_description, value));
        // The same shape rules as the other top-bar buttons' icon / text / both styles.
        TopButtonStyler.shape(chip, Icons.brightness(activity), icon, text);
        if (icon) showFill();
        if (!shown) {
            shown = true;
            wc.mount.setShown(true);
            chip.setVisibility(View.VISIBLE);
        }
    }

    /** The rounded sun fills from the bottom as the brightness rises; it eases to a new level instead of jumping. */
    private void showFill() {
        Drawable sun = chip.getCompoundDrawablesRelative()[0];
        if (sun == null || !Icons.rounded(activity)) return;
        int target = level == null ? 0 : Icons.fillLevel(level.level);
        if (fill != null) fill.cancel();
        if (fillLevel < 0 || fillLevel == target || !wc.animations) {
            fillLevel = target;
            sun.setLevel(target);
            return;
        }
        sun.setLevel(fillLevel);   // the pill got a fresh drawable: start it where the old one was
        fill = ValueAnimator.ofInt(fillLevel, target);
        fill.setDuration(wc.reducedMotion ? Motion.REDUCED_MS : 250);
        fill.addUpdateListener(a -> {
            fillLevel = (int) a.getAnimatedValue();
            Drawable current = chip.getCompoundDrawablesRelative()[0];
            if (current != null) current.setLevel(fillLevel);
        });
        fill.start();
    }

    private void hide() {
        if (!shown) return;
        shown = false;
        if (chip.hasFocus()) wc.host.nav().focusFirstButton();
        chip.setVisibility(View.GONE);
        wc.mount.setShown(false);
    }
}

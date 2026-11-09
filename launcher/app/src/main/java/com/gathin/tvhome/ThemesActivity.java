package com.gathin.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.view.View;

/**
 * The Styles screen ("Clean Look · Styles"): switch the look from the couch, without a computer. The presets are
 * bundled in the APK (assets/themes) and written by {@link StyleInstaller}. The active style is marked selected.
 */
public final class ThemesActivity extends Activity {

    private LinearLayout content;

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        content = new LinearLayout(this);
        setContentView(PanelUi.scroller(this, content));
        SettingsPanel.apply(this);
        content.addView(PanelUi.title(this, getString(R.string.styles_title)));
        content.addView(PanelUi.note(this, getString(R.string.styles_note)));
        String active = StyleInstaller.activeStyleId(this);
        View first = null;
        View focus = null;
        for (String[] style : StyleInstaller.styles(this)) {
            View row = PanelRows.row(this, style[1]);
            boolean isActive = style[0].equals(active);
            row.setSelected(isActive);
            row.setOnClickListener(v -> showStyle(style));
            content.addView(row);
            if (first == null) first = row;
            if (isActive) focus = row;
        }
        content.addView(PanelRows.header(this, getString(R.string.styles_other)));
        View builtin = PanelRows.row(this, getString(R.string.styles_builtin));
        builtin.setOnClickListener(v -> StyleInstaller.apply(this, null, "the built-in look"));
        content.addView(builtin);
        content.addView(PanelUi.note(this, getString(R.string.styles_hint)));
        if (focus == null) focus = first;
        if (focus != null) focus.requestFocus(); else builtin.requestFocus();
    }

    private void showStyle(String[] style) {
        content.removeAllViews();
        content.addView(PanelUi.title(this, style[1]));
        content.addView(PanelUi.note(this, style[2]));
        boolean isActive = style[0].equals(StyleInstaller.activeStyleId(this));
        if (isActive) content.addView(PanelUi.note(this, getString(R.string.styles_active)));
        View apply = PanelRows.row(this, getString(R.string.styles_apply));
        ((LinearLayout.LayoutParams) apply.getLayoutParams()).topMargin = PanelUi.dp(this, 26);
        apply.setOnClickListener(v -> StyleInstaller.apply(this, style[0], style[1]));
        content.addView(apply);
        View back = PanelRows.row(this, getString(R.string.styles_back));
        back.setOnClickListener(v -> recreate());
        content.addView(back);
        apply.requestFocus();
    }
}

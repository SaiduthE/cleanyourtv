package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.Map;

/**
 * Draws a top-bar pill in one of the {@link TopButtonStyle} styles and keeps the owner's choices in Launcher
 * settings' preferences. The shape rules are the Brightness chip's: icon only is the 42 dp circle (even 10 dp padding
 * around the 22 dp icon), text or icon + text are the same 42 dp tall pill, wider, with the pill background's own
 * corners, so the focus ring, colours and D-pad rules stay those of every other top-bar button.
 */
final class TopButtonStyler {

    /** The longest the label may run before it is cut with an ellipsis. */
    static final int MAX_TEXT_DP = 120;
    /** Keep the icon the pill already has (a festive skin's), instead of setting one. */
    static final int KEEP_ICON = -1;
    private static final int ICON_DP = 22;
    private static final int GAP_DP = 9;

    private TopButtonStyler() { }

    /**
     * The pill's padding, icon and width for what it shows; the caller sets the text. {@code iconRes} 0 shows none,
     * {@link #KEEP_ICON} leaves the icon alone. A pill that is only an icon is 42 dp wide, others wrap their content.
     */
    static void shape(TextView pill, int iconRes, boolean icon, boolean text) {
        Context ctx = pill.getContext();
        if (iconRes != KEEP_ICON) {
            pill.setCompoundDrawablesRelativeWithIntrinsicBounds(icon ? Icons.res(ctx, iconRes) : 0, 0, 0, 0);
        } else if (!icon) {
            pill.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0);
        }
        pill.setCompoundDrawablePadding(text && icon ? PanelRows.dp(ctx, GAP_DP) : 0);
        if (!text) {
            pill.setPaddingRelative(PanelRows.dp(ctx, 10), 0, PanelRows.dp(ctx, 10), 0);
        } else if (icon) {
            pill.setPaddingRelative(PanelRows.dp(ctx, 16), 0, PanelRows.dp(ctx, 20), 0);
        } else {
            pill.setPaddingRelative(PanelRows.dp(ctx, 18), 0, PanelRows.dp(ctx, 18), 0);
        }
        ViewGroup.LayoutParams lp = pill.getLayoutParams();
        if (lp != null) {
            int width = text ? ViewGroup.LayoutParams.WRAP_CONTENT : PanelRows.dp(ctx, 42);
            if (lp.width != width) {
                lp.width = width;
                pill.setLayoutParams(lp);
            }
        }
    }

    /**
     * Shows {@code label} and/or the icon on a top-bar pill. Without text the label goes to talkback; with it the
     * label is one line, cut with an ellipsis at {@link #MAX_TEXT_DP}.
     */
    static void apply(TextView pill, String style, int iconRes, CharSequence label) {
        boolean icon = TopButtonStyle.showsIcon(style);
        boolean text = TopButtonStyle.showsText(style);
        Context ctx = pill.getContext();
        shape(pill, iconRes, icon, text);
        pill.setSingleLine(true);
        pill.setEllipsize(TextUtils.TruncateAt.END);
        pill.setText(text ? label : null);
        pill.setContentDescription(text ? null : label);
        int extra = pill.getPaddingStart() + pill.getPaddingEnd() + (icon ? PanelRows.dp(ctx, ICON_DP + GAP_DP) : 0);
        pill.setMaxWidth(text ? PanelRows.dp(ctx, MAX_TEXT_DP) + extra : Integer.MAX_VALUE);
    }

    /**
     * Styles the Settings, Inputs, Discover and VPN pills of the top bar, after every other rule has shaped them.
     * A festive skin's own Inputs and Settings icons stay while the style shows an icon. The VPN pill's text and
     * icon follow the connection afterwards (MainActivity#updateVpnPill); here it gets its shape and width cap.
     *
     * @param discover the Discover pill, or null when the bar has none (the one-gear top menu)
     */
    static void applyBar(Context ctx, Map<TopButtonStyle.Button, String> styles, boolean festive, boolean topMenu,
                         boolean launcherSettingsButton, TextView settings, TextView inputs, TextView discover,
                         TextView vpn) {
        int settingsIcon = topMenu || !launcherSettingsButton ? Icons.SETTINGS : Icons.HOME_SETTINGS_PILL;
        apply(settings, styles.get(TopButtonStyle.Button.SETTINGS), festive ? KEEP_ICON : settingsIcon,
                ctx.getString(launcherSettingsButton && !topMenu ? R.string.launcher_settings : R.string.settings));
        apply(inputs, styles.get(TopButtonStyle.Button.INPUTS), festive ? KEEP_ICON : Icons.INPUT,
                ctx.getString(R.string.inputs));
        if (discover != null) {
            apply(discover, styles.get(TopButtonStyle.Button.DISCOVER), Icons.DISCOVER,
                    ctx.getString(R.string.discover));
        }
        apply(vpn, styles.get(TopButtonStyle.Button.VPN), Icons.LOCK, ctx.getString(R.string.vpn));
    }

    // ---- saved choices ------------------------------------------------------------------------------------------

    /** Every button's style: the owner's pick, else what the theme's icon_only_* settings gave it. */
    static Map<TopButtonStyle.Button, String> load(Context ctx, Config config) {
        SharedPreferences prefs = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        return TopButtonStyle.load(key -> prefs.getString(key, null), TopButtonStyle.themeDefaults(
                config.iconOnlyButtons, config.iconOnlyInputs, config.iconOnlyVpn, config.topMenu));
    }

    /**
     * Saves choices in one edit, together with the change count that makes Home rebuild when it next resumes, so
     * Home never sees the new styles without the count or the count without the styles.
     */
    static void save(Context ctx, Map<String, String> choices) {
        SharedPreferences prefs = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        for (Map.Entry<String, String> e : choices.entrySet()) editor.putString(e.getKey(), e.getValue());
        editor.putLong(Config.SETTINGS_REVISION_KEY, prefs.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
    }
}

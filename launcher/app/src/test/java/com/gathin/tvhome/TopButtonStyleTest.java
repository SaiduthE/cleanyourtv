package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.gathin.tvhome.SettingsModel.Group;
import com.gathin.tvhome.SettingsModel.Row;
import com.gathin.tvhome.TopButtonStyle.Button;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import org.junit.Test;

public class TopButtonStyleTest {

    private static final Map<Button, String> ALL_ICONS = TopButtonStyle.themeDefaults(true, true, true, false);

    @Test public void stylesAreIconTextAndBoth() {
        assertTrue(TopButtonStyle.showsIcon(TopButtonStyle.ICON));
        assertFalse(TopButtonStyle.showsText(TopButtonStyle.ICON));
        assertFalse(TopButtonStyle.showsIcon(TopButtonStyle.TEXT));
        assertTrue(TopButtonStyle.showsText(TopButtonStyle.TEXT));
        assertTrue(TopButtonStyle.showsIcon(TopButtonStyle.BOTH));
        assertTrue(TopButtonStyle.showsText(TopButtonStyle.BOTH));
        assertEquals(0, TopButtonStyle.indexOf(TopButtonStyle.ICON));
        assertEquals(2, TopButtonStyle.indexOf(TopButtonStyle.BOTH));
        assertEquals(0, TopButtonStyle.indexOf("nonsense"));
    }

    @Test public void nothingSavedKeepsTheThemeLook() {
        Map<Button, String> shipped = TopButtonStyle.load(key -> null, TopButtonStyle.themeDefaults(false, false, false, false));
        assertEquals(TopButtonStyle.BOTH, shipped.get(Button.SETTINGS));
        assertEquals(TopButtonStyle.BOTH, shipped.get(Button.INPUTS));
        assertEquals(TopButtonStyle.BOTH, shipped.get(Button.VPN));
        assertEquals("the compass was always round", TopButtonStyle.ICON, shipped.get(Button.DISCOVER));

        Map<Button, String> icons = TopButtonStyle.load(key -> null, ALL_ICONS);
        for (Button b : Button.values()) assertEquals(TopButtonStyle.ICON, icons.get(b));
        assertEquals(TopButtonStyle.ICON, TopButtonStyle.common(icons));
    }

    @Test public void theOneGearMenuIsRoundWithoutIconOnlyButtons() {
        Map<Button, String> menu = TopButtonStyle.themeDefaults(false, false, false, true);
        assertEquals(TopButtonStyle.ICON, menu.get(Button.SETTINGS));
    }

    @Test public void aSavedChoiceBeatsTheThemeAndAnUnreadableOneDoesNot() {
        Map<String, String> saved = new HashMap<>();
        saved.put(Button.INPUTS.key(), TopButtonStyle.TEXT);
        saved.put(Button.VPN.key(), "sideways");
        Map<Button, String> now = TopButtonStyle.load(saved::get, ALL_ICONS);
        assertEquals(TopButtonStyle.TEXT, now.get(Button.INPUTS));
        assertEquals("junk falls back to the theme", TopButtonStyle.ICON, now.get(Button.VPN));
        assertEquals(TopButtonStyle.ICON, now.get(Button.SETTINGS));
        assertEquals(TopButtonStyle.MIXED, TopButtonStyle.common(now));
    }

    @Test public void eachButtonHasItsOwnKeyThatNoThemeResetClears() {
        HashSet<String> keys = new HashSet<>();
        for (Button b : Button.values()) {
            assertTrue(b.key().startsWith("top_button_style_"));
            keys.add(b.key());
        }
        assertEquals(Button.values().length, keys.size());
        for (String reset : SettingsModel.RESET_KEYS) assertFalse(keys.contains(reset));
    }

    @Test public void settingAllWritesEveryKeyInOneGo() {
        Map<String, String> all = TopButtonStyle.allTo(TopButtonStyle.BOTH);
        assertEquals(Button.values().length, all.size());
        Map<Button, String> read = TopButtonStyle.load(all::get, ALL_ICONS);
        for (Button b : Button.values()) assertEquals(TopButtonStyle.BOTH, read.get(b));
        assertEquals(TopButtonStyle.BOTH, TopButtonStyle.common(read));
        // a bad style is written as the default, never as junk
        assertEquals(TopButtonStyle.ICON, TopButtonStyle.allTo("x").get(Button.VPN.key()));
    }

    @Test public void settingOneLeavesTheOthers() {
        Map<String, String> saved = new HashMap<>(TopButtonStyle.allTo(TopButtonStyle.ICON));
        saved.putAll(TopButtonStyle.one(Button.DISCOVER, TopButtonStyle.TEXT));
        Map<Button, String> read = TopButtonStyle.load(saved::get, ALL_ICONS);
        assertEquals(TopButtonStyle.TEXT, read.get(Button.DISCOVER));
        assertEquals(TopButtonStyle.ICON, read.get(Button.SETTINGS));
        assertEquals(TopButtonStyle.MIXED, TopButtonStyle.common(read));
        assertEquals(TopButtonStyle.ICON, read.get(Button.VPN));
    }

    @Test public void theSettingsRowIsOnePopupRowInTheButtonsGroup() {
        assertEquals(SettingsModel.Kind.OPTIONS, SettingsModel.kind(Row.TOP_BAR_BUTTONS));
        assertTrue(SettingsModel.rows(Group.BUTTONS, false, false).contains(Row.TOP_BAR_BUTTONS));
        int count = 0;
        for (Group g : SettingsModel.groups()) {
            if (SettingsModel.rows(g, true, true).contains(Row.TOP_BAR_BUTTONS)) count++;
        }
        assertEquals(1, count);
        assertTrue(new HashSet<>(Arrays.asList(Row.values())).contains(Row.TOP_BAR_BUTTONS));
    }
}

package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/** icon_style: rounded is the launcher's look, default gives back today's drawables exactly. */
public class IconsTest {

    private static final int[] ORIGINALS = {
            Icons.WIFI, Icons.ETHERNET, Icons.APPS, Icons.APPS_PILL, Icons.AV,
            Icons.DISCOVER, Icons.HOME_SETTINGS, Icons.HOME_SETTINGS_PILL, Icons.INPUT,
            Icons.LIVE_TV, Icons.LOCK, Icons.LOCK_OPEN, Icons.PICTURE,
            Icons.SETTINGS, Icons.SHELF_PLAY, Icons.SWAP_VERT, Icons.WIDGET_BRIGHTNESS,
            Icons.WIDGET_WEATHER,
    };

    @Test
    public void styleNameIsRoundedUnlessDefault() {
        assertEquals(Config.ICONS_ROUNDED, Config.iconStyle(null));
        assertEquals(Config.ICONS_ROUNDED, Config.iconStyle("rounded"));
        assertEquals(Config.ICONS_ROUNDED, Config.iconStyle("anything"));
        assertEquals(Config.ICONS_DEFAULT, Config.iconStyle("default"));
        assertEquals(Config.ICONS_DEFAULT, Config.iconStyle(" Default "));
    }

    @Test
    public void defaultStyleReturnsTheDrawableItWasGiven() {
        for (int res : ORIGINALS) assertEquals(res, Icons.res(false, res));
    }

    @Test
    public void roundedStyleReturnsAnotherDrawableForEveryOriginal() {
        for (int res : ORIGINALS) assertNotEquals(res, Icons.res(true, res));
    }

    @Test
    public void aRoundedDrawableStaysItself() {
        assertEquals(R.drawable.ic_r_lan, Icons.res(true, R.drawable.ic_r_lan));
        assertEquals(0, Icons.res(true, 0));
    }

    @Test
    public void defaultNetworkIconIsTodaysWhateverTheSignal() {
        for (int level = 0; level <= 4; level++) {
            assertEquals(Icons.WIFI, Icons.network(false, Icons.Link.WIFI, level));
        }
        assertEquals(Icons.WIFI, Icons.network(false, Icons.Link.OFFLINE, 0));
        assertEquals(Icons.ETHERNET, Icons.network(false, Icons.Link.ETHERNET, 4));
        assertEquals(Icons.ETHERNET, Icons.network(false, Icons.Link.ETHERNET_IDLE, 4));
    }

    @Test
    public void roundedWifiFollowsTheSignal() {
        assertEquals(R.drawable.ic_r_signal_wifi_0_bar, Icons.network(true, Icons.Link.WIFI, 0));
        assertEquals(R.drawable.ic_r_network_wifi_1_bar, Icons.network(true, Icons.Link.WIFI, 1));
        assertEquals(R.drawable.ic_r_network_wifi_2_bar, Icons.network(true, Icons.Link.WIFI, 2));
        assertEquals(R.drawable.ic_r_network_wifi_3_bar, Icons.network(true, Icons.Link.WIFI, 3));
        assertEquals(R.drawable.ic_r_signal_wifi_4_bar, Icons.network(true, Icons.Link.WIFI, 4));
    }

    @Test
    public void aSignalOutOfRangeIsClamped() {
        assertEquals(0, Icons.bars(-3));
        assertEquals(4, Icons.bars(9));
    }

    @Test
    public void roundedOfflineAndEthernet() {
        assertEquals(R.drawable.ic_r_wifi_off, Icons.network(true, Icons.Link.OFFLINE, 4));
        assertEquals(R.drawable.ic_r_lan_fill, Icons.network(true, Icons.Link.ETHERNET, 0));
        assertEquals(R.drawable.ic_r_lan, Icons.network(true, Icons.Link.ETHERNET_IDLE, 0));
    }

    @Test
    public void brightnessFillsFromZeroToTenThousand() {
        assertEquals(0, Icons.fillLevel(0));
        assertEquals(5000, Icons.fillLevel(50));
        assertEquals(10000, Icons.fillLevel(100));
        assertEquals(0, Icons.fillLevel(-5));
        assertEquals(10000, Icons.fillLevel(140));
    }
}

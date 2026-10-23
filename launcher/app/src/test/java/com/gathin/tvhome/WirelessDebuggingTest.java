package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** When keep_wireless_debugging switches Wireless debugging back on, and when it leaves it off. */
public class WirelessDebuggingTest {

    @Test
    public void alreadyOnIsLeftAlone() {
        assertFalse(new WirelessDebugging.Gate().due(true));
    }

    @Test
    public void bootBeforeWifiTriesAgainOnTheNextReturnToHome() {
        WirelessDebugging.Gate g = new WirelessDebugging.Gate();
        assertTrue(g.due(false));
        g.switchedOn(false);   // no Wi-Fi yet: Android turns it off without asking
        assertTrue(g.due(false));
    }

    @Test
    public void switchedOffAgainOnWifiIsNotAskedOnEveryReturnToHome() {
        WirelessDebugging.Gate g = new WirelessDebugging.Gate();
        assertTrue(g.due(false));
        g.switchedOn(true);
        // An untrusted network (Android asked, then switched it off) or the owner switching it off.
        assertFalse(g.due(false));
        assertFalse(g.due(false));
    }

    @Test
    public void theNextNetworkGetsOneMoreTry() {
        WirelessDebugging.Gate g = new WirelessDebugging.Gate();
        g.switchedOn(true);
        g.networkChanged();
        assertTrue(g.due(false));
        g.switchedOn(true);
        assertFalse(g.due(false));
    }
}

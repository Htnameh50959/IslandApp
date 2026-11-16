package com.staad.island

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HotspotNotificationTest {

    @Test
    fun countsEnglishForms() {
        assertEquals(2, HotspotNotification.clientCount("2 devices connected"))
        assertEquals(2, HotspotNotification.clientCount("2 connected devices"))
        assertEquals(3, HotspotNotification.clientCount("Connected devices: 3"))
        assertEquals(1, HotspotNotification.clientCount("1 device connected"))
    }

    @Test
    fun noDevicesIsZeroNotNull() {
        assertEquals(0, HotspotNotification.clientCount("No devices connected"))
        assertEquals(0, HotspotNotification.clientCount("No connected devices"))
    }

    @Test
    fun oneDeviceWithoutDigit() {
        assertEquals(1, HotspotNotification.clientCount("One device connected"))
    }

    @Test
    fun handlesNonLatinDigits() {
        assertEquals(2, HotspotNotification.clientCount("2 设备"))
        assertEquals(2, HotspotNotification.clientCount("\u0662 设备"))
    }

    @Test
    fun returnsNullWhenCountIsNotNamed() {
        assertNull(HotspotNotification.clientCount("Hotspot is on"))
        assertNull(HotspotNotification.clientCount(""))
    }

    @Test
    fun detectsHotspotTextAndChannel() {
        assertTrue(HotspotNotification.isHotspot("Portable hotspot is on", ""))
        assertTrue(HotspotNotification.isHotspot("", "Tethering"))
        assertTrue(!HotspotNotification.isHotspot("Music", "playback"))
    }
}

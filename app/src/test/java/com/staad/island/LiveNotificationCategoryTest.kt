package com.staad.island

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveNotificationCategoryTest {

    @Test
    fun notificationAppsAreAllSelectedUntilTheUserSavesASelection() {
        assertTrue(isNotificationAppSelected("com.example.mail", emptySet(), false))
        assertTrue(isNotificationAppSelected("com.example.mail", setOf("com.example.mail"), true))
        assertFalse(isNotificationAppSelected("com.example.chat", setOf("com.example.mail"), true))
        assertFalse(isNotificationAppSelected("com.example.mail", emptySet(), true))
    }

    @Test
    fun categoriesCanBeDisabledIndependently() {
        val config = IslandConfig.DEFAULT

        assertTrue(isLiveKindEnabled(LiveKind.CALL, config))
        assertFalse(isLiveKindEnabled(LiveKind.CALL, config.copy(notificationsEnabled = false)))
        assertTrue(isLiveKindEnabled(LiveKind.CALL, config))
        assertFalse(isLiveKindEnabled(LiveKind.CALL, config.copy(liveCalls = false)))
        assertTrue(isLiveKindEnabled(LiveKind.NAVIGATION, config.copy(liveCalls = false)))

        assertTrue(isLiveKindEnabled(LiveKind.NAVIGATION, config))
        assertFalse(isLiveKindEnabled(LiveKind.NAVIGATION, config.copy(liveNavigation = false)))
        assertTrue(isLiveKindEnabled(LiveKind.TIMER, config.copy(liveNavigation = false)))

        assertTrue(isLiveKindEnabled(LiveKind.TIMER, config))
        assertFalse(isLiveKindEnabled(LiveKind.TIMER, config.copy(liveTimers = false)))
        assertTrue(isLiveKindEnabled(LiveKind.ACTIVITY, config.copy(liveTimers = false)))

        assertTrue(isLiveKindEnabled(LiveKind.ACTIVITY, config))
        assertFalse(isLiveKindEnabled(LiveKind.ACTIVITY, config.copy(liveOther = false)))
        assertFalse(isLiveKindEnabled(LiveKind.NONE, config))
    }
}

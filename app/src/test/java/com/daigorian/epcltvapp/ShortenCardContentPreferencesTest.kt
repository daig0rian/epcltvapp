package com.daigorian.epcltvapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortenCardContentPreferencesTest {
    @Test fun api25AndOlderUsesShortCardsInitially() {
        listOf(22, 23, 24, 25).forEach { assertTrue(ShortenCardContentPreferences.shouldAutoShortenCards(it)) }
    }

    @Test fun api26AndNewerDefaultsToOff() {
        listOf(26, 28, 30, 34).forEach { assertFalse(ShortenCardContentPreferences.shouldAutoShortenCards(it)) }
    }

    @Test fun unsetUsesDeviceDefault() {
        assertTrue(ShortenCardContentPreferences.resolveShortenCards(null, true))
    }

    @Test fun savedOffWinsOverAutomaticOn() {
        assertFalse(ShortenCardContentPreferences.resolveShortenCards(false, true))
    }

    @Test fun unsetDefaultsToOff() {
        assertFalse(ShortenCardContentPreferences.resolveShortenCards(null))
    }

    @Test fun savedOnIsPreserved() {
        assertTrue(ShortenCardContentPreferences.resolveShortenCards(true))
    }

    @Test fun savedOffIsPreserved() {
        assertFalse(ShortenCardContentPreferences.resolveShortenCards(false))
    }
}

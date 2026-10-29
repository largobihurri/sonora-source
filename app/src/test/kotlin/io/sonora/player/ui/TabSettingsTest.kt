package io.sonora.player.ui

import io.sonora.player.navigation.TopLevelRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TabSettingsTest {

    private val hosts = listOf(TopLevelRoute.Search, TopLevelRoute.Converter, TopLevelRoute.Export)

    @Test
    fun `Search, Converter and Export host Settings over their own page but the Library keeps its own stack`() {
        for (tab in hosts) assertTrue(tab.hostsSettings())
        assertFalse(TopLevelRoute.Library.hostsSettings())
    }

    @Test
    fun `a tab shows only its own page until Settings is opened on it`() {
        for (tab in hosts) assertEquals(listOf(TabPage.Tab), settingsStack(emptySet(), tab))
    }

    @Test
    fun `opening Settings on a tab pushes it over that page and over no other tab`() {
        val open = emptySet<TopLevelRoute>().withSettingsOn(TopLevelRoute.Converter)
        assertEquals(listOf(TabPage.Tab, TabPage.Settings), settingsStack(open, TopLevelRoute.Converter))
        assertEquals(listOf(TabPage.Tab), settingsStack(open, TopLevelRoute.Search))
        assertEquals(listOf(TabPage.Tab), settingsStack(open, TopLevelRoute.Export))
    }

    @Test
    fun `Settings stays open on each tab independently, as the Library stack does`() {
        val open = emptySet<TopLevelRoute>().withSettingsOn(TopLevelRoute.Search).withSettingsOn(TopLevelRoute.Export)
        assertEquals(listOf(TabPage.Tab, TabPage.Settings), settingsStack(open, TopLevelRoute.Search))
        assertEquals(listOf(TabPage.Tab, TabPage.Settings), settingsStack(open, TopLevelRoute.Export))
        val closed = open.withoutSettingsOn(TopLevelRoute.Search)
        assertEquals(listOf(TabPage.Tab), settingsStack(closed, TopLevelRoute.Search))
        assertEquals(listOf(TabPage.Tab, TabPage.Settings), settingsStack(closed, TopLevelRoute.Export))
    }

    @Test
    fun `opening Settings twice on a tab still leaves one Settings page above it`() {
        val open = emptySet<TopLevelRoute>().withSettingsOn(TopLevelRoute.Search).withSettingsOn(TopLevelRoute.Search)
        assertEquals(listOf(TabPage.Tab, TabPage.Settings), settingsStack(open, TopLevelRoute.Search))
    }

    @Test
    fun `closing Settings on a tab that has none changes nothing`() {
        val open = setOf(TopLevelRoute.Export)
        assertEquals(open, open.withoutSettingsOn(TopLevelRoute.Search))
    }

    @Test
    fun `the Library never gets a Settings page over its own, since its stack holds Settings itself`() {
        val open = emptySet<TopLevelRoute>().withSettingsOn(TopLevelRoute.Library)
        assertTrue(open.isEmpty())
        assertEquals(listOf(TabPage.Tab), settingsStack(setOf(TopLevelRoute.Library), TopLevelRoute.Library))
    }

    @Test
    fun `Settings over a tab holds the tab for a tab tap, otherwise the stack inside the tab does`() {
        assertTrue(settingsHoldsTab(outerDepth = 2))
        assertFalse(settingsHoldsTab(outerDepth = 1))
    }
}

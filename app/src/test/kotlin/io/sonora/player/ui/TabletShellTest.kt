package io.sonora.player.ui

import io.sonora.core.designsystem.layout.WindowClass
import io.sonora.feature.library.LibraryPage
import io.sonora.player.navigation.TopLevelRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tablet shell's pure decisions. Sizes in dp. */
class TabletShellTest {

    private val phone = WindowClass.Phone
    private val phoneSideways = WindowClass.of(widthDp = 704, heightDp = 320)
    private val padLandscape = WindowClass.of(widthDp = 1152, heightDp = 720)
    private val padPortrait = WindowClass.of(widthDp = 720, heightDp = 1152)

    @Test
    fun `a phone keeps the bottom tab bar in both orientations, whatever the sidebar choice`() {
        for (windowClass in listOf(phone, phoneSideways)) {
            for (shown in listOf(true, false)) {
                assertEquals(NavigationChrome.BottomTabBar, navigationChrome(windowClass, shown, visualCollectionOnScreen = false))
            }
        }
    }

    @Test
    fun `a large screen shows the sidebar, or the top pill once it is hidden`() {
        for (windowClass in listOf(padLandscape, padPortrait)) {
            assertEquals(NavigationChrome.Sidebar, navigationChrome(windowClass, sidebarShown = true, visualCollectionOnScreen = false))
            assertEquals(NavigationChrome.TopPill, navigationChrome(windowClass, sidebarShown = false, visualCollectionOnScreen = false))
        }
    }

    @Test
    fun `a visual collection on screen takes a large screen's navigation away, and a phone's stays under its own gate`() {
        for (shown in listOf(true, false)) {
            assertEquals(NavigationChrome.None, navigationChrome(padLandscape, shown, visualCollectionOnScreen = true))
            assertEquals(NavigationChrome.BottomTabBar, navigationChrome(phone, shown, visualCollectionOnScreen = true))
        }
    }

    @Test
    fun `the sidebar is wider on an expanded window than on a medium one`() {
        assertEquals(320f, sidebarWidth(padLandscape), 0.001f)
        assertEquals(280f, sidebarWidth(padPortrait), 0.001f)
    }

    @Test
    fun `content starts after the sidebar and its margins, and at the edge without it`() {
        assertEquals(320f + 2 * SidebarMargin, contentStartInset(NavigationChrome.Sidebar, padLandscape), 0.001f)
        assertEquals(280f + 2 * SidebarMargin, contentStartInset(NavigationChrome.Sidebar, padPortrait), 0.001f)
        for (chrome in listOf(NavigationChrome.TopPill, NavigationChrome.None, NavigationChrome.BottomTabBar)) {
            assertEquals(0f, contentStartInset(chrome, padLandscape), 0.001f)
        }
    }

    @Test
    fun `the page content resizes once, to the settled navigation, not with the sidebar's slide`() {
        // The Pad 6 is 2.5 px per dp: the sidebar's 320 dp plus both margins.
        assertEquals((320f + 2 * SidebarMargin) * 2.5f, settledContentInsetPx(NavigationChrome.Sidebar, padLandscape, 2.5f), 0.001f)
        assertEquals((280f + 2 * SidebarMargin) * 2.5f, settledContentInsetPx(NavigationChrome.Sidebar, padPortrait, 2.5f), 0.001f)
        // Hiding it moves the inset to 0 at once; the translucent bar then slides away over the content, which is not laid out again.
        for (chrome in listOf(NavigationChrome.TopPill, NavigationChrome.None, NavigationChrome.BottomTabBar)) {
            assertEquals(0f, settledContentInsetPx(chrome, padLandscape, 2.5f), 0.001f)
        }
        assertEquals(0f, settledContentInsetPx(NavigationChrome.BottomTabBar, phone, 2.75f), 0.001f)
    }

    @Test
    fun `only the sidebar is revealed, the top pill takes its place when it is not`() {
        assertEquals(1f, sidebarRevealTarget(NavigationChrome.Sidebar), 0.001f)
        for (chrome in listOf(NavigationChrome.TopPill, NavigationChrome.None, NavigationChrome.BottomTabBar)) {
            assertEquals(0f, sidebarRevealTarget(chrome), 0.001f)
        }
    }

    @Test
    fun `back hides the sidebar only while it is the visible navigation`() {
        assertTrue(backHidesSidebar(NavigationChrome.Sidebar, chromeVisible = true))
        assertFalse(backHidesSidebar(NavigationChrome.Sidebar, chromeVisible = false)) // Now Playing covers it
        for (chrome in listOf(NavigationChrome.TopPill, NavigationChrome.None, NavigationChrome.BottomTabBar)) {
            assertFalse(backHidesSidebar(chrome, chromeVisible = true))
        }
    }

    @Test
    fun `the floating mini player is centred in the content and capped in width`() {
        val wide = floatingMiniPlayerFrame(contentWidth = 800f, navigationBar = 24f)
        assertEquals(FloatingMiniPlayerMaxWidth, wide.width, 0.001f)
        assertEquals((800f - FloatingMiniPlayerMaxWidth) / 2f, wide.x, 0.001f)
        assertEquals(60f, wide.height, 0.001f)
        assertEquals(24f + FloatingMiniPlayerMargin, wide.bottom, 0.001f)
    }

    @Test
    fun `the floating mini player keeps a side margin on a narrow content area`() {
        val narrow = floatingMiniPlayerFrame(contentWidth = 400f, navigationBar = 0f)
        assertEquals(400f - 2 * 16f, narrow.width, 0.001f)
        assertEquals(16f, narrow.x, 0.001f)
        assertEquals(0f, floatingMiniPlayerFrame(contentWidth = 10f, navigationBar = 0f).width, 0.001f)
    }

    @Test
    fun `the mini player cap is between 520 and 600 dp`() {
        assertTrue(FloatingMiniPlayerMaxWidth in 520f..600f)
    }

    @Test
    fun `floating content padding clears the mini player, or just the navigation bar without one`() {
        val frame = floatingMiniPlayerFrame(contentWidth = 800f, navigationBar = 24f)
        assertEquals(frame.top + ChromeGap, floatingContentPadding(hasMiniPlayer = true, navigationBar = 24f), 0.001f)
        assertEquals(24f + ChromeGap, floatingContentPadding(hasMiniPlayer = false, navigationBar = 24f), 0.001f)
    }

    @Test
    fun `the chrome state reserves the floating padding on a large screen and the bar's on a phone`() {
        val chrome = BottomChromeState(collapseAfterPx = 24f, expandAfterPx = 12f)
        chrome.navigationBar = 24f
        chrome.hasMiniPlayer = true
        val phonePadding = chrome.contentPadding

        chrome.floating = true
        assertEquals(floatingContentPadding(hasMiniPlayer = true, navigationBar = 24f), chrome.contentPadding.value, 0.001f)
        assertTrue(chrome.contentPadding < phonePadding)

        chrome.floating = false
        assertEquals(phonePadding, chrome.contentPadding)
    }

    @Test
    fun `the top pill is centred and the sidebar button sits at its leading side`() {
        val layout = topChromeLayout(contentWidth = 800f, pillWidth = 324f)
        assertEquals((800f - 324f) / 2f, layout.pillX, 0.001f)
        assertEquals(layout.pillX - TopToggleGap - TopToggleSize, layout.toggleX, 0.001f)
        assertTrue(layout.toggleX >= TopSideMargin)
    }

    @Test
    fun `the top pill leaves room for the button on both sides so it stays centred`() {
        for (width in listOf(600f, 720f, 1152f)) {
            val geometry = topPillGeometry(tabCount = 4, contentWidth = width)
            val layout = topChromeLayout(width, geometry.barWidth)
            assertTrue("button fits at $width", layout.toggleX >= TopSideMargin - 0.001f)
            assertEquals(width - (layout.pillX + geometry.barWidth), layout.pillX, 0.001f)
        }
    }

    @Test
    fun `the top pill is narrower than the phone's tab bar and fits its four sections`() {
        val geometry = topPillGeometry(tabCount = 4, contentWidth = 1152f)
        assertEquals(4 * TopPillCell + 3 * TabBarGeometry.GAP + 2 * TabBarGeometry.INSET, geometry.barWidth, 0.001f)
        assertTrue(geometry.barWidth < tabBarGeometry(4, 1152f).barWidth)
        assertFalse(geometry.compactLabels)
    }

    @Test
    fun `the visual collection on a large screen shows only when asked for`() {
        for (landscape in listOf(true, false)) {
            assertEquals(
                LibraryVisualCollectionMode.None,
                libraryVisualCollectionMode(landscape, TopLevelRoute.Library, libraryVisualCollection = true, nowPlayingOpen = false, windowClass = padLandscape),
            )
            assertEquals(
                LibraryVisualCollectionMode.Shown,
                libraryVisualCollectionMode(landscape, TopLevelRoute.Library, libraryVisualCollection = true, nowPlayingOpen = false, windowClass = padLandscape, visualCollectionRequested = true),
            )
            assertEquals(
                LibraryVisualCollectionMode.Covered,
                libraryVisualCollectionMode(landscape, TopLevelRoute.Library, libraryVisualCollection = true, nowPlayingOpen = true, windowClass = padLandscape, visualCollectionRequested = true),
            )
        }
    }

    @Test
    fun `a request for the visual collection does nothing on another tab or a page without one, or on a phone`() {
        assertEquals(
            LibraryVisualCollectionMode.None,
            libraryVisualCollectionMode(true, TopLevelRoute.Search, libraryVisualCollection = true, nowPlayingOpen = false, windowClass = padLandscape, visualCollectionRequested = true),
        )
        assertEquals(
            LibraryVisualCollectionMode.None,
            libraryVisualCollectionMode(true, TopLevelRoute.Library, libraryVisualCollection = false, nowPlayingOpen = false, windowClass = padLandscape, visualCollectionRequested = true),
        )
        // A phone's visual collection follows its orientation only; a stray request changes nothing.
        assertEquals(
            LibraryVisualCollectionMode.None,
            libraryVisualCollectionMode(false, TopLevelRoute.Library, libraryVisualCollection = true, nowPlayingOpen = false, windowClass = phone, visualCollectionRequested = true),
        )
        assertEquals(
            LibraryVisualCollectionMode.Shown,
            libraryVisualCollectionMode(true, TopLevelRoute.Library, libraryVisualCollection = true, nowPlayingOpen = false, windowClass = phoneSideways, visualCollectionRequested = false),
        )
    }

    @Test
    fun `the sidebar lists the four sections with the Library pages under Library and Settings last`() {
        assertEquals(
            listOf(
                SidebarDestination.Tab(TopLevelRoute.Library),
                SidebarDestination.Page(LibraryPage.Playlists),
                SidebarDestination.Page(LibraryPage.Songs),
                SidebarDestination.Page(LibraryPage.Albums),
                SidebarDestination.Page(LibraryPage.Artists),
                SidebarDestination.Page(LibraryPage.Folders),
                SidebarDestination.Page(LibraryPage.Converted),
                SidebarDestination.Page(LibraryPage.Statistics),
                SidebarDestination.Tab(TopLevelRoute.Search),
                SidebarDestination.Tab(TopLevelRoute.Converter),
                SidebarDestination.Tab(TopLevelRoute.Export),
            ),
            SidebarDestinations,
        )
        assertEquals(SidebarDestination.Page(LibraryPage.Settings), SidebarSettings)
    }

    @Test
    fun `the Library row is selected at its home and a page row while that page is open`() {
        assertEquals(SidebarDestination.Tab(TopLevelRoute.Library), sidebarSelected(TopLevelRoute.Library, page = null))
        assertEquals(SidebarDestination.Page(LibraryPage.Albums), sidebarSelected(TopLevelRoute.Library, LibraryPage.Albums))
        assertEquals(SidebarDestination.Page(LibraryPage.Settings), sidebarSelected(TopLevelRoute.Library, LibraryPage.Settings))
    }

    @Test
    fun `another tab is selected whatever page the Library keeps open underneath`() {
        assertEquals(SidebarDestination.Tab(TopLevelRoute.Search), sidebarSelected(TopLevelRoute.Search, LibraryPage.Albums))
        assertEquals(SidebarDestination.Tab(TopLevelRoute.Export), sidebarSelected(TopLevelRoute.Export, null))
    }

    @Test
    fun `no page reserves top room on a phone, with the sidebar shown or under a visual collection`() {
        for (windowClass in listOf(phone, phoneSideways)) {
            for (shown in listOf(true, false)) {
                assertEquals(0f, topChromeInset(navigationChrome(windowClass, shown, visualCollectionOnScreen = false)), 0f)
            }
        }
        for (windowClass in listOf(padLandscape, padPortrait)) {
            assertEquals(0f, topChromeInset(navigationChrome(windowClass, sidebarShown = true, visualCollectionOnScreen = false)), 0f)
            assertEquals(0f, topChromeInset(navigationChrome(windowClass, sidebarShown = false, visualCollectionOnScreen = true)), 0f)
        }
    }

    @Test
    fun `a large screen with the sidebar hidden reserves the room under the top pill, in both orientations`() {
        // The pill is 59 dp tall and rises 3.5 dp, so it ends 55.5 dp under the status bar; pages keep 8 dp clear of it.
        for (windowClass in listOf(padLandscape, padPortrait)) {
            val inset = topChromeInset(navigationChrome(windowClass, sidebarShown = false, visualCollectionOnScreen = false))
            assertEquals(63.5f, inset, 0.001f)
            assertTrue(inset > TopPillHeight + TopChromeRise)
        }
    }

    @Test
    fun `the room reserved clears the pill and is no taller than a page bar plus a title's top margin`() {
        val inset = topChromeInset(NavigationChrome.TopPill)
        // The page bars (52 dp) share the pill's row; their first row below starts at 52 dp: a few dp short of the pill's end.
        assertTrue(inset - 52f < 16f)
    }

    @Test
    fun `the sidebar's translucency is flat at every reveal on a large window`() {
        for (reveal in listOf(0f, 0.5f, 0.99f, 1f)) assertFalse(sidebarTranslucentLiquid(reveal))
    }

    @Test
    fun `with liquid at rest switched on, the sidebar's translucency is liquid only once it rests, flat while it slides`() {
        assertTrue(sidebarTranslucentLiquid(reveal = 1f, liquidAtRest = true))
        assertFalse(sidebarTranslucentLiquid(reveal = 0.99f, liquidAtRest = true))
        assertFalse(sidebarTranslucentLiquid(reveal = 0.5f, liquidAtRest = true))
        assertFalse(sidebarTranslucentLiquid(reveal = 0f, liquidAtRest = true))
    }
}

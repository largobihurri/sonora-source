package io.sonora.player.ui

import io.sonora.core.designsystem.layout.WidthClass
import io.sonora.core.designsystem.layout.WindowClass
import io.sonora.feature.library.LibraryPage
import io.sonora.player.navigation.TopLevelRoute

/** Which navigation the shell shows. */
internal enum class NavigationChrome {
    /** A phone: the floating translucent tab bar at the bottom, with the mini player above it. */
    BottomTabBar,

    /** A large screen: the translucent sidebar on the leading side. */
    Sidebar,

    /** A large screen with the sidebar hidden: the main sections in a translucent pill centred at the top of the content. */
    TopPill,

    /** A large screen under a visual collection that takes the whole window: no navigation. */
    None,
}

/**
 * The navigation for a window of [windowClass]: phones keep the bottom tab bar whatever [sidebarShown] says; a large
 * window shows the sidebar, or the top pill once the user hid it, and none while a visual collection is on screen
 * ([visualCollectionOnScreen]; a phone's visual collection hides its bar through its own gate).
 */
internal fun navigationChrome(windowClass: WindowClass, sidebarShown: Boolean, visualCollectionOnScreen: Boolean): NavigationChrome = when {
    !windowClass.isLarge -> NavigationChrome.BottomTabBar
    visualCollectionOnScreen -> NavigationChrome.None
    sidebarShown -> NavigationChrome.Sidebar
    else -> NavigationChrome.TopPill
}

/** Space (dp) around the floating sidebar: from the screen's edges and between it and the content. */
internal const val SidebarMargin = 12f

/** The sidebar's width (dp): wider on an expanded window, as on iPadOS. */
internal fun sidebarWidth(windowClass: WindowClass): Float = when (windowClass.widthClass) {
    WidthClass.EXPANDED -> 320f
    WidthClass.MEDIUM, WidthClass.COMPACT -> 280f
}

/** Where the content starts (dp from the start edge) with the [chrome] fully shown: after the sidebar and its margins. */
internal fun contentStartInset(chrome: NavigationChrome, windowClass: WindowClass): Float =
    if (chrome == NavigationChrome.Sidebar) sidebarWidth(windowClass) + 2 * SidebarMargin else 0f

/**
 * Where the page content starts, in px, as soon as the navigation is [chrome]: the sidebar's room while it is shown, nothing
 * otherwise. It is the settled value and does not follow the sidebar's slide: the pages are laid out again once, when the
 * choice changes, and the sidebar then slides over the content (or out of its way) without touching it. Laying the grid
 * out again on every frame of the 320 ms slide was the janky part of the toggle (T10).
 */
internal fun settledContentInsetPx(chrome: NavigationChrome, windowClass: WindowClass, density: Float): Float =
    contentStartInset(chrome, windowClass) * density

/**
 * Whether the sidebar's translucency is liquid at this [reveal] (1 shown .. 0 gone). Never on a large window (T13): a
 * translucent surface in motion is redrawn in full every frame (T12 made it flat while it slides, about 4 ms of every frame of
 * its slide), and the switch to liquid once it rests cost two frames of 12 to 14 ms of GPU on the Pad 6 at the end of every
 * showing. With the pages' own relayout that was 7 % janky frames over a toggle; flat at rest too (white in the light theme, a
 * plain tint in the dark one, without the liquid rim) it is 3 %. [liquidAtRest] switches the liquid look back on once the
 * sidebar rests.
 */
internal fun sidebarTranslucentLiquid(reveal: Float, liquidAtRest: Boolean = SidebarLiquidAtRest): Boolean = liquidAtRest && reveal >= 1f

/** The sidebar's translucency at rest is liquid (true) or flat (false): see [sidebarTranslucentLiquid]. */
internal const val SidebarLiquidAtRest = false

/** How far the sidebar is revealed (1) or gone (0) once the [chrome] has settled. */
internal fun sidebarRevealTarget(chrome: NavigationChrome): Float = if (chrome == NavigationChrome.Sidebar) 1f else 0f

/**
 * Whether the system back hides the sidebar: while it is the navigation on screen ([chromeVisible] false under Now Playing,
 * which owns back). It ranks below every page and sheet, so it only hides the sidebar when nothing else takes the back.
 */
internal fun backHidesSidebar(chrome: NavigationChrome, chromeVisible: Boolean): Boolean =
    chrome == NavigationChrome.Sidebar && chromeVisible

/** The widest the floating mini player gets (dp), however wide the content is. */
internal const val FloatingMiniPlayerMaxWidth = 560f

/** Space (dp) between the floating mini player and the navigation bar's top. */
internal const val FloatingMiniPlayerMargin = 16f

/**
 * The mini player on a large screen, in the content area [contentWidth] wide above a [navigationBar] inset: centred,
 * keeping the phone's side margin, capped at [FloatingMiniPlayerMaxWidth]. There is no tab bar under it.
 */
internal fun floatingMiniPlayerFrame(contentWidth: Float, navigationBar: Float): ChromeRect {
    val width = minOf(contentWidth - 2 * SideMargin, FloatingMiniPlayerMaxWidth).coerceAtLeast(0f)
    return ChromeRect((contentWidth - width) / 2f, navigationBar + FloatingMiniPlayerMargin, width, MiniPlayerHeight)
}

/** Bottom padding (dp) that keeps scrolling content clear of the floating mini player, or of the navigation bar without one. */
internal fun floatingContentPadding(hasMiniPlayer: Boolean, navigationBar: Float): Float =
    if (hasMiniPlayer) navigationBar + FloatingMiniPlayerMargin + MiniPlayerHeight + ChromeGap else navigationBar + ChromeGap

/** Space (dp) from the content's edges to the top chrome's outermost button. */
internal const val TopSideMargin = 16f

/** The sidebar button's size (dp), as the other translucent bar buttons. */
internal const val TopToggleSize = 44f

/** Space (dp) between the sidebar button and the pill. */
internal const val TopToggleGap = 8f

/** A cell of the top pill (dp): narrower than the phone's bar, so it leaves the page bars' buttons free on both sides. */
internal const val TopPillCell = 76f

/** Where the top pill and the sidebar button sit (dp from the content's start edge). */
internal data class TopChromeLayout(val pillX: Float, val toggleX: Float)

/** The top pill for [tabCount] sections in a content area [contentWidth] wide, leaving room for the button on both sides. */
internal fun topPillGeometry(tabCount: Int, contentWidth: Float): TabBarGeometry =
    tabBarGeometry(tabCount, contentWidth - 2 * (TopSideMargin + TopToggleSize + TopToggleGap), preferredCell = TopPillCell)

/** The pill centred in the content, and the sidebar button at its leading side. */
internal fun topChromeLayout(contentWidth: Float, pillWidth: Float): TopChromeLayout {
    val pillX = (contentWidth - pillWidth) / 2f
    return TopChromeLayout(pillX = pillX, toggleX = pillX - TopToggleGap - TopToggleSize)
}

/** Space (dp) a page keeps between the top pill's lower edge and its first content. */
internal const val TopChromeGap = 8f

/**
 * How far below the status bar the [chrome] reaches over the pages (dp): the top pill's lower edge (it is [TopPillHeight]
 * tall and rises [TopChromeRise]) plus [TopChromeGap]; zero while nothing floats at the top (a phone, the sidebar, a
 * Visual collection). Provided to every page as `LocalTopChromeInset`, so the pages clear the pill without knowing about it.
 */
internal fun topChromeInset(chrome: NavigationChrome): Float =
    if (chrome == NavigationChrome.TopPill) TopPillHeight + TopChromeRise + TopChromeGap else 0f

/** One row of the sidebar: a tab of the shell, or a page of the Library tab (Settings included). */
internal sealed interface SidebarDestination {
    data class Tab(val route: TopLevelRoute) : SidebarDestination

    data class Page(val page: LibraryPage) : SidebarDestination
}

/** The sidebar's rows in order: each section, the Library's pages under Library. */
internal val SidebarDestinations: List<SidebarDestination> = listOf(
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
)

/** The sidebar's footer row. */
internal val SidebarSettings = SidebarDestination.Page(LibraryPage.Settings)

/** The row to highlight on [tab], with the Library showing [page] (null at its home). Other tabs ignore the Library's page. */
internal fun sidebarSelected(tab: TopLevelRoute, page: LibraryPage?): SidebarDestination =
    if (tab == TopLevelRoute.Library && page != null) SidebarDestination.Page(page) else SidebarDestination.Tab(tab)

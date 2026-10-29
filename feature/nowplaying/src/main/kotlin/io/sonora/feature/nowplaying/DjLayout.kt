package io.sonora.feature.nowplaying

/*
 * What DJ mode changes in Now Playing's arrangements (D2.7, D2.10). The DJ layouts themselves are DjPortraitContent and
 * DjLandscapeContent; these are the decisions around them, kept pure.
 */

/**
 * Whether gravity mode hangs the groups level: its setting, except in DJ mode, whose layout ignores it, and on a
 * [largeScreen] (a tablet), where gravity mode never applies.
 */
internal fun gravityApplies(gravity: Boolean, djMode: Boolean, largeScreen: Boolean = false): Boolean =
    gravity && !djMode && !largeScreen

/**
 * Whether Now Playing's arrangements are the DJ ones: whenever DJ mode is on, in portrait ([DjPortraitContent]) and in
 * landscape ([DjLandscapeContent], D2.10) alike.
 */
internal fun showsDjLayout(djMode: Boolean): Boolean = djMode

/** Now Playing's arrangements: the standard ones, DJ mode's, or DUB mode's (its own layout, exclusive with DJ's). */
internal enum class DeckLayout {
    STANDARD,
    DJ,
    DUB,
    ;

    /** DJ or DUB: a deck layout, which leaves out gravity mode, the lyrics and the backdrop's drift as DJ mode does. */
    val deckOn: Boolean get() = this != STANDARD
}

/**
 * The arrangement for the two mode flags. They are exclusive (turning one on turns the other off); for the instant
 * both read on during a switch, DJ's keeps its place.
 */
internal fun deckLayout(djMode: Boolean, dubMode: Boolean): DeckLayout = when {
    djMode -> DeckLayout.DJ
    dubMode -> DeckLayout.DUB
    else -> DeckLayout.STANDARD
}

/** Whether the lyrics take the artwork's place: asked for, and never in DJ mode (its layout has no room for them). */
internal fun lyricsShown(showLyrics: Boolean, djMode: Boolean): Boolean = showLyrics && !djMode

/**
 * Whether a hand-off's artwork (T67) lands on Now Playing's big artwork: not while lyrics, gravity mode (whose artwork
 * hangs level) or DJ mode (a 56 dp thumbnail in the header) take its place.
 */
internal fun sharesArtwork(showLyrics: Boolean, gravity: Boolean, djMode: Boolean): Boolean = !showLyrics && !gravity && !djMode

/** The DJ bottom capsule: the normal one ([bottomSlots]) without lyrics, which DJ mode hides until D4. */
internal fun djBottomSlots(output: Boolean, fileInfo: Boolean, cast: Boolean): List<BottomSlot> =
    bottomSlots(lyrics = false, output = output, fileInfo = fileInfo, cast = cast)

/** The bottom capsule's buttons in or out of [djMode]. */
internal fun bottomSlotsFor(djMode: Boolean, lyrics: Boolean, output: Boolean, fileInfo: Boolean, cast: Boolean): List<BottomSlot> =
    if (djMode) djBottomSlots(output, fileInfo, cast) else bottomSlots(lyrics, output, fileInfo, cast)

/** One accessory of the header, before its "…" button. */
internal enum class HeaderSlot {
    /** The analysis chip of the song playing (DJ mode). */
    DJ_CHIP,

    /** The speed and pitch badge (it hides itself at normal speed and pitch); DJ mode has its own tempo controls. */
    TEMPO_BADGE,

    /** The running sleep timer's countdown. */
    SLEEP_TIMER,

    /** The song's star. */
    FAVOURITE,
}

/**
 * The header's accessories, left to right: the DJ chip in [djMode], else the tempo badge; the [sleepTimer]'s countdown
 * while one runs; the star while a [track] plays.
 */
internal fun headerSlots(djMode: Boolean, sleepTimer: Boolean, track: Boolean): List<HeaderSlot> = buildList {
    add(if (djMode) HeaderSlot.DJ_CHIP else HeaderSlot.TEMPO_BADGE)
    if (sleepTimer) add(HeaderSlot.SLEEP_TIMER)
    if (track) add(HeaderSlot.FAVOURITE)
}

/*
 * Per-frame drivers (D2.7b). A paused DJ screen must draw no frame at all, and a playing one only its waveform, overview
 * and time layers: anything else that asks for frames there either redraws the artwork backdrop, which every translucent
 * control samples (about a dozen on the DJ screen, each re-sampled per frame), or keeps the screen from going idle.
 */

/**
 * Whether the artwork backdrop drifts: out of DJ mode only, only while the sheet [sheetSettled] (it rests open, not
 * opening, closing or being dragged) and only while [playing] (it is the ambient motion of a song; paused, an untouched Now
 * Playing then draws no frame at all). Each drift step redraws the backdrop, and the backdrop is what the translucent
 * controls sample, so in DJ mode it would re-sample all of them on every step, paused or not (measured on the Realme: 121
 * frames/s and 74-85 % RenderThread CPU on a paused DJ screen); and a step in the middle of a sheet's motion adds a
 * re-sampling of all of it to the frames that are already the heaviest.
 */
internal fun backdropDrifts(djMode: Boolean, sheetSettled: Boolean, playing: Boolean): Boolean = !djMode && sheetSettled && playing

/**
 * Whether the header's title and artist scroll when too long: never in an arrangement kept [hidden] for the next turn;
 * in DJ mode only while [playing], so a paused DJ screen goes still (its narrow header makes most titles scroll).
 */
internal fun headerScrolls(hidden: Boolean, djMode: Boolean, playing: Boolean): Boolean = !hidden && (!djMode || playing)

/** Whether the DJ waveforms' position asks for every frame: while [playing], in the arrangement [shown] only. */
internal fun djPositionFrames(playing: Boolean, shown: Boolean): Boolean = playing && shown

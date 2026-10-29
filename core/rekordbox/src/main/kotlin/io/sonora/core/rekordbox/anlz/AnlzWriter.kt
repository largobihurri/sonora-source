package io.sonora.core.rekordbox.anlz

import io.sonora.core.domain.analysis.BeatGrid
import io.sonora.core.domain.analysis.BlueWaveform

/**
 * What the analysis files of one track are built from: [filePath], the audio file as the player finds it on the stick
 * (e.g. `/Contents/Artist/Album/file.aiff`, the `file_path` of the track in `export.pdb`), its [beatGrid] and its
 * [waveform]; [AnlzTrack.cues] are listed in the cue sections when there are any.
 */
data class AnlzTrack(
    val filePath: String,
    val beatGrid: BeatGrid,
    val waveform: BlueWaveform,
    /** The track's memory cues, hot cues and loops, in rekordbox's time base; none by default. */
    val cues: List<AnlzCue> = emptyList(),
    /**
     * Every beat of the grid with its own tempo (a track whose tempo changes, or one whose times are already in
     * rekordbox's time base); when given, these are written to `PQTZ` instead of [beatGrid]'s beats.
     */
    val beats: List<AnlzBeat>? = null,
    /** The 3-band waveform the colour waveforms and the `.2EX` file are built from; none by default. */
    val bands: BandPlanes? = null,
)

/**
 * Builds the `ANLZ0000.DAT` and `ANLZ0000.EXT` files of a track for players with blue waveforms (XDJ-700, XDJ-1000,
 * CDJ-2000NXS): the sections, in rekordbox's order, that those players read. A track with [AnlzTrack.bands] also gets
 * the colour waveforms (`PWV5`, `PWV4`; nexus 2) in its `.EXT` and a `.2EX` file with the 3-band ones (`PWV7`, `PWV6`,
 * `PWVC`; CDJ-3000), which the older players skip. `PQT2` and `PSSI` are left out.
 */
object AnlzWriter {

    /**
     * `.DAT`: path, VBR index, beat grid, blue preview, tiny preview, then the hot and memory cue lists (the old form:
     * hot cues A to C only).
     */
    fun datSections(track: AnlzTrack): List<AnlzSection> = listOf(
        PathSection(track.filePath),
        VbrSection,
        track.beats?.let(::BeatGridSection) ?: BeatGridSection.from(track.beatGrid),
        WaveformPreviewSection(track.waveform.preview()),
        TinyWaveformPreviewSection(track.waveform.tinyPreview()),
        *legacyCueLists(track),
    )

    /** `.EXT`: path, blue detail, then the hot and memory cue lists in both the old and the extended form. */
    fun extSections(track: AnlzTrack): List<AnlzSection> = listOf(
        PathSection(track.filePath),
        WaveformDetailSection(track.waveform.detail()),
        *legacyCueLists(track),
        ExtendedCueListSection(CueListType.HOT, track.cues.filter { it.hotCue != 0 }),
        ExtendedCueListSection(CueListType.MEMORY, track.cues.filter { it.hotCue == 0 }),
        *colorWaveforms(track),
    )

    /**
     * `.2EX` (read by the CDJ-3000 only): path, 3-band detail and preview, gains. `null` for a track without
     * [AnlzTrack.bands].
     */
    fun ex2Sections(track: AnlzTrack): List<AnlzSection>? = track.bands?.let { bands ->
        listOf(
            PathSection(track.filePath),
            ThreeBandDetailSection(ColorWaveforms.threeBandDetail(bands, track.waveform.detailSize)),
            ThreeBandPreviewSection(ColorWaveforms.threeBandPreview(bands)),
            WaveformGainSection,
        )
    }

    /** The colour detail (`PWV5`) and preview (`PWV4`), as many detail columns as the blue detail has. */
    private fun colorWaveforms(track: AnlzTrack): Array<AnlzSection> = track.bands?.let { bands ->
        arrayOf(
            ColorWaveformDetailSection(ColorWaveforms.detail(bands, track.waveform.detailSize)),
            ColorWaveformPreviewSection(ColorWaveforms.preview(bands)),
        )
    } ?: emptyArray()

    /** The hot list (pads A to C) and the memory list of the old form, rekordbox's order. */
    private fun legacyCueLists(track: AnlzTrack): Array<AnlzSection> = arrayOf(
        CueListSection(CueListType.HOT, track.cues.filter { it.hotCue in 1..AnlzCue.MAX_LEGACY_HOT_CUE }),
        CueListSection(CueListType.MEMORY, track.cues.filter { it.hotCue == 0 }),
    )

    fun writeDat(track: AnlzTrack): ByteArray = AnlzEncoder.encode(datSections(track))

    fun writeExt(track: AnlzTrack): ByteArray = AnlzEncoder.encode(extSections(track))

    /** The `.2EX` file, or `null` when the track has no [AnlzTrack.bands]. */
    fun writeEx2(track: AnlzTrack): ByteArray? = ex2Sections(track)?.let(AnlzEncoder::encode)
}

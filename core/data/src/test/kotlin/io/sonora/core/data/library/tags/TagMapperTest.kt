package io.sonora.core.data.library.tags

import com.google.common.truth.Truth.assertThat
import io.sonora.core.domain.library.TrackTags
import org.junit.Test

class TagMapperTest {

    @Test
    fun `maps a fully tagged file`() {
        val tags = TagMapper.map(
            RawTags(
                title = "Song", artist = "Band", album = "Record", albumArtist = "Band", genre = "Rock",
                year = "1999", track = "5", disc = "2", compilation = "0",
                durationSec = 183.4567, sampleRate = 96_000, bitsPerSample = 24, channels = "2",
            ),
        )
        assertThat(tags).isEqualTo(
            TrackTags(
                title = "Song", artist = "Band", album = "Record", albumArtist = "Band", genre = "Rock",
                year = 1999, trackNumber = 5, discNumber = 2, isCompilation = false,
                durationMs = 183_457, sampleRateHz = 96_000, bitsPerSample = 24, channelCount = 2, releaseDate = "1999",
            ),
        )
    }

    @Test
    fun `missing everything maps to untagged`() {
        assertThat(TagMapper.map(RawTags())).isEqualTo(TrackTags.UNTAGGED)
    }

    @Test
    fun `blank, NUL padded and BOM values count as missing or are trimmed`() {
        val tags = TagMapper.map(
            RawTags(title = "  ", artist = "Band\u0000\u0000", album = "﻿Record ", albumArtist = "\u0000", genre = ""),
        )
        assertThat(tags.title).isNull()
        assertThat(tags.artist).isEqualTo("Band")
        assertThat(tags.album).isEqualTo("Record")
        assertThat(tags.albumArtist).isNull()
        assertThat(tags.genre).isNull()
    }

    @Test
    fun `numbers with totals, padding or dates parse, garbage and zero do not`() {
        val tags = TagMapper.map(RawTags(year = "2001-05-03T00:00", track = "03/12", disc = " 1/2 "))
        assertThat(tags.year).isEqualTo(2001)
        assertThat(tags.trackNumber).isEqualTo(3)
        assertThat(tags.discNumber).isEqualTo(1)

        val garbled = TagMapper.map(RawTags(year = "0", track = "A1", disc = "/2", durationSec = -1.0, sampleRate = 0))
        assertThat(garbled.year).isNull()
        assertThat(garbled.trackNumber).isNull()
        assertThat(garbled.discNumber).isNull()
        assertThat(garbled.durationMs).isEqualTo(0L)
        assertThat(garbled.sampleRateHz).isNull()

        assertThat(TagMapper.map(RawTags(year = "99")).year).isNull()
        assertThat(TagMapper.map(RawTags(durationSec = Double.NaN)).durationMs).isEqualTo(0L)
    }

    @Test
    fun `compilation flag accepts the usual true spellings`() {
        for (value in listOf("1", "true", "TRUE", " yes ")) {
            assertThat(TagMapper.map(RawTags(compilation = value)).isCompilation).isTrue()
        }
        for (value in listOf(null, "", "0", "false", "no", "2x")) {
            assertThat(TagMapper.map(RawTags(compilation = value)).isCompilation).isFalse()
        }
    }

    @Test
    fun `channel descriptions map to counts`() {
        fun channels(value: String?) = TagMapper.map(RawTags(channels = value)).channelCount
        assertThat(channels("1")).isEqualTo(1)
        assertThat(channels("Mono")).isEqualTo(1)
        assertThat(channels("Joint Stereo")).isEqualTo(2)
        assertThat(channels("Dual mono")).isEqualTo(2)
        assertThat(channels("6")).isEqualTo(6)
        assertThat(channels("surround")).isNull()
        assertThat(channels("0")).isNull()
        assertThat(channels(null)).isNull()
    }

    @Test
    fun `codec comes from the reader and the header`() {
        fun codec(reader: String?, encoding: String? = null, format: String? = null, lossless: Boolean? = null) =
            TagMapper.map(RawTags(readerKey = reader, encodingType = encoding, format = format, lossless = lossless)).codec
        assertThat(codec("mp3", format = "MPEG-1 Layer 3")).isEqualTo("MP3")
        assertThat(codec("flac", encoding = "FLAC 24 bits")).isEqualTo("FLAC")
        assertThat(codec("m4a", encoding = "Apple Lossless")).isEqualTo("ALAC")
        assertThat(codec("m4a", encoding = "ALAC")).isEqualTo("ALAC")
        assertThat(codec("m4a", encoding = "AAC", lossless = false)).isEqualTo("AAC")
        assertThat(codec("ogg", encoding = "Ogg Vorbis v1")).isEqualTo("Vorbis")
        assertThat(codec("wav", encoding = "WAV PCM 16 bits")).isEqualTo("WAV")
        assertThat(codec("aif")).isEqualTo("AIFF")
        assertThat(codec("aifc")).isEqualTo("AIFF")
        assertThat(codec(null)).isNull()
        assertThat(codec("xyz")).isNull()
    }

    @Test
    fun `bitrate keeps positive values only`() {
        assertThat(TagMapper.map(RawTags(bitrateKbps = 320)).bitrateKbps).isEqualTo(320)
        assertThat(TagMapper.map(RawTags(bitrateKbps = 0)).bitrateKbps).isNull()
        assertThat(TagMapper.map(RawTags(bitrateKbps = -1)).bitrateKbps).isNull()
    }

    @Test
    fun `release date keeps as much of the tagged date as is valid`() {
        fun date(value: String?) = TagMapper.map(RawTags(year = value)).releaseDate
        assertThat(date("2020-12-10")).isEqualTo("2020-12-10")
        assertThat(date("2020-12-10T00:00:00Z")).isEqualTo("2020-12-10")
        assertThat(date("2020/3/7")).isEqualTo("2020-03-07")
        assertThat(date("2020-12")).isEqualTo("2020-12")
        assertThat(date(" 2020 ")).isEqualTo("2020")
        assertThat(date("2020-13-01")).isEqualTo("2020")
        assertThat(date("2021-02-30")).isEqualTo("2021-02")
        assertThat(date("99")).isNull()
        assertThat(date(null)).isNull()
    }

    @Test
    fun `copyright is cleaned like any text field`() {
        assertThat(TagMapper.map(RawTags(copyright = " ℗ 2020 Label\u0000")).copyright).isEqualTo("℗ 2020 Label")
        assertThat(TagMapper.map(RawTags(copyright = "  ")).copyright).isNull()
    }
}

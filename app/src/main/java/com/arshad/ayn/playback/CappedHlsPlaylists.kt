package com.arshad.ayn.playback

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.StreamKey
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.upstream.ParsingLoadable

/**
 * LIVE-D5: a live stream's quality cap as a format-list policy (M3-TRACK-01), the way the SABR list
 * is capped: the multivariant playlist keeps only the variants whose picture's short side is at
 * most [maxShortSide] lines (a portrait stream's 1080×1920 counts as 1080), so the track selector
 * never sees the rest. Media playlists parse as Media3 parses them.
 */
@OptIn(UnstableApi::class)
internal class CappedHlsPlaylistParserFactory(private val maxShortSide: Int) : HlsPlaylistParserFactory {
    private val parsers = DefaultHlsPlaylistParserFactory()

    override fun createPlaylistParser(): ParsingLoadable.Parser<HlsPlaylist> {
        val parser = parsers.createPlaylistParser()
        return ParsingLoadable.Parser { uri, input ->
            when (val playlist = parser.parse(uri, input)) {
                is HlsMultivariantPlaylist -> playlist.cappedAt(maxShortSide)
                else -> playlist
            }
        }
    }

    override fun createPlaylistParser(
        multivariantPlaylist: HlsMultivariantPlaylist,
        previousMediaPlaylist: HlsMediaPlaylist?,
    ): ParsingLoadable.Parser<HlsPlaylist> = parsers.createPlaylistParser(multivariantPlaylist, previousMediaPlaylist)
}

/**
 * The playlist with only the variants whose short side is at most [maxShortSide]; a variant of
 * unknown size stays. With none that small, the smallest alone: a preference never leaves a stream
 * unplayable. Audio and subtitle renditions stay; `copy` drops alternate video and closed-caption
 * renditions, which Ayn doesn't use (captions come from YouTube's timedtext, M7).
 */
@OptIn(UnstableApi::class)
internal fun HlsMultivariantPlaylist.cappedAt(maxShortSide: Int): HlsMultivariantPlaylist {
    val fitting = variants.indices.filter { i -> variants[i].format.shortSide()?.let { it <= maxShortSide } ?: true }
    if (fitting.size == variants.size) return this
    val kept = fitting.ifEmpty { listOfNotNull(variants.indices.minByOrNull { variants[it].format.shortSide() ?: Int.MAX_VALUE }) }
    val keys = kept.map { StreamKey(HlsMultivariantPlaylist.GROUP_INDEX_VARIANT, it) } +
        audios.indices.map { StreamKey(HlsMultivariantPlaylist.GROUP_INDEX_AUDIO, it) } +
        subtitles.indices.map { StreamKey(HlsMultivariantPlaylist.GROUP_INDEX_SUBTITLE, it) }
    return copy(keys)
}

private fun Format.shortSide(): Int? =
    if (width == Format.NO_VALUE || height == Format.NO_VALUE) null else minOf(width, height)

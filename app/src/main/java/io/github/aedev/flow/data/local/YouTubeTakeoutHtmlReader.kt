package io.github.aedev.flow.data.local

import java.io.Reader

// Every Takeout activity entry sits in this cell, whatever language the archive is in.
private const val TAKEOUT_ACTIVITY_MARKUP = "content-cell"
private const val READ_SIZE = 65_536

// A link this close to the end of what was read waits for the next read, so its channel and date are in view.
private const val OVERLAP = 8_192
private const val BATCH_SIZE = 500
private const val CHANNEL_LINK_REACH = 2_000
private const val DATE_REACH = 2_000
private const val CELL_END = "</div>"

private val watchLinkPattern =
    Regex("""href="https://(www|music)\.youtube\.com/watch\?v=([\w-]{10,12})"[^>]*?>([^<]+)</a>""", RegexOption.IGNORE_CASE)
private val channelLinkPattern =
    Regex("""href="https://(?:www|music)\.youtube\.com/channel/([^"&\s]+)"[^>]*?>([^<]+)</a>""", RegexOption.IGNORE_CASE)
private val searchLinkPattern =
    Regex("""href="(https://(?:www|music)\.youtube\.com/(?:results|search)\?[^"]+)"""", RegexOption.IGNORE_CASE)

/** A video watched, as a Takeout activity file records it. */
internal data class TakeoutWatch(
    val videoId: String,
    val title: String,
    val channelName: String,
    val channelId: String,
    val watchedAt: Long,
    val isMusic: Boolean,
    /** False when the file gave no readable date and [watchedAt] only keeps Takeout's order. */
    val dated: Boolean = true,
)

/** What a Takeout activity HTML file held. [watches] counts what was handed to the batch callback. */
internal data class TakeoutHtmlActivity(
    val watches: Int,
    val searches: List<TakeoutSearch>,
)

/**
 * Streams a Takeout activity HTML file, handing the videos watched on YouTube and YouTube Music to
 * [onWatches] in batches and keeping the searches. Takeout names the watch and search history files
 * in the account's language, so both are read from every file. Each entry keeps the date Takeout
 * wrote under it ([takeoutEntryTime]). An entry without a readable date is stamped from [now] down,
 * newest first as Takeout lists them, so it still sorts above the dated ones. With
 * [requireActivityMarkup], a file that never shows the My Activity layout gives nothing, so other
 * HTML in the archive is ignored.
 */
internal suspend fun readTakeoutHtmlActivity(
    reader: Reader,
    now: Long,
    requireActivityMarkup: Boolean,
    onWatches: suspend (List<TakeoutWatch>) -> Unit,
): TakeoutHtmlActivity {
    var markupSeen = !requireActivityMarkup
    var watched = 0
    val batch = mutableListOf<TakeoutWatch>()
    val searches = mutableListOf<TakeoutSearch>()
    val buffer = CharArray(READ_SIZE)
    var carry = ""
    var ended = false

    suspend fun flush() {
        if (batch.isEmpty()) return
        onWatches(batch.toList())
        batch.clear()
    }

    // The cell text after a link, where Takeout puts the channel and the date.
    fun cellAfter(
        window: String,
        from: Int,
        nextLink: Int,
    ): String {
        val end = minOf(window.length, nextLink, from + DATE_REACH)
        val cell = window.substring(minOf(from, end), end)
        val close = cell.indexOf(CELL_END)
        return if (close >= 0) cell.substring(0, close) else cell
    }

    while (!ended) {
        val count = reader.read(buffer)
        ended = count == -1
        val window = if (ended) carry else carry + String(buffer, 0, count)
        if (!markupSeen) markupSeen = window.contains(TAKEOUT_ACTIVITY_MARKUP)
        // Links that start from here on are read in the next round.
        val limit = if (ended) window.length else maxOf(0, window.length - OVERLAP)

        val channels = channelLinkPattern.findAll(window).toList()
        val watches = watchLinkPattern.findAll(window).toList()
        var channelIndex = 0
        for ((i, match) in watches.withIndex()) {
            if (match.range.first >= limit) break
            while (channelIndex < channels.size && channels[channelIndex].range.first <= match.range.first) channelIndex++
            val channel = channels.getOrNull(channelIndex)?.takeIf { it.range.first - match.range.last < CHANNEL_LINK_REACH }
            val nextLink = watches.getOrNull(i + 1)?.range?.first ?: window.length
            val at = takeoutEntryTime(cellAfter(window, match.range.last + 1, nextLink))
            batch +=
                TakeoutWatch(
                    videoId = match.groupValues[2].trim(),
                    title = match.groupValues[3].trim().unescapedHtml(),
                    channelName =
                        channel
                            ?.groupValues
                            ?.get(2)
                            ?.trim()
                            ?.unescapedHtml()
                            .orEmpty(),
                    channelId =
                        channel
                            ?.groupValues
                            ?.get(1)
                            ?.trim()
                            .orEmpty(),
                    watchedAt = at ?: (now - (watched + batch.size)),
                    isMusic = match.groupValues[1].equals("music", ignoreCase = true),
                    dated = at != null,
                )
        }

        val searchLinks = searchLinkPattern.findAll(window).toList()
        for ((i, match) in searchLinks.withIndex()) {
            if (match.range.first >= limit) break
            val (query, isMusic) = takeoutSearchOf(match.groupValues[1].unescapedHtml()) ?: continue
            val nextLink = searchLinks.getOrNull(i + 1)?.range?.first ?: window.length
            val at = takeoutEntryTime(cellAfter(window, match.range.last + 1, nextLink))
            searches += TakeoutSearch(query, at ?: (now - searches.size), isMusic)
        }

        carry = window.substring(limit)
        if (markupSeen && batch.size >= BATCH_SIZE) {
            watched += batch.size
            flush()
        }
    }
    if (!markupSeen) return TakeoutHtmlActivity(0, emptyList())
    watched += batch.size
    flush()
    return TakeoutHtmlActivity(watched, searches)
}

internal fun String.unescapedHtml(): String =
    replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&#x27;", "'")
        .replace("&amp;", "&")

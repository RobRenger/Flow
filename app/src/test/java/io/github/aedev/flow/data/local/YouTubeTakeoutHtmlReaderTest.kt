package io.github.aedev.flow.data.local

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.StringReader
import java.time.Instant
import java.time.ZoneId

class YouTubeTakeoutHtmlReaderTest {
    private fun cell(
        videoId: String,
        title: String,
        channelId: String,
        channel: String,
    ) = """<div class="content-cell mdl-cell">Watched <a href="https://www.youtube.com/watch?v=$videoId">$title</a><br>""" +
        """<a href="https://www.youtube.com/channel/$channelId">$channel</a><br>Sep 3, 2026</div>"""

    private suspend fun read(
        html: String,
        requireActivityMarkup: Boolean = true,
    ): Pair<TakeoutHtmlActivity, List<TakeoutWatch>> {
        val seen = mutableListOf<TakeoutWatch>()
        val result = readTakeoutHtmlActivity(StringReader(html), now = 1_000, requireActivityMarkup = requireActivityMarkup) { seen += it }
        return result to seen
    }

    @Test
    fun `each watched video comes with its channel, newest first`() =
        runTest {
            val (result, watches) =
                read(
                    cell("dQw4w9WgXcQ", "Never &amp; Ever", "UCuAXFkgsw1L7xaCfnd5JJOw", "Rick") +
                        cell("kcxK1Tnwy5M", "Second", "UC2", "Other"),
                )

            assertThat(result.watches).isEqualTo(2)
            assertThat(watches.map { it.videoId }).containsExactly("dQw4w9WgXcQ", "kcxK1Tnwy5M").inOrder()
            assertThat(watches.first().title).isEqualTo("Never & Ever")
            assertThat(watches.first().channelName).isEqualTo("Rick")
            assertThat(watches.first().channelId).isEqualTo("UCuAXFkgsw1L7xaCfnd5JJOw")
            assertThat(watches.map { it.watchedAt }).containsExactly(1_000L, 999L).inOrder()
        }

    @Test
    fun `other html in the archive gives nothing unless it is known to be history`() =
        runTest {
            val page = """<html><a href="https://www.youtube.com/watch?v=dQw4w9WgXcQ">Link</a></html>"""

            assertThat(read(page).first.watches).isEqualTo(0)
            assertThat(read(page).second).isEmpty()
            assertThat(read(page, requireActivityMarkup = false).first.watches).isEqualTo(1)
        }

    @Test
    fun `youtube music watches and searches from both services are read too`() =
        runTest {
            val html =
                """<div class="content-cell">Watched <a href="https://music.youtube.com/watch?v=ccccccccccc">Song</a></div>""" +
                    """<div class="content-cell">Searched for """ +
                    """<a href="https://www.youtube.com/results?search_query=lofi+%26+rain">lofi &amp; rain</a></div>""" +
                    """<div class="content-cell">Searched for <a href="https://music.youtube.com/search?q=jazz">jazz</a></div>"""

            val (result, watches) = read(html)

            assertThat(watches.single().isMusic).isTrue()
            assertThat(result.searches.map { it.query to it.isMusic }).containsExactly("lofi & rain" to false, "jazz" to true).inOrder()
        }

    @Test
    fun `each watch keeps the date takeout wrote under it`() =
        runTest {
            val html =
                """<div class="content-cell">Je hebt naar&nbsp;<a href="https://www.youtube.com/watch?v=tRsQsTMvPNg">Claude FM</a>&nbsp;gekeken<br>""" +
                    """<a href="https://www.youtube.com/channel/UCV03">Claude</a><br>21 okt 2026, 13:39:00 CEST<br></div>""" +
                    """<div class="content-cell">Watched <a href="https://music.youtube.com/watch?v=ccccccccccc">Song</a><br>""" +
                    """Mar 14, 2025, 12:57:26${'\u202f'}PM CET<br></div>""" +
                    """<div class="content-cell">Je hebt gezocht naar <a href="https://www.youtube.com/results?search_query=lofi">lofi</a><br>""" +
                    """3 jan 2026, 08:00:00 UTC<br></div>"""

            val (result, watches) = read(html)

            assertThat(watches.map { it.watchedAt })
                .containsExactly(Instant.parse("2026-10-21T11:39:00Z").toEpochMilli(), Instant.parse("2025-03-14T11:57:26Z").toEpochMilli())
                .inOrder()
            assertThat(watches.all { it.dated }).isTrue()
            assertThat(result.searches.single().searchedAt).isEqualTo(Instant.parse("2026-01-03T08:00:00Z").toEpochMilli())
        }

    @Test
    fun `a watch read across two reads still gets its channel and date`() =
        runTest {
            val one =
                """<div class="content-cell">Watched <a href="https://www.youtube.com/watch?v=dQw4w9WgXcQ">Video</a><br>""" +
                    """<a href="https://www.youtube.com/channel/UC1">Rick</a><br>Oct 21, 2026, 1:39:00 PM GMT+02:00<br></div>"""
            // Over 64k of padding puts later cells across the reader's read boundary.
            val html = (0 until 400).joinToString("") { one.replace("dQw4w9WgXcQ", "v%010d".format(it)) + " ".repeat(200) }

            val (result, watches) = read(html)

            assertThat(result.watches).isEqualTo(400)
            assertThat(watches.map { it.channelName }.toSet()).containsExactly("Rick")
            assertThat(watches.map { it.watchedAt }.toSet()).containsExactly(Instant.parse("2026-10-21T11:39:00Z").toEpochMilli())
        }

    @Test
    fun `dates in other layouts are read and an unknown zone is the given one`() {
        val amsterdam = ZoneId.of("Europe/Amsterdam")
        fun at(text: String) = takeoutEntryTime(text, amsterdam)

        assertThat(at("<br>21.10.2026, 13:39:00 MESZ<br>")).isEqualTo(Instant.parse("2026-10-21T11:39:00Z").toEpochMilli())
        assertThat(at("<br>12 juil. 2026, 09:05:00 XYZ<br>")).isEqualTo(Instant.parse("2026-07-12T07:05:00Z").toEpochMilli())
        assertThat(at("<br>Dec 31, 2025, 12:00:00 AM PST<br>")).isEqualTo(Instant.parse("2025-12-31T08:00:00Z").toEpochMilli())
        assertThat(at("<br>Sep 3, 2026<br>")).isNull()
        assertThat(at("<a>Top 10 of 2024</a> gekeken")).isNull()
    }
}

package com.arshad.ayn.feature

import com.arshad.ayn.core.access.AccessException
import com.arshad.ayn.core.access.ItemsPage
import com.arshad.ayn.core.access.PageToken
import com.arshad.ayn.core.access.VideoId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [ShortsList]: paging on through its source's pages, a failed page, one ask at a time. */
class ShortsListTest {

    private val token2 = PageToken(id = "2")
    private val token3 = PageToken(id = "3")

    private fun ids(list: ShortsList): List<String> = list.shorts.value.map { it.id.value }

    @Test
    fun aListWithoutAFetchHasNoMore() = runTest {
        val list = ShortsList(listOf(testVideo("aaaaaaaaaaa")), next = token2)

        assertFalse(list.hasMore)
        assertFalse(list.loadMore())
        assertEquals(listOf("aaaaaaaaaaa"), ids(list))
    }

    @Test
    fun loadMoreAppendsTheNextPagesShortsNotAlreadyListed() = runTest {
        val pages = mapOf(
            token2 to ItemsPage(emptyList(), next = token3, shorts = listOf(testVideo("aaaaaaaaaaa"), testVideo("bbbbbbbbbbb"))),
            token3 to ItemsPage(emptyList(), next = null, shorts = listOf(testVideo("ccccccccccc"))),
        )
        val list = ShortsList(listOf(testVideo("aaaaaaaaaaa")), next = token2) { pages.getValue(it) }

        assertTrue(list.loadMore())
        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb"), ids(list))
        assertTrue(list.hasMore)
        assertTrue(list.loadMore())
        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc"), ids(list))
        assertFalse(list.hasMore)
        assertFalse(list.loadMore())
    }

    @Test
    fun aFailedPageEndsPagingAtTheLoadedShorts() = runTest {
        val list = ShortsList(listOf(testVideo("aaaaaaaaaaa")), next = token2) { throw AccessException("down", RuntimeException()) }

        assertFalse(list.loadMore())
        assertFalse(list.hasMore)
        assertEquals(listOf("aaaaaaaaaaa"), ids(list))
    }

    @Test
    fun anAskWhileAPageIsOnItsWayWaitsForItInsteadOfAskingAgain() = runTest {
        val gate = CompletableDeferred<Unit>()
        var asks = 0
        val list = ShortsList(listOf(testVideo("aaaaaaaaaaa")), next = token2) {
            asks++
            gate.await()
            ItemsPage(emptyList(), next = token3, shorts = listOf(testVideo("bbbbbbbbbbb")))
        }

        val first = async { list.loadMore() }
        val second = async { list.loadMore() }
        runCurrent()
        gate.complete(Unit)

        assertTrue(first.await())
        assertTrue(second.await())
        assertEquals(1, asks)
        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb"), ids(list))
    }

    @Test
    fun theListKeepsTheShortLastPlayed() {
        val list = ShortsList(listOf(testVideo("aaaaaaaaaaa"), testVideo("bbbbbbbbbbb")))

        list.played(VideoId("bbbbbbbbbbb"))

        assertEquals(VideoId("bbbbbbbbbbb"), list.lastPlayed.value)
    }
}

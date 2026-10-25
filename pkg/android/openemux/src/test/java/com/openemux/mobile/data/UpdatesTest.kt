package com.openemux.mobile.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UpdateVersionTest {
    @Test fun parsesTheFirstDottedNumber() {
        assertEquals(listOf(1, 2, 3), UpdateVersion.parse("v1.2.3"))
        assertEquals(listOf(1, 2), UpdateVersion.parse("1.2"))
        assertEquals(listOf(1, 2), UpdateVersion.parse("OpenEmux 1.2"))
    }

    @Test fun noNumberIsNull() {
        assertNull(UpdateVersion.parse(""))
        assertNull(UpdateVersion.parse(null))
        assertNull(UpdateVersion.parse("latest"))
    }

    @Test fun aNumberTooLargeForAnIntIsNull() {
        assertNull(UpdateVersion.parse("1.99999999999"))
    }

    @Test fun comparesNumerically() {
        assertTrue(UpdateVersion.isNewer("1.10.0", "1.9.0"))
        assertFalse(UpdateVersion.isNewer("1.9.0", "1.10.0"))
        assertTrue(UpdateVersion.isNewer("1.2.1", "1.2"))
    }

    @Test fun missingPartsAreZero() {
        assertFalse(UpdateVersion.isNewer("v1.2", "1.2.0"))
        assertFalse(UpdateVersion.isNewer("1.2.0", "1.2"))
        assertFalse(UpdateVersion.isNewer("1.17.0", "1.17.0"))
    }

    @Test fun noNumberIsNeverNewer() {
        assertFalse(UpdateVersion.isNewer("latest", "1.0.0"))
        assertFalse(UpdateVersion.isNewer("2.0.0", "dev"))
    }
}

class ParseReleaseTest {
    @Test fun readsTheTagAndThePage() {
        val release = parseRelease("""{"tag_name":"v1.18.0","name":"OpenEmux 1.18.0","html_url":"https://github.com/o/r/1.18.0"}""")
        assertEquals(Release("1.18.0", "https://github.com/o/r/1.18.0"), release)
    }

    @Test fun withoutAPageFallsBackToTheLatestRelease() {
        assertEquals(Release("1.18.0", DEFAULT_RELEASE_URL), parseRelease("""{"tag_name":"1.18.0"}"""))
    }

    @Test fun aPageOutsideGitHubFallsBackToTheLatestRelease() {
        assertEquals(Release("1.18.0", DEFAULT_RELEASE_URL), parseRelease("""{"tag_name":"1.18.0","html_url":"javascript:alert(1)"}"""))
        assertEquals(Release("1.18.0", DEFAULT_RELEASE_URL), parseRelease("""{"tag_name":"1.18.0","html_url":null}"""))
    }

    @Test fun withoutATagUsesTheName() {
        assertEquals(Release("1.18.0", DEFAULT_RELEASE_URL), parseRelease("""{"name":"OpenEmux 1.18.0"}"""))
    }

    @Test fun withoutAVersionIsNull() {
        assertNull(parseRelease("""{"html_url":"https://example"}"""))
        assertNull(parseRelease("""{"tag_name":"nightly"}"""))
    }

    @Test fun invalidJsonIsNull() {
        assertNull(parseRelease("not json"))
        assertNull(parseRelease("""["v1.18.0"]"""))
    }
}

class UpdateCheckerTest {
    private val latest = """{"tag_name":"v1.18.0","html_url":"https://github.com/o/r"}"""

    @Test fun aNewerReleaseIsAvailable() = runBlocking {
        assertEquals(UpdateState.Available(Release("1.18.0", "https://github.com/o/r")), UpdateChecker("1.17.0") { latest }.check())
    }

    @Test fun theSameReleaseIsUpToDate() = runBlocking {
        assertSame(UpdateState.UpToDate, UpdateChecker("1.18.0") { latest }.check())
    }

    @Test fun noReplyIsUnknown() = runBlocking {
        assertSame(UpdateState.Unknown, UpdateChecker("1.17.0") { null }.check())
    }

    @Test fun anUnreadableReplyIsUnknown() = runBlocking {
        assertSame(UpdateState.Unknown, UpdateChecker("1.17.0") { "<html>" }.check())
    }

    @Test fun aFailureIsUnknown() = runBlocking {
        assertSame(UpdateState.Unknown, UpdateChecker("1.17.0") { throw IllegalStateException("boom") }.check())
    }

    @Test fun cancellationIsNotSwallowed() = runBlocking {
        try {
            UpdateChecker("1.17.0") { throw CancellationException("gone") }.check()
            fail("expected the cancellation to propagate")
        } catch (_: CancellationException) {
        }
    }
}

class UpdateStoreTest {
    private val newer = UpdateState.Available(Release("1.18.0", "https://github.com/o/r"))

    private class Fetches(private val body: String? = """{"tag_name":"1.18.0","html_url":"https://github.com/o/r"}""") {
        var count = 0
        val fetch: suspend () -> String? = { count++; body }
    }

    private fun store(fetches: Fetches, enabled: Boolean = true, saved: MutableList<Boolean> = mutableListOf()) =
        UpdateStore(enabled, { saved += it }, UpdateChecker("1.17.0", fetches.fetch), Dispatchers.Unconfined)

    @Test fun checksOncePerProcess() = runBlocking {
        val fetches = Fetches()
        val store = store(fetches)
        store.checkOnce(this)!!.join()
        assertNull(store.checkOnce(this))
        assertEquals(1, fetches.count)
        assertEquals(newer, store.state.value)
    }

    @Test fun offNeverTouchesTheNetwork() = runBlocking {
        val fetches = Fetches()
        val store = store(fetches, enabled = false)
        assertNull(store.checkOnce(this))
        assertEquals(0, fetches.count)
        assertSame(UpdateState.Unknown, store.state.value)
    }

    @Test fun notNowOnlyHidesTheBanner() = runBlocking {
        val store = store(Fetches())
        store.checkOnce(this)!!.join()
        assertFalse(store.dismissed.value)
        store.dismiss()
        assertTrue(store.dismissed.value)
        assertEquals(newer, store.state.value)
    }

    @Test fun turningItOffForgetsTheResultAndIsSaved() = runBlocking {
        val saved = mutableListOf<Boolean>()
        val store = store(Fetches(), saved = saved)
        store.checkOnce(this)!!.join()
        store.setEnabled(false)
        assertFalse(store.enabled.value)
        assertSame(UpdateState.Unknown, store.state.value)
        store.setEnabled(true)
        assertTrue(store.enabled.value)
        // On again waits for the next start.
        assertSame(UpdateState.Unknown, store.state.value)
        assertEquals(listOf(false, true), saved)
    }

    @Test fun turnedOffWhileCheckingShowsNothing() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val body = """{"tag_name":"1.18.0"}"""
        val store = UpdateStore(true, {}, UpdateChecker("1.17.0") { gate.await(); body }, Dispatchers.Unconfined)
        val job = store.checkOnce(this)!!
        store.setEnabled(false)
        gate.complete(Unit)
        job.join()
        assertSame(UpdateState.Unknown, store.state.value)
    }

    @Test fun offThenOnWhileCheckingStillShowsNothing() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val body = """{"tag_name":"1.18.0"}"""
        val store = UpdateStore(true, {}, UpdateChecker("1.17.0") { gate.await(); body }, Dispatchers.Unconfined)
        val job = store.checkOnce(this)!!
        store.setEnabled(false)
        store.setEnabled(true)
        gate.complete(Unit)
        job.join()
        assertSame(UpdateState.Unknown, store.state.value)
    }

    @Test fun aFailedCheckStaysUnknown() = runBlocking {
        val store = store(Fetches(body = null))
        store.checkOnce(this)!!.join()
        assertSame(UpdateState.Unknown, store.state.value)
    }
}

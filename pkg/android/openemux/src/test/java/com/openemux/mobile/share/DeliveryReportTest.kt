package com.openemux.mobile.share

import com.openemux.mobile.data.Console
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DeliveryReportTest {
    private val lib = FakeLibrary()
    private val paths = lib.paths

    private fun plan(manifest: Manifest, unchecked: Set<String> = emptySet()) =
        planReceive(manifest, paths, CoreIdentities(paths), unchecked = unchecked, probe = FakeSpace())

    /** A clean end: what the plan did not ask for comes back skipped, as [IncomingTransfer] reports it. */
    private fun done(plan: ReceivePlan, extra: List<Pair<String, String?>> = emptyList()) =
        ReceiveResult(TransferStatus.DONE, skipped = plan.decisions.filter { !it.send }.map { it.itemId to it.reason } + extra)

    // -- receiving -----------------------------------------------------------------

    @Test
    fun `everything placed is a complete report`() {
        val offer = manifest {
            game("g1", "SFC", "One") { item("rom", "One.sfc") }
            game("g2", "SFC", "Two") { item("rom", "Two.sfc") }
        }
        val p = plan(offer)
        val report = receiveReport(p, done(p))
        assertEquals(DeliveryReport(2, 2, emptyList()), report)
        assertTrue(report.complete)
    }

    @Test
    fun `a disc game kept out by a local file of its name is listed, not dropped`() {
        File(paths.roms(Console.PS), "Disc.cue").put("my own sheet")
        val offer = manifest {
            game("g1", "SFC", "One") { item("rom", "One.sfc") }
            game("g2", "SFC", "Two") { item("rom", "Two.sfc") }
            game("g3", "PS", "Disc") {
                item("rom", "Disc.cue", "cue".toByteArray(), role = "cue")
                item("rom", "Disc.bin", "bin".toByteArray(), role = "bin")
            }
        }
        val p = plan(offer)
        val report = receiveReport(p, done(p))
        assertEquals(DeliveryReport(2, 3, listOf(MissingGame("Disc", "PS", SkipReason.EXISTS_SKIP))), report)
        assertFalse(report.complete)
    }

    @Test
    fun `a ROM already here counts when something else of the game arrived`() {
        val sfc = paths.roms(Console.SFC)
        File(sfc, "Saved.sfc").put("rom")
        File(sfc, "Same.sfc").put("rom")
        val offer = manifest {
            game("g1", "SFC", "Saved") {
                item("rom", "Saved.sfc", "rom".toByteArray())
                item("battery_save", "Saved.srm", "save".toByteArray())
            }
            game("g2", "SFC", "Same") { item("rom", "Same.sfc", "rom".toByteArray()) }
        }
        val p = plan(offer)
        assertEquals(DeliveryReport(1, 2, listOf(MissingGame("Same", "SFC", SkipReason.EXISTS_SKIP))), receiveReport(p, done(p)))
    }

    @Test
    fun `an unsupported console is listed under the console the offer named`() {
        val offer = manifest { game("g1", "XYZ", "Alien") { item("rom", "Alien.xyz") } }
        val p = plan(offer)
        assertEquals(DeliveryReport(0, 1, listOf(MissingGame("Alien", "XYZ", SkipReason.UNSUPPORTED_CONSOLE))), receiveReport(p, done(p)))
    }

    @Test
    fun `a game the user unchecked is neither counted nor listed`() {
        val offer = manifest {
            game("g1", "SFC", "One") { item("rom", "One.sfc") }
            game("g2", "SFC", "Two") { item("rom", "Two.sfc") }
        }
        val p = plan(offer, unchecked = setOf("g2"))
        assertEquals(DeliveryReport(1, 1, emptyList()), receiveReport(p, done(p)))
    }

    @Test
    fun `a planned ROM that was not written is missing, with the transfer's reason`() {
        val offer = manifest { game("g1", "SFC", "One") { item("rom", "One.sfc") } }
        val p = plan(offer)
        val report = receiveReport(p, done(p, listOf("g1i1" to ErrorCode.NO_SPACE)))
        assertEquals(DeliveryReport(0, 1, listOf(MissingGame("One", "SFC", ErrorCode.NO_SPACE))), report)
    }

    @Test
    fun `a game with nothing planned falls back to why its ROM is absent`() {
        val game = GamePlan("g1", Console.SFC, "Empty", null).apply { absentReason = SkipReason.INVALID_ITEM }
        val p = ReceivePlan(listOf(game))
        assertEquals(listOf(MissingGame("Empty", "SFC", SkipReason.INVALID_ITEM)), receiveReport(p, ReceiveResult(TransferStatus.DONE)).missing)
    }

    // -- sending -------------------------------------------------------------------

    private fun built(block: ManifestDsl.() -> Unit) = BuiltManifest(ManifestDsl().apply(block).json(), emptyMap(), emptyList())

    @Test
    fun `everything sent is a complete report`() {
        val m = built { game("g1", "SFC", "One") { item("rom", "One.sfc"); item("battery_save", "One.srm") } }
        val report = sendReport(m, emptyList(), SendResult(SendResult.DONE, listOf("g1i1", "g1i2")))
        assertEquals(DeliveryReport(1, 1, emptyList()), report)
    }

    @Test
    fun `the receiver's answers decide which games arrived, and why the others did not`() {
        val m = built {
            game("g1", "SFC", "Sent") { item("rom", "Sent.sfc") }
            // The sheet file is not first, and its answer is still the one shown.
            game("g2", "PS", "Unchecked") {
                item("rom", "Unchecked.bin", role = "bin")
                item("rom", "Unchecked.cue", role = "cue")
            }
            game("g3", "SFC", "Saved") { item("rom", "Saved.sfc"); item("battery_save", "Saved.srm") }
            game("g4", "SFC", "There") { item("rom", "There.sfc") }
            game("g5", "SFC", "Unanswered") { item("rom", "Unanswered.sfc") }
            game("g6", "SFC", "Save only") { item("battery_save", "Save only.srm") }
        }
        val result = SendResult(
            SendResult.DONE,
            sent = listOf("g1i1", "g3i2", "g6i1"),
            skipped = listOf(
                "g2i1" to SkipReason.INVALID_ITEM, "g2i2" to SkipReason.USER_UNCHECKED,
                "g3i1" to SkipReason.EXISTS_SKIP, "g4i1" to SkipReason.EXISTS_SKIP, "g5i1" to null,
            ),
        )
        val excluded = listOf(ExcludedGame("PS/Broken.cue", "PS", "Broken", SkipReason.UNIT_INCOMPLETE))
        val report = sendReport(m, excluded, result)
        assertEquals(3, report.delivered)
        assertEquals(7, report.total)
        assertEquals(
            listOf(
                MissingGame("Unchecked", "PS", SkipReason.USER_UNCHECKED),
                MissingGame("There", "SFC", SkipReason.EXISTS_SKIP),
                MissingGame("Unanswered", "SFC", null),
                MissingGame("Broken", "PS", SkipReason.UNIT_INCOMPLETE),
            ),
            report.missing,
        )
    }

    @Test
    fun `an empty manifest is an empty, complete report`() {
        assertEquals(DeliveryReport(0, 0, emptyList()), sendReport(built { }, emptyList(), SendResult(SendResult.DONE)))
    }
}

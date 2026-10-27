package com.openemux.mobile.share

/** A game that did not make it, as the result card lists it; [reason] is a [SkipReason], null when the peer gave none. */
data class MissingGame(val title: String, val console: String, val reason: String?)

/**
 * How many of the games a transfer was about actually arrived (#615), and
 * which did not, with why. The desktop's `report.py` counts the same way, so
 * both ends of a transfer show the same numbers.
 */
data class DeliveryReport(val delivered: Int, val total: Int, val missing: List<MissingGame>) {
    val complete: Boolean get() = missing.isEmpty()
}

/**
 * The receiver's report for [plan] once [result] is in. A game counts when its
 * ROM was written, or when the library already had it and something else of it
 * (a save, a state, art) was. Games whose ROM the user unchecked on the offer
 * sheet are neither counted nor listed: that was a choice made on the screen.
 */
fun receiveReport(plan: ReceivePlan, result: ReceiveResult): DeliveryReport {
    val notWritten = result.skipped.toMap()
    var delivered = 0
    var total = 0
    val missing = mutableListOf<MissingGame>()
    for (game in plan.games) {
        if (game.userUnchecked) continue
        total++
        val written = game.decisions.filter { it.send && it.itemId !in notWritten }
        if (written.any { it.kind == Item.ROM } || game.romStatus == RomStatus.EXISTING && written.isNotEmpty()) {
            delivered++
            continue
        }
        val left = game.decisions.filter { !it.send || it.itemId in notWritten }
        val first = left.firstOrNull { it.kind == Item.ROM } ?: left.firstOrNull()
        val reason = when {
            game.romStatus == RomStatus.EXISTING -> SkipReason.EXISTS_SKIP
            first == null -> game.absentReason
            first.send -> notWritten[first.itemId]
            else -> first.reason
        }
        missing += MissingGame(game.title, game.consoleId, reason)
    }
    return DeliveryReport(delivered, total, missing)
}

/**
 * The sender's report for [built] (the manifest as offered) once [result] is
 * in. A game counts when every ROM file of it went, or when the receiver
 * already had its ROM and took something else of it. [excluded] are games the
 * user picked that never made it into the manifest: they count in the total.
 */
fun sendReport(built: BuiltManifest, excluded: List<ExcludedGame>, result: SendResult): DeliveryReport {
    val sent = result.sent.toSet()
    val skipped = result.skipped.toMap()
    var delivered = 0
    val missing = mutableListOf<MissingGame>()
    val games = built.json.getJSONArray("games")
    for (g in 0 until games.length()) {
        val game = games.getJSONObject(g)
        val list = game.getJSONArray("items")
        val items = (0 until list.length()).map { list.getJSONObject(it) }
        // The main file first, so its answer is the reason shown.
        val (roms, others) = items.partition { it.getString("kind") == Item.ROM }
        val romIds = roms.sortedByDescending { Item.isMainRom(Item.ROM, it) }.map { it.getString("id") }
        val otherIds = others.map { it.getString("id") }
        val romsSent = romIds.isNotEmpty() && romIds.all { it in sent }
        val romsThere = romIds.all { skipped[it] == SkipReason.EXISTS_SKIP }
        if (romsSent || romsThere && otherIds.any { it in sent }) {
            delivered++
            continue
        }
        val reason = (romIds + otherIds).firstOrNull { it !in sent }?.let { skipped[it] }
        missing += MissingGame(game.getString("title"), game.getString("console"), reason)
    }
    excluded.forEach { missing += MissingGame(it.title, it.console, it.reason) }
    return DeliveryReport(delivered, games.length() + excluded.size, missing)
}

package com.openemux.mobile.game

import com.openemux.mobile.launch.TABLETOP
import com.openemux.mobile.launch.TabletopBand
import com.openemux.mobile.ui.WindowLayout
import com.openemux.mobile.ui.forbiddenBand
import com.openemux.mobile.ui.tabletop
import kotlin.math.abs

// When the game switches to its tabletop skin and back, as plain values: the
// activity feeds in the window it sees and the overlay shown, and sends the
// engine whatever overlay these name.

/** How far the fold may sit from where the skin's tabletop overlays put it, in dp, and still use them. */
const val BAND_MATCH_DP = 8f

/**
 * What the skin's tabletop overlays were drawn for (SkinRenderer.TABLETOP_FILE): the band, in the launch window's dp,
 * every overlay's name, in skin.cfg's order, and the NDS screen gap each tabletop layout asks for. [flatLayout] is the
 * DeSmuME layout the flat overlays need when they name none: a package skin's, which the tabletop ones change.
 */
data class TabletopSkin(
    val bandStartDp: Float, val bandEndDp: Float, val overlays: List<String>, val gaps: Map<String, Int>,
    val flatLayout: String? = null,
) {
    companion object {
        /** Null for a file without a band or overlays: the skin then has no tabletop. */
        fun parse(text: String): TabletopSkin? {
            val props = text.lines().mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap()
            val start = props["band_start_dp"]?.toFloatOrNull() ?: return null
            val end = props["band_end_dp"]?.toFloatOrNull() ?: return null
            val overlays = props["overlays"]?.split(',')?.filter { it.isNotEmpty() }.orEmpty()
            if (overlays.isEmpty()) return null
            val gaps = props.filterKeys { it.startsWith("gap.") }.mapNotNull { (k, v) -> v.toIntOrNull()?.let { k.removePrefix("gap.") to it } }.toMap()
            return TabletopSkin(start, end, overlays, gaps, props["flat_layout"]?.takeIf { it.isNotEmpty() })
        }
    }
}

/**
 * Whether the game shows its tabletop skin in [window]: half open with the fold across a portrait window, both halves
 * big enough, and the fold where the skin's overlays put it (rotated the other way around an off-centre hinge, it is
 * not, and the game stays flat rather than draw on the crease).
 */
fun wantsTabletop(window: WindowLayout, skin: TabletopSkin?): Boolean {
    if (skin == null || window.widthDp >= window.heightDp || !window.tabletop) return false
    val band = forbiddenBand(window)!!
    return abs(band.startDp - skin.bandStartDp) <= BAND_MATCH_DP && abs(band.endDp - skin.bandEndDp) <= BAND_MATCH_DP
}

/**
 * Whether the skin shown has to be drawn again for a fold whose [band] (SkinGeometry.tabletopBandFor, in px of the
 * window it was seen in) there is: the skin has no tabletop overlays, or has them around a band elsewhere (drawn for
 * a fold seen in the window before, or for none).
 */
fun skinMissesFold(band: TabletopBand?, density: Float, skin: TabletopSkin?): Boolean {
    if (band == null) return false
    if (skin == null) return true
    return abs(band.startPx / density - skin.bandStartDp) > BAND_MATCH_DP || abs(band.endPx / density - skin.bandEndDp) > BAND_MATCH_DP
}

/**
 * The overlay to show when the posture asks for [tabletop] while [current] shows, among [overlays]; null when nothing
 * changes. Into tabletop from a portrait overlay: its tabletop twin (the keypad's, or on NDS the [ndsTabletop] layout's,
 * from a package skin's plain portrait overlay too).
 * Out of it: [flat], the overlay shown before, or the plain portrait one.
 */
fun tabletopTarget(current: String, tabletop: Boolean, overlays: Collection<String>, flat: String?, ndsTabletop: String?): String? {
    val inTabletop = TABLETOP in current
    if (tabletop == inTabletop) return null
    val keypad = current.startsWith("keypad-")
    val target = when {
        !tabletop -> flat ?: if (keypad) "keypad-portrait" else "portrait"
        "portrait" !in current -> return null
        keypad -> "keypad-$TABLETOP"
        ndsTabletop != null -> "$TABLETOP@$ndsTabletop"
        else -> TABLETOP
    }
    return target.takeIf { it in overlays }
}

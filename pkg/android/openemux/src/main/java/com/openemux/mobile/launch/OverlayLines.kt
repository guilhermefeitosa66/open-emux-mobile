package com.openemux.mobile.launch

import java.util.Locale

/**
 * The descriptor lines [SkinRenderer] and [ImportedSkinRenderer] write the
 * same way, so a change to how a control behaves reaches both kinds of skin.
 */
internal object OverlayLines {

    /** How far the D-pad's circle travels from the centre, over the D-pad's half-size (44 of its 120 units). */
    const val DPAD_TRAVEL = 44f / 60f

    /**
     * An eight-way D-pad area's keys: it is touched [left], [right], [up] and [down] times its half-size on
     * each side of its centre, steers by the thumb's angle to that centre with a dead zone in the middle, and
     * once held follows that finger over the whole screen, ahead of every other control. Binds other than the
     * engine's defaults are named.
     */
    fun dpad(cfg: StringBuilder, key: String, binds: List<String>, left: Float, right: Float, up: Float, down: Float) {
        reach(cfg, key, "x", "left", "right", left, right)
        reach(cfg, key, "y", "up", "down", up, down)
        cfg.append("${key}_range_mod = 20.0\n")
        cfg.append("${key}_range_mod_exclusive = true\n")
        cfg.append("${key}_dead_zone = 0.25\n")
        listOf("up", "down", "left", "right").zip(binds).forEach { (dir, bind) ->
            if (bind != dir) cfg.append("${key}_$dir = \"$bind\"\n")
        }
    }

    /** One axis's reach: both sides in one key when they match, each on its own otherwise. */
    private fun reach(cfg: StringBuilder, key: String, axis: String, a: String, b: String, ra: Float, rb: Float) {
        if (ra == rb) {
            cfg.append(String.format(Locale.US, "%s_reach_%s = %.4f\n", key, axis, ra))
        } else {
            cfg.append(String.format(Locale.US, "%s_reach_%s = %.4f\n", key, a, ra))
            cfg.append(String.format(Locale.US, "%s_reach_%s = %.4f\n", key, b, rb))
        }
    }

    /** The [image] the engine stretches over the desc's rect, moves with the thumb and recentres on release. */
    fun knob(cfg: StringBuilder, key: String, image: String) {
        cfg.append("${key}_overlay = \"$image\"\n")
        cfg.append("${key}_movable = true\n")
    }
}

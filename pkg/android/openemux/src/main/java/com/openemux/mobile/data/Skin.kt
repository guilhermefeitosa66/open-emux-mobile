package com.openemux.mobile.data

import androidx.annotation.StringRes
import com.retroarch.R

/**
 * The controller skin of each console, as the design's skin boards draw it:
 * a layout family, the console's colors and its buttons. Positions are in
 * the design's 390-wide portrait frame; [com.openemux.mobile.launch.SkinRenderer]
 * maps them to the screen. A bind is a RetroArch overlay button or hotkey;
 * a shoulder or console key without one is not drawn yet. Labels are the hardware's own, in
 * English like SELECT and START, so they are not translated.
 */
enum class SkinFamily { AB, ABXY, SIX, ANALOG, DUAL }

class SkinColors(
    val body: Int,
    val strip: Int,
    val icon: Int,
    val ink: Int,
    val dpad: Int,
    val mark: Int,
    val hub: Int,
    val shoulder: Int,
    val shoulderInk: Int,
    val pill: Int,
    val menu: Int,
    val menuInk: Int,
    val stick: Int,
    val knob: Int,
)

class SkinButton(val label: String, val bind: String?, val bg: Int, val fg: Int)

/** A button's offset from its cluster's centre and its size; [h] differs for the GameCube's beans. */
class SkinSlot(val dx: Float, val dy: Float, val w: Float, val h: Float = w)

enum class SkinSide { LEFT, RIGHT }

/** CENTER for a lone cluster, UPPER and LOWER when a side holds two. */
enum class SkinRow { CENTER, UPPER, LOWER }

sealed class SkinCluster(val side: SkinSide, val row: SkinRow) {
    /** [binds] are up, down, left, right: the Virtual Boy's second pad binds other buttons. */
    class DPad(side: SkinSide, row: SkinRow, val size: Float, val binds: List<String> = listOf("up", "down", "left", "right")) :
        SkinCluster(side, row)
    class Stick(
        side: SkinSide, row: SkinRow, val size: Float, val bind: String,
        val color: Int? = null, val knob: Int? = null, val label: String = "",
    ) : SkinCluster(side, row)
    class Face(side: SkinSide, row: SkinRow, val slots: List<SkinSlot>, val buttons: List<SkinButton>) : SkinCluster(side, row)
}

/** [labelRes], when set, names an app concept (the keypad, the disk) and replaces [label]. */
class SkinKey(val label: String, val bind: String?, @StringRes val labelRes: Int? = null)

private val KEYPAD_KEY = SkinKey("", KEYPAD_BIND, R.string.data_skin_keypad)

/** The console key that opens [Skin.keypad] in place of the face buttons. */
const val KEYPAD_BIND = "keypad"

class Skin(
    val name: String,
    val family: SkinFamily,
    val colors: SkinColors,
    val shouldersLeft: List<SkinKey>,
    val shouldersRight: List<SkinKey>,
    val clusters: List<SkinCluster>,
    val pills: List<SkinKey>,
    /** The controller's keypad, row by row, for the consoles whose games need it. */
    val keypad: List<List<SkinKey>> = emptyList(),
)

private object Slots {
    val ONE = listOf(SkinSlot(0f, 0f, 88f))
    val TWO = listOf(SkinSlot(-40f, 34f, 72f), SkinSlot(36f, -28f, 72f))
    val COL3 = listOf(SkinSlot(-34f, -60f, 54f), SkinSlot(34f, -10f, 54f), SkinSlot(-34f, 40f, 54f))
    val ROW4 = listOf(SkinSlot(-72f, 0f, 42f), SkinSlot(-24f, 0f, 42f), SkinSlot(24f, 0f, 42f), SkinSlot(72f, 0f, 42f))
    val DIAMOND = listOf(SkinSlot(0f, -56f, 54f), SkinSlot(56f, 0f, 54f), SkinSlot(0f, 56f, 54f), SkinSlot(-56f, 0f, 54f))
    val SIX = listOf(
        SkinSlot(-62f, 50f, 54f), SkinSlot(0f, 30f, 54f), SkinSlot(62f, 10f, 54f),
        SkinSlot(-62f, -20f, 44f), SkinSlot(0f, -40f, 44f), SkinSlot(62f, -60f, 44f),
    )
    val N64_AB = listOf(SkinSlot(-70f, 8f, 50f), SkinSlot(-10f, 44f, 62f))
    val C_DIAMOND = listOf(SkinSlot(0f, -36f, 32f), SkinSlot(36f, 0f, 32f), SkinSlot(0f, 36f, 32f), SkinSlot(-36f, 0f, 32f))
    val GC = listOf(SkinSlot(0f, 0f, 76f), SkinSlot(-64f, 40f, 42f), SkinSlot(64f, -12f, 42f, 60f), SkinSlot(-12f, -64f, 60f, 42f))
}

private fun c(v: Long) = v.toInt()

private val DARK = SkinColors(
    body = c(0xFF0B0B0E), strip = c(0xFF15151B), icon = c(0xFF8C8C9A), ink = c(0xFF8C8C9A),
    dpad = c(0xFF1F1F26), mark = c(0xFF6E6E7C), hub = c(0xFF17171C), shoulder = c(0xFF1F1F26), shoulderInk = c(0xFFA3A3B2),
    pill = c(0xFF1F1F26), menu = c(0xFF1F1F26), menuInk = c(0xFFF3F3F6), stick = c(0xFF1F1F26), knob = c(0xFF2E2E38),
)

/** The design's light-bodied consoles start from these instead. */
private val LIGHT = SkinColors(
    body = DARK.body, strip = c(0xFF2A2A30), icon = c(0xFFB4B4C0), ink = c(0xFF3A3A44),
    dpad = c(0xFF2C2C34), mark = c(0xFF8C8C96), hub = c(0xFF1E1E24), shoulder = c(0xFF8E8C98), shoulderInk = c(0xFF1E1E24),
    pill = c(0xFF6E6C78), menu = c(0xFF2C2C34), menuInk = c(0xFFF3F3F6), stick = c(0xFF3A3A42), knob = c(0xFF55555F),
)

private fun SkinColors.with(
    body: Long? = null, strip: Long? = null, icon: Long? = null, ink: Long? = null, dpad: Long? = null, mark: Long? = null,
    hub: Long? = null, shoulder: Long? = null, shoulderInk: Long? = null, pill: Long? = null, menu: Long? = null,
    menuInk: Long? = null, stick: Long? = null, knob: Long? = null,
) = SkinColors(
    body?.let(::c) ?: this.body, strip?.let(::c) ?: this.strip, icon?.let(::c) ?: this.icon, ink?.let(::c) ?: this.ink,
    dpad?.let(::c) ?: this.dpad, mark?.let(::c) ?: this.mark, hub?.let(::c) ?: this.hub,
    shoulder?.let(::c) ?: this.shoulder, shoulderInk?.let(::c) ?: this.shoulderInk, pill?.let(::c) ?: this.pill,
    menu?.let(::c) ?: this.menu, menuInk?.let(::c) ?: this.menuInk, stick?.let(::c) ?: this.stick, knob?.let(::c) ?: this.knob,
)

/** Buttons in slot order: labels with their binds, one background or one per button. */
private fun buttons(labels: List<Pair<String, String?>>, bg: List<Long>, fg: List<Long> = listOf(0xFFFFFFFF)) =
    labels.mapIndexed { i, (l, bind) -> SkinButton(l, bind, c(bg[i % bg.size]), c(fg[i % fg.size])) }

/** The usual side by side: D-pad (or stick) on the left, the face buttons on the right. */
private fun standard(slots: List<SkinSlot>, face: List<SkinButton>, stick: Boolean = false) = listOf(
    if (stick) SkinCluster.Stick(SkinSide.LEFT, SkinRow.CENTER, 130f, "analog_left")
    else SkinCluster.DPad(SkinSide.LEFT, SkinRow.CENTER, 150f),
    SkinCluster.Face(SkinSide.RIGHT, SkinRow.CENTER, slots, face),
)

private fun keys(vararg k: Pair<String, String?>) = k.map { (l, b) -> SkinKey(l, b) }

/** A 12-key pad typed on the keyboard (retrok_num0-9), with [left] and [right] beside the 0. */
private fun keypad12(left: String, right: String, leftLabel: String = "*", rightLabel: String = "#") =
    (0 until 3).map { r -> (1..3).map { SkinKey("${r * 3 + it}", "retrok_num${r * 3 + it}") } } +
        listOf(listOf(SkinKey(leftLabel, "retrok_$left"), SkinKey("0", "retrok_num0"), SkinKey(rightLabel, "retrok_$right")))

private val SELECT_START = keys("SELECT" to "select", "START" to "start")

// Two face buttons, left then right: the right one is the primary, RetroPad A.
private fun two(left: String, right: String) = listOf(left to "b", right to "a")

private fun skin(
    name: String, family: SkinFamily, colors: SkinColors, clusters: List<SkinCluster>,
    pills: List<SkinKey> = SELECT_START, left: List<SkinKey> = emptyList(), right: List<SkinKey> = emptyList(),
    keypad: List<List<SkinKey>> = emptyList(),
) = Skin(name, family, colors, left, right, clusters, pills, keypad)

// Genesis Plus GX and PicoDrive: A on Y, B on B, C on A, X on L, Y on X, Z on R, Mode on Select.
private val SIX_LABELS = listOf("A" to "y", "B" to "b", "C" to "a", "X" to "l", "Y" to "x", "Z" to "r")

// Beetle Saturn: A on B, B on A, C on R, X on Y, Y on X, Z on L; its L and R on L2 and R2.
private val SATURN_LABELS = listOf("A" to "b", "B" to "a", "C" to "r", "X" to "y", "Y" to "x", "Z" to "l")
private val DIAMOND_LABELS = listOf("X" to "x", "A" to "a", "B" to "b", "Y" to "y")
private val PS_LABELS = listOf("△" to "x", "○" to "a", "✕" to "b", "□" to "y")
private val SHOULDER_L = keys("L" to "l")
private val SHOULDER_R = keys("R" to "r")

/** The design's skin boards (SkinKit), console by console. */
val Console.skin: Skin
    get() = when (this) {
        Console.A2600 -> skin(
            "ATARI 2600", SkinFamily.AB,
            DARK.with(body = 0xFF2A1E15, strip = 0xFF1C140E, ink = 0xFFC9A27A, dpad = 0xFF111113, mark = 0xFF5A4A3C, menu = 0xFF1C140E, pill = 0xFF3A2A1E),
            standard(Slots.ONE, buttons(listOf("FIRE" to "b"), listOf(0xFFC8322B))),
            // Stella's Reset switch is on Start.
            keys("SELECT" to "select", "RESET" to "start"),
        )
        Console.A5200 -> skin(
            "ATARI 5200", SkinFamily.AB,
            DARK.with(body = 0xFF17171A, strip = 0xFF101012, ink = 0xFFA9A9B4, pill = 0xFF2A2A30),
            // atari800: Fire 1 is B, Fire 2 is A.
            standard(Slots.TWO, buttons(listOf("2" to "a", "1" to "b"), listOf(0xFFD2502C)), stick = true),
            keys("START" to "start", "PAUSE" to "select") + KEYPAD_KEY,
            keypad = keypad12("asterisk", "hash"),
        )
        Console.A7800 -> skin(
            "ATARI 7800", SkinFamily.AB,
            DARK.with(body = 0xFF1C1C21, strip = 0xFF121216, ink = 0xFFB4B4C0, pill = 0xFF2E2E35),
            // prosystem: button 1 is B, 2 is A, the console's Reset is X.
            standard(Slots.TWO, buttons(listOf("2" to "a", "1" to "b"), listOf(0xFFB8343A))),
            keys("SELECT" to "select", "PAUSE" to "start", "RESET" to "x"),
        )
        Console.LYNX -> skin(
            "LYNX", SkinFamily.AB,
            DARK.with(body = 0xFF1A1A1E, strip = 0xFF111114, ink = 0xFFA9A9B4),
            standard(Slots.TWO, buttons(two("B", "A"), listOf(0xFF3E3E48), listOf(0xFFE8E8EE))),
            // Beetle Lynx has Option 1 on R and Option 2 on L.
            keys("OPTION 1" to "r", "OPTION 2" to "l", "PAUSE" to "start"),
        )
        Console.CV -> skin(
            "COLECOVISION", SkinFamily.AB,
            DARK.with(body = 0xFF111113, strip = 0xFF0B0B0D, ink = 0xFFB4B4C0, pill = 0xFF2A2A30),
            // blueMSX: left fire on A, right fire on B; the keypad on the other buttons, 9 and 0 on the right stick.
            standard(Slots.TWO, buttons(listOf("L" to "a", "R" to "b"), listOf(0xFFD9632B))),
            listOf(KEYPAD_KEY),
            keypad = listOf(
                keys("1" to "x", "2" to "y", "3" to "r"),
                keys("4" to "l", "5" to "r2", "6" to "l2"),
                keys("7" to "r3", "8" to "l3", "9" to "r_x_minus"),
                keys("*" to "select", "0" to "r_x_plus", "#" to "start"),
            ),
        )
        Console.FDS -> skin(
            "FAMICOM DISK SYSTEM", SkinFamily.AB,
            DARK.with(
                body = 0xFF6E1A1F, strip = 0xFF4A1014, icon = 0xFFD8B9A8, ink = 0xFFE9E2D4, dpad = 0xFF1A1416, mark = 0xFF6E5A50,
                shoulder = 0xFF4A1014, pill = 0xFF4A1014, menu = 0xFF4A1014,
            ),
            standard(Slots.TWO, buttons(two("B", "A"), listOf(0xFFE9E2D4), listOf(0xFF6E1A1F))),
            // Nestopia turns the disk over on L.
            SELECT_START + SkinKey("", "l", R.string.data_skin_disk),
        )
        Console.FC -> skin(
            "NES · FAMICOM", SkinFamily.AB,
            DARK.with(body = 0xFF1E1E22, strip = 0xFF141417, ink = 0xFFB9B9C2, dpad = 0xFF0E0E10, pill = 0xFF2E2E33),
            standard(Slots.TWO, buttons(two("B", "A"), listOf(0xFFC8262B))),
        )
        Console.GB -> skin(
            "GAME BOY", SkinFamily.AB,
            LIGHT.with(body = 0xFFC7C3BD, ink = 0xFF2C2A6B, dpad = 0xFF26262B, mark = 0xFF6E6E78, pill = 0xFF8F8B86, menu = 0xFF8F8B86, menuInk = 0xFF1E1E24),
            standard(Slots.TWO, buttons(two("B", "A"), listOf(0xFF9B2257), listOf(0xFFF3E8EE))),
        )
        Console.GBC -> skin(
            "GAME BOY COLOR", SkinFamily.AB,
            DARK.with(body = 0xFF5B3592, strip = 0xFF3A2160, icon = 0xFFBBA7DE, ink = 0xFFD7C6F2, dpad = 0xFF1F1A2A, pill = 0xFF3A2160, menu = 0xFF3A2160),
            standard(Slots.TWO, buttons(two("B", "A"), listOf(0xFF2A2236), listOf(0xFFE8E0F5))),
        )
        Console.GBA -> skin(
            "GAME BOY ADVANCE", SkinFamily.ABXY,
            // Indigo, the launch color, with the original's light gray buttons.
            DARK.with(
                body = 0xFF4F4296, strip = 0xFF2E2762, icon = 0xFFB9B2E6, ink = 0xFFD6D0F2, dpad = 0xFFB9B9C4, mark = 0xFF6E6E80,
                hub = 0xFFA5A5B2, shoulder = 0xFFB9B9C4, shoulderInk = 0xFF2E2A55, pill = 0xFFB9B9C4, menu = 0xFF2E2762,
            ),
            standard(Slots.TWO, buttons(two("B", "A"), listOf(0xFFC4C4CE), listOf(0xFF2E2A55))),
            left = SHOULDER_L, right = SHOULDER_R,
        )
        Console.GG -> skin(
            "GAME GEAR", SkinFamily.AB,
            DARK.with(body = 0xFF18181B, ink = 0xFF9A9AA6, pill = 0xFF2F6FD6),
            standard(Slots.TWO, buttons(two("1", "2"), listOf(0xFF2E2E36), listOf(0xFFE8E8EE))),
            keys("START" to "start"),
        )
        Console.INTV -> skin(
            "INTELLIVISION", SkinFamily.AB,
            DARK.with(body = 0xFF2F2318, strip = 0xFF1F170F, icon = 0xFFB39466, ink = 0xFFD2B36A, dpad = 0xFF1A130C, mark = 0xFF7A6040, pill = 0xFF4A3824, menu = 0xFF1F170F),
            // FreeIntv: top button Y, lower left A, lower right B; the keypad from the keyboard.
            standard(Slots.COL3, buttons(listOf("1" to "y", "2" to "b", "3" to "a"), listOf(0xFFC9A24A), listOf(0xFF2F2318))),
            listOf(KEYPAD_KEY),
            keypad = keypad12("leftbracket", "rightbracket", "CLR", "ENT"),
        )
        Console.NGP -> skin(
            "NEO GEO POCKET", SkinFamily.AB,
            DARK.with(body = 0xFF2C3445, strip = 0xFF1C2230, icon = 0xFF9AA6BE, ink = 0xFFB8C2D6, dpad = 0xFF151A24, pill = 0xFF1C2230, menu = 0xFF1C2230),
            // The Neo Geo Pocket has A on the left; Beetle NGP reads it from B.
            standard(Slots.TWO, buttons(two("A", "B"), listOf(0xFFE6E8EE), listOf(0xFF2C3445))),
            keys("OPTION" to "start"),
        )
        Console.N64 -> skin(
            "NINTENDO 64", SkinFamily.ANALOG,
            DARK.with(
                body = 0xFF3C3C44, strip = 0xFF26262C, ink = 0xFFB4B4C0, dpad = 0xFF26262C, shoulder = 0xFF55555F, shoulderInk = 0xFFE8E8EE,
                stick = 0xFF26262C, knob = 0xFF4A4A55, pill = 0xFFC8312D, menu = 0xFF26262C,
            ),
            listOf(
                SkinCluster.DPad(SkinSide.LEFT, SkinRow.UPPER, 100f),
                SkinCluster.Stick(SkinSide.LEFT, SkinRow.LOWER, 112f, "analog_left"),
                // Mupen64Plus-Next reads the C buttons from the right stick.
                SkinCluster.Face(
                    SkinSide.RIGHT, SkinRow.UPPER, Slots.C_DIAMOND,
                    buttons(
                        listOf("C▲" to "r_y_minus", "C▶" to "r_x_plus", "C▼" to "r_y_plus", "C◀" to "r_x_minus"),
                        listOf(0xFFE8C22A), listOf(0xFF3C3C44),
                    ),
                ),
                SkinCluster.Face(SkinSide.RIGHT, SkinRow.LOWER, Slots.N64_AB, buttons(listOf("B" to "y", "A" to "b"), listOf(0xFF2E9E55, 0xFF2F5FD0))),
            ),
            keys("START" to "start"), left = keys("L" to "l", "Z" to "l2"), right = SHOULDER_R,
        )
        Console.NDS -> skin(
            "NINTENDO DS", SkinFamily.DUAL,
            DARK.with(body = 0xFF2A2B31, strip = 0xFF1C1D22, ink = 0xFFA9AAB6, dpad = 0xFF17171B, shoulder = 0xFF3A3B44, shoulderInk = 0xFFD8D8E0, pill = 0xFF3A3B44, menu = 0xFF1C1D22),
            standard(Slots.DIAMOND, buttons(DIAMOND_LABELS, listOf(0xFF41424C), listOf(0xFFE8E8EE))),
            left = SHOULDER_L, right = SHOULDER_R,
        )
        Console.GC -> skin(
            "GAMECUBE", SkinFamily.ANALOG,
            DARK.with(
                body = 0xFF3B2F7A, strip = 0xFF261E52, icon = 0xFFA9A0D8, ink = 0xFFC9C1F0, dpad = 0xFF9A98A8, mark = 0xFF3B2F7A, hub = 0xFF8A88A0,
                shoulder = 0xFF8E8AA6, shoulderInk = 0xFF1E1A3A, stick = 0xFF9A98A8, knob = 0xFFB9B7C6, pill = 0xFF261E52, menu = 0xFF261E52,
            ),
            listOf(
                SkinCluster.Stick(SkinSide.LEFT, SkinRow.UPPER, 112f, "analog_left"),
                SkinCluster.DPad(SkinSide.LEFT, SkinRow.LOWER, 84f),
                SkinCluster.Face(
                    SkinSide.RIGHT, SkinRow.UPPER, Slots.GC,
                    buttons(
                        listOf("A" to "a", "B" to "b", "X" to "x", "Y" to "y"),
                        listOf(0xFF2FAE6A, 0xFFD43C3C, 0xFFCFCFD8, 0xFFCFCFD8), listOf(0xFFFFFFFF, 0xFFFFFFFF, 0xFF3B2F7A, 0xFF3B2F7A),
                    ),
                ),
                SkinCluster.Stick(SkinSide.RIGHT, SkinRow.LOWER, 72f, "analog_right", c(0xFFC9A51F), c(0xFFE8C22A), "C"),
            ),
            keys("START" to "start"), left = keys("L" to "l2"), right = keys("Z" to "r", "R" to "r2"),
        )
        Console.O2 -> skin(
            "ODYSSEY²", SkinFamily.AB,
            DARK.with(body = 0xFF1A1A1A, strip = 0xFF111111, ink = 0xFFB0B0B0, pill = 0xFF2A2A2A),
            standard(Slots.ONE, buttons(listOf("ACTION" to "b"), listOf(0xFFD2382C))),
            listOf(KEYPAD_KEY),
            // O2EM's Clear and Enter are End and Return.
            keypad = keypad12("end", "enter", "CLR", "ENT"),
        )
        Console.SG1000 -> skin(
            "SG-1000", SkinFamily.AB,
            DARK.with(body = 0xFF232327, strip = 0xFF17171A, ink = 0xFFB4B4C0, pill = 0xFF2E2E33),
            standard(Slots.TWO, buttons(two("1", "2"), listOf(0xFFC73A35))),
            keys("PAUSE" to "start"),
        )
        Console.S32X -> skin(
            "32X", SkinFamily.SIX,
            DARK.with(body = 0xFF17171A, strip = 0xFF101012, ink = 0xFFE06A5A, pill = 0xFF2A2A30),
            standard(Slots.SIX, buttons(SIX_LABELS, listOf(0xFF34343B, 0xFF34343B, 0xFF34343B, 0xFF2A2A30, 0xFF2A2A30, 0xFF2A2A30), listOf(0xFFE0473A))),
            keys("MODE" to "select", "START" to "start"),
        )
        Console.MCD -> skin(
            "MEGA-CD · SEGA CD", SkinFamily.SIX,
            DARK.with(body = 0xFF1D1F25, strip = 0xFF14161B, ink = 0xFF7FA8D9, pill = 0xFF30333B),
            standard(Slots.SIX, buttons(SIX_LABELS, listOf(0xFF30333B), listOf(0xFF7FB0E8))),
            keys("MODE" to "select", "START" to "start"),
        )
        Console.MD -> skin(
            "MEGA DRIVE · GENESIS", SkinFamily.SIX,
            DARK.with(body = 0xFF141416, strip = 0xFF0E0E10, ink = 0xFFB9B9C2, pill = 0xFF2C2C30),
            standard(Slots.SIX, buttons(SIX_LABELS, listOf(0xFF2C2C30), listOf(0xFFE1404A))),
            keys("MODE" to "select", "START" to "start"),
        )
        Console.SMS -> skin(
            "MASTER SYSTEM", SkinFamily.AB,
            DARK.with(body = 0xFF1B1B1E, strip = 0xFF121214, ink = 0xFFD04A44, pill = 0xFFC0322D),
            standard(Slots.TWO, buttons(two("1", "2"), listOf(0xFF2C2C30))),
            keys("PAUSE" to "start"),
        )
        Console.SATURN -> skin(
            "SEGA SATURN", SkinFamily.SIX,
            LIGHT.with(body = 0xFFAFAFBA, ink = 0xFF2E2E38, dpad = 0xFF3A3A44, pill = 0xFF6E6E78),
            standard(Slots.SIX, buttons(SATURN_LABELS, listOf(0xFF4B4B55, 0xFF4B4B55, 0xFF4B4B55, 0xFF5E5E68, 0xFF5E5E68, 0xFF5E5E68), listOf(0xFFF3F3F6))),
            keys("START" to "start"), left = keys("L" to "l2"), right = keys("R" to "r2"),
        )
        Console.PS -> skin(
            "PLAYSTATION", SkinFamily.ANALOG,
            LIGHT.with(body = 0xFFBDBDC4, ink = 0xFF3A3A44, dpad = 0xFF8E8E98, mark = 0xFF4A4A55, hub = 0xFF7A7A84, shoulder = 0xFF9A9AA4, shoulderInk = 0xFF26262C, pill = 0xFF7A7A84),
            listOf(
                SkinCluster.DPad(SkinSide.LEFT, SkinRow.UPPER, 120f),
                SkinCluster.Stick(SkinSide.LEFT, SkinRow.LOWER, 96f, "analog_left"),
                SkinCluster.Face(SkinSide.RIGHT, SkinRow.UPPER, Slots.DIAMOND, buttons(PS_LABELS, listOf(0xFF3B3B44), listOf(0xFF3FBF8F, 0xFFE8525A, 0xFF7C9BEA, 0xFFD77FC2))),
                SkinCluster.Stick(SkinSide.RIGHT, SkinRow.LOWER, 96f, "analog_right"),
            ),
            left = keys("L2" to "l2", "L1" to "l"), right = keys("R1" to "r", "R2" to "r2"),
        )
        Console.PSP -> skin(
            "PSP", SkinFamily.ANALOG,
            DARK.with(body = 0xFF16161A, strip = 0xFF0F0F12, ink = 0xFF8C8C9A, dpad = 0xFF2A2A30, shoulder = 0xFF2A2A30, stick = 0xFF2A2A30, knob = 0xFF3A3A42, pill = 0xFF2A2A30),
            listOf(
                SkinCluster.DPad(SkinSide.LEFT, SkinRow.UPPER, 120f),
                SkinCluster.Stick(SkinSide.LEFT, SkinRow.LOWER, 72f, "analog_left"),
                SkinCluster.Face(SkinSide.RIGHT, SkinRow.UPPER, Slots.DIAMOND, buttons(PS_LABELS, listOf(0xFF2A2A30), listOf(0xFFC8C8D0))),
            ),
            left = SHOULDER_L, right = SHOULDER_R,
        )
        Console.SFC -> skin(
            "SUPER NINTENDO · SUPER FAMICOM", SkinFamily.ABXY,
            LIGHT.with(body = 0xFFC9C7CF, ink = 0xFF4A4856, shoulder = 0xFF9E9CA8, shoulderInk = 0xFF2A2832, pill = 0xFF6C6A76),
            standard(
                Slots.DIAMOND,
                buttons(DIAMOND_LABELS, listOf(0xFF3D63C9, 0xFFD63C3C, 0xFFE6B422, 0xFF2F9E57), listOf(0xFFFFFFFF, 0xFFFFFFFF, 0xFF3A2E00, 0xFFFFFFFF)),
            ),
            left = SHOULDER_L, right = SHOULDER_R,
        )
        Console.PCE -> skin(
            "PC ENGINE · TURBOGRAFX-16", SkinFamily.AB,
            LIGHT.with(body = 0xFFE6E4DF, ink = 0xFF4A4A50, dpad = 0xFF26262A, pill = 0xFF55555B),
            standard(Slots.TWO, buttons(two("II", "I"), listOf(0xFF26262A), listOf(0xFFF3F3F6))),
            keys("SELECT" to "select", "RUN" to "start"),
        )
        Console.PCECD -> skin(
            "PC ENGINE CD", SkinFamily.AB,
            DARK.with(body = 0xFF24252B, strip = 0xFF17181C, ink = 0xFFE2702B, dpad = 0xFF141418, pill = 0xFF3A3B44),
            standard(Slots.TWO, buttons(two("II", "I"), listOf(0xFF3A3B44), listOf(0xFFF2944F))),
            keys("SELECT" to "select", "RUN" to "start"),
        )
        Console.VECTREX -> skin(
            "VECTREX", SkinFamily.AB,
            DARK.with(body = 0xFF121214, strip = 0xFF0B0B0D, ink = 0xFF9A9AA6),
            standard(Slots.ROW4, buttons(listOf("1" to "a", "2" to "b", "3" to "x", "4" to "y"), listOf(0xFF3A3A42), listOf(0xFFE8E8EE))),
            emptyList(),
        )
        Console.VB -> skin(
            "VIRTUAL BOY", SkinFamily.AB,
            DARK.with(
                body = 0xFF141414, strip = 0xFF0C0C0C, icon = 0xFFA33A3A, ink = 0xFFD42A2A, dpad = 0xFF262626, mark = 0xFF7A1A1A,
                shoulder = 0xFF262626, shoulderInk = 0xFFE83A3A, pill = 0xFF262626, menu = 0xFF262626, menuInk = 0xFFE83A3A,
            ),
            listOf(
                SkinCluster.DPad(SkinSide.LEFT, SkinRow.CENTER, 150f),
                // Beetle VB's right pad: up L2, down L3, left R2, right R3.
                SkinCluster.DPad(SkinSide.RIGHT, SkinRow.UPPER, 110f, listOf("l2", "l3", "r2", "r3")),
                SkinCluster.Face(SkinSide.RIGHT, SkinRow.LOWER, Slots.TWO, buttons(two("B", "A"), listOf(0xFF2A2A2A), listOf(0xFFE83A3A))),
            ),
            left = SHOULDER_L, right = SHOULDER_R,
        )
        Console.WS -> skin(
            "WONDERSWAN", SkinFamily.AB,
            LIGHT.with(body = 0xFFD8D9DF, ink = 0xFF4A4A58, pill = 0xFF6E6E7A),
            standard(Slots.TWO, buttons(two("B", "A"), listOf(0xFF4A4A55), listOf(0xFFF3F3F6))),
            keys("START" to "start"),
        )
    }

/** A DeSmuME screen layout: its id in overlay names, its core option value, the picture's aspect and its name. */
class ScreenLayout(val id: String, val value: String, val aspect: Float, @StringRes val label: Int)

private val NDS_TOP = ScreenLayout("top", "top only", 256f / 192f, R.string.game_screens_top)
private val NDS_BOTTOM = ScreenLayout("bottom", "bottom only", 256f / 192f, R.string.game_screens_bottom)

/** The NDS layouts each orientation cycles through, in order; the last is the one it opens in by default. */
val NDS_LAYOUTS = mapOf(
    "portrait" to listOf(NDS_TOP, NDS_BOTTOM, ScreenLayout("stacked", "top/bottom", 256f / 384f, R.string.game_screens_stacked)),
    "landscape" to listOf(NDS_TOP, NDS_BOTTOM, ScreenLayout("side", "left/right", 512f / 192f, R.string.game_screens_side)),
)

/** The layout each orientation opens in, remembered across games (the file holds "portrait=id" and "landscape=id" lines). */
object NdsLayouts {
    private fun file(filesDir: java.io.File) = java.io.File(filesDir, "nds-layout")

    /** A saved layout the orientation no longer has falls back to its default. */
    fun load(filesDir: java.io.File): Pair<ScreenLayout, ScreenLayout> {
        val saved = runCatching { file(filesDir).readLines() }.getOrDefault(emptyList())
            .mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        fun pick(orientation: String) = NDS_LAYOUTS.getValue(orientation).let { list -> list.firstOrNull { it.id == saved[orientation] } ?: list.last() }
        return pick("portrait") to pick("landscape")
    }

    fun save(filesDir: java.io.File, orientation: String, layout: ScreenLayout) {
        val (portrait, landscape) = load(filesDir)
        val p = if (orientation == "portrait") layout else portrait
        val l = if (orientation == "landscape") layout else landscape
        runCatching { file(filesDir).writeText("portrait=${p.id}\nlandscape=${l.id}\n") }
    }

    /** The layout after [current] in [orientation]'s cycle. */
    fun next(orientation: String, current: String?): ScreenLayout {
        val list = NDS_LAYOUTS.getValue(orientation)
        return list[(list.indexOfFirst { it.id == current } + 1) % list.size]
    }
}

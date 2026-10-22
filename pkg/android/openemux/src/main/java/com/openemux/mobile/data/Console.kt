package com.openemux.mobile.data

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import androidx.compose.ui.res.stringResource
import com.retroarch.R

data class CoreDef(
    val id: String,
    val name: String,
    @StringRes val description: Int,
    val recommended: Boolean = false,
) {
    val fileName: String get() = "${id}_libretro_android.so"
}

/** [title] is the shader's own name, shown as is; null for "none", whose name is translated. */
data class ShaderDef(val id: String, private val title: String?, val preset: String?) {
    @Composable
    fun name(): String = title ?: stringResource(R.string.data_shader_none)

    fun name(context: Context): String = title ?: context.getString(R.string.data_shader_none)
}

val SHADERS = listOf(
    ShaderDef("none", null, null),
    ShaderDef("dot", "Dot", "handheld/dot.glslp"),
    ShaderDef("sharp", "Sharp Bilinear", "interpolation/sharp-bilinear-simple.glslp"),
    ShaderDef("zfast", "zfast CRT", "crt/zfast-crt.glslp"),
    ShaderDef("geom", "CRT Geom", "crt/crt-geom.glslp"),
)

fun shaderById(id: String?): ShaderDef = SHADERS.firstOrNull { it.id == id } ?: SHADERS[0]

data class CartColor(val id: String, @StringRes val label: Int, val swatch: Color)

/** Same ids and swatches as the desktop's cartridge_colors.py. */
val CART_COLORS = listOf(
    CartColor("default", R.string.data_color_default, Color(0xFF3A3A40)),
    CartColor("black", R.string.data_color_black, Color(0xFF26262A)),
    CartColor("white", R.string.data_color_white, Color(0xFFE8E4DA)),
    CartColor("red", R.string.data_color_red, Color(0xFFB23A34)),
    CartColor("orange", R.string.data_color_orange, Color(0xFFCC7A29)),
    CartColor("yellow", R.string.data_color_yellow, Color(0xFFD9A81F)),
    CartColor("green", R.string.data_color_green, Color(0xFF3F8C5B)),
    CartColor("teal", R.string.data_color_teal, Color(0xFF2C8A8A)),
    CartColor("blue", R.string.data_color_blue, Color(0xFF3167B0)),
    CartColor("purple", R.string.data_color_purple, Color(0xFF7A4FA3)),
    CartColor("pink", R.string.data_color_pink, Color(0xFFBE5289)),
    CartColor("gold", R.string.data_color_gold, Color(0xFFC6A02E)),
    CartColor("clear", R.string.data_color_clear, Color(0xFFB9C0C7)),
)

fun cartColorById(id: String?): CartColor = CART_COLORS.firstOrNull { it.id == id } ?: CART_COLORS[0]

/** Where the label goes inside the frame art, as fractions of the frame. */
data class LabelRect(val left: Float, val top: Float, val width: Float, val height: Float)

/** The desktop app's cartridge art: drawable cart_<name>[_color], its aspect and label window. */
data class CartFrame(val name: String, val aspect: Float, val label: LabelRect)

private fun frame(name: String, w: Float, h: Float, x: Float, y: Float, lw: Float, lh: Float) =
    CartFrame(name, w / h, LabelRect(x / w, y / h, lw / w, lh / h))

private fun core(id: String, name: String, @StringRes description: Int) = CoreDef(id, name, description)

/** Same systems, ids, extensions and core order as the desktop's systems.py. */
enum class Console(
    val id: String,
    val displayName: String,
    val short: String,
    val extensions: Set<String>,
    val artworkDir: String,
    /** Null for consoles shown as box art: discs, and the FDS. */
    val frame: CartFrame?,
    val shelfColor: Color,
    val gameAspect: Float,
    val cores: List<CoreDef>,
    val defaultShader: String,
    val faceButtons: Int,
    val screenScraperId: Int,
) {
    A2600(
        "A2600", "Atari 2600", "2600", setOf("a26", "bin", "rom"), "Atari_-_2600",
        frame("cart_a2600", 400f, 476f, 40.000f, 40.645f, 320.000f, 400.000f),
        Color(0xFF6B4A2E), 4f / 3f, listOf(core("stella", "Stella", R.string.data_core_stella)), "geom", 2, 26,
    ),
    A5200(
        "A5200", "Atari 5200", "5200", setOf("a52", "bin", "rom"), "Atari_-_5200",
        frame("cart_a5200", 400f, 368f, 54.667f, 42.667f, 287.333f, 231.333f),
        Color(0xFF5A4A3E), 4f / 3f, listOf(core("atari800", "Atari800", R.string.data_core_atari800)), "geom", 2, 40,
    ),
    A7800(
        "A7800", "Atari 7800", "7800", setOf("a78", "bin"), "Atari_-_7800",
        frame("cart_a7800", 400f, 476f, 40.000f, 40.645f, 320.000f, 400.000f),
        Color(0xFF4E4A58), 4f / 3f, listOf(core("prosystem", "ProSystem", R.string.data_core_prosystem)), "geom", 2, 41,
    ),
    LYNX(
        "LYNX", "Atari Lynx", "LYNX", setOf("lnx"), "Atari_-_Lynx",
        frame("cart_lynx", 400f, 417f, 10.417f, 90.278f, 379.861f, 260.417f),
        Color(0xFF3E4A5E), 160f / 102f, listOf(core("mednafen_lynx", "Beetle Lynx", R.string.data_core_beetle_lynx)), "dot", 2, 28,
    ),
    CV(
        "CV", "ColecoVision", "COLECO", setOf("col", "rom", "bin"), "Coleco_-_ColecoVision",
        frame("cart_cv", 400f, 461f, 47.333f, 22.000f, 301.333f, 309.333f),
        Color(0xFF2E3E5A), 4f / 3f,
        listOf(
            core("bluemsx", "blueMSX", R.string.data_core_bluemsx),
            core("gearcoleco", "Gearcoleco", R.string.data_core_gearcoleco),
        ),
        "geom", 2, 48,
    ),
    FDS(
        "FDS", "Famicom Disk System", "FDS", setOf("fds"), "Nintendo_-_Family_Computer_Disk_System", null,
        Color(0xFF8A3A3A), 4f / 3f,
        listOf(
            core("nestopia", "Nestopia", R.string.data_core_nestopia),
            core("fceumm", "FCEUmm", R.string.data_core_fceumm),
        ),
        "geom", 2, 106,
    ),
    FC(
        "FC", "Nintendo (NES) / Famicom", "NES", setOf("nes"), "Nintendo_-_Nintendo_Entertainment_System",
        frame("cart_fc", 400f, 449f, 161.68942f, 0f, 186.61885f, 306.46255f),
        Color(0xFF7A2E2E), 4f / 3f,
        listOf(
            core("nestopia", "Nestopia", R.string.data_core_nestopia),
            core("fceumm", "FCEUmm", R.string.data_core_fceumm),
            core("mesen", "Mesen", R.string.data_core_max_accuracy),
        ),
        "geom", 2, 3,
    ),
    GB(
        "GB", "Game Boy", "GB", setOf("gb"), "Nintendo_-_Game_Boy",
        frame("cart_gb", 177f, 200f, 24.137932f, 59.874607f, 128f, 110f),
        Color(0xFF4F5A3A), 10f / 9f,
        listOf(
            core("gambatte", "Gambatte", R.string.data_core_gambatte),
            core("mgba", "mGBA", R.string.data_core_mgba_gb),
        ),
        "dot", 2, 9,
    ),
    GBC(
        "GBC", "Game Boy Color", "GBC", setOf("gbc"), "Nintendo_-_Game_Boy_Color",
        frame("cart_gbc", 177f, 200f, 24.137932f, 59.874607f, 128f, 110f),
        Color(0xFF5A3A6B), 10f / 9f,
        listOf(
            core("gambatte", "Gambatte", R.string.data_core_gambatte),
            core("mgba", "mGBA", R.string.data_core_mgba_gbc),
        ),
        "dot", 2, 10,
    ),
    GBA(
        "GBA", "Game Boy Advance", "GBA", setOf("gba"), "Nintendo_-_Game_Boy_Advance",
        frame("cart_gba", 400f, 230f, 52.448154f, 51.132984f, 295.69769f, 152.55803f),
        Color(0xFF3B3486), 3f / 2f,
        listOf(
            CoreDef("mgba", "mGBA", R.string.data_core_mgba, recommended = true),
            CoreDef("gpsp", "gpSP", R.string.data_core_gpsp),
        ),
        "dot", 2, 12,
    ),
    GG(
        "GG", "Game Gear", "GG", setOf("gg"), "Sega_-_Game_Gear",
        frame("cart_gg", 400f, 403f, 45.333f, 81.333f, 312.667f, 226.667f),
        Color(0xFF2E2E36), 10f / 9f,
        listOf(
            core("genesis_plus_gx", "Genesis Plus GX", R.string.data_core_genesis_plus_gx),
            core("gearsystem", "Gearsystem", R.string.data_core_gearsystem),
        ),
        "dot", 2, 21,
    ),
    INTV(
        "INTV", "Intellivision", "INTV", setOf("int", "bin", "rom"), "Mattel_-_Intellivision",
        frame("cart_intv", 400f, 494f, 30.000f, 392.667f, 339.333f, 76.667f),
        Color(0xFF5E4A2E), 4f / 3f, listOf(core("freeintv", "FreeIntv", R.string.data_core_freeintv)), "geom", 2, 115,
    ),
    NGP(
        "NGP", "NeoGeo Pocket", "NGP", setOf("ngp", "ngc"), "SNK_-_Neo_Geo_Pocket",
        frame("cart_ngp", 400f, 400f, 42.667f, 103.333f, 315.333f, 171.333f),
        Color(0xFF3A4E6B), 160f / 152f, listOf(core("mednafen_ngp", "Beetle NeoPop", R.string.data_core_beetle_ngp)), "dot", 2, 25,
    ),
    N64(
        "N64", "Nintendo 64", "N64", setOf("n64", "z64", "v64"), "Nintendo_-_Nintendo_64",
        frame("cart_n64", 311f, 200f, 88.466187f, 23.273422f, 133.12051f, 153.34576f),
        Color(0xFF2E4A3A), 4f / 3f,
        listOf(
            core("mupen64plus_next_gles3", "Mupen64Plus-Next", R.string.data_core_mupen64plus),
            core("parallel_n64", "ParaLLEl N64", R.string.data_core_parallel_n64),
        ),
        "geom", 4, 14,
    ),
    NDS(
        "NDS", "Nintendo DS", "NDS", setOf("nds"), "Nintendo_-_Nintendo_DS",
        frame("cart_nds", 190f, 200f, 23.435738f, 14.144199f, 143.3605f, 158.74608f),
        Color(0xFF4A4A52), 2f / 3f,
        listOf(
            core("desmume", "DeSmuME", R.string.data_core_desmume),
            core("melonds", "melonDS", R.string.data_core_melonds),
        ),
        "sharp", 4, 15,
    ),
    GC(
        "GC", "Nintendo GameCube", "GC", setOf("iso", "gcm", "ciso", "rvz"), "Nintendo_-_GameCube", null,
        Color(0xFF4A3A7A), 4f / 3f, listOf(core("dolphin", "Dolphin", R.string.data_core_dolphin)), "geom", 4, 13,
    ),
    O2(
        "O2", "Odyssey2 / Videopac+", "O2", setOf("bin", "rom"), "Magnavox_-_Odyssey2",
        frame("cart_o2", 400f, 603f, 34.483f, 168.103f, 327.586f, 331.897f),
        Color(0xFF3A3A3A), 4f / 3f, listOf(core("o2em", "O2EM", R.string.data_core_o2em)), "geom", 2, 104,
    ),
    SG1000(
        "SG1000", "SG-1000", "SG-1000", setOf("sg", "bin"), "Sega_-_SG-1000",
        frame("cart_sg1000", 400f, 461f, 38.667f, 6.667f, 324.667f, 261.333f),
        Color(0xFF6B2E2E), 4f / 3f, listOf(core("genesis_plus_gx", "Genesis Plus GX", R.string.data_core_genesis_plus_gx)), "geom", 2, 109,
    ),
    S32X(
        "S32X", "Sega 32X", "32X", setOf("32x", "bin", "md"), "Sega_-_32X",
        frame("cart_s32x", 400f, 263f, 70.000f, 34.667f, 254.667f, 150.667f),
        Color(0xFF5A2E3A), 4f / 3f, listOf(core("picodrive", "PicoDrive", R.string.data_core_picodrive)), "geom", 4, 19,
    ),
    MCD(
        "MCD", "Sega CD / Mega CD", "SEGA CD", setOf("cue", "chd", "iso"), "Sega_-_Mega-CD_-_Sega_CD", null,
        Color(0xFF2E3A5A), 4f / 3f,
        listOf(
            core("genesis_plus_gx", "Genesis Plus GX", R.string.data_core_genesis_plus_gx),
            core("picodrive", "PicoDrive", R.string.data_core_lighter),
        ),
        "geom", 4, 20,
    ),
    MD(
        "MD", "Sega Genesis / Mega Drive", "MD", setOf("md", "gen", "bin", "smd"), "Sega_-_Mega_Drive_-_Genesis",
        frame("cart_md", 295f, 200f, 46.116512f, 4.5005116f, 202.53333f, 166.38126f),
        Color(0xFF26262E), 4f / 3f,
        listOf(
            core("genesis_plus_gx", "Genesis Plus GX", R.string.data_core_accurate_compatible),
            core("picodrive", "PicoDrive", R.string.data_core_lighter),
        ),
        "geom", 4, 1,
    ),
    SMS(
        "SMS", "Sega Master System", "SMS", setOf("sms", "bin"), "Sega_-_Master_System_-_Mark_III",
        frame("cart_sms", 295f, 200f, 8.6735439f, 6.7110262f, 276.76068f, 41.951309f),
        Color(0xFF2E4A6B), 4f / 3f,
        listOf(
            core("genesis_plus_gx", "Genesis Plus GX", R.string.data_core_genesis_plus_gx),
            core("gearsystem", "Gearsystem", R.string.data_core_gearsystem),
        ),
        "geom", 2, 2,
    ),
    SATURN(
        "SATURN", "Sega Saturn", "SATURN", setOf("cue", "chd", "iso"), "Sega_-_Saturn", null,
        Color(0xFF3A3A4A), 4f / 3f, listOf(core("mednafen_saturn", "Beetle Saturn", R.string.data_core_beetle_saturn)), "geom", 4, 22,
    ),
    PS(
        "PS", "Sony PlayStation", "PS1", setOf("cue", "chd", "pbp", "iso", "bin"), "Sony_-_PlayStation", null,
        Color(0xFF4A4A5A), 4f / 3f,
        listOf(
            core("mednafen_psx", "Beetle PSX", R.string.data_core_accurate),
            core("pcsx_rearmed", "PCSX ReARMed", R.string.data_core_pcsx_rearmed),
        ),
        "geom", 4, 57,
    ),
    PSP(
        "PSP", "Sony PSP", "PSP", setOf("iso", "cso", "pbp"), "Sony_-_PlayStation_Portable", null,
        Color(0xFF26262E), 480f / 272f, listOf(core("ppsspp", "PPSSPP", R.string.data_core_ppsspp)), "sharp", 4, 61,
    ),
    SFC(
        "SFC", "Super Nintendo", "SNES", setOf("sfc", "smc"), "Nintendo_-_Super_Nintendo_Entertainment_System",
        frame("cart_sfc", 400f, 255f, 77.487701f, 1.9874543f, 247.88605f, 112.57095f),
        Color(0xFF4A4757), 4f / 3f,
        listOf(
            CoreDef("snes9x", "Snes9x", R.string.data_core_snes9x, recommended = true),
            CoreDef("bsnes", "bsnes", R.string.data_core_max_accuracy),
        ),
        "geom", 4, 4,
    ),
    PCE(
        "PCE", "TurboGrafx-16 / PC Engine / SuperGrafx", "PCE", setOf("pce", "sgx", "bin"), "NEC_-_PC_Engine_-_TurboGrafx_16",
        frame("cart_pce", 400f, 506f, 52.321f, 175.527f, 295.359f, 286.920f),
        Color(0xFF5A5A62), 4f / 3f, listOf(core("mednafen_pce_fast", "Beetle PCE Fast", R.string.data_core_beetle_pce_fast)), "geom", 2, 31,
    ),
    PCECD(
        "PCECD", "TurboGrafx-CD / PC Engine CD", "PCE CD", setOf("cue", "chd", "iso"), "NEC_-_PC_Engine_CD_-_TurboGrafx-CD", null,
        Color(0xFF4A4A6B), 4f / 3f,
        listOf(
            core("mednafen_supergrafx", "Beetle SuperGrafx", R.string.data_core_beetle_supergrafx),
            core("mednafen_pce_fast", "Beetle PCE Fast", R.string.data_core_lighter),
        ),
        "geom", 2, 114,
    ),
    VECTREX(
        "VECTREX", "Vectrex", "VECTREX", setOf("vec", "bin"), "GCE_-_Vectrex",
        frame("cart_vectrex", 400f, 391f, 26.000f, 176.667f, 348.000f, 186.000f),
        Color(0xFF2E2E2E), 4f / 5f, listOf(core("vecx", "vecx", R.string.data_core_vecx)), "none", 4, 102,
    ),
    VB(
        "VB", "Virtual Boy", "VB", setOf("vb", "vboy", "bin"), "Nintendo_-_Virtual_Boy",
        frame("cart_vb", 400f, 383f, 61.333f, 38.667f, 274.667f, 218.667f),
        Color(0xFF6B2626), 384f / 224f, listOf(core("mednafen_vb", "Beetle VB", R.string.data_core_beetle_vb)), "dot", 2, 11,
    ),
    WS(
        "WS", "WonderSwan", "WS", setOf("ws", "wsc"), "Bandai_-_WonderSwan",
        frame("cart_ws", 400f, 241f, 14.667f, 32.000f, 370.667f, 136.000f),
        Color(0xFF3A4A4A), 224f / 144f, listOf(core("mednafen_wswan", "Beetle WonderSwan", R.string.data_core_beetle_wswan)), "dot", 4, 45,
    );

    fun core(id: String?): CoreDef = cores.firstOrNull { it.id == id } ?: cores[0]

    /**
     * The shelf card's background and text. Dark: [shelfColor] under white.
     * Light: a pastel of the same hue under ink of that hue (contrast over 7:1);
     * near-gray colors stay near gray.
     */
    fun shelf(dark: Boolean): Pair<Color, Color> {
        if (dark) return shelfColor to Color.White
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(shelfColor.toArgb(), hsl)
        val gray = hsl[1] < 0.1f
        val back = if (gray) hsl[1] * 1.2f else (hsl[1] * 1.6f).coerceIn(0.28f, 1f)
        val ink = if (gray) hsl[1] * 1.2f else (hsl[1] * 1.3f).coerceIn(0.35f, 1f)
        return Color(ColorUtils.HSLToColor(floatArrayOf(hsl[0], back, 0.84f))) to Color(ColorUtils.HSLToColor(floatArrayOf(hsl[0], ink, 0.22f)))
    }

    /** OpenEmu's pixel-art icon, the desktop's (console_icons.py); null shows the abbreviation chip. */
    @get:DrawableRes
    val icon: Int? get() = ICONS[id]

    /** "GBA - Game Boy Advance", for lists that name every console. */
    val fullName: String get() = "$short - $displayName"

    companion object {
        fun byId(id: String?): Console? = entries.firstOrNull { it.id == id }

        /** Every console that takes this extension, most likely first (rom_importer.py). */
        fun forExtension(ext: String): List<Console> {
            val e = ext.lowercase()
            val all = entries.filter { e in it.extensions }
            val preferred = PREFERRED_BY_EXTENSION[e].orEmpty().mapNotNull { byId(it) }.filter { it in all }
            return preferred + (all - preferred.toSet())
        }

        private val ICONS = mapOf(
            "A2600" to R.drawable.console_a2600,
            "A5200" to R.drawable.console_a5200,
            "A7800" to R.drawable.console_a7800,
            "LYNX" to R.drawable.console_lynx,
            "CV" to R.drawable.console_cv,
            "FDS" to R.drawable.console_fds,
            "FC" to R.drawable.console_fds,
            "GB" to R.drawable.console_gb,
            "GBC" to R.drawable.console_gb,
            "GBA" to R.drawable.console_gba,
            "GG" to R.drawable.console_gg,
            "INTV" to R.drawable.console_intv,
            "NGP" to R.drawable.console_ngp,
            "N64" to R.drawable.console_n64,
            "NDS" to R.drawable.console_nds,
            "GC" to R.drawable.console_gc,
            "O2" to R.drawable.console_o2,
            "SG1000" to R.drawable.console_sg1000,
            "S32X" to R.drawable.console_s32x,
            "MCD" to R.drawable.console_mcd,
            "MD" to R.drawable.console_md,
            "SMS" to R.drawable.console_sms,
            "SATURN" to R.drawable.console_saturn,
            "PS" to R.drawable.console_ps,
            "PSP" to R.drawable.console_psp,
            "SFC" to R.drawable.console_sfc,
            "PCE" to R.drawable.console_pce,
            "PCECD" to R.drawable.console_pcecd,
            "VECTREX" to R.drawable.console_vectrex,
            "VB" to R.drawable.console_vb,
            "WS" to R.drawable.console_ws,
        )

        private val PREFERRED_BY_EXTENSION = mapOf(
            "bin" to listOf("MD", "PS", "A2600", "SMS", "PCE"),
            "rom" to listOf("A2600", "CV", "INTV", "O2"),
            "iso" to listOf("PS", "PSP", "GC", "SATURN", "MCD", "PCECD"),
            "chd" to listOf("PS", "SATURN", "MCD", "PCECD"),
            "cue" to listOf("PS", "SATURN", "MCD", "PCECD"),
            "pbp" to listOf("PS", "PSP"),
            "md" to listOf("MD", "S32X"),
        )
    }
}

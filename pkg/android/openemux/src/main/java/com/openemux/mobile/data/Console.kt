package com.openemux.mobile.data

import androidx.compose.ui.graphics.Color

data class CoreDef(
    val id: String,
    val name: String,
    val description: String,
    val recommended: Boolean = false,
) {
    val fileName: String get() = "${id}_libretro_android.so"
}

data class ShaderDef(val id: String, val name: String, val preset: String?)

val SHADERS = listOf(
    ShaderDef("none", "Nenhum", null),
    ShaderDef("dot", "Dot", "handheld/dot.glslp"),
    ShaderDef("sharp", "Sharp Bilinear", "interpolation/sharp-bilinear-simple.glslp"),
    ShaderDef("zfast", "zfast CRT", "crt/zfast-crt.glslp"),
    ShaderDef("geom", "CRT Geom", "crt/crt-geom.glslp"),
)

fun shaderById(id: String?): ShaderDef = SHADERS.firstOrNull { it.id == id } ?: SHADERS[0]

data class CartColor(val id: String, val name: String, val swatch: Color)

/** Same ids and swatches as the desktop's cartridge_colors.py. */
val CART_COLORS = listOf(
    CartColor("default", "Original", Color(0xFF3A3A40)),
    CartColor("black", "Preto", Color(0xFF26262A)),
    CartColor("white", "Branco", Color(0xFFE8E4DA)),
    CartColor("red", "Vermelho", Color(0xFFB23A34)),
    CartColor("orange", "Laranja", Color(0xFFCC7A29)),
    CartColor("yellow", "Amarelo", Color(0xFFD9A81F)),
    CartColor("green", "Verde", Color(0xFF3F8C5B)),
    CartColor("teal", "Turquesa", Color(0xFF2C8A8A)),
    CartColor("blue", "Azul", Color(0xFF3167B0)),
    CartColor("purple", "Roxo", Color(0xFF7A4FA3)),
    CartColor("pink", "Rosa", Color(0xFFBE5289)),
    CartColor("gold", "Dourado", Color(0xFFC6A02E)),
    CartColor("clear", "Transparente", Color(0xFFB9C0C7)),
)

fun cartColorById(id: String?): CartColor = CART_COLORS.firstOrNull { it.id == id } ?: CART_COLORS[0]

/** Where the cover goes inside the frame art, as fractions of the frame. */
data class LabelRect(val left: Float, val top: Float, val width: Float, val height: Float)

enum class Console(
    val id: String,
    val displayName: String,
    val short: String,
    val extensions: Set<String>,
    val artworkDir: String,
    val frameName: String,
    val frameAspect: Float,
    val label: LabelRect,
    val shelfColor: Color,
    val gameAspect: Float,
    val cores: List<CoreDef>,
    val defaultShader: String,
    val faceButtons: Int,
    /** Share of the box art's left edge to drop (GBA boxes carry a white band there). */
    val coverTrimLeft: Float = 0f,
) {
    GBA(
        id = "GBA",
        displayName = "Game Boy Advance",
        short = "GBA",
        extensions = setOf("gba"),
        artworkDir = "Nintendo_-_Game_Boy_Advance",
        frameName = "cart_gba",
        frameAspect = 400f / 230f,
        label = LabelRect(52.448f / 400f, 51.133f / 230f, 295.698f / 400f, 152.558f / 230f),
        shelfColor = Color(0xFF3B3486),
        gameAspect = 3f / 2f,
        cores = listOf(
            CoreDef("mgba", "mGBA", "Mais preciso. Aceita BIOS oficial.", recommended = true),
            CoreDef("gpsp", "gpSP", "Mais leve, para aparelhos modestos."),
        ),
        defaultShader = "dot",
        faceButtons = 2,
        coverTrimLeft = 0.12f,
    ),
    SFC(
        id = "SFC",
        displayName = "Super Nintendo",
        short = "SNES",
        extensions = setOf("sfc", "smc"),
        artworkDir = "Nintendo_-_Super_Nintendo_Entertainment_System",
        frameName = "cart_sfc",
        frameAspect = 400f / 255f,
        label = LabelRect(77.488f / 400f, 1.987f / 255f, 247.886f / 400f, 112.571f / 255f),
        shelfColor = Color(0xFF4A4757),
        gameAspect = 4f / 3f,
        cores = listOf(
            CoreDef("snes9x", "Snes9x", "Rápido e compatível com quase tudo.", recommended = true),
            CoreDef("bsnes", "bsnes", "Precisão máxima. Pede um aparelho forte."),
        ),
        defaultShader = "zfast",
        faceButtons = 4,
    );

    fun core(id: String?): CoreDef = cores.firstOrNull { it.id == id } ?: cores[0]

    companion object {
        fun byId(id: String?): Console? = entries.firstOrNull { it.id == id }

        fun forExtension(ext: String): Console? =
            entries.firstOrNull { ext.lowercase() in it.extensions }
    }
}

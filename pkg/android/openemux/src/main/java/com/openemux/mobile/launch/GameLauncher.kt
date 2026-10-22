package com.openemux.mobile.launch

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.view.WindowInsets
import android.view.WindowManager
import androidx.core.content.res.ResourcesCompat
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.openemux.mobile.data.Console
import com.openemux.mobile.data.Covers
import com.openemux.mobile.data.Library
import com.openemux.mobile.data.Rom
import com.openemux.mobile.game.GameActivity
import com.openemux.mobile.game.GameExtras
import com.retroarch.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Composes the cartridge art the way the desktop does: cover under the frame. */
object CartArt {
    fun frameRes(context: Context, console: Console, color: String): Int {
        val res = context.resources
        if (color != "default") {
            val id = res.getIdentifier("${console.frameName}_$color", "drawable", context.packageName)
            if (id != 0) return id
        }
        return res.getIdentifier(console.frameName, "drawable", context.packageName)
    }

    suspend fun loadCover(context: Context, url: String?): Bitmap? {
        if (url == null) return null
        val request = ImageRequest.Builder(context).data(url).allowHardware(false).build()
        val result = context.imageLoader.execute(request)
        return ((result as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
    }

    fun compose(context: Context, console: Console, colorId: String, cover: Bitmap?, title: String): Bitmap {
        val frame = (ResourcesCompat.getDrawable(context.resources, frameRes(context, console, colorId), null) as BitmapDrawable).bitmap
        val out = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val l = console.label
        val dst = RectF(l.left * frame.width, l.top * frame.height, (l.left + l.width) * frame.width, (l.top + l.height) * frame.height)
        if (cover != null) {
            // Crop the box art to the label's shape, keeping its upper part (the logo).
            val target = dst.width() / dst.height()
            val trim = (cover.width * console.coverTrimLeft).toInt()
            val usableW = cover.width - trim
            val srcW: Int
            val srcH: Int
            if (usableW.toFloat() / cover.height > target) {
                srcH = cover.height; srcW = (cover.height * target).toInt()
            } else {
                srcW = usableW; srcH = (usableW / target).toInt()
            }
            val sx = trim + (usableW - srcW) / 2
            val sy = ((cover.height - srcH) * 0.3f).toInt()
            c.drawBitmap(cover, Rect(sx, sy, sx + srcW, sy + srcH), dst, Paint(Paint.FILTER_BITMAP_FLAG))
        } else {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(222, 219, 212) }
            c.drawRect(dst, p)
            p.color = Color.rgb(40, 40, 46)
            p.typeface = ResourcesCompat.getFont(context, R.font.grotesk_bold) ?: Typeface.DEFAULT_BOLD
            p.textAlign = Paint.Align.CENTER
            p.textSize = dst.height() * 0.16f
            val words = title.split(" ")
            val lines = mutableListOf<String>()
            var line = ""
            for (w in words) {
                val next = if (line.isEmpty()) w else "$line $w"
                if (p.measureText(next) > dst.width() * 0.86f && line.isNotEmpty()) { lines += line; line = w } else line = next
            }
            if (line.isNotEmpty()) lines += line
            val shown = lines.take(3)
            val lh = p.textSize * 1.15f
            var y = dst.centerY() - lh * (shown.size - 1) / 2f - (p.ascent() + p.descent()) / 2f
            for (s in shown) { c.drawText(s, dst.centerX(), y, p); y += lh }
        }
        c.drawBitmap(frame, 0f, 0f, null)
        return out
    }
}

class GameLauncher(private val context: Context, private val library: Library, private val covers: Covers) {
    private val paths = library.paths

    /** Cores, core info and shaders, copied out of the APK once per install. */
    private fun prepareEngine() {
        val stamp = File(paths.internal, "engine.stamp")
        val version = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        if (stamp.exists() && stamp.readText() == version) return

        paths.cores.mkdirs()
        val libDir = File(context.applicationInfo.nativeLibraryDir)
        Console.entries.flatMap { it.cores }.forEach { core ->
            val src = File(libDir, "lib${core.fileName}")
            if (src.exists()) src.copyTo(File(paths.cores, core.fileName), overwrite = true)
        }
        paths.info.mkdirs()
        context.assets.list("info")?.forEach { name ->
            context.assets.open("info/$name").use { input -> File(paths.info, name).outputStream().use { input.copyTo(it) } }
        }
        copyAssetTree("shaders", paths.shaders)
        stamp.writeText(version)
    }

    private fun copyAssetTree(asset: String, target: File) {
        val children = context.assets.list(asset).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            context.assets.open(asset).use { input -> target.outputStream().use { input.copyTo(it) } }
            return
        }
        target.mkdirs()
        children.forEach { copyAssetTree("$asset/$it", File(target, it)) }
    }

    suspend fun cartBitmap(rom: Rom): Bitmap {
        val cover = CartArt.loadCover(context, covers.urlFor(rom))
        return withContext(Dispatchers.Default) {
            CartArt.compose(context, rom.console, library.effectiveColor(rom), cover, rom.title)
        }
    }

    suspend fun launch(rom: Rom, resume: Boolean, slot: Int? = null) {
        val cart = cartBitmap(rom)
        val intent = withContext(Dispatchers.IO) {
            prepareEngine()
            val dir = File(paths.launch, "current").apply { deleteRecursively(); mkdirs() }

            val cartFile = File(dir, "cart.png")
            cartFile.outputStream().use { cart.compress(Bitmap.CompressFormat.PNG, 100, it) }

            val metrics = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).maximumWindowMetrics
            val bounds = metrics.bounds
            val cutout = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout())
            val skin = SkinRenderer(context).render(
                rom.console, cart, bounds.width(), bounds.height(), File(dir, "skin"),
                topInset = if (bounds.height() >= bounds.width()) cutout.top else cutout.left,
            )

            val shader = library.effectiveShader(rom)
            shader.preset?.let { preset ->
                File(dir, "global.glslp").writeText("#reference \"${File(paths.shaders, preset).absolutePath}\"\n")
            }

            File(dir, "core-options.cfg").writeText(
                "mgba_skip_bios = \"ON\"\n" +
                    "mgba_use_bios = \"ON\"\n"
            )

            listOf(paths.states(rom.console), paths.saves(rom.console), paths.system, paths.screenshots, paths.playtime)
                .forEach { it.mkdirs() }
            val config = File(dir, "retroarch.cfg")
            config.writeText(buildConfig(rom, resume, dir, skin))

            library.updateRom(rom.key) { it.copy(lastPlayed = System.currentTimeMillis()) }

            Intent(context, GameActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra("ROM", rom.file.absolutePath)
                putExtra("LIBRETRO", File(paths.cores, library.effectiveCore(rom).fileName).absolutePath)
                putExtra("CONFIGFILE", config.absolutePath)
                putExtra("IME", "com.android.inputmethod.latin/.LatinIME")
                putExtra(GameExtras.TITLE, rom.title)
                putExtra(GameExtras.CONSOLE, rom.console.id)
                putExtra(GameExtras.CART, cartFile.absolutePath)
                putExtra(GameExtras.STATES_DIR, paths.states(rom.console).absolutePath)
                putExtra(GameExtras.ROM_BASE, rom.baseName)
                putExtra(GameExtras.PLAYTIME, library.playtimeFile(rom).absolutePath)
                putExtra(GameExtras.SHADERS_DIR, paths.shaders.absolutePath)
                putExtra(GameExtras.SHADER, shader.id)
                putExtra(GameExtras.CORE_NAME, library.effectiveCore(rom).name)
                if (slot != null) putExtra(GameExtras.LOAD_SLOT, slot)
            }
        }
        context.startActivity(intent)
    }

    private fun buildConfig(rom: Rom, resume: Boolean, dir: File, skin: File): String {
        val settings = linkedMapOf(
            "video_driver" to "gl",
            "menu_driver" to "glui",
            "video_shader_enable" to "true",
            "video_shader_dir" to paths.shaders.absolutePath,
            "rgui_config_directory" to dir.absolutePath,
            "video_smooth" to "false",
            "video_scale_integer" to "false",
            "video_viewport_bias_portrait_x" to "0.500000",
            "video_viewport_bias_portrait_y" to "0.000000",
            "video_notch_write_over_enable" to "false",
            "input_overlay_enable" to "true",
            "input_overlay" to skin.absolutePath,
            "input_overlay_auto_rotate" to "true",
            "input_overlay_opacity" to "1.000000",
            "input_overlay_auto_scale" to "false",
            "input_overlay_hide_in_menu" to "true",
            "input_overlay_hide_when_gamepad_connected" to "true",
            "input_overlay_show_inputs" to "0",
            "input_menu_toggle_gamepad_combo" to "0",
            "libretro_directory" to paths.cores.absolutePath,
            "libretro_info_path" to paths.info.absolutePath,
            "system_directory" to paths.system.absolutePath,
            "savestate_directory" to paths.states(rom.console).absolutePath,
            "savefile_directory" to paths.saves(rom.console).absolutePath,
            "screenshot_directory" to paths.screenshots.absolutePath,
            "sort_savestates_enable" to "false",
            "sort_savefiles_enable" to "false",
            "sort_savestates_by_content_enable" to "false",
            "sort_savefiles_by_content_enable" to "false",
            "sort_screenshots_by_content_enable" to "false",
            "savestates_in_content_dir" to "false",
            "savefiles_in_content_dir" to "false",
            "savestate_auto_save" to "true",
            "savestate_auto_load" to resume.toString(),
            "savestate_thumbnail_enable" to "true",
            "state_slot" to "0",
            "global_core_options" to "true",
            "core_options_path" to File(dir, "core-options.cfg").absolutePath,
            "network_cmd_enable" to "true",
            "network_cmd_port" to "55355",
            "config_save_on_exit" to "false",
            "confirm_quit" to "false",
            "auto_overrides_enable" to "false",
            "auto_remaps_enable" to "false",
            "menu_show_load_content_animation" to "false",
            "notification_show_autoconfig" to "false",
            "notification_show_remap_load" to "false",
            "notification_show_config_override_load" to "false",
            "notification_show_set_initial_disk" to "false",
            "notification_show_fast_forward" to "true",
            "video_font_enable" to "true",
            "fps_show" to "false",
        )
        return settings.entries.joinToString("\n") { (k, v) -> "$k = \"$v\"" } + "\n"
    }
}

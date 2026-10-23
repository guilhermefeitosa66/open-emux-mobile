package com.openemux.mobile.launch

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import com.openemux.mobile.data.AchievementsStore
import com.openemux.mobile.data.Console
import com.openemux.mobile.data.Covers
import com.openemux.mobile.data.Labels
import com.openemux.mobile.data.Library
import com.openemux.mobile.data.NdsLayouts
import com.openemux.mobile.data.Rom
import com.openemux.mobile.data.configKeys
import com.openemux.mobile.game.GameActivity
import com.openemux.mobile.game.GameExtras
import com.retroarch.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Composes the cartridge art the way the desktop does: label under the frame. */
object CartArt {
    fun frameRes(context: Context, console: Console, color: String): Int {
        val name = console.frame?.name ?: return 0
        val res = context.resources
        if (color != "default") {
            val id = res.getIdentifier("${name}_$color", "drawable", context.packageName)
            if (id != 0) return id
        }
        return res.getIdentifier(name, "drawable", context.packageName)
    }

    fun loadLabel(file: File?): Bitmap? = file?.let { BitmapFactory.decodeFile(it.path) }

    /** A box-shaped card with the title, for a console with no frame and no box art. */
    fun titleCard(context: Context, title: String): Bitmap {
        val out = Bitmap.createBitmap(360, 500, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(36, 36, 44) }
        c.drawRoundRect(RectF(0f, 0f, 360f, 500f), 24f, 24f, p)
        p.color = Color.rgb(163, 163, 178)
        p.typeface = ResourcesCompat.getFont(context, R.font.grotesk_bold) ?: Typeface.DEFAULT_BOLD
        p.textAlign = Paint.Align.CENTER
        p.textSize = 34f
        c.drawText(title.take(22), 180f, 250f, p)
        return out
    }

    fun compose(context: Context, console: Console, colorId: String, label: Bitmap?, title: String): Bitmap {
        val frame = (ResourcesCompat.getDrawable(context.resources, frameRes(context, console, colorId), null) as BitmapDrawable).bitmap
        val out = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val l = console.frame!!.label
        val dst = RectF(l.left * frame.width, l.top * frame.height, (l.left + l.width) * frame.width, (l.top + l.height) * frame.height)
        if (label != null) {
            // Crop-to-fill, centred, like the desktop compositor.
            val target = dst.width() / dst.height()
            val srcW: Int
            val srcH: Int
            if (label.width.toFloat() / label.height > target) {
                srcH = label.height; srcW = (label.height * target).toInt()
            } else {
                srcW = label.width; srcH = (label.width / target).toInt()
            }
            val sx = (label.width - srcW) / 2
            val sy = (label.height - srcH) / 2
            c.drawBitmap(label, Rect(sx, sy, sx + srcW, sy + srcH), dst, Paint(Paint.FILTER_BITMAP_FLAG))
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

class GameLauncher(
    private val context: Context,
    private val library: Library,
    private val covers: Covers,
    private val labels: Labels,
    private val achievements: AchievementsStore,
) {
    private val paths = library.paths

    /** Shaders, copied out of the APK once per install; cores and core info come from the setup. */
    private fun prepareEngine() {
        val stamp = File(paths.internal, "engine.stamp")
        val version = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        if (stamp.exists() && stamp.readText() == version) return
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
        if (rom.console.frame == null) {
            // No cartridge art for this console: the box art stands in, as on the desktop.
            val request = ImageRequest.Builder(context).data(covers.urlFor(rom)).allowHardware(false).build()
            val box = ((context.imageLoader.execute(request) as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
            return box ?: withContext(Dispatchers.Default) { CartArt.titleCard(context, rom.title) }
        }
        return withContext(Dispatchers.Default) {
            val label = CartArt.loadLabel(labels.found.value[rom.key])
            CartArt.compose(context, rom.console, library.effectiveColor(rom), label, rom.title)
        }
    }

    /** Starts the game from [from], in the library's own task, so it opens inside the app. */
    suspend fun launch(from: Activity, rom: Rom, resume: Boolean, slot: Int? = null) {
        val cart = cartBitmap(rom)
        val cheevos = achievements.state.value
        // Hardcore refuses loading a state: start fresh rather than have the engine refuse it.
        val hardcore = cheevos.hardcoreActive
        val intent = withContext(Dispatchers.IO) {
            prepareEngine()
            val dir = File(paths.launch, "current").apply { deleteRecursively(); mkdirs() }

            val cartFile = File(dir, "cart.png")
            cartFile.outputStream().use { cart.compress(Bitmap.CompressFormat.PNG, 100, it) }

            val metrics = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).maximumWindowMetrics
            val bounds = metrics.bounds
            val cutout = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout())
            val ndsLayouts = if (rom.console == Console.NDS) NdsLayouts.load(context.filesDir) else null
            val skin = SkinRenderer(context).render(
                rom.console, bounds.width(), bounds.height(), File(dir, "skin"),
                cutout = if (bounds.height() >= bounds.width()) cutout.top else maxOf(cutout.left, cutout.right),
                ndsLayouts = ndsLayouts,
            )

            val shader = library.effectiveShader(rom)
            shader.preset?.let { preset ->
                File(dir, "global.glslp").writeText("#reference \"${File(paths.shaders, preset).absolutePath}\"\n")
            }

            File(dir, "core-options.cfg").writeText(
                "mgba_skip_bios = \"ON\"\n" +
                    "mgba_use_bios = \"ON\"\n" +
                    // The skins draw the 6-button pad; Genesis Plus GX picks it on its own.
                    "picodrive_input1 = \"6 button pad\"\n" +
                    // The skin opens on the saved portrait layout; the activity follows the overlay from there.
                    (ndsLayouts?.let { "desmume_screens_layout = \"${it.first.value}\"\n" } ?: "")
            )

            listOf(paths.states(rom.console), paths.saves(rom.console), paths.system, paths.screenshots, paths.playtime)
                .forEach { it.mkdirs() }
            val config = File(dir, "retroarch.cfg")
            config.writeText(buildConfig(rom, resume && !hardcore, dir, skin, cheevos.configKeys()))

            library.updateRom(rom.key) { it.copy(lastPlayed = System.currentTimeMillis()) }

            Intent(context, GameActivity::class.java).apply {
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
                putExtra(GameExtras.HARDCORE, hardcore)
                if (slot != null && !hardcore) putExtra(GameExtras.LOAD_SLOT, slot)
            }
        }
        from.startActivity(intent, ActivityOptions.makeCustomAnimation(from, R.anim.game_enter, R.anim.hold).toBundle())
    }

    private fun buildConfig(rom: Rom, resume: Boolean, dir: File, skin: File, cheevos: Map<String, String>): String {
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
            // The skin's turbo toggle: once on, every held button repeats (engine opt-in).
            "input_turbo_locked" to "true",
            "input_turbo_mode" to "0",
            "input_turbo_enable" to "false",
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
            // The app ships its own shaders and core info; extracting the APK would also save the config with a toast.
            "bundle_assets_extract_enable" to "false",
            "confirm_quit" to "false",
            "auto_overrides_enable" to "false",
            "auto_remaps_enable" to "false",
            "menu_show_load_content_animation" to "false",
            "notification_show_autoconfig" to "false",
            "notification_show_remap_load" to "false",
            "notification_show_config_override_load" to "false",
            "notification_show_set_initial_disk" to "false",
            "notification_show_autoconfig_fails" to "false",
            "notification_show_cheats_applied" to "false",
            "notification_show_patch_applied" to "false",
            "notification_show_refresh_rate" to "false",
            "notification_show_netplay_extra" to "false",
            "notification_show_disk_control" to "false",
            "notification_show_screenshot" to "false",
            "notification_show_screenshot_flash" to "0",
            "notification_show_when_menu_is_alive" to "false",
            "notification_show_autoload_state" to "false",
            "notification_show_fast_forward" to "true",
            "video_font_enable" to "true",
            "fps_show" to "false",
            // rcheevos keeps its badge cache under the thumbnails directory.
            "thumbnails_directory" to File(paths.internal, "thumbnails").absolutePath,
        )
        settings.putAll(cheevos)
        // The PS skin has both sticks: Beetle PSX and PCSX ReARMed read them as a DualShock.
        if (rom.console == Console.PS) settings["input_libretro_device_p1"] = "517"
        return settings.entries.joinToString("\n") { (k, v) -> "$k = \"$v\"" } + "\n"
    }
}

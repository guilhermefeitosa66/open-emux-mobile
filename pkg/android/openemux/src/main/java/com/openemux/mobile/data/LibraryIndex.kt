package com.openemux.mobile.data

import java.io.File

/** First line of the index; anything else means there is no index to trust. */
private const val INDEX_VERSION = "v1"

/**
 * The last scan's ROMs, one key per line, so a cold start can show the shelf
 * on its first frame instead of an empty library (issue #574).
 */
fun encodeLibraryIndex(roms: List<Rom>): String =
    (listOf(INDEX_VERSION) + roms.map { it.key }.filterNot { '\n' in it || '\r' in it })
        .joinToString("\n", postfix = "\n")

/**
 * The ROMs an index lists, built and sorted the way [Library.scan] builds them,
 * so a later scan of the same files yields an equal list. Null when [text] is
 * not an index this version wrote. Files are not looked at: the scan does that.
 */
fun decodeLibraryIndex(
    text: String,
    romsDir: (Console) -> File,
    consoleById: (String) -> Console? = { Console.byId(it) },
): List<Rom>? {
    val lines = text.lines()
    if (lines.first().trim() != INDEX_VERSION) return null
    return lines.drop(1)
        .filter { it.isNotBlank() }
        .distinct()
        .mapNotNull { key ->
            val slash = key.indexOf('/')
            if (slash < 0) return@mapNotNull null
            val console = consoleById(key.substring(0, slash)) ?: return@mapNotNull null
            val name = key.substring(slash + 1)
            if (name.substringAfterLast('.', "").lowercase() !in console.extensions) return@mapNotNull null
            Rom(key, console, File(romsDir(console), name), titleFromFileName(name))
        }
        .sortedBy { it.title.lowercase() }
}

/** What a library screen shows: nothing while the library is not known yet, the empty state only once it is. */
enum class ShelfContent { Loading, Empty, Shelves }

fun shelfContent(loaded: Boolean, roms: List<Rom>): ShelfContent = when {
    roms.isNotEmpty() -> ShelfContent.Shelves
    loaded -> ShelfContent.Empty
    else -> ShelfContent.Loading
}

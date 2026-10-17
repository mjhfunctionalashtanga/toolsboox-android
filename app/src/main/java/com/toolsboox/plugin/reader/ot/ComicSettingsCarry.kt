package com.toolsboox.plugin.reader.ot

/**
 * Where a comic's settings are copied FROM the first time the book opens under a new fleet key
 * (folder/name#size) — pure, so the rule is pinned by ComicSettingsCarryTest. Michael 2026-10-11,
 * comics slice 2 review.
 *
 *  1. THE SAME FILE IN ANOTHER FOLDER: a fleet key already holding settings that ends in this
 *     key's `name#size`. Re-pointing the Palma's comics folder renames every fleet key at once;
 *     copying from the file-name key then reverted each book to its pre-slice-2 settings.
 *  2. Otherwise the file-name key ([legacy]), as slice 2 did. A re-download that changes the size
 *     is a new name#size and lands here too — the file name is all that still says "same book".
 *
 * [prefKeys] are the reader prefs' keys; a setting is stored as `comic_<name>_<book key>` for
 * each of [names].
 */
internal object ComicSettingsCarry {

    fun source(key: String, legacy: String, prefKeys: Collection<String>, names: List<String>): String {
        val tail = key.substringAfterLast('/')              // "name.cbz#size"
        if (!tail.contains('#')) return legacy
        for (name in names) {
            val prefix = "comic_${name}_"
            for (k in prefKeys) {
                if (!k.startsWith(prefix)) continue
                val other = k.removePrefix(prefix)
                if (other != key && (other == tail || other.endsWith("/$tail"))) return other
            }
        }
        return legacy
    }
}

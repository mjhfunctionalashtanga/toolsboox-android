package com.toolsboox.ot

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.ColorInt
import androidx.appcompat.app.AppCompatDelegate

/**
 * Appearance for the Ledger, mirrored from the iPad app's Theme.swift.
 *
 * Two orthogonal choices, both stored in the plain "ledger_a11y" prefs alongside the font/size dials:
 *
 *  - a SCHEME (light / dark / follow-system), which drives [AppCompatDelegate]'s night mode and so
 *    flips the whole app between the day and night colour resources; and
 *  - a VIBE — a named accent + a default reading face — that tints the chrome and, when the reader
 *    hasn't picked a face of their own, chooses one that suits the vibe (terminal → mono, and so on).
 *
 * The app is monochrome by design, so a vibe is a light touch: one accent colour, resolved at runtime
 * for the active light/dark scheme (mirroring the iOS adaptive accents), plus a font default. There
 * are no per-vibe XML themes; callers ask [accent] for the int and paint with it.
 */
object LedgerTheme {

    private const val PREFS = "ledger_a11y"
    const val THEME_KEY = "app_theme"
    const val SCHEME_KEY = "app_scheme"
    /** Read directly, not via LedgerFonts, so the vibe default only applies when the user is on "system". */
    private const val READING_FONT_KEY = "reading_font"

    private const val DEFAULT_THEME = "ink"
    private const val DEFAULT_SCHEME = 0 // 0 = system, 1 = light, 2 = dark

    /**
     * A named appearance. [accentLight] reads on the light (paper-white) chrome; [accentDark] is a
     * slightly lifted twin that keeps contrast on the near-black night chrome. [fontId] is the reading
     * face this vibe leans on, used ONLY when the reader's own font is still "system".
     */
    data class Vibe(
        val id: String,
        val name: String,
        val vibe: String,
        @ColorInt val accentLight: Int,
        @ColorInt val accentDark: Int,
        val fontId: String
    )

    /** The ten vibes, in the same order as iOS. Accents are e-ink-legible solids — no gradients. */
    val ALL: List<Vibe> = listOf(
        Vibe("ink",      "Ink",      "blue",       0xFF1E5FA8.toInt(), 0xFF6AA6E0.toInt(), "system"),
        Vibe("paper",    "Paper",    "warm brown", 0xFF8A5A2B.toInt(), 0xFFCB9A67.toInt(), "system"),
        Vibe("slate",    "Slate",    "indigo",     0xFF3B4CA0.toInt(), 0xFF8A97D6.toInt(), "system"),
        Vibe("terminal", "Terminal", "green",      0xFF1F7A3D.toInt(), 0xFF46C46F.toInt(), "fastMono"),
        Vibe("sunset",   "Sunset",   "orange",     0xFFC2611A.toInt(), 0xFFE79A54.toInt(), "system"),
        Vibe("rose",     "Rose",     "pink",       0xFFB03A6E.toInt(), 0xFFE384AC.toInt(), "system"),
        Vibe("citrine",  "Citrine",  "gold",       0xFF9A7A15.toInt(), 0xFFD9BB4E.toInt(), "atkinson"),
        Vibe("emerald",  "Emerald",  "green",      0xFF1E7A5A.toInt(), 0xFF4FC79A.toInt(), "fastSans"),
        Vibe("garnet",   "Garnet",   "deep red",   0xFF8E2436.toInt(), 0xFFD46072.toInt(), "fastMono"),
        Vibe("amethyst", "Amethyst", "violet",     0xFF6A3AA0.toInt(), 0xFFAB82D6.toInt(), "fastSerif")
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The stored vibe, defaulting to [DEFAULT_THEME]; an unknown id falls back to the default. */
    fun current(context: Context): Vibe {
        val id = prefs(context).getString(THEME_KEY, DEFAULT_THEME)
        return ALL.firstOrNull { it.id == id } ?: ALL.first { it.id == DEFAULT_THEME }
    }

    /** Store the vibe by its [Vibe.id]; unknown ids fall back to the default. */
    fun setTheme(context: Context, id: String) {
        val safe = ALL.firstOrNull { it.id == id }?.id ?: DEFAULT_THEME
        prefs(context).edit().putString(THEME_KEY, safe).apply()
    }

    /** The stored scheme: 0 = follow system, 1 = light, 2 = dark. */
    fun scheme(context: Context): Int = prefs(context).getInt(SCHEME_KEY, DEFAULT_SCHEME)

    /** Store the scheme (0/1/2); call [applyNightMode] afterwards to make it take effect. */
    fun setScheme(context: Context, n: Int) {
        prefs(context).edit().putInt(SCHEME_KEY, n.coerceIn(0, 2)).apply()
    }

    /**
     * The reading font id that should actually be used: the reader's own pick when they've chosen one,
     * otherwise the current vibe's default face. Mirrors iOS `ReadingFont.effective`.
     */
    fun effectiveFontId(context: Context): String {
        val picked = prefs(context).getString(READING_FONT_KEY, "system") ?: "system"
        return if (picked != "system") picked else current(context).fontId
    }

    /** Push the stored scheme into [AppCompatDelegate]. Safe to call every launch. */
    fun applyNightMode(context: Context) {
        val mode = when (scheme(context)) {
            1 -> AppCompatDelegate.MODE_NIGHT_NO
            2 -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    /** True when the configuration on [context] is currently rendering in night (dark) mode. */
    fun isNight(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /** The current vibe's accent, resolved for the active light/dark scheme. */
    @ColorInt
    fun accent(context: Context): Int {
        val vibe = current(context)
        return if (isNight(context)) vibe.accentDark else vibe.accentLight
    }
}

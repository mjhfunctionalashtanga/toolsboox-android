package com.toolsboox.ot

import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import com.toolsboox.ui.plugin.ScreenFragment

/**
 * Make the wrench's modal text size actually reach a dialog.
 *
 * The setting used to be applied by hand, one widget at a time, which meant it only worked in the
 * three menus somebody had remembered to wire — and the app builds around 137 dialogs, nearly all
 * of them plain `AlertDialog.Builder(ctx)`. Setting the size on each one would be 137 chances to
 * miss one, and it would never reach a stock builder's title, buttons or list rows, which are
 * inflated internally where no caller can get at them.
 *
 * So scale the CONTEXT instead. A dialog built from [wrap] inherits an overridden `fontScale`, and
 * every piece of text it creates — including the ones we never touch — comes out at the right size.
 *
 * Scoped deliberately to dialogs. Turning `fontScale` up app-wide would double up with
 * [ReadingSize], which is the separate, larger control for the reading screens.
 */
object ModalScale {

    /**
     * The modal-SIZE key (distinct from `modal_text_size`, which drives the dialog fontScale).
     *
     * This is the one dial that scales a whole nav modal as a UNIT — its type AND its box — so the
     * page-switcher, the directory, and the temporal nav strip all grow or shrink together. Stored
     * by the settings chips as "compact"/"standard"/"expanded"; read back here as a float.
     */
    const val SIZE_KEY = "modal_size"

    /**
     * The modal-size dial as a multiplier: compact 0.8 / standard 1.0 / expanded 1.25, defaulting
     * to standard. Everything that wants to scale a nav modal as one unit reads THIS so the whole
     * app moves off a single setting.
     *
     * Compact is deliberately calibrated to be MARGIN-SIZED on the reading surfaces: the floating
     * pills and the pen button take this multiplier on their geometry (button dims, padding —
     * see ScreenFragment.applyPillSizing / MainActivity.applyPenButtonScale), and at 0.8 a
     * vertical pill comes out at ~39-42dp total and the pen button at ~43dp, inside the narrowest
     * reading gutter (44dp in the article view's CSS, ~46dp in the book reader's foliate margins
     * on a Tab8). Don't nudge this up without re-checking that arithmetic — "fits in the margin"
     * is the promise the smallest step makes.
     */
    fun sizeScale(context: Context): Float =
        when (context.getSharedPreferences("ledger_a11y", 0).getString(SIZE_KEY, "standard")) {
            // Recalibrated TWICE on the Tab Mini C (07-24). The first pass shrank the dial —
            // and text kept "shrinking and shrinking" while boxes stayed huge, because THREE
            // scales stacked (system font scale x menu dial x this). The real fix removed the
            // stacking (nav modals now read this dial alone, over smaller base sizes), so the
            // dial itself returns to honest steps.
            "compact" -> 0.8f
            "expanded" -> 1.25f
            else -> 1.0f
        }

    /**
     * The TOP NAV's own dial, separate from the floating modals'.
     *
     * These were one setting, and they shouldn't be: a floating modal can grow into free screen,
     * while the date strip lives in a fixed 1404×140 footprint with evenly divided slots and no
     * reflow. Sizing the modals up therefore sized the nav past its own box, where long labels
     * ("September 2026") had nowhere to go — so the one dial that made dialogs comfortable was
     * also the one that made the top nav unreadable. Two dials, because they are two problems.
     *
     * Defaults to whatever [SIZE_KEY] is currently set to, so upgrading changes nothing on screen;
     * the moment the nav dial is touched it goes its own way and stops following the modals.
     */
    const val NAV_SIZE_KEY = "nav_size"

    /**
     * The floating PILLS' own dial — the pill chrome and the pen button, sized independently of
     * both the dialogs and the top nav.
     *
     * Three things wanted three answers. A dialog can grow into free screen; the nav strip is
     * boxed; and the pills are calibrated against something else entirely — the reading gutter.
     * The doc on [sizeScale] spells that out: at 0.8 a vertical pill lands ~39-42dp and the pen
     * button ~43dp, inside the narrowest margin (44dp in the article CSS, ~46dp in the book
     * reader's foliate margins on a Tab8). That arithmetic is about the MARGIN, and it has no
     * business moving because a dialog wanted to be easier to read.
     *
     * Same inheritance rule as [NAV_SIZE_KEY]: unset, it follows the modal dial, so nothing moves
     * on upgrade and the settings row never reads as "nothing selected". Touch it and it detaches.
     */
    const val PILL_SIZE_KEY = "pill_size"

    fun pillScale(context: Context): Float {
        val prefs = context.getSharedPreferences("ledger_a11y", 0)
        val inherited = prefs.getString(SIZE_KEY, "standard")
        return when (prefs.getString(PILL_SIZE_KEY, inherited)) {
            "compact" -> 0.8f
            "expanded" -> 1.25f
            else -> 1.0f
        }
    }

    /**
     * True when the nav and pill dials are being left to follow the modal dial — neither has been
     * given a value of its own. The settings screen shows this as one "size everything together"
     * switch, so the common case stays a single choice and the three dials are what you get by
     * turning it off, rather than three rows everyone must reason about.
     */
    fun sizesLinked(context: Context): Boolean {
        val prefs = context.getSharedPreferences("ledger_a11y", 0)
        return prefs.getString(NAV_SIZE_KEY, null) == null && prefs.getString(PILL_SIZE_KEY, null) == null
    }

    /** Re-link: drop the independent dials so both follow the modal dial again. */
    fun linkSizes(context: Context) {
        context.getSharedPreferences("ledger_a11y", 0).edit()
            .remove(NAV_SIZE_KEY).remove(PILL_SIZE_KEY).apply()
    }

    /** Unlink: pin both to what they're showing NOW, so turning the switch off changes nothing
     *  visually — it only makes the two rows editable. A switch that also resized the app would
     *  make "let me adjust one of these" cost a surprise. */
    fun unlinkSizes(context: Context) {
        val prefs = context.getSharedPreferences("ledger_a11y", 0)
        val current = prefs.getString(SIZE_KEY, "standard")
        prefs.edit()
            .putString(NAV_SIZE_KEY, prefs.getString(NAV_SIZE_KEY, current))
            .putString(PILL_SIZE_KEY, prefs.getString(PILL_SIZE_KEY, current))
            .apply()
    }

    fun stripScale(context: Context): Float {
        val prefs = context.getSharedPreferences("ledger_a11y", 0)
        val inherited = prefs.getString(SIZE_KEY, "standard")
        return when (prefs.getString(NAV_SIZE_KEY, inherited)) {
            "compact" -> 0.85f
            "expanded" -> 1.25f
            else -> 1f
        }
    }

    /**
     * [context] re-themed at the user's modal text size, or [context] itself at Medium, where
     * there is nothing to change and the extra wrapper would only cost an allocation.
     */
    fun wrap(context: Context): Context {
        // The overlay goes in the CONSTRUCTOR, and passing 0 here was a real bug rather than a
        // tidy way of saying "no change": with a zero theme id the framework substitutes a
        // default SYSTEM theme and lays it over the app's, so every dialog came out restyled —
        // bigger type, different metrics, nothing to do with the accessibility setting.
        //
        // A non-zero id takes the honest path instead: copy the base context's theme, then apply
        // just this overlay on top of it. The overlay is per-tier, so it also sets the dialog's
        // WIDTH on big panels — Small is a compact selector, Large reads at arm's length.
        val tier = context.getSharedPreferences("ledger_a11y", 0)
            .getString("modal_text_size", "medium")
        val overlay = when (tier) {
            "small" -> com.toolsboox.R.style.LedgerModalOverlay_Small
            "large" -> com.toolsboox.R.style.LedgerModalOverlay_Large
            else -> com.toolsboox.R.style.LedgerModalOverlay_Medium
        }
        val wrapper = ContextThemeWrapper(context, overlay)
        val scale = ScreenFragment.modalTextScale(context)
        if (scale != 1f) {
            // Must land before anything reads a resource off this context, hence before the theme
            // is ever touched.
            wrapper.applyOverrideConfiguration(Configuration().apply {
                setTo(context.resources.configuration)
                fontScale *= scale
            })
        }
        return wrapper
    }
}

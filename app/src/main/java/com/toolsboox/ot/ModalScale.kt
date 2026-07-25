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
     * The modal-size dial as a multiplier: compact 0.85 / standard 1.0 / expanded 1.25, defaulting
     * to standard. Everything that wants to scale a nav modal as one unit reads THIS so the whole
     * app moves off a single setting.
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
     * The temporal strip keeps the OLD dial mapping: its 1404-wide footprint and slot geometry
     * were tuned at 1.0 and Michael's resize complaint was about the floating modals, not the
     * strip. Shrinking the strip's type 28% as a side effect would break what already works.
     */
    fun stripScale(context: Context): Float =
        when (context.getSharedPreferences("ledger_a11y", 0).getString(SIZE_KEY, "standard")) {
            "compact" -> 0.85f
            "expanded" -> 1.25f
            else -> 1f
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

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
        // just this overlay on top of it.
        val wrapper = ContextThemeWrapper(context, com.toolsboox.R.style.LedgerModalOverlay)
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

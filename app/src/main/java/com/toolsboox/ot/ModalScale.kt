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
        val scale = ScreenFragment.modalTextScale(context)
        if (scale == 1f) return context
        val cfg = Configuration().apply {
            setTo(context.resources.configuration)
            fontScale *= scale
        }
        // themeResId 0 keeps the base context's theme — AppCompat dialogs need it, and losing it
        // is what makes a naively re-configured context throw at inflation time.
        return ContextThemeWrapper(context, 0).apply { applyOverrideConfiguration(cfg) }
    }
}

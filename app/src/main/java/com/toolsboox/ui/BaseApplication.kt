package com.toolsboox.ui

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Base application.
 *
 * @author <a href="mailto:gabor.auth@toolsboox.com">Gábor AUTH</a>
 */
@HiltAndroidApp
class BaseApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()
        // The saved light/dark scheme, set before ANY activity exists. Doing this from the
        // activity (after its super.onCreate) drew the first frame in the wrong scheme and then
        // recreated the whole activity on every cold launch — here it lands once, ahead of all
        // inflation, and covers every entry point (share sheet, deep links) the same way.
        com.toolsboox.ot.LedgerTheme.applyNightMode(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

}
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
        // The card index writes itself from the day-save funnel, which lives in a service that has
        // no Context and must not be given one (it is constructed by hand in a JVM unit test where
        // there is none). Handing it the application context here — once, ahead of any save — is
        // what lets the index ride every write without threading a Context through the file layer.
        com.toolsboox.plugin.calendar.ot.PickingsCards.attach(this)
        // The notification categories exist from first launch, before any feature posts one:
        // channels registered here appear on the system's per-app notification page immediately,
        // so the per-category switches Michael asked for are settable ahead of the first nudge
        // rather than materializing one by one as features happen to fire. Idempotent — see
        // [LedgerNotifications] for why the registry precedes its posters.
        com.toolsboox.ot.LedgerNotifications.ensureChannels(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

}
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
        // The Mihon extension runtime's registry — seated before anything can dex-load a source.
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
        com.toolsboox.plugin.calendar.ot.ArticlePickIndex.attach(this)
        // The notification categories exist from first launch, before any feature posts one:
        // channels registered here appear on the system's per-app notification page immediately,
        // so the per-category switches Michael asked for are settable ahead of the first nudge
        // rather than materializing one by one as features happen to fire. Idempotent — see
        // [LedgerNotifications] for why the registry precedes its posters.
        com.toolsboox.ot.LedgerNotifications.ensureChannels(this)
        // The diagnostics room: on a fleet with no adb (the B6 field loop, a tester's HiBreak
        // Plus), an uncaught exception otherwise dies unreadable. The log ring's tree is
        // planted here, ahead of MainActivity's logcat trees; the crash recorder writes each
        // trace to filesDir/diagnostics before the process goes down; the ring flushes on the
        // way to the background. Settings › Diagnostics reads, copies and shares all of it.
        com.toolsboox.ot.Diagnostics.install(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

}
package com.toolsboox.ot

import com.squareup.moshi.Moshi

/**
 * THE one Moshi the ledger's files are read and written with.
 *
 * Every day/week/month/year file on disk carries `Locale`, `Date` and `UUID` fields, and a Moshi
 * without these three adapters cannot decode one: it throws, and before 2026-10-12 a throw on a
 * day load read as "corrupt day file … treating as unwritten" (Android -322, the Synth landing that
 * never landed). NetworkModule.provideMoshi hands this to Hilt; the places that live outside Hilt
 * (the widget renderers, the player capture, the share sheet) reach it through
 * `CalendarDayService.standalone(context)` instead of copying the three-adapter builder by hand —
 * a fourth copy that forgot one adapter would decode nothing and say so only in logcat. The sync
 * workers (WebDAV, Ultrabridge, CalDAV, the quick day mirror) and the widget renderer, which need a
 * Moshi rather than a day service, call [standard] directly; no hand-built copy is left.
 */
object LedgerMoshi {

    /** A fresh instance with the ledger's three adapters. Cheap; Moshi caches adapters per instance. */
    fun standard(): Moshi = Moshi.Builder()
        .add(LocaleJsonAdapter())
        .add(DateJsonAdapter())
        .add(UUIDJsonAdapter())
        .build()
}

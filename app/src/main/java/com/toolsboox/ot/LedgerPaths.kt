package com.toolsboox.ot

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File

/**
 * Where the ledger lives on disk.
 *
 * One answer, because every screen that reads or writes a day needs the same one — this had been
 * copied into five files, each re-deciding the pre-R fallback for itself.
 */
object LedgerPaths {

    /** Root of the day JSONs (`calendar/yyyy/MM/day-*-v2.json` hangs off this). */
    fun documentsRoot(context: Context): File =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)!!
        else
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "toolsBoox")

    /**
     * The day file for one date, newest format first, or null when that day was never written.
     *
     * The `calendar/yyyy/MM/day-yyyy-MM-dd[-v2].json` layout and the v2-then-v1 preference had been
     * open-coded in every store that wanted to ASK something about a day without loading it (how
     * many sub-pages, when was it last written). It belongs next to [documentsRoot] for the same
     * reason that did: five copies of a path convention is five places for it to drift.
     */
    fun dayFile(context: Context, date: java.time.LocalDate): File? {
        val y = "%04d".format(date.year)
        val m = "%02d".format(date.monthValue)
        val d = "%02d".format(date.dayOfMonth)
        val dir = File(documentsRoot(context), "calendar/$y/$m")
        return listOf(File(dir, "day-$y-$m-$d-v2.json"), File(dir, "day-$y-$m-$d.json"))
            .firstOrNull { it.exists() }
    }

    /** Persistent store for annotation and A/V-gram media; referenced by filename in the day JSON. */
    fun attachmentsDir(context: Context): File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "attachments").apply { mkdirs() }
}

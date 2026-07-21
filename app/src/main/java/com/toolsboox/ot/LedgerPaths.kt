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

    /** Persistent store for annotation and A/V-gram media; referenced by filename in the day JSON. */
    fun attachmentsDir(context: Context): File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "attachments").apply { mkdirs() }
}

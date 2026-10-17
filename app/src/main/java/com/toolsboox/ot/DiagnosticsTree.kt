package com.toolsboox.ot

import timber.log.Timber

/**
 * The Timber tree that feeds the diagnostics ring. It writes nothing to logcat — the
 * DebugTree/ReleaseTree MainActivity plants still do that — it only remembers.
 *
 * Deliberately NOT a DebugTree: DebugTree walks the stack for a tag on EVERY call, including
 * the verbose ink chatter this tree never keeps. Here the priority gate runs first and the
 * stack is only read for the lines that are kept (INFO and up), which is a handful a minute.
 */
class DiagnosticsTree(private val ring: DiagnosticsLog) : Timber.Tree() {

    override fun isLoggable(tag: String?, priority: Int): Boolean = priority >= DiagnosticsLog.INFO

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        // Cheap gate first: a stack walk per INFO line is exactly the cost the ring must not add.
        if (priority < DiagnosticsLog.WARN && tag == null && !DiagnosticsLog.keeps(priority, "", message)) {
            if (!DiagnosticsLog.keeps(priority, inferTag(), message)) return
        }
        val resolvedTag = tag ?: inferTag()
        if (!DiagnosticsLog.keeps(priority, resolvedTag, message)) return
        ring.add(DiagnosticsLog.formatLine(System.currentTimeMillis(), priority, resolvedTag, message, t))
    }

    /** The calling class's simple name, the way DebugTree names its lines. */
    private fun inferTag(): String? {
        val frame = Throwable().stackTrace.firstOrNull { el ->
            val c = el.className
            !c.startsWith("timber.log.") && !c.startsWith("com.toolsboox.ot.Diagnostics") &&
                !c.startsWith("java.lang.Thread") && !c.startsWith("dalvik.")
        } ?: return null
        var name = frame.className.substringAfterLast('.')
        // Strip the anonymous/lambda suffix: MailSync$fetch$1 → MailSync.
        val dollar = name.indexOf('$')
        if (dollar > 0) name = name.substring(0, dollar)
        return name
    }
}

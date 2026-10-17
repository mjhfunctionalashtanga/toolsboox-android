package com.toolsboox.ot

import java.util.concurrent.ConcurrentHashMap

/**
 * Ink-latency counters: label-keyed ring buffers with a zero-allocation hot path,
 * percentiles computed lazily at report time.
 *
 * Adapted from mokke's PerfCounters (https://github.com/imedwei/mokke-ink-notes,
 * MIT License, Copyright (c) 2026 Durham Goode) — trimmed to the label-keyed form;
 * the enum-keyed fast path and bug-report plumbing stayed behind.
 *
 * The audience is the person holding the pen: the B6 has no adb, so the report
 * dialog in Settings is the only latency instrument in the field. The same
 * counters serve the Boox "slowdowns when writing" question.
 */
object InkPerf {

    private const val WINDOW_SIZE = 100

    private val counters = ConcurrentHashMap<String, RingCounter>()

    /** Record one sample under [label]. Hot path: map lookup + two array writes. */
    fun record(label: String, elapsedNanos: Long) {
        counters.computeIfAbsent(label) { RingCounter(WINDOW_SIZE) }.record(elapsedNanos)
    }

    fun reset() = counters.clear()

    /** Multi-line human-readable report, one row per label with samples. */
    fun report(): String {
        val rows = counters.entries
            .map { (label, counter) -> label to counter.snapshot() }
            .filter { it.second.count > 0 }
            .sortedBy { it.first }
        if (rows.isEmpty()) return "No samples yet — write a few strokes first."
        return rows.joinToString("\n\n") { (label, s) ->
            "$label\n  n=${s.count}  last=${s.lastMs}ms  p50=${s.p50Ms}ms  p95=${s.p95Ms}ms  max=${s.maxMs}ms"
        }
    }

    class Snapshot(val count: Long, val lastMs: Long, val p50Ms: Long, val p95Ms: Long, val maxMs: Long)

    private class RingCounter(private val capacity: Int) {
        private val elapsedNanos = LongArray(capacity)
        private var writeIdx = 0
        private var totalCount = 0L

        @Synchronized
        fun record(elapsed: Long) {
            elapsedNanos[writeIdx % capacity] = elapsed
            writeIdx++
            totalCount++
        }

        @Synchronized
        fun snapshot(): Snapshot {
            if (totalCount == 0L) return Snapshot(0, 0, 0, 0, 0)
            val size = minOf(totalCount, capacity.toLong()).toInt()
            val lastMs = elapsedNanos[(writeIdx - 1 + capacity) % capacity] / 1_000_000
            val sorted = LongArray(size)
            for (i in 0 until size) sorted[i] = elapsedNanos[i] / 1_000_000
            sorted.sort()
            return Snapshot(
                count = totalCount,
                lastMs = lastMs,
                p50Ms = sorted[size / 2],
                p95Ms = sorted[(size * 95L / 100).toInt().coerceAtMost(size - 1)],
                maxMs = sorted[size - 1],
            )
        }
    }
}

package com.toolsboox.plugin.calendar.da.v2

import com.squareup.moshi.JsonClass
import java.util.UUID

/**
 * A named Kanban board. Boards live in one synced sidecar file (`boards/boards.json`), id-union
 * merged like contacts. A task's `board` (= Board.id) files it onto one board; "" shows on All.
 * Wire-compatible with the iOS `Board`.
 */
@JsonClass(generateAdapter = true)
data class Board(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var created: Long = System.currentTimeMillis(),
    var updated: Long = System.currentTimeMillis(),
    var deletedAt: Long = 0L
) {
    val isDeleted: Boolean get() = deletedAt > 0L
}

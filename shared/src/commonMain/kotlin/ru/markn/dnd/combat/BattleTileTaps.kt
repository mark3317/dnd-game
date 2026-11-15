package ru.markn.dnd.combat

/** Select immediately; a second release on the same tile confirms without delaying selection. */
internal class BattleTileTaps(private val timeoutMillis: Long) {
    private var previous: Tile? = null
    private var releasedAt = 0L

    fun release(tile: Tile, timeMillis: Long): Boolean {
        if (tile == previous && timeMillis - releasedAt in 0..timeoutMillis) {
            reset()
            return true
        }
        previous = tile
        releasedAt = timeMillis
        return false
    }

    fun reset() { previous = null }
}

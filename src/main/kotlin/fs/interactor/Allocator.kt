package fs.interactor

data class MemoryArea(val begin: Long, val size: Long)

/**
 * Provides interface describing allocation strategy.
 */
interface Allocator {
    fun registerFreeArea(area: MemoryArea)
    fun registerUsedArea(area: MemoryArea)

    fun unregisterFreeArea(position: Long): MemoryArea
    fun unregisterUsedArea(position: Long): MemoryArea

    fun allocateNewData(minimalSize: Long, fitted: Boolean = false): MemoryArea
    fun clear()
}
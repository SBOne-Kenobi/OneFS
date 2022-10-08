package fs.interactor

import java.util.TreeSet
import kotlin.math.max

class SimpleAllocator: Allocator {

    private var lastPosition: Long = 0

    private val freeAreas: HashMap<Long, MemoryArea> = HashMap()
    private val usedAreas: HashMap<Long, MemoryArea> = HashMap()

    private val freeAreasOrderedBySize: TreeSet<MemoryArea> =
        TreeSet(compareBy<MemoryArea> { it.size }.thenBy { it.begin })

    private fun registerAny(area: MemoryArea) {
        lastPosition = max(lastPosition, area.begin + area.size)
    }

    override fun registerFreeArea(area: MemoryArea) {
        freeAreas[area.begin] = area
        freeAreasOrderedBySize.add(area)
        registerAny(area)
    }

    override fun registerUsedArea(area: MemoryArea) {
        usedAreas[area.begin] = area
        registerAny(area)
    }

    override fun unregisterFreeArea(position: Long): MemoryArea {
        return freeAreas.remove(position)!!.also {
            freeAreasOrderedBySize.remove(it)
        }
    }

    override fun unregisterUsedArea(position: Long): MemoryArea {
        return usedAreas.remove(position)!!
    }

    private fun Long.computeNewSize(fitted: Boolean): Long {
        if (fitted) {
            return this
        }
        var result = 1L
        while (result < this) {
            result *= 2
        }
        return result
    }

    override fun allocateNewData(minimalSize: Long, fitted: Boolean): MemoryArea {
        val targetArea = freeAreasOrderedBySize.ceiling(MemoryArea(-1, minimalSize))
        if (targetArea != null) {
            if (!fitted || targetArea.size == minimalSize) {
                unregisterFreeArea(targetArea.begin)
                registerUsedArea(targetArea)
                return targetArea
            }
        }

        val computeNewSize = minimalSize.computeNewSize(fitted)
        val newArea = MemoryArea(lastPosition, computeNewSize)
        registerUsedArea(newArea)

        return newArea
    }

    override fun clear() {
        lastPosition = 0
        freeAreas.clear()
        freeAreasOrderedBySize.clear()
        usedAreas.clear()
    }
}
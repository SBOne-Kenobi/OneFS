package utils


/**
 * List that implements lazy values initializing.
 */
class LazyList<T>(private val initializers: List<() -> T>) : List<T> {
    private val computed = HashMap<Int, T>()

    override val size: Int = initializers.size

    override fun get(index: Int): T {
        return computed.getOrPut(index) {
            initializers[index]()
        }
    }

    override fun isEmpty(): Boolean = initializers.isEmpty()

    override fun iterator(): Iterator<T> = listIterator()

    override fun listIterator(): ListIterator<T> = listIterator(0)

    override fun listIterator(index: Int): ListIterator<T> = object : ListIterator<T> {
        var currentIndex = index
        override fun hasNext(): Boolean = currentIndex < size
        override fun hasPrevious(): Boolean = currentIndex > 0
        override fun next(): T = get(currentIndex++)
        override fun nextIndex(): Int = currentIndex
        override fun previous(): T = get(--currentIndex)
        override fun previousIndex(): Int = currentIndex - 1
    }

    override fun subList(fromIndex: Int, toIndex: Int): List<T> =
        LazyList((fromIndex until toIndex).map { { get(it) } })

    override fun lastIndexOf(element: T): Int {
        for (i in size - 1 downTo 0) {
            if (get(i) == element) {
                return i
            }
        }
        return -1
    }

    override fun indexOf(element: T): Int {
        for (i in 0 until size) {
            if (get(i) == element) {
                return i
            }
        }
        return -1
    }

    override fun containsAll(elements: Collection<T>): Boolean = elements.all { contains(it) }

    override fun contains(element: T): Boolean = indexOf(element) != -1
}
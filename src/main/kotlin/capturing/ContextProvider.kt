package capturing

/**
 * Interface for creating needed [Context]s.
 */
interface ContextProvider<R: ReadContext, W: WriteContext> {
    suspend fun createReadContext(): R
    suspend fun createWriteContext(): W
}
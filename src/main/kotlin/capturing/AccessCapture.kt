package capturing

interface Context
interface ReadContext : Context
interface WriteContext : Context

/**
 * Interface for read-write capturing.
 */
interface AccessCapture<out R: ReadContext, out W: WriteContext> {

    /**
     * Wait for success capturing write access.
     */
    suspend fun captureWrite(block: suspend W.() -> Unit)

    /**
     * Wait for success capturing read access.
     */
    suspend fun <T> captureRead(block: suspend R.() -> T): T

    /**
     * Try to capture write access without long waiting.
     */
    suspend fun tryCaptureWrite(block: suspend W.() -> Unit)

    /**
     * Try to capture read access without long waiting.
     */
    suspend fun <T> tryCaptureRead(block: suspend R.() -> T): T

}

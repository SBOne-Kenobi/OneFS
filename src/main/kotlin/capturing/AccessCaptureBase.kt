package capturing

import kotlinx.coroutines.coroutineScope

/**
 * This class provides convenient interface for capturing.
 */
abstract class AccessCaptureBase<R: ReadContext, W: WriteContext> : AccessCapture<R, W> {

    override suspend fun captureWrite(block: suspend W.() -> Unit) {
        captureWriteContext().use(block)
    }

    override suspend fun <T> captureRead(block: suspend R.() -> T): T =
        captureReadContext().use(block)

    /**
     * Try to capture write access.
     *
     * @throws WriteCaptureException when capturing failed
     */
    override suspend fun tryCaptureWrite(block: suspend W.() -> Unit) {
        tryCaptureWriteContext()?.use(block)
            ?: throw WriteCaptureException()
    }

    /**
     * Try to capture read access.
     *
     * @throws ReadCaptureException when capturing failed
     */
    override suspend fun <T> tryCaptureRead(block: suspend R.() -> T): T =
        tryCaptureReadContext()?.use(block)
            ?: throw ReadCaptureException()


    /**
     * Wrapper for [Context] capturing.
     *
     * @param context capturing context
     * @param onRelease function to call after capturing.
     */
    protected class ContextCapture<C: Context>(val context: C, val onRelease: suspend () -> Unit) {

        /**
         * Invoke [block] and call [onRelease] after.
         */
        suspend inline fun <T> use(crossinline block: suspend C.() -> T): T = coroutineScope {
            try {
                coroutineScope {
                    block.invoke(context)
                }
            } finally {
                onRelease()
            }
        }
    }

    /**
     * Capture write access and create [WriteContext].
     */
    protected abstract suspend fun captureWriteContext(): ContextCapture<W>

    /**
     * Capture read access and create [ReadContext].
     */
    protected abstract suspend fun captureReadContext(): ContextCapture<R>

    /**
     * Try to capture write access or return null on failure.
     */
    protected abstract suspend fun tryCaptureWriteContext(): ContextCapture<W>?

    /**
     * Try to capture read access or return null on failure.
     */
    protected abstract suspend fun tryCaptureReadContext(): ContextCapture<R>?

}
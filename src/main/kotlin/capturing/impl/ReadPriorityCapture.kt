package capturing.impl

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import capturing.AccessCapture
import capturing.AccessCaptureBase
import capturing.Context
import capturing.ContextProvider
import capturing.ReadContext
import capturing.WriteContext

/**
 * [AccessCapture] with readers priority: when there is at least one reader, no writers can write and may wait forever.
 *
 * @param contextProvider provides [Context] creation
 */
class ReadPriorityCapture<R: ReadContext, W: WriteContext>(
    private val contextProvider: ContextProvider<R, W>
) : AccessCaptureBase<R, W>() {

    /**
     * Locked when any writer or at least one reader get access.
     */
    private val readCapture = Mutex()

    private var readerCount = 0
    private val readerMutex = Mutex()

    private suspend fun unsafeWriteContext(): ContextCapture<W> {
        val context = contextProvider.createWriteContext()
        return ContextCapture(context) {
            readCapture.unlock()
        }
    }

    private suspend fun unsafeReadContext(): ContextCapture<R> {
        val context = contextProvider.createReadContext()
        return ContextCapture(context) {
            readerMutex.withLock {
                if (--readerCount == 0) {
                    readCapture.unlock()
                }
            }
        }
    }

    override suspend fun captureWriteContext(): ContextCapture<W> {
        readCapture.lock()
        return unsafeWriteContext()
    }

    override suspend fun captureReadContext(): ContextCapture<R> {
        readerMutex.withLock {
            if (++readerCount == 1) {
                readCapture.lock()
            }
        }
        return unsafeReadContext()
    }

    override suspend fun tryCaptureWriteContext(): ContextCapture<W>? =
        if (readCapture.tryLock()) {
            unsafeWriteContext()
        } else {
            null
        }

    override suspend fun tryCaptureReadContext(): ContextCapture<R>? {
        readerMutex.withLock {
            if (readerCount == 0 && !readCapture.tryLock()) {
                return null
            }
            ++readerCount
        }
        return unsafeReadContext()
    }

}
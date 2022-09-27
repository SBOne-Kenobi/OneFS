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
 * [AccessCapture] with writers priority: when there is at least one writer, all new readers will be waiting for access.
 *
 * @param contextProvider provides [Context] creation.
 */
class WritePriorityCapture<R: ReadContext, W: WriteContext>(
    private val contextProvider: ContextProvider<R, W>
) : AccessCaptureBase<R, W>() {

    /**
     * Locked when at least one writer and any reader get access.
     */
    private val writeCaptured = Mutex()

    /**
     * Locked when at least one reader and any writer get access.
     */
    private val canWrite = Mutex()

    private var readerCount = 0
    private val readerMutex = Mutex()

    private var writerCount = 0
    private val writerMutex = Mutex()

    private suspend fun unsafeWriteContext(): ContextCapture<W> {
        val context = contextProvider.createWriteContext()
        return ContextCapture(context) {
            canWrite.unlock()
            writerMutex.withLock {
                if (--writerCount == 0) {
                    writeCaptured.unlock()
                }
            }
        }
    }

    private suspend fun unsafeReadContext(): ContextCapture<R> {
        val context = contextProvider.createReadContext()
        return ContextCapture(context) {
            readerMutex.withLock {
                if (--readerCount == 0) {
                    canWrite.unlock()
                }
            }
        }
    }

    override suspend fun captureWriteContext(): ContextCapture<W> {
        writerMutex.withLock {
            if (++writerCount == 1) {
                writeCaptured.lock()
            }
        }
        canWrite.lock()
        return unsafeWriteContext()
    }

    override suspend fun captureReadContext(): ContextCapture<R> {
        writeCaptured.withLock {
            readerMutex.withLock {
                if (++readerCount == 1) {
                    canWrite.lock()
                }
            }
        }
        return unsafeReadContext()
    }

    override suspend fun tryCaptureWriteContext(): ContextCapture<W>? {
        writerMutex.withLock {
            if (writerCount == 0 && !writeCaptured.tryLock()) {
                return null
            }
            ++writerCount
        }
        if (!canWrite.tryLock()) {
            writeCaptured.unlock()
            return null
        }
        return unsafeWriteContext()
    }

    override suspend fun tryCaptureReadContext(): ContextCapture<R>? {
        if (!writeCaptured.tryLock()) {
            return null
        }
        readerMutex.withLock {
            if (++readerCount == 1) {
                canWrite.lock() // always success
            }
        }
        writeCaptured.unlock()
        return unsafeReadContext()
    }

}

package capturing

import capturing.impl.ReadPriorityCapture
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test

class ReadPriorityCaptureTest {

    @Test
    fun testReaders() = runTest {
        val delayMs = 100L
        val readers = 1000

        val provider = MockContextProvider()
        val capture = ReadPriorityCapture(provider)

        coroutineScope {
            repeat(readers) {
                launch {
                    capture.captureRead { work(delayMs) }
                }
            }
        }

        assertEquals(readers, provider.readerMockContext.getMax())
        assertEquals(0, provider.readerMockContext.getCurrent())
    }

    @Test
    fun testWriters() = runTest {
        val delayMs = 100L
        val writers = 10

        val provider = MockContextProvider()
        val capture = ReadPriorityCapture(provider)

        coroutineScope {
            repeat(writers) {
                launch {
                    capture.captureWrite { work(delayMs) }
                }
            }
        }

        assertEquals(1, provider.writerMockContext.getMax())
        assertEquals(0, provider.writerMockContext.getCurrent())
    }

    @Test
    fun testPriority() = runTest {
        val delayMs = 400L
        val waitDelayMs = 50L
        val readers = 50

        val provider = MockContextProvider()
        val capture = ReadPriorityCapture(provider)

        coroutineScope {
            repeat(readers) {
                launch {
                    capture.captureRead { work(delayMs) }
                }
            }
            withContext(Dispatchers.Default) { // sure that all readers captured
                delay(waitDelayMs)
            }
            launch {
                capture.captureWrite { work(delayMs) }
            }
            repeat(readers) {
                launch {
                    capture.captureRead { work(delayMs) }
                }
            }
        }

        assertEquals(2 * readers, provider.readerMockContext.getMax()) // all readers go first
        assertEquals(1, provider.writerMockContext.getMax())
    }

    @Test
    fun testTryCaptureWrite() = runTest {
        val delayMs = 400L
        val waitDelayMs = 50L
        val readers = 50

        val provider = MockContextProvider()
        val capture = ReadPriorityCapture(provider)

        coroutineScope {
            repeat(readers) {
                launch {
                    capture.captureRead { work(delayMs) }
                }
            }
            withContext(Dispatchers.Default) { // sure that all readers captured
                delay(waitDelayMs)
            }
            try {
                capture.tryCaptureWrite { work(delayMs) }
            } catch (_: WriteCaptureException) { }
        }

        assertEquals(0, provider.writerMockContext.getMax())
    }


    @Test
    fun testTryCaptureRead() = runTest {
        val delayMs = 400L
        val waitDelayMs = 50L

        val provider = MockContextProvider()
        val capture = ReadPriorityCapture(provider)

        coroutineScope {
            launch {
                capture.captureWrite { work(delayMs) }
            }
            withContext(Dispatchers.Default) { // sure that writer captured
                delay(waitDelayMs)
            }
            try {
                capture.tryCaptureRead { work(delayMs) }
            } catch (_: ReadCaptureException) { }
        }

        assertEquals(0, provider.readerMockContext.getMax())
    }
}
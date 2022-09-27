package capturing

import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

open class MockContext : Context {
    private val mutex = Mutex()
    private var currentWorkersInTime = 0
    private var maxWorkersInTime = 0

    suspend fun getCurrent(): Int = mutex.withLock {
        currentWorkersInTime
    }

    suspend fun getMax(): Int = mutex.withLock {
        maxWorkersInTime
    }

    suspend fun work(timeMillis: Long) {
        mutex.withLock {
            maxWorkersInTime = max(maxWorkersInTime, ++currentWorkersInTime)
        }
        withContext(Dispatchers.Default) {
            delay(timeMillis)
        }
        mutex.withLock {
            --currentWorkersInTime
        }
    }
}

class MockReadContext(private val context: MockContext) : ReadContext {
    suspend fun work(timeMillis: Long) {
        context.work(timeMillis)
    }
}

class MockWriteContext(private val context: MockContext) : WriteContext {
    suspend fun work(timeMillis: Long) {
        context.work(timeMillis)
    }
}

class MockContextProvider : ContextProvider<MockReadContext, MockWriteContext> {
    val readerMockContext = MockContext()
    val writerMockContext = MockContext()

    override suspend fun createReadContext() =
        MockReadContext(readerMockContext)

    override suspend fun createWriteContext() =
        MockWriteContext(writerMockContext)
}
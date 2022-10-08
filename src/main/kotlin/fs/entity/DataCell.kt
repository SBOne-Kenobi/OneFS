package fs.entity

import java.io.InputStream
import java.io.OutputStream
import kotlin.properties.Delegates
import org.apache.commons.io.input.BoundedInputStream


interface DataPointerObserver : AutoCloseable {
    fun lengthOnChange(newValue: Long) { }
}

/**
 * Pointer to file's content.
 */
class DataPointer(
    val beginPosition: Long,
    dataLength: Long,
    val dataCapacity: Long = dataLength,
    observer: DataPointerObserver
) : AutoCloseable by observer {
    var dataLength: Long by Delegates.observable(dataLength) { _, _, new -> observer.lengthOnChange(new) }
}

/**
 * Class to interact and control file's content.
 */
interface DataCellController : AutoCloseable {
    val dataPointer: DataPointer

    fun getInputStream(): InputStream
    fun getOutputStream(offset: Long): OutputStream

    fun allocateNew(newMinimalSize: Long): DataCellController
    fun free()

    fun createDeepCopy(): DataCellController
}

interface DataCellInterface {
    fun getInputStream(): InputStream
}

class DataCell(private val controller: DataCellController) : DataCellInterface {
    override fun getInputStream(): InputStream {
        val length = controller.dataPointer.dataLength
        return BoundedInputStream(controller.getInputStream(), length)
    }
}

class MutableDataCell(controller: DataCellController) : DataCellInterface {
    var controller: DataCellController = controller
        private set

    override fun getInputStream(): InputStream {
        val length = controller.dataPointer.dataLength
        return BoundedInputStream(controller.getInputStream(), length)
    }

    fun clearData() {
        controller.dataPointer.dataLength = 0
    }

    /**
     * Create [OutputStream] that controls data size and reallocation if needed.
     */
    fun getOutputStream(offset: Long): OutputStream {
        val targetOffset = if (offset == -1L) {
            controller.dataPointer.dataLength
        } else {
            offset.coerceIn(0, controller.dataPointer.dataLength)
        }
        return object : OutputStream() {
            private var baseOutputStream = controller.getOutputStream(targetOffset)
            private var wroteBytes: Long = targetOffset
            private val capacity: Long
                get() = controller.dataPointer.dataCapacity

            private fun reallocate(size: Long) {
                baseOutputStream.close()

                val newController = controller.allocateNew(size)
                val output = newController.getOutputStream(offset = 0)
                getInputStream().use { input ->
                    input.copyTo(output)
                }

                controller.free()
                controller = newController
                baseOutputStream = output
            }

            private fun updateSize() {
                if (wroteBytes > controller.dataPointer.dataLength) {
                    controller.dataPointer.dataLength = wroteBytes
                }
            }

            override fun write(b: Int) {
                if (wroteBytes == capacity) {
                    reallocate(wroteBytes + 1)
                }
                baseOutputStream.write(b)
                ++wroteBytes
                updateSize()
            }

            override fun close() {
                super.close()
                baseOutputStream.close()
            }

            override fun flush() {
                super.flush()
                baseOutputStream.flush()
            }

            override fun write(data: ByteArray, off: Int, len: Int) {
                if (off < 0 || off > data.size || len < 0 || off + len > data.size || off + len < 0) {
                    throw IndexOutOfBoundsException()
                } else if (len == 0) {
                    return
                }
                if (wroteBytes + len >= capacity) {
                    reallocate(wroteBytes + len)
                }
                baseOutputStream.write(data, off, len)
                wroteBytes += len
                updateSize()
            }

        }
    }

}

package utils

import java.io.OutputStream
import java.io.RandomAccessFile

class RandomAccessFileOutputStream(
    private val raf: RandomAccessFile,
    private val closeOnClose: Boolean = false
) : OutputStream() {
    private var currentPosition = raf.filePointer

    override fun write(b: Int) {
        raf.seek(currentPosition)
        raf.write(b)
        ++currentPosition
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        raf.seek(currentPosition)
        raf.write(b, off, len)
        currentPosition += len
    }

    override fun close() {
        super.close()
        if (closeOnClose) {
            raf.close()
        }
    }
}
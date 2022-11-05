package fs

import fs.entity.DataCell
import fs.entity.DataCellController
import fs.entity.FSPath
import fs.entity.FileNode
import fs.entity.FolderNode
import fs.entity.ItemPointer
import fs.entity.ItemRecord
import fs.entity.LONG_SIZE
import fs.entity.MutableDataCell
import fs.entity.NodeLoader
import fs.entity.ParseError
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * Split path string into sequence of folders/files names.
 */
fun String.toPathSequence(): Sequence<String> {
    var res = if (lastOrNull() == '/') {
        dropLast(1)
    } else {
        this
    }.splitToSequence('/')
    if (startsWith('/') || startsWith('.')) {
        res = res.drop(1)
    }
    return res
}

fun getDigestMD5(): MessageDigest =
    MessageDigest.getInstance("MD5")

/**
 * Compute md5 hash.
 */
fun ByteArray.computeMD5(): ByteArray {
    val md = getDigestMD5()
    return md.digest(this)
}

fun InputStream.computeMD5(): ByteArray {
    val md = getDigestMD5()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val size = read(buffer)
        if (size <= 0) {
            break
        }
        md.update(buffer, 0, size)
    }
    return md.digest()
}

val emptyMD5: ByteArray
    get() = getDigestMD5().digest()

/**
 * Generate flow of files.
 */
suspend fun NavContext.getFlowOfFiles(
    recursive: Boolean
): Flow<NodeLoader<FileNode>> {
    if (!recursive) {
        return currentFolder.files.asFlow()
    }
    return flow {
        val stack = ArrayList<Triple<FolderNode, FSPath, Int>>()
        stack.add(Triple(currentFolder, currentPath, 0))
        while (stack.isNotEmpty()) {
            val (folderNode, path, index) = stack.removeLast()
            if (index < folderNode.folders.size) {
                stack.add(Triple(folderNode, path, index + 1))

                folderNode.folders[index].use { nextNode ->
                    stack.add(Triple(nextNode.load(), nextNode.path, 0))
                }
            } else {
                emitAll(folderNode.files.asFlow())
            }
        }
    }
}

fun OutputStream.skip(size: Long) {
    val buffer = ByteArray(size.coerceAtMost(DEFAULT_BUFFER_SIZE.toLong()).toInt())
    var remaining = size
    while (remaining > 0) {
        val nextSize = remaining.coerceAtMost(buffer.size.toLong()).toInt()
        write(buffer, 0, nextSize)
        remaining -= nextSize
    }
}

fun InputStream.readLong(): Long = readNBytes(LONG_SIZE).let { bytes ->
    if (bytes.size != LONG_SIZE) {
        throw ParseError("Failed to parse next long")
    }
    ByteBuffer.wrap(bytes).long
}

fun OutputStream.writeLong(long: Long) {
    ByteBuffer
        .allocate(LONG_SIZE)
        .putLong(long)
        .array()
        .let { bytes -> write(bytes) }
}

fun ItemRecord.toPointer(): ItemPointer =
    ItemPointer(beginRecordPosition)

fun DataCellController.toMutableDataCell(): MutableDataCell =
    MutableDataCell(this)

fun DataCellController.toDataCell(): DataCell =
    DataCell(this)

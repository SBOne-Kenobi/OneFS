package fs

import fs.entity.FileNodeInterface
import fs.entity.FolderNodeInterface
import fs.entity.ItemRecord
import fs.entity.NodeWithPath
import fs.entity.add
import fs.entity.mutable
import fs.interactor.MemoryArea
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

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
 * Generate flow of files with type [File].
 */
suspend inline fun <reified File : FileNodeInterface, Folder: FolderNodeInterface> NavContext<File, Folder>.getFlowOfFiles(
    recursive: Boolean
): Flow<NodeWithPath<File>> {
    if (!recursive) {
        return currentFolder.files
            .asFlow()
            .map { NodeWithPath(it as File, currentPath.mutable().add(it)) }
    }
    return flow {
        val stack = ArrayList<Pair<NodeWithPath<FolderNodeInterface>, Int>>()
        stack.add(NodeWithPath(currentFolder, currentPath.mutable()) to 0)
        while (stack.isNotEmpty()) {
            val (folderNodeWithPath, index) = stack.removeLast()
            val (folderNode, path) = folderNodeWithPath
            if (index < folderNode.folders.size) {
                stack.add(folderNodeWithPath to index + 1)

                val nextNode = folderNode.folders[index]
                stack.add(NodeWithPath(nextNode, path.copy().add(nextNode)) to 0)
            } else {
                folderNode.files.forEach {
                    emit(NodeWithPath(it as File, path.copy().add(it)))
                }
            }
        }
    }
}

fun <T> List<*>.toInits(): MutableList<() -> T> =
    mapTo(mutableListOf()) { { throw IllegalStateException("Must be replaced!") } }

fun ByteArray.toInt(): Int =
    ByteBuffer.wrap(this).int

fun ItemRecord.toMemoryArea(): MemoryArea =
    MemoryArea(beginRecordPosition, recordSize)

fun OutputStream.skip(size: Long) {
    val buffer = ByteArray(size.coerceAtMost(DEFAULT_BUFFER_SIZE.toLong()).toInt())
    var remaining = size
    while (remaining > 0) {
        val nextSize = remaining.coerceAtMost(buffer.size.toLong()).toInt()
        write(buffer, 0, nextSize)
        remaining -= nextSize
    }
}

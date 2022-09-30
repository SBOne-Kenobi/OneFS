package fs

import fs.proto.File
import fs.proto.FileOrBuilder
import fs.proto.Folder
import fs.proto.FolderOrBuilder
import fs.proto.MD5
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

/**
 * Compute md5 hash.
 */
fun ByteArray.computeMD5(): MD5 {
    val md = MessageDigest.getInstance("MD5")
    val bytes = md.digest(this).map { it.toULong() }
    val little = bytes.drop(2).reduce { acc, byte -> acc.shl(Byte.SIZE_BITS) + byte }
    val upper = bytes.take(2).reduce { acc, byte -> acc.shl(Byte.SIZE_BITS) + byte }
    return MD5.newBuilder().apply {
        this.little = little.toLong()
        this.upper = upper.toLong()
    }.build()
}

fun FileOrBuilder.build(): File = when (this) {
    is File -> this
    is File.Builder -> build()
    else -> throw IllegalStateException("Unexpected type ${this::class}")
}

fun FolderOrBuilder.build(): Folder = when (this) {
    is Folder -> this
    is Folder.Builder -> build()
    else -> throw IllegalStateException("Unexpected type ${this::class}")
}

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

fun Int.toByteArray(): ByteArray =
    ByteBuffer.allocate(Int.SIZE_BYTES).putInt(this).array()

fun ByteArray.toInt(): Int =
    ByteBuffer.wrap(this).int

fun String.excludeLast(): String =
    substringBeforeLast('/').takeIf { it.isNotBlank() } ?: "/"

fun String.lastName(): String =
    substringAfterLast('/')

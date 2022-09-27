package fs

import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.readBytes

/**
 * Convert folders sequence to path string.
 */
val Iterable<FolderOrBuilder>.path: String
    get() = joinToString("/", postfix = "/") { it.name }

/**
 * Split path string into sequence of folders/files names.
 */
fun String.toPathSequence(): Sequence<String> =
    if (lastOrNull() == '/') {
        dropLast(1)
    } else {
        this
    }.splitToSequence('/')

/**
 * Compute md5 hash for File.
 */
fun FileOrBuilder.computeMD5(): MD5 {
    val md = MessageDigest.getInstance("MD5")
    val bytes = md.digest(data.toByteArray()).map { it.toULong() }
    val little = bytes.drop(2).reduce { acc, byte -> acc.shl(Byte.SIZE_BITS) + byte }
    val upper = bytes.take(2).reduce { acc, byte -> acc.shl(Byte.SIZE_BITS) + byte }
    return MD5.newBuilder().apply {
        this.little = little.toLong()
        this.upper = upper.toLong()
    }.build()
}

/**
 * Parse [FileSystem] from specified file.
 */
fun Path.toFileSystem(): FileSystem =
    FileSystem.parseFrom(readBytes())

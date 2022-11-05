package fs.entity

import fs.emptyMD5

/**
 * Interface for encapsulate node loading strategy.
 */
interface NodeLoader<out T: FSNode> : AutoCloseable {
    val name: String
    val path: FSPath

    fun load(): T
}

sealed interface FSNode {
    val parent: NodeLoader<FolderNode>?
}

class FileNode(
    val fileName: String,
    val creationTimestamp: Long,
    val modificationTimestamp: Long,
    val md5: ByteArray = emptyMD5,
    override val parent: NodeLoader<FolderNode>? = null
): FSNode

class FolderNode(
    val folderName: String,
    val files: List<NodeLoader<FileNode>> = emptyList(),
    val folders: List<NodeLoader<FolderNode>> = emptyList(),
    override val parent: NodeLoader<FolderNode>? = null
): FSNode

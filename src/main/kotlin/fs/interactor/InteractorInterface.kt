package fs.interactor

import fs.FolderNodeInterface
import fs.MutableFolderNode
import fs.proto.FileOrBuilder
import fs.proto.FolderOrBuilder

/**
 * Interface for generating and save sequence of change events.
 */
interface InteractorInterface {

    /**
     * Creates root folder node.
     */
    fun getFileSystem(): MutableFolderNode

    /**
     * Override sequence with optimized change messages to create [rootNode].
     */
    suspend fun overrideFileWith(rootNode: FolderNodeInterface)

    fun createFile(path: String, file: FileOrBuilder)

    fun deleteFile(path: String)

    fun moveFile(from: String, to: String)

    fun copyFile(from: String, to: String)

    fun modifyFile(path: String, data: ByteArray, begin: Int, end: Int, timestamp: Long)

    fun createFolder(path: String, folder: FolderOrBuilder)

    fun deleteFolder(path: String)

    fun moveFolder(from: String, to: String)

    fun copyFolder(from: String, to: String)

}
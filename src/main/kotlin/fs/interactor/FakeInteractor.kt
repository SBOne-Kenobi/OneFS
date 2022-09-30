package fs.interactor

import fs.FolderNodeInterface
import fs.MutableFolderNode
import fs.proto.FileOrBuilder
import fs.proto.Folder
import fs.proto.FolderOrBuilder

/**
 * Interactor that does nothing.
 */
class FakeInteractor : InteractorInterface {
    override fun getFileSystem(): MutableFolderNode =
        MutableFolderNode(Folder.newBuilder().apply { name = "" })

    override suspend fun overrideFileWith(rootNode: FolderNodeInterface) {}

    override fun createFile(path: String, file: FileOrBuilder) {}

    override fun deleteFile(path: String) {}

    override fun moveFile(from: String, to: String) {}

    override fun copyFile(from: String, to: String) {}

    override fun modifyFile(path: String, data: ByteArray, begin: Int, end: Int, timestamp: Long) {}

    override fun createFolder(path: String, folder: FolderOrBuilder) {}

    override fun deleteFolder(path: String) {}

    override fun moveFolder(from: String, to: String) {}

    override fun copyFolder(from: String, to: String) {}
}
package fs

import capturing.AccessCapture
import capturing.ContextProvider
import capturing.ReadContext
import capturing.WriteContext
import fs.entity.DirectoryAlreadyExists
import fs.entity.DirectoryNotFound
import fs.entity.FileAlreadyExists
import fs.entity.FileNodeInterface
import fs.entity.FileNotFound
import fs.entity.MutableDataCell
import fs.entity.MutableFSPath
import fs.entity.MutableFileNode
import fs.entity.MutableFolderNode
import fs.entity.NodeWithPath
import fs.entity.OneFileSystemException
import fs.entity.add
import fs.entity.copyFile
import fs.entity.copyFolder
import fs.entity.mutable
import fs.entity.name
import fs.entity.removeLast
import fs.interactor.InteractorInterface
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.FileSystems
import java.nio.file.PathMatcher
import kotlin.io.path.Path
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.fold

/**
 * Provider for using filesystem represented in one file.
 */
class OneFileSystemProvider(
    private val interactor: InteractorInterface
) : ContextProvider<FileSystemReadContext, FileSystemWriteContext> {

    private val navigator = FileSystemNavigator(interactor.getFileSystem())

    override suspend fun createReadContext(): FileSystemReadContext =
        object : FileSystemReadContext, AccessCapture<NavReadContext, NavWriteContext> by navigator {}

    override suspend fun createWriteContext(): FileSystemWriteContext {
        return object : FileSystemWriteContext, AccessCapture<NavReadContext, NavWriteContext> by navigator {

            override fun NavWriteContext.createFolder(name: String) {
                currentFolder.apply {
                    if (folders.firstOrNull { it.folderName == name } != null) {
                        throw DirectoryAlreadyExists(currentPath.path + name)
                    }
                    val folder = MutableFolderNode(name, parent = this)
                    folders.add(folder)
                    interactor.createFolder(folder)
                }
            }

            override fun NavWriteContext.createFile(
                name: String,
                data: ByteArray,
                creationTimestamp: Long,
                modificationTimestamp: Long,
                md5: ByteArray,
                dataCell: MutableDataCell?
            ) {
                val targetDataCell = dataCell ?: MutableDataCell(interactor.allocateNewData(data.size.toLong()))
                currentFolder.apply {
                    if (files.firstOrNull { it.fileName == name } != null) {
                        throw FileAlreadyExists(currentPath.path + name)
                    }
                    val file = MutableFileNode(
                        name, targetDataCell, creationTimestamp, modificationTimestamp, md5, parent = this
                    )
                    files.add(file)
                    interactor.createFile(file)
                }
            }

            override fun NavWriteContext.deleteFolder(name: String) {
                currentFolder.apply {
                    val folderPos = folders.indexOfFirst { it.folderName == name }.takeIf { it != -1 }
                        ?: throw DirectoryNotFound(currentPath.path + name)
                    val folder = folders.removeAt(folderPos)
                    interactor.deleteFolder(folder)
                }
            }

            override fun NavWriteContext.deleteFile(name: String) {
                currentFolder.apply {
                    val filePos = files.indexOfFirst { it.fileName == name }.takeIf { it != -1 }
                        ?: throw FileNotFound(currentPath.path + name)
                    val file = files.removeAt(filePos)
                    interactor.deleteFile(file)
                }
            }

            private fun NavWriteContext.getDestFolderAndNewName(
                destinationPath: String,
                lastIsFile: Boolean,
            ): DestFinderResult {
                val path = MutableFSPath(destinationPath)
                val lastIsDestFolder = destinationPath.endsWith('/')
                var (destinationFolder, resultPath) = if (destinationPath.startsWith('/')) {
                    rootFolder to MutableFSPath()
                } else {
                    currentFolder to currentPath.mutable()
                }

                val last = if (lastIsDestFolder) {
                    null
                } else {
                    val name = path.name
                    path.removeLast()
                    name
                }

                path.pathList.forEach { nextName ->
                    destinationFolder = destinationFolder.folders.firstOrNull {
                        it.folderName == nextName
                    } ?: throw DirectoryNotFound(destinationPath)
                    resultPath.add(destinationFolder)
                }


                val (destFileIndexOrNull, fileName) = last?.let { lastName ->
                    if (lastIsFile) {
                        destinationFolder.files.indexOfFirst {
                            it.fileName == lastName
                        }.takeIf { it != -1 }
                    } else {
                        destinationFolder.folders.indexOfFirst {
                            it.folderName == lastName
                        }.takeIf { it != -1 }
                    } to lastName
                } ?: (null to null)

                return DestFinderResult(destinationFolder, destFileIndexOrNull, fileName, resultPath)
            }

            private fun NavWriteContext.transferFileImpl(
                name: String, destinationPath: String, override: Boolean, isMove: Boolean,
            ) {
                val srcFile = getFile(name)

                val (destinationFolder, destFileIndexOrNull, newFileName, _) =
                    getDestFolderAndNewName(destinationPath, lastIsFile = true)

                if (destFileIndexOrNull != null) {
                    if (!override) {
                        throw FileAlreadyExists(destinationPath)
                    }
                    val deletedFile = destinationFolder.files.removeAt(destFileIndexOrNull)
                    interactor.deleteFile(deletedFile)
                }
                if (isMove) {
                    srcFile.parent?.files?.remove(srcFile)
                    srcFile.parent = destinationFolder
                    newFileName?.let {
                        srcFile.fileName = it
                    }
                    destinationFolder.files.add(srcFile)
                    interactor.updateFileRecord(srcFile)
                } else {
                    val newFile = srcFile.copyFile(interactor, destinationFolder) {
                        newFileName?.let {
                            fileName = it
                        }
                    }
                    destinationFolder.files.add(newFile)
                }
            }

            private fun NavWriteContext.transferFolderImpl(
                name: String, destinationPath: String, override: Boolean, isMove: Boolean,
            ) {
                val srcFolder = getFolder(name)

                val (destinationFolder, destFolderIndexOrNull, newFolderName, _) =
                    getDestFolderAndNewName(destinationPath, lastIsFile = false)

                if (destFolderIndexOrNull != null) {
                    if (!override) {
                        throw DirectoryAlreadyExists(destinationPath)
                    }
                    val deletedFolder = destinationFolder.folders.removeAt(destFolderIndexOrNull)
                    interactor.deleteFolder(deletedFolder)
                }
                if (isMove) {
                    srcFolder.parent?.folders?.remove(srcFolder)
                    srcFolder.parent = destinationFolder
                    newFolderName?.let {
                        srcFolder.folderName = it
                    }
                    destinationFolder.folders.add(srcFolder)
                    interactor.updateFolderRecord(srcFolder)
                } else {
                    val newFolder = srcFolder.copyFolder(interactor, destinationFolder) {
                        newFolderName?.let {
                            folderName = it
                        }
                    }
                    destinationFolder.folders.add(newFolder)
                }
            }

            override fun NavWriteContext.moveFile(name: String, destinationPath: String, override: Boolean) {
                transferFileImpl(name, destinationPath, override, isMove = true)
            }

            override fun NavWriteContext.moveFolder(name: String, destinationPath: String, override: Boolean) {
                transferFolderImpl(name, destinationPath, override, isMove = true)
            }

            override fun NavWriteContext.copyFile(name: String, destinationPath: String, override: Boolean) {
                transferFileImpl(name, destinationPath, override, isMove = false)
            }

            override fun NavWriteContext.copyFolder(name: String, destinationPath: String, override: Boolean) {
                transferFolderImpl(name, destinationPath, override, isMove = false)
            }

            override fun NavWriteContext.outputStream(name: String, offset: Long): OutputStream {
                val file = getFile(name)
                return file.dataCell.getOutputStream(offset)
            }

            override fun NavWriteContext.updateMD5(name: String) {
                val file = getFile(name)
                file.md5 = file.dataCell.getInputStream().computeMD5()
                interactor.updateFileRecord(file)
            }

            override fun NavWriteContext.clearFile(name: String) {
                val file = getFile(name)
                file.dataCell.clearData()
            }

            override fun NavWriteContext.appendIntoFile(name: String, data: ByteArray) {
                outputStream(name, offset = -1).use {
                    it.write(data)
                }
            }

            override fun <FileId> NavWriteContext.importFile(
                destinationPath: String,
                importer: Importer<FileId, *>,
                fileId: FileId
            ) {
                val (destinationFolder, fileIndex, fileName, _) =
                    getDestFolderAndNewName(destinationPath, lastIsFile = true)
                if (fileIndex != null) {
                    throw FileAlreadyExists(destinationPath)
                }
                if (fileName != null) {
                    System.err.println("Selected file name $fileName will be ignored")
                }
                val targetFile = try {
                    importer.importFile(interactor, destinationFolder, fileId)
                } catch (e: Throwable) {
                    throw OneFileSystemException("Failed while importing", e)
                }
                destinationFolder.files.add(targetFile)
            }

            override fun <FolderId> NavWriteContext.importDirectory(
                destinationPath: String,
                importer: Importer<*, FolderId>,
                folderId: FolderId
            ) {
                val (destinationFolder, dirIndex, dirName, _) =
                    getDestFolderAndNewName(destinationPath, lastIsFile = false)
                if (dirIndex != null) {
                    throw DirectoryAlreadyExists(destinationPath)
                }
                if (dirName != null) {
                    System.err.println("Selected directory name $dirName will be ignored")
                }
                val targetDir = try {
                    importer.importFolder(interactor, destinationFolder, folderId)
                } catch (e: Throwable) {
                    throw OneFileSystemException("Failed while importing", e)
                }
                destinationFolder.folders.add(targetDir)
            }
        }
    }
}


interface FileSystemReadContext : ReadContext, WithNavigator {

    /**
     * Find all files that matched [glob].
     *
     * @param recursive specify finding strategy. Default true.
     */
    suspend fun NavContextGeneric.findFiles(
        glob: String = "*",
        recursive: Boolean = true
    ): Flow<NodeWithPath<FileNodeInterface>> {
        val globMatcher = if (glob == "*") {
            PathMatcher { true }
        } else {
            FileSystems.getDefault().getPathMatcher("glob:$glob")
        }
        return getFlowOfFiles(recursive).filter { (_, fsPath) ->
            globMatcher.matches(Path(fsPath.path))
        }
    }

    fun NavContextGeneric.inputStream(name: String): InputStream =
        getFile(name).dataCell.getInputStream()

    fun NavContextGeneric.readFile(name: String): ByteArray =
        inputStream(name).use { it.readBytes() }

    /**
     * Validate all files in current directory.
     */
    suspend fun NavContextGeneric.validate(): Boolean {
        return getFlowOfFiles(recursive = true).fold(true) { acc, (fileNode, _) ->
            acc && fileNode.run {
                val targetMd5 = dataCell.getInputStream().computeMD5()
                md5.contentEquals(targetMd5)
            }
        }
    }

}

interface FileSystemWriteContext : WriteContext, FileSystemReadContext {

    fun NavWriteContext.createFolder(name: String)

    fun NavWriteContext.createFile(
        name: String,
        data: ByteArray = ByteArray(0),
        creationTimestamp: Long = System.currentTimeMillis(),
        modificationTimestamp: Long = creationTimestamp,
        md5: ByteArray = data.computeMD5(),
        dataCell: MutableDataCell? = null
    )

    fun NavWriteContext.deleteFolder(name: String)

    fun NavWriteContext.deleteFile(name: String)

    fun NavWriteContext.moveFile(name: String, destinationPath: String, override: Boolean = false)

    fun NavWriteContext.moveFolder(name: String, destinationPath: String, override: Boolean = false)

    fun NavWriteContext.copyFile(name: String, destinationPath: String, override: Boolean = false)

    fun NavWriteContext.copyFolder(name: String, destinationPath: String, override: Boolean = false)

    /**
     * Creates [OutputStream] for file [name] with [offset] from begin of specified file.
     *
     * Note: [offset] may be -1, it will mean the end of file.
     */
    fun NavWriteContext.outputStream(name: String, offset: Long = -1): OutputStream

    fun NavWriteContext.updateMD5(name: String)

    fun NavWriteContext.clearFile(name: String)

    fun NavWriteContext.appendIntoFile(name: String, data: ByteArray)

    /**
     * Create importing file with [fileId] using [importer] in [destinationPath].
     */
    fun <FileId> NavWriteContext.importFile(destinationPath: String, importer: Importer<FileId, *>, fileId: FileId)

    /**
     * Create importing directory with [folderId] using [importer] in [destinationPath].
     */
    fun <FolderId> NavWriteContext.importDirectory(destinationPath: String, importer: Importer<*, FolderId>, folderId: FolderId)

}

private data class DestFinderResult(
    val folderNode: MutableFolderNode,
    val indexOfDestNode: Int?,
    val resultName: String?,
    val path: MutableFSPath,
)

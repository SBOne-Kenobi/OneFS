package fs

import capturing.AccessCapture
import capturing.ContextProvider
import capturing.ReadContext
import capturing.WriteContext
import fs.entity.DirectoryAlreadyExists
import fs.entity.DirectoryNotFound
import fs.entity.FSNode
import fs.entity.FSPath
import fs.entity.FileAlreadyExists
import fs.entity.FileNode
import fs.entity.FileNotFound
import fs.entity.FolderNode
import fs.entity.NodeLoader
import fs.entity.OneFileSystemException
import fs.entity.addFile
import fs.entity.addFolder
import fs.entity.name
import fs.entity.removeLast
import fs.importer.CopyImporter
import fs.importer.Importer
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

    private val navigator = FileSystemNavigator(interactor)

    override suspend fun createReadContext(): FileSystemReadContext =
        object : FileSystemReadContext, AccessCapture<NavReadContext, NavWriteContext> by navigator {
            override fun NavContext.inputStream(name: String): InputStream =
                interactor.getDataCell(currentPath.addFile(name)).getInputStream()

            override suspend fun NavContext.validate(): Boolean =
                getFlowOfFiles(recursive = true).fold(true) { acc, loader ->
                    loader.use {
                        acc && interactor.getDataCell(loader.path).getInputStream().use { input ->
                            input.computeMD5().contentEquals(loader.load().md5)
                        }
                    }
                }
        }

    override suspend fun createWriteContext(): FileSystemWriteContext {
        val readContext = createReadContext()
        return object : FileSystemWriteContext, FileSystemReadContext by readContext {

            override fun NavWriteContext.createFolder(name: String) {
                currentFolder.apply {
                    val path = currentPath.addFolder(name)
                    if (folders.firstOrNull { it.name == name } != null) {
                        throw DirectoryAlreadyExists(path.pathString)
                    }
                    interactor.createFolder(path)
                }
                reload()
            }

            override fun NavWriteContext.createFile(
                name: String,
                data: ByteArray,
                md5: ByteArray,
            ) {
                currentFolder.apply {
                    val path = currentPath.addFile(name)
                    if (files.firstOrNull { it.name == name } != null) {
                        throw FileAlreadyExists(path.pathString)
                    }
                    interactor.createFile(path)

                    val dataCell = interactor.getMutableDataCell(path)

                    dataCell.clearData()
                    dataCell.getOutputStream(0).use {
                        it.write(data)
                    }

                    interactor.setMD5(path, md5)
                }
                reload()
            }

            private fun NavWriteContext.deleteFolderImpl(loader: NodeLoader<FolderNode>) {
                val folder = loader.load()

                folder.files.forEach {
                    interactor.deleteFile(it.path)
                }

                folder.folders.forEach {
                    deleteFolderImpl(it)
                }

                interactor.deleteFolder(loader.path)

                loader.close()
            }

            override fun NavWriteContext.deleteFolder(name: String) {
                currentFolder.apply {
                    val path = currentPath.pathString + name
                    val loader = folders.firstOrNull { it.name == name }
                        ?: throw DirectoryNotFound(path)
                    deleteFolderImpl(loader)
                }
                reload()
            }

            override fun NavWriteContext.deleteFile(name: String) {
                currentFolder.apply {
                    val path = currentPath.addFile(name)
                    files.firstOrNull { it.name == name }
                        ?: throw FileNotFound(path.pathString)
                    interactor.deleteFile(path)
                }
                reload()
            }

            private fun NavWriteContext.getDestFolderAndNewName(
                destinationPath: String,
                lastIsFile: Boolean,
            ): DestFinderResult {
                var path = FSPath(destinationPath)
                val lastIsDestFolder = destinationPath.endsWith('/')

                val last = if (lastIsDestFolder) {
                    null
                } else {
                    val name = path.name
                    path = path.removeLast()
                    name
                }

                val destinationLoader = if (destinationPath.startsWith('/')) {
                    interactor.getFolderLoader(path)
                } else {
                    path.pathList.fold(currentLoader) { acc, name ->
                        acc.use {
                            acc.load().folders.firstOrNull {
                                it.name == name
                            } ?: throw DirectoryNotFound(acc.path.pathString + name)
                        }
                    }
                }

                val (destLoader, fileName) = last?.let { lastName ->
                    if (lastIsFile) {
                        destinationLoader.load().files.firstOrNull {
                            it.name == lastName
                        }
                    } else {
                        destinationLoader.load().folders.firstOrNull {
                            it.name == lastName
                        }
                    } to lastName
                } ?: (null to null)

                return DestFinderResult(destinationLoader, destLoader, fileName)
            }

            private fun NavWriteContext.transferFileImpl(
                name: String, destinationPath: String, override: Boolean, isMove: Boolean,
            ) {
                val srcFile = getFile(name)

                val (destinationFolder, destFile, newFileName) =
                    getDestFolderAndNewName(destinationPath, lastIsFile = true)

                if (destFile != null) {
                    if (!override) {
                        throw FileAlreadyExists(destinationPath)
                    }
                    interactor.deleteFile(destFile.path)
                }

                val resultPath = destinationFolder.path.addFile(newFileName ?: srcFile.name)

                if (isMove) {
                    interactor.moveFile(srcFile.path, resultPath)
                } else {
                    val copyImporter = CopyImporter()
                    copyImporter.importFile(interactor, destinationFolder, srcFile, newFileName)
                }
                srcFile.close()

                reload()
            }

            private fun NavWriteContext.transferFolderImpl(
                name: String, destinationPath: String, override: Boolean, isMove: Boolean,
            ) {
                val srcFolder = getFolder(name)

                val (destinationFolder, destFolder, newFolderName) =
                    getDestFolderAndNewName(destinationPath, lastIsFile = false)

                if (destFolder != null) {
                    if (!override) {
                        throw DirectoryAlreadyExists(destinationPath)
                    }
                    interactor.deleteFolder(destFolder.path)
                }

                val resultPath = destinationFolder.path.addFolder(newFolderName ?: srcFolder.name)

                if (isMove) {
                    interactor.moveFolder(srcFolder.path, resultPath)
                } else {
                    val copyImporter = CopyImporter()
                    copyImporter.importFolder(interactor, destinationFolder, srcFolder, newFolderName)
                }
                srcFolder.close()

                reload()
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

            override fun NavWriteContext.outputStream(name: String, offset: Long): OutputStream =
                interactor.getMutableDataCell(currentPath.addFile(name)).getOutputStream(offset)

            override fun NavWriteContext.updateMD5(name: String) {
                val md5 = inputStream(name).use {
                    it.computeMD5()
                }
                interactor.setMD5(currentPath.addFile(name), md5)
                reload()
            }

            override fun NavWriteContext.clearFile(name: String) {
                interactor.getMutableDataCell(currentPath.addFile(name)).use {
                    it.clearData()
                }
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
                val (destinationFolder, fileLoader, fileName) =
                    getDestFolderAndNewName(destinationPath, lastIsFile = true)
                if (fileLoader != null) {
                    throw FileAlreadyExists(fileLoader.path.pathString)
                }
                try {
                    importer.importFile(interactor, destinationFolder, fileId, fileName)
                } catch (e: Throwable) {
                    throw OneFileSystemException("Failed while importing", e)
                }
                reload()
            }

            override fun <FolderId> NavWriteContext.importDirectory(
                destinationPath: String,
                importer: Importer<*, FolderId>,
                folderId: FolderId
            ) {
                val (destinationFolder, destLoader, dirName) =
                    getDestFolderAndNewName(destinationPath, lastIsFile = false)
                if (destLoader != null) {
                    throw DirectoryAlreadyExists(destLoader.path.pathString)
                }
                try {
                    importer.importFolder(interactor, destinationFolder, folderId, dirName)
                } catch (e: Throwable) {
                    throw OneFileSystemException("Failed while importing", e)
                }
                reload()
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
    suspend fun NavContext.findFiles(
        glob: String = "*",
        recursive: Boolean = true
    ): Flow<NodeLoader<FileNode>> {
        val globMatcher = if (glob == "*") {
            PathMatcher { true }
        } else {
            FileSystems.getDefault().getPathMatcher("glob:$glob")
        }
        return getFlowOfFiles(recursive).filter { loader ->
            globMatcher.matches(Path(loader.path.pathString))
        }
    }

    fun NavContext.inputStream(name: String): InputStream

    fun NavContext.readFile(name: String): ByteArray =
        inputStream(name).use { it.readBytes() }

    /**
     * Validate all files in current directory.
     */
    suspend fun NavContext.validate(): Boolean

}

interface FileSystemWriteContext : WriteContext, FileSystemReadContext {

    fun NavWriteContext.createFolder(name: String)

    fun NavWriteContext.createFile(
        name: String,
        data: ByteArray = ByteArray(0),
        md5: ByteArray = data.computeMD5()
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
    val folderLoader: NodeLoader<FolderNode>,
    val destNodeLoader: NodeLoader<FSNode>?,
    val resultName: String?,
)

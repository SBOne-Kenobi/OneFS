package fs

import capturing.AccessCapture
import capturing.ContextProvider
import capturing.ReadContext
import capturing.WriteContext
import com.google.protobuf.ByteString
import fs.interactor.InteractorInterface
import fs.proto.File
import fs.proto.Folder
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

            override suspend fun NavWriteContext.optimize() {
                interactor.overrideFileWith(rootFolder)
            }

            override fun NavWriteContext.createFolder(name: String) {
                currentFolder.apply {
                    if (folders.firstOrNull { it.folder.name == name } != null) {
                        throw DirectoryAlreadyExists(currentPath.path + name)
                    }
                    val folder = Folder.newBuilder().apply { this.name = name }
                    folders.add(MutableFolderNode(folder, parent = this))
                    interactor.createFolder(currentPath.path + name, folder)
                }
            }

            override fun NavWriteContext.createFile(name: String) {
                val file = File.newBuilder().apply {
                    val creationTime = System.currentTimeMillis()
                    this.name = name
                    this.data = ByteString.EMPTY
                    this.creationTimestamp = creationTime
                    this.modificationTimestamp = creationTime
                }.buildPartial()
                createFile(file)
            }

            override fun NavWriteContext.createFile(file: File) {
                val name = file.name
                currentFolder.apply {
                    if (files.firstOrNull { it.file.name == name } != null) {
                        throw FileAlreadyExists(currentPath.path + name)
                    }
                    val newFile = File.newBuilder(file).apply {
                        md5 = data.toByteArray().computeMD5()
                    }
                    files.add(MutableFileNode(newFile, parent = this))
                    interactor.createFile(currentPath.path + name, file)
                }
            }

            private fun NavWriteContext.deleteFolderImpl(name: String) {
                currentFolder.apply {
                    val success = folders.removeIf { it.folder.name == name }
                    if (!success) {
                        throw DirectoryNotFound(currentPath.path + name)
                    }
                }
            }

            override fun NavWriteContext.deleteFolder(name: String) {
                deleteFolderImpl(name)
                interactor.deleteFolder(currentPath.path + name)
            }

            private fun NavWriteContext.deleteFileImpl(name: String) {
                currentFolder.apply {
                    val success = files.removeIf { it.file.name == name }
                    if (!success) {
                        throw FileNotFound(currentPath.path + name)
                    }
                }
            }

            override fun NavWriteContext.deleteFile(name: String) {
                deleteFileImpl(name)
                interactor.deleteFile(currentPath.path + name)
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
                        it.folder.name == nextName
                    } ?: throw DirectoryNotFound(destinationPath)
                    resultPath.add(destinationFolder)
                }


                val (destFileIndexOrNull, fileName) = last?.let { lastName ->
                    if (lastIsFile) {
                        destinationFolder.files.indexOfFirst {
                            it.file.name == lastName
                        }.takeIf { it != -1 }
                    } else {
                        destinationFolder.folders.indexOfFirst {
                            it.folder.name == lastName
                        }.takeIf { it != -1 }
                    } to lastName
                } ?: (null to null)

                return DestFinderResult(destinationFolder, destFileIndexOrNull, fileName, resultPath)
            }

            private fun NavWriteContext.transferFileImpl(
                name: String, destinationPath: String, override: Boolean, isMove: Boolean,
            ): String {
                val srcFile = getFile(name)

                val (destinationFolder, destFileIndexOrNull, newFileName, destFSPath) =
                    getDestFolderAndNewName(destinationPath, lastIsFile = true)

                if (destFileIndexOrNull != null) {
                    if (!override) {
                        throw FileAlreadyExists(destinationPath)
                    }
                    destinationFolder.files.removeAt(destFileIndexOrNull)
                }
                val dstFile = srcFile.copyFile(destinationFolder)
                if (isMove) {
                    deleteFileImpl(name)
                }
                newFileName?.let {
                    dstFile.file.name = it
                }
                destinationFolder.files.add(dstFile)
                return destFSPath.path + dstFile.file.name
            }

            private fun NavWriteContext.transferFolderImpl(
                name: String, destinationPath: String, override: Boolean, isMove: Boolean,
            ): String {
                val srcFolder = getFolder(name)

                val (destinationFolder, destFolderIndexOrNull, newFolderName, destFSPath) =
                    getDestFolderAndNewName(destinationPath, lastIsFile = false)

                if (destFolderIndexOrNull != null) {
                    if (!override) {
                        throw DirectoryAlreadyExists(destinationPath)
                    }
                    destinationFolder.folders.removeAt(destFolderIndexOrNull)
                }
                val dstFolder = srcFolder.copyFolder(destinationFolder)
                if (isMove) {
                    deleteFolderImpl(name)
                }
                newFolderName?.let {
                    dstFolder.folder.name = it
                }
                destinationFolder.folders.add(dstFolder)
                return destFSPath.path + dstFolder.folder.name
            }

            override fun NavWriteContext.moveFile(name: String, destinationPath: String, override: Boolean) {
                val resultDestPath = transferFileImpl(name, destinationPath, override, isMove = true)
                interactor.moveFile(currentPath.path + name, resultDestPath)
            }

            override fun NavWriteContext.moveFolder(name: String, destinationPath: String, override: Boolean) {
                val resultDestPath = transferFolderImpl(name, destinationPath, override, isMove = true)
                interactor.moveFolder(currentPath.path + name, resultDestPath)
            }

            override fun NavWriteContext.copyFile(name: String, destinationPath: String, override: Boolean) {
                val resultDestPath = transferFileImpl(name, destinationPath, override, isMove = false)
                interactor.copyFile(currentPath.path + name, resultDestPath)
            }

            override fun NavWriteContext.copyFolder(name: String, destinationPath: String, override: Boolean) {
                val resultDestPath = transferFolderImpl(name, destinationPath, override, isMove = false)
                interactor.copyFolder(currentPath.path + name, resultDestPath)
            }

            override fun NavWriteContext.writeIntoFile(name: String, data: ByteArray, begin: Int, end: Int) {
                if ((begin == -1 && end != -1) || (begin != -1 && end != -1 && begin > end)) {
                    throw OneFileSystemException("Incorrect range [$begin, $end)")
                }
                val fileNode = getFile(name)
                val timestamp = System.currentTimeMillis()
                fileNode.file.apply {
                    val oldData = this.data.toByteArray()
                    val indices = oldData.indices.toMutableList()
                    val (prefix, suffix) = if (begin != -1) {
                        if (begin < end || end == -1) {
                            indices.removeIf { it >= begin && (end == -1 || it < end) }
                        }
                        indices.partition { it < begin }
                    } else {
                        indices to emptyList()
                    }
                    val newData = oldData.sliceArray(prefix) + data + oldData.sliceArray(suffix)
                    this.data = ByteString.copyFrom(newData)
                    this.md5 = newData.computeMD5()
                    this.modificationTimestamp = timestamp
                }
                interactor.modifyFile(currentPath.path + name, data, begin, end, timestamp)
            }

            override fun NavWriteContext.appendIntoFile(name: String, data: ByteArray) {
                writeIntoFile(name, data, -1, -1)
            }

            private fun registerImportedFile(
                destPath: MutableFSPath,
                fileNode: FileNodeInterface
            ) {
                interactor.createFile(destPath.path + fileNode.file.name, fileNode.file)
            }

            private fun registerImportedFolder(
                destPath: MutableFSPath,
                folderNode: FolderNodeInterface
            ) {
                interactor.createFolder(destPath.path + folderNode.folder.name, folderNode.folder)
                destPath.add(folderNode)
                folderNode.files.forEach {
                    registerImportedFile(destPath, it)
                }
                folderNode.folders.forEach {
                    registerImportedFolder(destPath, it)
                }
                destPath.removeLast()
            }

            /**
             * Create importing file using [file] in [destinationPath].
             */
            override fun NavWriteContext.importFile(destinationPath: String, file: () -> MutableFileNode) {
                val (destinationFolder, fileIndex, fileName, destPath) =
                    getDestFolderAndNewName(destinationPath, lastIsFile = true)
                if (fileIndex != null) {
                    throw FileAlreadyExists(destinationPath)
                }
                val targetFile = try {
                    file()
                } catch (e: Throwable) {
                    throw OneFileSystemException("Failed while importing", e)
                }
                fileName?.let {
                    targetFile.file.name = it
                }
                targetFile.parent = destinationFolder
                destinationFolder.files.add(targetFile)
                registerImportedFile(destPath, targetFile)
            }

            /**
             * Create importing directory using [dir] in [destinationPath].
             */
            override fun NavWriteContext.importDirectory(destinationPath: String, dir: () -> MutableFolderNode) {
                val (destinationFolder, dirIndex, dirName, destPath) =
                    getDestFolderAndNewName(destinationPath, lastIsFile = false)
                if (dirIndex != null) {
                    throw DirectoryAlreadyExists(destinationPath)
                }
                val targetDir = try {
                    dir()
                } catch (e: Throwable) {
                    throw OneFileSystemException("Failed while importing", e)
                }
                dirName?.let {
                    targetDir.folder.name = it
                }
                targetDir.parent = destinationFolder
                destinationFolder.folders.add(targetDir)
                registerImportedFolder(destPath, targetDir)
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

    fun NavContextGeneric.readFile(name: String): ByteArray =
        getFile(name).file.data.toByteArray()

    /**
     * Validate all files in current directory.
     */
    suspend fun NavContextGeneric.validate(): Boolean {
        return getFlowOfFiles(recursive = true).fold(true) { acc, (fileNode, _) ->
            acc && fileNode.file.run {
                md5 == data.toByteArray().computeMD5()
            }
        }
    }

}

interface FileSystemWriteContext : WriteContext, FileSystemReadContext {

    /**
     * Optimize file size of OneFS.
     */
    suspend fun NavWriteContext.optimize()

    fun NavWriteContext.createFolder(name: String)

    fun NavWriteContext.createFile(name: String)

    fun NavWriteContext.createFile(file: File)

    fun NavWriteContext.deleteFolder(name: String)

    fun NavWriteContext.deleteFile(name: String)

    fun NavWriteContext.moveFile(name: String, destinationPath: String, override: Boolean = false)

    fun NavWriteContext.moveFolder(name: String, destinationPath: String, override: Boolean = false)

    fun NavWriteContext.copyFile(name: String, destinationPath: String, override: Boolean = false)

    fun NavWriteContext.copyFolder(name: String, destinationPath: String, override: Boolean = false)

    /**
     * Rewrite content of file [name] placed from [begin] (include) to [end] (exclude) with [data].
     *
     * If [end] equals -1 it means to end of file.
     */
    fun NavWriteContext.writeIntoFile(name: String, data: ByteArray, begin: Int = 0, end: Int = -1)

    fun NavWriteContext.appendIntoFile(name: String, data: ByteArray)

    /**
     * Create importing file using [file] in [destinationPath].
     */
    fun NavWriteContext.importFile(destinationPath: String, file: () -> MutableFileNode)

    /**
     * Create importing directory using [dir] in [destinationPath].
     */
    fun NavWriteContext.importDirectory(destinationPath: String, dir: () -> MutableFolderNode)

}

private data class DestFinderResult(
    val folderNode: MutableFolderNode,
    val indexOfDestNode: Int?,
    val resultName: String?,
    val path: MutableFSPath,
)

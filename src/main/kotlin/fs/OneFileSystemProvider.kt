package fs

import capturing.AccessCapture
import capturing.ContextProvider
import capturing.ReadContext
import capturing.WriteContext
import com.google.protobuf.ByteString
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.StandardOpenOption
import kotlin.io.path.Path
import kotlin.io.path.notExists
import kotlin.io.path.outputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/**
 * Provider for using filesystem represented in one file.
 */
class OneFileSystemProvider(
    val fileSystemPath: Path,
) : ContextProvider<FileSystemReadContext, FileSystemWriteContext> {

    init {
        if (fileSystemPath.notExists()) {
            emptyFS().writeTo(fileSystemPath.outputStream(StandardOpenOption.CREATE))
        }
    }

    private fun emptyFS(): FileSystem = FileSystem.newBuilder().apply {
        rootBuilder.apply {
            name = ""
        }
    }.build()

    private val navigator = FileSystemNavigator(fileSystemPath.toFileSystem())

    override suspend fun createReadContext(): FileSystemReadContext =
        object : FileSystemReadContext, AccessCapture<NavReadContext, NavWriteContext> by navigator {}

    override suspend fun createWriteContext(): FileSystemWriteContext {
        val readContext = createReadContext()
        return object : FileSystemWriteContext, FileSystemReadContext by readContext {
            override fun NavWriteContext.commit() {
                fileSystemBuilder.build().writeTo(fileSystemPath.outputStream())
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
    suspend fun NavReadContext.findFiles(glob: String = "*", recursive: Boolean = true): Flow<FileWithAbsolutePath> =
        channelFlow {
            val globMatcher = if (glob == "*") {
                PathMatcher { true }
            } else {
                FileSystems.getDefault().getPathMatcher("glob:$glob")
            }
            applyFunc(recursive) { (folder, path) ->
                launch {
                    folder.filesList.forEach {
                        val absolutePath = path + it.name
                        if (globMatcher.matches(Path(absolutePath))) {
                            send(FileWithAbsolutePath(it, absolutePath))
                        }
                    }
                }
                true
            }
        }

    fun NavReadContext.readFile(name: String): ByteArray =
        getFile(name).data.toByteArray()

    fun NavReadContext.fileInputStream(name: String): InputStream =
        ByteArrayInputStream(readFile(name))

    /**
     * Validate all files in current directory.
     */
    suspend fun NavReadContext.validate(): Boolean {
        return applyFunc(recursive = true) { (folder, _) ->
            folder.filesList.forEach {
                if (it.computeMD5() != it.md5) {
                    return@applyFunc false
                }
            }
            true
        }
    }

}

interface FileSystemWriteContext : WriteContext, FileSystemReadContext {

    /**
     * Save changes.
     */
    fun NavWriteContext.commit()

    fun NavWriteContext.createFolder(name: String) {
        currentFolderBuilder.apply {
            if (foldersBuilderList.firstOrNull { it.name == name } != null) {
                throw DirectoryAlreadyExists(currentPathString + name)
            }
            addFoldersBuilder().apply {
                this.name = name
            }
        }
    }

    fun NavWriteContext.createFile(name: String) {
        currentFolderBuilder.apply {
            if (filesBuilderList.firstOrNull { it.name == name } != null) {
                throw FileAlreadyExists(currentPathString + name)
            }
            addFilesBuilder().apply {
                val creationTime = System.currentTimeMillis()
                this.name = name
                this.data = ByteString.empty()
                this.md5 = computeMD5()
                this.creationTimestamp = creationTime
                this.modificationTimestamp = creationTime
            }
        }
    }

    fun NavWriteContext.deleteFolder(name: String) {
        currentFolderBuilder.apply {
            val index = foldersBuilderList.indexOfFirst { it.name == name }
            if (index == -1) {
                throw DirectoryNotFound(currentPathString + name)
            }
            removeFolders(index)
        }
    }

    fun NavWriteContext.deleteFile(name: String) {
        currentFolderBuilder.apply {
            val index = filesBuilderList.indexOfFirst { it.name == name }
            if (index == -1) {
                throw FileNotFound(currentPathString + name)
            }
            removeFiles(index)
        }
    }

    private fun NavWriteContext.getDestFolderAndNewName(
        destinationPath: String,
        lastIsFile: Boolean,
    ): Triple<Folder.Builder, Int?, String?> {
        val path = destinationPath.toPathSequence().toMutableList()
        val lastIsFolder = destinationPath.endsWith('/')
        var destinationFolder = if (destinationPath.startsWith('/')) {
            path.removeFirst()
            fileSystemBuilder.rootBuilder
        } else {
            if (destinationPath.startsWith('.')) {
                path.removeFirst()
            }
            currentFolderBuilder
        }

        val last = if (lastIsFolder) {
            null
        } else {
            path.removeLastOrNull()
        }

        path.forEach { nextName ->
            destinationFolder = destinationFolder.foldersBuilderList.firstOrNull {
                it.name == nextName
            } ?: throw DirectoryNotFound(destinationPath)
        }

        val (destFileIndexOrNull, fileName) = last?.let { lastName ->
            if (lastIsFile) {
                destinationFolder.filesBuilderList.indexOfFirst {
                    it.name == lastName
                }.takeIf { it != -1 }
            } else {
                destinationFolder.foldersBuilderList.indexOfFirst {
                    it.name == lastName
                }.takeIf { it != -1 }
            } to lastName
        } ?: run {
            null to null
        }

        return Triple(destinationFolder, destFileIndexOrNull, fileName)
    }

    private fun NavWriteContext.moveFileImpl(
        name: String, destinationPath: String, override: Boolean, withDeletion: Boolean,
    ) {
        val targetFile = getFile(name).toBuilder()

        val (destinationFolder, destFileIndexOrNull, newFileName) =
            getDestFolderAndNewName(destinationPath, lastIsFile = true)

        if (destFileIndexOrNull != null) {
            if (!override) {
                throw FileAlreadyExists(destinationPath)
            }
            destinationFolder.removeFiles(destFileIndexOrNull)
        }
        if (withDeletion) {
            deleteFile(name)
        }
        newFileName?.let {
            targetFile.name = it
        }
        destinationFolder.addFiles(targetFile)
    }

    private fun NavWriteContext.moveFolderImpl(
        name: String, destinationPath: String, override: Boolean, withDeletion: Boolean,
    ) {
        val targetFolder = getFolder(name).toBuilder()

        val (destinationFolder, destFolderIndexOrNull, newFolderName) =
            getDestFolderAndNewName(destinationPath, lastIsFile = false)

        if (destFolderIndexOrNull != null) {
            if (!override) {
                throw DirectoryAlreadyExists(destinationPath)
            }
            destinationFolder.removeFolders(destFolderIndexOrNull)
        }
        if (withDeletion) {
            deleteFolder(name)
        }
        newFolderName?.let {
            targetFolder.name = it
        }
        destinationFolder.addFolders(targetFolder)
    }

    fun NavWriteContext.moveFile(name: String, destinationPath: String, override: Boolean = false) {
        moveFileImpl(name, destinationPath, override, withDeletion = true)
    }

    fun NavWriteContext.moveFolder(name: String, destinationPath: String, override: Boolean = false) {
        moveFolderImpl(name, destinationPath, override, withDeletion = true)
    }

    fun NavWriteContext.copyFile(name: String, destinationPath: String, override: Boolean = false) {
        moveFileImpl(name, destinationPath, override, withDeletion = false)
    }

    fun NavWriteContext.copyFolder(name: String, destinationPath: String, override: Boolean = false) {
        moveFolderImpl(name, destinationPath, override, withDeletion = false)
    }

    fun NavWriteContext.writeIntoFile(name: String, data: ByteArray) {
        val fileBuilder = currentFolderBuilder.filesBuilderList.firstOrNull {
            it.name == name
        } ?: throw FileNotFound(currentPathString + name)
        fileBuilder.apply {
            this.data = ByteString.copyFrom(data)
            this.md5 = computeMD5()
            this.modificationTimestamp = System.currentTimeMillis()
        }
    }

    fun NavWriteContext.appendIntoFile(name: String, data: ByteArray) {
        val fileBuilder = currentFolderBuilder.filesBuilderList.firstOrNull {
            it.name == name
        } ?: throw FileNotFound(currentPathString + name)
        fileBuilder.apply {
            this.data = this.data.concat(ByteString.copyFrom(data))
            this.md5 = computeMD5()
            this.modificationTimestamp = System.currentTimeMillis()
        }
    }

    fun NavWriteContext.fileOutputStream(name: String, clear: Boolean = true): BufferedOutputStream {
        val fileBuilder = currentFolderBuilder.filesBuilderList.firstOrNull {
            it.name == name
        } ?: throw FileNotFound(currentPathString + name)

        if (clear) {
            fileBuilder.apply {
                this.data = ByteString.EMPTY
                this.md5 = computeMD5()
                this.modificationTimestamp = System.currentTimeMillis()
            }
        }

        val outputStream = ByteArrayOutputStream()
        return object : BufferedOutputStream(outputStream) {
            override fun flush() {
                super.flush()
                fileBuilder.apply {
                    this.data = this.data.concat(ByteString.copyFrom(outputStream.toByteArray()))
                    this.md5 = computeMD5()
                    this.modificationTimestamp = System.currentTimeMillis()
                }
                outputStream.reset()
            }
        }
    }

    /**
     * Create importing file using [file] in [destinationPath].
     */
    fun NavWriteContext.importFile(destinationPath: String, file: () -> File) {
        val (destinationFolder, fileIndex, fileName) = getDestFolderAndNewName(destinationPath, lastIsFile = true)
        if (fileIndex != null) {
            throw FileAlreadyExists(destinationPath)
        }
        val targetFile = try {
            file().toBuilder()
        } catch (e: Throwable) {
            throw OneFileSystemException("Failed while importing", e)
        }
        fileName?.let {
            targetFile.name = it
        }
        destinationFolder.addFiles(targetFile)
    }

    /**
     * Create importing directory using [dir] in [destinationPath].
     */
    fun NavWriteContext.importDirectory(destinationPath: String, dir: () -> Folder) {
        val (destinationFolder, dirIndex, dirName) = getDestFolderAndNewName(destinationPath, lastIsFile = false)
        if (dirIndex != null) {
            throw DirectoryAlreadyExists(destinationPath)
        }
        val targetDir = try {
            dir().toBuilder()
        } catch (e: Throwable) {
            throw OneFileSystemException("Failed while importing", e)
        }
        dirName?.let {
            targetDir.name = it
        }
        destinationFolder.addFolders(targetDir)
    }

}

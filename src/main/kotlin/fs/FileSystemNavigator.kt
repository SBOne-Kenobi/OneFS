package fs

import capturing.AccessCapture
import capturing.ContextProvider
import capturing.ReadContext
import capturing.WriteContext
import capturing.impl.ReadPriorityCapture
import kotlinx.coroutines.coroutineScope

/**
 * Helper class for navigation in [FileSystem].
 */
class FileSystemNavigator(fileSystem: FileSystem) :
    ContextProvider<NavReadContext, NavWriteContext>, WithNavigator
{
    private val capture = ReadPriorityCapture(this)

    private var _fileSystemBuilder: FileSystem.Builder = fileSystem.toBuilder()
    private var _currentFolderBuilder: Folder.Builder = _fileSystemBuilder.rootBuilder
    private var _currentPath = mutableListOf(_currentFolderBuilder)

    override suspend fun createReadContext(): NavReadContext =
        object : NavReadContext {
            override val currentFolder: Folder
                get() = _currentFolderBuilder.build()
            override val currentPathString
                get() = _currentPath.path
        }

    override suspend fun createWriteContext(): NavWriteContext {
        val readContext = createReadContext()
        return object : NavWriteContext, NavReadContext by readContext {
            override val fileSystemBuilder by ::_fileSystemBuilder
            override val currentFolderBuilder by ::_currentFolderBuilder

            override infix fun cd(targetPath: String) {
                var newFolderBuilder: Folder.Builder
                val newPath: MutableList<Folder.Builder>

                var seq = targetPath.toPathSequence()
                if (targetPath.startsWith('/')) {
                    newFolderBuilder = fileSystemBuilder.rootBuilder
                    newPath = mutableListOf(newFolderBuilder)
                    seq = seq.drop(1)
                } else {
                    if (targetPath.startsWith('.')) {
                        seq = seq.drop(1)
                    }
                    newFolderBuilder = _currentFolderBuilder
                    newPath = _currentPath.toMutableList()
                }

                seq.forEach { nextName ->
                    newFolderBuilder = newFolderBuilder.foldersBuilderList.firstOrNull {
                        it.name == nextName
                    } ?: throw DirectoryNotFound(newPath.path + nextName)
                    newPath.add(newFolderBuilder)
                }
                _currentFolderBuilder = newFolderBuilder
                _currentPath = newPath
            }

            override fun back() {
                if (_currentPath.size > 1) {
                    _currentPath.removeLast()
                    _currentFolderBuilder = _currentPath.last()
                }
            }
        }
    }

    override suspend fun captureWrite(block: suspend NavWriteContext.() -> Unit) {
        capture.captureWrite(block)
    }

    override suspend fun <T> captureRead(block: suspend NavReadContext.() -> T): T {
        return capture.captureRead(block)
    }

    override suspend fun tryCaptureWrite(block: suspend NavWriteContext.() -> Unit) {
        capture.tryCaptureWrite(block)
    }

    override suspend fun <T> tryCaptureRead(block: suspend NavReadContext.() -> T): T {
        return capture.tryCaptureRead(block)
    }

}

interface NavReadContext : ReadContext {
    val currentFolder: Folder

    /**
     * Current absolute path string.
     */
    val currentPathString: String

    /**
     * Get folder with [name].
     */
    fun getFolder(name: String): Folder =
        currentFolder.foldersList.firstOrNull { it.name == name }
            ?: throw DirectoryNotFound(currentPathString + name)

    /**
     * Get file with [name].
     */
    fun getFile(name: String): File =
        currentFolder.filesList.firstOrNull { it.name == name }
            ?: throw FileNotFound(currentPathString + name)

    /**
     * Apply function [block] recursive. When [block] returns false, recursive calls will be stopped.
     *
     * @return true if all invocations returned true
     */
    suspend fun applyFunc(recursive: Boolean, block: suspend (FolderWithAbsolutePath) -> Boolean): Boolean = coroutineScope {
        if (!recursive) {
            return@coroutineScope block(FolderWithAbsolutePath(currentFolder, currentPathString))
        }
        val stack = ArrayList<Pair<FolderWithAbsolutePath, Int>>()
        stack.add(FolderWithAbsolutePath(currentFolder, currentPathString) to 0)
        while (stack.isNotEmpty()) {
            val (folderWithPath, index) = stack.removeLast()
            if (index < folderWithPath.folder.foldersCount) {
                stack.add(folderWithPath to index + 1)
                val nextFolder = folderWithPath.folder.getFolders(index)
                val nextFolderWithPath = FolderWithAbsolutePath(
                    nextFolder,
                    folderWithPath.absolutePath + nextFolder.name + "/"
                )
                stack.add(nextFolderWithPath to 0)
            } else {
                if (!block(folderWithPath)) {
                    return@coroutineScope false
                }
            }
        }
        true
    }
}

interface NavWriteContext : WriteContext, NavReadContext {
    /**
     * Mutable [FileSystem].
     */
    val fileSystemBuilder: FileSystem.Builder

    /**
     * Mutable [Folder].
     */
    val currentFolderBuilder: Folder.Builder

    /**
     * Go to path [targetPath].
     */
    infix fun cd(targetPath: String)

    /**
     * Go to the parent directory.
     */
    fun back()
}

/**
 * Wraps capturing with clear names for navigation.
 */
interface WithNavigator: AccessCapture<NavReadContext, NavWriteContext> {
    /**
     * Alias for [captureWrite].
     */
    suspend fun withMutableFolder(block: suspend NavWriteContext.() -> Unit) = captureWrite(block)

    /**
     * Alias for [captureRead]
     */
    suspend fun withFolder(block: suspend NavReadContext.() -> Unit) = captureRead(block)
}

data class FolderWithAbsolutePath(
    val folder: Folder,
    val absolutePath: String
)

data class FileWithAbsolutePath(
    val file: File,
    val absolutePath: String
)

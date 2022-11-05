package fs

import capturing.AccessCapture
import capturing.ContextProvider
import capturing.ReadContext
import capturing.WriteContext
import capturing.impl.ReadPriorityCapture
import fs.entity.DirectoryNotFound
import fs.entity.FSPath
import fs.entity.FileNode
import fs.entity.FileNotFound
import fs.entity.FolderNode
import fs.entity.NodeLoader
import fs.interactor.InteractorInterface

/**
 * Helper class for navigation in file system.
 */
class FileSystemNavigator(private val interactor: InteractorInterface) :
    ContextProvider<NavReadContext, NavWriteContext>, WithNavigator, AutoCloseable {
    private val capture = ReadPriorityCapture(this)

    private var _currentLoader: NodeLoader<FolderNode> = interactor.getFolderLoader(FSPath("/"))
        set(value) {
            field.close()
            _currentPath = value.path
            _currentFolderNode = value.load()
            field = value
        }
    private var _currentFolderNode: FolderNode
    private var _currentPath: FSPath

    init {
        _currentPath = _currentLoader.path
        _currentFolderNode = _currentLoader.load()
    }

    override suspend fun createReadContext(): NavReadContext =
        object : NavReadContext {
            override val currentLoader: NodeLoader<FolderNode> by ::_currentLoader
            override val currentFolder: FolderNode by ::_currentFolderNode
            override val currentPath: FSPath by ::_currentPath
        }

    override suspend fun createWriteContext(): NavWriteContext {
        return object : NavWriteContext {
            override var currentLoader: NodeLoader<FolderNode> by ::_currentLoader
            override val currentFolder: FolderNode by ::_currentFolderNode
            override val currentPath: FSPath by ::_currentPath

            override infix fun cd(targetPath: String) {
                if (targetPath.startsWith('/')) {
                    val path = if (targetPath.endsWith('/')) {
                        targetPath
                    } else {
                        "$targetPath/"
                    }
                    currentLoader = interactor.getFolderLoader(FSPath(path))
                } else {
                    val path = FSPath(targetPath)

                    path.pathList.forEach { name ->
                        currentLoader = getFolder(name)
                    }
                }
            }

            override fun back() {
                currentFolder.parent?.let {
                    currentLoader = it
                }
            }

            override fun reload() {
                _currentLoader = _currentLoader
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

    override fun close() {
        _currentLoader.close()
    }

}

interface NavContext {
    val currentLoader: NodeLoader<FolderNode>
    val currentFolder: FolderNode
    val currentPath: FSPath

    /**
     * Get folder with [name].
     */
    fun getFolder(name: String): NodeLoader<FolderNode> = currentFolder.folders
        .firstOrNull { it.name == name }
        ?: throw DirectoryNotFound(currentPath.pathString + name)

    /**
     * Get file with [name].
     */
    fun getFile(name: String): NodeLoader<FileNode> = currentFolder.files
        .firstOrNull { it.name == name }
        ?: throw FileNotFound(currentPath.pathString + name)
}

interface NavReadContext : ReadContext, NavContext

interface NavWriteContext : WriteContext, NavContext {
    /**
     * Go to path [targetPath].
     */
    infix fun cd(targetPath: String)

    /**
     * Go to the parent directory.
     */
    fun back()

    /**
     * Load changes from file system.
     */
    fun reload()
}

/**
 * Wraps capturing with clear names for navigation.
 */
interface WithNavigator : AccessCapture<NavReadContext, NavWriteContext> {
    /**
     * Alias for [captureWrite].
     */
    suspend fun withMutableFolder(block: suspend NavWriteContext.() -> Unit) = captureWrite(block)

    /**
     * Alias for [captureRead]
     */
    suspend fun withFolder(block: suspend NavReadContext.() -> Unit) = captureRead(block)
}

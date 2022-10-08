package fs

import capturing.AccessCapture
import capturing.ContextProvider
import capturing.ReadContext
import capturing.WriteContext
import capturing.impl.ReadPriorityCapture
import fs.entity.DirectoryNotFound
import fs.entity.FSPath
import fs.entity.FSPathInterface
import fs.entity.FileNode
import fs.entity.FileNodeInterface
import fs.entity.FileNotFound
import fs.entity.FolderNode
import fs.entity.FolderNodeInterface
import fs.entity.MutableFSPath
import fs.entity.MutableFileNode
import fs.entity.MutableFolderNode
import fs.entity.add
import fs.entity.immutable
import fs.entity.immutableFolder
import fs.entity.mutable
import fs.entity.path
import fs.entity.removeLast

/**
 * Helper class for navigation in file system.
 */
class FileSystemNavigator(val _rootNode: MutableFolderNode) :
    ContextProvider<NavReadContext, NavWriteContext>, WithNavigator
{
    private val capture = ReadPriorityCapture(this)

    private var _currentFolderNode: MutableFolderNode = _rootNode
    private var _currentPath = _currentFolderNode.path.mutable()

    override suspend fun createReadContext(): NavReadContext =
        object : NavReadContext {
            override val rootFolder: FolderNode by lazy {
                _rootNode.immutableFolder()
            }
            override val currentFolder: FolderNode by lazy {
                _currentFolderNode.immutableFolder()
            }
            override val currentPath: FSPathInterface by lazy {
                _currentPath.immutable()
            }
        }

    override suspend fun createWriteContext(): NavWriteContext {
        return object : NavWriteContext {
            override val rootFolder: MutableFolderNode by ::_rootNode
            override val currentFolder: MutableFolderNode by ::_currentFolderNode
            override val currentPath: FSPathInterface by ::_currentPath

            override infix fun cd(targetPath: String) {
                var newFolderNode: MutableFolderNode
                val newPath: MutableFSPath

                val targetPathOptimized = targetPath.removePrefix(currentPath.path)
                if (targetPathOptimized.isBlank()) {
                    return
                }

                val target = FSPath(targetPathOptimized)
                if (targetPathOptimized.startsWith('/')) {
                    newFolderNode = _rootNode
                    newPath = MutableFSPath()
                } else {
                    newFolderNode = _currentFolderNode
                    newPath = _currentPath
                }

                target.pathList.forEach { nextName ->
                    newFolderNode = newFolderNode.folders
                        .firstOrNull { it.folderName == nextName }
                        ?: throw DirectoryNotFound(newPath.path + nextName)
                    newPath.add(newFolderNode)
                }
                _currentFolderNode = newFolderNode
                _currentPath = newPath
            }

            override fun back() {
                _currentFolderNode.parent?.let {
                    _currentFolderNode = it
                    _currentPath.removeLast()
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

typealias NavContextGeneric = NavContext<FileNodeInterface, FolderNodeInterface>

interface NavContext<out File: FileNodeInterface, out Folder: FolderNodeInterface> {
    val rootFolder: Folder
    val currentFolder: Folder
    val currentPath: FSPathInterface
}

/**
 * Get folder with [name].
 */
inline fun <File: FileNodeInterface, reified Folder: FolderNodeInterface> NavContext<File, Folder>.getFolder(
    name: String
): Folder = currentFolder.folders
    .firstOrNull { it.folderName == name } as Folder?
    ?: throw DirectoryNotFound(currentPath.path + name)

/**
 * Get file with [name].
 */
inline fun <reified File: FileNodeInterface, Folder: FolderNodeInterface> NavContext<File, Folder>.getFile(
    name: String
): File = currentFolder.files
    .firstOrNull { it.fileName == name } as File?
    ?: throw FileNotFound(currentPath.path + name)

interface NavReadContext : ReadContext, NavContext<FileNode, FolderNode> {
    override val rootFolder: FolderNode
    override val currentFolder: FolderNode
}

interface NavWriteContext : WriteContext, NavContext<MutableFileNode, MutableFolderNode> {
    override val rootFolder: MutableFolderNode
    override val currentFolder: MutableFolderNode

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

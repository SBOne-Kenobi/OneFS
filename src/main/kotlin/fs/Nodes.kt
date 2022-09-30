package fs

import fs.proto.File
import fs.proto.FileOrBuilder
import fs.proto.Folder
import fs.proto.FolderOrBuilder
import utils.LazyList

sealed interface FSNodeInterface {
    val parent: FolderNodeInterface?
}

sealed class FSNode(final override val parent: FolderNode?) : FSNodeInterface

sealed class MutableFSNode(final override var parent: MutableFolderNode?) : FSNodeInterface


sealed interface FileNodeInterface : FSNodeInterface {
    val file: FileOrBuilder
}

class MutableFileNode(
    override val file: File.Builder,
    parent: MutableFolderNode? = null
): MutableFSNode(parent), FileNodeInterface

class FileNode(
    override val file: File,
    parent: FolderNode? = null
): FSNode(parent), FileNodeInterface


sealed interface FolderNodeInterface : FSNodeInterface {
    val folder: FolderOrBuilder
    val files: List<FileNodeInterface>
    val folders: List<FolderNodeInterface>
}

class MutableFolderNode(
    override val folder: Folder.Builder,
    override val files: MutableList<MutableFileNode> = mutableListOf(),
    override val folders: MutableList<MutableFolderNode> = mutableListOf(),
    parent: MutableFolderNode? = null
): MutableFSNode(parent), FolderNodeInterface

class FolderNode(
    override val folder: Folder,
    override val files: List<FileNode> = emptyList(),
    override val folders: List<FolderNode> = emptyList(),
    parent: FolderNode? = null
): FSNode(parent), FolderNodeInterface


fun MutableFileNode.immutableFile(builtParent: FolderNode? = null): FileNode =
    FileNode(file.build(), builtParent)

fun MutableFolderNode.immutableFolder(builtParent: FolderNode? = null): FolderNode {
    val initsFolder = folders.toInits<FolderNode>()
    val initsFiles = files.toInits<FileNode>()
    val result = FolderNode(
        folder.build(),
        LazyList(initsFiles),
        LazyList(initsFolder),
        builtParent
    )
    repeat(initsFolder.size) { i -> initsFolder[i] = { folders[i].immutableFolder(result) } }
    repeat(initsFiles.size) { i -> initsFiles[i] = { files[i].immutableFile(result) } }
    return result
}

fun MutableFSNode.immutableNode(builtParent: FolderNode? = null): FSNode = when (this) {
    is MutableFileNode -> immutableFile(builtParent)
    is MutableFolderNode -> immutableFolder(builtParent)
}

fun MutableFileNode.copyFile(parent: MutableFolderNode? = null): MutableFileNode =
    MutableFileNode(File.newBuilder(file.build()), parent)

fun MutableFolderNode.copyFolder(parent: MutableFolderNode? = null): MutableFolderNode {
    val result = MutableFolderNode(
        Folder.newBuilder(folder.build()),
        parent = parent
    )
    result.files.addAll(files.map { it.copyFile(result) })
    result.folders.addAll(folders.map { it.copyFolder(result) })
    return result
}

fun MutableFSNode.copyNode(parent: MutableFolderNode? = null): MutableFSNode = when (this) {
    is MutableFileNode -> copyFile(parent)
    is MutableFolderNode -> copyFolder(parent)
}

val FSNodeInterface.path: MutableFSPath
    get() {
        val nodePath = generateSequence(this) { it.parent }
            .toList().asReversed().drop(1)
        return nodePath.fold(MutableFSPath(), MutableFSPath::add)
    }



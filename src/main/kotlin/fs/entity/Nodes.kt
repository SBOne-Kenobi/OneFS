package fs.entity

import fs.emptyMD5
import fs.interactor.InteractorInterface
import fs.toInits
import utils.LazyList

sealed interface FSNodeInterface {
    val parent: FolderNodeInterface?
}

sealed class FSNode(final override val parent: FolderNode?) : FSNodeInterface

sealed class MutableFSNode(final override var parent: MutableFolderNode?) : FSNodeInterface


sealed interface FileNodeInterface : FSNodeInterface {
    val fileName: String
    val dataCell: DataCellInterface
    val creationTimestamp: Long
    val modificationTimestamp: Long
    val md5: ByteArray
}

class MutableFileNode(
    override var fileName: String,
    override val dataCell: MutableDataCell,
    override var creationTimestamp: Long,
    override var modificationTimestamp: Long,
    override var md5: ByteArray = emptyMD5,
    parent: MutableFolderNode? = null
): MutableFSNode(parent), FileNodeInterface

class FileNode(
    override val fileName: String,
    override val dataCell: DataCell,
    override val creationTimestamp: Long,
    override val modificationTimestamp: Long,
    override val md5: ByteArray = emptyMD5,
    parent: FolderNode? = null
): FSNode(parent), FileNodeInterface


sealed interface FolderNodeInterface : FSNodeInterface {
    val folderName: String
    val files: List<FileNodeInterface>
    val folders: List<FolderNodeInterface>
}

class MutableFolderNode(
    override var folderName: String,
    override val files: MutableList<MutableFileNode> = mutableListOf(),
    override val folders: MutableList<MutableFolderNode> = mutableListOf(),
    parent: MutableFolderNode? = null
): MutableFSNode(parent), FolderNodeInterface

class FolderNode(
    override val folderName: String,
    override val files: List<FileNode> = emptyList(),
    override val folders: List<FolderNode> = emptyList(),
    parent: FolderNode? = null
): FSNode(parent), FolderNodeInterface


fun MutableFileNode.immutableFile(builtParent: FolderNode? = null): FileNode =
    FileNode(
        fileName,
        DataCell(dataCell.controller),
        creationTimestamp,
        modificationTimestamp,
        md5,
        builtParent
    )

fun MutableFolderNode.immutableFolder(builtParent: FolderNode? = null): FolderNode {
    val initsFolder = folders.toInits<FolderNode>()
    val initsFiles = files.toInits<FileNode>()
    val result = FolderNode(
        folderName,
        LazyList(initsFiles),
        LazyList(initsFolder),
        builtParent
    )
    repeat(initsFolder.size) { i -> initsFolder[i] = { folders[i].immutableFolder(result) } }
    repeat(initsFiles.size) { i -> initsFiles[i] = { files[i].immutableFile(result) } }
    return result
}

fun MutableFileNode.copyFile(
    interactor: InteractorInterface,
    parent: MutableFolderNode? = null,
    change: MutableFileNode.() -> Unit = {}
): MutableFileNode =
    MutableFileNode(
        fileName,
        MutableDataCell(dataCell.controller.createDeepCopy()),
        creationTimestamp,
        modificationTimestamp,
        md5.copyOf(),
        parent
    ).apply {
        change()
        interactor.createFile(this)
    }

fun MutableFolderNode.copyFolder(
    interactor: InteractorInterface,
    parent: MutableFolderNode? = null,
    change: MutableFolderNode.() -> Unit = {}
): MutableFolderNode {
    val result = MutableFolderNode(folderName, parent = parent)
    result.change()
    interactor.createFolder(result)
    result.files.addAll(files.map { it.copyFile(interactor, result) })
    result.folders.addAll(folders.map { it.copyFolder(interactor, result) })
    return result
}

val FSNodeInterface.path: MutableFSPath
    get() {
        val nodePath = generateSequence(this) { it.parent }
            .toList().asReversed().drop(1)
        return nodePath.fold(MutableFSPath(), MutableFSPath::add)
    }



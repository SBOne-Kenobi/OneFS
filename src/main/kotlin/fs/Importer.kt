package fs

import fs.entity.MutableDataCell
import fs.entity.MutableFileNode
import fs.entity.MutableFolderNode
import fs.interactor.InteractorInterface
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.forEachDirectoryEntry
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * Interface for importing data from external sources.
 */
interface Importer<FileId, FolderId> {
    fun importFile(interactor: InteractorInterface, parent: MutableFolderNode, fileId: FileId): MutableFileNode
    fun importFolder(interactor: InteractorInterface, parent: MutableFolderNode, folderId: FolderId): MutableFolderNode
}

class SystemImporter : Importer<Path, Path> {
    override fun importFile(interactor: InteractorInterface, parent: MutableFolderNode, fileId: Path): MutableFileNode {
        val attrs = Files.readAttributes(fileId, BasicFileAttributes::class.java)

        val dataCell = MutableDataCell(interactor.allocateNewData(Files.size(fileId)))
        dataCell.getOutputStream(0).use { output ->
            fileId.inputStream().use { input ->
                input.copyTo(output)
            }
        }

        return MutableFileNode(
            fileId.name,
            dataCell,
            attrs.creationTime().toMillis(),
            attrs.lastModifiedTime().toMillis(),
            fileId.inputStream().computeMD5(),
            parent
        ).also {
            interactor.createFile(it)
        }
    }

    override fun importFolder(interactor: InteractorInterface, parent: MutableFolderNode, folderId: Path): MutableFolderNode {
        val result = MutableFolderNode(folderId.name, parent = parent)
        interactor.createFolder(result)
        folderId.forEachDirectoryEntry { pathId ->
            val child = if (pathId.isDirectory()) {
                importFolder(interactor, result, pathId).also {
                    result.folders.add(it)
                }
            } else {
                importFile(interactor, result, pathId).also {
                    result.files.add(it)
                }
            }
            child.parent = result
        }
        return result
    }
}

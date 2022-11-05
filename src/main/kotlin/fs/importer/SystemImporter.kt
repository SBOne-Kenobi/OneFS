package fs.importer

import fs.computeMD5
import fs.entity.FolderNode
import fs.entity.NodeLoader
import fs.entity.addFile
import fs.entity.addFolder
import fs.interactor.InteractorInterface
import java.nio.file.Path
import kotlin.io.path.forEachDirectoryEntry
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.name

class SystemImporter : Importer<Path, Path> {
    override fun importFile(
        interactor: InteractorInterface,
        parent: NodeLoader<FolderNode>,
        fileId: Path,
        name: String?
    ) {
        val path = parent.path.addFile(name ?: fileId.name)

        interactor.createFile(path)

        interactor.getMutableDataCell(path).use { dataCell ->
            dataCell.getOutputStream(0).use { output ->
                fileId.inputStream().use { input ->
                    input.copyTo(output)
                }
            }
        }

        interactor.setMD5(path, fileId.inputStream().use { it.computeMD5() })
    }

    override fun importFolder(
        interactor: InteractorInterface,
        parent: NodeLoader<FolderNode>,
        folderId: Path,
        name: String?
    ) {
        val path = parent.path.addFolder(name ?: folderId.name)

        interactor.createFolder(path)
        val loader = interactor.getFolderLoader(path)

        folderId.forEachDirectoryEntry { pathId ->
            if (pathId.isDirectory()) {
                importFolder(interactor, loader, pathId)
            } else {
                importFile(interactor, loader, pathId)
            }
        }
    }
}
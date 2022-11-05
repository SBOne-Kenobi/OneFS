package fs.importer

import fs.entity.FileNode
import fs.entity.FolderNode
import fs.entity.NodeLoader
import fs.entity.addFile
import fs.entity.addFolder
import fs.interactor.InteractorInterface

class CopyImporter : Importer<NodeLoader<FileNode>, NodeLoader<FolderNode>> {
    override fun importFile(
        interactor: InteractorInterface,
        parent: NodeLoader<FolderNode>,
        fileId: NodeLoader<FileNode>,
        name: String?
    ) {
        val path = parent.path.addFile(name ?: fileId.name)
        interactor.createFile(path)
        interactor.getMutableDataCell(path).getOutputStream(offset = 0).use { output ->
            interactor.getMutableDataCell(fileId.path).getInputStream().use { input ->
                input.copyTo(output)
            }
        }
        interactor.setMD5(path, fileId.load().md5)
    }

    override fun importFolder(
        interactor: InteractorInterface,
        parent: NodeLoader<FolderNode>,
        folderId: NodeLoader<FolderNode>,
        name: String?
    ) {
        val path = parent.path.addFolder(name ?: folderId.name)
        interactor.createFolder(path)

        val loader = interactor.getFolderLoader(path)
        val folder = folderId.load()

        folder.files.forEach { node ->
            importFile(interactor, loader, node)
        }

        folder.folders.forEach { node ->
            importFolder(interactor, loader, node)
        }
    }
}
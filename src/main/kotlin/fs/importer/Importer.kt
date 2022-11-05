package fs.importer

import fs.entity.FolderNode
import fs.entity.NodeLoader
import fs.interactor.InteractorInterface

/**
 * Interface for importing data from external sources.
 */
interface Importer<FileId, FolderId> {

    fun importFile(
        interactor: InteractorInterface,
        parent: NodeLoader<FolderNode>,
        fileId: FileId,
        name: String? = null
    )

    fun importFolder(
        interactor: InteractorInterface,
        parent: NodeLoader<FolderNode>,
        folderId: FolderId,
        name: String? = null
    )
}


package fs.interactor

import fs.entity.DataCellController
import fs.entity.MutableFileNode
import fs.entity.MutableFolderNode

/**
 * Interface for generating and save sequence of change events.
 */
interface InteractorInterface {

    /**
     * Creates root folder node.
     */
    fun getFileSystem(): MutableFolderNode

    fun allocateNewData(minimalSize: Long): DataCellController

    fun createFile(file: MutableFileNode)

    fun deleteFile(file: MutableFileNode)

    fun updateFileRecord(file: MutableFileNode)

    fun createFolder(folder: MutableFolderNode)

    fun deleteFolder(folder: MutableFolderNode)

    fun updateFolderRecord(folder: MutableFolderNode)

}
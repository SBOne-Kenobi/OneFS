package fs.interactor

import fs.entity.DataCell
import fs.entity.FSPath
import fs.entity.FileNode
import fs.entity.FolderNode
import fs.entity.MutableDataCell
import fs.entity.NodeLoader

/**
 * Interface for working with file system.
 */
interface InteractorInterface {

    fun createFile(path: FSPath)

    fun deleteFile(path: FSPath)

    fun moveFile(sourcePath: FSPath, destinationPath: FSPath)

    fun setMD5(path: FSPath, md5: ByteArray)

    fun getFileLoader(path: FSPath): NodeLoader<FileNode>

    fun getDataCell(path: FSPath): DataCell

    fun getMutableDataCell(path: FSPath): MutableDataCell

    fun createFolder(path: FSPath)

    fun deleteFolder(path: FSPath)

    fun moveFolder(sourcePath: FSPath, destinationPath: FSPath)

    fun getFolderLoader(path: FSPath): NodeLoader<FolderNode>

}
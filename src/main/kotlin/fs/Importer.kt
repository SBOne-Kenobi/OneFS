package fs

import com.google.protobuf.ByteString
import fs.proto.File
import fs.proto.Folder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.forEachDirectoryEntry
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * Interface for importing data from external sources.
 */
interface Importer<FileId, FolderId> {
    fun importFile(fileId: FileId): MutableFileNode
    fun importFolder(folderId: FolderId): MutableFolderNode
}

class SystemImporter : Importer<Path, Path> {
    override fun importFile(fileId: Path): MutableFileNode {
        val file = File.newBuilder().apply {
            val file = fileId.toFile()
            val content = file.readBytes()
            name = file.name
            data = ByteString.copyFrom(content)
            md5 = content.computeMD5()
            val attrs = Files.readAttributes(fileId, BasicFileAttributes::class.java)
            creationTimestamp = attrs.creationTime().toMillis()
            modificationTimestamp = attrs.lastModifiedTime().toMillis()
        }
        return MutableFileNode(file)
    }

    override fun importFolder(folderId: Path): MutableFolderNode {
        val folder = Folder.newBuilder().apply {
            name = folderId.name
        }
        val result = MutableFolderNode(folder)
        folderId.forEachDirectoryEntry { path ->
            val child = if (path.isDirectory()) {
                importFolder(path).also {
                    result.folders.add(it)
                }
            } else {
                importFile(path).also {
                    result.files.add(it)
                }
            }
            child.parent = result
        }
        return result
    }
}

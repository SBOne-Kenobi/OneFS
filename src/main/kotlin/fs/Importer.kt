package fs

import com.google.protobuf.ByteString
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
    fun importFile(fileId: FileId): File
    fun importFolder(folderId: FolderId): Folder
}

class SystemImporter : Importer<Path, Path> {
    override fun importFile(fileId: Path): File = File.newBuilder().apply {
        val file = fileId.toFile()
        name = file.name
        data = ByteString.copyFrom(file.readBytes())
        md5 = computeMD5()
        val attrs = Files.readAttributes(fileId, BasicFileAttributes::class.java)
        creationTimestamp = attrs.creationTime().toMillis()
        modificationTimestamp = attrs.lastModifiedTime().toMillis()
    }.build()

    override fun importFolder(folderId: Path): Folder = Folder.newBuilder().apply {
        name = folderId.name
        folderId.forEachDirectoryEntry { path ->
            if (path.isDirectory()) {
                addFolders(importFolder(path))
            } else {
                addFiles(importFile(path))
            }
        }
    }.build()
}

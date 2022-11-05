package fs.interactor

import fs.emptyMD5
import fs.entity.DataCell
import fs.entity.DataCellController
import fs.entity.DataPointer
import fs.entity.DataPointerObserver
import fs.entity.FSNode
import fs.entity.FSPath
import fs.entity.FileNode
import fs.entity.FileRecord
import fs.entity.FolderNode
import fs.entity.FolderRecord
import fs.entity.FreeRecord
import fs.entity.HEADER_SIZE
import fs.entity.ITEM_POINTER_SIZE
import fs.entity.ItemPointer
import fs.entity.ItemRecord
import fs.entity.LONG_SIZE
import fs.entity.MAX_NAME_SIZE
import fs.entity.MutableDataCell
import fs.entity.NodeLoader
import fs.entity.OneFileSystemException
import fs.entity.ParseError
import fs.entity.RowUsedContentRecord
import fs.entity.addFile
import fs.entity.addFolder
import fs.entity.getRecords
import fs.entity.name
import fs.entity.parseNextRecord
import fs.entity.removeLast
import fs.entity.writeRecord
import fs.readLong
import fs.toDataCell
import fs.toMutableDataCell
import fs.toPointer
import fs.writeLong
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.Path
import kotlin.io.path.createFile
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.notExists
import kotlin.io.path.outputStream
import kotlin.properties.Delegates
import kotlin.reflect.jvm.isAccessible
import org.apache.commons.io.input.BoundedInputStream
import org.apache.commons.io.input.RandomAccessFileInputStream
import utils.RandomAccessFileOutputStream

/**
 * Interactor for working with filesystem's file.
 */
class FSInteractor(val fileSystemPath: Path) : InteractorInterface, AutoCloseable {

    init {
        if (fileSystemPath.notExists()) {
            fileSystemPath.createFile()
            fileSystemPath.outputStream().use {
                writeRecord(
                    it, FolderRecord(
                        name = "",
                        ItemPointer(beginPosition = -1),
                        childrenPointer = ItemPointer(beginPosition = -1),
                        beginRecordPosition = 0
                    )
                )
            }
        } else if (fileSystemPath.isDirectory()) {
            throw OneFileSystemException("$fileSystemPath is a directory!")
        }
    }

    enum class RAFAccess(val flags: String) {
        READ("r"),
        WRITE("rw")
    }

    private fun getRAF(access: RAFAccess) = RandomAccessFile(fileSystemPath.toFile(), access.flags)

    private inner class PointedDataCellController(private val recordPosition: Long) : DataCellController {

        var pointerPosition by Delegates.notNull<Long>()

        private inner class LengthUpdater : DataPointerObserver {
            private val raf = getRAF(RAFAccess.WRITE)
            private val position = recordPosition + HEADER_SIZE

            override fun lengthOnChange(newValue: Long) {
                raf.seek(position)
                raf.writeLong(newValue)
            }

            override fun close() {
                raf.close()
            }

        }

        override val dataPointer: DataPointer by lazy {
            val record = readRecord(recordPosition) as RowUsedContentRecord

            DataPointer(
                record.beginRecordPosition + EXTENDED_DATA_HEADER_SIZE,
                record.filledSize,
                record.capacitySize,
                LengthUpdater()
            )
        }

        override fun getInputStream(): InputStream {
            val raf = getRAF(RAFAccess.READ)
            raf.seek(dataPointer.beginPosition)
            return RandomAccessFileInputStream(raf, true)
        }

        override fun getOutputStream(offset: Long): OutputStream {
            val raf = getRAF(RAFAccess.WRITE)
            raf.seek(dataPointer.beginPosition + offset)
            return RandomAccessFileOutputStream(raf, closeOnClose = true)
        }

        override fun allocateNew(newMinimalSize: Long): DataCellController {
            return allocateRowRecord(newMinimalSize).also {
                // change pointer in file record
                it.pointerPosition = pointerPosition
                val raf = getRAF(RAFAccess.WRITE)
                raf.seek(pointerPosition)
                raf.writeLong(it.recordPosition)
                raf.close()
            }
        }

        override fun free() {
            makeRecordFree(recordPosition)
            close()
        }

        override fun close() {
            val field = this::dataPointer
            field.isAccessible = true
            field.getDelegate().apply {
                this as Lazy<*>
                if (isInitialized()) {
                    dataPointer.close()
                }
            }
        }

    }

    private fun getFileController(fileRecord: FileRecord): PointedDataCellController =
        PointedDataCellController(fileRecord.contentPointer.beginPosition).apply {
            pointerPosition = fileRecord.beginRecordPosition + HEADER_SIZE + MAX_NAME_SIZE + ITEM_POINTER_SIZE
        }

    private fun getFolderChildrenController(folderRecord: FolderRecord): PointedDataCellController =
        PointedDataCellController(folderRecord.childrenPointer.beginPosition).apply {
            pointerPosition = folderRecord.beginRecordPosition + HEADER_SIZE + MAX_NAME_SIZE + ITEM_POINTER_SIZE
        }

    companion object {
        private const val ROW_HEADER_SIZE = LONG_SIZE + LONG_SIZE
        private const val EXTENDED_DATA_HEADER_SIZE = HEADER_SIZE + ROW_HEADER_SIZE
    }

    private fun writeRecord(record: ItemRecord) {
        val raf = getRAF(RAFAccess.WRITE)
        raf.seek(record.beginRecordPosition)
        RandomAccessFileOutputStream(raf, closeOnClose = true).use {
            writeRecord(it, record)
        }
    }

    private fun readRecord(position: Long): ItemRecord {
        val raf = getRAF(RAFAccess.READ)
        raf.seek(position)
        return RandomAccessFileInputStream(raf, true).use { input ->
            parseNextRecord(input, position)!!
        }
    }

    private fun makeRecordFree(position: Long) {
        val raf = getRAF(RAFAccess.WRITE)
        raf.seek(position)
        // mark data free
        raf.write(0)
        raf.close()
    }

    private fun FreeRecord.convertToRowRecord(): RowUsedContentRecord =
        RowUsedContentRecord(
            filledSize = 0,
            sizeOfData - ROW_HEADER_SIZE,
            beginRecordPosition
        ).also {
            writeRecord(it)
        }

    private fun allocateRowRecord(minimalDataSize: Long): PointedDataCellController {
        val record = findFreeSpace(ROW_HEADER_SIZE + minimalDataSize, fit = false)
            .convertToRowRecord()
        return PointedDataCellController(record.beginRecordPosition)
    }

    private fun findFreeSpace(minimalDataSize: Long, fit: Boolean): FreeRecord {
        var fileSize = 0L
        var record = fileSystemPath.inputStream().use { input ->
            input.getRecords().find { record ->
                fileSize += record.recordSize
                if (record is FreeRecord) {
                    if (fit) {
                        record.sizeOfData == minimalDataSize
                    } else {
                        record.sizeOfData >= minimalDataSize
                    }
                } else {
                    false
                }
            }
        } as FreeRecord?
        if (record == null) {
            record = FreeRecord(minimalDataSize, fileSize)
            writeRecord(record)
        }

        return record
    }

    private fun FolderRecord.getChildren(): Sequence<ItemRecord> = sequence {
        getRAF(RAFAccess.READ).use { raf ->
            val children = readRecord(childrenPointer.beginPosition) as RowUsedContentRecord
            val position = children.beginRecordPosition + EXTENDED_DATA_HEADER_SIZE
            raf.seek(position)
            BoundedInputStream(RandomAccessFileInputStream(raf), children.filledSize).use { input ->
                while (true) {
                    val childPosition = try {
                        input.readLong()
                    } catch (_: ParseError) {
                        break
                    }
                    yield(readRecord(childPosition))
                }
            }
        }
    }

    private fun findRecord(path: FSPath): ItemRecord {
        var currentRecord = fileSystemPath.inputStream().use { input ->
            input.getRecords().find {
                it is FolderRecord && it.name == "" && it.parentPointer.beginPosition == -1L
            } as FolderRecord
        }
        val lastIndex = path.pathList.lastIndex
        path.pathList.forEachIndexed { index, name ->
            val targetRecord = currentRecord.getChildren().find { childRecord ->
                when (childRecord) {
                    is FileRecord -> childRecord.name == name
                    is FolderRecord -> childRecord.name == name
                    else -> false
                }
            } ?: throw OneFileSystemException("${path.pathString} is not found!")
            if (targetRecord is FolderRecord) {
                currentRecord = targetRecord
            } else {
                if (index == lastIndex) {
                    return targetRecord
                } else {
                    throw OneFileSystemException("Unexpected file $name")
                }
            }
        }
        return currentRecord
    }

    private fun FolderRecord.addChild(position: Long) {
        getFolderChildrenController(this)
            .toMutableDataCell()
            .getOutputStream(offset = -1)
            .use { output ->
                output.writeLong(position)
            }
    }

    private fun FolderRecord.removeChild(position: Long) {
        val children = getChildren().toMutableList().apply {
            removeIf { it.beginRecordPosition == position }
        }
        getFolderChildrenController(this)
            .toMutableDataCell()
            .apply { clearData() }
            .getOutputStream(offset = -1)
            .use { output ->
                children.forEach {
                    output.writeLong(it.beginRecordPosition)
                }
            }
    }


    override fun createFile(path: FSPath) {
        val parentRecord = findRecord(path.removeLast()) as FolderRecord

        val contentRecord = findFreeSpace(ROW_HEADER_SIZE + 20L, fit = false)
            .convertToRowRecord()
        val freeRecord = findFreeSpace(FileRecord.DATA_SIZE.toLong(), fit = true)

        val currentTime = System.currentTimeMillis()
        val fileRecord = FileRecord(
            path.name,
            parentRecord.toPointer(),
            contentRecord.toPointer(),
            currentTime,
            currentTime,
            emptyMD5,
            freeRecord.beginRecordPosition
        )
        writeRecord(fileRecord)

        parentRecord.addChild(fileRecord.beginRecordPosition)
    }

    override fun deleteFile(path: FSPath) {
        val record = findRecord(path) as FileRecord
        val parentRecord = readRecord(record.parentPointer.beginPosition) as FolderRecord

        parentRecord.removeChild(record.beginRecordPosition)
        makeRecordFree(record.beginRecordPosition)
        makeRecordFree(record.contentPointer.beginPosition)
    }

    override fun moveFile(sourcePath: FSPath, destinationPath: FSPath) {
        val sourceFile = findRecord(sourcePath) as FileRecord

        val sourceParent = readRecord(sourceFile.parentPointer.beginPosition) as FolderRecord
        val destParent = findRecord(destinationPath.removeLast()) as FolderRecord

        val newFileRecord = FileRecord(
            destinationPath.name,
            destParent.toPointer(),
            sourceFile.contentPointer,
            sourceFile.creationTimestamp,
            sourceFile.modificationTimestamp,
            sourceFile.md5,
            sourceFile.beginRecordPosition
        )

        sourceParent.removeChild(sourceFile.beginRecordPosition)
        destParent.addChild(newFileRecord.beginRecordPosition)

        writeRecord(newFileRecord)
    }

    override fun setMD5(path: FSPath, md5: ByteArray) {
        val record = findRecord(path) as FileRecord
        val offset = HEADER_SIZE + MAX_NAME_SIZE + 2 * ITEM_POINTER_SIZE + 2 * LONG_SIZE

        getRAF(RAFAccess.WRITE).use {
            it.seek(record.beginRecordPosition + offset)
            it.write(md5)
        }
    }

    private inner class CachedLoader<T : FSNode>(val delegate: NodeLoader<T>) : NodeLoader<T> {
        @Suppress("UNCHECKED_CAST")
        private val node: T
            get() {
                cache?.let { return it }
                return delegate.load().also {
                    cache = it
                }
            }

        private var cache: T? = null

        override val name: String by delegate::name
        override val path: FSPath by delegate::path

        override fun load(): T = node

        override fun close() {
            delegate.close()
            cache = null
        }
    }

    private inner class NodeLoaderByRecord<T : FSNode>(
        override val path: FSPath,
        val recordPosition: Long,
        val transform: (path: FSPath, ItemRecord) -> T
    ) : NodeLoader<T> {
        override val name: String = path.name

        override fun load(): T = transform(path, readRecord(recordPosition))

        override fun close() {}

    }

    private fun recordToFolderLoader(path: FSPath, position: Long) =
        NodeLoaderByRecord(path, position, ::recordToFolderNode)

    private fun recordToFileLoader(path: FSPath, position: Long) =
        NodeLoaderByRecord(path, position, ::recordToFileNode)

    private fun recordToFileNode(path: FSPath, itemRecord: ItemRecord): FileNode {
        itemRecord as FileRecord
        return FileNode(
            itemRecord.name,
            itemRecord.creationTimestamp,
            itemRecord.modificationTimestamp,
            itemRecord.md5,
            recordToFolderLoader(path.removeLast(), itemRecord.parentPointer.beginPosition)
        )
    }

    private fun recordToFolderNode(path: FSPath, itemRecord: ItemRecord): FolderNode {
        itemRecord as FolderRecord
        val (files, folders) = itemRecord
            .getChildren()
            .partition { it is FileRecord }

        val parentLoader = if (path.pathList.isEmpty()) {
            null
        } else {
            recordToFolderLoader(path.removeLast(), itemRecord.parentPointer.beginPosition)
        }

        return FolderNode(
            itemRecord.name,
            files.map {
                it as FileRecord
                recordToFileLoader(path.addFile(it.name), it.beginRecordPosition)
            },
            folders.map {
                it as FolderRecord
                recordToFolderLoader(path.addFolder(it.name), it.beginRecordPosition)
            },
            parentLoader
        )
    }

    override fun getFileLoader(path: FSPath): NodeLoader<FileNode> {
        val record = findRecord(path)
        val mainLoader = recordToFileLoader(path, record.beginRecordPosition)
        return CachedLoader(mainLoader)
    }

    override fun getDataCell(path: FSPath): DataCell {
        val record = findRecord(path) as FileRecord
        return getFileController(record).toDataCell()
    }

    override fun getMutableDataCell(path: FSPath): MutableDataCell {
        val record = findRecord(path) as FileRecord
        return getFileController(record).toMutableDataCell()
    }

    override fun createFolder(path: FSPath) {
        val parentRecord = findRecord(path.removeLast()) as FolderRecord

        val children = findFreeSpace(10L * LONG_SIZE, fit = false).convertToRowRecord()
        val freeRecord = findFreeSpace(HEADER_SIZE + FolderRecord.DATA_SIZE.toLong(), fit = true)

        val folderRecord = FolderRecord(
            path.name,
            parentRecord.toPointer(),
            children.toPointer(),
            freeRecord.beginRecordPosition
        )
        writeRecord(folderRecord)

        parentRecord.addChild(folderRecord.beginRecordPosition)
    }

    override fun deleteFolder(path: FSPath) {
        val folderRecord = findRecord(path) as FolderRecord
        val parentRecord = readRecord(folderRecord.parentPointer.beginPosition) as FolderRecord

        parentRecord.removeChild(folderRecord.beginRecordPosition)
        makeRecordFree(folderRecord.beginRecordPosition)
        makeRecordFree(folderRecord.childrenPointer.beginPosition)
    }

    override fun moveFolder(sourcePath: FSPath, destinationPath: FSPath) {
        val sourceFolder = findRecord(sourcePath) as FolderRecord

        val sourceParent = readRecord(sourceFolder.parentPointer.beginPosition) as FolderRecord
        val destParent = findRecord(destinationPath.removeLast()) as FolderRecord

        val newFolderRecord = FolderRecord(
            destinationPath.name,
            destParent.toPointer(),
            sourceFolder.childrenPointer,
            sourceFolder.beginRecordPosition
        )

        sourceParent.removeChild(sourceFolder.beginRecordPosition)
        destParent.addChild(newFolderRecord.beginRecordPosition)

        writeRecord(newFolderRecord)
    }

    override fun getFolderLoader(path: FSPath): NodeLoader<FolderNode> {
        val record = findRecord(path)
        val mainLoader = recordToFolderLoader(path, record.beginRecordPosition)
        return CachedLoader(mainLoader)
    }

    override fun close() {}

}
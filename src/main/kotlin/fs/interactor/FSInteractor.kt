package fs.interactor

import fs.entity.DataCellController
import fs.entity.DataPointer
import fs.entity.DataPointerObserver
import fs.entity.FileRecord
import fs.entity.FolderRecord
import fs.entity.FreeRecord
import fs.entity.HEADER_SIZE
import fs.entity.ITEM_POINTER_SIZE
import fs.entity.ItemPointer
import fs.entity.ItemRecord
import fs.entity.LONG_SIZE
import fs.entity.MAX_NAME_SIZE
import fs.entity.MutableDataCell
import fs.entity.MutableFSNode
import fs.entity.MutableFileNode
import fs.entity.MutableFolderNode
import fs.entity.OneFileSystemException
import fs.entity.RowUsedContentRecord
import fs.entity.parseNextRecord
import fs.entity.writeRecord
import fs.toMemoryArea
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
class FSInteractor(val fileSystemPath: Path, private val allocator: Allocator) : InteractorInterface, AutoCloseable {

    init {
        if (fileSystemPath.notExists()) {
            fileSystemPath.createFile()
            fileSystemPath.outputStream().use {
                writeRecord(
                    it, FolderRecord(
                        name = "",
                        ItemPointer(beginPosition = -1),
                        beginRecordPosition = 0
                    )
                )
            }
        } else if (fileSystemPath.isDirectory()) {
            throw OneFileSystemException("$fileSystemPath is a directory!")
        }
    }

    private fun getRAF() = RandomAccessFile(fileSystemPath.toFile(), "rw")

    private inner class FileDataCellController(private val recordPosition: Long) : DataCellController {

        var fileRecordPosition by Delegates.notNull<Long>()

        private inner class LengthUpdater : DataPointerObserver {
            private val raf = getRAF()
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
            val raf = getRAF()
            raf.seek(recordPosition)
            val inputStream = RandomAccessFileInputStream(raf)
            val record = parseNextRecord(inputStream, recordPosition) as RowUsedContentRecord
            inputStream.close()
            raf.close()

            DataPointer(
                record.beginRecordPosition + EXTENDED_DATA_HEADER_SIZE,
                record.filledSize,
                record.capacitySize,
                LengthUpdater()
            )
        }

        override fun getInputStream(): InputStream {
            val raf = getRAF()
            raf.seek(dataPointer.beginPosition)
            return RandomAccessFileInputStream(raf, true)
        }

        override fun getOutputStream(offset: Long): OutputStream {
            val raf = getRAF()
            raf.seek(dataPointer.beginPosition + offset)
            return RandomAccessFileOutputStream(raf, closeOnClose = true)
        }

        override fun allocateNew(newMinimalSize: Long): DataCellController {
            return allocateNewData(newMinimalSize).also {
                // change pointer in file record
                it as FileDataCellController
                it.fileRecordPosition = fileRecordPosition
                val raf = getRAF()
                raf.seek(fileRecordPosition + HEADER_SIZE + MAX_NAME_SIZE + ITEM_POINTER_SIZE)
                raf.writeLong(it.recordPosition)
                raf.close()
            }
        }

        override fun free() {
            makeRecordFree(recordPosition)
            close()
        }

        /**
         * Don't use [allocateNew] without register and connection new data with any file.
         */
        override fun createDeepCopy(): DataCellController {
            val newController = allocateNewData(dataPointer.dataLength)
            BoundedInputStream(getInputStream(), dataPointer.dataLength).use { input ->
                newController.getOutputStream(0).use { output ->
                    input.copyTo(output)
                }
            }
            newController.dataPointer.dataLength = dataPointer.dataLength
            return newController
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

    private fun getDataCell(filePosition: Long, beginPosition: Long): MutableDataCell {
        val dataCellController = FileDataCellController(beginPosition)
        dataCellController.fileRecordPosition = filePosition
        return MutableDataCell(dataCellController)
    }

    private val positionToNode: MutableMap<Long, MutableFSNode> = mutableMapOf()
    private val nodeToPosition: MutableMap<MutableFSNode, Long> = mutableMapOf()

    private fun registerNewNodeWithPosition(node: MutableFSNode, position: Long) {
        positionToNode[position] = node
        nodeToPosition[node] = position
    }

    private fun unregisterNode(node: MutableFSNode) {
        val position = nodeToPosition[node]
        nodeToPosition.remove(node)
        positionToNode.remove(position)
    }

    private fun MutableFSNode?.toPointer(): ItemPointer = if (this == null) {
        ItemPointer(beginPosition = -1)
    } else {
        ItemPointer(nodeToPosition[this]!!)
    }

    private fun registerRecord(record: ItemRecord, initChain: MutableList<() -> Unit>): MutableFolderNode? =
        when (record) {
            is FileRecord -> {
                val fileNode = MutableFileNode(
                    record.name,
                    getDataCell(record.beginRecordPosition, record.contentPointer.beginPosition),
                    record.creationTimestamp,
                    record.modificationTimestamp,
                    record.md5
                )
                initChain.add {
                    fileNode.parent = positionToNode[record.parentPointer.beginPosition] as MutableFolderNode?
                    fileNode.parent?.files?.add(fileNode)
                }
                registerNewNodeWithPosition(fileNode, record.beginRecordPosition)
                allocator.registerUsedArea(record.toMemoryArea())
                null
            }

            is FolderRecord -> {
                val folderNode = MutableFolderNode(record.name)
                initChain.add {
                    folderNode.parent = positionToNode[record.parentPointer.beginPosition] as MutableFolderNode?
                    folderNode.parent?.folders?.add(folderNode)
                }
                registerNewNodeWithPosition(folderNode, record.beginRecordPosition)
                allocator.registerUsedArea(record.toMemoryArea())
                folderNode.takeIf { record.parentPointer.beginPosition == -1L }
            }

            is FreeRecord -> {
                allocator.registerFreeArea(record.toMemoryArea())
                null
            }

            is RowUsedContentRecord -> {
                allocator.registerUsedArea(record.toMemoryArea())
                null
            }
        }

    override fun getFileSystem(): MutableFolderNode {
        allocator.clear()
        nodeToPosition.clear()
        positionToNode.clear()

        var rootNode: MutableFolderNode? = null
        val initChain = mutableListOf<() -> Unit>()

        val input = fileSystemPath.inputStream()
        var currentPosition = 0L
        while (true) {
            val record = parseNextRecord(input, currentPosition) ?: break
            currentPosition += record.recordSize
            registerRecord(record, initChain)?.let {
                rootNode = it
            }
        }
        initChain.forEach { it() }

        return rootNode ?: throw OneFileSystemException("Root not found!")
    }

    companion object {
        private const val EXTENDED_DATA_HEADER_SIZE = HEADER_SIZE + LONG_SIZE + LONG_SIZE
    }

    private fun writeRecord(record: ItemRecord) {
        val raf = getRAF()
        raf.seek(record.beginRecordPosition)
        RandomAccessFileOutputStream(raf, closeOnClose = true).use {
            writeRecord(it, record)
        }
    }

    override fun allocateNewData(minimalSize: Long): DataCellController {
        val area = allocator.allocateNewData(EXTENDED_DATA_HEADER_SIZE + minimalSize)

        val record = RowUsedContentRecord(
            filledSize = 0,
            area.size - EXTENDED_DATA_HEADER_SIZE,
            area.begin
        )
        writeRecord(record)

        return FileDataCellController(area.begin)
    }

    private fun makeRecordFree(position: Long) {
        val raf = getRAF()
        raf.seek(position)
        // mark data free
        raf.write(0)
        raf.close()

        val area = allocator.unregisterUsedArea(position)
        allocator.registerFreeArea(area)
    }

    private fun MutableFileNode.toFileRecord(beginPosition: Long) = FileRecord(
        fileName,
        parent.toPointer(),
        ItemPointer(dataCell.controller.dataPointer.beginPosition - EXTENDED_DATA_HEADER_SIZE),
        creationTimestamp,
        modificationTimestamp,
        md5,
        beginPosition
    )

    private fun MutableFolderNode.toFolderRecord(beginPosition: Long) = FolderRecord(
        folderName,
        parent.toPointer(),
        beginPosition
    )

    override fun createFile(file: MutableFileNode) {
        val area = allocator.allocateNewData(HEADER_SIZE + FileRecord.DATA_SIZE.toLong(), fitted = true)

        val record = file.toFileRecord(area.begin)
        writeRecord(record)

        registerNewNodeWithPosition(file, area.begin)
    }

    override fun deleteFile(file: MutableFileNode) {
        val position = nodeToPosition[file]!!
        makeRecordFree(position)
        unregisterNode(file)
    }

    override fun updateFileRecord(file: MutableFileNode) {
        val position = nodeToPosition[file]!!
        val record = file.toFileRecord(position)
        writeRecord(record)
    }

    override fun createFolder(folder: MutableFolderNode) {
        val area = allocator.allocateNewData(HEADER_SIZE + FolderRecord.DATA_SIZE.toLong(), fitted = true)

        val record = folder.toFolderRecord(area.begin)
        writeRecord(record)
        registerNewNodeWithPosition(folder, area.begin)
    }

    override fun deleteFolder(folder: MutableFolderNode) {
        val position = nodeToPosition[folder]!!
        makeRecordFree(position)
        unregisterNode(folder)
        folder.files.forEach {
            deleteFile(it)
        }
        folder.folders.forEach {
            deleteFolder(it)
        }
    }

    override fun updateFolderRecord(folder: MutableFolderNode) {
        val position = nodeToPosition[folder]!!
        val record = folder.toFolderRecord(position)
        writeRecord(record)
    }

    override fun close() {
        nodeToPosition.keys.forEach {
            if (it is MutableFileNode) {
                it.dataCell.controller.close()
            }
        }
    }

}
package fs

import fs.entity.FileRecord
import fs.entity.FolderRecord
import fs.entity.ItemPointer
import fs.entity.ItemRecord
import fs.entity.LONG_SIZE
import fs.entity.RowUsedContentRecord
import fs.entity.writeRecord
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import kotlin.io.path.outputStream

class FSBuilder {

    private val records: MutableList<ItemRecord> = mutableListOf()
    private val datas: MutableList<ByteArray?> = mutableListOf()
    var wrote: Long = 0L
        private set

    val root = FolderBuilder().apply {
        folderName = ""
        position = 0
    }

    fun addRecord(record: ItemRecord, data: ByteArray? = null) {
        records.add(record)
        datas.add(data)
        wrote += record.recordSize
    }

    fun build(path: Path) {
        records.clear()
        datas.clear()
        wrote = 0L

        root.build(null, this)

        val output = path.outputStream()
        (records zip datas).forEach { (record, data) ->
            writeRecord(output, record, data?.let { ByteArrayInputStream(it) })
        }
        output.close()
    }
}

class FolderBuilder {
    lateinit var folderName: String
    val fileBuilders: MutableList<FileBuilder> = mutableListOf()
    val folderBuilders: MutableList<FolderBuilder> = mutableListOf()

    var position: Long = 0

    fun build(parent: FolderBuilder?, builder: FSBuilder) {
        val childrenPosition = builder.wrote
        val childrenCount = fileBuilders.size + folderBuilders.size
        val childrenData = ByteArray(LONG_SIZE * childrenCount)
        builder.addRecord(RowUsedContentRecord(
            LONG_SIZE * childrenCount.toLong(),
            LONG_SIZE * childrenCount.toLong(),
            childrenPosition
        ), childrenData)

        position = builder.wrote
        builder.addRecord(FolderRecord(
            folderName,
            ItemPointer(parent?.position ?: -1),
            ItemPointer(childrenPosition),
            position
        ))

        val output = ByteArrayOutputStream()
        fileBuilders.forEach {
            it.build( this, builder)
            output.writeLong(it.position)
        }
        folderBuilders.forEach {
            it.build(this, builder)
            output.writeLong(it.position)
        }
        output.toByteArray().copyInto(childrenData)
    }
}

class FileBuilder {
    lateinit var fileName: String
    lateinit var data: ByteArray
    var creationTimestamp: Long = 0
    var modificationTimestamp: Long = 0
    lateinit var md5: ByteArray

    var position: Long = 0

    fun build(parent: FolderBuilder, builder: FSBuilder) {
        val dataPosition = builder.wrote
        builder.addRecord(RowUsedContentRecord(
            data.size.toLong(),
            data.size.toLong(),
            dataPosition
        ), data)

        position = builder.wrote
        builder.addRecord(FileRecord(
            fileName,
            ItemPointer(parent.position),
            ItemPointer(dataPosition),
            creationTimestamp,
            modificationTimestamp,
            md5,
            position
        ))
    }
}
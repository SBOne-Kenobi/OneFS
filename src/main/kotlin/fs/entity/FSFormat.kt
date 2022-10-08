package fs.entity

import fs.skip
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/*
    Global item format: [type, 1 byte] [size of data, 8 bytes] [data, any bytes].
    Item pointer (IP): [begin position, 8 bytes]
    Free record:        type=0, data=[some data].
    Row used content:   type=1, data=[filled size, 8 bytes] [capacity size, 8 bytes] [content].
    File:               type=2, data=[name, 30 bytes] [IP to parent] [IP to content] [creationTimestamp, 8 bytes] [modificationTimestamp, 8 bytes] [md5, 16 bytes].
    Folder:             type=3, data=[name, 30 bytes] [IP to parent].

    Null IP = -1
*/

const val TYPE_SIZE = 1
const val LONG_SIZE = Long.SIZE_BYTES
const val MAX_NAME_SIZE = 30
const val MD5_SIZE = 16
const val ITEM_POINTER_SIZE = LONG_SIZE

const val HEADER_SIZE = TYPE_SIZE + LONG_SIZE

sealed class ItemRecord(val type: Int, val sizeOfData: Long, val beginRecordPosition: Long) {
    val recordSize: Long
        get() = HEADER_SIZE + sizeOfData
}

data class ItemPointer(val beginPosition: Long)

class FreeRecord(
    sizeOfData: Long,
    beginRecordPosition: Long
) : ItemRecord(
    type = 0,
    sizeOfData = sizeOfData,
    beginRecordPosition = beginRecordPosition
)

class RowUsedContentRecord(
    val filledSize: Long,
    val capacitySize: Long,
    beginRecordPosition: Long
) : ItemRecord(
    type = 1,
    sizeOfData = LONG_SIZE + LONG_SIZE + capacitySize,
    beginRecordPosition = beginRecordPosition
)

class FileRecord(
    val name: String,
    val parentPointer: ItemPointer,
    val contentPointer: ItemPointer,
    val creationTimestamp: Long,
    val modificationTimestamp: Long,
    val md5: ByteArray,
    beginRecordPosition: Long
) : ItemRecord(
    type = 2,
    sizeOfData = DATA_SIZE.toLong(),
    beginRecordPosition = beginRecordPosition
) {
    companion object {
        const val DATA_SIZE = MAX_NAME_SIZE + ITEM_POINTER_SIZE + ITEM_POINTER_SIZE + LONG_SIZE + LONG_SIZE + MD5_SIZE
    }
}

class FolderRecord(
    val name: String,
    val parentPointer: ItemPointer,
    beginRecordPosition: Long
) : ItemRecord(
    type = 3,
    sizeOfData = DATA_SIZE.toLong(),
    beginRecordPosition = beginRecordPosition
) {
    companion object {
        const val DATA_SIZE = MAX_NAME_SIZE + ITEM_POINTER_SIZE
    }
}

class ParseError(message: String? = null, cause: Throwable? = null) : Exception(message, cause)

private fun InputStream.readLong(): Long = readNBytes(LONG_SIZE).let { bytes ->
    if (bytes.size != LONG_SIZE) {
        throw ParseError("Failed to parse next long")
    }
    ByteBuffer.wrap(bytes).long
}

fun parseFreeData(inputStream: InputStream, sizeOfData: Long, position: Long) : FreeRecord {
    val skipped = inputStream.skip(sizeOfData)
    if (skipped != sizeOfData) {
        throw ParseError("Failed to parse free record")
    }
    return FreeRecord(sizeOfData, position)
}

fun parseRowUsedData(inputStream: InputStream, sizeOfData: Long, position: Long) : RowUsedContentRecord {
    val size = inputStream.readLong()
    val capacity = inputStream.readLong()
    if (LONG_SIZE + LONG_SIZE + capacity != sizeOfData) {
        throw ParseError("Illegal size of row used data")
    }
    val skipped = inputStream.skip(capacity)
    if (skipped != capacity) {
        throw ParseError("Failed to parse row used data")
    }
    return RowUsedContentRecord(size, capacity, position)
}

private fun InputStream.parseName(): String = readNBytes(MAX_NAME_SIZE).let { bytes ->
    if (bytes.size != MAX_NAME_SIZE) {
        throw ParseError("Failed to parse name")
    }
    val endIndex = bytes.indexOf(0)
    bytes.decodeToString(endIndex = endIndex, throwOnInvalidSequence = true)
}

private fun InputStream.parseItemPointer(): ItemPointer =
    ItemPointer(readLong())

fun parseFileData(inputStream: InputStream, sizeOfData: Long, position: Long) : FileRecord {
    if (sizeOfData != FileRecord.DATA_SIZE.toLong()) {
        throw ParseError("Illegal size of file record data")
    }

    val name = inputStream.parseName()
    val parentPointer = inputStream.parseItemPointer()
    val contentPointer = inputStream.parseItemPointer()
    val creationTimestamp = inputStream.readLong()
    val modificationTimestamp = inputStream.readLong()
    val md5 = inputStream.readNBytes(MD5_SIZE)
    if (md5.size != MD5_SIZE) {
        throw ParseError("Failed to parse md5 hash")
    }

    return FileRecord(
        name, parentPointer, contentPointer,
        creationTimestamp, modificationTimestamp,
        md5, position
    )
}

fun parseFolderData(inputStream: InputStream, sizeOfData: Long, position: Long) : FolderRecord {
    if (sizeOfData != FolderRecord.DATA_SIZE.toLong()) {
        throw ParseError("Illegal size of folder record data")
    }

    val name = inputStream.parseName()
    val parentPointer = inputStream.parseItemPointer()

    return FolderRecord(name, parentPointer, position)
}

fun parseNextRecord(inputStream: InputStream, position: Long): ItemRecord? {
    try {
        val byte = inputStream.read()
        if (byte == -1) {
            return null
        }
        val sizeOfData = inputStream.readLong()
        return when (byte) {
            0 -> parseFreeData(inputStream, sizeOfData, position)
            1 -> parseRowUsedData(inputStream, sizeOfData, position)
            2 -> parseFileData(inputStream, sizeOfData, position)
            3 -> parseFolderData(inputStream, sizeOfData, position)
            else -> throw ParseError("Unknown type of record $byte")
        }
    } catch (e: Throwable) {
        throw ParseError("Failed to parse record", e)
    }
}

class WriteError(message: String? = null, cause: Throwable? = null) : Exception(message, cause)

private fun OutputStream.writeLong(long: Long) {
    ByteBuffer
        .allocate(LONG_SIZE)
        .putLong(long)
        .array()
        .let { bytes -> write(bytes) }
}

fun OutputStream.writeFreeData(record: FreeRecord) {
    skip(record.sizeOfData)
}

fun OutputStream.writeRowUsedData(record: RowUsedContentRecord, inputStream: InputStream?) {
    writeLong(record.filledSize)
    writeLong(record.capacitySize)
    val copied = inputStream?.copyTo(this) ?: 0
    if (copied != record.filledSize) {
        throw WriteError("Failed to write row used data")
    }
    skip(record.capacitySize - copied)
}

private fun OutputStream.writeName(name: String) {
    val result = ByteArray(MAX_NAME_SIZE) { 0 }
    name.encodeToByteArray().copyInto(result)
    write(result)
}

private fun OutputStream.writeItemPointer(pointer: ItemPointer) {
    writeLong(pointer.beginPosition)
}

fun OutputStream.writeFileData(record: FileRecord) {
    writeName(record.name)
    writeItemPointer(record.parentPointer)
    writeItemPointer(record.contentPointer)
    writeLong(record.creationTimestamp)
    writeLong(record.modificationTimestamp)
    write(record.md5)
}

fun OutputStream.writeFolderData(record: FolderRecord) {
    writeName(record.name)
    writeItemPointer(record.parentPointer)
}

fun writeRecord(outputStream: OutputStream, record: ItemRecord, inputStream: InputStream? = null) {
    try {
        outputStream.write(record.type)
        outputStream.writeLong(record.sizeOfData)
        when (record) {
            is FileRecord -> outputStream.writeFileData(record)
            is FolderRecord -> outputStream.writeFolderData(record)
            is FreeRecord -> outputStream.writeFreeData(record)
            is RowUsedContentRecord -> outputStream.writeRowUsedData(record, inputStream)
        }
    } catch (e: Throwable) {
        e.printStackTrace()
        throw WriteError("Failed to write record", e)
    }
}



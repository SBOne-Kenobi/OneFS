package fs.interactor

import capturing.impl.ReadPriorityCapture
import com.google.protobuf.ByteString
import com.google.protobuf.Message
import fs.FileNodeInterface
import fs.FileSystemWriteContext
import fs.FolderNodeInterface
import fs.MutableFolderNode
import fs.NavWriteContext
import fs.OneFileSystemException
import fs.OneFileSystemProvider
import fs.build
import fs.excludeLast
import fs.lastName
import fs.proto.CopyFile
import fs.proto.CopyFolder
import fs.proto.CreateFile
import fs.proto.CreateFolder
import fs.proto.DeleteFile
import fs.proto.DeleteFolder
import fs.proto.FileOrBuilder
import fs.proto.Folder
import fs.proto.FolderOrBuilder
import fs.proto.ModifyFile
import fs.proto.MoveFile
import fs.proto.MoveFolder
import fs.toByteArray
import fs.toInt
import java.io.InputStream
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.createFile
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.notExists
import kotlin.io.path.outputStream
import kotlin.io.path.writeBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Interactor for working with filesystem's file.
 */
class FSInteractor(val fileSystemPath: Path) : InteractorInterface, AutoCloseable {

    init {
        if (fileSystemPath.notExists()) {
            fileSystemPath.createFile()
        } else if (fileSystemPath.isDirectory()) {
            throw OneFileSystemException("$fileSystemPath is a directory!")
        }
    }

    private data class MessageRecord(val id: Int, val message: Message)
    private var stream = fileSystemPath.outputStream(StandardOpenOption.APPEND)

    private fun writeMessage(message: MessageRecord) {
        // id + size of message + message bytes
        stream.write(message.id.toByteArray())
        val bytes = message.message.toByteArray()
        stream.write(bytes.size.toByteArray())
        stream.write(bytes)
        stream.flush()
    }

    private fun readNextCommand(input: InputStream): Message? {
        val command = ByteArray(Int.SIZE_BYTES)
        if (input.read(command) != command.size) {
            return null
        }
        val sizeBytes = ByteArray(Int.SIZE_BYTES)
        if (input.read(sizeBytes) != sizeBytes.size) {
            return null
        }
        val messageBytes = ByteArray(sizeBytes.toInt())
        if (input.read(messageBytes) != messageBytes.size) {
            return null
        }
        return when (command.toInt()) {
            1 -> CreateFile.parseFrom(messageBytes)
            2 -> DeleteFile.parseFrom(messageBytes)
            3 -> MoveFile.parseFrom(messageBytes)
            4 -> CopyFile.parseFrom(messageBytes)
            5 -> ModifyFile.parseFrom(messageBytes)
            6 -> CreateFolder.parseFrom(messageBytes)
            7 -> DeleteFolder.parseFrom(messageBytes)
            8 -> MoveFolder.parseFrom(messageBytes)
            9 -> CopyFolder.parseFrom(messageBytes)
            else -> null
        }
    }

    private fun NavWriteContext.callCommand(message: Message, writeContext: FileSystemWriteContext) {
        writeContext.run {
            when (message) {
                is CreateFile -> {
                    cd(message.path.excludeLast())
                    createFile(message.file)
                }
                is DeleteFile -> {
                    cd(message.path.excludeLast())
                    deleteFile(message.path.lastName())
                }
                is MoveFile -> {
                    cd(message.from.excludeLast())
                    moveFile(message.from.lastName(), message.to, override = true)
                }
                is CopyFile -> {
                    cd(message.from.excludeLast())
                    copyFile(message.from.lastName(), message.to, override = true)
                }
                is ModifyFile -> {
                    cd(message.path.excludeLast())
                    writeIntoFile(message.path.lastName(), message.data.toByteArray(), message.begin, message.end)
                }
                is CreateFolder -> {
                    cd(message.path.excludeLast())
                    createFolder(message.folder.name)
                }
                is DeleteFolder -> {
                    cd(message.path.excludeLast())
                    deleteFolder(message.path.lastName())
                }
                is MoveFolder -> {
                    cd(message.from.excludeLast())
                    moveFolder(message.from.lastName(), message.to, override = true)
                }
                is CopyFolder -> {
                    cd(message.from.excludeLast())
                    copyFolder(message.from.lastName(), message.to, override = true)
                }
            }
        }
    }

    override fun getFileSystem(): MutableFolderNode {
        val input = fileSystemPath.inputStream()
        val fakeInteractor = FakeInteractor()
        val provider = OneFileSystemProvider(fakeInteractor)
        val capture = ReadPriorityCapture(provider)
        var result = MutableFolderNode(Folder.newBuilder().apply { name = "" })
        runBlocking {
            capture.captureWrite {
                val writeContext = this
                captureWrite {
                    while (true) {
                        val message = readNextCommand(input) ?: break
                        callCommand(message, writeContext)
                    }
                    result = rootFolder
                }
            }
        }
        return result
    }

    private fun NavWriteContext.overrideFolder(
        folderNode: FolderNodeInterface,
        writeContext: FileSystemWriteContext
    ) {
        writeContext.run {
            folderNode.files.forEach { overrideFile(it, writeContext) }
            folderNode.folders.forEach {
                createFolder(it.folder.name)
                cd(it.folder.name)
                overrideFolder(it, writeContext)
                back()
            }
        }
    }

    private fun NavWriteContext.overrideFile(
        fileNode: FileNodeInterface,
        writeContext: FileSystemWriteContext
    ) {
        writeContext.run {
            createFile(fileNode.file.build())
        }
    }

    override suspend fun overrideFileWith(rootNode: FolderNodeInterface) {
        withContext(Dispatchers.IO) {
            stream.close()
        }
        fileSystemPath.writeBytes(ByteArray(0))
        stream = fileSystemPath.outputStream(StandardOpenOption.APPEND)

        val provider = OneFileSystemProvider(this)
        val capture = ReadPriorityCapture(provider)
        capture.captureWrite {
            val writeContext = this
            withMutableFolder {
                overrideFolder(rootNode, writeContext)
            }
        }
    }

    override fun createFile(path: String, file: FileOrBuilder) {
        val message = CreateFile.newBuilder().apply {
            this.path = path
            this.file = file.build()
        }.build()
        writeMessage(MessageRecord(1, message))
    }

    override fun deleteFile(path: String) {
        val message = DeleteFile.newBuilder().apply {
            this.path = path
        }.build()
        writeMessage(MessageRecord(2, message))
    }

    override fun moveFile(from: String, to: String) {
        val message = MoveFile.newBuilder().apply {
            this.from = from
            this.to = to
        }.build()
        writeMessage(MessageRecord(3, message))
    }

    override fun copyFile(from: String, to: String) {
        val message = CopyFile.newBuilder().apply {
            this.from = from
            this.to = to
        }.build()
        writeMessage(MessageRecord(4, message))
    }

    override fun modifyFile(path: String, data: ByteArray, begin: Int, end: Int, timestamp: Long) {
        val message = ModifyFile.newBuilder().apply {
            this.path = path
            this.data = ByteString.copyFrom(data)
            this.begin = begin
            this.end = end
            this.timestamp = timestamp
        }.build()
        writeMessage(MessageRecord(5, message))
    }

    override fun createFolder(path: String, folder: FolderOrBuilder) {
        val message = CreateFolder.newBuilder().apply {
            this.path = path
            this.folder = folder.build()
        }.build()
        writeMessage(MessageRecord(6, message))
    }

    override fun deleteFolder(path: String) {
        val message = DeleteFolder.newBuilder().apply {
            this.path = path
        }.build()
        writeMessage(MessageRecord(7, message))
    }

    override fun moveFolder(from: String, to: String) {
        val message = MoveFolder.newBuilder().apply {
            this.from = from
            this.to = to
        }.build()
        writeMessage(MessageRecord(8, message))
    }

    override fun copyFolder(from: String, to: String) {
        val message = CopyFolder.newBuilder().apply {
            this.from = from
            this.to = to
        }.build()
        writeMessage(MessageRecord(9, message))
    }

    override fun close() {
        stream.close()
    }

}
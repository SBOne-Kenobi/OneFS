package fs

import capturing.impl.ReadPriorityCapture
import com.google.protobuf.ByteString
import fs.interactor.FSInteractor
import fs.interactor.InteractorInterface
import fs.proto.File
import fs.proto.Folder
import kotlin.io.path.Path
import kotlin.io.path.deleteExisting
import kotlin.io.path.div
import kotlin.io.path.fileSize
import kotlin.io.path.toPath
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class OneFileSystemProviderTest {

    private inline fun withTestFS(block: (InteractorInterface) -> Unit) {
        try {
            val interactor = FSInteractor(fsPath)
            runBlocking {
                interactor.overrideFileWith(testRoot)
            }
            block(interactor)
        } finally {
            fsPath.deleteExisting()
        }
    }

    @Test
    fun testNavigation() = runTest {
        withTestFS { interactor ->
            val provider = OneFileSystemProvider(interactor)
            val capture = ReadPriorityCapture(provider)

            capture.captureRead {
                withFolder {
                    assertEquals("/", currentPath.path)
                    assertEquals(2, currentFolder.files.size)
                    assertEquals(2, currentFolder.folders.size)
                }
                withMutableFolder {
                    try {
                        cd("lol")
                        assert(false)
                    } catch (_: DirectoryNotFound) {
                    }
                    assertEquals("/", currentPath.path)

                    cd("empty_folder")
                    assertEquals("/empty_folder/", currentPath.path)
                }
                withFolder {
                    assertEquals(0, currentFolder.files.size)
                    assertEquals(0, currentFolder.folders.size)
                }
                withMutableFolder {
                    cd("/folder/empty_folder_2")
                    assertEquals("/folder/empty_folder_2/", currentPath.path)

                    back()
                    assertEquals("/folder/", currentPath.path)

                    cd("/empty_folder")
                    assertEquals("/empty_folder/", currentPath.path)

                    cd("/")
                    assertEquals("/", currentPath.path)

                    cd("folder/empty_folder_2/")
                    assertEquals("/folder/empty_folder_2/", currentPath.path)
                }
                withFolder {
                    assertEquals(0, currentFolder.files.size)
                    assertEquals(0, currentFolder.folders.size)
                }
                withMutableFolder {
                    back()
                    assertEquals("/folder/", currentPath.path)
                }
                withFolder {
                    assertEquals(2, currentFolder.folders.size)
                    assertEquals(2, currentFolder.files.size)
                }
                withMutableFolder {
                    cd("folder_2")
                    assertEquals("/folder/folder_2/", currentPath.path)
                }
                withFolder {
                    assertEquals(1, currentFolder.files.size)
                    assertEquals(0, currentFolder.folders.size)
                }
            }
        }
    }

    @Test
    fun testFindFiles() = runTest {
        withTestFS { interactor ->
            val provider = OneFileSystemProvider(interactor)
            val capture = ReadPriorityCapture(provider)

            capture.captureRead {
                withFolder {
                    val foundFiles = findFiles("*").map { it.node.file.name }.toList().sorted()
                    val expectedFiles =
                        listOf("empty.txt", "file", "file_inner.txt", "strangeF!LE", "empty_file").sorted()
                    assertContentEquals(expectedFiles, foundFiles)
                }
                withFolder {
                    val foundFiles = findFiles("**/*.txt").map { it.node.file.name }.toList().sorted()
                    val expectedFiles = listOf("empty.txt", "file_inner.txt").sorted()
                    assertContentEquals(expectedFiles, foundFiles)
                }
                withFolder {
                    val foundFiles = findFiles("*", recursive = false).map { it.node.file.name }.toList().sorted()
                    val expectedFiles = listOf("empty.txt", "file").sorted()
                    assertContentEquals(expectedFiles, foundFiles)
                }
                withMutableFolder {
                    cd("empty_folder")
                }
                withFolder {
                    val foundFiles = findFiles("*").toList()
                    assert(foundFiles.isEmpty())
                }
                withMutableFolder {
                    back()
                }
                withFolder {
                    val foundFiles = findFiles("**/folder*/*").map { it.path.path }.toList().sorted()
                    val expectedFiles = listOf(
                        "/folder/file_inner.txt", "/folder/strangeF!LE", "/folder/folder_2/empty_file"
                    ).sorted()
                    assertContentEquals(expectedFiles, foundFiles)
                }
            }

        }
    }

    @Test
    fun testRead() = runTest {
        withTestFS { interactor ->
            val provider = OneFileSystemProvider(interactor)
            val capture = ReadPriorityCapture(provider)

            capture.captureRead {
                withFolder {
                    assert(readFile("empty.txt").isEmpty())
                    assertEquals("This is file!", readFile("file").decodeToString())
                }
                withMutableFolder {
                    cd("folder")
                    val lines = readFile("strangeF!LE").decodeToString().lines()
                    val expectedLines = listOf(
                        "", "\ts", "t\tr", "\ta", "g\t\t\te", "", "", "\t"
                    )
                    assertEquals(expectedLines, lines)
                }
            }
        }
    }

    @Test
    fun testValidate() = runTest {
        withTestFS { interactor ->
            val provider = OneFileSystemProvider(interactor)
            val capture = ReadPriorityCapture(provider)

            capture.captureRead {
                withFolder {
                    assert(validate())
                }
                withMutableFolder {
                    cd("folder/folder_2")
                    currentFolder.files.first().apply {
                        file.data = ByteString.copyFromUtf8("Unexpected text")
                    }
                    cd("/")
                }
                withFolder {
                    assertFalse(validate())
                }
            }
        }
    }

    @Test
    fun testCreate() = runTest {
        withTestFS { interactor ->
            val provider = OneFileSystemProvider(interactor)
            val capture = ReadPriorityCapture(provider)

            capture.captureWrite {
                withMutableFolder {
                    createFile("empty_2.txt")
                }
                withFolder {
                    assert(findFiles("**/empty_2.txt", recursive = false).toList().isNotEmpty())
                }
                withMutableFolder {
                    assertEquals(2, currentFolder.folders.size)
                    createFolder("new_folder")
                    assertEquals(3, currentFolder.folders.size)
                    cd("new_folder")
                    assertEquals("/new_folder/", currentPath.path)
                }
            }

            val fileSystem = interactor.getFileSystem()
            assertEquals(3, fileSystem.folders.size)
            assertEquals(3, fileSystem.files.size)
        }
    }

    @Test
    fun testDelete() = runTest {
        withTestFS { interactor ->
            val provider = OneFileSystemProvider(interactor)
            val capture = ReadPriorityCapture(provider)

            capture.captureWrite {
                withMutableFolder {
                    deleteFile("empty.txt")
                }
                withFolder {
                    assert(findFiles("**/empty.txt", recursive = false).toList().isEmpty())
                }
                withMutableFolder {
                    assertEquals(2, currentFolder.folders.size)
                    deleteFolder("folder")
                    assertEquals(1, currentFolder.folders.size)
                    try {
                        cd("folder")
                        assert(false)
                    } catch (_: DirectoryNotFound) {
                    }
                }
            }

            val fileSystem = interactor.getFileSystem()
            assertEquals(1, fileSystem.folders.size)
            assertEquals(1, fileSystem.files.size)
        }
    }

    @Test
    fun testMove() = runTest {
        withTestFS { interactor ->
            val provider = OneFileSystemProvider(interactor)
            val capture = ReadPriorityCapture(provider)

            capture.captureWrite {
                withMutableFolder {
                    moveFile("file", "empty_folder/")
                    assertEquals(1, currentFolder.files.size)

                    cd("empty_folder")
                    assertEquals(1, currentFolder.files.size)
                    assertEquals("This is file!", readFile("file").decodeToString())

                    back()

                    moveFile("empty.txt", "empty.empty")
                    assertEquals(1, currentFolder.files.size)
                    assert(readFile("empty.empty").isEmpty())

                    moveFolder("empty_folder", "folder/not_empty_folder")
                    assertEquals(1, currentFolder.folders.size)

                    moveFolder("folder", "renamed_folder")
                    assertEquals(1, currentFolder.folders.size)

                    cd("renamed_folder")
                    assertEquals(3, currentFolder.folders.size)

                    moveFolder("empty_folder_2", "/")
                    assertEquals(2, currentFolder.folders.size)

                    cd("not_empty_folder")
                    assertEquals(1, currentFolder.files.size)

                    cd("/")
                    assertEquals(2, currentFolder.folders.size)

                    assert(validate())
                }
            }

            val fileSystem = interactor.getFileSystem()
            assertEquals(1, fileSystem.files.size)
            assertEquals(2, fileSystem.folders.size)
            assertEquals(0, fileSystem.folders.first { it.folder.name == "empty_folder_2" }.files.size)
            assertEquals(2, fileSystem.folders.first { it.folder.name == "renamed_folder" }.folders.size)
        }
    }

    @Test
    fun testCopy() = runTest {
        withTestFS { interactor ->
            val provider = OneFileSystemProvider(interactor)
            val capture = ReadPriorityCapture(provider)

            capture.captureWrite {
                withMutableFolder {
                    copyFile("file", "empty_folder/")
                    assertEquals(2, currentFolder.files.size)

                    cd("empty_folder")
                    assertEquals(1, currentFolder.files.size)
                    assertEquals("This is file!", readFile("file").decodeToString())

                    back()

                    copyFile("empty.txt", "empty.empty")
                    assertEquals(3, currentFolder.files.size)
                    assert(readFile("empty.empty").isEmpty())

                    copyFolder("empty_folder", "folder/not_empty_folder")
                    assertEquals(2, currentFolder.folders.size)

                    cd("folder")
                    assertEquals(3, currentFolder.folders.size)

                    try {
                        copyFolder("empty_folder_2", "/empty_folder", override = false)
                        assert(false)
                    } catch (_: DirectoryAlreadyExists) {
                    }
                    copyFolder("empty_folder_2", "/empty_folder", override = true)
                    assertEquals(3, currentFolder.folders.size)

                    cd("not_empty_folder")
                    assertEquals(1, currentFolder.files.size)

                    cd("/")
                    assertEquals(2, currentFolder.folders.size)

                    assert(validate())
                }
            }

            val fileSystem = interactor.getFileSystem()
            assertEquals(3, fileSystem.files.size)
            assertEquals(2, fileSystem.folders.size)
            assertEquals(0, fileSystem.folders.first { it.folder.name == "empty_folder" }.files.size)
            assertEquals(3, fileSystem.folders.first { it.folder.name == "folder" }.folders.size)
        }
    }

    @Test
    fun testWrite() = runTest {
        withTestFS { interactor ->
            val provider = OneFileSystemProvider(interactor)
            val capture = ReadPriorityCapture(provider)

            capture.captureWrite {
                withMutableFolder {
                    writeIntoFile("empty.txt", "Some text".toByteArray())
                    appendIntoFile("file", "\nJust appended text".toByteArray())
                }
                withFolder {
                    assertEquals("Some text", readFile("empty.txt").decodeToString())
                    assertEquals("This is file!\nJust appended text", readFile("file").decodeToString())
                    assert(validate())
                }
                withMutableFolder {
                    writeIntoFile("file", "not ".toByteArray(), begin = 8, end = 8)
                    writeIntoFile("file", ByteArray(0), begin = 17)
                    assert(validate())
                }
                withFolder {
                    assertEquals("This is not file!", readFile("file").decodeToString())
                }
            }

            val fileSystem = interactor.getFileSystem()
            fileSystem.files.forEach {
                if (it.file.name == "file") {
                    assertEquals("This is not file!", it.file.data.toStringUtf8())
                } else if (it.file.name == "empty.txt") {
                    assertEquals("Some text", it.file.data.toStringUtf8())
                }
            }

        }
    }

    @Test
    fun testImport() = runTest {
        withTestFS { interactor ->
            val provider = OneFileSystemProvider(interactor)
            val capture = ReadPriorityCapture(provider)
            val importer = SystemImporter()
            val testDirPath = OneFileSystemProviderTest::class.java.getResource("/testDir")!!.toURI().toPath()

            capture.captureWrite {
                withMutableFolder {
                    importFile("imported_file") {
                        importer.importFile(testDirPath / "testFile.txt")
                    }
                    assertEquals(3, currentFolder.files.size)
                    assertEquals("Hello, that's a test file!", readFile("imported_file").decodeToString())

                    importDirectory("./") {
                        importer.importFolder(testDirPath)
                    }
                    assertEquals(3, currentFolder.folders.size)

                    cd("testDir")
                    assertEquals(1, currentFolder.files.size)
                    assertEquals(1, currentFolder.folders.size)

                    cd("inner")
                    assertEquals(1, currentFolder.files.size)
                    assertContentEquals(
                        listOf("Inner file", "Ho-ho-ho", "end."),
                        readFile("innerFile").decodeToString().lines()
                    )
                }
            }

            val fileSystem = interactor.getFileSystem()
            assertEquals(3, fileSystem.files.size)
            assertEquals(3, fileSystem.folders.size)
            assertEquals(1, fileSystem.folders.first { it.folder.name == "testDir" }.files.size)

        }
    }

    @Test
    fun testOverride() = runTest {
        withTestFS { interactor ->
            val resultString = "Hello, This is file!"

            val provider = OneFileSystemProvider(interactor)
            val capture = ReadPriorityCapture(provider)

            capture.captureWrite {
                withMutableFolder {
                    writeIntoFile("file", "Hello, ".toByteArray(), begin = 0, end = 0)
                    assertEquals(resultString, readFile("file").decodeToString())
                }
            }

            val sizeWithoutOptimize = fsPath.fileSize()

            capture.captureWrite {
                withMutableFolder {
                    optimize()
                    assertEquals(resultString, readFile("file").decodeToString())
                }
            }

            val sizeWithOptimize = fsPath.fileSize()
            assert(sizeWithOptimize < sizeWithoutOptimize)

            val fileSystem = interactor.getFileSystem()
            assertEquals(resultString, fileSystem.files.first { it.file.name == "file" }.file.data.toStringUtf8())
        }
    }

    companion object {
        val fsPath = Path(System.getProperty("java.io.tmpdir"), "testOneFS")

        private fun MutableFolderNode.addFiles(vararg files: Pair<String, String>) {
            this.files.addAll(files.map { (name, data) ->
                MutableFileNode(File.newBuilder().apply {
                    this.name = name
                    this.data = ByteString.copyFromUtf8(data)
                    this.md5 = this.data.toByteArray().computeMD5()
                }, parent = this)
            })
        }

        private fun MutableFolderNode.addFolder(name: String, init: MutableFolderNode.() -> Unit = {}) {
            this.folders.add(
                MutableFolderNode(Folder.newBuilder().apply { this.name = name }, parent = this).apply(init)
            )
        }

        val testRoot = MutableFolderNode(Folder.newBuilder().apply { name = "" }).apply {
            addFiles(
                "empty.txt" to "",
                "file" to "This is file!",
            )
            addFolder("empty_folder")
            addFolder("folder") {
                addFiles(
                    "file_inner.txt" to "This is inner file.",
                    "strangeF!LE" to "\n\ts\nt\tr\n\ta\ng\t\t\te\n\n\n\t"
                )
                addFolder("empty_folder_2")
                addFolder("folder_2") {
                    addFiles(
                        "empty_file" to ""
                    )
                }
            }
        }.immutableFolder()
    }
}
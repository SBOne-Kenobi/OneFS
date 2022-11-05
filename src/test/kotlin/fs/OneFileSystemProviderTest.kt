package fs

import capturing.impl.ReadPriorityCapture
import fs.entity.DirectoryAlreadyExists
import fs.entity.DirectoryNotFound
import fs.entity.FSPath
import fs.importer.SystemImporter
import fs.interactor.FSInteractor
import fs.interactor.InteractorInterface
import java.io.RandomAccessFile
import kotlin.io.path.Path
import kotlin.io.path.div
import kotlin.io.path.toPath
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class OneFileSystemProviderTest {

    private inline fun withTestFS(block: (InteractorInterface) -> Unit) {
        try {
            fsBuilder.build(fsPath)
            val interactor = FSInteractor(fsPath)
            block(interactor)
        } finally {
            fsPath.toFile().delete()
        }
    }

    @Test
    fun testNavigation() = runTest {
        withTestFS { interactor ->
            val provider = OneFileSystemProvider(interactor)
            val capture = ReadPriorityCapture(provider)

            capture.captureRead {
                withFolder {
                    assertEquals("/", currentPath.pathString)
                    assertEquals(2, currentFolder.files.size)
                    assertEquals(2, currentFolder.folders.size)
                }
                withMutableFolder {
                    try {
                        cd("lol")
                        assert(false)
                    } catch (_: DirectoryNotFound) {
                    }
                    assertEquals("/", currentPath.pathString)

                    cd("empty_folder")
                    assertEquals("/empty_folder/", currentPath.pathString)
                }
                withFolder {
                    assertEquals(0, currentFolder.files.size)
                    assertEquals(0, currentFolder.folders.size)
                }
                withMutableFolder {
                    cd("/folder/empty_folder_2")
                    assertEquals("/folder/empty_folder_2/", currentPath.pathString)

                    back()
                    assertEquals("/folder/", currentPath.pathString)

                    cd("/empty_folder")
                    assertEquals("/empty_folder/", currentPath.pathString)

                    cd("/")
                    assertEquals("/", currentPath.pathString)

                    cd("folder/empty_folder_2/")
                    assertEquals("/folder/empty_folder_2/", currentPath.pathString)
                }
                withFolder {
                    assertEquals(0, currentFolder.files.size)
                    assertEquals(0, currentFolder.folders.size)
                }
                withMutableFolder {
                    back()
                    assertEquals("/folder/", currentPath.pathString)
                }
                withFolder {
                    assertEquals(2, currentFolder.folders.size)
                    assertEquals(2, currentFolder.files.size)
                }
                withMutableFolder {
                    cd("folder_2")
                    assertEquals("/folder/folder_2/", currentPath.pathString)
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
                    val foundFiles = findFiles("*").map { it.name }.toList().sorted()
                    val expectedFiles =
                        listOf("empty.txt", "file", "file_inner.txt", "strangeF!LE", "empty_file").sorted()
                    assertContentEquals(expectedFiles, foundFiles)
                }
                withFolder {
                    val foundFiles = findFiles("**/*.txt").map { it.name }.toList().sorted()
                    val expectedFiles = listOf("empty.txt", "file_inner.txt").sorted()
                    assertContentEquals(expectedFiles, foundFiles)
                }
                withFolder {
                    val foundFiles = findFiles("*", recursive = false).map { it.name }.toList().sorted()
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
                    val foundFiles = findFiles("**/folder*/*").map { it.path.pathString }.toList().sorted()
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
                    val lines = inputStream("strangeF!LE").readBytes().decodeToString().lines()
                    val expectedLines = listOf(
                        "", "\ts", "t\tr", "\ta", "g\t\t\te", "", "", "\t"
                    )
                    assertEquals(expectedLines, lines)
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
                    assertEquals("/new_folder/", currentPath.pathString)
                }
            }

            val fileSystem = interactor.getFolderLoader(FSPath("/")).load()
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

            val fileSystem = interactor.getFolderLoader(FSPath("/")).load()
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

            val fileSystem = interactor.getFolderLoader(FSPath("/")).load()
            assertEquals(1, fileSystem.files.size)
            assertEquals(2, fileSystem.folders.size)
            assertEquals(0, fileSystem.folders.first { it.name == "empty_folder_2" }.load().files.size)
            assertEquals(2, fileSystem.folders.first { it.name == "renamed_folder" }.load().folders.size)
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

            val fileSystem = interactor.getFolderLoader(FSPath("/")).load()
            assertEquals(3, fileSystem.files.size)
            assertEquals(2, fileSystem.folders.size)
            assertEquals(0, fileSystem.folders.first { it.name == "empty_folder" }.load().files.size)
            assertEquals(3, fileSystem.folders.first { it.name == "folder" }.load().folders.size)
        }
    }

    @Test
    fun testWrite() = runTest {
        withTestFS { interactor ->
            val provider = OneFileSystemProvider(interactor)
            val capture = ReadPriorityCapture(provider)

            capture.captureWrite {
                withMutableFolder {
                    outputStream("empty.txt").use {
                        it.write("Some text".encodeToByteArray())
                    }
                    appendIntoFile("file", "\nJust appended text".toByteArray())
                }
                withFolder {
                    assertEquals("Some text", readFile("empty.txt").decodeToString())
                    assertEquals("This is file!\nJust appended text", readFile("file").decodeToString())
                    assertFalse(validate())
                }
                withMutableFolder {
                    updateMD5("file")
                    updateMD5("empty.txt")
                    assert(validate())
                }
                withMutableFolder {
                    clearFile("file")
                    outputStream("file").use {
                        it.write("This is file.".encodeToByteArray())
                    }
                    outputStream("file", offset = 8).use {
                        it.write("FILE".encodeToByteArray())
                    }
                    updateMD5("file")
                    assert(validate())
                }
                withFolder {
                    assertEquals("This is FILE.", readFile("file").decodeToString())
                }
            }

            val raf = RandomAccessFile(fsPath.toFile(), "r")

            interactor.getDataCell(FSPath("/file")).use { dataCell ->
                val pointer = dataCell.controller.dataPointer
                raf.seek(pointer.beginPosition)
                val content = ByteArray(pointer.dataLength.toInt())
                raf.readFully(content)
                assertEquals("This is FILE.", content.decodeToString())
            }

            interactor.getDataCell(FSPath("/empty.txt")).use { dataCell ->
                val pointer = dataCell.controller.dataPointer
                raf.seek(pointer.beginPosition)
                val content = ByteArray(pointer.dataLength.toInt())
                raf.readFully(content)
                assertEquals("Some text", content.decodeToString())
            }

            raf.close()
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
                    importFile("./", importer, testDirPath / "testFile.txt")
                    assertEquals(3, currentFolder.files.size)
                    assertEquals("Hello, that's a test file!", readFile("testFile.txt").decodeToString())

                    importDirectory("./", importer, testDirPath)
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
                    assert(validate())
                }
            }

            val fileSystem = interactor.getFolderLoader(FSPath("/")).load()
            assertEquals(3, fileSystem.files.size)
            assertEquals(3, fileSystem.folders.size)
            assertEquals(1, fileSystem.folders.first { it.name == "testDir" }.load().files.size)

        }
    }

    companion object {
        val fsPath = Path(System.getProperty("java.io.tmpdir"), "testOneFS")

        private fun FolderBuilder.addFiles(vararg files: Pair<String, String>) {
            fileBuilders.addAll(files.map { (name, data) ->
                FileBuilder().apply {
                    this.fileName = name
                    this.data = data.encodeToByteArray()
                    this.md5 = this.data.computeMD5()
                }
            })
        }

        private fun FolderBuilder.addFolder(name: String, init: FolderBuilder.() -> Unit = {}) {
            folderBuilders.add(
                FolderBuilder().apply {
                    this.folderName = name
                    init()
                }
            )
        }

        val fsBuilder = FSBuilder().apply {
            root.apply {
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
            }
        }
    }
}
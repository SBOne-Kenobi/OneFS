package fs

import capturing.impl.ReadPriorityCapture
import com.google.protobuf.ByteString
import kotlin.io.path.Path
import kotlin.io.path.deleteExisting
import kotlin.io.path.div
import kotlin.io.path.outputStream
import kotlin.io.path.toPath
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class OneFileSystemProviderTest {

    inline fun withTestFS(block: () -> Unit) {
        try {
            testFileSystem.writeTo(fsPath.outputStream())
            block()
        } finally {
            fsPath.deleteExisting()
        }
    }

    @Test
    fun testNavigation() = runTest { withTestFS {
        val provider = OneFileSystemProvider(fsPath)
        val capture = ReadPriorityCapture(provider)

        capture.captureRead {
            withFolder {
                assertEquals("/", currentPathString)
                assertEquals(2, currentFolder.filesCount)
                assertEquals(2, currentFolder.foldersCount)
            }
            withMutableFolder {
                try {
                    cd("lol")
                    assert(false)
                } catch (_: DirectoryNotFound) {
                }
                assertEquals("/", currentPathString)

                cd("empty_folder")
                assertEquals("/empty_folder/", currentPathString)
            }
            withFolder {
                assertEquals(0, currentFolder.filesCount)
                assertEquals(0, currentFolder.foldersCount)
            }
            withMutableFolder {
                cd("/folder/empty_folder_2")
                assertEquals("/folder/empty_folder_2/", currentPathString)

                back()
                assertEquals("/folder/", currentPathString)

                cd("/empty_folder")
                assertEquals("/empty_folder/", currentPathString)

                cd("/")
                assertEquals("/", currentPathString)

                cd("folder/empty_folder_2/")
                assertEquals("/folder/empty_folder_2/", currentPathString)
            }
            withFolder {
                assertEquals(0, currentFolder.filesCount)
                assertEquals(0, currentFolder.foldersCount)
            }
            withMutableFolder {
                back()
                assertEquals("/folder/", currentPathString)
            }
            withFolder {
                assertEquals(2, currentFolder.foldersCount)
                assertEquals(2, currentFolder.filesCount)
            }
            withMutableFolder {
                cd("folder_2")
                assertEquals("/folder/folder_2/", currentPathString)
            }
            withFolder {
                assertEquals(1, currentFolder.filesCount)
                assertEquals(0, currentFolder.foldersCount)
            }
        }
    }}

    @Test
    fun testFindFiles() = runTest { withTestFS {
        val provider = OneFileSystemProvider(fsPath)
        val capture = ReadPriorityCapture(provider)

        capture.captureRead {
            withFolder {
                val foundFiles = findFiles("*").map { it.file.name }.toList().sorted()
                val expectedFiles = listOf("empty.txt", "file", "file_inner.txt", "strangeF!LE", "empty_file").sorted()
                assertContentEquals(expectedFiles, foundFiles)
            }
            withFolder {
                val foundFiles = findFiles("**/*.txt").map { it.file.name }.toList().sorted()
                val expectedFiles = listOf("empty.txt", "file_inner.txt").sorted()
                assertContentEquals(expectedFiles, foundFiles)
            }
            withFolder {
                val foundFiles = findFiles("*", recursive = false).map { it.file.name }.toList().sorted()
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
                val foundFiles = findFiles("**/folder*/*").map { it.absolutePath }.toList().sorted()
                val expectedFiles = listOf(
                    "/folder/file_inner.txt", "/folder/strangeF!LE", "/folder/folder_2/empty_file"
                ).sorted()
                assertContentEquals(expectedFiles, foundFiles)
            }
        }

    }}

    @Test
    fun testRead() = runTest { withTestFS {
        val provider = OneFileSystemProvider(fsPath)
        val capture = ReadPriorityCapture(provider)

        capture.captureRead {
            withFolder {
                assert(readFile("empty.txt").isEmpty())
                assertEquals("This is file!", readFile("file").decodeToString())
            }
            withMutableFolder {
                cd("folder")
                val reader = fileInputStream("strangeF!LE").bufferedReader()
                val expectedLines = listOf(
                    "", "\ts", "t\tr", "\ta", "g\t\t\te", "", "", "\t"
                )
                assertEquals(expectedLines, reader.readLines())
            }
        }
    }}

    @Test
    fun testValidate() = runTest { withTestFS {
        val provider = OneFileSystemProvider(fsPath)
        val capture = ReadPriorityCapture(provider)

        capture.captureRead {
            withFolder {
                assert(validate())
            }
            withMutableFolder {
                cd("folder/folder_2")
                currentFolderBuilder.filesBuilderList.first().apply {
                    data = ByteString.copyFromUtf8("Unexpected text")
                }
                cd("/")
            }
            withFolder {
                assertFalse(validate())
            }
        }
    }}

    @Test
    fun testCreate() = runTest { withTestFS {
        val provider = OneFileSystemProvider(fsPath)
        val capture = ReadPriorityCapture(provider)

        capture.captureWrite {
            withMutableFolder {
                createFile("empty_2.txt")
            }
            withFolder {
                assert(findFiles("**/empty_2.txt", recursive = false).toList().isNotEmpty())
            }
            withMutableFolder {
                assertEquals(2, currentFolder.foldersCount)
                createFolder("new_folder")
                assertEquals(3, currentFolder.foldersCount)
                cd("new_folder")
                assertEquals("/new_folder/", currentPathString)
            }
        }

        var fileSystem = fsPath.toFileSystem()
        assertNotEquals(3, fileSystem.root.foldersCount)
        assertNotEquals(3, fileSystem.root.filesCount)

        capture.captureWrite {
            withMutableFolder {
                commit()
            }
        }

        fileSystem = fsPath.toFileSystem()
        assertEquals(3, fileSystem.root.foldersCount)
        assertEquals(3, fileSystem.root.filesCount)
    }}

    @Test
    fun testDelete() = runTest { withTestFS {
        val provider = OneFileSystemProvider(fsPath)
        val capture = ReadPriorityCapture(provider)

        capture.captureWrite {
            withMutableFolder {
                deleteFile("empty.txt")
            }
            withFolder {
                assert(findFiles("**/empty.txt", recursive = false).toList().isEmpty())
            }
            withMutableFolder {
                assertEquals(2, currentFolder.foldersCount)
                deleteFolder("folder")
                assertEquals(1, currentFolder.foldersCount)
                try {
                    cd("folder")
                    assert(false)
                } catch (_: DirectoryNotFound) {}
            }
        }

        var fileSystem = fsPath.toFileSystem()
        assertNotEquals(1, fileSystem.root.foldersCount)
        assertNotEquals(1, fileSystem.root.filesCount)

        capture.captureWrite {
            withMutableFolder {
                commit()
            }
        }

        fileSystem = fsPath.toFileSystem()
        assertEquals(1, fileSystem.root.foldersCount)
        assertEquals(1, fileSystem.root.filesCount)
    }}

    @Test
    fun testMove() = runTest { withTestFS {
        val provider = OneFileSystemProvider(fsPath)
        val capture = ReadPriorityCapture(provider)

        capture.captureWrite {
            withMutableFolder {
                moveFile("file", "empty_folder/")
                assertEquals(1, currentFolderBuilder.filesCount)

                cd("empty_folder")
                assertEquals(1, currentFolderBuilder.filesCount)
                assertEquals("This is file!", readFile("file").decodeToString())

                back()

                moveFile("empty.txt", "empty.empty")
                assertEquals(1, currentFolderBuilder.filesCount)
                assert(readFile("empty.empty").isEmpty())

                moveFolder("empty_folder", "folder/not_empty_folder")
                assertEquals(1, currentFolderBuilder.foldersCount)

                moveFolder("folder", "renamed_folder")
                assertEquals(1, currentFolderBuilder.foldersCount)

                cd("renamed_folder")
                assertEquals(3, currentFolderBuilder.foldersCount)

                moveFolder("empty_folder_2", "/")
                assertEquals(2, currentFolder.foldersCount)

                cd("not_empty_folder")
                assertEquals(1, currentFolder.filesCount)

                cd("/")
                assertEquals(2, currentFolder.foldersCount)

                assert(validate())

                commit()
            }
        }

        val fileSystem = fsPath.toFileSystem()
        assertEquals(1, fileSystem.root.filesCount)
        assertEquals(2, fileSystem.root.foldersCount)
        assertEquals(0, fileSystem.root.foldersList.first { it.name == "empty_folder_2" }.filesCount)
        assertEquals(2, fileSystem.root.foldersList.first { it.name == "renamed_folder" }.foldersCount)
    }}

    @Test
    fun testCopy() = runTest { withTestFS {
        val provider = OneFileSystemProvider(fsPath)
        val capture = ReadPriorityCapture(provider)

        capture.captureWrite {
            withMutableFolder {
                copyFile("file", "empty_folder/")
                assertEquals(2, currentFolderBuilder.filesCount)

                cd("empty_folder")
                assertEquals(1, currentFolderBuilder.filesCount)
                assertEquals("This is file!", readFile("file").decodeToString())

                back()

                copyFile("empty.txt", "empty.empty")
                assertEquals(3, currentFolderBuilder.filesCount)
                assert(readFile("empty.empty").isEmpty())

                copyFolder("empty_folder", "folder/not_empty_folder")
                assertEquals(2, currentFolderBuilder.foldersCount)

                cd("folder")
                assertEquals(3, currentFolderBuilder.foldersCount)

                try {
                    copyFolder("empty_folder_2", "/empty_folder", override = false)
                    assert(false)
                } catch (_: DirectoryAlreadyExists) {}
                copyFolder("empty_folder_2", "/empty_folder", override = true)
                assertEquals(3, currentFolder.foldersCount)

                cd("not_empty_folder")
                assertEquals(1, currentFolder.filesCount)

                cd("/")
                assertEquals(2, currentFolder.foldersCount)

                assert(validate())

                commit()
            }
        }

        val fileSystem = fsPath.toFileSystem()
        assertEquals(3, fileSystem.root.filesCount)
        assertEquals(2, fileSystem.root.foldersCount)
        assertEquals(0, fileSystem.root.foldersList.first { it.name == "empty_folder" }.filesCount)
        assertEquals(3, fileSystem.root.foldersList.first { it.name == "folder" }.foldersCount)
    }}

    @Test
    fun testWrite() = runTest { withTestFS {
        val provider = OneFileSystemProvider(fsPath)
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
            val bytes = Random.Default.nextBytes(10)
            withMutableFolder {
                fileOutputStream("file").use {
                    it.write(bytes)
                }
            }
            withFolder {
                assertContentEquals(bytes, readFile("file"))
            }
        }

    }}

    @Test
    fun testImport() = runTest { withTestFS {
        val provider = OneFileSystemProvider(fsPath)
        val capture = ReadPriorityCapture(provider)
        val importer = SystemImporter()
        val testDirPath = OneFileSystemProviderTest::class.java.getResource("/testDir").toURI().toPath()

        capture.captureWrite {
            withMutableFolder {
                importFile("imported_file") {
                    importer.importFile(testDirPath / "testFile.txt")
                }
                assertEquals(3, currentFolderBuilder.filesCount)
                assertEquals("Hello, that's a test file!", readFile("imported_file").decodeToString())

                importDirectory(".") {
                    importer.importFolder(testDirPath)
                }
                assertEquals(3, currentFolderBuilder.foldersCount)

                cd("testDir")
                assertEquals(1, currentFolderBuilder.filesCount)
                assertEquals(1, currentFolderBuilder.foldersCount)

                cd("inner")
                assertEquals(1, currentFolderBuilder.filesCount)
                assertContentEquals(
                    listOf("Inner file", "Ho-ho-ho", "end."),
                    fileInputStream("innerFile").bufferedReader().readLines()
                )
            }
        }

    }}

    companion object {
        val fsPath = Path(System.getProperty("java.io.tmpdir"), "testOneFS")

        private fun Folder.Builder.addFiles(vararg files: Pair<String, String>) {
            files.forEach { (name, data) ->
                addFilesBuilder().apply {
                    this.name = name
                    this.data = ByteString.copyFromUtf8(data)
                    this.md5 = computeMD5()
                }
            }
        }

        val testFileSystem = FileSystem.newBuilder().apply {
            rootBuilder.apply {
                name = ""
                addFiles(
                    "empty.txt" to "",
                    "file" to "This is file!",
                )
                addFoldersBuilder().apply {
                    name = "empty_folder"
                }
                addFoldersBuilder().apply {
                    name = "folder"
                    addFiles(
                        "file_inner.txt" to "This is inner file.",
                        "strangeF!LE" to "\n\ts\nt\tr\n\ta\ng\t\t\te\n\n\n\t"
                    )
                    addFoldersBuilder().apply {
                        name = "empty_folder_2"
                    }
                    addFoldersBuilder().apply {
                        name = "folder_2"
                        addFiles(
                            "empty_file" to ""
                        )
                    }
                }
            }
        }.build()
    }

}
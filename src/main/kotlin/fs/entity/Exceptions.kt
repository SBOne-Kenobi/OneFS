package fs.entity

open class OneFileSystemException(message: String? = null, cause: Throwable? = null) : Exception(message, cause)

interface PathNotFound
class DirectoryNotFound(path: String) : PathNotFound, OneFileSystemException("Directory $path doesn't found")
class FileNotFound(path: String) : PathNotFound, OneFileSystemException("File $path doesn't found")

interface AlreadyExists
class DirectoryAlreadyExists(path: String) : AlreadyExists, OneFileSystemException("Directory $path already exists")
class FileAlreadyExists(path: String) : AlreadyExists, OneFileSystemException("File $path already exists")

package fs.entity

import fs.toPathSequence

data class FSPath(
    val pathString: String,
    val pathList: List<String> = pathString.toPathSequence().toList()
)

val FSPath.name: String
    get() = pathList.lastOrNull() ?: "."

fun FSPath.add(node: FSNode): FSPath =
    when (node) {
        is FileNode -> addFile(node.fileName)
        is FolderNode -> addFolder(node.folderName)
    }

fun FSPath.addFile(name: String): FSPath {
    return FSPath(pathString + name, pathList + name)
}

fun FSPath.addFolder(name: String): FSPath {
    return FSPath("$pathString$name/", pathList + name)
}

fun FSPath.removeLast(): FSPath =
    if (pathList.isNotEmpty()) {
        FSPath(
            pathString.trimEnd('/').substringBeforeLast(delimiter = '/', missingDelimiterValue = ".") + '/',
            pathList.dropLast(1)
        )
    } else {
        this
    }

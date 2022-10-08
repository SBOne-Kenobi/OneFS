package fs.entity

import fs.toPathSequence


sealed interface FSPathInterface {
    val path: String
    val pathList: List<String>
}

data class FSPath(
    override val path: String,
    override val pathList: List<String> = path.toPathSequence().toList()
) : FSPathInterface {
    constructor() : this("/", emptyList())
}

data class MutableFSPath(
    override var path: String,
    override val pathList: MutableList<String> = path.toPathSequence().toMutableList()
) : FSPathInterface {
    constructor() : this("/", mutableListOf())
}


data class NodeWithPath<out T: FSNodeInterface>(val node: T, val path: MutableFSPath)


val FSPathInterface.name: String
    get() = pathList.lastOrNull() ?: "."

fun FSPathInterface.mutable() = when (this) {
    is MutableFSPath -> this.copy(pathList = pathList.toMutableList())
    is FSPath -> MutableFSPath(path, pathList.toMutableList())
}

fun FSPathInterface.immutable() = when (this) {
    is FSPath -> this.copy()
    is MutableFSPath -> FSPath(path, pathList.toList())
}

fun MutableFSPath.add(node: FSNodeInterface): MutableFSPath {
    val (name, postfix) = when (node) {
        is FileNodeInterface -> node.fileName to ""
        is FolderNodeInterface -> node.folderName to "/"
    }
    path += (name + postfix)
    pathList.add(name)
    return this
}

fun MutableFSPath.removeLast(): MutableFSPath {
    if (pathList.isNotEmpty()) {
        pathList.removeLast()
        path = path
            .trimEnd('/')
            .substringBeforeLast(delimiter = '/', missingDelimiterValue = ".") + '/'
    }
    return this
}

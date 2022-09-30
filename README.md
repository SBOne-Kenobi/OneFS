# OneFileSystem

This project is a FileSystem that stores all information about self in one file.

## Implementation details
- Created interface `AccessCapture` in package `capturing` for multithread working with readers and writers with different strategies.
- There are some default strategies described in package `capturing.impl`, but also there is possibility of creation your own strategies. 
- Storage format of FileSystem using protobuf.
- Storage format is a sequence of change events that lets efficient add a lot of new mutations.
- Also, created interface `InteractorInterface` for generating a sequence of change messages.

### Future improvement
- Encapsulate working with protobuf for better practices.
- Provide easy-to-use interface for working with OneFileSystem.

## Usage
Create OneFileSystem provider and choose strategy for working with FS.
```kotlin 
val interactor = FSInteractor(fileSystemPath)
val provider = OneFileSystemProvider(interactor)
val capture = ReadPriorityCapture(provider)
```
Then we can use navigation and some useful functions for working with FS. The list of available functions depends on type of capturing.
```kotlin 
capture.captureWrite {
    withMutableFolder {
        createFolder("new_folder") // create new folder
        cd("new_folder") // go into it
        
        createFile("new_file") // create new file
        
        writeIntoFile("new_file", "This is Content".toByteArray()) // write some content
        // result content is "This is Content"
        writeIntoFile("new_file", "not c".toByteArray(), begin = 8, end = 9) // change this content
        // result content is "This is not content"
        
        optimize() // Rewrite file system's file optmize number of generated messages 
    }
}

capture.captureRead {
    withMutableFolder {
        back() // go to parent (root) folder
    }
    withFolder {
        // find and print all file's names
        println(findFiles().toList().joinToString() {
            it.name
        })
    }
}
```
For more usage examples look at tests `fs.OneFileSystemProviderTest`.

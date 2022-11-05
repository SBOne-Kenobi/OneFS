# OneFileSystem

This project is a FileSystem that stores all information about self in one file.

## Implementation details
- Created interface `AccessCapture` in package `capturing` for multithread working with readers and writers with different strategies.
- There are some default strategies described in package `capturing.impl`, but also there is possibility of creation your own strategies. 
- Storage format of FileSystem is described in `fs/entity/FSFormat.kt`.
- Storage format is sequence of files and folders records with pointers to each other.
- Created interface `InteractorInterface` for working with records and overrides them.

### Future improvement
- Provide easy-to-use interface for working with OneFileSystem.
- Speed up working with FS

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
        
        appendIntoFile("new_file", "This is Content".toByteArray()) // write some content
        // result content is "This is Content"
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

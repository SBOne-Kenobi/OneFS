# OneFileSystem

This project is a FileSystem that stores all information about self in one file.

## Implementation details
- Created interface `AccessCapture` in package `capturing` for multithread working with readers and writers with different strategies.
- There are some default strategies described in package `capturing.impl`, but also there is possibility of creation your own strategies. 
- Storage format of FileSystem is protobuf. So there are some inefficient details such as reading all file system while working with it.

### Future improvement
- Encapsulate working with protobuf for better practices.
- Change storage format from protobuf into something else (maybe custom) that can read and work with file without save it in RAM.
- Provide easy-to-use interface for working with OneFileSystem.

## Usage
Create OneFileSystem provider and choose strategy for working with FS.
```kotlin 
val provider = OneFileSystemProvider(...)
val capture = ReadPriorityCapture(provider)
```
Then we can use navigation and some useful functions for working with FS. The list of available functions depends on type of capturing.
```kotlin 
capture.captureWrite {
    withMutableFolder {
        createFolder("new_folder") // create new folder
        cd("new_folder") // go into it
        
        createFile("new_file") // create new file
        fileOutputStream("new_file").bufferedWriter.use {
            it.append("Content") // write some content
        }
        
        commit() // save changes into filesystem's file
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

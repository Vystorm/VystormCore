package Vystorm.vystorm_core.storage.internal;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;

/** Platform boundary for directory metadata persistence, not file-content persistence. */
public final class DirectorySync {
    private DirectorySync() {}

    /**
     * Force directory entries on providers supporting directory channels (including Linux).
     * The JDK Windows default provider cannot open directories as FileChannels. There we
     * validate the directory, but cannot promise directory-entry persistence on power loss.
     * Callers must still force their file contents and use atomic renames on every platform.
     * Other I/O errors are never suppressed.
     */
    public static void force(Path directory) throws IOException {
        force(directory, directory.getFileSystem() == FileSystems.getDefault()
                && "\\".equals(directory.getFileSystem().getSeparator()));
    }

    static void force(Path directory, boolean windowsDefaultProvider) throws IOException {
        if (!Files.readAttributes(directory, BasicFileAttributes.class).isDirectory()) {
            throw new NotDirectoryException(directory.toString());
        }
        if (windowsDefaultProvider) return;
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }
}

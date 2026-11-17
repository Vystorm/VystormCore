package Vystorm.vystorm_core.storage.internal;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DirectorySyncTest {
    @TempDir Path directory;

    @Test void givenNativeProvider_whenForcingDirectory_thenSucceeds() throws IOException {
        DirectorySync.force(directory);
    }

    @Test void givenWindowsProvider_whenDirectoryExists_thenDoesNotOpenUnsupportedChannel() throws IOException {
        try (var channels = mockStatic(FileChannel.class)) {
            DirectorySync.force(directory, true);
            channels.verifyNoInteractions();
        }
    }

    @Test void givenWindowsProvider_whenDirectoryMissingOrRegularFile_thenFailureIsNotHidden() throws IOException {
        assertThrows(NoSuchFileException.class, () -> DirectorySync.force(directory.resolve("missing"), true));
        Path file = Files.writeString(directory.resolve("file"), "data");
        assertThrows(NotDirectoryException.class, () -> DirectorySync.force(file, true));
    }

    @Test void givenSupportingProvider_whenForcing_thenSyncMetadataAndCloseChannel() throws IOException {
        FileChannel channel = mock(FileChannel.class);
        try (var channels = mockStatic(FileChannel.class)) {
            channels.when(() -> FileChannel.open(directory, StandardOpenOption.READ)).thenReturn(channel);
            DirectorySync.force(directory, false);
            verify(channel).force(true);
            verify(channel).close();
        }
    }

    @Test void givenSupportingProvider_whenSyncFails_thenPropagateFailureAndClose() throws IOException {
        FileChannel channel = mock(FileChannel.class);
        IOException failure = new IOException("disk failure");
        doThrow(failure).when(channel).force(true);
        try (var channels = mockStatic(FileChannel.class)) {
            channels.when(() -> FileChannel.open(directory, StandardOpenOption.READ)).thenReturn(channel);
            assertSame(failure, assertThrows(IOException.class, () -> DirectorySync.force(directory, false)));
            verify(channel).close();
        }
    }
}

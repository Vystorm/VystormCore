package Vystorm.vystorm_core.network.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * Stores the paired network key as Base64 in one file, written atomically and readable only by the owner
 * (POSIX 600, on Windows an ACL with just the owner). A file that cannot be parsed is reported as absent but is never
 * overwritten by {@link #load}; only {@link #save} and {@link #delete} change it.
 *
 * <p>Internal: not part of the public API; may change or disappear in any version.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public final class KeyFile {
    private KeyFile() {}

    public static Optional<NetworkKey> load(Path file) {
        try {
            if (!Files.isRegularFile(file)) return Optional.empty();
            return KeySources.fromBase64(Files.readString(file, StandardCharsets.US_ASCII));
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    public static void save(Path file, NetworkKey key) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path temp = Files.createTempFile(dir, ".network-key", ".tmp");
        try {
            restrict(temp);
            Files.writeString(temp, Base64.getEncoder().encodeToString(key.bytes()), StandardCharsets.US_ASCII);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException noAtomic) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            restrict(file);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    public static void delete(Path file) throws IOException { Files.deleteIfExists(file); }

    private static void restrict(Path file) {
        try {
            PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
            if (posix != null) { posix.setPermissions(PosixFilePermissions.fromString("rw-------")); return; }
            AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
            if (acl != null) {
                UserPrincipal owner = acl.getOwner();
                AclEntry entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                        .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build();
                acl.setAcl(List.of(entry));
            }
        } catch (IOException | RuntimeException unsupported) {
            // Best effort: some file systems cannot restrict permissions. The key is still never logged.
        }
    }
}

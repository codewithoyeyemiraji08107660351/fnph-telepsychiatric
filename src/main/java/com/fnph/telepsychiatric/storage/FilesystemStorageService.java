package com.fnph.telepsychiatric.storage;

import com.fnph.telepsychiatric.common.PublicId;
import com.fnph.telepsychiatric.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Set;

/**
 * Files on a disk, done carefully.
 *
 * <h2>Paths are generated, never taken from the upload</h2>
 *
 * A filename is user input. A file called {@code ../../../etc/passwd} or
 * {@code ..%2f..%2fapplication.yaml} is the oldest attack there is against
 * file storage, and the only reliable defence is never to use the supplied
 * name in a path at all. The original name is kept in the database for display.
 *
 * <h2>Directories are sharded</h2>
 *
 * {@code uploads/2026/09/a7/k2/01ARZ3...}. A single directory holding a
 * hundred thousand files makes every lookup slow on ext4 and makes {@code ls}
 * unusable during an incident, which is exactly when somebody needs it.
 *
 * <h2>Writes are atomic</h2>
 *
 * Written to a temporary file, flushed to the disk, then renamed into place.
 * A crash mid-write otherwise leaves a truncated file that the database says is
 * a complete prescription, and nothing would detect it until a patient
 * presented half a document at a pharmacy.
 *
 * <h2>Checksums are computed from what landed, not what was claimed</h2>
 *
 * The size and hash come from reading the bytes as they are written, not from
 * the upload's headers. A client can say anything about its own upload.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FilesystemStorageService implements StorageService {

    private static final Set<PosixFilePermission> FILE_PERMISSIONS =
            PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
            PosixFilePermissions.fromString("rwx------");

    private final StorageProperties properties;
    private final StorageDeletionRepository deletionRepository;
    private final com.fnph.telepsychiatric.configuration.ConfigurationService configuration;

    @Override
    public StoredObject store(StorageArea area, InputStream content, String originalFilename,
                              String declaredContentType) {

        assertContentTypeAllowed(declaredContentType);

        String relativePath = generatePath(area, originalFilename);
        Path target = resolve(area, relativePath);
        Path temporary = target.resolveSibling(target.getFileName() + ".part");

        try {
            createDirectories(target.getParent());

            long maxBytes = (long) properties.getMaxFileSizeMb() * 1024 * 1024;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long written = 0;

            try (OutputStream out = Files.newOutputStream(temporary,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = content.read(buffer)) != -1) {
                    written += read;
                    if (written > maxBytes) {
                        // Enforced while streaming, not from a header. A client
                        // can claim any size it likes.
                        throw new StorageException(
                                "That file is larger than the %d MB limit."
                                        .formatted(properties.getMaxFileSizeMb()));
                    }
                    digest.update(buffer, 0, read);
                    out.write(buffer, 0, read);
                }
                out.flush();
            }

            if (written == 0) {
                throw new StorageException("That file is empty");
            }

            // Force the bytes to the disk before the rename. Without this the
            // rename can complete while the contents are still in the page
            // cache, and a power loss leaves a correctly named empty file.
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.READ)) {
                channel.force(true);
            }

            setPermissions(temporary);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);

            String checksum = HexFormat.of().formatHex(digest.digest());

            log.info("Stored {} bytes in {}/{}", written, area.directory(), relativePath);
            return new StoredObject(area, relativePath, written, checksum, declaredContentType);

        } catch (StorageException e) {
            deleteQuietly(temporary);
            throw e;
        } catch (Exception e) {
            deleteQuietly(temporary);
            throw new StorageException("Could not store the file", e);
        }
    }

    @Override
    public InputStream read(StorageArea area, String path, String expectedChecksum) {
        Path file = resolve(area, path);

        if (!Files.exists(file)) {
            // Logged at error rather than warn. A record pointing at a missing
            // file means the database and the disk have diverged, which after a
            // restore usually means the files were not restored with it.
            log.error("Storage miss: {}/{} is referenced but not present on disk",
                    area.directory(), path);
            throw new StorageException("That file is not available. Contact the helpdesk.");
        }

        try {
            if (properties.isVerifyChecksumOnRead() && expectedChecksum != null) {
                verifyChecksum(file, expectedChecksum, area, path);
            }
            return Files.newInputStream(file, StandardOpenOption.READ);
        } catch (IOException e) {
            throw new StorageException("Could not read the file", e);
        }
    }

    /**
     * Reads the file through and compares the hash.
     *
     * A mismatch means the bytes changed after they were written: failing
     * hardware, a partial restore, or someone editing files on the server.
     * Serving it anyway would put a corrupted prescription in a patient's hands.
     */
    private void verifyChecksum(Path file, String expected, StorageArea area, String path) {
        try (DigestInputStream in = new DigestInputStream(
                Files.newInputStream(file), MessageDigest.getInstance("SHA-256"))) {
            byte[] buffer = new byte[8192];
            while (in.read(buffer) != -1) {
                // draining to hash
            }
            String actual = HexFormat.of().formatHex(in.getMessageDigest().digest());
            if (!actual.equals(expected)) {
                log.error("Checksum mismatch on {}/{}: expected {}, found {}",
                        area.directory(), path, expected, actual);
                throw new StorageException(
                        "That file failed its integrity check and has not been served. "
                                + "This has been logged for ICT.");
            }
        } catch (StorageException e) {
            throw e;
        } catch (Exception e) {
            throw new StorageException("Could not verify the file", e);
        }
    }

    @Override
    public boolean exists(StorageArea area, String path) {
        return Files.exists(resolve(area, path));
    }

    @Override
    public void requestDeletion(StorageArea area, String path, String reason) {
        if (deletionRepository.existsByStorageAreaAndStoragePath(area, path)) {
            return;
        }
        int graceHours = configuration.getInt("storage_deletion_grace_hours");

        StorageDeletion deletion = new StorageDeletion();
        deletion.setStorageArea(area);
        deletion.setStoragePath(path);
        deletion.setReason(reason);
        deletion.setRequestedAt(java.time.LocalDateTime.now());
        deletion.setRequestedBy(CurrentUser.usernameOrSystem());
        deletion.setEligibleAt(java.time.LocalDateTime.now().plusHours(graceHours));
        deletionRepository.save(deletion);

        log.info("Queued {}/{} for deletion in {} hours: {}",
                area.directory(), path, graceHours, reason);
    }

    /**
     * Removes files whose grace period has passed. Run on a schedule.
     *
     * Separate from the request so a mistaken deletion can still be undone
     * during the window. A disk has no recycle bin.
     */
    public int sweepDeletions() {
        var due = deletionRepository.findDue(java.time.LocalDateTime.now());
        int removed = 0;

        for (StorageDeletion deletion : due) {
            try {
                Files.deleteIfExists(resolve(deletion.getStorageArea(), deletion.getStoragePath()));
                deletion.setDeletedAt(java.time.LocalDateTime.now());
                deletionRepository.save(deletion);
                removed++;
            } catch (Exception e) {
                deletion.setAttempts(deletion.getAttempts() + 1);
                deletion.setLastError(e.getMessage());
                deletionRepository.save(deletion);
                log.error("Could not delete {}/{}: {}",
                        deletion.getStorageArea().directory(), deletion.getStoragePath(),
                        e.getMessage());
            }
        }
        return removed;
    }

    @Override
    public int freeSpacePercent() {
        try {
            FileStore store = Files.getFileStore(Paths.get(properties.getRoot()));
            long total = store.getTotalSpace();
            return total == 0 ? 0 : (int) (store.getUsableSpace() * 100 / total);
        } catch (IOException e) {
            log.error("Could not read free space at {}: {}", properties.getRoot(), e.getMessage());
            return -1;
        }
    }

    // -----------------------------------------------------------------

    /**
     * Builds a path from a new identifier and the date, never from the filename.
     *
     * Sharded two levels off the identifier so no directory grows without
     * bound.
     */
    private String generatePath(StorageArea area, String originalFilename) {
        String id = PublicId.generate();
        LocalDate today = LocalDate.now();
        String extension = safeExtension(originalFilename);

        return "%d/%02d/%s/%s/%s%s".formatted(
                today.getYear(), today.getMonthValue(),
                id.substring(id.length() - 4, id.length() - 2).toLowerCase(),
                id.substring(id.length() - 2).toLowerCase(),
                id, extension);
    }

    /**
     * Keeps a short alphanumeric extension for convenience and nothing else.
     *
     * Not used for any decision. Content type comes from the database, so a
     * file renamed to .pdf does not become a PDF.
     */
    private String safeExtension(String originalFilename) {
        if (originalFilename == null) {
            return "";
        }
        int dot = originalFilename.lastIndexOf('.');
        if (dot < 0 || dot == originalFilename.length() - 1) {
            return "";
        }
        String extension = originalFilename.substring(dot + 1).toLowerCase();
        if (!extension.matches("^[a-z0-9]{1,8}$")) {
            return "";
        }
        return "." + extension;
    }

    /**
     * Resolves a path under the root, and refuses anything that escapes it.
     *
     * The normalise-and-compare is the check that matters. Paths in the
     * database should always be ones this class generated, but "should always"
     * is not a security control, and a single bad row would otherwise read any
     * file the process can see.
     */
    private Path resolve(StorageArea area, String relativePath) {
        Path root = Paths.get(properties.getRoot()).toAbsolutePath().normalize();
        Path areaRoot = root.resolve(area.directory()).normalize();
        Path resolved = areaRoot.resolve(relativePath).normalize();

        if (!resolved.startsWith(areaRoot)) {
            log.error("Blocked path escape: {} resolved outside {}", relativePath, areaRoot);
            throw new StorageException("Invalid file path");
        }
        return resolved;
    }

    private void assertContentTypeAllowed(String contentType) {
        if (contentType == null || !properties.getAllowedContentTypes().contains(contentType)) {
            throw new StorageException(
                    "That file type is not accepted. Allowed: "
                            + String.join(", ", properties.getAllowedContentTypes()));
        }
    }

    private void createDirectories(Path directory) throws IOException {
        if (Files.exists(directory)) {
            return;
        }
        Files.createDirectories(directory);
        try {
            Files.setPosixFilePermissions(directory, DIRECTORY_PERMISSIONS);
        } catch (UnsupportedOperationException e) {
            // Windows. Permissions are the deployment's problem there.
            log.debug("POSIX permissions unavailable on this filesystem");
        }
    }

    private void setPermissions(Path file) {
        try {
            Files.setPosixFilePermissions(file, FILE_PERMISSIONS);
        } catch (UnsupportedOperationException | IOException e) {
            log.debug("Could not set POSIX permissions on {}", file);
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Could not clean up {}: {}", path, e.getMessage());
        }
    }
}

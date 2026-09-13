package com.fnph.telepsychiatric.storage;

import java.io.InputStream;

/**
 * File storage, behind an interface.
 *
 * FNPH have chosen a filesystem. That is a reasonable choice for a single
 * hospital with predictable volume, and it avoids a cloud dependency in a
 * setting where connectivity is the least reliable part of the stack.
 *
 * The interface exists so the choice can be revisited without touching the
 * eleven callers. Nothing outside {@link FilesystemStorageService} knows a
 * directory is involved.
 */
public interface StorageService {

    /**
     * Stores a file and returns where it went.
     *
     * The path is generated here, never derived from the uploaded filename. A
     * filename is user input, and user input in a path is how a directory
     * traversal happens.
     */
    StoredObject store(StorageArea area, InputStream content, String originalFilename,
                       String declaredContentType);

    /**
     * Opens a stored file.
     *
     * The checksum is verified as it is read when configured, so a truncated or
     * corrupted file fails rather than being served.
     */
    InputStream read(StorageArea area, String path, String expectedChecksum);

    boolean exists(StorageArea area, String path);

    /**
     * Queues a file for deletion rather than removing it.
     *
     * Unlinking inside the transaction that marks a record deleted loses the
     * file if that transaction rolls back, and a disk has no undo. The sweeper
     * removes it once the transaction has definitely committed and a grace
     * period has passed.
     */
    void requestDeletion(StorageArea area, String path, String reason);

    /** Free space as a percentage. A disk fills; object storage does not. */
    int freeSpacePercent();

    class StorageException extends RuntimeException {
        public StorageException(String message) {
            super(message);
        }

        public StorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

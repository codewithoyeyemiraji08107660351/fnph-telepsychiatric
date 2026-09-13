package com.fnph.telepsychiatric.storage;

/**
 * What a caller records after storing a file.
 *
 * @param area        logical area
 * @param path        path relative to the storage root, generated server-side
 * @param sizeBytes   verified after the write, not taken from the upload header
 * @param checksum    SHA-256 of what actually landed on disk
 * @param contentType as detected, not as claimed
 */
public record StoredObject(StorageArea area, String path, long sizeBytes,
                           String checksum, String contentType) {
}

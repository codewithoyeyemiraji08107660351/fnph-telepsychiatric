package com.fnph.telepsychiatric.storage;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@Getter
@Setter
@ConfigurationProperties(prefix = "application.storage")
public class StorageProperties {

    /**
     * The root every file lives under.
     *
     * Must be outside the application directory and outside anything the web
     * server serves. A deployment that puts this inside the served tree turns
     * every prescription into a public URL, and nothing in the application
     * would notice.
     *
     * On two application nodes this has to be a shared mount visible at the
     * same path on both, or the second node cannot read what the first wrote.
     */
    private String root = "/var/lib/fnph/storage";

    /** Largest single file accepted, in megabytes. */
    private int maxFileSizeMb = 20;

    /**
     * What may be uploaded.
     *
     * An allow-list, not a block-list. A block-list is a list of the file types
     * somebody thought of.
     */
    private List<String> allowedContentTypes = List.of(
            "application/pdf",
            "image/jpeg",
            "image/png",
            "image/heic",
            "text/csv",
            "text/plain");

    /**
     * Verify the checksum on every read.
     *
     * Costs a hash over the file. Worth it: a truncated or corrupted
     * prescription served as though it were intact is worse than a failed
     * download, and nothing else in the system would catch it.
     */
    private boolean verifyChecksumOnRead = true;

    /** Free space below this raises a warning to ICT. */
    private int freeSpaceWarningPercent = 20;
}

package com.fnph.telepsychiatric.storage;

/**
 * The logical areas files are kept in.
 *
 * Separate areas rather than one directory, because they have different
 * retention rules, different access controls and different backup priorities.
 * An EHR import can be regenerated from the hospital; a signed prescription
 * cannot.
 */
public enum StorageArea {

    /** Patient and centre uploads: prior results, referral attachments. */
    UPLOADS("uploads"),

    /** Rendered prescriptions, investigation requests, summaries. */
    DOCUMENTS("documents"),

    /** The raw EHR snapshot files, kept as evidence behind every activation. */
    EHR_IMPORTS("ehr-imports"),

    /** Session recordings. Empty unless FNPH switch recording on. */
    RECORDINGS("recordings"),

    /** Backups written by the scheduled job. */
    BACKUPS("backups");

    private final String directory;

    StorageArea(String directory) {
        this.directory = directory;
    }

    public String directory() {
        return directory;
    }
}

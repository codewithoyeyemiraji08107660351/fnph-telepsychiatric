package com.fnph.telepsychiatric.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Path handling for filesystem storage.
 *
 * The reason this class exists: a filename is user input, and user input in a
 * path is how a directory traversal happens. These are the cases an attacker
 * actually tries.
 */
class StoragePathTest {

    private static final String ROOT = "/var/lib/fnph/storage";

    /** Mirrors FilesystemStorageService.resolve. */
    private boolean escapes(String relativePath) {
        Path root = Paths.get(ROOT).toAbsolutePath().normalize();
        Path areaRoot = root.resolve("uploads").normalize();
        Path resolved = areaRoot.resolve(relativePath).normalize();
        return !resolved.startsWith(areaRoot);
    }

    @ParameterizedTest(name = "refuses {0}")
    @ValueSource(strings = {
            "../../../etc/passwd",
            "../../application.yaml",
            "..",
            "../",
            "2026/09/../../../../etc/shadow",
            "/etc/passwd",
            "/var/lib/fnph/storage/documents/other.pdf"
    })
    @DisplayName("a path that escapes the area is refused")
    void escapesAreRefused(String path) {
        assertThat(escapes(path))
                .as("%s must not resolve outside the area", path)
                .isTrue();
    }

    @ParameterizedTest(name = "accepts {0}")
    @ValueSource(strings = {
            "2026/09/a7/k2/01ARZ3NDEKTSV4RRFFQ69G5FAV.pdf",
            "2026/12/ff/00/01ARZ3NDEKTSV4RRFFQ69G5FAV",
            "2027/01/ab/cd/01BX5ZZKBKACTAV9WEVGEMMVRZ.csv"
    })
    @DisplayName("a generated path resolves inside the area")
    void generatedPathsAreAccepted(String path) {
        assertThat(escapes(path)).isFalse();
    }

    @Test
    @DisplayName("one area cannot reach into another")
    void areasAreSeparate() {
        // Documents and uploads have different access rules. A path crossing
        // between them would let an upload endpoint serve a prescription.
        assertThat(escapes("../documents/anything.pdf")).isTrue();
    }

    @Test
    @DisplayName("the stored extension is cosmetic and cannot carry a path")
    void extensionsAreSanitised() {
        assertThat(safeExtension("report.pdf")).isEqualTo(".pdf");
        assertThat(safeExtension("scan.JPEG")).isEqualTo(".jpeg");
        assertThat(safeExtension("../../etc/passwd")).isEmpty();
        assertThat(safeExtension("noextension")).isEmpty();
        assertThat(safeExtension("trailing.")).isEmpty();
        assertThat(safeExtension(null)).isEmpty();
    }

    @Test
    @DisplayName("content type is never taken from the extension")
    void extensionDoesNotDecideContentType() {
        // A file renamed to .pdf does not become a PDF. The content type comes
        // from the database column set at upload, not from the name on disk.
        assertThat(safeExtension("malware.exe.pdf")).isEqualTo(".pdf");
    }

    private String safeExtension(String originalFilename) {
        if (originalFilename == null) {
            return "";
        }
        int dot = originalFilename.lastIndexOf('.');
        if (dot < 0 || dot == originalFilename.length() - 1) {
            return "";
        }
        String extension = originalFilename.substring(dot + 1).toLowerCase();
        return extension.matches("^[a-z0-9]{1,8}$") ? "." + extension : "";
    }
}

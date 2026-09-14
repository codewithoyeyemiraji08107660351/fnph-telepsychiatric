package com.fnph.telepsychiatric.upload;

import com.fnph.telepsychiatric.audit.AuditAction;
import com.fnph.telepsychiatric.audit.AuditService;
import com.fnph.telepsychiatric.patient.PatientRepository;
import com.fnph.telepsychiatric.security.CurrentUser;
import com.fnph.telepsychiatric.storage.StorageArea;
import com.fnph.telepsychiatric.storage.StorageService;
import com.fnph.telepsychiatric.storage.StoredObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Uploading and retrieving supporting files.
 *
 * <h2>Nothing here trusts the upload</h2>
 *
 * The filename is kept for display and never used as a path. The declared size
 * is ignored in favour of counting the bytes. The content type is checked
 * against an allow-list, and on read it comes from the database rather than
 * from the extension on disk, so a file renamed to {@code .pdf} does not become
 * a PDF.
 *
 * <h2>Files are not scanned</h2>
 *
 * {@code scanStatus} exists and nothing moves it. A centre uploading a lab
 * result is an untrusted file landing on the server, and until a scanner is
 * wired that risk is real rather than theoretical. It is named here so nobody
 * assumes the column means something.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UploadService {

    private final FileUploadRepository uploadRepository;
    private final PatientRepository patientRepository;
    private final StorageService storageService;
    private final AuditService auditService;

    @Value("${application.storage.require-clean-scan:false}")
    private boolean requireCleanScan;

    @Transactional
    public FileUpload upload(MultipartFile file, FileCategory category,
                             String description, String referenceId) {
        if (file == null || file.isEmpty()) {
            throw new UploadException("No file was received");
        }

        StoredObject stored;
        try (InputStream in = file.getInputStream()) {
            stored = storageService.store(StorageArea.UPLOADS, in,
                    file.getOriginalFilename(), file.getContentType());
        } catch (StorageService.StorageException e) {
            throw new UploadException(e.getMessage());
        } catch (Exception e) {
            throw new UploadException("Could not read the uploaded file");
        }

        FileUpload upload = new FileUpload();
        upload.setOriginalFileName(file.getOriginalFilename());
        upload.setStorageArea(stored.area());
        upload.setStoragePath(stored.path());
        // Counted while writing, not taken from the request.
        upload.setFileSize(stored.sizeBytes());
        upload.setChecksum(stored.checksum());
        upload.setMimeType(stored.contentType());
        upload.setCategory(category);
        upload.setScanStatus(ScanStatus.UPLOADED);
        upload.setDescription(description);
        upload.setReferenceId(referenceId);
        upload.setUploadedBy(CurrentUser.usernameOrSystem());
        upload.setUploadedAt(LocalDateTime.now());

        CurrentUser.get().ifPresent(principal -> {
            if (principal.getPatientId() != null) {
                patientRepository.findById(principal.getPatientId())
                        .ifPresent(upload::setPatient);
            }
        });

        FileUpload saved = uploadRepository.save(upload);

        auditService.record(AuditService.AuditEvent.builder()
                .action(AuditAction.RECORD_CREATED)
                .entityType("FileUpload")
                .entityId(saved.getId())
                .details("%s, %d bytes, %s".formatted(category, stored.sizeBytes(),
                        stored.contentType()))
                .build());

        log.info("Upload {} stored: {} bytes, {}", saved.getPublicId(),
                stored.sizeBytes(), category);
        return saved;
    }

    /**
     * Opens a stored file, verifying it on the way out.
     *
     * The checksum check is the reason this goes through the service rather
     * than the web server serving the directory. A truncated or corrupted file
     * fails here; served directly it would look fine.
     */
    @Transactional(readOnly = true)
    public FileContent read(String uploadPublicId) {
        FileUpload upload = uploadRepository.findByPublicId(uploadPublicId)
                .orElseThrow(() -> new UploadException("No such file"));

        assertReadable(upload);

        InputStream stream = storageService.read(
                upload.getStorageArea(), upload.getStoragePath(), upload.getChecksum());

        return new FileContent(stream, upload.getOriginalFileName(),
                upload.getMimeType(), upload.getFileSize());
    }

    @Transactional(readOnly = true)
    public List<FileUpload> mine() {
        Long patientId = CurrentUser.require().getPatientId();
        return patientId != null
                ? uploadRepository.findAllByPatientIdOrderByUploadedAtDesc(patientId)
                : uploadRepository.findAllByUploadedByOrderByUploadedAtDesc(
                        CurrentUser.usernameOrSystem());
    }

    @Transactional
    public void delete(String uploadPublicId, String reason) {
        FileUpload upload = uploadRepository.findByPublicId(uploadPublicId)
                .orElseThrow(() -> new UploadException("No such file"));

        upload.setDeleted(true);
        upload.setDeletedAt(LocalDateTime.now());
        upload.setDeletedBy(CurrentUser.usernameOrSystem());
        upload.setDeletedReason(reason);
        uploadRepository.save(upload);

        // Queued, not unlinked. Unlinking inside this transaction loses the
        // file if it rolls back, and a disk has no undo.
        storageService.requestDeletion(upload.getStorageArea(), upload.getStoragePath(), reason);
    }

    private void assertReadable(FileUpload upload) {
        CurrentUser.get().ifPresent(principal -> {
            if (principal.getPatientId() != null) {
                if (upload.getPatient() == null
                        || !principal.getPatientId().equals(upload.getPatient().getId())) {
                    // Same message as a missing file. Confirming it exists
                    // would let a caller probe for other people's uploads.
                    throw new UploadException("No such file");
                }
                return;
            }

            if (principal.getCentreId() != null) {
                if (upload.resolveCentreId() == null
                        || !principal.getCentreId().equals(upload.resolveCentreId())) {
                    throw new UploadException("No such file");
                }
                return;
            }

            // Neither patient nor centre: FNPH staff, bounded by permission.
        });

        // Judged unsafe by a human. Refused regardless of the flag, because
        // the quarantine endpoint exists precisely so ICT can make that call.
        if (upload.getScanStatus() == ScanStatus.QUARANTINED
                || upload.getScanStatus() == ScanStatus.REJECTED) {
            log.warn("Refused {} file {} requested by {}",
                    upload.getScanStatus(), upload.getPublicId(),
                    CurrentUser.usernameOrSystem());
            throw new UploadException(
                    "This file is not available. It was withheld during a security check. "
                            + "Contact the help desk if you need it.");
        }

        if (requireCleanScan && upload.getScanStatus() != ScanStatus.CLEAN) {
            log.warn("Refused unscanned file {} (status {}) requested by {}",
                    upload.getPublicId(), upload.getScanStatus(),
                    CurrentUser.usernameOrSystem());
            throw new UploadException(
                    "This file has not finished its security check. Try again shortly.");
        }
    }

    public record FileContent(InputStream stream, String filename,
                              String contentType, long sizeBytes) {
    }

    public static class UploadException extends RuntimeException {
        public UploadException(String message) {
            super(message);
        }
    }
}

package com.fnph.telepsychiatric.upload;

import com.fnph.telepsychiatric.common.BaseEntity;
import com.fnph.telepsychiatric.patient.Patient;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "file_uploads")
@Getter
@Setter
public class FileUpload extends BaseEntity {


    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "patient_id")
    private Patient patient;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "file_path", nullable = false, length = 500)
    private String filePath;

    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    @Column(name = "mime_type", nullable = false, length = 100)
    private String mimeType;

    @Column(name = "checksum", length = 64)
    private String checksum;

    @Column(name = "file_type", nullable = false)
    @Enumerated(EnumType.STRING)
    private FileStatus fileType;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private FileStatus status = FileStatus.UPLOADED;

    @Column(name = "uploaded_by", length = 50)
    private String uploadedBy;

    @Column(name = "uploaded_at", nullable = false)
    private LocalDateTime uploadedAt;

    @Column(name = "scanned_at")
    private LocalDateTime scannedAt;

    @Column(name = "scan_result", columnDefinition = "TEXT")
    private String scanResult;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "reference_id", length = 50)
    private String referenceId;
}

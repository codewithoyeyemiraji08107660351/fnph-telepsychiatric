package com.fnph.telepsychiatric.ehr.api;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record ManualEhrRecordResponse(
        String publicId,
        long version,
        String ehrNumber,
        String fullName,
        LocalDate dateOfBirth,
        String phoneNumber,
        String email,
        String clinic,
        String patientStatus,
        boolean active,
        String syncStatus,
        LocalDateTime lastSyncedAt,
        String lastSyncedBy,
        String lastSyncDirection,
        LocalDateTime updatedAt,
        String updatedBy
) {}

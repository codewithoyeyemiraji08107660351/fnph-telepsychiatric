package com.fnph.telepsychiatric.ehr.api;

import com.fnph.telepsychiatric.ehr.ManualEhrRecordService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/ehr-records/manual")
@RequiredArgsConstructor
public class ManualEhrRecordController {
    private final ManualEhrRecordService service;

    @GetMapping
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).EHR_IMPORT_READ)")
    public List<ManualEhrRecordResponse> list() { return service.list(); }

    @PostMapping
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).EHR_IMPORT_UPLOAD)")
    public ResponseEntity<ManualEhrRecordResponse> create(@Valid @RequestBody ManualEhrRecordRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @PutMapping("/{publicId}")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).EHR_IMPORT_UPLOAD)")
    public ManualEhrRecordResponse update(@PathVariable String publicId, @Valid @RequestBody ManualEhrRecordRequest request) {
        return service.update(publicId, request);
    }

    @PostMapping("/{publicId}/sync")
    @PreAuthorize("hasAuthority(T(com.fnph.telepsychiatric.authz.Permissions).EHR_IMPORT_ACTIVATE)")
    public ManualEhrRecordResponse sync(@PathVariable String publicId, @Valid @RequestBody ManualEhrSyncRequest request) {
        return service.sync(publicId, request);
    }
}

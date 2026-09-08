package com.fnph.telepsychiatric.document;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DocumentDownloadEventRepository
        extends JpaRepository<DocumentDownloadEvent, Long> {

    List<DocumentDownloadEvent> findAllByIssuedDocumentIdOrderByDownloadedAtAsc(Long documentId);
}

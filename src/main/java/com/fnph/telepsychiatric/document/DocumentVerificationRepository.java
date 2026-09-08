package com.fnph.telepsychiatric.document;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DocumentVerificationRepository extends JpaRepository<DocumentVerification, Long> {

    Optional<DocumentVerification> findByVerificationToken(String token);
    Optional<DocumentVerification> findByIssuedDocumentId(Long issuedDocumentId);
}

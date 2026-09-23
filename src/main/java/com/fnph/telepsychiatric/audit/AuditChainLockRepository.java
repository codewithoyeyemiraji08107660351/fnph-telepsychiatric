package com.fnph.telepsychiatric.audit;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;

public interface AuditChainLockRepository
        extends JpaRepository<AuditChainLock, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AuditChainLock> findLockedById(Long id);
}
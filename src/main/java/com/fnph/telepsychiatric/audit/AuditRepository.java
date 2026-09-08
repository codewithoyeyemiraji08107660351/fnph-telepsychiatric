package com.fnph.telepsychiatric.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AuditRepository extends JpaRepository<AuditLog, Long> {

    /** The tail of the chain, needed to link the next row to it. */
    Optional<AuditLog> findFirstByOrderByIdDesc();

    @Query("""
           select a from AuditLog a
           where (:action    is null or a.action = :action)
             and (:entityType is null or a.entityType = :entityType)
             and (:userId    is null or a.user.id = :userId)
             and (:centreId  is null or a.centreId = :centreId)
             and a.performedAt between :from and :to
           order by a.performedAt desc
           """)
    Page<AuditLog> search(@Param("action") String action,
                          @Param("entityType") String entityType,
                          @Param("userId") Long userId,
                          @Param("centreId") Long centreId,
                          @Param("from") LocalDateTime from,
                          @Param("to") LocalDateTime to,
                          Pageable pageable);

    /**
     * Everything done inside one supervised session.
     *
     * The question an auditor actually asks is not "what did this administrator
     * do" but "what did they do while pretending to be someone else", and this
     * is what answers it.
     */
    List<AuditLog> findAllByViewAsSessionIdOrderByPerformedAtAsc(Long viewAsSessionId);

    /** Ordered walk for chain verification. */
    @Query("select a from AuditLog a where a.id > :afterId order by a.id asc")
    List<AuditLog> findForChainVerification(@Param("afterId") Long afterId, Pageable pageable);

    long countByViewAsSessionId(Long viewAsSessionId);
}

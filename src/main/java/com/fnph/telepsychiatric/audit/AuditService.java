package com.fnph.telepsychiatric.audit;

import com.fnph.telepsychiatric.security.CurrentUser;
import com.fnph.telepsychiatric.security.SecurityUser;
import com.fnph.telepsychiatric.security.crypto.Tokens;
import com.fnph.telepsychiatric.tenancy.TenantContext;
import com.fnph.telepsychiatric.user.UserRepository;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Writes the audit trail.
 *
 * <h2>Why audit is written explicitly rather than by a Hibernate listener</h2>
 *
 * A persistence listener can see that {@code prescriptions.status} changed from
 * PENDING_REVIEW to RELEASED. It cannot see that a Hub Coordinator released a
 * clinical bundle for a named patient after a pharmacy review, which is the
 * only version of that event an auditor can use. Intent and reason exist at the
 * service boundary and nowhere below it.
 *
 * So audit rows are written where the decision is made, with the business
 * meaning attached. The cost is that a developer has to remember; the
 * mitigation is that the actions worth auditing are a closed enum and the
 * review checklist names them.
 *
 * <h2>Why writes run in their own transaction</h2>
 *
 * {@code REQUIRES_NEW}. If a clinical operation fails and rolls back, the
 * attempt still happened and the record of it must survive. An audit row that
 * disappears with the failed operation is exactly the row an investigation
 * would want.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

    private final AuditRepository auditRepository;
    private final UserRepository userRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditEvent event) {
        try {
            AuditLog entry = new AuditLog();

            CurrentUser.get().ifPresent(principal -> {
                entry.setUser(userRepository.findById(principal.getUserId()).orElse(null));
                entry.setUsername(principal.getUsername());
            });

            // During supervised access the authenticated principal is the
            // administrator and the effective principal is whoever they are
            // acting as. Both are recorded, because "who did this" has two
            // correct answers and an auditor needs both.
            SupervisionContext supervision = SupervisionContext.current();
            if (supervision != null) {
                entry.setEffectivePrincipal(
                        userRepository.findById(supervision.targetUserId()).orElse(null));
                entry.setViewAsSessionId(supervision.viewAsSessionId());
            }

            entry.setAction(event.action().name());
            entry.setEntityType(event.entityType());
            entry.setEntityId(event.entityId());
            entry.setDetails(event.details());
            entry.setReason(event.reason());
            entry.setOutcome(event.outcome() == null ? "SUCCESS" : event.outcome());
            entry.setIpAddress(event.ipAddress());
            entry.setUserAgent(event.userAgent());
            entry.setBeforeHash(event.beforeState() == null ? null : Tokens.hash(event.beforeState()));
            entry.setAfterHash(event.afterState() == null ? null : Tokens.hash(event.afterState()));
            entry.setPerformedAt(LocalDateTime.now());
            entry.setIsSystem(CurrentUser.get().isEmpty());
            entry.setCentreId(TenantContext.current().centreId());

            linkToChain(entry);

            auditRepository.save(entry);
        } catch (Exception e) {
            // An audit write must never take down the operation it describes.
            // Logged at error so a persistent failure is visible in monitoring
            // rather than silently producing a trail with holes in it.
            log.error("Failed to write audit event {}: {}", event.action(), e.getMessage(), e);
        }
    }

    /**
     * Links this row to the previous one, so removing or editing any row breaks
     * every hash after it.
     */
    private void linkToChain(AuditLog entry) {
        String previous = auditRepository.findFirstByOrderByIdDesc()
                .map(AuditLog::getChainHash)
                .orElse("GENESIS");

        entry.setPreviousChainHash(previous);
        entry.setChainHash(Tokens.hash(String.join("|",
                previous,
                nullSafe(entry.getUsername()),
                nullSafe(entry.getAction()),
                nullSafe(entry.getEntityType()),
                String.valueOf(entry.getEntityId()),
                nullSafe(entry.getOutcome()),
                nullSafe(entry.getBeforeHash()),
                nullSafe(entry.getAfterHash()),
                String.valueOf(entry.getPerformedAt()))));
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    /** Convenience for the common case of recording access to a record. */
    public void recordAccess(AuditAction action, String entityType, Long entityId, String details) {
        record(AuditEvent.builder()
                .action(action).entityType(entityType).entityId(entityId)
                .details(details).build());
    }

    public void recordDenial(String entityType, Long entityId, String details) {
        record(AuditEvent.builder()
                .action(AuditAction.ACCESS_DENIED).entityType(entityType).entityId(entityId)
                .details(details).outcome("DENIED").build());
    }

    @Builder
    public record AuditEvent(
            AuditAction action,
            String entityType,
            Long entityId,
            String details,
            String reason,
            String outcome,
            String ipAddress,
            String userAgent,
            String beforeState,
            String afterState) {
    }
}

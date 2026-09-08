package com.fnph.telepsychiatric.supervision;

import com.fnph.telepsychiatric.audit.AuditAction;
import com.fnph.telepsychiatric.audit.AuditService;
import com.fnph.telepsychiatric.authz.Role;
import com.fnph.telepsychiatric.authz.RoleScope;
import com.fnph.telepsychiatric.authz.UserRole;
import com.fnph.telepsychiatric.authz.UserRoleRepository;
import com.fnph.telepsychiatric.security.CurrentUser;
import com.fnph.telepsychiatric.security.SecurityUser;
import com.fnph.telepsychiatric.supervision.api.ViewAsSessionResponse;
import com.fnph.telepsychiatric.supervision.api.StartViewAsRequest;
import com.fnph.telepsychiatric.user.UserRepository;
import com.fnph.telepsychiatric.user.UserStatus;
import com.fnph.telepsychiatric.user.Users;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Supervised access to another user's dashboard.
 *
 * <h2>Supervised access is read-only, and that is the important decision</h2>
 *
 * An administrator opening a doctor's dashboard can see everything the doctor
 * sees. They cannot write a clinical note, sign one, issue a prescription,
 * submit a professional review, approve a booking, move money or download a
 * patient's document.
 *
 * The reason is not caution. If an administrator could write in a clinician's
 * name, the resulting note would be indistinguishable from one the clinician
 * wrote, and the specification is explicit that clinician-authored content is
 * not altered by anyone else. A supervision feature that can forge clinical
 * authorship is not a supervision feature.
 *
 * Enforcement is the {@code is_mutating} flag on every permission: during
 * supervision the effective authority set is the target's non-mutating
 * permissions only. It is a property of the data, so a permission added later
 * is covered by default, because the default is mutating.
 *
 * {@code document.download} is mutating on purpose. It consumes the patient's
 * single allowed download, and an administrator looking at a dashboard must not
 * burn it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ViewAsService {

    private final ViewAsSessionRepository viewAsRepository;
    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final AuditService auditService;

    @Value("${application.security.supervision.max-duration-minutes:30}")
    private int maxDurationMinutes;

    @Transactional
    public ViewAsSessionResponse start(StartViewAsRequest request, String ipAddress, String userAgent) {
        SecurityUser principal = CurrentUser.require();

        Users administrator = userRepository.findById(principal.getUserId())
                .orElseThrow(() -> new IllegalStateException("Administrator account not found"));

        Users target = userRepository.findByPublicId(request.targetUserPublicId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "No user with id " + request.targetUserPublicId()));

        if (target.getId().equals(administrator.getId())) {
            throw new IllegalArgumentException(
                    "Supervising your own account produces an audit trail that says nothing");
        }
        if (target.getStatus() != UserStatus.ACTIVE) {
            throw new IllegalArgumentException(
                    "That account is not active, so there is no dashboard to supervise");
        }

        // One at a time. Two open sessions would make the audit trail ambiguous
        // about which one an action belonged to, which defeats the point.
        viewAsRepository.findOpenForAdministrator(administrator.getId(), LocalDateTime.now())
                .ifPresent(open -> {
                    throw new IllegalStateException(
                            "You already have a supervised session open on another account. "
                                    + "End it before starting another.");
                });

        UserRole primary = userRoleRepository.findByUserIdAndIsPrimaryTrue(target.getId())
                .orElseThrow(() -> new IllegalStateException("That account has no primary role"));
        Role targetRole = primary.getRole();

        if (targetRole.getScope() == RoleScope.PATIENT) {
            // A patient dashboard is one person's clinical record. Reading it
            // for support reasons is a records access, which goes through the
            // permission and leaves an ordinary audit entry naming the patient.
            // Dressing it up as supervision would hide it inside a mode
            // designed for staff dashboards.
            throw new IllegalArgumentException("""
                    Patient accounts cannot be supervised. Viewing a patient's record is \
                    a records access under the patient.read permission, audited by \
                    patient, not a dashboard supervision.""");
        }

        LocalDateTime now = LocalDateTime.now();

        ViewAsSession session = new ViewAsSession();
        session.setAdministrator(administrator);
        session.setTargetUser(target);
        session.setTargetRole(targetRole);
        session.setReason(request.reason());
        session.setStartedAt(now);
        session.setExpiresAt(now.plusMinutes(maxDurationMinutes));
        session.setIpAddress(ipAddress);
        session.setUserAgent(userAgent);
        ViewAsSession saved = viewAsRepository.save(session);

        auditService.record(AuditService.AuditEvent.builder()
                .action(AuditAction.VIEW_AS_STARTED)
                .entityType("ViewAsSession")
                .entityId(saved.getId())
                .details("Supervising %s (%s)".formatted(target.getUsername(), targetRole.getCode()))
                .reason(request.reason())
                .ipAddress(ipAddress)
                .userAgent(userAgent)
                .build());

        log.info("{} started supervised access to {} as {}: {}",
                administrator.getUsername(), target.getUsername(), targetRole.getCode(),
                request.reason());

        return toResponse(saved, readOnlyPermissions(target.getId()));
    }

    @Transactional
    public void end(String sessionPublicId, String endReason) {
        ViewAsSession session = viewAsRepository.findByPublicId(sessionPublicId)
                .orElseThrow(() -> new EntityNotFoundException("No such supervised session"));

        SecurityUser principal = CurrentUser.require();
        if (!session.getAdministrator().getId().equals(principal.getUserId())) {
            throw new IllegalArgumentException("That supervised session belongs to someone else");
        }
        if (session.getEndedAt() != null) {
            return;
        }

        session.setEndedAt(LocalDateTime.now());
        session.setEndReason(endReason == null ? "Ended by the administrator" : endReason);
        viewAsRepository.save(session);

        auditService.record(AuditService.AuditEvent.builder()
                .action(AuditAction.VIEW_AS_ENDED)
                .entityType("ViewAsSession")
                .entityId(session.getId())
                .details("%d actions performed".formatted(session.getActionsPerformed()))
                .build());
    }

    /**
     * The target's non-mutating permissions.
     *
     * Derived from the data rather than a hard-coded list, so a permission added
     * next year is excluded by default. The default is mutating, which is the
     * safe direction to fail.
     */
    @Transactional(readOnly = true)
    public List<String> readOnlyPermissions(Long targetUserId) {
        return userRoleRepository.findNonMutatingPermissionCodesByUserId(targetUserId);
    }

    /** Closes sessions nobody ended. Run on a schedule. */
    @Transactional
    public int closeExpiredSessions() {
        int closed = viewAsRepository.closeExpired(LocalDateTime.now());
        if (closed > 0) {
            log.info("Closed {} expired supervised session(s)", closed);
        }
        return closed;
    }

    private ViewAsSessionResponse toResponse(ViewAsSession session, List<String> permissions) {
        return new ViewAsSessionResponse(
                session.getPublicId(),
                session.getTargetUser().getPublicId(),
                session.getTargetUser().getUsername(),
                session.getTargetUser().getFullName(),
                session.getTargetRole().getCode(),
                session.getTargetRole().getDashboardRoute(),
                session.getReason(),
                session.getStartedAt(),
                session.getExpiresAt(),
                session.getEndedAt(),
                session.getActionsPerformed(),
                permissions);
    }
}

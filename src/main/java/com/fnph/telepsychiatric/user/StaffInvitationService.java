package com.fnph.telepsychiatric.user;

import com.fnph.telepsychiatric.authz.*;
import com.fnph.telepsychiatric.center.CapabilityType;
import com.fnph.telepsychiatric.center.Center;
import com.fnph.telepsychiatric.center.CenterRepository;
import com.fnph.telepsychiatric.center.CentreCapabilityRepository;
import com.fnph.telepsychiatric.email.AccountEmailService;
import com.fnph.telepsychiatric.security.CurrentUser;
import com.fnph.telepsychiatric.security.crypto.Tokens;
import com.fnph.telepsychiatric.session.*;
import com.fnph.telepsychiatric.user.api.CreateStaffUserRequest;
import com.fnph.telepsychiatric.user.api.StaffUserResponse;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Creating staff and centre accounts by invitation.
 *
 * **Patients are deliberately out of scope here.** A patient account is not
 * created by an administrator and never receives an invitation email. Patients
 * enrol themselves by verifying an existing FNPH EHR number, and an unsolicited
 * email naming someone as a patient of a neuropsychiatric hospital would
 * disclose their care to anyone with access to that inbox: a shared family
 * address, a work account, a phone showing previews on a locked screen. That
 * disclosure is the exact harm the whole privacy design exists to prevent, and
 * it would happen before the person had agreed to anything.
 *
 * So this service refuses the PATIENT role outright. The patient pathway is
 * EHR verification followed by contact verification, which the patient starts.
 *
 * **No password is ever generated or emailed.** The invitation carries a
 * single-use link and the recipient chooses their own password. A generated
 * password sits in an inbox and in the mail server's storage for the life of
 * the account, and neither is under FNPH's control.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StaffInvitationService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final CenterRepository centreRepository;
    private final CentreCapabilityRepository capabilityRepository;
    private final AccountEmailService emailService;
    private final AuthenticationService authenticationService;
    private final SessionService sessionService;
    private final MfaService mfaService;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties authProperties;

    @Transactional
    public StaffUserResponse invite(CreateStaffUserRequest request,
                                    AuthenticationService.RequestContext context) {

        Role role = roleRepository.findByCode(request.primaryRoleCode())
                .orElseThrow(() -> new IllegalArgumentException(
                        "No role with code " + request.primaryRoleCode()));

        if (role.getScope() == RoleScope.PATIENT) {
            throw new IllegalArgumentException("""
                    Patient accounts cannot be created here. A patient enrols by \
                    verifying their existing FNPH EHR number, and is never sent an \
                    unsolicited email, because an email naming someone as a patient \
                    of this hospital discloses their care to anyone with access to \
                    that inbox.""");
        }

        String email = request.email().trim().toLowerCase();
        String username = request.username().trim().toLowerCase();

        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new IllegalArgumentException("An account already exists with that email address");
        }
        if (userRepository.existsByUsernameIgnoreCase(username)) {
            throw new IllegalArgumentException("That username is already taken");
        }

        Center centre = null;
        if (role.getScope() == RoleScope.CENTRE) {
            if (request.centrePublicId() == null || request.centrePublicId().isBlank()) {
                throw new IllegalArgumentException(
                        "A centre role requires the centre this account belongs to");
            }
            centre = centreRepository.findByPublicId(request.centrePublicId())
                    .orElseThrow(() -> new EntityNotFoundException(
                            "No centre with id " + request.centrePublicId()));

            if (isOptionalLocalRole(role.getCode())
                    && !hasCapability(centre, role.getCode())) {
                throw new IllegalArgumentException(
                        "The optional " + role.getName() + " role is not activated at "
                                + centre.getName() + ". Activate the capability first.");
            }
        } else if (request.centrePublicId() != null && !request.centrePublicId().isBlank()) {
            throw new IllegalArgumentException(
                    "Only centre roles may be bound to a centre");
        }

        LocalDateTime now = LocalDateTime.now();
        String actor = CurrentUser.usernameOrSystem();

        Users user = new Users();
        user.setUsername(username);
        user.setEmail(email);
        user.setFirstName(request.firstName().trim());
        user.setLastName(request.lastName().trim());
        user.setPhoneNumber(request.phoneNumber());
        user.setStaffNumber(request.staffNumber());
        user.setCentre(centre);
        user.setLoginType(LoginType.USERNAME);
        // A placeholder that no password can ever match. The account cannot
        // authenticate until activation sets a real one.
        user.setPassword(passwordEncoder.encode(Tokens.generate()));
        user.setStatus(UserStatus.INVITED);
        user.setIsActive(false);
        user.setMustChangePassword(false);
        user.setMfaEnabled(mfaService.isRequiredFor(role.getScope()));
        user.setInvitedAt(now);
        user.setInvitedBy(actor);
        userRepository.save(user);

        UserRole assignment = new UserRole();
        assignment.setUserId(user.getId());
        assignment.setRoleId(role.getId());
        assignment.setIsPrimary(true);
        assignment.setGrantedAt(now);
        assignment.setGrantedBy(actor);
        assignment.setGrantReason("Account created: " + request.reason());
        userRoleRepository.save(assignment);

        LocalDateTime expiresAt = now.plusHours(authProperties.getActivationTokenHours());
        String rawToken = authenticationService.issueToken(
                user, AccountTokenPurpose.ACTIVATION, expiresAt, context);

        emailService.sendInvitation(
                email, user.getFullName(), role.getName(),
                centre == null ? null : centre.getName(),
                rawToken, expiresAt, actor);

        log.info("Invited {} as {} ({}), invitation expires {}",
                user.getPublicId(), role.getCode(),
                centre == null ? "FNPH" : centre.getCode(), expiresAt);

        return toResponse(user, role, centre, expiresAt);
    }

    /** Reissues an invitation. The previous link stops working immediately. */
    @Transactional
    public StaffUserResponse resendInvitation(String userPublicId,
                                              AuthenticationService.RequestContext context) {
        Users user = userRepository.findByPublicId(userPublicId)
                .orElseThrow(() -> new EntityNotFoundException("No user with id " + userPublicId));

        if (user.getStatus() != UserStatus.INVITED) {
            throw new IllegalArgumentException(
                    "That account is already active. Send a password reset instead.");
        }

        UserRole primary = userRoleRepository.findByUserIdAndIsPrimaryTrue(user.getId())
                .orElseThrow(() -> new IllegalStateException("Account has no primary role"));

        LocalDateTime expiresAt = LocalDateTime.now().plusHours(authProperties.getActivationTokenHours());
        String rawToken = authenticationService.issueToken(
                user, AccountTokenPurpose.ACTIVATION, expiresAt, context);

        emailService.sendInvitation(
                user.getEmail(), user.getFullName(), primary.getRole().getName(),
                user.getCentre() == null ? null : user.getCentre().getName(),
                rawToken, expiresAt, CurrentUser.usernameOrSystem());

        return toResponse(user, primary.getRole(), user.getCentre(), expiresAt);
    }

    /**
     * Removes access without removing history.
     *
     * Clinical, financial and audit records referencing this account stay
     * exactly as they are. That is a stated non-negotiable control, and it is
     * why this is a status change rather than a delete.
     */
    @Transactional
    public void deactivate(String userPublicId, String reason) {
        Users user = userRepository.findByPublicId(userPublicId)
                .orElseThrow(() -> new EntityNotFoundException("No user with id " + userPublicId));

        user.setStatus(UserStatus.DEACTIVATED);
        user.setIsActive(false);
        user.setDeactivatedAt(LocalDateTime.now());
        user.setDeactivatedReason(reason);
        userRepository.save(user);

        sessionService.revokeAll(user.getId(), null, "Account deactivated: " + reason);

        emailService.sendAccountDeactivated(user.getEmail(), user.getFullName(), reason);

        log.info("Account {} deactivated by {}: {}",
                user.getPublicId(), CurrentUser.usernameOrSystem(), reason);
    }

    private boolean isOptionalLocalRole(String code) {
        return List.of("CENTRE_PHARMACY", "CENTRE_LABORATORY", "CENTRE_HIM").contains(code);
    }

    private boolean hasCapability(Center centre, String roleCode) {
        CapabilityType required = switch (roleCode) {
            case "CENTRE_PHARMACY" -> CapabilityType.PHARMACY;
            case "CENTRE_LABORATORY" -> CapabilityType.LABORATORY;
            case "CENTRE_HIM" -> CapabilityType.HIM;
            default -> null;
        };
        return required == null || capabilityRepository.isEnabled(centre.getId(), required);
    }

    private StaffUserResponse toResponse(Users user, Role role, Center centre, LocalDateTime expiresAt) {
        return new StaffUserResponse(
                user.getPublicId(), user.getUsername(), user.getEmail(), user.getFullName(),
                user.getStatus().name(), role.getCode(), role.getName(),
                centre == null ? null : centre.getPublicId(),
                centre == null ? null : centre.getName(),
                Boolean.TRUE.equals(user.getMfaEnabled()),
                user.getInvitedAt(), user.getInvitedBy(), expiresAt, user.getActivatedAt());
    }
}

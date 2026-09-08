package com.fnph.telepsychiatric.email;

import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Account emails: invitation, password reset, and the notices that tell someone
 * their account changed.
 *
 * Three rules hold for every message here.
 *
 * **No clinical detail, ever.** These reach an inbox that may be shared, synced
 * to a phone shown on a locked screen, or backed up by a mail provider outside
 * Nigeria. Nothing about a patient, a diagnosis, an appointment or a medication
 * appears in an email. Notifications that need clinical content say "you have an
 * update" and require an authenticated session to read it.
 *
 * **No password, ever.** An invitation carries a link that lets the recipient
 * choose their own. A password sent by email sits in that inbox and in the mail
 * server's storage for the life of the account, and neither is under FNPH's
 * control.
 *
 * **Sent asynchronously, and failure never blocks the operation.** Creating a
 * user account must not fail because a mail server timed out. The account is
 * created, the failure is logged, and the administrator can resend.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AccountEmailService {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("d MMMM yyyy 'at' HH:mm");

    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;

    @Value("${application.notification.email.from}")
    private String from;

    @Value("${application.notification.email.from-name}")
    private String fromName;

    @Value("${application.base-url}")
    private String baseUrl;

    @Value("${application.clinical.clinical-emergency-number}")
    private String emergencyNumber;

    /**
     * Sent when an administrator creates a staff or centre account.
     *
     * Not sent to patients. Patients do not receive an invitation: they enrol
     * themselves by verifying an existing FNPH EHR number, and an unsolicited
     * email naming someone as a patient of a neuropsychiatric hospital would
     * disclose their care to anyone with access to that inbox.
     */
    @Async
    public void sendInvitation(String toAddress, String recipientName, String roleName,
                               String centreName, String activationToken, LocalDateTime expiresAt,
                               String invitedByName) {
        Context context = baseContext();
        context.setVariable("recipientName", recipientName);
        context.setVariable("roleName", roleName);
        context.setVariable("centreName", centreName);
        context.setVariable("activationUrl", baseUrl + "/activate?token=" + activationToken);
        context.setVariable("expiresAt", expiresAt.format(STAMP));
        context.setVariable("invitedByName", invitedByName);

        send(toAddress, "Set up your FNPH Telepsychiatry account", "email/invitation", context);
    }

    @Async
    public void sendPasswordReset(String toAddress, String recipientName,
                                  String resetToken, LocalDateTime expiresAt, String requestIp) {
        Context context = baseContext();
        context.setVariable("recipientName", recipientName);
        context.setVariable("resetUrl", baseUrl + "/reset-password?token=" + resetToken);
        context.setVariable("expiresAt", expiresAt.format(STAMP));
        context.setVariable("requestIp", requestIp);

        send(toAddress, "Reset your FNPH Telepsychiatry password", "email/password-reset", context);
    }

    /**
     * Confirmation that a password changed.
     *
     * The point is not to be helpful. It is that a change the account holder did
     * not make is the only signal they will ever get that someone else is in
     * their account, and it has to arrive whether the change came from the user,
     * a reset link or an administrator.
     */
    @Async
    public void sendPasswordChanged(String toAddress, String recipientName,
                                    LocalDateTime changedAt, String changedFromIp) {
        Context context = baseContext();
        context.setVariable("recipientName", recipientName);
        context.setVariable("changedAt", changedAt.format(STAMP));
        context.setVariable("changedFromIp", changedFromIp);

        send(toAddress, "Your FNPH Telepsychiatry password was changed",
                "email/password-changed", context);
    }

    /** Sent on first sign-in from an unrecognised device. */
    @Async
    public void sendNewDeviceNotice(String toAddress, String recipientName,
                                    String deviceLabel, String ipAddress, LocalDateTime signedInAt) {
        Context context = baseContext();
        context.setVariable("recipientName", recipientName);
        context.setVariable("deviceLabel", deviceLabel);
        context.setVariable("ipAddress", ipAddress);
        context.setVariable("signedInAt", signedInAt.format(STAMP));

        send(toAddress, "New sign-in to your FNPH Telepsychiatry account",
                "email/new-device", context);
    }

    /** Sent when an administrator changes what an account may do. */
    @Async
    public void sendRolesChanged(String toAddress, String recipientName,
                                 String newPrimaryRole, String changedByName, String reason) {
        Context context = baseContext();
        context.setVariable("recipientName", recipientName);
        context.setVariable("newPrimaryRole", newPrimaryRole);
        context.setVariable("changedByName", changedByName);
        context.setVariable("reason", reason);

        send(toAddress, "Your FNPH Telepsychiatry access has changed",
                "email/roles-changed", context);
    }

    @Async
    public void sendAccountDeactivated(String toAddress, String recipientName, String reason) {
        Context context = baseContext();
        context.setVariable("recipientName", recipientName);
        context.setVariable("reason", reason);

        send(toAddress, "Your FNPH Telepsychiatry account has been deactivated",
                "email/account-deactivated", context);
    }

    private Context baseContext() {
        Context context = new Context();
        context.setVariables(Map.of(
                "serviceName", "FNPH Kaduna Telepsychiatry",
                "baseUrl", baseUrl,
                "emergencyNumber", emergencyNumber));
        return context;
    }

    private void send(String toAddress, String subject, String template, Context context) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(
                    message, MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED,
                    StandardCharsets.UTF_8.name());

            helper.setFrom(from, fromName);
            helper.setTo(toAddress);
            helper.setSubject(subject);
            helper.setText(templateEngine.process(template, context), true);

            mailSender.send(message);
            log.info("Sent '{}' to {}", subject, maskAddress(toAddress));
        } catch (Exception e) {
            // Never rethrow. The account operation has already succeeded and
            // must not be rolled back because a mail server was unreachable.
            log.error("Failed to send '{}' to {}: {}", subject, maskAddress(toAddress), e.getMessage());
        }
    }

    /** Logs must not become a directory of who holds an account here. */
    private String maskAddress(String address) {
        int at = address.indexOf('@');
        if (at <= 1) {
            return "***";
        }
        return address.charAt(0) + "***" + address.substring(at);
    }
}

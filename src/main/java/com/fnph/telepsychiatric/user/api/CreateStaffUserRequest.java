package com.fnph.telepsychiatric.user.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

@Schema(name = "CreateStaffUserRequest",
        description = """
                Creates a staff or centre account and emails an invitation.

                **Patient accounts cannot be created here.** Passing a PATIENT role is
                rejected. Patients enrol themselves by verifying an existing FNPH EHR
                number, and are never sent an unsolicited email, because an email naming
                someone as a patient of this hospital discloses their care to anyone with
                access to that inbox.

                No password is set or emailed. The invitation carries a single-use link
                and the recipient chooses their own.
                """)
public record CreateStaffUserRequest(

        @NotBlank(message = "A username is required")
        @Size(min = 3, max = 100)
        @Pattern(regexp = "^[a-zA-Z0-9._-]+$",
                 message = "Use letters, numbers, dots, underscores and hyphens only")
        @Schema(description = "Sign-in identifier. Lowercased on save.", example = "dr.bello",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String username,

        @NotBlank(message = "An email address is required")
        @Email(message = "That is not a valid email address")
        @Size(max = 100)
        @Schema(description = """
                Where the invitation is sent. Check it carefully: an address that reaches
                the wrong person hands them the account. It is only marked verified once
                the recipient follows the link, and a password reset is never sent to an
                unverified address for that reason.
                """,
                example = "a.bello@fnphkaduna.gov.ng", requiredMode = Schema.RequiredMode.REQUIRED)
        String email,

        @NotBlank(message = "A first name is required")
        @Size(max = 50)
        @Schema(example = "Aisha", requiredMode = Schema.RequiredMode.REQUIRED)
        String firstName,

        @NotBlank(message = "A last name is required")
        @Size(max = 50)
        @Schema(example = "Bello", requiredMode = Schema.RequiredMode.REQUIRED)
        String lastName,

        @Size(max = 20)
        @Schema(description = "Optional contact number.", example = "+2348012345678")
        String phoneNumber,

        @Size(max = 50)
        @Schema(description = "Hospital or centre staff number, where one exists.", example = "FNPH/2026/0184")
        String staffNumber,

        @NotBlank(message = "A role is required")
        @Schema(description = """
                The role this account holds, which also decides where the user lands
                after sign-in. Must not be PATIENT.

                A centre role additionally requires `centrePublicId`, and the three
                optional local roles (CENTRE_PHARMACY, CENTRE_LABORATORY, CENTRE_HIM)
                require that capability to be activated at that centre first.
                """,
                example = "DOCTOR", requiredMode = Schema.RequiredMode.REQUIRED)
        String primaryRoleCode,

        @Schema(description = "The centre this account belongs to. Required for a centre role, "
                + "rejected for any other.",
                example = "01M1X5FF5ZR2M84M6088QR0FGF", nullable = true)
        String centrePublicId,

        @NotBlank(message = "A reason is required")
        @Size(min = 10, max = 500, message = "Give a reason of at least 10 characters")
        @Schema(description = "Why this account is being created. Written to the audit trail with "
                + "the administrator's identity.",
                example = "New consultant psychiatrist joining the telepsychiatry rota from 15 September",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String reason
) {
}

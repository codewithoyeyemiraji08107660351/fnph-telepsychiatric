package com.fnph.telepsychiatric.helpdesk.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(name = "RaiseTicketRequest",
        description = """
                Raise a support request.

                **Do not use this for a medical emergency.** Choosing \
                `CLINICAL_CONCERN` sends an immediate reply with emergency guidance and \
                passes the ticket straight to the clinical coordination team. The \
                helpdesk does not answer clinical questions and cannot give medical \
                advice.
                """)
public record RaiseTicketRequest(

        @NotNull(message = "A category is required")
        @Schema(description = """
                What this is about.

                `CLINICAL_CONCERN` is anything about health, symptoms, medication or \
                treatment. It is escalated automatically and never answered by the \
                helpdesk: a support agent has no clinical permission and no clinical \
                training.
                """,
                allowableValues = {"ACCESS_AND_SIGN_IN", "ENROLMENT", "BOOKING", "PAYMENT",
                        "TECHNICAL_FAULT", "DOCUMENT_ACCESS", "CLINICAL_CONCERN", "OTHER"},
                example = "BOOKING", requiredMode = Schema.RequiredMode.REQUIRED)
        String category,

        @NotBlank(message = "A subject is required")
        @Size(max = 200)
        @Schema(example = "Cannot join my consultation",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String subject,

        @NotBlank(message = "Describe the problem")
        @Size(min = 10, max = 4000)
        @Schema(description = """
                What happened.

                **Please do not include medical details here.** Support staff handle \
                access, booking, payment and technical problems. Anything about your \
                health goes to the clinical team, and the right route for that is \
                `CLINICAL_CONCERN` or your appointment.
                """,
                example = "The join button is greyed out and it says the room is not open yet, "
                        + "but my appointment was at 10am.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String body,

        @Size(max = 50)
        @Schema(description = "The appointment reference, if this is about one.",
                example = "APT-K7M4NPQR", nullable = true)
        String appointmentReference,

        @Size(max = 50)
        @Schema(description = "The payment reference or RRR, if this is about one.",
                example = "FNPH-A7K2M9PQR4", nullable = true)
        String paymentReference,

        @Size(max = 50)
        @Schema(description = "The document issue number, if this is about one.",
                example = "RX-2610-K7M4NPQR", nullable = true)
        String documentNumber
) {
}

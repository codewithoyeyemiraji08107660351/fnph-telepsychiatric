package com.fnph.telepsychiatric.handler;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@Schema(name = "Error",
        description = """
                The single error shape every endpoint returns.

                Messages describe what the caller did wrong. They never expose stack
                traces, SQL, internal identifiers or whether a record exists but is
                not visible to this caller, because "not found" and "not yours" have
                to be indistinguishable or the API becomes a way to confirm that a
                given person is a patient here.
                """)
public class ErrorResponse {

    @Schema(description = "When the error occurred (UTC).", example = "2026-09-07T09:14:22.481")
    private LocalDateTime timestamp;

    @Schema(description = "HTTP status code.", example = "403")
    private int status;

    @Schema(description = "Short status label.", example = "Forbidden")
    private String error;

    @Schema(description = "What went wrong, in terms the caller can act on.",
            example = "The primary role must be one of the roles being assigned")
    private String message;

    @Schema(description = "The request path.", example = "/api/v1/admin/users/01M1X5FF5ZR2M84M6088QR0FGE/roles")
    private String path;

    @Schema(description = "Field-level validation failures, keyed by field name. "
            + "Present on 400 responses only.",
            example = "{\"reason\": \"Give a reason of at least 10 characters\"}",
            nullable = true)
    private Map<String, String> validationErrors;
}

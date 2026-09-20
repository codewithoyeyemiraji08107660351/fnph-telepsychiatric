package com.fnph.telepsychiatric.session.api;

import java.time.LocalDateTime;

public record PatientPasswordResetResponse(String token, LocalDateTime expiresAt) {
}

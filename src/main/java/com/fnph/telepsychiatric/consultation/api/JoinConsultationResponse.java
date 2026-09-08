package com.fnph.telepsychiatric.consultation.api;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(name = "JoinConsultation",
        description = """
                Everything the browser needs to open the call.

                Pass `roomUrl` and `token` to the Daily client. **Do not store the token \
                or reuse it.** It is a bearer credential for a live clinical consultation, \
                it is bound to this participant and this room, and it dies at the slot end.
                """)
public record JoinConsultationResponse(

        @Schema(example = "01M1X5FF5ZR2M84M6088QR0FGE")
        String consultationPublicId,

        @Schema(description = "The room to join.", example = "https://fnph.daily.co/fnph-apt-a7k2m9")
        String roomUrl,

        @Schema(description = """
                Short-lived meeting token, minted server-side.

                The Daily API key never reaches the browser: it creates rooms and mints \
                tokens on the hospital's account, and anyone who read it from the page \
                source could do the same. This token can do one thing, in one room, for \
                the length of one appointment.
                """)
        String token,

        @Schema(description = "Scheduled start (UTC).")
        LocalDateTime scheduledStart,

        @Schema(description = """
                Scheduled end (UTC). **Fixed.** Joining late does not move it: a patient \
                arriving five minutes late has twenty-five minutes, because extending this \
                session would shorten the next one.
                """)
        LocalDateTime scheduledEnd,

        @Schema(description = """
                Seconds left. Drive the countdown from this, and re-read it on reconnect.

                The server is the authority. It ends the session at the scheduled time \
                whatever the client shows, and the provider expires the room independently, \
                so a stale browser timer cannot extend anything.
                """,
                example = "1740")
        long remainingSeconds,

        @Schema(description = "Show the first warning at this many minutes remaining.",
                example = "15")
        int firstWarningMinutes,

        @Schema(description = "Show the second, more prominent warning at this many minutes.",
                example = "10")
        int secondWarningMinutes,

        @Schema(description = """
                True for the clinician, who may mute, eject and end the call. False for a \
                patient or centre.

                Only the clinician controls the session. A patient who could end it could \
                end it for the doctor.
                """,
                example = "false")
        boolean isOwner
) {
}

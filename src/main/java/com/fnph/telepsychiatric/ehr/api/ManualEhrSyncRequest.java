package com.fnph.telepsychiatric.ehr.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ManualEhrSyncRequest(
        @NotNull Direction direction,
        @NotBlank @Size(min = 5, max = 500) String reason
) {
    public enum Direction { FROM_IMPORT, TO_IMPORT }
}

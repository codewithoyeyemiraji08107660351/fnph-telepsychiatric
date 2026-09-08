package com.fnph.telepsychiatric.configuration;

import com.fnph.telepsychiatric.common.ImmutableEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Every change to a setting, append-only.
 *
 * The reason a consultation fee changed in March must still be readable in
 * December, together with what it was before. Without the previous value a
 * reconciliation dispute cannot be settled: nobody can say which fee was in
 * force on the day in question.
 */
@Entity
@Table(name = "configuration_changes")
@Getter
@Setter
public class ConfigurationChange extends ImmutableEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "configuration_id", nullable = false)
    private SystemConfiguration configuration;

    /** Denormalised so history survives even if a key is later retired. */
    @Column(name = "config_key", nullable = false, length = 100)
    private String configKey;

    @Column(name = "previous_value", length = 1000)
    private String previousValue;

    @Column(name = "new_value", nullable = false, length = 1000)
    private String newValue;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Column(name = "changed_by", nullable = false, length = 100)
    private String changedBy;

    @Column(name = "changed_at", nullable = false)
    private LocalDateTime changedAt;

    @Column(name = "effective_from", nullable = false)
    private LocalDateTime effectiveFrom;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;
}

package com.fnph.telepsychiatric.configuration;

import com.fnph.telepsychiatric.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One governed setting.
 *
 * Fees, timing values, thresholds and validity periods are governance
 * decisions that change without a release. Holding them in a properties file
 * would mean a fee change is a deployment, which in practice means it does not
 * happen and the wrong number stays live.
 */
@Entity
@Table(name = "system_configuration")
@Getter
@Setter
public class SystemConfiguration extends BaseEntity {

    @Column(name = "config_key", nullable = false, length = 100)
    private String configKey;

    @Column(name = "config_value", nullable = false, length = 1000)
    private String configValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "value_type", nullable = false, length = 20)
    private ConfigValueType valueType;

    @Column(name = "category", nullable = false, length = 50)
    private String category;

    @Column(name = "description", nullable = false, length = 500)
    private String description;

    /**
     * Bounds checked on every write. A consultation fee of zero or a session
     * length of four hours is a typo, and the moment to catch it is before it
     * reaches a patient-facing screen rather than in a reconciliation report a
     * month later.
     */
    @Column(name = "min_value", length = 50)
    private String minValue;

    @Column(name = "max_value", length = 50)
    private String maxValue;

    @Column(name = "allowed_values", length = 500)
    private String allowedValues;

    /**
     * Marks a value FNPH governance owns rather than operations.
     *
     * Does not block the change: an administrator with the permission can still
     * make it. It puts the requirement in front of them and records in the
     * reason that they were told, which is what an auditor asks about later.
     */
    @Column(name = "requires_governance", nullable = false)
    private Boolean requiresGovernance = false;

    @Column(name = "is_sensitive", nullable = false)
    private Boolean isSensitive = false;

    @Column(name = "effective_from", nullable = false)
    private LocalDateTime effectiveFrom;
}

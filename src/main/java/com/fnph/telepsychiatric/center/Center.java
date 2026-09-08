package com.fnph.telepsychiatric.center;

import com.fnph.telepsychiatric.common.SoftDeletableEntity;
import com.fnph.telepsychiatric.patient.CentrePatient;
import com.fnph.telepsychiatric.user.Users;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Entity
@Table(name = "centres")
@Getter
@Setter
public class Center extends SoftDeletableEntity {

    @Column(name = "code", unique = true, nullable = false, length = 20)
    private String code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "lga", length = 50)
    private String lga;

    @Column(name = "state", length = 50)
    private String state;

    @Column(name = "address", length = 255)
    private String address;

    @Column(name = "phone_number", length = 20)
    private String phoneNumber;

    @Column(name = "email", length = 100)
    private String email;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @OneToMany(mappedBy = "centre", cascade = CascadeType.ALL)
    private List<Users> staff = new ArrayList<>();

    @OneToMany(mappedBy = "centre", cascade = CascadeType.ALL)
    private List<CentrePatient> patients = new ArrayList<>();

    /**
     * Optional local roles, one row per capability. Replaces three booleans,
     * which recorded the answer and destroyed the review that produced it.
     */
    @OneToMany(mappedBy = "centre", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    private Set<CentreCapability> capabilities = new HashSet<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CentreStatus status = CentreStatus.SETUP;

    @Column(name = "activated_at")
    private LocalDateTime activatedAt;

    @Column(name = "activated_by", length = 100)
    private String activatedBy;

    @Column(name = "suspended_at")
    private LocalDateTime suspendedAt;

    @Column(name = "suspended_by", length = 100)
    private String suspendedBy;

    @Column(name = "suspend_reason", length = 500)
    private String suspendReason;

    @Column(name = "default_consultation_minutes", nullable = false)
    private Integer defaultConsultationMinutes = 30;
}

package com.fnph.telepsychiatric.center;

import com.fnph.telepsychiatric.common.BaseEntity;
import com.fnph.telepsychiatric.patient.CentrePatient;
import com.fnph.telepsychiatric.user.Users;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "centres")
@Getter
@Setter
public class Center extends BaseEntity {

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

    @Column(name = "wallet_balance", precision = 19, scale = 2)
    private BigDecimal walletBalance = BigDecimal.ZERO;

    @Column(name = "low_balance_threshold", precision = 19, scale = 2)
    private BigDecimal lowBalanceThreshold;

    @Column(name = "critical_balance_threshold", precision = 19, scale = 2)
    private BigDecimal criticalBalanceThreshold;

    @OneToMany(mappedBy = "centre", cascade = CascadeType.ALL)
    private List<Users> staff = new ArrayList<>();

    @OneToMany(mappedBy = "centre", cascade = CascadeType.ALL)
    private List<CentrePatient> patients = new ArrayList<>();

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "has_pharmacy_capability", nullable = false)
    private Boolean hasPharmacyCapability = false;

    @Column(name = "has_laboratory_capability", nullable = false)
    private Boolean hasLaboratoryCapability = false;

    @Column(name = "has_him_capability", nullable = false)
    private Boolean hasHimCapability = false;
}

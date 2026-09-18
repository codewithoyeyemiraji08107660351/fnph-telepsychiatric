package com.fnph.telepsychiatric.patient;

import com.fnph.telepsychiatric.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** Portal intake is separate from the authoritative offline EHR. */
@Entity
@Table(name = "patient_intakes")
@Getter @Setter
public class PatientIntake extends BaseEntity {
    @Column(name = "patient_id", nullable = false) private Long patientId;
    @Column(name = "payload", nullable = false, columnDefinition = "TEXT") private String payload;
    @Column(name = "appointment_public_id", length = 26) private String appointmentPublicId;
}

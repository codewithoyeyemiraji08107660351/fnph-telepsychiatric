package com.fnph.telepsychiatric.patient;
import com.fnph.telepsychiatric.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
@Entity @Table(name="patient_portal_profiles") @Getter @Setter
public class PatientPortalProfile extends BaseEntity {
    @Column(name="patient_id",nullable=false,unique=true) private Long patientId;
    @Column(length=30) private String phone;
    @Column(length=254) private String email;
    @Column(length=300) private String location;
}

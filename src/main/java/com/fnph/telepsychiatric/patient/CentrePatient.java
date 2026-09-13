package com.fnph.telepsychiatric.patient;

import com.fnph.telepsychiatric.appointment.CentreAppointment;
import com.fnph.telepsychiatric.center.Center;
import com.fnph.telepsychiatric.clinical.CentreVitals;
import com.fnph.telepsychiatric.common.SoftDeletableEntity;
import com.fnph.telepsychiatric.consultation.CentreConsultation;
import com.fnph.telepsychiatric.tenancy.TenantFilters;
import com.fnph.telepsychiatric.tenancy.TenantOwned;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Filter;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "centre_patients", uniqueConstraints = {
        @UniqueConstraint(name = "uk_centre_patients_centre_local_id",
                          columnNames = {"centre_id", "centre_patient_id"})
}, indexes = {
        @Index(name = "idx_centre_patients_centre", columnList = "centre_id")
})
@Getter
@Setter
@Filter(name = TenantFilters.CENTRE_TENANT, condition = TenantFilters.CONDITION)
public class CentrePatient extends SoftDeletableEntity implements TenantOwned {

    @Column(name = "centre_patient_id", nullable = false, length = 50)
    private String centrePatientId;

    @Column(name = "first_name", nullable = false, length = 50)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 50)
    private String lastName;

    @Column(name = "middle_name", length = 50)
    private String middleName;

    @Column(name = "date_of_birth", nullable = false)
    private LocalDate dateOfBirth;

    @Column(name = "gender", length = 10)
    private String gender;

    @Column(name = "phone_number", length = 20)
    private String phoneNumber;

    @Column(name = "email", length = 100)
    private String email;

    @Column(name = "address", length = 255)
    private String address;

    /**
     * Narrative only. A centre patient who also holds an FNPH record is still
     * treated strictly as a centre referral, and the offline FNPH record is
     * never linked or retrieved. This column must never be used as a join key
     * to Patient. A tenancy test asserts no query traverses it.
     */
    @Column(name = "fnph_ehr_number", length = 50)
    private String fnphEhrNumber;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "centre_id", nullable = false)
    private Center centre;

    @OneToMany(mappedBy = "centrePatient", cascade = CascadeType.ALL)
    private List<CentreAppointment> appointments = new ArrayList<>();

    @OneToMany(mappedBy = "centrePatient", cascade = CascadeType.ALL)
    private List<CentreConsultation> consultations = new ArrayList<>();

    @OneToMany(mappedBy = "centrePatient", cascade = CascadeType.ALL)
    private List<CentreVitals> vitalsRecords = new ArrayList<>();


    /**
     * Tenant key for isolation enforcement. Null means this row belongs to the
     * FNPH pathway rather than to a centre.
     */
    @Override
    public Long resolveCentreId() {
        return centre == null ? null : centre.getId();
    }
}

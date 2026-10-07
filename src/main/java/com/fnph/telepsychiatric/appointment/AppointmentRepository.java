package com.fnph.telepsychiatric.appointment;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Collection;

public interface AppointmentRepository extends JpaRepository<Appointment, Long> {

    Optional<Appointment> findByPublicId(String publicId);
    Optional<Appointment> findByReference(String reference);
    Optional<Appointment> findBySlotId(Long slotId);

    List<Appointment> findAllByPatientIdOrderByAppointmentDateDesc(Long patientId);

    /** The Hub Coordinator's queue: paid, waiting on a decision. */
    Page<Appointment> findAllByStatusOrderByAppointmentDateAsc(Status status,
                                                               Pageable pageable);

    long countByStatus(Status status);

    /** One day's appointments for the hub, with patient and doctor loaded. */
    @Query("""
           select a from Appointment a
           join fetch a.patient
           left join fetch a.doctor
           where a.appointmentDate >= :from and a.appointmentDate < :to
           order by a.appointmentDate asc
           """)
    List<Appointment> findDay(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * One doctor's consultations in the given states from a point in time,
     * soonest first, with the patient loaded for the worklist row.
     */
    @Query("""
           select a from Appointment a
           join fetch a.patient
           where a.doctor.id = :doctorId
             and a.status in :statuses
             and a.appointmentDate >= :from
           order by a.appointmentDate asc
           """)
    List<Appointment> findDoctorWorklist(@Param("doctorId") Long doctorId,
                                         @Param("statuses") java.util.Collection<Status> statuses,
                                         @Param("from") LocalDateTime from);

    /** Held appointments whose payment never completed. */
    @Query("""
           select a from Appointment a
           where a.status = com.fnph.telepsychiatric.appointment.Status.SLOT_HELD
             and a.heldUntil <= :now
           """)
    List<Appointment> findLapsedHolds(@Param("now") LocalDateTime now);

    @Query("""
           select a from Appointment a
           where a.doctor.id = :doctorId
             and a.status = com.fnph.telepsychiatric.appointment.Status.APPROVED
             and a.appointmentDate between :from and :to
           order by a.appointmentDate asc
           """)
    List<Appointment> findDoctorDay(@Param("doctorId") Long doctorId,
                                    @Param("from") LocalDateTime from,
                                    @Param("to") LocalDateTime to);

    /**
     * The Hub Coordinator's workflow board for a date window, with the team
     * fetched in the same query so the board costs one round trip.
     */
    @Query("""
           select a from Appointment a
           left join fetch a.patient
           left join fetch a.doctor
           left join fetch a.nurse
           left join fetch a.pharmacist
           left join fetch a.laboratoryTechnician
           left join fetch a.himOfficer
           left join fetch a.assignedRoom
           where a.appointmentDate >= :from and a.appointmentDate < :to
             and a.status not in :excluded
           order by a.appointmentDate desc
           """)
    List<Appointment> findHubWorkflow(@Param("from") LocalDateTime from,
                                      @Param("to") LocalDateTime to,
                                      @Param("excluded") Collection<Status> excluded);

    /** Names the consultations on one page of the hub activity feed, in one query. */
    @Query("""
           select a from Appointment a
           left join fetch a.patient
           where a.id in :ids
           """)
    List<Appointment> findWithPatientByIdIn(@Param("ids") Collection<Long> ids);

    /**
     * Whether a doctor already holds another approved or running consultation
     * that overlaps the given window. Used before moving a consultation to them.
     */
    @Query("""
           select count(a) > 0 from Appointment a
           where a.doctor.id = :doctorId
             and a.id <> :excludeId
             and a.status in (com.fnph.telepsychiatric.appointment.Status.APPROVED,
                              com.fnph.telepsychiatric.appointment.Status.IN_PROGRESS)
             and a.appointmentDate < :end
             and a.scheduledEndAt > :start
           """)
    boolean doctorBusy(@Param("doctorId") Long doctorId,
                       @Param("excludeId") Long excludeId,
                       @Param("start") LocalDateTime start,
                       @Param("end") LocalDateTime end);
}

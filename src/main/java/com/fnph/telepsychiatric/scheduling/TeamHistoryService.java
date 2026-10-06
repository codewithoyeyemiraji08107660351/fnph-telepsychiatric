package com.fnph.telepsychiatric.scheduling;

import com.fnph.telepsychiatric.appointment.Appointment;
import com.fnph.telepsychiatric.scheduling.AppointmentTeamEvent.ChangeSource;
import com.fnph.telepsychiatric.scheduling.AppointmentTeamEvent.TeamRole;
import com.fnph.telepsychiatric.security.CurrentUser;
import com.fnph.telepsychiatric.user.Users;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Records who holds each role on an appointment, and every change.
 *
 * Usage at every place the team is changed:
 * <pre>
 *   TeamSnapshot before = TeamSnapshot.of(appointment);
 *   ... change the appointment ...
 *   teamHistory.record(appointment, before, ChangeSource.ASSIGNMENT, reason);
 * </pre>
 *
 * Runs inside the caller's transaction, so a team change and its history row
 * commit or roll back together.
 */
@Service
@RequiredArgsConstructor
public class TeamHistoryService {

    private final AppointmentTeamEventRepository eventRepository;

    /** The team as it stands. Room is held as the entity, everyone else as a user. */
    public record TeamSnapshot(Users doctor, Users nurse, Users pharmacist,
                               Users laboratory, Users him, Room room) {

        public static TeamSnapshot of(Appointment a) {
            return new TeamSnapshot(a.getDoctor(), a.getNurse(), a.getPharmacist(),
                    a.getLaboratoryTechnician(), a.getHimOfficer(), a.getAssignedRoom());
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Appointment appointment, TeamSnapshot before,
                       ChangeSource source, String reason) {

        TeamSnapshot after = TeamSnapshot.of(appointment);
        LocalDateTime now = LocalDateTime.now();
        String actor = CurrentUser.usernameOrSystem();

        user(appointment, TeamRole.DOCTOR, before.doctor(), after.doctor(), source, reason, actor, now);
        user(appointment, TeamRole.NURSE, before.nurse(), after.nurse(), source, reason, actor, now);
        user(appointment, TeamRole.PHARMACIST, before.pharmacist(), after.pharmacist(), source, reason, actor, now);
        user(appointment, TeamRole.LABORATORY, before.laboratory(), after.laboratory(), source, reason, actor, now);
        user(appointment, TeamRole.HIM, before.him(), after.him(), source, reason, actor, now);

        Long fromRoom = before.room() == null ? null : before.room().getId();
        Long toRoom = after.room() == null ? null : after.room().getId();
        if (!Objects.equals(fromRoom, toRoom)) {
            save(appointment, TeamRole.ROOM, fromRoom, toRoom,
                    before.room() == null ? null : before.room().getCode(),
                    after.room() == null ? null : after.room().getCode(),
                    source, reason, actor, now);
        }
    }

    private void user(Appointment appointment, TeamRole role, Users from, Users to,
                      ChangeSource source, String reason, String actor, LocalDateTime now) {
        Long fromId = from == null ? null : from.getId();
        Long toId = to == null ? null : to.getId();
        if (Objects.equals(fromId, toId)) {
            return;
        }
        save(appointment, role, fromId, toId,
                from == null ? null : from.getFullName(),
                to == null ? null : to.getFullName(),
                source, reason, actor, now);
    }

    private void save(Appointment appointment, TeamRole role, Long fromId, Long toId,
                      String fromLabel, String toLabel, ChangeSource source,
                      String reason, String actor, LocalDateTime now) {
        AppointmentTeamEvent event = new AppointmentTeamEvent();
        event.setAppointmentId(appointment.getId());
        event.setTeamRole(role);
        event.setFromRefId(fromId);
        event.setToRefId(toId);
        event.setFromLabel(truncate(fromLabel, 150));
        event.setToLabel(truncate(toLabel, 150));
        event.setChangeSource(source);
        event.setReason(truncate(reason, 500));
        event.setChangedBy(actor);
        event.setChangedAt(now);
        eventRepository.save(event);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}

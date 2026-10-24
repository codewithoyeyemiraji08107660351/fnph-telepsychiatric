package com.fnph.telepsychiatric.scheduling;

import com.fnph.telepsychiatric.audit.AuditAction;
import com.fnph.telepsychiatric.audit.AuditService;
import com.fnph.telepsychiatric.configuration.ConfigurationKeys;
import com.fnph.telepsychiatric.configuration.ConfigurationService;
import com.fnph.telepsychiatric.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Publishing consultation days and generating their slots.
 *
 * <h2>A day is published whole or not at all</h2>
 *
 * Slots are generated from the publication in one transaction. A half-published
 * day shows a patient two available times out of sixteen and reads as a fully
 * booked clinic, which is worse than no day at all because nobody reports it.
 *
 * <h2>Capacity is rooms, not a number</h2>
 *
 * One slot per period per active consultation room. That makes capacity a real
 * thing rather than a figure somebody typed: four rooms means four concurrent
 * consultations, and taking a room out of service reduces tomorrow's capacity
 * without anyone editing a schedule.
 *
 * The patient picks a time. Which room they get is the Hub Coordinator's
 * decision at approval, and the room recorded on the slot is a provisional
 * allocation, not a promise.
 *
 * <h2>Withdrawing does not cancel what is booked</h2>
 *
 * A withdrawn day stops taking new bookings and leaves existing ones standing.
 * Cancelling a patient's confirmed appointment is a decision with a phone call
 * attached, not a side effect of tidying a schedule.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ScheduleService {

    private final SchedulePublicationRepository publicationRepository;
    private final SlotRepository slotRepository;
    private final RoomRepository roomRepository;
    private final ConfigurationService configuration;
    private final AuditService auditService;

    @Transactional
    public SchedulePublication publish(ScheduleAudience audience, LocalDate serviceDate,
                                       LocalTime windowStart, LocalTime windowEnd,
                                       boolean publishImmediately) {

        if (serviceDate.isBefore(com.fnph.telepsychiatric.common.HospitalClock.today())) {
            throw new SchedulingException("That date has passed");
        }
        if (!windowEnd.isAfter(windowStart)) {
            throw new SchedulingException("The window must end after it starts");
        }
        publicationRepository.findByAudienceAndServiceDate(audience, serviceDate)
                .ifPresent(existing -> {
                    // One grid per day. A withdrawn day is reopened, not rebuilt:
                    // its slots still carry the bookings that stood through the
                    // withdrawal.
                    throw new SchedulingException(existing.getStatus() == PublicationStatus.WITHDRAWN
                            ? "%s is already set up and was withdrawn. Reopen it from the list instead of building it again."
                                    .formatted(serviceDate)
                            : "A schedule already exists for %s. Use the existing day, or withdraw it first."
                                    .formatted(serviceDate));
                });

        // Per audience, so changing the Centre duration cannot silently move
        // FNPH appointments.
        int slotMinutes = configuration.getInt(audience == ScheduleAudience.FNPH_PATIENT
                ? ConfigurationKeys.SESSION_MINUTES_FNPH
                : ConfigurationKeys.SESSION_MINUTES_CENTRE);

        RoomType roomType = audience == ScheduleAudience.FNPH_PATIENT
                ? RoomType.PATIENT_SERVICE : RoomType.CENTRE_CONSULTATION;

        List<Room> rooms = roomRepository.findAllByRoomTypeAndIsActiveTrueOrderByCodeAsc(roomType);
        if (rooms.isEmpty()) {
            throw new SchedulingException(
                    "There are no active %s rooms, so this day would publish with no capacity."
                            .formatted(roomType));
        }

        SchedulePublication publication = new SchedulePublication();
        publication.setAudience(audience);
        publication.setServiceDate(serviceDate);
        publication.setWindowStart(windowStart);
        publication.setWindowEnd(windowEnd);
        publication.setSlotMinutes(slotMinutes);
        publication.setStatus(publishImmediately
                ? PublicationStatus.PUBLISHED : PublicationStatus.DRAFT);
        if (publishImmediately) {
            publication.setPublishedAt(LocalDateTime.now());
            publication.setPublishedBy(CurrentUser.usernameOrSystem());
        }
        SchedulePublication saved = publicationRepository.save(publication);

        List<Slot> generated = generateSlots(saved, rooms);

        if (generated.isEmpty()) {
            // A window shorter than one slot generates nothing and would
            // otherwise publish a day that exists, looks fine on the schedule
            // list, and shows a patient no times at all.
            throw new SchedulingException(
                    "That window is shorter than one %d minute consultation, so no slots "
                            .formatted(slotMinutes)
                            + "would be generated. Widen it or check the session length "
                            + "for this audience.");
        }

        saved.setSlotsGenerated(generated.size());
        publicationRepository.save(saved);

        auditService.record(AuditService.AuditEvent.builder()
                .action(AuditAction.RECORD_CREATED)
                .entityType("SchedulePublication")
                .entityId(saved.getId())
                .details("%s %s, %s to %s, %d slots across %d room(s)"
                        .formatted(audience, serviceDate, windowStart, windowEnd,
                                generated.size(), rooms.size()))
                .build());

        log.info("Published {} schedule for {}: {} slots across {} rooms",
                audience, serviceDate, generated.size(), rooms.size());
        return saved;
    }

    private List<Slot> generateSlots(SchedulePublication publication, List<Room> rooms) {
        List<Slot> slots = new ArrayList<>();
        LocalDate date = publication.getServiceDate();
        int minutes = publication.getSlotMinutes();

        // The window is hospital time; slots are stored in UTC.
        LocalDateTime periodStart = com.fnph.telepsychiatric.common.HospitalClock.toUtc(date, publication.getWindowStart());
        LocalDateTime windowEnd = com.fnph.telepsychiatric.common.HospitalClock.toUtc(date, publication.getWindowEnd());

        while (!periodStart.plusMinutes(minutes).isAfter(windowEnd)) {
            for (Room room : rooms) {
                Slot slot = new Slot();
                slot.setPublication(publication);
                slot.setStartAt(periodStart);
                slot.setEndAt(periodStart.plusMinutes(minutes));
                slot.setRoom(room);
                slot.setState(SlotState.AVAILABLE);
                slots.add(slotRepository.save(slot));
            }
            periodStart = periodStart.plusMinutes(minutes);
        }
        return slots;
    }

    @Transactional
    public SchedulePublication publishDraft(String publicationPublicId) {
        SchedulePublication publication = require(publicationPublicId);
        if (publication.getStatus() != PublicationStatus.DRAFT) {
            throw new SchedulingException("Only a draft can be published");
        }
        publication.setStatus(PublicationStatus.PUBLISHED);
        publication.setPublishedAt(LocalDateTime.now());
        publication.setPublishedBy(CurrentUser.usernameOrSystem());
        return publicationRepository.save(publication);
    }

    /**
     * Stops new bookings on a day. Existing ones stand.
     */
    @Transactional
    public SchedulePublication withdraw(String publicationPublicId, String reason) {
        SchedulePublication publication = require(publicationPublicId);

        if (reason == null || reason.isBlank()) {
            throw new SchedulingException("Say why the day is being withdrawn");
        }

        publication.setStatus(PublicationStatus.WITHDRAWN);
        publication.setWithdrawnAt(LocalDateTime.now());
        publication.setWithdrawReason(reason);
        publicationRepository.save(publication);

        // Only the untouched slots. A HELD or BOOKED slot belongs to a patient
        // who has already committed, and taking it away here would cancel their
        // appointment as a side effect of an administrative change.
        List<Slot> free = slotRepository.findAllByPublicationIdOrderByStartAtAsc(publication.getId())
                .stream().filter(s -> s.getState() == SlotState.AVAILABLE).toList();

        free.forEach(slot -> {
            slot.setState(SlotState.BLOCKED);
            slot.setBlockedReason(reason);
            slotRepository.save(slot);
        });

        long stillBooked = slotRepository.countByPublicationIdAndState(
                publication.getId(), SlotState.BOOKED);

        auditService.record(AuditService.AuditEvent.builder()
                .action(AuditAction.RECORD_UPDATED)
                .entityType("SchedulePublication")
                .entityId(publication.getId())
                .details("Withdrawn. %d slot(s) blocked, %d booking(s) left standing"
                        .formatted(free.size(), stillBooked))
                .reason(reason)
                .build());

        log.info("Withdrew {} for {}: {} blocked, {} bookings stand",
                publication.getAudience(), publication.getServiceDate(),
                free.size(), stillBooked);
        return publication;
    }

    /** Takes one slot out of service. Refuses if somebody already holds it. */
    @Transactional
    public Slot blockSlot(String slotPublicId, String reason) {
        Slot slot = slotRepository.findByPublicId(slotPublicId)
                .orElseThrow(() -> new SchedulingException("No such slot"));

        if (slot.getState() == SlotState.BOOKED || slot.getState() == SlotState.HELD) {
            throw new SchedulingException(
                    "That slot is taken. Cancel the appointment first, which is a decision "
                            + "with a phone call attached, not a schedule edit.");
        }
        slot.setState(SlotState.BLOCKED);
        slot.setBlockedReason(reason);
        return slotRepository.save(slot);
    }

    /**
     * Returns a blocked slot to the pool.
     *
     * On a withdrawn day this also reopens the day. Bookable times come only
     * from open days, so a slot unblocked on a withdrawn day used to stay
     * invisible to patients and to the Hub Coordinator's reschedule list: the
     * coordinator unblocked it, saw "Available", and still could not use it.
     * Only the slots that were unblocked become bookable; the rest stay blocked.
     *
     * Asking to unblock a slot that is already available is not an error. It
     * is what a double click or a stale list sends.
     */
    @Transactional
    public Slot unblockSlot(String slotPublicId) {
        Slot slot = slotRepository.findByPublicId(slotPublicId)
                .orElseThrow(() -> new SchedulingException("No such slot"));
        if (slot.getState() == SlotState.AVAILABLE) {
            reopenIfWithdrawn(slot.getPublication(), "Reopened by unblocking a slot");
            return slot;
        }
        if (slot.getState() != SlotState.BLOCKED) {
            throw new SchedulingException("That slot is taken, so there is nothing to unblock");
        }
        slot.setState(SlotState.AVAILABLE);
        slot.setBlockedReason(null);
        Slot saved = slotRepository.save(slot);
        reopenIfWithdrawn(slot.getPublication(), "Reopened by unblocking a slot");
        return saved;
    }

    /**
     * Opens a withdrawn day again: the day takes bookings and every slot the
     * withdrawal blocked returns to the pool. Slots blocked one at a time for
     * their own reason (a room fault, a doctor away) stay blocked.
     */
    @Transactional
    public SchedulePublication reopen(String publicationPublicId) {
        SchedulePublication publication = require(publicationPublicId);
        if (publication.getStatus() != PublicationStatus.WITHDRAWN) {
            throw new SchedulingException("Only a withdrawn day can be reopened");
        }
        if (publication.getServiceDate().isBefore(com.fnph.telepsychiatric.common.HospitalClock.today())) {
            throw new SchedulingException("That day has passed, so it cannot be reopened");
        }
        String withdrawReason = publication.getWithdrawReason();
        int unblocked = 0;
        for (Slot slot : slotRepository.findAllByPublicationIdOrderByStartAtAsc(publication.getId())) {
            boolean blockedByWithdrawal = slot.getState() == SlotState.BLOCKED
                    && (withdrawReason == null || withdrawReason.equals(slot.getBlockedReason()));
            if (blockedByWithdrawal) {
                slot.setState(SlotState.AVAILABLE);
                slot.setBlockedReason(null);
                slotRepository.save(slot);
                unblocked++;
            }
        }
        reopenIfWithdrawn(publication, "Reopened; %d slot(s) returned to the pool".formatted(unblocked));
        return publication;
    }

    private void reopenIfWithdrawn(SchedulePublication publication, String note) {
        if (publication == null || publication.getStatus() != PublicationStatus.WITHDRAWN) {
            return;
        }
        if (publication.getServiceDate().isBefore(com.fnph.telepsychiatric.common.HospitalClock.today())) {
            throw new SchedulingException("That day has passed, so it cannot be reopened");
        }
        String previousReason = publication.getWithdrawReason();
        publication.setStatus(PublicationStatus.PUBLISHED);
        publication.setPublishedAt(LocalDateTime.now());
        publication.setPublishedBy(CurrentUser.usernameOrSystem());
        // Cleared so the list stops calling an open day withdrawn. The audit
        // entry keeps the original reason.
        publication.setWithdrawnAt(null);
        publication.setWithdrawReason(null);
        publicationRepository.save(publication);

        auditService.record(AuditService.AuditEvent.builder()
                .action(AuditAction.RECORD_UPDATED)
                .entityType("SchedulePublication")
                .entityId(publication.getId())
                .details(note)
                .reason(previousReason == null ? null : "Previously withdrawn: " + previousReason)
                .build());
        log.info("Reopened {} for {}: {}", publication.getAudience(), publication.getServiceDate(), note);
    }

    @Transactional(readOnly = true)
    public List<SchedulePublication> publicationsBetween(LocalDate from, LocalDate to) {
        return publicationRepository.findAllByServiceDateBetweenOrderByServiceDateAsc(from, to);
    }

    @Transactional(readOnly = true)
    public List<Slot> slotsOf(String publicationPublicId) {
        return slotRepository.findAllByPublicationIdOrderByStartAtAsc(
                require(publicationPublicId).getId());
    }

    private SchedulePublication require(String publicId) {
        return publicationRepository.findByPublicId(publicId)
                .orElseThrow(() -> new SchedulingException("No such schedule"));
    }

    public static class SchedulingException extends RuntimeException {
        public SchedulingException(String message) {
            super(message);
        }
    }
}

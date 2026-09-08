package com.fnph.telepsychiatric.scheduling;

import com.fnph.telepsychiatric.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * A consultation room.
 *
 * The patient is told the room, never the doctor's name. Whether a room also
 * limits how many consultations can run at once is still an open question with
 * FNPH; modelling it as a resource now means the answer is a configuration
 * change rather than a rewrite of the scheduling engine.
 */
@Entity
@Table(name = "rooms")
@Getter
@Setter
public class Room extends BaseEntity {

    @Column(name = "code", nullable = false, length = 20)
    private String code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "room_type", nullable = false, length = 30)
    private RoomType roomType;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "capacity_notes", length = 255)
    private String capacityNotes;
}

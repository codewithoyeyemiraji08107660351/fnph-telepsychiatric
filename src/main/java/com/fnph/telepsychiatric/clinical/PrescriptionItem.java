package com.fnph.telepsychiatric.clinical;

import com.fnph.telepsychiatric.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "prescription_items", indexes = {
        @Index(name = "idx_prescription_items_parent", columnList = "prescription_id")
})
@Getter
@Setter
public class PrescriptionItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "prescription_id", nullable = false, foreignKey = @ForeignKey(name = "fk_prescription_items_parent"))
    private Prescription prescription;

    @Column(name = "medication", nullable = false, length = 255)
    private String medication;

    @Column(name = "strength", length = 100)
    private String strength;

    @Column(name = "frequency", nullable = false, length = 100)
    private String frequency;

    @Column(name = "duration", length = 100)
    private String duration;

    @Column(name = "instructions", columnDefinition = "TEXT")
    private String instructions;

    @Column(name = "sequence", nullable = false)
    private Integer sequence = 0;
}

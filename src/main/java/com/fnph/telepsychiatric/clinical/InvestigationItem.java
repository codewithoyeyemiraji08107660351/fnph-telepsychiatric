package com.fnph.telepsychiatric.clinical;

import com.fnph.telepsychiatric.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "investigation_items", indexes = {
        @Index(name = "idx_investigation_items_parent", columnList = "investigation_id")
})
@Getter
@Setter
public class InvestigationItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "investigation_id", nullable = false, foreignKey = @ForeignKey(name = "fk_investigation_items_parent"))
    private Investigation investigation;

    /** For example Full blood count, Liver function test, Serum prolactin. */
    @Column(name = "panel_name", nullable = false, length = 150)
    private String panelName;

    @Column(name = "panel_code", length = 50)
    private String panelCode;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "sequence", nullable = false)
    private Integer sequence = 0;
}

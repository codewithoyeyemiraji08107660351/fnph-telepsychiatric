package com.fnph.telepsychiatric.triage;

import com.fnph.telepsychiatric.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** A version of the triage questions. Versioned for the same reason as consent. */
@Entity
@Table(name = "triage_question_sets")
@Getter
@Setter
public class TriageQuestionSet extends BaseEntity {

    @Column(name = "version", nullable = false, length = 30)
    private String version;

    @Column(name = "audience", nullable = false, length = 20)
    private String audience;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "DRAFT";

    @Column(name = "effective_from")
    private LocalDateTime effectiveFrom;

    @Column(name = "retired_at")
    private LocalDateTime retiredAt;

    @OneToMany(mappedBy = "questionSet", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sequence ASC")
    private List<TriageQuestion> questions = new ArrayList<>();
}

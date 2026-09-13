package com.fnph.telepsychiatric.triage;

import com.fnph.telepsychiatric.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * One triage question and the answer that stops the journey.
 *
 * The stop answer is stored rather than assumed to be "yes". A question can be
 * worded so that either answer is the dangerous one: "can the patient take part
 * in a video conversation" stops on no, not on yes. Hard-coding yes would let a
 * badly worded question pass exactly the patients it was meant to catch.
 */
@Entity
@Table(name = "triage_questions")
@Getter
@Setter
public class TriageQuestion extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "question_set_id", nullable = false)
    private TriageQuestionSet questionSet;

    @Column(name = "sequence", nullable = false)
    private Integer sequence;

    @Column(name = "question_text", nullable = false, columnDefinition = "TEXT")
    private String questionText;

    /** YES or NO. Whichever answer means this patient must not proceed. */
    @Column(name = "stop_answer", nullable = false, length = 10)
    private String stopAnswer;

    @Column(name = "stop_reason", nullable = false, length = 200)
    private String stopReason;
}

package com.fnph.telepsychiatric.consultation;

import com.fnph.telepsychiatric.tenancy.UnscopedQuery;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CentreConsultationNoteRepository
        extends JpaRepository<CentreConsultationNote, Long> {

    /** The live version. Keyed by a consultation the caller already resolved. */
    @UnscopedQuery(value = UnscopedQuery.Reason.KEYED_BY_SCOPED_PARENT,
            detail = "The centre consultation is reached through a scoped lookup first")
    Optional<CentreConsultationNote>
    findFirstByCentreConsultationIdAndSupersededAtIsNullOrderByVersionDesc(
            Long centreConsultationId);

    @UnscopedQuery(value = UnscopedQuery.Reason.KEYED_BY_SCOPED_PARENT,
            detail = "Amendment history for an already-resolved consultation")
    List<CentreConsultationNote> findAllByCentreConsultationIdOrderByVersionDesc(
            Long centreConsultationId);
}
package com.fnph.telepsychiatric.audit;

import com.fnph.telepsychiatric.security.crypto.Tokens;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Walks the audit chain and reports where it breaks.
 *
 * This is the answer to the acceptance criterion about audit reconstruction. It
 * turns "the trail cannot be altered" from an assertion into something an
 * assessor can run and read the output of.
 *
 * Two independent guarantees sit under it. Database grants stop the application
 * altering the table at all. The chain detects alteration by anyone who got
 * past the grants, which in practice means someone holding database
 * credentials. Neither is sufficient alone.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditChainVerifier {

    private static final int BATCH = 500;

    private final AuditRepository auditRepository;

    @Transactional(readOnly = true)
    public VerificationResult verify() {
        List<Break> breaks = new ArrayList<>();
        String expectedPrevious = "GENESIS";
        long lastId = 0;
        long checked = 0;

        while (true) {
            List<AuditLog> batch =
                    auditRepository.findForChainVerification(lastId, PageRequest.of(0, BATCH));
            if (batch.isEmpty()) {
                break;
            }

            for (AuditLog entry : batch) {
                checked++;

                if (!expectedPrevious.equals(entry.getPreviousChainHash())) {
                    // A row was removed, or one was inserted out of order.
                    breaks.add(new Break(entry.getId(), entry.getPerformedAt().toString(),
                            "Previous hash does not match the row before it. A row has been "
                                    + "removed or inserted."));
                }

                String recomputed = Tokens.hash(String.join("|",
                        entry.getPreviousChainHash() == null ? "" : entry.getPreviousChainHash(),
                        nullSafe(entry.getUsername()),
                        nullSafe(entry.getAction()),
                        nullSafe(entry.getEntityType()),
                        String.valueOf(entry.getEntityId()),
                        nullSafe(entry.getOutcome()),
                        nullSafe(entry.getBeforeHash()),
                        nullSafe(entry.getAfterHash()),
                        String.valueOf(entry.getPerformedAt())));

                if (!recomputed.equals(entry.getChainHash())) {
                    // The row's own content no longer matches its hash.
                    breaks.add(new Break(entry.getId(), entry.getPerformedAt().toString(),
                            "Row content does not match its recorded hash. This row has been edited."));
                }

                expectedPrevious = entry.getChainHash();
                lastId = entry.getId();
            }
        }

        if (!breaks.isEmpty()) {
            log.error("Audit chain verification found {} break(s) across {} entries", breaks.size(), checked);
        }
        return new VerificationResult(breaks.isEmpty(), checked, breaks);
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    public record VerificationResult(boolean intact, long entriesChecked, List<Break> breaks) {
    }

    public record Break(Long entryId, String performedAt, String description) {
    }
}

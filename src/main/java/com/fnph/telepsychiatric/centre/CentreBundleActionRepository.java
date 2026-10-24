package com.fnph.telepsychiatric.centre;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CentreBundleActionRepository extends JpaRepository<CentreBundleAction, Long> {

    Optional<CentreBundleAction> findByCentreIdAndReceiptIdAndCapability(
            Long centreId, Long receiptId, CentreCapability capability);

    List<CentreBundleAction> findAllByCentreIdAndReceiptIdOrderByActedAtAsc(Long centreId, Long receiptId);
}

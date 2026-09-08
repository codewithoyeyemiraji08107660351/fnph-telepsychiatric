package com.fnph.telepsychiatric.centre;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CentreBundleReceiptRepository extends JpaRepository<CentreBundleReceipt, Long> {

    Optional<CentreBundleReceipt> findByPublicId(String publicId);
    Optional<CentreBundleReceipt> findByBundleId(Long bundleId);

    /** The incoming queue: delivered, not yet treated. */
    List<CentreBundleReceipt> findAllByTreatedAtIsNullOrderByDeliveredAtAsc();

    /** History: treated, most recent first. */
    List<CentreBundleReceipt> findAllByTreatedAtIsNotNullOrderByTreatedAtDesc();

    long countByTreatedAtIsNull();
}

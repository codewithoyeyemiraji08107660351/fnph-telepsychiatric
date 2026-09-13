package com.fnph.telepsychiatric.storage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface StorageDeletionRepository extends JpaRepository<StorageDeletion, Long> {

    boolean existsByStorageAreaAndStoragePath(StorageArea area, String path);

    @Query("""
           select d from StorageDeletion d
           where d.deletedAt is null and d.eligibleAt <= :now and d.attempts < 5
           """)
    List<StorageDeletion> findDue(@Param("now") LocalDateTime now);
}

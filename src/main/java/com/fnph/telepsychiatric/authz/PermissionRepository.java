package com.fnph.telepsychiatric.authz;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface PermissionRepository extends JpaRepository<Permission, Long> {

    Optional<Permission> findByCode(String code);

    List<Permission> findAllByOrderByModuleAscCodeAsc();

    List<Permission> findAllByModuleOrderByCodeAsc(String module);

    @Query("select distinct p.module from Permission p order by p.module")
    List<String> findAllModules();

    /** Permissions no role grants. Each one is either dead or an omission. */
    @Query("""
           select p from Permission p
           where not exists (select 1 from Role r join r.permissions rp where rp = p)
           """)
    List<Permission> findUngrantedPermissions();
}

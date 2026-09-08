package com.fnph.telepsychiatric.authz;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RoleRepository extends JpaRepository<Role, Long> {

    Optional<Role> findByCode(String code);

    Optional<Role> findByPublicId(String publicId);

    @EntityGraph(attributePaths = "permissions")
    Optional<Role> findWithPermissionsByCode(String code);

    List<Role> findAllByIsActiveTrueOrderByScopeAscNameAsc();

    List<Role> findAllByScopeAndIsActiveTrueOrderByNameAsc(RoleScope scope);

    List<Role> findAllByCodeIn(List<String> codes);

    /** Permission counts for the matrix overview, without loading the sets. */
    @Query("""
           select r.code as code, count(p.id) as total
           from Role r left join r.permissions p
           group by r.code
           """)
    List<PermissionCountView> countPermissionsPerRole();

    @Query("""
           select r from Role r
           where not exists (select 1 from Role r2 join r2.permissions p2 where r2 = r)
           """)
    List<Role> findRolesWithNoPermissions();

    @Query("select distinct p.code from Role r join r.permissions p where r.code = :code")
    List<String> findPermissionCodesByRoleCode(@Param("code") String code);

    interface PermissionCountView {
        String getCode();
        Long getTotal();
    }
}

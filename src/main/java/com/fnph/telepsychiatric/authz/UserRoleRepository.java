package com.fnph.telepsychiatric.authz;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRoleRepository extends JpaRepository<UserRole, UserRoleId> {

    List<UserRole> findAllByUserId(Long userId);

    Optional<UserRole> findByUserIdAndIsPrimaryTrue(Long userId);

    @Modifying
    @Query("delete from UserRole ur where ur.userId = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);

    /**
     * Every permission code a user holds, flattened across all their roles.
     *
     * One query rather than walking lazy collections in the authentication
     * filter. The filter runs on every request, so the cost of getting this
     * wrong is paid continuously.
     */
    @Query("""
           select distinct p.code
           from UserRole ur
             join ur.role r
             join r.permissions p
           where ur.userId = :userId and r.isActive = true
           """)
    List<String> findPermissionCodesByUserId(@Param("userId") Long userId);

    @Query("""
           select r.code from UserRole ur join ur.role r
           where ur.userId = :userId and r.isActive = true
           """)
    List<String> findRoleCodesByUserId(@Param("userId") Long userId);

    /**
     * The subset of a user's permissions that change nothing.
     *
     * This is the effective authority set during supervised access. Derived
     * from the isMutating flag rather than a hard-coded list, so a permission
     * added later is excluded by default, and the default is mutating.
     */
    @Query("""
           select distinct p.code
           from UserRole ur
             join ur.role r
             join r.permissions p
           where ur.userId = :userId and r.isActive = true and p.isMutating = false
           """)
    List<String> findNonMutatingPermissionCodesByUserId(@Param("userId") Long userId);
}

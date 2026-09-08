package com.fnph.telepsychiatric.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<Users, Long> {

    Optional<Users> findByUsernameIgnoreCase(String username);

    Optional<Users> findByEmailIgnoreCase(String email);

    Optional<Users> findByPublicId(String publicId);

    boolean existsByUsernameIgnoreCase(String username);

    boolean existsByEmailIgnoreCase(String email);

    Optional<Users> findByPatient_EhrNumber(String ehrNumber);

    @Query("""
           select u from Users u
             join UserRole ur on ur.userId = u.id
             join ur.role r
           where r.code = :roleCode and u.isActive = true and u.deleted = false
           """)
    List<Users> findActiveByRoleCode(@Param("roleCode") String roleCode);

    /**
     * Centre-scoped lookup. Every centre query is constrained to the
     * authenticated centre. Never expose a findByRole that crosses centres.
     */
    @Query("select u from Users u where u.centre.id = :centreId and u.deleted = false")
    List<Users> findAllByCentre(@Param("centreId") Long centreId);
}

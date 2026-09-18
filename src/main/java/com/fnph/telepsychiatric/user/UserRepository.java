package com.fnph.telepsychiatric.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
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

    /**
     * Staff and centre accounts only.
     *
     * A patient is a clinical record, not a directory entry, and this list is
     * used for assignment and support. Reading a patient goes through
     * patient.read, which is audited by patient.
     */
    @Query("""
           select u from Users u
           where u.patient is null
             and (:term is null
               or lower(u.username) like lower(concat('%', :term, '%'))
               or lower(u.firstName) like lower(concat('%', :term, '%'))
               or lower(u.lastName)  like lower(concat('%', :term, '%')))
             and (:status is null or u.status = :status)
           order by u.username asc
           """)
    List<Users> searchStaff(
            @Param("term") String term,
            @Param("status") UserStatus status,
            org.springframework.data.domain.Pageable pageable);

    Optional<Users> findByEmail(@Email @Size(max = 100) String email);

    Optional<Object> findByUsername(String username);
}

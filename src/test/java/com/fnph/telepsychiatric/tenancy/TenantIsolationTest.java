package com.fnph.telepsychiatric.tenancy;

import com.fnph.telepsychiatric.appointment.CentreAppointment;
import com.fnph.telepsychiatric.center.Center;
import com.fnph.telepsychiatric.center.CenterRepository;
import com.fnph.telepsychiatric.patient.CentrePatient;
import com.fnph.telepsychiatric.patient.CentrePatientRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * The isolation suite.
 *
 * This is the one that stays green or the build stops. A centre must not be
 * able to reach another centre's data by any route: a list query, a primary
 * key lookup with an altered identifier, an existence check, a count, or a
 * write.
 *
 * <h2>TEMPORARY: running against local MySQL, not a container</h2>
 *
 * The container form is the correct one and must be restored. Testcontainers
 * cannot reach Docker Desktop on this machine, and the question this file
 * exists to answer is whether a Hibernate filter reaches a derived query.
 * That is Java-side behaviour, identical on MySQL 8.0 and 8.4, so a local
 * instance answers it.
 *
 * What a local instance does NOT answer is the schema behaviour the original
 * comment was about: BIT columns, multiple NULLs under a unique index, check
 * constraints, InnoDB row locking. Restore the container before this file is
 * trusted for any of that, and do not commit it in this state.
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.DisplayName.class)
class TenantIsolationTest {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:mysql://localhost:3306/telepsychiatric_test"
                        + "?useSSL=false&allowPublicKeyRetrieval=true"
                        + "&serverTimezone=UTC&characterEncoding=UTF-8");
        registry.add("spring.datasource.username",
                () -> System.getProperty("test.db.username", "root"));
        registry.add("spring.datasource.password",
                () -> System.getProperty("test.db.password", ""));

        registry.add("application.security.jwt.secret-key",
                () -> "dGVzdC1zZWNyZXQta2V5LWZvci10ZXN0aW5nLW9ubHktMzJieXRlcy1taW5pbXVt");
        registry.add("application.security.encryption.key",
                () -> "dGVzdC1lbmNyeXB0aW9uLWtleS0zMi1ieXRlcyEhISE=");
        // The placeholders in application.yaml that carry no default. Spring
        // never imports .env, so under surefire none of these resolve. Supplied
        // by their raw names so the ${...} references resolve without needing to
        // know each internal property path.
        registry.add("DB_USERNAME", () -> System.getProperty("test.db.username", "root"));
        registry.add("DB_PASSWORD", () -> System.getProperty("test.db.password", ""));
        registry.add("MAIL_HOST", () -> "localhost");
        registry.add("MAIL_USERNAME", () -> "test");
        registry.add("MAIL_PASSWORD", () -> "test");
        registry.add("MAIL_FROM", () -> "noreply@test.local");
        registry.add("REMITA_BASE_URL", () -> "http://localhost:9999");
        registry.add("REMITA_API_KEY", () -> "test");
        registry.add("REMITA_API_TOKEN", () -> "test");
        registry.add("REMITA_MERCHANT_ID", () -> "test");
        registry.add("REMITA_SERVICE_TYPE_ID", () -> "test");
        registry.add("REMITA_WEBHOOK_SECRET", () -> "test");
    }

    @Autowired javax.sql.DataSource dataSource;
    @Autowired CenterRepository centreRepository;
    @Autowired CentrePatientRepository centrePatientRepository;

    private Long centreA;
    private Long centreB;
    private Long patientOfA;
    private Long patientOfB;

    @BeforeEach
    void seedTwoCentres() {
        TenantContext.set(TenantScope.hospital("test setup"));

        List<Center> centres = centreRepository.findAll();
        centreA = centres.get(0).getId();
        centreB = centres.get(1).getId();

        patientOfA = savePatient(centres.get(0), "A-0001", "Amina");
        patientOfB = savePatient(centres.get(1), "B-0001", "Binta");
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private Long savePatient(Center centre, String localId, String firstName) {
        CentrePatient patient = new CentrePatient();
        patient.setCentre(centre);
        patient.setCentrePatientId(localId);
        patient.setFirstName(firstName);
        patient.setLastName("Test");
        patient.setDateOfBirth(LocalDate.of(1990, 1, 1));
        patient.setIsActive(true);
        return centrePatientRepository.save(patient).getId();
    }

    // -----------------------------------------------------------------
    // Reads
    // -----------------------------------------------------------------

    @Test
    @DisplayName("01 a centre listing returns only its own rows")
    @Transactional
    void listReturnsOnlyOwnRows() {
        TenantContext.set(TenantScope.centre(centreA));

        List<CentrePatient> visible = centrePatientRepository.findAll();

        assertThat(visible).isNotEmpty();
        assertThat(visible).allSatisfy(p ->
                assertThat(p.getCentre().getId()).isEqualTo(centreA));
        assertThat(visible).noneMatch(p -> p.getId().equals(patientOfB));
    }

    @Test
    @DisplayName("02 an altered identifier does not return another centre's record")
    @Transactional
    void alteredIdentifierIsRefused() {
        // The acceptance criterion, and the reason findById is checked
        // explicitly: Hibernate filters do not apply to EntityManager.find(),
        // so the filter alone would let this straight through.
        TenantContext.set(TenantScope.centre(centreA));

        assertThatThrownBy(() -> centrePatientRepository.findById(patientOfB))
                .isInstanceOf(CrossTenantAccessException.class);
    }

    @Test
    @DisplayName("03 a centre can still read its own record by identifier")
    @Transactional
    void ownRecordIsReadable() {
        TenantContext.set(TenantScope.centre(centreA));

        assertThat(centrePatientRepository.findById(patientOfA))
                .isPresent()
                .get()
                .extracting(p -> p.getCentre().getId())
                .isEqualTo(centreA);
    }

    @Test
    @DisplayName("04 an existence check does not confirm another centre's record")
    @Transactional
    void existenceCheckDoesNotLeak() {
        // A bare exists that ignored the tenant would answer "yes, that record
        // exists" about another centre's patient, which is the disclosure the
        // 404 rule exists to prevent.
        TenantContext.set(TenantScope.centre(centreA));

        assertThat(centrePatientRepository.existsById(patientOfB)).isFalse();
        assertThat(centrePatientRepository.existsById(patientOfA)).isTrue();
    }

    @Test
    @DisplayName("05 a count reflects only the caller's centre")
    @Transactional
    void countIsScoped() {
        TenantContext.set(TenantScope.centre(centreA));
        long fromA = centrePatientRepository.count();

        TenantContext.set(TenantScope.centre(centreB));
        long fromB = centrePatientRepository.count();

        TenantContext.set(TenantScope.hospital("verifying the total"));
        long total = centrePatientRepository.count();

        assertThat(fromA).isPositive();
        assertThat(fromB).isPositive();
        assertThat(total).isGreaterThanOrEqualTo(fromA + fromB);
    }

    @Test
    @DisplayName("06 a derived query respects the tenant without being told to")
    @Transactional
    void derivedQueriesAreScoped() {
        // The point of enforcing in the repository base class: a query method
        // written later, by someone who has never heard of the filter, is
        // covered anyway.
        TenantContext.set(TenantScope.centre(centreA));

        assertThat(centrePatientRepository.findByCentrePatientId("B-0001")).isEmpty();
        assertThat(centrePatientRepository.findByCentrePatientId("A-0001")).isPresent();
    }

    @Test
    @DisplayName("06b a derived query is scoped on a session with no prior CRUD call")
    @Transactional
    void derivedQueryIsScopedWithoutAPriorCrudCall() {
        // Test 06 cannot distinguish two outcomes, because @BeforeEach runs
        // findAll and save inside this same transaction, and a Hibernate filter
        // enabled once stays enabled for the whole session. So 06 can pass
        // because the filter was switched on during setup rather than because
        // derived queries are covered.
        //
        // Production does not look like that. A controller whose first
        // repository call is findByPublicId opens a session where no overridden
        // CRUD method has run, and if the filter is only enabled inside those
        // methods it is never enabled at all.
        //
        // This clears the session first, which detaches everything and forces
        // the derived query to go to the database on its own terms.
        TenantContext.set(TenantScope.centre(centreA));

        var em = ((org.springframework.orm.jpa.EntityManagerHolder)
                org.springframework.transaction.support.TransactionSynchronizationManager
                        .getResource(dataSourceBackedEntityManagerFactory()))
                .getEntityManager();
        em.flush();
        em.clear();

        assertThat(centrePatientRepository.findByCentrePatientId("B-0001"))
                .as("a derived query on a session where no CRUD method has run must "
                        + "still be tenant scoped, or findByPublicId on any of the 43 "
                        + "controllers reaches another centre's rows")
                .isEmpty();
    }

    @Autowired jakarta.persistence.EntityManagerFactory entityManagerFactory;

    private jakarta.persistence.EntityManagerFactory dataSourceBackedEntityManagerFactory() {
        return entityManagerFactory;
    }

    // -----------------------------------------------------------------
    // Writes
    // -----------------------------------------------------------------

    @Test
    @DisplayName("07 a centre cannot write a row into another centre")
    @Transactional
    void writeIntoAnotherTenantIsRefused() {
        // The quieter risk. Reads are what everyone tests; a coordinator
        // posting a referral carrying someone else's centre id would create a
        // record inside another tenant.
        TenantContext.set(TenantScope.hospital("fetching the target centre"));
        Center other = centreRepository.findById(centreB).orElseThrow();

        TenantContext.set(TenantScope.centre(centreA));

        CentrePatient smuggled = new CentrePatient();
        smuggled.setCentre(other);
        smuggled.setCentrePatientId("SMUGGLED-1");
        smuggled.setFirstName("Should");
        smuggled.setLastName("NotSave");
        smuggled.setDateOfBirth(LocalDate.of(1990, 1, 1));
        smuggled.setIsActive(true);

        assertThatThrownBy(() -> centrePatientRepository.save(smuggled))
                .isInstanceOf(CrossTenantAccessException.class);
    }

    // -----------------------------------------------------------------
    // Scope behaviour
    // -----------------------------------------------------------------

    @Test
    @DisplayName("08 hospital staff legitimately see every centre")
    @Transactional
    void hospitalScopeIsUnrestricted() {
        // Not a loophole. The Hub Coordinator approves bookings from every
        // centre and the doctor consults for all of them.
        TenantContext.set(TenantScope.hospital("Hub Coordinator approving bookings"));

        assertThat(centrePatientRepository.findById(patientOfA)).isPresent();
        assertThat(centrePatientRepository.findById(patientOfB)).isPresent();
    }

    @Test
    @DisplayName("09 an unset context returns nothing rather than everything")
    @Transactional
    void unsetContextFailsClosed() {
        // The single most important property here. A bug that leaves the
        // context unset must produce an empty result, not a full one. Failing
        // open would be silent and total.
        TenantContext.clear();

        assertThat(centrePatientRepository.findAll()).isEmpty();
        assertThat(centrePatientRepository.count()).isZero();
    }

    @Test
    @DisplayName("10 a centre principal with no centre bound sees nothing")
    @Transactional
    void centrePrincipalWithoutCentreSeesNothing() {
        // Should be impossible, because role assignment refuses a centre role
        // on an account with no centre. If it happens anyway, deny.
        TenantContext.set(TenantScope.centre(null));

        assertThat(centrePatientRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("11 a patient principal reaches no centre data at all")
    @Transactional
    void patientScopeReachesNoCentreData() {
        TenantContext.set(TenantScope.none());

        assertThat(centrePatientRepository.findAll()).isEmpty();
        assertThat(centrePatientRepository.existsById(patientOfA)).isFalse();
    }

    @Test
    @DisplayName("12 widening the scope for a job restores it afterwards")
    @Transactional
    void scopeIsRestoredAfterWidening() {
        // A job that widens scope and leaks it into the surrounding request
        // would be a leak that only appears under load, which is the worst
        // kind to find.
        TenantContext.set(TenantScope.centre(centreA));

        long acrossAll = TenantContext.runAcrossAllCentres(
                "nightly reconciliation", () -> centrePatientRepository.count());

        long afterwards = centrePatientRepository.count();

        assertThat(acrossAll).isGreaterThan(afterwards);
        assertThat(TenantContext.current().centreId()).isEqualTo(centreA);
        assertThat(TenantContext.current().isConstrained()).isTrue();
    }

    // -----------------------------------------------------------------
    // Coverage: no tenant-owned entity may be left out
    // -----------------------------------------------------------------

    /**
     * The only two entities allowed to carry a tenant key without being tenant
     * filtered, each for a stated reason.
     *
     * <b>Users</b> — authentication has to find the account before any tenant
     * scope exists, so a filter here would make signing in impossible. Access
     * to other people's accounts is guarded by the {@code user.read} permission
     * instead, and no centre role holds it.
     *
     * <b>AuditLog</b> — append-only, and readable only by holders of
     * {@code audit.read}, which no centre role has. Filtering it would also
     * break the Central Administrator's cross-centre audit view, which is the
     * one place a cross-tenant attempt becomes visible.
     *
     * Anything else appearing here is an omission, not an exception.
     */
    private static final List<String> JUSTIFIED_EXCEPTIONS = List.of("Users", "AuditLog");

    @Test
    @DisplayName("13 every entity with a centre_id implements TenantOwned")
    void everyTenantTableIsEnforced() throws Exception {
        // Catches the real failure mode: someone adds a centre-owned entity in
        // six months and does not implement the interface, so it silently sits
        // outside every check in this file.
        var reflections = new org.reflections.Reflections("com.fnph.telepsychiatric");
        var entities = reflections.getTypesAnnotatedWith(jakarta.persistence.Entity.class);

        List<String> unenforced = entities.stream()
                .filter(type -> java.util.Arrays.stream(type.getDeclaredFields())
                        .anyMatch(f -> {
                            var jc = f.getAnnotation(jakarta.persistence.JoinColumn.class);
                            var c = f.getAnnotation(jakarta.persistence.Column.class);
                            return (jc != null && "centre_id".equals(jc.name()))
                                    || (c != null && "centre_id".equals(c.name()));
                        }))
                .filter(type -> !TenantOwned.class.isAssignableFrom(type))
                .map(Class::getSimpleName)
                .filter(name -> !JUSTIFIED_EXCEPTIONS.contains(name))
                .sorted()
                .toList();

        assertThat(unenforced)
                .as("these entities carry a tenant key but sit outside tenant enforcement. "
                        + "Either implement TenantOwned or add a justified exception with a reason.")
                .isEmpty();
    }

    @Test
    @DisplayName("14 no centre role can read the two justified exceptions")
    void justifiedExceptionsAreGuardedByPermission() {
        // The exceptions above are only safe because permission stops centre
        // roles reaching them. If that ever changes, the exception becomes a
        // hole, so it is asserted here rather than trusted to the comment.
        TenantContext.set(TenantScope.hospital("checking the matrix"));

        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        List<String> crossings = jdbc.queryForList("""
                SELECT CONCAT(r.code, ' -> ', p.code)
                FROM role_permission rp
                  JOIN roles r       ON r.id = rp.role_id
                  JOIN permissions p ON p.id = rp.permission_id
                WHERE r.scope = 'CENTRE'
                  AND p.code IN ('user.read', 'user.create', 'user.update', 'audit.read', 'audit.export')
                """, String.class);

        assertThat(crossings)
                .as("Users and AuditLog sit outside the tenant filter and are guarded by "
                        + "permission alone. No centre role may hold these.")
                .isEmpty();
    }
}
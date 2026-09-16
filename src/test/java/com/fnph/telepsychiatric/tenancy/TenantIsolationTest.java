package com.fnph.telepsychiatric.tenancy;

import com.fnph.telepsychiatric.center.Center;
import com.fnph.telepsychiatric.center.CenterRepository;
import com.fnph.telepsychiatric.centre.CentreReferral;
import com.fnph.telepsychiatric.centre.CentreReferralRepository;
import com.fnph.telepsychiatric.centre.ReferralStatus;
import com.fnph.telepsychiatric.centre.ReferralUrgency;
import com.fnph.telepsychiatric.notification.NotificationRepository;
import com.fnph.telepsychiatric.patient.CentrePatient;
import com.fnph.telepsychiatric.patient.CentrePatientRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.Query;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * The isolation suite.
 *
 * This is the one that stays green or the build stops. A centre must not be
 * able to reach another centre's data by any route: a list query, a primary
 * key lookup with an altered identifier, an existence check, a count, a
 * derived query, a named query, or a write.
 *
 * <h2>Runs against the Docker MySQL 8.4 instance</h2>
 *
 * Port 3307, schema {@code telepsychiatric_test}, user {@code fnph_app}: the
 * same server and the same version the application runs on. Overridable with
 * {@code -Dtest.db.port}, {@code -Dtest.db.username} and
 * {@code -Dtest.db.password}.
 *
 * Matching the production engine matters here rather than being a convenience.
 * BIT columns, multiple NULLs under a unique index, check constraints and
 * InnoDB row locking all differ between engines and versions, and several
 * assertions below depend on them.
 *
 * <h2>What this suite learned the hard way</h2>
 *
 * Tests 06 and 06b originally asserted that the Hibernate filter in
 * {@link TenantAwareRepository} covered derived queries without being told to.
 * It does not. Spring Data executes a derived query and an {@code @Query}
 * without passing through that class, so {@code applyTenantFilter} never runs.
 * Both tests failed against MySQL, returning another centre's patient, and the
 * fix was to name the centre in the query rather than to trust a filter nobody
 * had enabled.
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.DisplayName.class)
class TenantIsolationTest {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:mysql://localhost:"
                        + System.getProperty("test.db.port", "3307")
                        + "/telepsychiatric_test"
                        + "?useSSL=false&allowPublicKeyRetrieval=true"
                        + "&serverTimezone=UTC&characterEncoding=UTF-8");
        registry.add("spring.datasource.username",
                () -> System.getProperty("test.db.username", "fnph_app"));
        registry.add("spring.datasource.password",
                () -> System.getProperty("test.db.password", "apppass"));

        registry.add("application.security.jwt.secret-key",
                () -> "dGVzdC1zZWNyZXQta2V5LWZvci10ZXN0aW5nLW9ubHktMzJieXRlcy1taW5pbXVt");
        registry.add("application.security.encryption.key",
                () -> "dGVzdC1lbmNyeXB0aW9uLWtleS0zMi1ieXRlcyEhISE=");
        // The placeholders in application.yaml that carry no default. Spring
        // never imports .env.dev, so under surefire none of these resolve. Supplied
        // by their raw names so the ${...} references resolve without needing to
        // know each internal property path.
        registry.add("DB_USERNAME", () -> System.getProperty("test.db.username", "fnph_app"));
        registry.add("DB_PASSWORD", () -> System.getProperty("test.db.password", "apppass"));
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
    @Autowired jakarta.persistence.EntityManagerFactory entityManagerFactory;
    @Autowired CenterRepository centreRepository;
    @Autowired CentrePatientRepository centrePatientRepository;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Autowired CentreReferralRepository referralRepository;

    private Long centreA;
    private Long centreB;
    private Long patientOfA;
    private Long patientOfB;


    @BeforeEach
    void seedTwoCentres() {
        TenantContext.set(TenantScope.hospital("test setup"));

        // Delete first, outside the persistence context.
        //
        // Two tests in this class assert on behaviour that never touches the
        // database, so they carry no @Transactional and their seed rows commit.
        // Everything after them then collided on
        // uk_centre_patients_centre_local_id. Cleaning up front costs one
        // statement and removes the ordering dependency entirely, which is
        // better than making every test transactional and hoping nobody adds
        // one that is not.
        jdbc.update("DELETE FROM centre_patients WHERE centre_patient_id IN (?, ?, ?)",
                "A-0001", "B-0001", "SMUGGLED-1");

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
    @DisplayName("06 a derived query names the centre and is scoped by it")
    @Transactional
    void derivedQueriesAreScoped() {
        // This test used to assert that the filter covered derived queries
        // without being told to, and it failed: Spring Data executes a derived
        // query without passing through TenantAwareRepository, so no filter is
        // ever enabled and the query returned centre B's patient.
        //
        // Centre-local identifiers are unique per centre and not globally, so
        // an unscoped lookup on one is ambiguous as well as leaky.
        TenantContext.set(TenantScope.centre(centreA));

        assertThat(centrePatientRepository
                .findByCentreIdAndCentrePatientId(centreA, "B-0001")).isEmpty();
        assertThat(centrePatientRepository
                .findByCentreIdAndCentrePatientId(centreA, "A-0001")).isPresent();
    }

    @Test
    @DisplayName("06b a centre-named query is scoped with no session state from setup")
    @Transactional
    void derivedQueryIsScopedWithoutAPriorCrudCall() {
        // Test 06 alone cannot distinguish two outcomes, because @BeforeEach
        // runs findAll and save inside this same transaction and a Hibernate
        // filter enabled once stays enabled for the whole session. So 06 could
        // pass because setup switched a filter on rather than because the query
        // scoped itself.
        //
        // Clearing the session detaches everything and forces the query to the
        // database on its own terms, which is what a controller whose first
        // repository call is a lookup actually does.
        TenantContext.set(TenantScope.centre(centreA));

        var em = ((org.springframework.orm.jpa.EntityManagerHolder)
                org.springframework.transaction.support.TransactionSynchronizationManager
                        .getResource(entityManagerFactory))
                .getEntityManager();
        em.flush();
        em.clear();

        assertThat(centrePatientRepository
                .findByCentreIdAndCentrePatientId(centreA, "B-0001"))
                .as("the query's own predicate must scope it, with nothing carried over "
                        + "from setup")
                .isEmpty();
    }

    @Test
    @DisplayName("06c a name search does not reach other centres")
    @Transactional
    void nameSearchIsScoped() {
        // Wider than the altered-identifier case and needing no identifier at
        // all: an unscoped search on a common name tells a coordinator that a
        // named person is a psychiatric patient somewhere in the network.
        //
        // Both seeded patients share the surname "Test", so an unscoped search
        // returns two rows and a scoped one returns one.
        TenantContext.set(TenantScope.centre(centreA));

        List<CentrePatient> found = centrePatientRepository.search(centreA, "Test");

        assertThat(found).isNotEmpty();
        assertThat(found).allSatisfy(p ->
                assertThat(p.getCentre().getId()).isEqualTo(centreA));
        assertThat(found).noneMatch(p -> p.getId().equals(patientOfB));
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
    @DisplayName("10b a centre-only path refuses a principal with no centre")
    void requireCentreIdRefusesAnUnscopedPrincipal() {
        // The counterpart to 10. A filtered query returning nothing is right
        // for a list, and wrong for an endpoint that only makes sense for a
        // centre: an empty result there reads as a data problem rather than as
        // the wrong caller.
        TenantContext.set(TenantScope.hospital("Hub Coordinator on a centre-only path"));

        assertThatThrownBy(TenantContext::requireCentreId)
                .isInstanceOf(IllegalStateException.class);
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
     * Entities carrying a tenant key that are deliberately not tenant filtered.
     *
     * <b>Users</b> — authentication must find the account before any tenant
     * scope exists, so a filter here would make signing in impossible. Guarded
     * by {@code user.read}, which no centre role holds.
     *
     * <b>AuditLog</b> — append-only, guarded by {@code audit.read}. Filtering
     * it would also break the Central Administrator's cross-centre audit view,
     * the one place a cross-tenant attempt becomes visible.
     *
     * <b>Notification</b> — scoped explicitly instead: countUnread and
     * findInbox both carry the centre in their predicate. A filter here would
     * be worse than nothing, because notifyUser and notifyPatient leave
     * centre_id null by design and every personally addressed notification
     * would disappear from its owner's inbox. Asserted in 14b.
     *
     * <b>ConsentAcceptance</b> and <b>TriageResponse</b> — guarded by
     * {@code consent.read} and {@code triage.read}. Centre roles hold only
     * consent.accept and triage.submit.
     *
     * <b>WalletAlert</b> — guarded by {@code wallet.read_balance}, held by
     * Finance and the Central Administrator only. Module 3 states centres do
     * not see wallet amounts.
     *
     * <b>NotificationBroadcast</b> — guarded by {@code notification.send},
     * which no centre role holds.
     *
     * Anything else appearing here is an omission, not an exception.
     */
    private static final List<String> JUSTIFIED_EXCEPTIONS = List.of(
            "Users", "AuditLog", "Notification", "ConsentAcceptance",
            "TriageResponse", "WalletAlert", "NotificationBroadcast");

    @Test
    @DisplayName("13 every entity with a centre_id implements TenantOwned")
    @Transactional
    void everyTenantTableIsEnforced() {
        // Catches the real failure mode: someone adds a centre-owned entity in
        // six months and does not implement the interface, so it silently sits
        // outside every check in this file.
        var reflections = new org.reflections.Reflections("com.fnph.telepsychiatric");
        var entities = reflections.getTypesAnnotatedWith(jakarta.persistence.Entity.class);

        List<String> unenforced = entities.stream()
                .filter(type -> Arrays.stream(type.getDeclaredFields())
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
    @DisplayName("14 no centre role can read the permission-guarded exceptions")
    @Transactional
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
                  AND p.code IN ('user.read', 'user.create', 'user.update',
                                 'audit.read', 'audit.export',
                                 'consent.read', 'triage.read',
                                 'wallet.read_balance', 'notification.send')
                """, String.class);

        assertThat(crossings)
                .as("these entities sit outside the tenant filter and are guarded by "
                        + "permission alone. No centre role may hold these.")
                .isEmpty();
    }

    @Test
    @DisplayName("14b Notification's exception holds only while its queries name the centre")
    void notificationQueriesAreCentreScoped() {
        // Notification is the one exception that does not rest on permission:
        // every centre role holds notification.read_own. It rests on the inbox
        // queries scoping themselves. If either loses its centre predicate the
        // exception silently becomes a hole, so it is asserted rather than
        // trusted to the comment above.
        var inboxQueries = Arrays.stream(NotificationRepository.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("findInbox")
                        || m.getName().equals("countUnread"))
                .toList();

        assertThat(inboxQueries)
                .as("findInbox and countUnread must both exist on NotificationRepository")
                .hasSize(2);

        assertThat(inboxQueries).allSatisfy(m -> {
            Query query = m.getAnnotation(Query.class);
            assertThat(query)
                    .as("%s must be an explicit @Query, not a derived query", m.getName())
                    .isNotNull();
            assertThat(query.value())
                    .as("%s must constrain the centre", m.getName())
                    .contains("centreId");
        });
    }

    @Test
    @Transactional
    @DisplayName("15 a referral is invisible and uncountable from another centre")
    void referralLookupIsCentreScoped() {
        TenantContext.set(TenantScope.centre(centreA));
        CentreReferral referral = new CentreReferral();
        referral.setCentre(centreRepository.findById(centreA).orElseThrow());
        referral.setCentrePatient(
                centrePatientRepository.findById(patientOfA).orElseThrow());
        referral.setReferralReason("Follow-up after discharge");
        referral.setStatus(ReferralStatus.SUBMITTED);
        referral.setUrgency(ReferralUrgency.ROUTINE);
        referral.setReferralReason("Follow-up after discharge");
        // The service generates this on create. Saving the entity directly
        // skips that, and the column is NOT NULL.
        referral.setReference("TEST-REF-" + System.nanoTime());
        referral.setStatus(ReferralStatus.SUBMITTED);
        CentreReferral saved = referralRepository.save(referral);

        assertThat(referralRepository
                .findByCentreIdAndPublicId(centreA, saved.getPublicId()))
                .isPresent();
        assertThat(referralRepository
                .countByCentreIdAndStatus(centreA, ReferralStatus.SUBMITTED))
                .isEqualTo(1);

        // Centre B sees neither the row nor the count. The count matters
        // separately: /centres/me/utilisation showed every centre the whole
        // network's totals, which named nobody but disclosed how busy the
        // other 22 centres were.
        TenantContext.set(TenantScope.centre(centreB));
        assertThat(referralRepository
                .findByCentreIdAndPublicId(centreB, saved.getPublicId()))
                .isEmpty();
        assertThat(referralRepository
                .countByCentreIdAndStatus(centreB, ReferralStatus.SUBMITTED))
                .isZero();
    }
}
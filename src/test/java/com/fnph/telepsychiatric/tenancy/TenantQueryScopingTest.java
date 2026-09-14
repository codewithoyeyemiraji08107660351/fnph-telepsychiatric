package com.fnph.telepsychiatric.tenancy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.reflections.Reflections;
import org.springframework.core.ResolvableType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fails the build when a query on a tenant-owned entity does not name a centre
 * and does not say why.
 *
 * <h2>Why a static test rather than more behavioural ones</h2>
 *
 * TenantIsolationTest proves that the queries it exercises are scoped. It
 * cannot prove anything about the ones nobody thought to exercise, and those
 * are the dangerous ones: {@code findAllByTreatedAtIsNullOrderByDeliveredAtAsc}
 * returned every centre's care bundles for months with a passing test suite,
 * because no test called it.
 *
 * This test does not run a query or touch a database. It reads every repository
 * interface in the application and asks one question of each declared method:
 * does the centre appear, either as a parameter in the method name, as a
 * predicate in an {@code @Query}, or as an {@link UnscopedQuery} reason. A
 * method that answers none of the three is reported.
 *
 * The Hibernate filter in {@link TenantAwareRepository} is the reason this is
 * necessary. It is enabled inside the inherited CRUD methods and nowhere else,
 * so a derived query looks protected, reads as protected, and is not.
 */
class TenantQueryScopingTest {

    private static final String BASE = "com.fnph.telepsychiatric";

    @Test
    @DisplayName("01 every query on a tenant-owned entity names a centre or says why not")
    void everyQueryIsScopedOrJustified() {
        List<String> unscoped = new ArrayList<>();

        for (Class<?> repository : tenantOwnedRepositories()) {
            for (Method method : repository.getDeclaredMethods()) {
                if (method.isDefault() || method.isSynthetic()) {
                    continue;
                }
                if (namesCentre(method) || isJustified(method)) {
                    continue;
                }
                unscoped.add("%s#%s".formatted(
                        repository.getSimpleName(), method.getName()));
            }
        }

        assertThat(unscoped)
                .withFailMessage("""
                        %d queries on tenant-owned entities do not name a centre and carry \
                        no @UnscopedQuery reason. Each one returns rows from every centre \
                        when called by a centre principal.

                        Either add the centre to the signature, or annotate with \
                        @UnscopedQuery and a reason a reviewer can check.

                        %s""", unscoped.size(), String.join("\n", unscoped))
                .isEmpty();
    }

    @Test
    @DisplayName("02 every @UnscopedQuery gives a reason worth reading")
    void everyJustificationSaysSomething() {
        List<String> thin = new ArrayList<>();

        for (Class<?> repository : tenantOwnedRepositories()) {
            for (Method method : repository.getDeclaredMethods()) {
                UnscopedQuery annotation = method.getAnnotation(UnscopedQuery.class);
                if (annotation == null) {
                    continue;
                }
                // A reason short enough to be a shrug is not a reason. The
                // point of the annotation is that the next person can check the
                // claim, and "hospital only" cannot be checked.
                if (annotation.detail().isBlank() || annotation.detail().length() < 25) {
                    thin.add("%s#%s: \"%s\"".formatted(repository.getSimpleName(),
                            method.getName(), annotation.detail()));
                }
            }
        }

        assertThat(thin)
                .withFailMessage("These exceptions are asserted rather than explained:%n%s",
                        String.join("\n", thin))
                .isEmpty();
    }

    // -----------------------------------------------------------------

    private Set<Class<? extends Repository>> tenantOwnedRepositories() {
        Reflections reflections = new Reflections(BASE);
        Set<Class<? extends Repository>> all = reflections.getSubTypesOf(Repository.class);
        all.removeIf(candidate -> !candidate.isInterface());
        all.removeIf(candidate -> !ownsTenantData(candidate));
        assertThat(all)
                .withFailMessage("No tenant-owned repositories found. The scan is broken, "
                        + "and a broken scan passes silently, which is worse than a failure.")
                .isNotEmpty();
        return all;
    }

    /** True when the repository's entity carries a centre. */
    private boolean ownsTenantData(Class<?> repository) {
        Class<?> entity = ResolvableType.forClass(JpaRepository.class, repository)
                .getGeneric(0).resolve();
        return entity != null && TenantOwned.class.isAssignableFrom(entity);
    }

    /**
     * The centre appears in the method name or in the query text.
     *
     * {@code ByCentreId} matches; {@code ByCentrePatientId} does not, which is
     * correct, because a centre-patient identifier is not a centre and one of
     * those was how a patient's whole referral history became readable from the
     * wrong centre.
     */
    private boolean namesCentre(Method method) {
        if (method.getName().contains("ByCentreId")
                || method.getName().contains("AndCentreId")) {
            return true;
        }
        Query query = method.getAnnotation(Query.class);
        if (query == null) {
            return false;
        }
        String jpql = query.value();
        return jpql.contains(":centreId") || jpql.contains(".centre.id");
    }

    private boolean isJustified(Method method) {
        return method.getAnnotation(UnscopedQuery.class) != null;
    }
}
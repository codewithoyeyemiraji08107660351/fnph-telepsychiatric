package com.fnph.telepsychiatric.tenancy;

import com.fnph.telepsychiatric.authz.RoleScope;
import com.fnph.telepsychiatric.security.SecurityUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the filter that populates {@link TenantContext} from the principal.
 *
 * <h2>Why this exists separately from TenantIsolationTest</h2>
 *
 * Every test in TenantIsolationTest calls {@code TenantContext.set(...)}
 * directly. That proves a real and important thing: given a correct scope,
 * every tenant-owned query is constrained. It proves nothing about whether the
 * scope is correct, because the tests supply it themselves.
 *
 * The gap between those two statements is this filter, and the codebase has no
 * HTTP-level test of any kind. So a filter that resolved the wrong centre, ran
 * before authentication, or failed to clear on a pooled thread would leave all
 * nineteen isolation tests green and every centre's data reachable from
 * another centre's account.
 *
 * These tests drive the filter directly with a mock request and a mock chain.
 * No Spring context, so they run in milliseconds alongside
 * TenantQueryScopingTest.
 */
class TenantContextFilterTest {

    private final TenantContextFilter filter = new TenantContextFilter();

    @AfterEach
    void clearEverything() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    /**
     * Captures the scope as the request saw it, since the filter clears it
     * before returning and asserting afterwards would always see none().
     */
    private TenantScope scopeDuringRequest() throws ServletException, IOException {
        AtomicReference<TenantScope> seen = new AtomicReference<>();
        FilterChain chain = (req, res) -> seen.set(TenantContext.current());
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);
        return seen.get();
    }

    private void authenticateAs(RoleScope scope, Long centreId, String primaryRole) {
        SecurityUser user = SecurityUser.builder()
                .userId(1L)
                .publicId("01TESTPRINCIPAL0000000000")
                .username("test.user")
                .password("irrelevant")
                .displayName("Test User")
                .active(true)
                .scope(scope)
                .centreId(centreId)
                .primaryRole(primaryRole)
                .roles(Set.of(primaryRole))
                .permissions(Set.of())
                .build();

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        user, null, List.of(new SimpleGrantedAuthority("test.permission"))));
    }

    @Test
    @DisplayName("01 a centre principal is scoped to its own centre and constrained")
    void centrePrincipalIsScopedToItsCentre() throws Exception {
        authenticateAs(RoleScope.CENTRE, 42L, "CENTRE_HUB_COORDINATOR");

        TenantScope scope = scopeDuringRequest();

        assertThat(scope.centreId()).isEqualTo(42L);
        assertThat(scope.isConstrained()).isTrue();
    }

    @Test
    @DisplayName("02 a centre principal with no centre bound is denied, not widened")
    void centrePrincipalWithoutCentreIsDenied() throws Exception {
        // Role assignment is supposed to refuse a centre role on an account
        // with no centre. This asserts the behaviour if it happens anyway,
        // because "should be impossible" is how the nine cross-tenant paths
        // found in the audit were justified before they were found.
        authenticateAs(RoleScope.CENTRE, null, "CENTRE_PHARMACY");

        TenantScope scope = scopeDuringRequest();

        assertThat(scope.centreId()).isNull();
        assertThat(scope.isConstrained())
                .as("a centre principal with no centre must match nothing, never everything")
                .isTrue();
    }

    @Test
    @DisplayName("03 FNPH staff are unrestricted and the reason names the role")
    void fnphStaffAreUnrestricted() throws Exception {
        authenticateAs(RoleScope.FNPH, null, "HUB_COORDINATOR");

        TenantScope scope = scopeDuringRequest();

        assertThat(scope.unrestricted()).isTrue();
        // The reason lands in the audit trail, so it has to identify who
        // widened the scope rather than just that it was widened.
        assertThat(scope.reason()).contains("HUB_COORDINATOR");
    }

    @Test
    @DisplayName("04 a patient reaches no centre data")
    void patientIsConstrainedWithNoCentre() throws Exception {
        authenticateAs(RoleScope.PATIENT, null, "PATIENT");

        TenantScope scope = scopeDuringRequest();

        assertThat(scope.centreId()).isNull();
        assertThat(scope.isConstrained()).isTrue();
    }

    @Test
    @DisplayName("05 an unauthenticated request is constrained, not unrestricted")
    void unauthenticatedRequestMatchesNothing() throws Exception {
        // No authentication set. Covers the public endpoints: enrolment
        // lookup, document verification, the Remita webhook.
        TenantScope scope = scopeDuringRequest();

        assertThat(scope.isConstrained())
                .as("code with no authenticated caller must not silently gain every centre")
                .isTrue();
        assertThat(scope.centreId()).isNull();
    }

    @Test
    @DisplayName("06 the scope is cleared after the request")
    void scopeIsClearedAfterRequest() throws Exception {
        authenticateAs(RoleScope.CENTRE, 42L, "CENTRE_HUB_COORDINATOR");

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                (req, res) -> { });

        assertThat(TenantContext.current().centreId()).isNull();
    }

    @Test
    @DisplayName("07 the scope is cleared even when the request throws")
    void scopeIsClearedWhenTheChainThrows() {
        authenticateAs(RoleScope.CENTRE, 42L, "CENTRE_HUB_COORDINATOR");

        // The finally block is the whole control. Without it, any request that
        // throws leaves its centre on the thread, and the next request the
        // pool hands that thread to inherits it. The symptom is one centre
        // intermittently seeing another's data under load: rare,
        // load-dependent, and a disclosure every time.
        try {
            filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                    (req, res) -> {
                        throw new IllegalStateException("handler blew up");
                    });
        } catch (Exception expected) {
            // The exception is the point of the test.
        }

        assertThat(TenantContext.current().centreId())
                .as("a failed request must not leave its centre on the thread")
                .isNull();
    }

    @Test
    @DisplayName("08 a reused thread does not inherit the previous centre")
    void reusedThreadDoesNotInheritTheLastCentre() throws Exception {
        // Simulates what a servlet container's thread pool does: two requests,
        // same thread, different principals. This is the assertion that would
        // have caught a missing clear() before production rather than after.
        authenticateAs(RoleScope.CENTRE, 42L, "CENTRE_HUB_COORDINATOR");
        assertThat(scopeDuringRequest().centreId()).isEqualTo(42L);

        SecurityContextHolder.clearContext();
        authenticateAs(RoleScope.CENTRE, 77L, "CENTRE_PHARMACY");

        assertThat(scopeDuringRequest().centreId())
                .as("the second request must see its own centre, not the first's")
                .isEqualTo(77L);
    }

    @Test
    @DisplayName("09 the filter is ordered after Spring Security")
    void filterRunsAfterAuthentication() {
        // The @Order value is a magic number. If it moved ahead of Spring
        // Security, resolveScope() would find no principal on every request,
        // every centre user would silently see nothing, and no other test here
        // would notice: they all authenticate by hand.
        Order order = AnnotationUtils.findAnnotation(TenantContextFilter.class, Order.class);

        assertThat(order)
                .as("the filter must declare an explicit order relative to the security chain")
                .isNotNull();
        assertThat(order.value())
                .as("must run late enough that authentication has produced a principal")
                .isGreaterThan(Ordered.LOWEST_PRECEDENCE - 1000);
    }
}
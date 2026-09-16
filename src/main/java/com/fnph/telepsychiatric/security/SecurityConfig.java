package com.fnph.telepsychiatric.security;

import com.fnph.telepsychiatric.config.CorsProperties;
import com.fnph.telepsychiatric.supervision.SupervisionContextFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Web security.
 *
 * <h2>Authorisation lives on the methods, not on the paths</h2>
 *
 * Every non-public endpoint carries {@code @PreAuthorize} with the permission
 * it needs, checked against the permission matrix. This class decides only what
 * is public and what requires a valid token.
 *
 * <h2>Why the per-path role lists were removed</h2>
 *
 * They were a second source of truth for the same decision, and the two
 * disagreed in both directions.
 *
 * Endpoints that existed and were unreachable by anyone, because their paths
 * were absent from the list and fell through to {@code denyAll()}: all four
 * booking endpoints, so no patient could see or hold a slot; all five document
 * endpoints, so no patient could collect a prescription; all six patient-record
 * endpoints; three of the five review endpoints, so Pharmacy could see its
 * queue and not open or submit anything; both consent endpoints, because the
 * matcher said {@code /consents/**} and the controller publishes
 * {@code /consent/**}.
 *
 * Endpoints reachable only by the wrong role, because a coarse path rule
 * silently overrode a fine-grained permission: {@code /api/v1/clinical/**} was
 * limited to DOCTOR, which denied the Hub Coordinator the {@code vitals.read}
 * and {@code clinical_note.read} it holds and needs for a release check, and
 * denied the patient the {@code vitals.submit} that makes stage three of the
 * patient journey possible at all. {@code /api/v1/appointments/**} required
 * ROLE_PATIENT, which denied the Hub Coordinator the cancel, reschedule and
 * no-show endpoints that only it holds permissions for.
 *
 * A path matcher cannot see permissions, so it can only ever be a worse copy of
 * the matrix. It is not defence in depth: a coarse rule that silently overrides
 * a fine-grained one is not a second layer, it is a bug waiting for the next
 * endpoint. The invariant that makes this safe is enforced instead by
 * {@code EveryEndpointIsGuardedTest}, which fails the build if any mapped
 * method lacks a permission check.
 *
 * <h2>Order is load-bearing</h2>
 *
 * Spring Security takes the first matching rule and stops. Two consequences
 * worth knowing before anything in the list below is moved:
 *
 * The password change path is matched before {@code /api/v1/auth/**}, which is
 * public. Move it below and the endpoint that lets someone set a password
 * becomes reachable without one.
 *
 * The two health probes are matched before {@code /actuator/**}, which requires
 * ICT Support. Move them below and the container health check starts failing
 * with 401, which presents as a deployment that will not come up rather than as
 * a security rule.
 *
 * Record-level and tenant-level authorisation is enforced separately in the
 * repository layer. Menu or route visibility is never a control.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final AuthenticationProvider authenticationProvider;
    private final JwtAuthFilter jwtAuthFilter;
    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
    private final JwtAccessDeniedHandler jwtAccessDeniedHandler;
    private final CorsProperties corsProperties;

    /**
     * The only role named in this file. Actuator endpoints are not application
     * endpoints, carry no {@code @PreAuthorize}, and must not fall through to
     * {@code authenticated()} where a patient token would reach them.
     *
     * {@code hasRole} prepends {@code ROLE_}, which matches what
     * {@link SecurityUser#getAuthorities()} publishes: roles prefixed, and
     * permissions bare.
     */
    private static final String ICT_SUPPORT = "ICT_SUPPORT";

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        // An explicit list, never a wildcard. CorsProperties defaults to empty
        // and documents why: a wildcard with allowCredentials(true) lets any
        // site make authenticated requests against patient records. Spring
        // refuses that combination at startup, which is the behaviour we want,
        // and the empty default means a misconfigured deployment fails closed.
        config.setAllowedOrigins(corsProperties.getAllowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type",
                "X-Requested-With", "Accept", SupervisionContextFilter.HEADER));
        config.setExposedHeaders(List.of("Location"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // Stateless with a bearer token, so there is no session cookie
                // for a cross-site request to ride on and nothing for a CSRF
                // token to protect.
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .headers(headers -> headers
                                // No framing at all. Nothing here is meant to be
                                // embedded, and the consultation iframe embeds Daily
                                // rather than the other way round.
                                .frameOptions(frame -> frame.deny())
                                .contentTypeOptions(Customizer.withDefaults())

                                // A document verification URL carries a token in its
                                // path. Without this header, a browser following any
                                // outbound link from that page sends the whole URL,
                                // token included, to the destination site.
                                .referrerPolicy(referrer -> referrer.policy(
                                        ReferrerPolicyHeaderWriter.ReferrerPolicy
                                                .STRICT_ORIGIN_WHEN_CROSS_ORIGIN))

                                // No content security policy yet, deliberately. The
                                // consultation page embeds a Daily room, so frame-src
                                // has to allow the configured Daily domain, and getting
                                // that wrong presents as a consultation that will not
                                // load rather than as a policy error. Worth adding once
                                // the Daily domain is fixed, not before.

                                .httpStrictTransportSecurity(hsts -> hsts
                                        .includeSubDomains(true)
                                        .maxAgeInSeconds(31536000))
                        // includeSubDomains for a year commits every subdomain
                        // of this domain to HTTPS in every browser that sees the
                        // header, and it cannot be withdrawn early. Confirm the
                        // domain is FNPH's to make that commitment on before
                        // this reaches production.
                )
                .authorizeHttpRequests(auth -> auth

                        // ---------------------------------------------------------
                        // Matched first, deliberately.
                        //
                        // This path sits under /api/v1/auth/**, which is public
                        // below. Matched here so the endpoint that sets a
                        // password requires a token. Do not move it.
                        // ---------------------------------------------------------
                        .requestMatchers(
                                JwtAuthFilter.PASSWORD_CHANGE_PATH
                        ).authenticated()

                        // ---------------------------------------------------------
                        // Public
                        // ---------------------------------------------------------

                        // Sign-in, MFA and recovery.
                        .requestMatchers("/api/v1/auth/**").permitAll()

                        // Patient self-enrolment against the EHR snapshot. Public by
                        // necessity: the patient has no account yet. Protected by
                        // corroboration, contact verification and two-way rate
                        // limiting rather than by authentication.
                        .requestMatchers("/api/v1/enrolment/**").permitAll()

                        // Document verification. Returns issue number, dates and status
                        // only, never a patient name or clinical content, because a QR
                        // code found on the floor must not disclose that a named person
                        // is a patient here.
                        .requestMatchers("/api/v1/verify/**").permitAll()

                        // What a sign-in page needs before anyone has a token: the
                        // emergency number the triage stop screen must show, whether a
                        // second factor is required, the password length rule.
                        //
                        // GET only, so nothing under /public can be written to without
                        // a token by accident. The controller returns a hand-written
                        // allow-list rather than the configuration table: anything
                        // added there is readable by anyone who finds the URL.
                        .requestMatchers(HttpMethod.GET, "/api/v1/public/**").permitAll()

                        // Machine only. Signature and payload hash verified inside the
                        // handler, which is why authentication is not the control.
                        .requestMatchers("/api/v1/webhooks/**").permitAll()

                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        // ---------------------------------------------------------
                        // Operational surfaces. Not application endpoints, so they
                        // carry no @PreAuthorize and cannot fall through below.
                        // ---------------------------------------------------------

                        // The two probes only. Both return a bare status, because
                        // management.endpoint.health.show-details is never outside the
                        // dev profile.
                        //
                        // The full response names the database vendor, the mail host
                        // and port, the Redis version, the filesystem path and the
                        // free disk bytes. On a public address that is a reconnaissance
                        // report, and the Redis version alone is one lookup from a
                        // known-vulnerabilities list.
                        //
                        // readiness covers the database only. A queue being unreachable
                        // must not take the application out of service while
                        // consultations still work; a database being unreachable must.
                        .requestMatchers("/actuator/health/liveness",
                                "/actuator/health/readiness").permitAll()

                        // Everything else on the actuator, including the detailed
                        // health response, needs ICT Support.
                        .requestMatchers("/actuator/**").hasRole(ICT_SUPPORT)

                        // The API specification: every endpoint, every field, every
                        // permission name, for a psychiatric hospital.
                        //
                        // Reachable here so dev and staging work, and disabled in the
                        // prod profile through springdoc.api-docs.enabled and
                        // springdoc.swagger-ui.enabled, both of which are set.
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**",
                                "/swagger-ui.html").permitAll()

                        // ---------------------------------------------------------
                        // Everything else
                        //
                        // Requires a valid token and nothing more here. The permission
                        // check is the @PreAuthorize on the method, which is the only
                        // place the matrix is expressed.
                        //
                        // This line is why EveryEndpointIsGuardedTest is load-bearing
                        // rather than tidy. With no path rules above it, a new
                        // controller method with no @PreAuthorize is reachable by any
                        // authenticated caller, including a patient. That test is the
                        // only thing that catches it.
                        // ---------------------------------------------------------
                        .anyRequest().authenticated()
                )
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(jwtAuthenticationEntryPoint)
                        .accessDeniedHandler(jwtAccessDeniedHandler)
                )
                .authenticationProvider(authenticationProvider)
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
package com.fnph.telepsychiatric.security;

import com.fnph.telepsychiatric.config.CorsProperties;
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
     */
    private static final String ICT_SUPPORT = "ICT_SUPPORT";

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(corsProperties.getAllowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Requested-With", "Accept"));
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
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .contentTypeOptions(Customizer.withDefaults())
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31536000))
                )
                .authorizeHttpRequests(auth -> auth

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

                        // Machine only. Signature and payload hash verified inside the
                        // handler, which is why authentication is not the control.
                        .requestMatchers("/api/v1/webhooks/**").permitAll()

                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        // ---------------------------------------------------------
                        // Operational surfaces. Not application endpoints, so they
                        // carry no @PreAuthorize and cannot fall through below.
                        // ---------------------------------------------------------

                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/**").hasRole(ICT_SUPPORT)

                        // Disabled in the prod profile via springdoc configuration.
                        // Left reachable here so the dev and staging profiles work.
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()

                        // ---------------------------------------------------------
                        // Everything else
                        //
                        // Requires a valid token and nothing more here. The permission
                        // check is the @PreAuthorize on the method, which is the only
                        // place the matrix is expressed.
                        // ---------------------------------------------------------
                        .anyRequest().authenticated()
                )
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(jwtAuthenticationEntryPoint)
                        .accessDeniedHandler(jwtAccessDeniedHandler)
                )
                .authenticationProvider(authenticationProvider)
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
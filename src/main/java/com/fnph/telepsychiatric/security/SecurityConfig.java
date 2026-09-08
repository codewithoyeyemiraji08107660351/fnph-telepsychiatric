package com.fnph.telepsychiatric.security;

import com.fnph.telepsychiatric.config.CorsProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
 * Replaces the previous configuration, which carried matchers from a
 * ride-hailing project (ADMIN, DRIVER, PASSENGER). None of those roles exist
 * in this system, so every one of those rules was dead and the paths they
 * appeared to protect were protected by nothing.
 *
 * Path matchers here are a coarse first filter only. Record-level, tenant-level
 * and state-transition authorisation is enforced in the service layer with
 * @PreAuthorize and repository scoping. Menu or route visibility is never the
 * control.
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

    private static final String CENTRAL_ADMINISTRATOR = "CENTRAL_ADMINISTRATOR";
    private static final String HUB_COORDINATOR = "HUB_COORDINATOR";
    private static final String DOCTOR = "DOCTOR";
    private static final String PHARMACIST = "PHARMACIST";
    private static final String LABORATORY_TECHNICIAN = "LABORATORY_TECHNICIAN";
    private static final String NURSING = "NURSING";
    private static final String HIM = "HIM";
    private static final String FINANCE = "FINANCE";
    private static final String PATIENT = "PATIENT";
    private static final String CENTRE_HUB_COORDINATOR = "CENTRE_HUB_COORDINATOR";
    private static final String CENTRE_ASSISTANT_COORDINATOR = "CENTRE_ASSISTANT_COORDINATOR";
    private static final String CENTRE_PHARMACY = "CENTRE_PHARMACY";
    private static final String CENTRE_LABORATORY = "CENTRE_LABORATORY";
    private static final String CENTRE_HIM = "CENTRE_HIM";
    private static final String ICT_SUPPORT = "ICT_SUPPORT";
    private static final String HELPDESK = "HELPDESK";

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

                // Public. Enrolment, sign-in and recovery.
                .requestMatchers("/api/v1/auth/**").permitAll()

                // Patient self-enrolment against the EHR snapshot. Public by
                // necessity: the patient has no account yet. Protected by
                // corroboration, contact verification and two-way rate
                // limiting rather than by authentication.
                .requestMatchers("/api/v1/enrolment/**").permitAll()

                // Public document verification. Returns a minimal valid/expired
                // result only and never clinical content.
                .requestMatchers("/api/v1/verify/**").permitAll()

                // Machine only. Signature verified inside the handler.
                .requestMatchers("/api/v1/webhooks/**").permitAll()

                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.OPTIONS, "/**").permitAll()

                // Patient portal and mobile app. Centre and staff credentials
                // must be rejected here, which is asserted by test.
                .requestMatchers(
                        "/api/v1/patient-profile/**",
                        "/api/v1/triage/**",
                        "/api/v1/consents/**",
                        "/api/v1/vitals/**",
                        "/api/v1/payments/**",
                        "/api/v1/availability/**",
                        "/api/v1/appointments/**"
                ).hasRole(PATIENT)

                // Hub Coordinator
                .requestMatchers("/api/v1/hub/**")
                        .hasAnyRole(HUB_COORDINATOR, CENTRAL_ADMINISTRATOR)

                // Doctor clinical workspace
                .requestMatchers("/api/v1/clinical/**")
                        .hasAnyRole(DOCTOR, CENTRAL_ADMINISTRATOR)

                // Multidisciplinary review queues
                .requestMatchers("/api/v1/reviews/pharmacy/**")
                        .hasAnyRole(PHARMACIST, CENTRE_PHARMACY, CENTRAL_ADMINISTRATOR)
                .requestMatchers("/api/v1/reviews/laboratory/**")
                        .hasAnyRole(LABORATORY_TECHNICIAN, CENTRE_LABORATORY, CENTRAL_ADMINISTRATOR)

                // Preparation queues
                .requestMatchers("/api/v1/queues/nursing/**")
                        .hasAnyRole(NURSING, CENTRAL_ADMINISTRATOR)
                .requestMatchers("/api/v1/queues/him/**")
                        .hasAnyRole(HIM, CENTRE_HIM, CENTRAL_ADMINISTRATOR)

                .requestMatchers("/api/v1/support/**").authenticated()

                // Finance
                .requestMatchers("/api/v1/finance/**")
                        .hasAnyRole(FINANCE, CENTRAL_ADMINISTRATOR)

                // Centre workspace. Tenant scoping is enforced in the repository
                // layer, not here. This matcher only decides who may reach it.
                .requestMatchers("/api/v1/centres/**")
                        .hasAnyRole(CENTRE_HUB_COORDINATOR, CENTRE_ASSISTANT_COORDINATOR,
                                    CENTRE_PHARMACY, CENTRE_LABORATORY, CENTRE_HIM,
                                    CENTRAL_ADMINISTRATOR)

                // Administration. The path matcher is a coarse first filter; the
                // real decision is the @PreAuthorize permission check on each
                // method, which is why the matcher only requires authentication
                // rather than duplicating the permission list here.
                .requestMatchers("/api/v1/admin/**")
                        .hasAnyRole(CENTRAL_ADMINISTRATOR, ICT_SUPPORT, HELPDESK)

                // Every authenticated principal may ask who they are and manage
                // the devices signed into their own account.
                .requestMatchers("/api/v1/me").authenticated()
                .requestMatchers("/api/v1/sessions/**").authenticated()

                // ICT and helpdesk workspaces
                .requestMatchers("/api/v1/ict/**").hasAnyRole(ICT_SUPPORT, CENTRAL_ADMINISTRATOR)
                .requestMatchers("/api/v1/helpdesk/**").hasAnyRole(HELPDESK, CENTRAL_ADMINISTRATOR)

                // Shared authenticated surfaces
                .requestMatchers("/api/v1/consultations/**", "/api/v1/notifications/**",
                                 "/api/v1/support/**", "/api/v1/uploads/**").authenticated()

                .anyRequest().denyAll()
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

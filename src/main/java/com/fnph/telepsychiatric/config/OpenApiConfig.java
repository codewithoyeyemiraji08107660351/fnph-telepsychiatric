package com.fnph.telepsychiatric.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI document for the FNPH Telepsychiatry API.
 *
 * The specification is a deliverable, not a side effect. The web and mobile
 * clients are built against it, FNPH review it, and it is the artefact that
 * shows an assessor which permission guards which operation.
 *
 * Disabled entirely on the prod profile: an unauthenticated schema listing
 * every endpoint and its guard is a map for anyone probing the service.
 */
@Configuration
public class OpenApiConfig {

    @Value("${application.base-url}")
    private String baseUrl;

    @Bean
    public OpenAPI fnphOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("FNPH Kaduna Telepsychiatry API")
                        .version("v1")
                        .description("""
                                Backend for the Federal Neuropsychiatric Hospital Kaduna
                                telepsychiatry platform.

                                ## Three portals, one API

                                * **FNPH Core Engine** — hospital staff. The offline FNPH EHR
                                  remains the authoritative clinical record in phase one.
                                * **FNPH User Portal** — verified existing FNPH Kaduna patients,
                                  web and mobile.
                                * **Centre of Excellence** — 23 LGA centres, each fully isolated.
                                  The complete clinical record is held online.

                                ## Authorisation

                                Every operation is guarded by a **permission**, not a role name.
                                Roles are bundles of permissions held in the database, so moving
                                a capability between roles is a data change rather than a release.
                                Each operation below names the permission it requires.

                                Path-level rules are a coarse first filter only. Record-level,
                                tenant-level and state-transition checks are enforced in the
                                service layer on every request. A centre can never reach another
                                centre's data by any route, and an altered identifier returns a
                                denial rather than data.

                                ## Identifiers

                                Every resource is addressed by its `publicId`, a 26-character
                                ULID. Internal numeric keys are never exposed, so the API cannot
                                be walked to discover how many records exist.

                                ## Time

                                All timestamps are UTC, ISO-8601. Clients render West Africa Time.

                                ## Not for emergencies

                                This service excludes emergencies, severe agitation, acute
                                psychosis and immediate risk. Those are directed to physical or
                                emergency care.
                                """)
                        .contact(new Contact()
                                .name("FNPH Kaduna Telepsychiatry")
                                .email("support@fnphkaduna.gov.ng"))
                        .license(new License().name("Proprietary — Federal Neuropsychiatric Hospital, Kaduna")))
                .servers(List.of(
                        new Server().url(baseUrl).description("Current environment")))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("""
                                        Short-lived access token from `POST /api/v1/auth/login`.

                                        Expires in 15 minutes. Refresh with the rotated refresh
                                        token rather than extending the access token: a permission
                                        revoked by an administrator must stop working promptly, and
                                        a long-lived token would keep it alive.
                                        """)))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .tags(List.of(
                        new Tag().name("Session")
                                .description("Sign-in, refresh, and the current principal"),
                        new Tag().name("Administration — Roles & Permissions")
                                .description("""
                                        Inspect and assign the permission matrix. Read operations
                                        need `role.read`; assignment needs `role.assign`. Both are
                                        held only by the Central Administrator.
                                        """)));
    }
}

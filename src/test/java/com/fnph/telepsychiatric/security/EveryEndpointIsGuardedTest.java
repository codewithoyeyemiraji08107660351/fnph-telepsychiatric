package com.fnph.telepsychiatric.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SecurityConfig no longer restricts endpoints by path. The permission check is
 * the {@code @PreAuthorize} on the method, so a mapped method without one is
 * reachable by any authenticated principal, patients included.
 *
 * That is a safe arrangement only while the invariant holds, and an invariant
 * nothing checks is a convention. This checks it.
 *
 * Deliberately a classpath scan rather than {@code @SpringBootTest}: it needs
 * no database, runs in under a second, and therefore actually gets run.
 */
class EveryEndpointIsGuardedTest {

    /**
     * Controllers that are public in their entirety, each protected by
     * something other than a token.
     *
     * AuthController issues the tokens. EnrolmentController serves a patient
     * who has no account yet and is protected by corroboration, contact
     * verification and rate limiting. RemitaWebhookController is machine-only
     * and verifies a signature and payload hash inside the handler.
     */
    private static final Set<String> PUBLIC_CONTROLLERS = Set.of(
            "AuthController",
            "EnrolmentController",
            "RemitaWebhookController"
    );

    /**
     * Individual public methods on otherwise guarded controllers.
     *
     * Document verification answers a QR code scanned by a pharmacy that has no
     * account here. It returns issue number, dates and status only.
     */
    private static final Set<String> PUBLIC_METHODS = Set.of(
            "DocumentController#verify"
    );

    @Test
    @DisplayName("every mapped endpoint carries a permission check")
    void everyEndpointIsGuarded() throws ClassNotFoundException {
        List<String> unguarded = new ArrayList<>();

        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        for (BeanDefinition definition
                : scanner.findCandidateComponents("com.fnph.telepsychiatric")) {

            Class<?> controller = Class.forName(definition.getBeanClassName());
            String simpleName = controller.getSimpleName();

            if (PUBLIC_CONTROLLERS.contains(simpleName)) {
                continue;
            }
            if (AnnotatedElementUtils.hasAnnotation(controller, PreAuthorize.class)) {
                continue;
            }

            for (Method method : controller.getDeclaredMethods()) {
                // @GetMapping and friends are themselves annotated
                // @RequestMapping, so this one check covers all of them.
                if (!AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class)) {
                    continue;
                }
                if (PUBLIC_METHODS.contains(simpleName + "#" + method.getName())) {
                    continue;
                }
                if (!AnnotatedElementUtils.hasAnnotation(method, PreAuthorize.class)) {
                    unguarded.add(simpleName + "#" + method.getName());
                }
            }
        }

        assertThat(unguarded)
                .as("""
                    Path matchers no longer gate these. A mapped method with no \
                    @PreAuthorize is reachable by any authenticated principal, which \
                    on this system includes every patient. Add the permission from the \
                    matrix, or add the method to PUBLIC_METHODS with a comment saying \
                    what protects it instead.""")
                .isEmpty();
    }
}
package com.fnph.telepsychiatric.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.Optional;

/**
 * The session behind the access token on the current request.
 *
 * Kept as request attributes rather than on {@link SecurityUser}, which is an
 * immutable snapshot of the account and is not specific to one device.
 * Empty outside a request, and on requests that are not token-authenticated.
 */
public final class CurrentSession {

    private static final String ID = CurrentSession.class.getName() + ".id";
    private static final String PUBLIC_ID = CurrentSession.class.getName() + ".publicId";

    private CurrentSession() {
    }

    static void bind(HttpServletRequest request, Long id, String publicId) {
        request.setAttribute(ID, id);
        request.setAttribute(PUBLIC_ID, publicId);
    }

    /** Internal id, for queries such as "revoke every session except this one". */
    public static Optional<Long> id() {
        return attribute(ID, Long.class);
    }

    /** Public id, for comparison with values shown to the user. */
    public static Optional<String> publicId() {
        return attribute(PUBLIC_ID, String.class);
    }

    private static <T> Optional<T> attribute(String name, Class<T> type) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return Optional.empty();
        }
        Object value = attributes.getAttribute(name, RequestAttributes.SCOPE_REQUEST);
        return type.isInstance(value) ? Optional.of(type.cast(value)) : Optional.empty();
    }
}

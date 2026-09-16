package com.fnph.telepsychiatric.security;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import java.util.Optional;


public final class CurrentSession {
    private static final String ID = CurrentSession.class.getName() + ".id";
    private static final String PUBLIC_ID = CurrentSession.class.getName() + ".publicId";

    private CurrentSession() {}

    static void bind(HttpServletRequest request, Long id, String publicId) {
        request.setAttribute(ID, id);
        request.setAttribute(PUBLIC_ID, publicId);
    }

    public static Optional<Long> id() { return attribute(ID, Long.class); }
    public static Optional<String> publicId() { return attribute(PUBLIC_ID, String.class); }


    private static <T> Optional<T> attribute(String name, Class<T> type) {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs == null) return Optional.empty();
        Object value = attrs.getAttribute(name, RequestAttributes.SCOPE_REQUEST);
        return type.isInstance(value) ? Optional.of(type.cast(value)) : Optional.empty();
    }
}
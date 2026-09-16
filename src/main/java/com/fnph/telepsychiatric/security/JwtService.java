package com.fnph.telepsychiatric.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Rewritten against jjwt 0.12.x. The previous version used the 0.11 builder API
 * (parserBuilder, setClaims, setSubject) which is removed in 0.12.
 *
 * Changes beyond the API migration:
 *  - Access tokens are minutes, not 24 hours.
 *  - Refresh tokens are separate, carry a jti, and are typed so an access token
 *    cannot be presented as a refresh token or the reverse.
 *  - Tokens carry the role and, for centre staff, the centre id as claims, so
 *    tenancy is available without a database read on every request.
 *  - Parse failures are caught and logged rather than thrown out of the filter.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JwtService {

    public static final String CLAIM_TOKEN_TYPE = "typ";
    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_CENTRE_ID = "centreId";
    public static final String CLAIM_USER_ID = "uid";
    /** Public id of the UserSession the access token was minted for. */
    public static final String CLAIM_SESSION_ID = "sid";

    /** What the authentication filter needs from a valid access token. */
    public record AccessToken(String username, Long userId, String sessionPublicId) {
    }

    private final JwtProperties properties;

    private SecretKey signingKey() {
        byte[] keyBytes = Decoders.BASE64.decode(properties.getSecretKey());
        return Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * Claims carry the primary role and, for centre staff, the tenant id.
     *
     * The full permission set is deliberately NOT in the token. It would push
     * a 15-minute token past a comfortable header size, and worse, a permission
     * revoked by an administrator would keep working until the token expired.
     * Permissions are resolved per request from the database, which is a single
     * indexed query.
     *
     * The session id binds the token to one signed-in device. The filter
     * refuses the token once that session is revoked, expired or idle, so
     * signing a device out takes effect on its next request rather than when
     * the token runs out.
     */
    public String generateAccessToken(SecurityUser user, String sessionPublicId) {
        Objects.requireNonNull(sessionPublicId, "An access token must be bound to a session");
        Map<String, Object> claims = new HashMap<>();
        claims.put(CLAIM_TOKEN_TYPE, TokenType.ACCESS.name());
        claims.put(CLAIM_ROLE, user.getPrimaryRole());
        claims.put(CLAIM_USER_ID, user.getUserId());
        claims.put(CLAIM_SESSION_ID, sessionPublicId);
        if (user.getCentreId() != null) {
            claims.put(CLAIM_CENTRE_ID, user.getCentreId());
        }
        return build(claims, user.getUsername(), properties.getAccessTokenMinutes(), ChronoUnit.MINUTES, null);
    }

    /**
     * @param sessionId the UserSession identifier. Stored as jti so a single
     *                  device can be revoked without invalidating every session
     *                  the account holds.
     */
    public String generateRefreshToken(SecurityUser user, UUID sessionId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(CLAIM_TOKEN_TYPE, TokenType.REFRESH.name());
        claims.put(CLAIM_USER_ID, user.getUserId());
        return build(claims, user.getUsername(), properties.getRefreshTokenDays(), ChronoUnit.DAYS,
                sessionId == null ? null : sessionId.toString());
    }

    private String build(Map<String, Object> claims, String subject, long amount, ChronoUnit unit, String jti) {
        Instant now = Instant.now();
        Instant expiry = now.plus(amount, unit);
        var builder = Jwts.builder()
                .claims(claims)
                .subject(subject)
                .issuer(properties.getIssuer())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry));
        if (jti != null) {
            builder.id(jti);
        }
        return builder.signWith(signingKey()).compact();
    }

    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(signingKey())
                .requireIssuer(properties.getIssuer())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public String extractUsername(String token) {
        return parse(token).getSubject();
    }

    public String extractTokenId(String token) {
        return parse(token).getId();
    }

    /**
     * Parses and type-checks an access token.
     *
     * Empty for anything that is not a signed, unexpired ACCESS token from this
     * issuer. The session claim may be null on a token minted before sessions
     * were bound; the filter refuses those.
     */
    public Optional<AccessToken> parseAccessToken(String token) {
        try {
            Claims claims = parse(token);
            if (!TokenType.ACCESS.name().equals(claims.get(CLAIM_TOKEN_TYPE, String.class))) {
                return Optional.empty();
            }
            Object uid = claims.get(CLAIM_USER_ID);
            Object sid = claims.get(CLAIM_SESSION_ID);
            return Optional.of(new AccessToken(
                    claims.getSubject(),
                    uid instanceof Number n ? n.longValue() : null,
                    sid instanceof String value ? value : null));
        } catch (JwtException | IllegalArgumentException ex) {
            log.debug("Rejected token: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    public boolean isAccessTokenValid(String token, UserDetails userDetails) {
        try {
            Claims claims = parse(token);
            if (!TokenType.ACCESS.name().equals(claims.get(CLAIM_TOKEN_TYPE, String.class))) {
                return false;
            }
            return claims.getSubject().equals(userDetails.getUsername())
                    && claims.getExpiration().after(new Date());
        } catch (JwtException | IllegalArgumentException ex) {
            log.debug("Rejected token: {}", ex.getMessage());
            return false;
        }
    }

    /**
     * The token handed back between password and second factor.
     *
     * Typed CHALLENGE so it cannot be presented as an access token. It carries
     * no role or permission claims, and the security configuration only accepts
     * it on the MFA endpoints, so holding one without completing the challenge
     * grants nothing.
     */
    public String generateChallengeToken(SecurityUser user, long minutes) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(CLAIM_TOKEN_TYPE, TokenType.CHALLENGE.name());
        claims.put(CLAIM_USER_ID, user.getUserId());
        return build(claims, user.getUsername(), minutes, ChronoUnit.MINUTES, null);
    }

    public java.util.Optional<String> extractChallengeSubject(String token) {
        try {
            Claims claims = parse(token);
            if (!TokenType.CHALLENGE.name().equals(claims.get(CLAIM_TOKEN_TYPE, String.class))) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(claims.getSubject());
        } catch (JwtException | IllegalArgumentException ex) {
            return java.util.Optional.empty();
        }
    }

    public boolean isRefreshToken(String token) {
        try {
            return TokenType.REFRESH.name().equals(parse(token).get(CLAIM_TOKEN_TYPE, String.class));
        } catch (JwtException | IllegalArgumentException ex) {
            return false;
        }
    }

    public long getAccessTokenMinutes() {
        return properties.getAccessTokenMinutes();
    }
}

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
     */
    public String generateAccessToken(SecurityUser user) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(CLAIM_TOKEN_TYPE, TokenType.ACCESS.name());
        claims.put(CLAIM_ROLE, user.getPrimaryRole());
        claims.put(CLAIM_USER_ID, user.getUserId());
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

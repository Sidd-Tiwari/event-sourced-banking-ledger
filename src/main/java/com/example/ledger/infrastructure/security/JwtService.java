package com.example.ledger.infrastructure.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * IMPORTANT / HONEST SCOPE NOTE: this issues and validates HS256-signed JWTs
 * against a single symmetric secret from configuration. It deliberately does
 * NOT implement:
 *   - refresh tokens / rotation
 *   - a JWKS endpoint or asymmetric (RS256) signing for multi-service trust
 *   - key rotation (kid header + key lookup)
 *   - a real user/credential store (see AuthController - it issues tokens
 *     for any {username, role, customerId} triple with no password check)
 *
 * This is the right amount of security depth to demonstrate JWT validation,
 * RBAC, and object-level authorization end-to-end for a portfolio project.
 * In an interview, be upfront about this scope: "I implemented JWT
 * validation and RBAC myself to understand the mechanics; a production
 * system would sit behind a real IdP (Okta/Auth0/Keycloak) issuing
 * RS256 tokens with proper key rotation rather than hand-rolling this."
 */
@Component
public class JwtService {

    private final SecretKey key;
    private final Duration tokenTtl;

    public JwtService(@Value("${jwt.secret}") String secret,
                       @Value("${jwt.ttl-minutes:60}") long ttlMinutes) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException(
                    "jwt.secret must be set via the JWT_SECRET env var and be at least 32 chars long");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.tokenTtl = Duration.ofMinutes(ttlMinutes);
    }

    public String issueToken(String username, Role role, UUID customerId) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .subject(username)
                .claim("role", role.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(tokenTtl)))
                .issuer("event-sourced-ledger")
                .audience().add("event-sourced-ledger-api").and();
        if (customerId != null) {
            builder.claim("customerId", customerId.toString());
        }
        return builder.signWith(key).compact();
    }

    public Optional<AuthenticatedPrincipal> validate(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer("event-sourced-ledger")
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            String username = claims.getSubject();
            Role role = Role.valueOf(claims.get("role", String.class));
            String customerIdClaim = claims.get("customerId", String.class);
            UUID customerId = customerIdClaim != null ? UUID.fromString(customerIdClaim) : null;

            return Optional.of(new AuthenticatedPrincipal(username, role, customerId));
        } catch (JwtException | IllegalArgumentException e) {
            // Covers: expired, bad signature, wrong issuer, malformed token,
            // unknown role string. All treated identically as "not authenticated" -
            // we deliberately don't leak WHICH check failed to the client.
            return Optional.empty();
        }
    }

    public record AuthenticatedPrincipal(String username, Role role, UUID customerId) {}
}
